package com.example.samdapp.presentation.doctorassignment

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.usecase.AssignDoctorUseCase
import com.example.samdapp.domain.usecase.ResolveDoctorAssignmentUseCase
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
import kotlinx.coroutines.launch

data class DoctorAssignmentConfirmUiState(
    val isLoading: Boolean = true,
    val selectedDoctor: Doctor? = null,
    /** Whether [selectedDoctor] was proposed via continuity (same doctor as a prior follow-up
     *  visit) or least-busy auto-assignment — drives which copy the screen shows. Switching to an
     *  alternative doesn't change this; it only describes how the ORIGINAL proposal was reached. */
    val isContinuity: Boolean = false,
    val showAlternatives: Boolean = false,
    val alternatives: List<Doctor> = emptyList(),
    val isConfirming: Boolean = false,
    val errorMessage: String? = null,
    /** Set instead of sending [DoctorAssignmentConfirmEffect.Done] when there's no network: the
     *  case is saved locally with the doctor already picked, but nothing has actually been sent
     *  yet, so the screen stays put and says so rather than auto-navigating away as if it had. */
    val queuedOffline: Boolean = false,
)

sealed interface DoctorAssignmentConfirmEffect {
    data object Done : DoctorAssignmentConfirmEffect
}

@Stable
interface DoctorAssignmentConfirmActions {
    fun onShowAlternatives()
    fun onSelectAlternative(doctor: Doctor)
    fun onConfirm()
}

@HiltViewModel(assistedFactory = DoctorAssignmentConfirmViewModel.Factory::class)
class DoctorAssignmentConfirmViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
    private val caseRecordRepository: CaseRecordRepository,
    private val resolveDoctorAssignmentUseCase: ResolveDoctorAssignmentUseCase,
    private val assignDoctorUseCase: AssignDoctorUseCase,
    private val auditLogger: AuditLogger,
    private val connectivityController: ConnectivityController,
) : ViewModel(), DoctorAssignmentConfirmActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): DoctorAssignmentConfirmViewModel
    }

    private val _uiState = MutableStateFlow(DoctorAssignmentConfirmUiState())
    val uiState: StateFlow<DoctorAssignmentConfirmUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DoctorAssignmentConfirmEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val encounterId = caseRecordRepository.observeCaseRecord(caseRecordId).first()?.encounterId
            val proposal = encounterId?.let { resolveDoctorAssignmentUseCase(caseRecordId, it).getOrNull() }
            if (proposal == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Could not resolve a doctor for this case") }
                return@launch
            }
            _uiState.update { it.copy(isLoading = false, selectedDoctor = proposal.doctor, isContinuity = proposal.isContinuity) }
        }
    }

    override fun onShowAlternatives() {
        val current = _uiState.value.selectedDoctor ?: return
        viewModelScope.launch {
            resolveDoctorAssignmentUseCase.sameSpecialtyAlternatives(current.specialty, current.id).fold(
                onSuccess = { alternatives -> _uiState.update { it.copy(showAlternatives = true, alternatives = alternatives) } },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message ?: "Could not load alternatives") } },
            )
        }
    }

    override fun onSelectAlternative(doctor: Doctor) {
        _uiState.update { it.copy(selectedDoctor = doctor, showAlternatives = false) }
    }

    override fun onConfirm() {
        val state = _uiState.value
        val doctor = state.selectedDoctor ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirming = true) }
            val isOnline = connectivityController.isOnline.first()
            assignDoctorUseCase(caseRecordId, doctor.id, isOnline = isOnline).fold(
                onSuccess = {
                    auditLogger.log(
                        action = if (isOnline) AuditAction.CASE_SENT_TO_DOCTOR else AuditAction.CASE_QUEUED_FOR_SYNC,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("doctorId" to doctor.id, "doctorName" to doctor.name, "continuity" to state.isContinuity.toString()),
                    )
                    if (isOnline) {
                        _effects.send(DoctorAssignmentConfirmEffect.Done)
                    } else {
                        _uiState.update { it.copy(isConfirming = false, queuedOffline = true) }
                    }
                },
                onFailure = { error -> _uiState.update { it.copy(isConfirming = false, errorMessage = error.message ?: "Could not assign doctor") } },
            )
        }
    }
}
