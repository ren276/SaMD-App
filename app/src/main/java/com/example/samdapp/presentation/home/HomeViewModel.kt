package com.example.samdapp.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Crash-recovery resume prompt (item 5, privacy/UX hardening pass): a `DRAFT` case this worker
 *  started but never reached Acknowledgement/save for — [patientName] is best-effort (null if the
 *  patient record can't be loaded), never blocking the prompt itself. */
data class ResumableEncounter(
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val patientName: String?,
)

data class HomeUiState(
    val todaysPatients: List<Patient> = emptyList(),
    val isLoadingRoster: Boolean = true,
    val sync: SyncState = SyncState(),
    val resumableEncounter: ResumableEncounter? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    getTodaysPatientsUseCase: GetTodaysPatientsUseCase,
    private val syncStatus: SyncStatus,
    private val authSession: AuthSession,
    private val caseRecordRepository: CaseRecordRepository,
    private val patientRepository: PatientRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Day-scoped roster only — the repository never exposes the full patient table.
        viewModelScope.launch {
            getTodaysPatientsUseCase().collect { patients ->
                _uiState.update { it.copy(todaysPatients = patients, isLoadingRoster = false) }
            }
        }
        viewModelScope.launch {
            syncStatus.state.collect { syncState -> _uiState.update { it.copy(sync = syncState) } }
        }
        viewModelScope.launch {
            authSession.currentUser()
                .flatMapLatest { session ->
                    if (session == null) flowOf(null) else caseRecordRepository.observeResumableDraftForUser(session.userId)
                }
                .flatMapLatest { draft ->
                    if (draft == null) {
                        flowOf(null as ResumableEncounter?)
                    } else {
                        patientRepository.observePatient(draft.patientId).flatMapLatest { patient ->
                            flowOf(
                                ResumableEncounter(
                                    patientId = draft.patientId,
                                    encounterId = draft.encounterId,
                                    caseRecordId = draft.id,
                                    patientName = patient?.fullName,
                                ),
                            )
                        }
                    }
                }
                .collect { resumable -> _uiState.update { it.copy(resumableEncounter = resumable) } }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch { syncStatus.syncNow() }
    }
}
