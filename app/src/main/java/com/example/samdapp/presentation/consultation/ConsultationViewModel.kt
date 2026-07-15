package com.example.samdapp.presentation.consultation

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.AttachmentType
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
    val relevantHistory: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSend: Boolean get() = chiefComplaint.isNotBlank() && !isSaving
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
    fun onRelevantHistoryChange(value: String)
    fun onAddAttachment(type: AttachmentType, uri: String)
    fun onRecordAudioAttachment()
    fun onSend()
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
    override fun onImpactChange(value: String) = _uiState.update { it.copy(impactOnDailyActivities = value) }
    override fun onRelevantHistoryChange(value: String) = _uiState.update { it.copy(relevantHistory = value) }

    override fun onAddAttachment(type: AttachmentType, uri: String) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + PendingAttachment(type, uri)) }
    }

    override fun onRecordChiefComplaintVoice() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRecordingVoice = true) }
            captureAudioAttachmentUseCase().fold(
                onSuccess = { captured ->
                    _uiState.update { it.copy(isRecordingVoice = false, chiefComplaint = captured.transcript) }
                    auditLogger.log(
                        action = "audio_captured",
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
                        action = "audio_captured",
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
                relevantHistory = current.relevantHistory.ifBlank { null },
            ).getOrElse { error ->
                _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not save consultation") }
                return@launch
            }
            current.pendingAttachments.forEach { pending ->
                addAttachmentUseCase(consultation.id, pending.type, pending.uri).onSuccess {
                    auditLogger.log(
                        action = "attachment_added",
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("type" to pending.type.name, "uri" to pending.uri),
                    )
                }
            }
            auditLogger.log(
                action = "consultation_saved",
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
