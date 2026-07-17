package com.example.samdapp.presentation.patientsummary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.PatientFacingAuditEntry
import com.example.samdapp.domain.audit.toPatientFacingEntries
import com.example.samdapp.domain.repository.AuditLogRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PatientAuditUiState(
    val isLoading: Boolean = true,
    val entries: List<PatientFacingAuditEntry> = emptyList(),
)

@HiltViewModel(assistedFactory = PatientAuditViewModel.Factory::class)
class PatientAuditViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): PatientAuditViewModel
    }

    private val _uiState = MutableStateFlow(PatientAuditUiState())
    val uiState: StateFlow<PatientAuditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            auditLogRepository.observeForPatient(patientId).collect { entries ->
                _uiState.update { it.copy(isLoading = false, entries = entries.toPatientFacingEntries()) }
            }
        }
    }
}
