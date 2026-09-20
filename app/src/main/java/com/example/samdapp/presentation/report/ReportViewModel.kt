package com.example.samdapp.presentation.report

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.di.SlmReadbackEnabled
import com.example.samdapp.domain.report.ReportAudience
import com.example.samdapp.domain.slm.MAX_QUESTION_CHARS
import com.example.samdapp.domain.slm.SlmReadbackResult
import com.example.samdapp.domain.slm.SlmReadbackUseCase
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import com.example.samdapp.domain.usecase.CreateReferralUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ReportUiState(
    val isLoading: Boolean = true,
    val report: ClinicalReport? = null,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    val showReferralSheet: Boolean = false,
    val referralUrgency: UrgencyLevel = UrgencyLevel.ROUTINE,
    val referralReason: String = "",
    val isSubmittingReferral: Boolean = false,
    val referralErrorMessage: String? = null,
    val referralConfirmationMessage: String? = null,
    /** [com.example.samdapp.config.FeatureFlags.SLM_READBACK_ENABLED], injected rather than read
     *  inline so the guards below can be proved from both sides. See
     *  [com.example.samdapp.di.FeatureFlagModule]. */
    val readbackEnabled: Boolean = false,
    /**
     * PR-7. The read-back sheet's visibility, its one question, and what it is showing.
     *
     * **Deliberately not in a `SavedStateHandle` and deliberately not `rememberSaveable`.** A
     * generation cannot survive process death: the HTTP call is gone, the engine's device-side
     * mutex is gone, and the envelope the grounding and completeness gates read is gone with the
     * binding's own per-generation state. Restoring [ReadbackState.Generating] would restore a
     * spinner with nothing behind it, which is the broken state the nav-stack work exists to
     * prevent rather than an example of surviving one. So this state dies with the process and a
     * restore lands on a closed sheet over a re-assembled report, which is a correct screen. The
     * route itself stays ids-only ([com.example.samdapp.presentation.navigation.ReportRoute]
     * carries a case record id and nothing else) and is unchanged by this PR.
     */
    val showReadbackSheet: Boolean = false,
    val readbackQuestion: String = "",
    val readback: ReadbackState = ReadbackState.Idle,
) {
    val canSubmitReferral: Boolean get() = referralReason.isNotBlank() && !isSubmittingReferral

    /**
     * Whether the read-back entry point exists at all on this screen.
     *
     * Three conditions, and the flag is first because it is the one that must hold in every
     * shipped build today. The other two are the feature's own preconditions: there is a report to
     * restate, and a physician has committed a decision on it, which is the same
     * `report.kernelDecision != null` that `ApprovedRecordReader` requires before it will build a
     * snapshot. Checking approval here as well is not duplication of that gate, it is the
     * difference between a button that refuses when pressed and a button that is not offered; the
     * seam still refuses independently, and `NOT_APPROVED` still has its own copy for the case
     * where the decision is withdrawn between render and tap.
     *
     * Read by the screen to decide whether to render the control **at all**. Hidden, not disabled,
     * matching what the `VOICE_*` flags do on the consultation screen: a disabled control for a
     * feature that is off in every build is a dead affordance a worker learns to ignore.
     */
    val canOpenReadback: Boolean
        get() = canOpenReadback(
            flagEnabled = readbackEnabled,
            hasPhysicianDecision = report?.kernelDecision != null,
        )

    /** The Ask button. Blank is refused by the seam too; this stops the round trip. */
    val canAskReadback: Boolean
        get() = canAskReadback(readbackQuestion, readback, MAX_QUESTION_CHARS)
}

sealed interface ReportEffect {
    data class SharePdf(val uri: Uri) : ReportEffect
    data class ExportFailed(val message: String) : ReportEffect
}

/**
 * PR-7. Separate from [ReportReferralActions] because they are separate features on one screen,
 * and because a test that drives the read-back should not have to know the referral surface exists.
 */
@Stable
interface ReportReadbackActions {
    fun onOpenReadback()
    fun onDismissReadback()
    fun onReadbackQuestionChange(question: String)
    fun onAskReadback()
    fun onCancelReadback()
    fun onAskAnotherReadback()
    fun onRetryReadback()
}

@Stable
interface ReportReferralActions {
    fun onOpenReferralSheet()
    fun onDismissReferralSheet()
    fun onReferralUrgencyChange(urgency: UrgencyLevel)
    fun onReferralReasonChange(reason: String)
    fun onSubmitReferral()
    fun onDismissReferralConfirmation()
}

/**
 * Loads the assembled [ClinicalReport] for one case and drives the preview + PDF export + referral
 * (REQ-REF-01). The preliminary report is rendered for [ReportAudience.WORKER] (private ailments
 * redacted); the final report (Phase 5) is the same use case once a prescription has arrived.
 */
