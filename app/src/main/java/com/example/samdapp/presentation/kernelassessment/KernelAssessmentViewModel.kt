package com.example.samdapp.presentation.kernelassessment

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.assessment.AssessmentQueueScheduler
import com.example.samdapp.data.assessment.AssessmentWorkState
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.kernel.KernelFailure
import com.example.samdapp.domain.kernel.KernelTriageRules
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.connectivity.NetworkMonitor
import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.toCadreTier
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** One differential candidate. [scorePercent] is the model's raw score and is shown to
 *  [CadreTier.PHYSICIAN] only; [why] is the classifier's own explanation, which embeds its scores
 *  ("ranked #2 (31.2%) ... confidence boosted to 52.1%"), so it is physician-only as well. */
data class DifferentialLine(
    val label: String,
    val scorePercent: Int? = null,
    val why: String? = null,
)

/**
 * Unified view of "the AI assessment for this case" — sourced PRIMARILY from the real
 * `/api/v1/evaluate` output ([EvaluateReportOutput], no mock fallback of its own), falling back
 * to the old `/v1/assess`-derived [KernelReportOutput] (which DOES have a mock fallback) only
 * when no evaluate output exists for this case yet.
 */
data class AssessmentDisplay(
    val predictedCondition: String,
    val icdCode: String?,
    val confidencePercent: Int,
    val requiresHumanVerification: Boolean,
    val isMockFallback: Boolean,
    /** True when the real kernel call failed and this build had no fallback to offer
     *  ([InferenceSource.UNAVAILABLE] — always true in staging/prod on failure, since those
     *  flavors bind no mock). Distinguishes "the AI genuinely couldn't run" from a mock result,
     *  so the screen can show a retry affordance instead of a fabricated assessment. */
    val isUnavailable: Boolean,
    /** Why there is no assessment, when [isUnavailable] and the cause was classified. Null both
     *  on a successful display and on the two UNAVAILABLE states that are not failures (the
     *  kernel answered with an empty differential; no payload could be built). The screen turns
     *  this into copy via [kernelFailureCopy] and into a retry affordance via
     *  [KernelFailure.advice], so it is the enum that travels here, not a pre-rendered string:
     *  a ViewModel has no Context and must not resolve resources. */
    val failure: KernelFailure?,
    val sourceLabel: String,
    /** Per-candidate lines. Evaluate source: ICD candidate with its score and `why`. Kernel
     *  fallback: plain differential names (no per-candidate score/reasoning in that older
     *  contract shape). What is shown of each depends on the role, see the screen. */
    val differentialLines: List<DifferentialLine>,
    /** Role-neutral reasoning. Never carries the model score: it is rendered separately, by role. */
    val reasoningLines: List<String>,
    /** The classifier's own scored explanation of its top candidate. Physician-only. */
    val modelExplanationLines: List<String> = emptyList(),
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    /** Any EMERGENCY result, including the red flag. Shown with an emergency banner. */
    val isEmergency: Boolean = false,
    /** The classifier's rule-based red flag only. Its banner replaces the percentage bar, since
     *  its 1.0 is a literal, not a model probability. */
    val isCriticalVitalsFlag: Boolean = false,
)

private fun EvaluateReportOutput.toDisplay(): AssessmentDisplay {
    val summary = diagnosticSummary
    val top = summary.differential.firstOrNull { it.icdCandidate == summary.primaryIcdCandidate }
        ?: summary.differential.firstOrNull()
    return AssessmentDisplay(
        predictedCondition = summary.primaryAilmentName ?: "No primary candidate",
        icdCode = summary.primaryIcdCandidate,
        confidencePercent = ((top?.adjustedConfidence ?: 0.0) * 100).toInt(),
        requiresHumanVerification = safetyAndTriage.requiresHumanReview,
        isMockFallback = false,
        isUnavailable = false,
        failure = null,
        sourceLabel = "Real-time AI inference (/api/v1/evaluate)",
        differentialLines = summary.differential.map {
            DifferentialLine(it.icdCandidate, (it.adjustedConfidence * 100).toInt(), it.why)
        },
        reasoningLines = emptyList(),
        modelExplanationLines = listOfNotNull(top?.why),
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
    )
}

