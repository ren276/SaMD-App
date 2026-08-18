package com.example.samdapp.domain.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class SyncState(
    val lastSyncedAt: Instant? = null,
    val pendingCount: Int = 0,
    val isSyncing: Boolean = false,
    /** Rows the backend judged malformed (`rejected`, SAMD-SYNC-6xxx) and the outbox has
     *  stopped retrying (Phase 6b). Phase 7's admin view surfaces these; this field only makes
     *  the count queryable, no UI here. */
    val failedCount: Int = 0,
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
}