@HiltViewModel(assistedFactory = ReportViewModel.Factory::class)
class ReportViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
    private val assembleReportUseCase: AssembleReportUseCase,
    /**
     * **`Lazy`, so the exporter is built on the first export tap rather than with the screen.**
     * It is the one dependency here that needs an Android `Context`, and it is needed only when a
     * worker actually exports. Deferring it is the right shape on its own terms, and it is also
     * what lets the read-back state machine below be tested on the JVM: without it, constructing
     * this ViewModel in a unit test means constructing a `Context`, which cannot be done, and the
     * safety-critical transitions would have had to be tested somewhere other than where they
     * live. Hilt supports `Lazy` natively, so this is a one-word production change.
     */
    private val pdfExporter: dagger.Lazy<ReportPdfExporter>,
    private val createReferralUseCase: CreateReferralUseCase,
    private val slmReadbackUseCase: SlmReadbackUseCase,
    private val auditLogger: AuditLogger,
    private val authSession: AuthSession,
    @SlmReadbackEnabled private val readbackEnabled: Boolean,
) : ViewModel(), ReportReferralActions, ReportReadbackActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): ReportViewModel
    }

    private val _uiState = MutableStateFlow(ReportUiState(readbackEnabled = readbackEnabled))
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ReportEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            assembleReportUseCase(caseRecordId, ReportAudience.WORKER).fold(
                onSuccess = { report ->
                    _uiState.update { it.copy(isLoading = false, report = report, referralReason = report.referralReasonSuggestion) }
                    // H-17 (Build 1): only once the gate has actually resolved to "show" —
                    // a committed physician decision — not on a preliminary, pre-decision load.
                    // Never the drug name.
                    if (report.kernelDecision != null) {
                        val viewerRole = authSession.currentUser().first()?.role
                        auditLogger.log(
                            action = AuditAction.PRESCRIPTION_SURFACED_TO_WORKER,
                            caseRecordId = caseRecordId,
                            payload = auditPayload(
                                "kernelDecision" to report.kernelDecision.name,
                                "viewerRole" to viewerRole?.name,
                                "caseRecordId" to caseRecordId,
                            ),
                        )
                    }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Could not build report") } },
            )
        }
    }

    fun onExportPdf() {
        val report = _uiState.value.report ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            pdfExporter.get().export(report).fold(
                onSuccess = { uri ->
                    _uiState.update { it.copy(isExporting = false) }
                    auditLogger.log(
                        action = AuditAction.REPORT_EXPORTED,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("isFinal" to report.isFinal.toString()),
                    )
                    _effects.send(ReportEffect.SharePdf(uri))
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isExporting = false) }
                    _effects.send(ReportEffect.ExportFailed(e.message ?: "PDF export failed"))
                },
            )
        }
    }

    // ---------------------------------------------------------------- read-back (PR-7)

    /**
     * The one job a read-back runs on.
     *
     * **A field rather than a fire-and-forget launch, because cancellation is a requirement.** A
     * worker who taps away with a patient in front of them has to be able to abandon a generation,
     * and this is the handle that makes that possible. What cancelling does device-side is exact
     * and worth stating: the coroutine stops, so no chunk is delivered, nothing is displayed and
     * the seam produces no result; the binding's `finally` releases its mutex, so the next
     * read-back is not locked out; the binding cleared its envelope before it called, so no stale
     * identity or finish reason is left readable; and Retrofit cancels the OkHttp call, which
     * closes the socket.
     *
     * **It does not stop the GPU.** The generation on the service runs on to its token ceiling,
     * holding VRAM, and on a single-worker service it blocks the next request. That is not
     * something this layer can fix and it is not a gap in this PR: the interface's own KDoc says
     * so, and making cancellation real is the service's obligation via client-disconnect
     * detection, which lands with PR-5.
     */
    private var readbackJob: Job? = null

    override fun onOpenReadback() {
        // The flag is checked here as well as at the render site. The screen not drawing a button
        // is what a worker sees; this is what makes the use case unreachable rather than merely
        // unclicked, which is the same belt-and-braces TranscribeAudioUseCase applies to its own
        // flag: the guard has to hold for any caller, not just the one screen that has one today.
        if (!_uiState.value.canOpenReadback) return
        _uiState.update { it.copy(showReadbackSheet = true) }
    }

    override fun onDismissReadback() {
        // Dismissing while a generation is in flight cancels it. Tapping outside the sheet is the
        // "taps away" case and it must not leave a generation running against a closed surface.
        cancelReadback()
        _uiState.update {
            it.copy(showReadbackSheet = false, readbackQuestion = "", readback = ReadbackState.Idle)
        }
    }

    override fun onReadbackQuestionChange(question: String) =
        _uiState.update { it.copy(readbackQuestion = question) }

    override fun onAskReadback() {
        val current = _uiState.value
        // Two guards, and A TEST FOUND THE NEED FOR BOTH. The first version of this method had
        // neither: only the action that opens the sheet was gated, which left the action that
        // starts a generation reachable with the flag off. It was unreachable from the UI, because
        // the sheet is the only thing that calls it, and that is exactly the reasoning
        // TranscribeAudioUseCase's KDoc rejects for its own flag: the guard has to hold for any
        // caller, not only for the one screen that has one today.
        //
        // The sheet check is not redundant with the flag check. It is the stronger statement and it
        // holds even if the flag is ever turned on: a generation is never started for a surface
        // that is not open, so a stale action reference or a future second caller cannot begin one
        // behind a closed sheet, where the worker would never see its result and never be able to
        // stop it.
        if (!current.canOpenReadback) return
        if (!current.showReadbackSheet) return
        if (!current.canAskReadback) return
        val question = current.readbackQuestion
        // Before the launch, not inside it. The generating state has to be on screen from the
        // moment the request starts rather than from the first byte, and a coroutine that has been
        // launched has not necessarily been dispatched yet. There is no token trickle to wait for
        // either: the grounding gate withholds the whole generation until it completes.
        _uiState.update { it.copy(readback = ReadbackState.Generating) }
        readbackJob = viewModelScope.launch {
            val result = slmReadbackUseCase(caseRecordId, question)
            _uiState.update {
                it.copy(
                    readback = when (result) {
                        // The text is passed through exactly as the seam produced it. Section 6.2
                        // forbids meaning-level filtering, so there is no trimming, no truncation
                        // for layout and no highlighting here.
                        is SlmReadbackResult.Answer -> ReadbackState.Answer(result.text)
                        // Carries a reason and no text, because that is all Refused holds. There
                        // is no partial generation for this branch to leak.
                        is SlmReadbackResult.Refused -> ReadbackState.Refused(result.reason)
                    },
                )
            }
        }
    }

    override fun onCancelReadback() {
        cancelReadback()
        // Back to the question box with the question intact, so a worker who stopped because it
        // was taking too long can press Ask again without retyping.
        _uiState.update { it.copy(readback = ReadbackState.Idle) }
    }

    /**
     * Back to an EMPTY question box, and that emptiness is the single-turn rule at this layer.
     * The previous answer and the previous question are both gone: nothing from the last turn is
     * on screen, and nothing from it travels with the next one, because the seam takes a case id
     * and one question and has no field a transcript could arrive on.
     */
    override fun onAskAnotherReadback() =
        _uiState.update { it.copy(readbackQuestion = "", readback = ReadbackState.Idle) }

    /**
     * Re-run the same question. Offered only where [SlmRetryOffer.RETRY_NOW] is, which is the two
     * transport failures where no generation completed; a refusal caused by the answer's content
     * or length would produce the identical answer under a deterministic decode and is offered
     * [SlmRetryOffer.ASK_AGAIN] instead.
     */
    override fun onRetryReadback() {
        if (_uiState.value.readbackQuestion.isBlank()) return
        _uiState.update { it.copy(readback = ReadbackState.Idle) }
        onAskReadback()
    }

    private fun cancelReadback() {
        readbackJob?.cancel()
        readbackJob = null
    }

    // ---------------------------------------------------------------- referral

    override fun onOpenReferralSheet() = _uiState.update { it.copy(showReferralSheet = true) }
    override fun onDismissReferralSheet() = _uiState.update { it.copy(showReferralSheet = false) }
    override fun onReferralUrgencyChange(urgency: UrgencyLevel) = _uiState.update { it.copy(referralUrgency = urgency) }
    override fun onReferralReasonChange(reason: String) = _uiState.update { it.copy(referralReason = reason) }
    override fun onDismissReferralConfirmation() = _uiState.update { it.copy(referralConfirmationMessage = null) }

    override fun onSubmitReferral() {
        val current = _uiState.value
        val report = current.report ?: return
        if (!current.canSubmitReferral) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingReferral = true, referralErrorMessage = null) }
            createReferralUseCase(
                patientUid = report.header.patientUid,
                caseRecordId = caseRecordId,
                urgencyLevel = current.referralUrgency,
                reason = current.referralReason,
                sendingPhcId = report.header.phcName,
            ).fold(
                onSuccess = { referral ->
                    auditLogger.log(
                        action = AuditAction.REFERRAL_CREATED,
                        caseRecordId = caseRecordId,
                        payload = auditPayload(
                            "urgencyLevel" to referral.urgencyLevel.name,
                            "patientUid" to referral.patientUid,
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            isSubmittingReferral = false,
                            showReferralSheet = false,
                            referralConfirmationMessage =
                                "Referral sent — Patient UID ${referral.patientUid} queued for CHC/District Hospital appointment.",
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSubmittingReferral = false, referralErrorMessage = e.message ?: "Could not create referral") }
                },
            )
        }
    }
}
