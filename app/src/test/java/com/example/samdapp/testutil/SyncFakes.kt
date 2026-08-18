package com.example.samdapp.testutil

import com.example.samdapp.data.remote.SyncPushResult
import com.example.samdapp.data.remote.SyncPushService
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncPushResponseDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.data.sync.SyncOutboxRepository
import com.example.samdapp.data.sync.SyncOutboxScheduler
import com.example.samdapp.data.sync.toLocalSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stands in for [com.example.samdapp.data.sync.WorkManagerSyncOutboxScheduler] — no Context, no
 *  WorkManager test harness, so SyncStatusImplTest's ported MockSyncStatusTest behaviors stay
 *  plain-JVM. */
class FakeSyncOutboxScheduler(
    private val runNowResult: Result<Unit> = Result.success(Unit),
) : SyncOutboxScheduler {
    var ensurePeriodicWorkCallCount = 0
        private set
    var runNowAndAwaitCallCount = 0
        private set

    override fun ensurePeriodicWork() {
        ensurePeriodicWorkCallCount++
    }

    override suspend fun runNowAndAwait(): Result<Unit> {
        runNowAndAwaitCallCount++
        return runNowResult
    }
}

/** In-memory stand-in for [com.example.samdapp.data.sync.RoomSyncOutboxRepository]: same
 *  PENDING-selection / ack-application contract, no Room. [applyAck] uses the same
 *  `toLocalSyncState()` mapping the real repository does, so a caller can't tell the difference
 *  from the outside. */
class FakeSyncOutboxRepository(
    initial: List<SyncRecordDto> = emptyList(),
) : SyncOutboxRepository {
    private val pending = initial.toMutableList()
    private val failedCount = MutableStateFlow(0)
    val syncedIds = mutableListOf<Pair<String, String>>()
    val conflictedIds = mutableListOf<Pair<String, String>>()
    val failedIds = mutableListOf<Pair<String, String>>()
    val failedCodes = mutableMapOf<Pair<String, String>, String?>()

    override suspend fun collectPendingRecords(): List<SyncRecordDto> = pending.toList()

    override suspend fun applyAck(result: SyncResultDto, sentLocalModifiedAt: java.time.Instant) {
        // toLocalSyncState() first, same as RoomSyncOutboxRepository.applyAck: an unknown status
        // throws before anything is mutated, rather than silently dropping the pending row.
        val localState = result.toLocalSyncState()
        val key = result.table to result.id
        pending.removeAll { it.table == result.table && it.id == result.id }
        when (localState) {
            com.example.samdapp.domain.model.SyncState.SYNCED -> syncedIds += key
            com.example.samdapp.domain.model.SyncState.CONFLICT -> conflictedIds += key
            com.example.samdapp.domain.model.SyncState.FAILED -> {
                failedIds += key
                failedCodes[key] = result.code
                failedCount.value = failedIds.size
            }
            com.example.samdapp.domain.model.SyncState.PENDING -> Unit
        }
    }

    override fun observeFailedCount() = failedCount.asStateFlow()
}

/** Fakes the `/sync/push` backend closely enough to exercise crash-resume: [storedResponses] is
 *  keyed by `batch_id`, mirroring the backend's 24h idempotency store (api-contract.md §6.1) — a
 *  replayed `batch_id` returns the same stored response without "re-applying" anything twice.
 *  [rejectedRecordIds] lets a test simulate a malformed row. */
class FakeSyncPushService(
    private val rejectedRecordIds: Set<String> = emptySet(),
) : SyncPushService {
    val calls = mutableListOf<SyncPushRequestDto>()
    private val storedResponses = mutableMapOf<String, SyncPushResponseDto>()
    private val serverVersions = mutableMapOf<String, Int>()

    /** When true, the *next* call to [push] records the request as if the backend had applied it
     *  (so a later replay of the same batch_id returns the same stored response), then throws
     *  instead of returning — simulating the device losing the response after the backend
     *  already committed. Only fires once. The caller doesn't need to predict the batch_id in
     *  advance (it's minted fresh by [SyncBatchPacker] inside the drain call). */
    var crashOnNextPush: Boolean = false

    override suspend fun push(request: SyncPushRequestDto): SyncPushResult<SyncPushResponseDto> {
        calls += request
        storedResponses[request.batchId]?.let { return SyncPushResult.Success(it) }

        val response = buildResponse(request)
        storedResponses[request.batchId] = response

        if (crashOnNextPush) {
            crashOnNextPush = false
            throw java.io.IOException("simulated crash: connectivity dropped after the backend applied this batch")
        }
        return SyncPushResult.Success(response)
    }

    private fun buildResponse(request: SyncPushRequestDto): SyncPushResponseDto {
        var applied = 0
        var rejected = 0
        val results = request.records.map { record ->
            if (record.id in rejectedRecordIds) {
                rejected++
                SyncResultDto(
                    table = record.table, id = record.id, status = "rejected",
                    code = "SAMD-SYNC-6003", message = "simulated malformed record.",
                )
            } else {
                applied++
                val version = (serverVersions[record.id] ?: 0) + 1
                serverVersions[record.id] = version
                SyncResultDto(table = record.table, id = record.id, status = "applied", serverVersion = version)
            }
        }
        return SyncPushResponseDto(
            batchId = request.batchId, received = request.records.size, applied = applied,
            stale = 0, conflicted = 0, rejected = rejected, serverTime = "2026-08-18T00:00:00Z",
            results = results,
        )
    }
}
