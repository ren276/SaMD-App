package com.example.samdapp.presentation.consultation

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.audit.levenshteinDistance
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.domain.document.CapturedPage
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.document.DocumentCaptureStore
import com.example.samdapp.domain.usecase.UploadConsultationDocumentUseCase
import kotlinx.coroutines.flow.first
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingAttachment(val type: AttachmentType, val uri: String)

/** H-18, Builds 3a and 3b: a document the worker has picked (PATH A) or scanned (PATH B) and
 *  tagged, queued here (same deferred shape as [PendingAttachment]) until
 *  [ConsultationUiState.canSend] and the real `consultationId` it needs as a foreign key exist.
 *  [bytes] carries the provenance; both kinds go to the same upload use case unchanged. */
data class PendingDocument(
    val bytes: DocumentBytes,
    val label: String,
    val departmentCode: DepartmentCode,
    val recordTypeCode: RecordTypeCode,
)

/**
 * H-18, Build 3b: an in-progress camera capture. Lives here rather than in a screen of its own
 * because the department/record-type/label the document needs are already this screen's state,
 * and because a capture must not outlive the consultation it belongs to.
 *
 * Holds only page IDS and small in-memory thumbnails: every captured page's real bytes are
 * encrypted on disk under [sessionId] the moment the camera returns them, and nothing here is
 * persisted. A process death therefore loses the capture, which is the intended posture - the
 * startup sweep deletes the orphaned session directory rather than resurrecting a half-captured
 * clinical document with no owner.
 */
data class DocumentCaptureUiState(
    val sessionId: String,
    val maxPages: Int,
    val pages: List<CapturedPage> = emptyList(),
    /** Non-null while the camera activity is capturing this page. */
    val pendingPageId: String? = null,
    val pendingStagingPath: String? = null,
    val isAssembling: Boolean = false,
    val pagesAssembled: Int = 0,
    val errorMessage: String? = null,
    /** R5: back or cancel with pages captured asks before throwing them away. */
    val confirmDiscard: Boolean = false,
) {
    val canAddPage: Boolean get() = !isAssembling && pendingPageId == null && pages.size < maxPages
    val canFinish: Boolean get() = !isAssembling && pendingPageId == null && pages.isNotEmpty()
}

/** The two facts `onSend` needs to emit `VOICE_FIELD_EDITED` honestly, captured at the Edit tap
 *  and carried in [ConsultationUiState.impactVoicePendingEdit] until save consumes them:
 *  [originalSuggestion] for `editDistance` against the eventual saved text, [dwellMs] for how
 *  long the suggestion sat before the worker acted on it. */
data class PendingVoiceEdit(val originalSuggestion: String, val dwellMs: Long)

private const val SLOT_IMPACT_ON_DAILY_ACTIVITIES = "IMPACT_ON_DAILY_ACTIVITIES"

/** Honest engine identity for the audit payload. The two fields answer one audit question
 *  between them — which weights plus which code produced this text — so they are split that way:
 *  [ASR_MODEL_ID] names the weights and is the vendored asset directory verbatim, pinned per file
 *  by SHA-256 in the SBOM model companion under `docs/sbom/`; [ASR_MODEL_VERSION] names the
 *  runtime, which is what varies independently of the weights from release to release. The model
 *  id already carries the model's own date, so repeating it as the version would say nothing. */
private const val ASR_MODEL_ID = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
private const val ASR_MODEL_VERSION = "sherpa-onnx-1.13.7"

private fun dwellMillisSince(shownAtNanos: Long?): Long? =
    shownAtNanos?.let { (System.nanoTime() - it) / 1_000_000 }

