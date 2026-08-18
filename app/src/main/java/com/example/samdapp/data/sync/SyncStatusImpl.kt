package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Phase 6b's real [SyncStatus], replacing the simulated `MockSyncStatus`. Two independent sync
 * concerns live behind this one seam, on purpose:
 *
 * - [caseRecordRepository]'s `PENDING_SYNC` -> `SENT_TO_DOCTOR` flip: the doctor-assignment queue,
 *   unchanged from `MockSyncStatus` (still doesn't touch the network — see that repository's own
 *   KDoc). [pendingCount] reads this queue, exactly as before: MockSyncStatusTest's six behaviors
 *   (offline refusal, auto-sync-on-transition, no-auto-sync-from-already-online-cold-start,
 *   pendingCount/state settling) are ported to SyncStatusImplTest unchanged in substance, with a
 *   [FakeSyncOutboxScheduler]/[FakeSyncOutboxRepository] standing in for the two new dependencies
 *   below so those six cases stay plain-JVM-testable.
 * - [syncOutboxScheduler]'s generic outbox drain: the twenty MIGRATION_12_13 tables' `PENDING`
 *   rows, pushed to `POST /sync/push` via [SyncPushWorker]. [SyncState.failedCount] surfaces
 *   [syncOutboxRepository]'s FAILED-row count (Phase 7's admin view is out of scope; this only
 *   makes the count queryable).
 *
 * These two never corrupt each other: draining `case_records`' transport `syncState` (via
 * [SyncOutboxRepository.applyAck] -> `CaseRecordDao.applySyncResult`) touches only the
 * `syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt` columns, never `status`
 * (the clinical `CaseStatus`, flipped only by [caseRecordRepository]'s own methods). Both call
 * [Instant.now] independently and write disjoint columns, so there is no ordering hazard between
 * them even when [syncNow] runs both in the same round.
 */
@Singleton
class SyncStatusImpl @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val connectivityController: ConnectivityController,
    private val syncOutboxScheduler: SyncOutboxScheduler,
    private val syncOutboxRepository: SyncOutboxRepository,
) : SyncStatus {

    private val isSyncing = MutableStateFlow(false)
    private val lastSyncedAt = MutableStateFlow<Instant?>(null)
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Idempotent (ExistingPeriodicWorkPolicy.KEEP internally) — safe on every construction,
        // not just a genuine first app start.
        syncOutboxScheduler.ensurePeriodicWork()

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
        syncOutboxRepository.observeFailedCount(),
    ) { pendingCount, syncing, lastSynced, failedCount ->
        SyncState(lastSyncedAt = lastSynced, pendingCount = pendingCount, isSyncing = syncing, failedCount = failedCount)
    }

    override suspend fun syncNow(): Result<Unit> {
        if (!connectivityController.isOnline.first()) {
            return Result.failure(IllegalStateException("No network available — can't sync while offline"))
        }
        isSyncing.value = true
        return try {
            val caseResult = caseRecordRepository.sendAllPendingCases()
            val outboxResult = syncOutboxScheduler.runNowAndAwait()
            val result = caseResult.fold(onSuccess = { outboxResult }, onFailure = { Result.failure(it) })
            if (result.isSuccess) lastSyncedAt.value = Instant.now()
            result
        } finally {
            isSyncing.value = false
        }
    }
}
