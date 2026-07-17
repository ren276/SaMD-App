package com.example.samdapp.presentation.patientsummary

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationChain
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.groupIntoChains
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.usecase.ReceiveDoctorPrescriptionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [caseRecordId]/[caseStatus] back the async doctor-response follow-up (REQ-RX-01/03): the doctor's
 * own review happens on a separate channel, out of scope here, so this screen is where the PHC
 * worker checks whether that channel has produced a response yet — same day, same patient, reached
 * only through the day-scoped roster (no new "all patients" query, REQ-ROS-02 stays intact).
 */
data class PatientSummaryUiState(
    val patient: Patient? = null,
    val isLoading: Boolean = true,
    val caseRecordId: String? = null,
    val encounterId: String? = null,
    val caseStatus: CaseStatus? = null,
    val isCheckingForResponse: Boolean = false,
    val noResponseYet: Boolean = false,
    /** Flat visit history, newest first — the source for the "mark as follow-up" picker (you follow
     *  up a specific prior visit, so this stays ungrouped). */
    val history: List<ConsultationHistoryEntry> = emptyList(),
    /** [history] grouped into follow-up chains — one entry per chain, represented by its latest
     *  visit. This is what Consultation History renders, so the list stays clean (one row per
     *  chain, not one per follow-up). */
    val chains: List<ConsultationChain> = emptyList(),
    val isLoadingHistory: Boolean = true,
) {
    val canCheckForDoctorResponse: Boolean get() = caseStatus == CaseStatus.SENT_TO_DOCTOR && !isCheckingForResponse
    val canViewReport: Boolean get() = caseRecordId != null
}

@Stable
interface PatientSummaryActions {
    fun onCheckForDoctorResponse()
}

@HiltViewModel(assistedFactory = PatientSummaryViewModel.Factory::class)
class PatientSummaryViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val patientRepository: PatientRepository,
    private val caseRecordRepository: CaseRecordRepository,
    private val encounterRepository: EncounterRepository,
    private val receiveDoctorPrescriptionUseCase: ReceiveDoctorPrescriptionUseCase,
) : ViewModel(), PatientSummaryActions {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): PatientSummaryViewModel
    }

    private val _uiState = MutableStateFlow(PatientSummaryUiState())
    val uiState: StateFlow<PatientSummaryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                patientRepository.observePatient(patientId),
                caseRecordRepository.observeLatestForPatient(patientId),
            ) { patient, caseRecord -> patient to caseRecord }
                .collect { (patient, caseRecord) ->
                    _uiState.update {
                        it.copy(
                            patient = patient,
                            isLoading = false,
                            caseRecordId = caseRecord?.id,
                            encounterId = caseRecord?.encounterId,
                            caseStatus = caseRecord?.status,
                        )
                    }
                }
        }
        viewModelScope.launch {
            encounterRepository.observeHistoryForPatient(patientId).collect { history ->
                _uiState.update { it.copy(history = history, chains = history.groupIntoChains(), isLoadingHistory = false) }
            }
        }
    }

    override fun onCheckForDoctorResponse() {
        val current = _uiState.value
        val caseRecordId = current.caseRecordId
        val encounterId = current.encounterId
        if (caseRecordId == null || encounterId == null || !current.canCheckForDoctorResponse) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingForResponse = true, noResponseYet = false) }
            val prescription = receiveDoctorPrescriptionUseCase(caseRecordId, patientId, encounterId).getOrNull()
            _uiState.update { it.copy(isCheckingForResponse = false, noResponseYet = prescription == null) }
        }
    }
}
