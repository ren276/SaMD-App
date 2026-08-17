package com.example.samdapp.presentation.acknowledgement

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.config.SyncWindowProvider
import com.example.samdapp.domain.usecase.AcknowledgeCaseUseCase
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

/** [hoursUntilReview] backs the expectation-management message (REQ-TRS-03) — this screen is only
 *  ever reached on the non-emergency path (an emergency short-circuits from Compounder straight to
 *  Home, see [com.example.samdapp.presentation.emergency.EmergencyOverrideScreen]), so no extra
 *  guard is needed here. */
data class AcknowledgementUiState(
    val isSaving: Boolean = true,
    val errorMessage: String? = null,
    val hoursUntilReview: Int = 24,
)

/** Always routes to [com.example.samdapp.presentation.doctorassignment.DoctorAssignmentConfirmScreen]
 *  — that screen resolves and shows which doctor the case will go to (continuity or auto-assigned)
 *  before actually sending it, so a worker can always see the mock assignment and switch it,
 *  regardless of whether this is a follow-up. There is no silent "auto-assign and skip the screen"
 *  path anymore — that was invisible even when it worked, and gave no way to see or recover from
 *  a failed resolution (e.g. no active doctors). */
sealed interface AcknowledgementEffect {
    data class SendToDoctor(val caseRecordId: String) : AcknowledgementEffect
}

@Stable
interface AcknowledgementActions {
    fun onContinue()
}

@HiltViewModel(assistedFactory = AcknowledgementViewModel.Factory::class)
class AcknowledgementViewModel @AssistedInject constructor(
    @Assisted val caseRecordId: String,
    private val acknowledgeCaseUseCase: AcknowledgeCaseUseCase,
    private val syncWindowProvider: SyncWindowProvider,
    private val auditLogger: AuditLogger,
) : ViewModel(), AcknowledgementActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): AcknowledgementViewModel
    }

    private val _uiState = MutableStateFlow(AcknowledgementUiState(hoursUntilReview = syncWindowProvider.hoursUntilReview()))
    val uiState: StateFlow<AcknowledgementUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AcknowledgementEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            acknowledgeCaseUseCase(caseRecordId).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false) }
                    auditLogger.log(
                        action = AuditAction.CONSULTATION_LOCKED,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("status" to "saved_locally"),
                    )
                },
                onFailure = { error -> _uiState.update { it.copy(isSaving = false, errorMessage = error.message) } },
            )
        }
    }

    override fun onContinue() {
        viewModelScope.launch { _effects.send(AcknowledgementEffect.SendToDoctor(caseRecordId)) }
    }
}