/** Shared with [KernelReportOutput.toDisplay]'s own [InferenceSource.UNAVAILABLE] branch below,
 *  so a stalled case (no row) and a written UNAVAILABLE row (a real one) can never read
 *  differently for what is the same clinical state. */
private const val UNAVAILABLE_SOURCE_LABEL = "Assessment unavailable: no AI result was produced"

/** Synthesized when no report row exists AND no assessment work is live for the case (stalled:
 *  enqueued work finished without ever writing a row, was cancelled, or was never enqueued).
 *  Deliberately identical in shape and wording to [KernelReportOutput]'s own
 *  [InferenceSource.UNAVAILABLE] rendering below — the same retryable state, not a second
 *  failure-looking one, for a case where not even a DB row exists to render it from. Shares its
 *  predictedCondition/reasoningLines text with [GenerateKernelReportUseCase]'s own written
 *  UNAVAILABLE row (that class's `UNAVAILABLE_PREDICTED_CONDITION`/`UNAVAILABLE_REASONING_SUMMARY`
 *  constants), not a second copy of the same wording. */
internal fun stalledDisplay(): AssessmentDisplay = AssessmentDisplay(
    predictedCondition = GenerateKernelReportUseCase.UNAVAILABLE_PREDICTED_CONDITION,
    icdCode = null,
    confidencePercent = 0,
    requiresHumanVerification = true,
    isMockFallback = false,
    isUnavailable = true,
    // No row exists, so no cause was ever classified. Reach-neutral copy, same as before.
    failure = null,
    sourceLabel = UNAVAILABLE_SOURCE_LABEL,
    differentialLines = emptyList(),
    reasoningLines = listOf(GenerateKernelReportUseCase.UNAVAILABLE_REASONING_SUMMARY),
    evidenceFor = emptyList(),
    evidenceAgainst = emptyList(),
)

internal fun KernelReportOutput.toDisplay(): AssessmentDisplay = AssessmentDisplay(
    predictedCondition = predictedCondition,
    icdCode = icdCode,
    confidencePercent = (confidenceScore * 100).toInt(),
    requiresHumanVerification = requiredHumanVerification,
    isMockFallback = inferenceSource == InferenceSource.MOCK_FALLBACK,
    isUnavailable = inferenceSource == InferenceSource.UNAVAILABLE,
    failure = failureCode,
    isEmergency = inferenceSource == InferenceSource.REAL_INFERENCE &&
        (urgencyLevel == UrgencyLevel.EMERGENCY || KernelTriageRules.isCriticalVitalsFlag(predictedCondition)),
    isCriticalVitalsFlag = inferenceSource == InferenceSource.REAL_INFERENCE &&
        KernelTriageRules.isCriticalVitalsFlag(predictedCondition),
    sourceLabel = when (inferenceSource) {
        InferenceSource.REAL_INFERENCE -> "Real-time AI inference (/v1/assess)"
        // Rendered from strings.xml (AssessmentCopy); this string is what the acknowledgement
        // audit entry records, and must not claim an outage the device did not observe.
        InferenceSource.MOCK_FALLBACK -> "Mock result (dev build only): the real assessment service was not reached"
        // Reach-neutral: UNAVAILABLE covers both an unreachable kernel and one that answered
        // with an empty differential. Naming a cause here would be wrong half the time.
        InferenceSource.UNAVAILABLE -> UNAVAILABLE_SOURCE_LABEL
    },
    differentialLines = differentials.map { DifferentialLine(it) },
    reasoningLines = listOf(reasoningSummary),
    evidenceFor = evidenceFor,
    evidenceAgainst = evidenceAgainst,
)

