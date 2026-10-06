package com.example.samdapp.testutil

import com.example.samdapp.data.remote.SyncPushResult
import com.example.samdapp.data.remote.SyncPushService
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncPushResponseDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.data.sync.SyncOutboxRepository
import com.example.samdapp.domain.model.SyncRetryClass
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
    /** The backend's PHI-safe message, carried alongside the code because the two together are
     *  what `syncFailureReasonFor` needs: a duplicate and a validation reject share one code. */
    val failedMessages = mutableMapOf<Pair<String, String>, String?>()
    /** Set by a test that wants a named row; absent exercises the real null-name fallback. */
    val patientNames = mutableMapOf<Pair<String, String>, String>()
    /** Rows a RETRYABLE ack left in the queue. The whole point of S-2: these are NOT removed
     *  from [pending], because a retryable rejection must be drained again. */
    val retryableIds = mutableListOf<Pair<String, String>>()
    val requeuedIds = mutableListOf<Pair<String, String>>()
    /** Per (table, id) acked-attempt count, mirroring the real `syncAttemptCount` column so a
     *  drainer test can exercise the cap without a Room database. */
    val attemptCounts = mutableMapOf<Pair<String, String>, Int>()

    override suspend fun collectPendingRecords(): List<SyncRecordDto> = pending.toList()

    /** Acks whose status this build does not recognise, mirroring
     *  `RoomSyncOutboxRepository.skippedAcks`. S-3 made an unknown status a recorded skip
     *  instead of a throw; the fake has to record it too, or a drainer test cannot tell "skipped
     *  one ack" from "applied it". */
    val skippedAcks = mutableListOf<Triple<String, String, String>>()

    override suspend fun applyAck(result: SyncResultDto, sentLocalModifiedAt: java.time.Instant) {
        // toLocalSyncState() first, same as RoomSyncOutboxRepository.applyAck: an unknown status
        // is recorded and skipped before anything is mutated, leaving the row PENDING and in the
        // queue, which is what makes it resend on the next drain.
        val localState = result.toLocalSyncState() ?: run {
            skippedAcks += Triple(result.table, result.id, result.status)
            return
        }
        val key = result.table to result.id
        val attempts = (attemptCounts[key] ?: 0) + 1
        attemptCounts[key] = if (localState == com.example.samdapp.domain.model.SyncState.SYNCED) 0 else attempts
        // A RETRYABLE row stays queued; every other outcome leaves the queue. Mirrors the real
        // drain predicate, where RETRYABLE is still collectable and the rest are not.
        val exhausted = localState == com.example.samdapp.domain.model.SyncState.RETRYABLE &&
            attempts >= com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS
        if (localState != com.example.samdapp.domain.model.SyncState.RETRYABLE || exhausted) {
            pending.removeAll { it.table == result.table && it.id == result.id }
        }
        if (exhausted) {
            failedIds += key
            failedCodes[key] = com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE
            failedCount.value = failedIds.size
            return
        }
        when (localState) {
            com.example.samdapp.domain.model.SyncState.SYNCED -> syncedIds += key
            com.example.samdapp.domain.model.SyncState.CONFLICT -> conflictedIds += key
            com.example.samdapp.domain.model.SyncState.FAILED -> {
                failedIds += key
                failedCodes[key] = result.code
                failedMessages[key] = result.message
                failedCount.value = failedIds.size
            }
            com.example.samdapp.domain.model.SyncState.RETRYABLE -> retryableIds += key
            com.example.samdapp.domain.model.SyncState.PENDING -> Unit
        }
    }

    override suspend fun requeueFailed(table: String, id: String) {
        requeuedIds += table to id
        attemptCounts[table to id] = 0
        failedIds.removeAll { it == (table to id) }
        failedCount.value = failedIds.size
    }

    override fun observeFailedCount() = failedCount.asStateFlow()

    /** Mirrors the real repository closely enough for a ViewModel test: FAILED rows only, never
     *  a RETRYABLE one, classified by the same
     *  [com.example.samdapp.domain.model.syncFailureReasonFor] the real one calls. */
    override suspend fun failedRecords(): List<com.example.samdapp.domain.sync.FailedSyncRecord> =
        failedIds.map { (table, id) ->
            com.example.samdapp.domain.sync.FailedSyncRecord(
                table = table,
                recordId = id,
                patientName = patientNames[table to id],
                recordedAt = java.time.Instant.EPOCH,
                reason = com.example.samdapp.domain.model.syncFailureReasonFor(
                    com.example.samdapp.domain.model.SyncState.FAILED, failedCodes[table to id],
                    failedMessages[table to id],
                ),
            )
        }

}

/** Fakes the `/sync/push` backend closely enough to exercise crash-resume: [storedResponses] is
 *  keyed by `batch_id`, mirroring the backend's 24h idempotency store (api-contract.md §6.1) — a
 *  replayed `batch_id` returns the same stored response without "re-applying" anything twice.
 *  [rejectedRecordIds] lets a test simulate a malformed row. */
class FakeSyncPushService(
    private val rejectedRecordIds: Set<String> = emptySet(),
    /** Ids the backend rejects as RETRYABLE (a 23503 whose parent has not landed), as opposed to
     *  [rejectedRecordIds], which it rejects terminally. Kept separate so a test can express
     *  "this row is fine, its parent just is not here yet". */
    private val retryableRecordIds: Set<String> = emptySet(),
    /** Ids that stop being retryable once [parentLanded] flips, modelling the parent arriving on
     *  a later drain. */
    private val healsWhenParentLands: Set<String> = emptySet(),
    /** Ids the backend acks with a status word this build of the app has never heard of, which
     *  is what a backend that grew a new ack status looks like from here. */
    private val unknownStatusRecordIds: Set<String> = emptySet(),
) : SyncPushService {
    var parentLanded: Boolean = false
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
            val stillRetryable = record.id in retryableRecordIds &&
                !(record.id in healsWhenParentLands && parentLanded)
            if (record.id in unknownStatusRecordIds) {
                SyncResultDto(table = record.table, id = record.id, status = "quarantined")
            } else if (record.id in rejectedRecordIds) {
                rejected++
                SyncResultDto(
                    table = record.table, id = record.id, status = "rejected",
                    code = "SAMD-SYNC-6003", message = "simulated malformed record.",
                    retryClass = SyncRetryClass.TERMINAL.name,
                )
            } else if (stillRetryable) {
                rejected++
                SyncResultDto(
                    table = record.table, id = record.id, status = "rejected",
                    code = "SAMD-SYNC-6003", message = "a referenced record does not exist yet.",
                    retryClass = SyncRetryClass.RETRYABLE.name,
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
