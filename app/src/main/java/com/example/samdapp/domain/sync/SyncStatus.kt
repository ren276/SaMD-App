package com.example.samdapp.domain.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class SyncState(
    val lastSyncedAt: Instant? = null,
    val pendingCount: Int = 0,
    val isSyncing: Boolean = false,
)

/**
 * Sync status surface for the roster/home screen. There is no backend yet — real
 * WorkManager-driven sync and the offline snapshot/restore + ACID reconciliation are
 * deferred (see agent_docs/hardening.md and docs/sync-design.md). This interface is
 * the seam the real sync engine implements later; the current implementation only reports
 * and simulates state, it does not move any data off the device.
 */
interface SyncStatus {
    val state: Flow<SyncState>
    suspend fun syncNow(): Result<Unit>
}