data class ConsultationUiState(
    val chiefComplaint: String,
    val isVoiceMode: Boolean = false,
    val isRecordingVoice: Boolean = false,
    val onset: String = "",
    val durationBucket: String? = null,
    val severityScore: Int = 0,
    val aggravatingFactors: String = "",
    val relievingFactors: String = "",
    val impactOnDailyActivities: String = "",
    /** The outstanding ASR suggestion for [impactOnDailyActivities], or null when there is none.
     *  It sits **beside** the committed value and is never the field value itself, so no code
     *  path can read an unconfirmed transcript as if the worker had accepted it
     *  (`scratchpad/pr3-voice-gate-design-memo.md` A.2 property 1).
     *
     *  Non-null means the gate is in its Suggested state. Nothing clears it except the three
     *  explicit worker actions and the failure edges: there is no timeout and no auto-accept, so
     *  a future diff that introduces a timer touching this field is wrong (A.2 property 2).
     *
     *  ViewModel-held only, deliberately. Process death discards it along with the rest of the
     *  draft, which fails safe: the suggestion was never the field value and no provenance was
     *  stamped, so nothing unconfirmed survives (A.2 property 3). */
    val impactVoiceSuggestion: String? = null,
    /** Mic live for [impactOnDailyActivities]. The field is never mutated while this is true. */
    val isCapturingImpactVoice: Boolean = false,
    /** The [FieldProvenance] the committed [impactOnDailyActivities] will carry at save.
     *  Null means "typed, or empty": [com.example.samdapp.domain.usecase.SaveConsultationUseCase]
     *  stamps `TYPED` for a non-blank value, which is PR 1's behaviour unchanged.
     *  `VOICE_UNCONFIRMED` is never assigned here (A.1): a suggestion the worker has not accepted
     *  lives in [impactVoiceSuggestion], not in the provenance of a committed value. */
    val impactProvenance: FieldProvenance? = null,
    /** `System.nanoTime()` at the moment [impactVoiceSuggestion] was shown, or null when nothing
     *  is or has just been shown. Consumed at the resolving action (Use it, Edit or Discard) to
     *  compute `dwellMs` for the audit payload (`scratchpad/pr3-voice-gate-design-memo.md` C.3):
     *  a measured interval, never content. `System.nanoTime()` rather than
     *  `android.os.SystemClock.elapsedRealtime()` because it is monotonic, immune to wall-clock
     *  changes, and pure JVM, so it needs no Android framework stub in a plain unit test.
     *  Null on the two honest-failure `REJECTED` edges (ASR error, blank transcript): neither
     *  ever showed a suggestion, so there is no dwell interval to measure, and `dwellMs` is
     *  correctly absent from those two payloads rather than fabricated as zero. */
    val impactVoiceSuggestionShownAtNanos: Long? = null,
    /** Set by [ConsultationActions.onEditImpactSuggestion], consumed by `onSend`. The
     *  `VOICE_FIELD_EDITED` breadcrumb fires at save, not at the Edit tap (memo A.3/C.1: it
     *  records a confirmed edit, not an abandoned one), so the data that breadcrumb's
     *  `editDistance` and `dwellMs` need is captured at the tap and carried here until save
     *  consumes it. Null if the value's provenance is not currently `VOICE_EDITED`, or if a
     *  fresh voice capture superseded it before save (in which case `VOICE_FIELD_EDITED` still
     *  emits, honestly, without those two keys, rather than reusing stale metrics). */
    val impactVoicePendingEdit: PendingVoiceEdit? = null,
    val relevantHistory: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** H-18, Build 3a: documents already picked, tagged and queued (the "upload reports, if any"
     *  affordance). Each entry's department/record-type were worker-selected from the controlled
     *  vocabularies before it could be added — see [documentDraftDepartment]/[documentDraftRecordType]. */
    val pendingDocuments: List<PendingDocument> = emptyList(),
    /** Non-null while the multi-page camera capture surface is open (H-18, Build 3b). */
    val documentCapture: DocumentCaptureUiState? = null,
    /** In-progress selections for the next document to queue — reset after each add. Both must
     *  be non-null (worker SELECTS, never a silent default) before a picked file can be added. */
    val documentDraftDepartment: DepartmentCode? = null,
    val documentDraftRecordType: RecordTypeCode? = null,
    val documentDraftLabel: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    /** H-18, Build 3a: non-empty means one or more documents failed to upload during send. The
     *  consultation itself already saved successfully — this only holds the screen open (see
     *  [pendingSentEffect]) until the worker acknowledges, so the failure is seen rather than
     *  raced off-screen by an immediate navigation. */
    val documentUploadFailures: List<String> = emptyList(),
    /** The [ConsultationEffect.Sent] navigation held back while [documentUploadFailures] is
     *  non-empty, dispatched by [ConsultationActions.onDismissDocumentUploadFailures]. */
    val pendingSentEffect: ConsultationEffect.Sent? = null,
) {
    /** The file-pick affordance is enabled only once both controlled-vocabulary dropdowns are
     *  selected — a document is never queued with a guessed department or record type. */
    val canPickDocument: Boolean get() = documentDraftDepartment != null && documentDraftRecordType != null
    /** The added clauses are the voice gate (`pr3-voice-gate-design-memo.md` A.2 property 4).
     *  Because `ConsultationScreen` binds the send button to this, blocking here also blocks the
     *  H-08 review dialog the button opens, so an outstanding suggestion cannot be carried past
     *  the screen boundary. Deliberately one derived clause here rather than a second guard
     *  inside the dialog: stacking the voice gate into that dialog would train dismissal, the
     *  failure mode H-02 already records for AGREE. */
    val canSend: Boolean
        get() = chiefComplaint.isNotBlank() && !isSaving &&
            impactVoiceSuggestion == null && !isCapturingImpactVoice
    val hasAudioAttachment: String? get() = pendingAttachments.firstOrNull { it.type == AttachmentType.AUDIO }?.uri
}

sealed interface ConsultationEffect {
    data class Sent(
        val patientId: String,
        val encounterId: String,
        val caseRecordId: String,
        val consultationId: String,
        val audioUri: String?,
    ) : ConsultationEffect
}

@Stable
interface ConsultationActions {
    fun onChiefComplaintChange(value: String)
    fun onToggleVoiceMode()
    fun onRecordChiefComplaintVoice()
    fun onOnsetChange(value: String)
    fun onDurationBucketChange(value: String)
    fun onSeverityScoreChange(value: Int)
    fun onAggravatingFactorsChange(value: String)
    fun onRelievingFactorsChange(value: String)
    fun onImpactChange(value: String)

