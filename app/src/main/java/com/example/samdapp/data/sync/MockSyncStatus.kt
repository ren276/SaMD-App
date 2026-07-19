package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Sync Up" for the offline-first flow: every case that was assigned a doctor while offline sits
 * as `PENDING_SYNC` (see [CaseRecordRepository.assignDoctor]) until this runs. There's still no
 * real backend (see agent_docs/hardening.md/docs/sync-design.md) — sending a case here just flips
 * its local status to `SENT_TO_DOCTOR` rather than transmitting anything — but unlike the previous
 * version, [pendingCount] is the real queued-case count and [syncNow] refuses to run offline
 * instead of always reporting success.
 *
 * Also auto-syncs the moment the app goes online — either the real network returning or the
 * worker flipping the manual toggle back on — so nothing sits queued waiting for someone to
 * remember to tap the button. [syncNow] itself stays public/manual too, for "send right now"
 * (e.g. right after confirming a doctor while already online).
 */
@Singleton
class MockSyncStatus @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val connectivityController: ConnectivityController,
) : SyncStatus {

    private val isSyncing = MutableStateFlow(false)
    private val lastSyncedAt = MutableStateFlow<Instant?>(null)
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        autoSyncScope.launch {
            // drop(1): skip the state as observed at startup, only react to actual transitions
            // (an app that launches already online shouldn't trigger a sync it didn't ask for).
            connectivityController.isOnline.distinctUntilChanged().drop(1).filter { it }.collect {
                if (caseRecordRepository.observePendingSyncCount().first() > 0) syncNow()
            }
        }
    }

    override val state: Flow<SyncState> = combine(
        caseRecordRepository.observePendingSyncCount(),
        isSyncing,
        lastSyncedAt,
    ) { pendingCount, syncing, lastSynced ->
        SyncState(lastSyncedAt = lastSynced, pendingCount = pendingCount, isSyncing = syncing)
    }

    override suspend fun syncNow(): Result<Unit> {
        if (!connectivityController.isOnline.first()) {
            return Result.failure(IllegalStateException("No network available — can't sync while offline"))
        }
        isSyncing.value = true
        delay(SIMULATED_SYNC_MILLIS)
        val result = caseRecordRepository.sendAllPendingCases()
        lastSyncedAt.value = Instant.now()
        isSyncing.value = false
        return result
    }

    private companion object {
        const val SIMULATED_SYNC_MILLIS = 1_200L
    }
}
