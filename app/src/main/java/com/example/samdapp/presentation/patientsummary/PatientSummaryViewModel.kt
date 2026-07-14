package com.example.samdapp.presentation.patientsummary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PatientSummaryUiState(val patient: Patient? = null, val isLoading: Boolean = true)

@HiltViewModel(assistedFactory = PatientSummaryViewModel.Factory::class)
class PatientSummaryViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val patientRepository: PatientRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): PatientSummaryViewModel
    }

    private val _uiState = MutableStateFlow(PatientSummaryUiState())
    val uiState: StateFlow<PatientSummaryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            patientRepository.observePatient(patientId).collect { patient ->
                _uiState.update { it.copy(patient = patient, isLoading = false) }
            }
        }
    }
}
