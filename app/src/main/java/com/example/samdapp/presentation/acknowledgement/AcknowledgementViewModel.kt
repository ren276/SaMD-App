package com.example.samdapp.presentation.acknowledgement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.config.SyncWindowProvider
import com.example.samdapp.domain.usecase.AcknowledgeCaseUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [hoursUntilReview] backs the expectation-management message (REQ-TRS-03) — this screen is only
 *  ever reached on the non-emergency path (an emergency short-circuits from Compounder straight to
 *  Home, see [com.example.samdapp.presentation.emergency.EmergencyOverrideScreen]), so no extra
 *  guard is needed here. */
data class AcknowledgementUiState(
    val isSaving: Boolean = true,
    val errorMessage: String? = null,
    val hoursUntilReview: Int = 24,
)

@HiltViewModel(assistedFactory = AcknowledgementViewModel.Factory::class)
class AcknowledgementViewModel @AssistedInject constructor(
    @Assisted val caseRecordId: String,
    private val acknowledgeCaseUseCase: AcknowledgeCaseUseCase,
    private val syncWindowProvider: SyncWindowProvider,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): AcknowledgementViewModel
    }

    private val _uiState = MutableStateFlow(AcknowledgementUiState(hoursUntilReview = syncWindowProvider.hoursUntilReview()))
    val uiState: StateFlow<AcknowledgementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            acknowledgeCaseUseCase(caseRecordId).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false) }
                    auditLogger.log(
                        action = "consultation_locked",
                        caseRecordId = caseRecordId,
                        payload = auditPayload("status" to "saved_locally"),
                    )
                },
                onFailure = { error -> _uiState.update { it.copy(isSaving = false, errorMessage = error.message) } },
            )
        }
    }
}
