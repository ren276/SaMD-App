package com.example.samdapp.presentation.doctorlist

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.usecase.AssignDoctorUseCase
import com.example.samdapp.domain.usecase.GetAvailableDoctorsUseCase
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

data class DoctorListUiState(
    val isLoading: Boolean = true,
    val doctors: List<Doctor> = emptyList(),
    val selectedDoctorId: String? = null,
    val isSending: Boolean = false,
    val sentToDoctor: Doctor? = null,
    val errorMessage: String? = null,
) {
    val canSend: Boolean get() = selectedDoctorId != null && !isSending
}

sealed interface DoctorListEffect {
    data object Done : DoctorListEffect
}

@Stable
interface DoctorListActions {
    fun onSelectDoctor(doctorId: String)
    fun onSend()
    fun onDone()
}

@HiltViewModel(assistedFactory = DoctorListViewModel.Factory::class)
class DoctorListViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
    private val getAvailableDoctorsUseCase: GetAvailableDoctorsUseCase,
    private val assignDoctorUseCase: AssignDoctorUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), DoctorListActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): DoctorListViewModel
    }

    private val _uiState = MutableStateFlow(DoctorListUiState())
    val uiState: StateFlow<DoctorListUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DoctorListEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            getAvailableDoctorsUseCase().fold(
                onSuccess = { doctors -> _uiState.update { it.copy(isLoading = false, doctors = doctors) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load doctors") }
                },
            )
        }
    }

    override fun onSelectDoctor(doctorId: String) = _uiState.update { it.copy(selectedDoctorId = doctorId) }

    override fun onSend() {
        val state = _uiState.value
        val doctorId = state.selectedDoctorId ?: return
        val doctor = state.doctors.firstOrNull { it.id == doctorId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            assignDoctorUseCase(caseRecordId, doctorId).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSending = false, sentToDoctor = doctor) }
                    auditLogger.log(
                        action = "case_sent_to_doctor",
                        caseRecordId = caseRecordId,
                        payload = auditPayload("doctorId" to doctorId, "doctorName" to doctor.name),
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSending = false, errorMessage = error.message ?: "Could not assign doctor") }
                },
            )
        }
    }

    override fun onDone() {
        viewModelScope.launch { _effects.send(DoctorListEffect.Done) }
    }
}
