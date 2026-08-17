package com.example.samdapp.presentation.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
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
    @Assisted("consultationId") private val consultationId: String,
    @Assisted("audioUri") private val audioUri: String,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("consultationId") consultationId: String,
            @Assisted("audioUri") audioUri: String,
        ): TranscriptionViewModel
    }

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
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
