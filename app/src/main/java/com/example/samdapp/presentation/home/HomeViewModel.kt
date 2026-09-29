package com.example.samdapp.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.sync.FailedSyncRecord
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
    /** True while the worker has the failed-record list open. The list is a dialog over Home,
     *  not a destination: a worker who does not know they have a problem never navigates to a
     *  screen they have no reason to open, and discoverability is the whole point of it. */
    val isFailedRecordsOpen: Boolean = false,
    val isLoadingFailedRecords: Boolean = false,
    /** Fetched when the list is opened, not observed. [SyncState.failedCount] is already inside
     *  the sync flow this ViewModel collects, so the card that shows the number costs nothing;
     *  the twenty queries behind the list are kept off the launch path (perf audit F2A-01). */
    val failedRecords: List<FailedSyncRecord> = emptyList(),
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

    fun onOpenFailedRecords() {
        _uiState.update { it.copy(isFailedRecordsOpen = true, isLoadingFailedRecords = true) }
        viewModelScope.launch { loadFailedRecords() }
    }

    fun onDismissFailedRecords() {
        // The list is dropped, not kept: it is a snapshot, and a stale one is worse than a
        // second fetch. The count behind the card keeps flowing either way.
        _uiState.update {
            it.copy(isFailedRecordsOpen = false, isLoadingFailedRecords = false, failedRecords = emptyList())
        }
    }

    /** The only action the list offers, and only for the causes where the record is not believed
     *  to be wrong. Re-reads the list afterwards so the requeued row leaves it; the count on the
     *  card updates itself, because it is a Flow over the same rows. */
    fun onSendFailedRecordAgain(record: FailedSyncRecord) {
        viewModelScope.launch {
            syncStatus.sendFailedRecordAgain(record)
            loadFailedRecords()
        }
    }

    private suspend fun loadFailedRecords() {
        val records = syncStatus.failedRecords()
        _uiState.update { it.copy(failedRecords = records, isLoadingFailedRecords = false) }
    }
}
