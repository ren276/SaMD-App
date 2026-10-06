package com.example.samdapp.domain.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class SyncState(
    val lastSyncedAt: Instant? = null,
    val pendingCount: Int = 0,
    val isSyncing: Boolean = false,
    /** Records the outbox has stopped trying to send: the backend refused them on their merits
     *  (`rejected` with `retry_class` TERMINAL or CONFLICT), or S-2's attempt cap ran out, or
     *  they were too large to send at all, or the server acked `conflict` (record state CONFLICT,
     *  never resent on its own). S-3 renders this on Home as the count on a card that
     *  opens [SyncStatus.failedRecords]; before S-3 it was queryable and nothing read it.
     *
     *  Deliberately does NOT include `RETRYABLE` rows. Those are rows the device is still
     *  working on, bounded by [com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS], and a worker
     *  has no action for one. Counting them here would mean most of this number needed nothing
     *  from anybody, which is how a number stops being read. Every RETRYABLE row that genuinely
     *  needs a person arrives in this count on its own, the same working day, carrying
     *  [com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE]. */
    val failedCount: Int = 0,
    /** Clinical rows the outbox still owes the server and is still working on (PENDING plus
     *  RETRYABLE, nineteen tables). Not [pendingCount], which is the doctor-assignment queue. */
    val outboxPending: Int = 0,
    /** The same for `audit_log`, kept apart so an audit-only backlog is not shown as records. */
    val auditPending: Int = 0,
    /** Why the last drain failed, or null if it succeeded or none has failed since app start. */
    val lastDrainFailure: DrainFailure? = null,
)

/**
 * Sync status surface for the roster/home screen. Phase 6b replaced the simulated
 * [com.example.samdapp.data.sync.MockSyncStatus] with a real WorkManager-driven outbox
 * ([com.example.samdapp.data.sync.SyncStatusImpl]) that pushes every syncable table's `PENDING`
 * rows to `POST /sync/push` (api-contract.md §6.1, docs/sync-design.md §2). This interface's
 * shape is unchanged by that swap — it is the stable seam the real engine implements.
 */
interface SyncStatus {
    val state: Flow<SyncState>
    suspend fun syncNow(): Result<Unit>

    /** [syncNow] with the outbox drained in this process, under the drainer's own lock, instead of
     *  through a WorkManager request. For a caller that is already a background worker and needs
     *  the push to have finished, or failed, when this returns: it cannot be left waiting on a
     *  second queue, and a WorkManager retry backoff reads as a failure to it. Sends every queued
     *  case to the doctor queue exactly as [syncNow] does. */
    suspend fun syncNowInProcess(): Result<Unit>

    /** The same state as [state], read once and directly: the outbox counts from one-shot suspend
     *  DAO reads, not from a Flow. For a decision made right after [syncNow] returns, such as the
     *  "Sync now" message, which must reflect the drain's committed writes and not an emission
     *  that has not caught up yet. */
    suspend fun stateNow(): SyncState

    /** The rows behind [SyncState.failedCount], newest first, each already classified into the
     *  one cause and one action a worker is shown.
     *
     *  Suspend, and not a `Flow`, on purpose. [SyncState.failedCount] is already inside
     *  [state]'s `combine` and therefore already subscribed from app start, so the card costs
     *  nothing new. The list is twenty more queries and is fetched only when a worker opens it,
     *  which keeps it off the launch path the perf audit found contending on the SQLCipher pool
     *  (F2A-01). */
    suspend fun failedRecords(): List<FailedSyncRecord>

    /** Puts one FAILED record back in the queue, unchanged, via S-2's
     *  `SyncOutboxRepository.requeueFailed`. Offered only for the causes where the record is not
     *  believed to be wrong; see [com.example.samdapp.domain.model.SyncFailureReason.action].
     *  Idempotent, and a no-op for a record that has left FAILED since the list was read. */
    suspend fun sendFailedRecordAgain(record: FailedSyncRecord)
}
