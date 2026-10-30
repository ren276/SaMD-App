package com.example.samdapp.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.sync.OwnerMismatchHold
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.connectivity.BackendLocalNetwork
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.sync.DrainFailure
import com.example.samdapp.domain.sync.FailedSyncRecord
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
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

/** One-shot events for Home. */
sealed interface HomeEffect {
    /** Shown once as a snackbar after "Sync now" finishes. */
    data class ShowSyncNowResult(val message: SyncNowMessage) : HomeEffect
}

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
    /** The gate's decision for the backend host at the last evaluation (memo P2-R5). */
    val localNetworkBlocked: Boolean = false,
    /** The persisted owner-mismatch hold, observed (memo P2-R9). */
    val ownerMismatchHeld: Boolean = false,
)

internal const val HOLD_RETRY_START_MS = 1_000L
internal const val HOLD_RETRY_MAX_MS = 30_000L

/**
 * The hold as Home reads it (memo P2-R12). [OwnerMismatchHold.held] keeps its contract: a read
 * error propagates. Home catches it here: it emits the last known value, or `true` when none has
 * been read yet (fail closed: an unknown hold suppresses the local-network caption and its action,
 * and never invents owner-mismatch copy), then resubscribes after 1 s, doubling to a 30 s cap, for
 * as long as Home collects. The wait restarts at 1 s after a read succeeds. [log] never gets the
 * exception text. Emitting `false` on an error is forbidden: it would hide a real hold.
 */
internal fun Flow<Boolean>.holdFailingClosed(log: (String) -> Unit): Flow<Boolean> = flow {
    var last: Boolean? = null
    var failures = 0
    emitAll(
        onEach { last = it; failures = 0 }.retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            val wait = minOf(HOLD_RETRY_START_MS shl minOf(failures, 5), HOLD_RETRY_MAX_MS)
            failures++
            log("owner-mismatch hold read failed; keeping ${last ?: "held (unknown)"}, retry in $wait ms")
            emit(last ?: true)
            delay(wait)
            true
        },
    )
}

private const val TAG = "HomeViewModel"

// The host JVM's android.jar throws from Log; a failed log line must never fail Home.
private fun logHoldReadFailure(message: String) {
    runCatching { Log.w(TAG, message) }
}

/** Home's caption: [syncCaption] over the drain failure Home should show (see [homeDrainFailure]). */
fun HomeUiState.caption(isOnline: Boolean): SyncCaption =
    syncCaption(sync.copy(lastDrainFailure = homeDrainFailure(sync.lastDrainFailure, localNetworkBlocked, ownerMismatchHeld)), isOnline)

@HiltViewModel
class HomeViewModel @Inject constructor(
    getTodaysPatientsUseCase: GetTodaysPatientsUseCase,
    private val syncStatus: SyncStatus,
    private val authSession: AuthSession,
    private val caseRecordRepository: CaseRecordRepository,
    private val patientRepository: PatientRepository,
    private val ownerMismatchHold: OwnerMismatchHold,
    private val backendLocalNetwork: BackendLocalNetwork,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        // Day-scoped roster only — the repository never exposes the full patient table.
        viewModelScope.launch {
            getTodaysPatientsUseCase().collect { patients ->
                _uiState.update { it.copy(todaysPatients = patients, isLoadingRoster = false) }
            }
        }
        viewModelScope.launch {
            // Every emission re-asks the gate with the host read live (P2-R5); the hold is observed,
            // never read once (P2-R9), so a release while Home is visible shows on the next one.
            combine(syncStatus.state, ownerMismatchHold.held.holdFailingClosed(::logHoldReadFailure)) { syncState, held -> syncState to held }
                .collect { (syncState, held) ->
                    val blocked = evaluateLocalNetwork()
                    _uiState.update { it.copy(sync = syncState, ownerMismatchHeld = held, localNetworkBlocked = blocked) }
                }
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

    /** The Result is shown, not discarded. The message is chosen from a direct read taken after
     *  the sync returns ([SyncStatus.stateNow]), never from the Flow-backed state, so it reflects
     *  what the drain committed even if an emission has not caught up yet. */
    fun onSyncNow() {
        viewModelScope.launch {
            val result = syncStatus.syncNow()
            _effects.send(HomeEffect.ShowSyncNowResult(syncNowMessage(result, syncStatus.stateNow())))
        }
    }

    // In memory only: this process saw the gate block the backend host (memo 5.5).
    private var sawLocalNetworkBlock = false

    private fun evaluateLocalNetwork(): Boolean = backendLocalNetwork.blocked().also { if (it) sawLocalNetworkBlock = true }

    /**
     * Memo 5.5: nothing else wakes the drain when the grant comes back from Settings. If this
     * process saw the block, or the last drain was refused by it, and the gate no longer blocks and
     * something is waiting, run exactly one Sync now (same enqueue as the button, REPLACE, no snackbar). The flag
     * then reflects only this evaluation.
     */
    fun onResume() {
        val hadBlock = sawLocalNetworkBlock
        sawLocalNetworkBlock = false
        val blocked = evaluateLocalNetwork()
        val sync = _uiState.value.sync
        _uiState.update { it.copy(localNetworkBlocked = blocked) }
        val refusedBefore = hadBlock || sync.lastDrainFailure == DrainFailure.LOCAL_NETWORK_NOT_ALLOWED
        if (!blocked && refusedBefore && sync.outboxPending + sync.auditPending > 0) syncOnResume()
    }

    /** The same enqueue as [onSyncNow] (SyncStatus.syncNow, REPLACE), without its snackbar: the
     *  worker did not ask for this one, so a result toast would be noise. */
    private fun syncOnResume() {
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
