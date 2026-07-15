package com.example.samdapp.data.sync

import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock — simulates a sync round without a backend (see agent_docs/hardening.md: real sync is
 * deferred until infra exists). syncNow() just waits and stamps lastSyncedAt; it does not
 * transmit or reconcile anything. The real engine will replace this behind [SyncStatus].
 */
@Singleton
class MockSyncStatus @Inject constructor() : SyncStatus {

    private val _state = MutableStateFlow(SyncState())
    override val state: Flow<SyncState> = _state.asStateFlow()

    override suspend fun syncNow(): Result<Unit> {
        _state.update { it.copy(isSyncing = true) }
        delay(SIMULATED_SYNC_MILLIS)
        _state.update { SyncState(lastSyncedAt = Instant.now(), pendingCount = 0, isSyncing = false) }
        return Result.success(Unit)
    }

    private companion object {
        const val SIMULATED_SYNC_MILLIS = 1_200L
    }
}