    // ── Voice confirmation gate for impactOnDailyActivities ──────────────────────────────────
    // `scratchpad/pr3-voice-gate-design-memo.md` Part A. Called from the mic button and the
    // suggestion surface in ConsultationScreen.kt, both hidden while
    // FeatureFlags.VOICE_FIELD_IMPACT_ENABLED is off (see its KDoc), so the gate has a caller
    // and a flag but is not yet reachable in a shipped build. Breadcrumb emission is PR 3d.

    /** Idle to Capturing. Runs the existing recognizer and routes the result to Suggested or to
     *  an honest-failure edge. Never mutates the field. */
    fun onRecordImpactVoice()

    /** Suggested to Idle, accepting: the suggestion becomes the committed value, stamped
     *  `VOICE_CONFIRMED`. */
    fun onUseImpactSuggestion()

    /** Suggested to Idle, accepting for correction: the suggestion becomes the committed value
     *  stamped `VOICE_EDITED`, and the worker edits it in the field. The confirm is the existing
     *  review-and-send, not a new per-field tap (memo A.3). */
    fun onEditImpactSuggestion()

    /** Suggested to Idle, rejecting: field and provenance untouched, suggestion dropped. */
    fun onDiscardImpactSuggestion()

    /** The worker declined the microphone prompt. Surfaces the refusal on the existing
     *  [ConsultationUiState.errorMessage] path so the mic is not a silent dead end
     *  (`scratchpad/pr4b-flag-flip-design-memo.md` D.3). */
    fun onVoicePermissionDenied()

    fun onRelevantHistoryChange(value: String)
    fun onAddAttachment(type: AttachmentType, uri: String)
    fun onRecordAudioAttachment()

    // ── Consultation documents (H-18, Build 3a, PATH A direct-file upload) ────────────────────
    fun onDocumentDepartmentSelected(code: DepartmentCode)
    fun onDocumentRecordTypeSelected(code: RecordTypeCode)
    fun onDocumentLabelChange(text: String)
    /** [claimedMimeType] is `ContentResolver.getType(uri)` from the picker, read by the Screen
     *  (same layering as [onAddAttachment]) — not trusted, only carried through to the upload
     *  path's magic-byte cross-check. */
    fun onDocumentPicked(uri: String, claimedMimeType: String?)

    // ── Multi-page camera capture (H-18, Build 3b, PATH B) ────────────────────────────────────
    /** Opens the capture surface. Gated on the same controlled-vocabulary selections the file
     *  picker is: a scanned document is never queued with a guessed department or record type. */
    fun onStartDocumentCapture()
    /** Allocates the next page and its staging path; the Screen launches the camera onto it. */
    fun onAddDocumentPage()
    /** [saved] is the camera contract's own result - false means the worker backed out. */
    fun onDocumentPageCaptured(saved: Boolean)
    fun onDeleteDocumentPage(pageId: String)
    /** R7: page order is clinical meaning in a multi-page report, so it is worker-controlled. */
    fun onMoveDocumentPage(from: Int, to: Int)
    fun onFinishDocumentCapture()
    fun onCancelDocumentAssembly()
    fun onRequestDiscardDocumentCapture()
    fun onDismissDiscardDocumentCapture()
    fun onConfirmDiscardDocumentCapture()
    fun onDismissDocumentCaptureError()
    /** Dismisses the "some documents failed to upload" notice and lets the already-completed
     *  send proceed to navigate away. */
    fun onDismissDocumentUploadFailures()

    fun onSend()
    /** Pre-fills the main concern + history-of-present-illness from [DemoPatientProfile] — demo only. */
    fun fillDemoData()
}

