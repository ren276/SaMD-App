package com.example.samdapp.presentation.consultation

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingAttachment(val type: AttachmentType, val uri: String)

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
    val relevantHistory: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
) {
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

    fun onRelevantHistoryChange(value: String)
    fun onAddAttachment(type: AttachmentType, uri: String)
    fun onRecordAudioAttachment()
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
     *    a suggestion. `AndroidSpeechRecognizerService` reads the results list and calls
     *    `.orEmpty()`, so a success carrying "" is reachable, and entering Suggested with an empty
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
                        // Honest-failure edge: successful recognition, nothing usable in it.
                        // TODO(PR 3d): emit AuditAction.VOICE_FIELD_REJECTED here.
                        _uiState.update {
                            it.copy(
                                isCapturingImpactVoice = false,
                                errorMessage = "Nothing was heard. Please try again or type the answer.",
                            )
                        }
                    } else {
                        // TODO(PR 3d): emit AuditAction.VOICE_FIELD_SUGGESTED here.
                        _uiState.update {
                            it.copy(
                                isCapturingImpactVoice = false,
                                impactVoiceSuggestion = captured.transcript,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    // Honest-failure edge: the capture failed, so no value was produced.
                    // TODO(PR 3d): emit AuditAction.VOICE_FIELD_REJECTED here.
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

    /** TODO(PR 3d): emit [AuditAction.VOICE_FIELD_CONFIRMED] here. */
    override fun onUseImpactSuggestion() = _uiState.update { state ->
        val suggestion = state.impactVoiceSuggestion ?: return@update state
        state.copy(
            impactOnDailyActivities = suggestion,
            impactProvenance = FieldProvenance.VOICE_CONFIRMED,
            impactVoiceSuggestion = null,
        )
    }

    /** The suggestion is committed so the worker can correct it in place, stamped `VOICE_EDITED`
     *  from the outset. The confirm for that edit is the existing review-and-send at the screen
     *  boundary (memo A.3), so no `VOICE_FIELD_EDITED` breadcrumb belongs here: PR 3d emits it at
     *  save, where it records a confirmed edit rather than an abandoned one. */
    override fun onEditImpactSuggestion() = _uiState.update { state ->
        val suggestion = state.impactVoiceSuggestion ?: return@update state
        state.copy(
            impactOnDailyActivities = suggestion,
            impactProvenance = FieldProvenance.VOICE_EDITED,
            impactVoiceSuggestion = null,
        )
    }

    /** TODO(PR 3d): emit [AuditAction.VOICE_FIELD_REJECTED] here. */
    override fun onDiscardImpactSuggestion() = _uiState.update { it.copy(impactVoiceSuggestion = null) }
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
                relevantHistory = DemoPatientProfile.RELEVANT_HISTORY,
            )
        }
    }

    override fun onAddAttachment(type: AttachmentType, uri: String) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + PendingAttachment(type, uri)) }
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
            auditLogger.log(
                action = AuditAction.CONSULTATION_SAVED,
                patientId = patientId,
                caseRecordId = caseRecordId,
                payload = auditPayload("consultationId" to consultation.id, "chiefComplaint" to current.chiefComplaint),
            )
            _uiState.update { it.copy(isSaving = false) }
            _effects.send(
                ConsultationEffect.Sent(patientId, encounterId, caseRecordId, consultation.id, current.hasAudioAttachment),
            )
        }
    }
}