data class KernelAssessmentUiState(
    val isLoading: Boolean = true,
    val display: AssessmentDisplay? = null,
    val liabilityAcknowledged: Boolean = false,
    val isRetrying: Boolean = false,
    /** No report yet, the job is queued, and the phone has no network. The job waits on the OS
     *  network (NetworkType.CONNECTED), so this is shown as "Waiting for a connection" instead of
     *  a spinner with nothing said. The job then runs on its own, which is true. */
    val waitingForNetwork: Boolean = false,
    /** Resolved once from this consultation's AUDIO attachment row, not carried in the route.
     *  A uri that survived three screens is not evidence the attachment was persisted; the row
     *  is. Null means no audio leg, which sends the case straight to Acknowledgement. */
    val audioUri: String? = null,
    /** Fails closed: a worker tier until a session says otherwise. Only PHYSICIAN sees the score. */
    val cadreTier: CadreTier = CadreTier.COMMUNITY,
) {
    val showModelScore: Boolean get() = cadreTier == CadreTier.PHYSICIAN
    val canContinue: Boolean get() = !isLoading && liabilityAcknowledged
}

sealed interface KernelAssessmentEffect {
    /** [audioUri] is resolved here rather than in the navigation lambda, because the answer now
     *  comes from a database read and a nav lambda cannot suspend. */
    data class Continue(val audioUri: String?) : KernelAssessmentEffect
}

@Stable
interface KernelAssessmentActions {
    fun onLiabilityAcknowledgedChange(acknowledged: Boolean)
    fun onContinue()
    fun onRetry()
}

/**
 * The "AI Assessment Panel" (REQ-HAN-07): confidence gauge + explainability + liability checkbox,
 * shown right after the kernel handoff, before the case moves on to transcription/save. Reads
 * what [com.example.samdapp.presentation.sending.SendingViewModel] already persisted via
 * [EvaluateReportRepository] (primary) / [KernelReportRepository] (fallback) — this screen doesn't
 * generate the assessment, it presents it.
 */