@HiltViewModel(assistedFactory = ConsultationViewModel.Factory::class)
class ConsultationViewModel @AssistedInject constructor(
    @Assisted("patientId") private val patientId: String,
    @Assisted("encounterId") private val encounterId: String,
    @Assisted("caseRecordId") private val caseRecordId: String,
    @Assisted("initialChiefComplaint") initialChiefComplaint: String,
    private val saveConsultationUseCase: SaveConsultationUseCase,
    private val addAttachmentUseCase: AddAttachmentUseCase,
    private val captureAudioAttachmentUseCase: CaptureAudioAttachmentUseCase,
    private val uploadConsultationDocumentUseCase: UploadConsultationDocumentUseCase,
    private val documentCaptureStore: DocumentCaptureStore,
    private val authSession: AuthSession,
    private val auditLogger: AuditLogger,
) : ViewModel(), ConsultationActions {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("patientId") patientId: String,
            @Assisted("encounterId") encounterId: String,
            @Assisted("caseRecordId") caseRecordId: String,
            @Assisted("initialChiefComplaint") initialChiefComplaint: String,
        ): ConsultationViewModel
    }

    private val _uiState = MutableStateFlow(ConsultationUiState(chiefComplaint = initialChiefComplaint))
    val uiState: StateFlow<ConsultationUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ConsultationEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onChiefComplaintChange(value: String) = _uiState.update { it.copy(chiefComplaint = value) }
    override fun onToggleVoiceMode() = _uiState.update { it.copy(isVoiceMode = !it.isVoiceMode) }
    override fun onOnsetChange(value: String) = _uiState.update { it.copy(onset = value) }
    override fun onDurationBucketChange(value: String) = _uiState.update { it.copy(durationBucket = value) }
    override fun onSeverityScoreChange(value: Int) = _uiState.update { it.copy(severityScore = value) }
    override fun onAggravatingFactorsChange(value: String) = _uiState.update { it.copy(aggravatingFactors = value) }
    override fun onRelievingFactorsChange(value: String) = _uiState.update { it.copy(relievingFactors = value) }
    /**
     * Keyboard edits carry the provenance transitions from the design memo's A.3:
     * clearing the field to empty resets provenance to null (an empty field has no provenance to
     * record), and hand-correcting a `VOICE_CONFIRMED` value makes it `VOICE_EDITED`, which is
     * the field-audit memo's own definition of that value ("voice-seeded, then hand-corrected").
     * A null provenance stays null and is stamped `TYPED` at save by
     * [com.example.samdapp.domain.usecase.SaveConsultationUseCase], PR 1's behaviour unchanged.
     */
    override fun onImpactChange(value: String) = _uiState.update {
        it.copy(
            impactOnDailyActivities = value,
            impactProvenance = when {
                value.isBlank() -> null
                it.impactProvenance == FieldProvenance.VOICE_CONFIRMED -> FieldProvenance.VOICE_EDITED
                else -> it.impactProvenance
            },
            // A cleared field has no provenance and nothing pending to report at save.
            impactVoicePendingEdit = if (value.isBlank()) null else it.impactVoicePendingEdit,
        )
    }

    /**
     * Idle to Capturing, then to Suggested or to an honest-failure edge.
     *
     * Three properties hold on every path out of here, and they are the reason this handler is
     * shaped the way it is (`scratchpad/pr3-voice-gate-design-memo.md` A.2 and B.3):
     *
     * 1. `impactOnDailyActivities` and `impactProvenance` are never written. A transcript reaches
     *    [ConsultationUiState.impactVoiceSuggestion] only, so nothing is committed until the
     *    worker acts on it.
     * 2. A **blank transcript on a successful recognition** is treated as a failed capture, not as
     *    a suggestion. `SherpaOnnxTranscriptionService` returns `Result.success("")` when the
     *    audio held nothing decodable — with an offline recognizer that is the *normal* outcome
     *    of a mis-tapped mic, not a corner case — and entering Suggested with an empty
     *    suggestion would put the worker in front of a gate with nothing in it. This is the same
     *    shape as the empty-differential-200 bug this repo already fixed once
     *    ([AuditAction.KERNEL_EMPTY_DIFFERENTIAL]): a successful response carrying nothing usable
     *    routes to the honest-failure state rather than being dressed up as a real result. The
     *    blank check is a check, not a transformation: the transcript itself is stored verbatim,
     *    with no tidying, so what the worker confirms is exactly what gets committed.
     * 3. An error leaves the field exactly as the worker left it. Never commit an empty string,
     *    never commit an unconfirmed value, the voice-level analogue of
     *    [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE]'s honest-failure state.
     *
     * Guarded against re-entry: a capture already in flight, or a suggestion already awaiting a
     * decision, refuses a second request rather than starting a second recognizer session or
     * overwriting the pending suggestion when the first completes.
     */
    override fun onRecordImpactVoice() {
        val current = _uiState.value
        if (current.isCapturingImpactVoice || current.impactVoiceSuggestion != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturingImpactVoice = true, errorMessage = null) }
            captureAudioAttachmentUseCase().fold(
                onSuccess = { captured ->
                    if (captured.transcript.isBlank()) {
                        // Honest-failure edge: successful recognition, nothing usable in it. No
                        // suggestion was ever shown, so no dwellMs; charCount = 0 records that the
                        // recognizer returned something, just nothing usable, distinct from the
                        // ASR-error edge below where no transcript was produced at all.
                        auditLogger.log(
                            action = AuditAction.VOICE_FIELD_REJECTED,
                            patientId = patientId,
                            caseRecordId = caseRecordId,
                            payload = impactVoicePayload(
                                provenance = FieldProvenance.VOICE_UNCONFIRMED,
                                charCount = 0,
                            ),
                        )
                        _uiState.update {
                            it.copy(
                                isCapturingImpactVoice = false,
                                errorMessage = "Nothing was heard. Please try again or type the answer.",
                            )
                        }
                    } else {
                        auditLogger.log(
                            action = AuditAction.VOICE_FIELD_SUGGESTED,
                            patientId = patientId,
                            caseRecordId = caseRecordId,
                            payload = impactVoicePayload(
                                provenance = FieldProvenance.VOICE_UNCONFIRMED,
                                charCount = captured.transcript.length,
                            ),
                        )
                        _uiState.update {
                            it.copy(
                                isCapturingImpactVoice = false,
                                impactVoiceSuggestion = captured.transcript,
                                impactVoiceSuggestionShownAtNanos = System.nanoTime(),
                            )
                        }
                    }
                },
                onFailure = { error ->
                    // Honest-failure edge: the capture failed, so no value was produced. No
                    // suggestion was shown (no dwellMs) and no transcript exists (no charCount).
                    auditLogger.log(
                        action = AuditAction.VOICE_FIELD_REJECTED,
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = impactVoicePayload(provenance = FieldProvenance.VOICE_UNCONFIRMED),
                    )
                    _uiState.update {
                        it.copy(
                            isCapturingImpactVoice = false,
                            errorMessage = error.message ?: "Voice capture failed",
                        )
                    }
                },
            )
        }
    }

    override fun onUseImpactSuggestion() {
        val state = _uiState.value
        val suggestion = state.impactVoiceSuggestion ?: return
        viewModelScope.launch {
            auditLogger.log(
                action = AuditAction.VOICE_FIELD_CONFIRMED,
                patientId = patientId,
                caseRecordId = caseRecordId,
                payload = impactVoicePayload(
                    provenance = FieldProvenance.VOICE_CONFIRMED,
                    charCount = suggestion.length,
                    dwellMs = dwellMillisSince(state.impactVoiceSuggestionShownAtNanos),
                ),
            )
        }
        _uiState.update {
            it.copy(
                impactOnDailyActivities = suggestion,
                impactProvenance = FieldProvenance.VOICE_CONFIRMED,
                impactVoiceSuggestion = null,
                impactVoiceSuggestionShownAtNanos = null,
                impactVoicePendingEdit = null,
            )
        }
    }

    /** The suggestion is committed so the worker can correct it in place, stamped `VOICE_EDITED`
     *  from the outset. The confirm for that edit is the existing review-and-send at the screen
     *  boundary (memo A.3), so the `VOICE_FIELD_EDITED` breadcrumb does not emit here; `onSend`
     *  emits it, where it records a confirmed edit rather than an abandoned one. What that later
     *  emission needs (the original suggestion, for `editDistance`, and `dwellMs`, measured now
     *  while the suggestion was actually on screen) is captured here and carried in
     *  [ConsultationUiState.impactVoicePendingEdit]. */
    override fun onEditImpactSuggestion() = _uiState.update { state ->
        val suggestion = state.impactVoiceSuggestion ?: return@update state
        state.copy(
            impactOnDailyActivities = suggestion,
            impactProvenance = FieldProvenance.VOICE_EDITED,
            impactVoiceSuggestion = null,
            impactVoiceSuggestionShownAtNanos = null,
            impactVoicePendingEdit = PendingVoiceEdit(
                originalSuggestion = suggestion,
                dwellMs = dwellMillisSince(state.impactVoiceSuggestionShownAtNanos) ?: 0L,
            ),
        )
    }

    override fun onDiscardImpactSuggestion() {
        val state = _uiState.value
        val suggestion = state.impactVoiceSuggestion
        if (suggestion != null) {
            viewModelScope.launch {
                auditLogger.log(
                    action = AuditAction.VOICE_FIELD_REJECTED,
                    patientId = patientId,
                    caseRecordId = caseRecordId,
                    payload = impactVoicePayload(
                        provenance = FieldProvenance.VOICE_UNCONFIRMED,
                        charCount = suggestion.length,
                        dwellMs = dwellMillisSince(state.impactVoiceSuggestionShownAtNanos),
                    ),
                )
            }
        }
        _uiState.update {
            it.copy(impactVoiceSuggestion = null, impactVoiceSuggestionShownAtNanos = null)
        }
    }

    /** No breadcrumb: nothing was captured, nothing was suggested, and a declined system prompt
     *  is not a gate transition. The message is the only outcome. */
    override fun onVoicePermissionDenied() {
        _uiState.update {
            it.copy(
                isCapturingImpactVoice = false,
                errorMessage = "Microphone permission was declined. Allow it to record, " +
                    "or type the answer.",
            )
        }
    }

    /** `slot`, `provenance` and `asrModelId`/`asrModelVersion` are on every `VOICE_FIELD_*`
     *  payload; `charCount`, `editDistance` and `dwellMs` are passed only where the caller has
     *  them, and appear as JSON null otherwise, rather than varying the payload's key set
     *  transition to transition. `asrModelVersion` is no longer among the nullable ones: it was
     *  null only while the platform recognizer exposed no version, and the on-device engine has
     *  a real one. `slot` is a plain `String`, not an enum: one voice-enabled field does not
     *  earn one yet (memo C.2),
     *  promote when a second slot exists. Never a transcript, corrected text, URI or patient
     *  name (memo C.4); `patientId`/`caseRecordId` travel as [AuditLogger.log] parameters, same
     *  as every other call site in this class, not inside this payload. */
    private fun impactVoicePayload(
        provenance: FieldProvenance,
        charCount: Int? = null,
        editDistance: Int? = null,
        dwellMs: Long? = null,
    ) = auditPayload(
        "slot" to SLOT_IMPACT_ON_DAILY_ACTIVITIES,
        "provenance" to provenance.name,
        "asrModelId" to ASR_MODEL_ID,
        "asrModelVersion" to ASR_MODEL_VERSION,
        "charCount" to charCount?.toString(),
        "editDistance" to editDistance?.toString(),
        "dwellMs" to dwellMs?.toString(),
    )
    override fun onRelevantHistoryChange(value: String) = _uiState.update { it.copy(relevantHistory = value) }

    /** Investor-demo shortcut: fills every HPI field from [DemoPatientProfile] in one tap. */
    override fun fillDemoData() {
        _uiState.update {
            it.copy(
                chiefComplaint = DemoPatientProfile.MAIN_CONCERN,
                onset = DemoPatientProfile.SYMPTOM_ONSET,
                durationBucket = DemoPatientProfile.DURATION_BUCKET,
                severityScore = DemoPatientProfile.SEVERITY_SCORE,
                aggravatingFactors = DemoPatientProfile.AGGRAVATING_FACTORS,
                relievingFactors = DemoPatientProfile.RELIEVING_FACTORS,
                impactOnDailyActivities = DemoPatientProfile.IMPACT_ON_DAILY_ACTIVITIES,
                // A prior voice-stamped provenance must not survive being overwritten by demo
                // text it does not describe; it stamps TYPED at save like any other typed value.
                impactProvenance = null,
                impactVoicePendingEdit = null,
                relevantHistory = DemoPatientProfile.RELEVANT_HISTORY,
            )
        }
    }

    override fun onAddAttachment(type: AttachmentType, uri: String) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + PendingAttachment(type, uri)) }
    }

    override fun onDocumentDepartmentSelected(code: DepartmentCode) =
        _uiState.update { it.copy(documentDraftDepartment = code) }
    override fun onDocumentRecordTypeSelected(code: RecordTypeCode) =
        _uiState.update { it.copy(documentDraftRecordType = code) }
    override fun onDocumentLabelChange(text: String) = _uiState.update { it.copy(documentDraftLabel = text) }

    override fun onDocumentPicked(uri: String, claimedMimeType: String?) {
        val state = _uiState.value
        val department = state.documentDraftDepartment ?: return
        val recordType = state.documentDraftRecordType ?: return
        _uiState.update {
            it.copy(
                pendingDocuments = it.pendingDocuments +
                    PendingDocument(DocumentBytes.DirectFile(uri, claimedMimeType), state.documentDraftLabel, department, recordType),
                // Reset the draft so the next pick starts from an explicit selection again.
                documentDraftDepartment = null,
                documentDraftRecordType = null,
                documentDraftLabel = "",
            )
        }
    }

    // ── Multi-page camera capture (H-18, Build 3b, PATH B) ────────────────────────────────────

    /** Cancelling this cancels the assembly coroutine, which is what deletes the partial output
     *  (R8) - the store's own cancellation handler owns the cleanup, not this field. */
    private var assemblyJob: Job? = null

    override fun onStartDocumentCapture() {
        val state = _uiState.value
        if (!state.canPickDocument || state.documentCapture != null) return
        _uiState.update {
            it.copy(
                documentCapture = DocumentCaptureUiState(
                    sessionId = documentCaptureStore.newSession(),
                    maxPages = documentCaptureStore.maxPages,
                ),
            )
        }
    }

    override fun onAddDocumentPage() {
        val capture = _uiState.value.documentCapture ?: return
        if (!capture.canAddPage) return
        val pageId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val path = documentCaptureStore.stagingPathFor(capture.sessionId, pageId)
            updateCapture { it.copy(pendingPageId = pageId, pendingStagingPath = path, errorMessage = null) }
        }
    }

    /**
     * R2's timing guarantee, at the one point in the app where a document page exists as
     * plaintext. `ingestPage` encrypts the staging file into the capture session and deletes it
     * in a `finally`, so the plaintext is gone before this coroutine resumes - and the UI does
     * not offer "add another page" again until it has ([DocumentCaptureUiState.canAddPage] is
     * false while `pendingPageId` is set), so page N+1 can never be captured while page N's
     * plaintext is still on disk.
     */
    override fun onDocumentPageCaptured(saved: Boolean) {
        val capture = _uiState.value.documentCapture ?: return
        val pageId = capture.pendingPageId ?: return
        viewModelScope.launch {
            if (!saved) {
                documentCaptureStore.discardStaging(capture.sessionId, pageId)
                updateCapture { it.copy(pendingPageId = null, pendingStagingPath = null) }
                return@launch
            }
            documentCaptureStore.ingestPage(capture.sessionId, pageId).fold(
                onSuccess = { page ->
                    updateCapture { it.copy(pages = it.pages + page, pendingPageId = null, pendingStagingPath = null) }
                },
                onFailure = { error ->
                    updateCapture {
                        it.copy(
                            pendingPageId = null,
                            pendingStagingPath = null,
                            errorMessage = error.message ?: "That page could not be saved. Take it again.",
                        )
                    }
                },
            )
        }
    }

    override fun onDeleteDocumentPage(pageId: String) {
        val capture = _uiState.value.documentCapture ?: return
        if (capture.isAssembling) return
        viewModelScope.launch {
            documentCaptureStore.deletePage(capture.sessionId, pageId)
            updateCapture { state -> state.copy(pages = state.pages.filterNot { it.pageId == pageId }) }
        }
    }

    override fun onMoveDocumentPage(from: Int, to: Int) {
        updateCapture { capture ->
            if (capture.isAssembling) return@updateCapture capture
            if (from !in capture.pages.indices || to !in capture.pages.indices || from == to) {
                return@updateCapture capture
            }
            val reordered = capture.pages.toMutableList()
            reordered.add(to, reordered.removeAt(from))
            capture.copy(pages = reordered)
        }
    }

    /** The page list is read ONCE here, in its final worker-chosen order, and that list is what
     *  the assembler iterates - there is no second ordering step anywhere downstream that could
     *  disagree with what the thumbnail strip showed. */
    override fun onFinishDocumentCapture() {
        val state = _uiState.value
        val capture = state.documentCapture ?: return
        if (!capture.canFinish) return
        val department = state.documentDraftDepartment ?: return
        val recordType = state.documentDraftRecordType ?: return
        val label = state.documentDraftLabel
        val orderedPageIds = capture.pages.map { it.pageId }
        assemblyJob = viewModelScope.launch {
            updateCapture { it.copy(isAssembling = true, pagesAssembled = 0, errorMessage = null) }
            val result = documentCaptureStore.assemble(capture.sessionId, orderedPageIds) { done, _ ->
                updateCapture { it.copy(pagesAssembled = done) }
            }
            result.fold(
                onSuccess = { assembled ->
                    _uiState.update {
                        it.copy(
                            pendingDocuments = it.pendingDocuments +
                                PendingDocument(assembled, label, department, recordType),
                            documentCapture = null,
                            // Same reset as the file-picker path: the next document starts from
                            // an explicit selection again.
                            documentDraftDepartment = null,
                            documentDraftRecordType = null,
                            documentDraftLabel = "",
                        )
                    }
                },
                onFailure = { error ->
                    // R4 reaching the worker: the capture surface stays open with every page
                    // intact and an explicit message. No document was produced, and none is
                    // queued - a shorter PDF is never the fallback.
                    updateCapture {
                        it.copy(
                            isAssembling = false,
                            pagesAssembled = 0,
                            errorMessage = error.message
                                ?: "This document could not be assembled. Retake the page it named and try again.",
                        )
                    }
                },
            )
        }
    }

    override fun onCancelDocumentAssembly() {
        assemblyJob?.cancel()
        assemblyJob = null
        updateCapture { it.copy(isAssembling = false, pagesAssembled = 0) }
    }

    override fun onRequestDiscardDocumentCapture() {
        val capture = _uiState.value.documentCapture ?: return
        // Nothing captured yet means nothing to lose - asking would be noise.
        if (capture.pages.isEmpty()) onConfirmDiscardDocumentCapture() else updateCapture { it.copy(confirmDiscard = true) }
    }

    override fun onDismissDiscardDocumentCapture() = updateCapture { it.copy(confirmDiscard = false) }

    /** R5: abandoning discards everything. No draft is kept - encrypted PHI on disk with no
     *  metadata row, no audit and no owner is a worse posture than losing the photos. */
    override fun onConfirmDiscardDocumentCapture() {
        val capture = _uiState.value.documentCapture ?: return
        assemblyJob?.cancel()
        assemblyJob = null
        _uiState.update { it.copy(documentCapture = null) }
        viewModelScope.launch { documentCaptureStore.discardSession(capture.sessionId) }
    }

    override fun onDismissDocumentCaptureError() = updateCapture { it.copy(errorMessage = null) }

    private fun updateCapture(transform: (DocumentCaptureUiState) -> DocumentCaptureUiState) {
        _uiState.update { state ->
            state.documentCapture?.let { state.copy(documentCapture = transform(it)) } ?: state
        }
    }

    override fun onRecordChiefComplaintVoice() {
        // Disabled pending the sherpa-onnx on-device engine and the confirmation-gate design
        // (scoped to PR 3/4): see FeatureFlags.VOICE_INPUT_ENABLED KDoc. The UI never shows the
        // control that calls this while the flag is off, so this return is a second, independent
        // stop, not the only one.
        if (!FeatureFlags.VOICE_INPUT_ENABLED) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRecordingVoice = true) }
            captureAudioAttachmentUseCase().fold(
                onSuccess = { captured ->
                    // Field-audit memo C-1: a raw ASR transcript must never be written directly
                    // into chiefComplaint. The confirmation-gate design (suggestion shown beside
                    // the field, explicit accept/edit/discard, provenance stamp, canSend guard)
                    // is deferred to PR 3, so until it lands this handler intentionally drops the
                    // transcript rather than committing an unconfirmed value. audio_captured is
                    // still logged, matching onRecordAudioAttachment's audit behavior for the
                    // capture itself; the drop happens only at the chiefComplaint write.
                    _uiState.update { it.copy(isRecordingVoice = false) }
                    auditLogger.log(
                        action = AuditAction.AUDIO_CAPTURED,
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("uri" to captured.uri, "purpose" to "chief_complaint"),
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRecordingVoice = false, errorMessage = error.message ?: "Voice capture failed") }
                },
            )
        }
    }

    override fun onRecordAudioAttachment() {
        if (!FeatureFlags.VOICE_INPUT_ENABLED) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRecordingVoice = true) }
            captureAudioAttachmentUseCase().fold(
                onSuccess = { captured ->
                    _uiState.update {
                        it.copy(
                            isRecordingVoice = false,
                            pendingAttachments = it.pendingAttachments + PendingAttachment(AttachmentType.AUDIO, captured.uri),
                        )
                    }
                    auditLogger.log(
                        action = AuditAction.AUDIO_CAPTURED,
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("uri" to captured.uri, "purpose" to "attachment"),
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isRecordingVoice = false, errorMessage = error.message ?: "Audio capture failed") }
                },
            )
        }
    }

    override fun onSend() {
        val current = _uiState.value
        if (!current.canSend) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val consultation = saveConsultationUseCase(
                patientId = patientId,
                encounterId = encounterId,
                chiefComplaint = current.chiefComplaint,
                onset = current.onset.ifBlank { null },
                durationBucket = current.durationBucket,
                severityScore = current.severityScore,
                aggravatingFactors = current.aggravatingFactors.ifBlank { null },
                relievingFactors = current.relievingFactors.ifBlank { null },
                impactOnDailyActivities = current.impactOnDailyActivities.ifBlank { null },
                impactOnDailyActivitiesProvenance = current.impactProvenance,
                relevantHistory = current.relevantHistory.ifBlank { null },
            ).getOrElse { error ->
                _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not save consultation") }
                return@launch
            }
            // VOICE_FIELD_EDITED emits here, not at the Edit tap (memo A.3/C.1): a save is what
            // turns a hand-corrected suggestion into a confirmed edit rather than an abandoned
            // one. Only when the saved value's own provenance is VOICE_EDITED, so a value that
            // reverted to TYPED/VOICE_CONFIRMED after the Edit tap (further edits, a re-capture,
            // Use it on a fresh suggestion) does not get a stale EDITED breadcrumb.
            if (current.impactProvenance == FieldProvenance.VOICE_EDITED) {
                val pending = current.impactVoicePendingEdit
                auditLogger.log(
                    action = AuditAction.VOICE_FIELD_EDITED,
                    patientId = patientId,
                    caseRecordId = caseRecordId,
                    payload = impactVoicePayload(
                        provenance = FieldProvenance.VOICE_EDITED,
                        charCount = current.impactOnDailyActivities.length,
                        // Absent, honestly, rather than fabricated, if a fresh voice capture
                        // superseded the metrics this value's Edit tap originally recorded.
                        editDistance = pending?.let {
                            levenshteinDistance(it.originalSuggestion, current.impactOnDailyActivities)
                        },
                        dwellMs = pending?.dwellMs,
                    ),
                )
            }
            current.pendingAttachments.forEach { pending ->
                addAttachmentUseCase(consultation.id, pending.type, pending.uri).onSuccess {
                    auditLogger.log(
                        action = AuditAction.ATTACHMENT_ADDED,
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("type" to pending.type.name, "uri" to pending.uri),
                    )
                }
            }
            // H-18, Build 3a: documents are encrypted and inserted here, once `consultation.id`
            // exists as their foreign key — same deferred-commit shape as pendingAttachments
            // above. UploadConsultationDocumentUseCase emits its own DOCUMENT_UPLOADED audit row
            // on success. A per-document failure does not block the send (documents are the
            // optional "if any" affordance; the consultation itself already saved successfully
            // above) — but it must not be silently lost either. Failures are collected rather
            // than written straight to errorMessage, because the Sent effect below fires in the
            // same coroutine and PatientSummaryScreen navigates away as soon as it's received —
            // an errorMessage set right before that would be raced off-screen before the worker
            // could ever read it, which is worse than not tracking it at all (it looks handled
            // and isn't). See onDismissDocumentUploadFailures.
            val documentFailures = mutableListOf<String>()
            if (current.pendingDocuments.isNotEmpty()) {
                val session = authSession.currentUser().first()
                current.pendingDocuments.forEach { pending ->
                    uploadConsultationDocumentUseCase(
                        consultationId = consultation.id,
                        bytes = pending.bytes,
                        label = pending.label,
                        departmentCode = pending.departmentCode,
                        recordTypeCode = pending.recordTypeCode,
                        uploaderUserId = session?.userId ?: "phc_field_worker",
                        uploaderRole = session?.role?.name ?: "ASHA_WORKER",
                    ).onFailure { error ->
                        documentFailures += pending.label.ifBlank { pending.recordTypeCode.name } +
                            ": " + (error.message ?: "upload failed")
                    }
                }
            }
            auditLogger.log(
                action = AuditAction.CONSULTATION_SAVED,
                patientId = patientId,
                caseRecordId = caseRecordId,
                payload = auditPayload("consultationId" to consultation.id, "chiefComplaint" to current.chiefComplaint),
            )
            val sentEffect = ConsultationEffect.Sent(patientId, encounterId, caseRecordId, consultation.id, current.hasAudioAttachment)
            if (documentFailures.isEmpty()) {
                _uiState.update { it.copy(isSaving = false) }
                _effects.send(sentEffect)
            } else {
                // Held until the worker explicitly acknowledges (see onDismissDocumentUploadFailures)
                // instead of sent now — the consultation is already saved either way, this only
                // delays the navigation away from this screen until the failure has actually
                // been seen.
                _uiState.update { it.copy(isSaving = false, documentUploadFailures = documentFailures, pendingSentEffect = sentEffect) }
            }
        }
    }

    override fun onDismissDocumentUploadFailures() {
        val pending = _uiState.value.pendingSentEffect ?: return
        _uiState.update { it.copy(documentUploadFailures = emptyList(), pendingSentEffect = null) }
        viewModelScope.launch { _effects.send(pending) }
    }
}
