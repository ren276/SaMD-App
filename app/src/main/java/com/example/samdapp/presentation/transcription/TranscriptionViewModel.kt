package com.example.samdapp.presentation.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.usecase.TranscribeAudioUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranscriptionUiState(
    val isLoading: Boolean = true,
    val transcription: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel(assistedFactory = TranscriptionViewModel.Factory::class)
class TranscriptionViewModel @AssistedInject constructor(
    @Assisted private val consultationId: String,
    private val consultationRepository: ConsultationRepository,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(consultationId: String): TranscriptionViewModel
    }

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // The uri is read from this consultation's AUDIO attachment row rather than carried
            // through three routes. No row means nothing was persisted to transcribe, which is an
            // error state on this screen, never a crash: this block runs on construction, so
            // throwing here would take down a screen the worker cannot back out of.
            val audioUri = consultationRepository.getById(consultationId)
                ?.attachments
                ?.firstOrNull { it.type == AttachmentType.AUDIO }
                ?.uri
            if (audioUri == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "No recording was saved for this consultation.")
                }
                return@launch
            }
            transcribeAudioUseCase(consultationId, audioUri).fold(
                onSuccess = { text ->
                    _uiState.update { it.copy(isLoading = false, transcription = text) }
                    auditLogger.log(
                        action = AuditAction.TRANSCRIPTION_COMPLETED,
                        payload = auditPayload("consultationId" to consultationId, "audioUri" to audioUri),
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Transcription failed") }
                },
            )
        }
    }
}