@HiltViewModel(assistedFactory = KernelAssessmentViewModel.Factory::class)
class KernelAssessmentViewModel @AssistedInject constructor(
    @Assisted("caseRecordId") private val caseRecordId: String,
    @Assisted("consultationId") private val consultationId: String,
    private val evaluateReportRepository: EvaluateReportRepository,
    private val kernelReportRepository: KernelReportRepository,
    private val consultationRepository: ConsultationRepository,
    private val assessmentQueueScheduler: AssessmentQueueScheduler,
    private val auditLogger: AuditLogger,
    private val authSession: AuthSession,
    /** The OS network, not the manual offline toggle: it is what WorkManager waits on before the
     *  queued job runs (honouring the toggle is filed separately, operator ruling H2). */
    private val networkMonitor: NetworkMonitor,
) : ViewModel(), KernelAssessmentActions {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("caseRecordId") caseRecordId: String,
            @Assisted("consultationId") consultationId: String,
        ): KernelAssessmentViewModel
    }

    /** Resolved once in `init` and awaited by [onContinue]. See the comment at its assignment. */
    private lateinit var audioUriLookup: Deferred<String?>

    private val _uiState = MutableStateFlow(KernelAssessmentUiState())
    val uiState: StateFlow<KernelAssessmentUiState> = _uiState.asStateFlow()

    private val _effects = Channel<KernelAssessmentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        // Resolved once, not collected: the AUDIO attachment row for this consultation is written
        // by ConsultationViewModel.onSend before it emits the effect that navigates here, so it
        // either exists by now or the worker recorded nothing. Reuses ConsultationRepository.getById
        // (which already resolves attachments) rather than adding a narrow DAO query: firstOrNull
        // over that ASC-ordered list is exactly the semantics the route argument used to carry,
        // and a hand-written ORDER BY would reintroduce the chance of picking the wrong take from
        // a worker who recorded twice.
        // Held as a Deferred, not merely written into uiState when it happens to finish. The
        // Continue button is enabled by `canContinue`, which tracks the REPORT load and knows
        // nothing about this lookup, so a worker who acknowledges quickly could previously press
        // Continue while `audioUri` was still its initial null. That is indistinguishable from
        // "there is no recording", and the nav branch would skip transcription for a case that
        // does have audio: a silently dropped clinical leg. onContinue now awaits this.
        audioUriLookup = viewModelScope.async {
            val audioUri = consultationRepository.getById(consultationId)
                ?.attachments
                ?.firstOrNull { it.type == AttachmentType.AUDIO }
                ?.uri
            _uiState.update { it.copy(audioUri = audioUri) }
            audioUri
        }
        // The tier gates the model score. A null session is a worker, never a physician.
        viewModelScope.launch {
            authSession.currentUser().collect { session ->
                val tier = session?.role?.toCadreTier() ?: CadreTier.COMMUNITY
                _uiState.update { it.copy(cadreTier = tier) }
            }
        }
        // Collected, not one-shot: the async submission queue means no report row is guaranteed
        // to exist yet when this screen opens. workState tells apart "still processing" (show a
        // wait state) from "stalled" (no row, nothing running - offer the same retry affordance
        // an UNAVAILABLE row already offers, via stalledDisplay()).
        viewModelScope.launch {
            combine(
                evaluateReportRepository.observeForCase(caseRecordId),
                kernelReportRepository.observeForCase(caseRecordId),
                assessmentQueueScheduler.observeWorkState(caseRecordId),
                networkMonitor.isNetworkAvailable,
            ) { evaluateOutput, kernelOutput, workState, networkAvailable ->
                val reportDisplay = evaluateOutput?.toDisplay() ?: kernelOutput?.toDisplay()
                _uiState.update {
                    when {
                        reportDisplay != null -> it.copy(
                            isLoading = false,
                            display = reportDisplay,
                            isRetrying = workState != AssessmentWorkState.NONE,
                            waitingForNetwork = false,
                        )
                        workState != AssessmentWorkState.NONE -> it.copy(
                            isLoading = true,
                            display = null,
                            waitingForNetwork = workState == AssessmentWorkState.QUEUED && !networkAvailable,
                        )
                        else -> it.copy(isLoading = false, display = stalledDisplay(), isRetrying = false, waitingForNetwork = false)
                    }
                }
            }.collect {}
        }
    }

    override fun onLiabilityAcknowledgedChange(acknowledged: Boolean) =
        _uiState.update { it.copy(liabilityAcknowledged = acknowledged) }

    /** Retry affordance on the UNAVAILABLE/stalled state: re-enqueues the same unique assessment
     *  work as the original send, via [assessmentQueueScheduler]. Fire-and-forget - the result
     *  reaches the screen through the collected Flow in [init], not through this call's return.
     *  May legitimately come back UNAVAILABLE again if the server is still unreachable; that's an
     *  honest result, not an error in this use case. */
    override fun onRetry() {
        if (_uiState.value.isRetrying) return
        assessmentQueueScheduler.enqueueAssessment(caseRecordId)
    }

    override fun onContinue() {
        if (!_uiState.value.canContinue) return
        viewModelScope.launch {
            val display = _uiState.value.display
            auditLogger.log(
                action = AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED,
                caseRecordId = caseRecordId,
                payload = auditPayload(
                    "predictedCondition" to display?.predictedCondition,
                    "requiredHumanVerification" to display?.requiresHumanVerification?.toString(),
                    "isMockFallback" to display?.isMockFallback?.toString(),
                    "sourceLabel" to display?.sourceLabel,
                ),
            )
            // Awaited, not read from uiState: the lookup may still be in flight, and a null read
            // here would drop the transcription leg for a case that has audio.
            _effects.send(KernelAssessmentEffect.Continue(audioUriLookup.await()))
        }
    }
}
