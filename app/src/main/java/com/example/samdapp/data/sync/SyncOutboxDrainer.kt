package com.example.samdapp.data.sync

import com.example.samdapp.data.local.auth.AuthTokenStore
import com.example.samdapp.data.remote.SyncPushResult
import com.example.samdapp.data.remote.SyncPushService
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one full outbox drain: resume any batch the previous run died mid-flight on, then
 * pack and send everything currently PENDING, applying each batch's acks as it comes back.
 * Called by [SyncPushWorker.doWork] (the real, connectivity-constrained background path) and by
 * [SyncStatusImpl.syncNow] (immediate "sync now"), so both go through the exact same logic —
 * this class has no WorkManager/Android-framework dependency itself, so it is fully JVM-testable
 * against [FakeSyncPushService]-style fakes (SyncOutboxDrainerTest: large-backlog, crash-resume,
 * malformed-row scenarios).
 *
 * Crash-resume, the subtlest correctness point in Phase 6b: [InFlightBatchStore] persists a
 * batch's `batch_id` and member (table, id) list *before* that batch is sent, and only clears it
 * once that batch's ack has been applied locally. If this process dies between "backend applied"
 * and "ack applied", the next [drain] call's [resumeInFlightBatch] finds that leftover state and
 * resends the *same* rows under the *same* batch_id — which the backend's 24h idempotency store
 * (api-contract.md §6.1) answers with the original stored response verbatim, so the row ends up
 * applied exactly once. Minting a fresh batch_id on retry here would be the double-apply bug this
 * design exists to rule out.
 */
@Singleton
class SyncOutboxDrainer @Inject constructor(
    private val repository: SyncOutboxRepository,
    private val packer: SyncBatchPacker,
    private val pushService: SyncPushService,
    private val inFlightBatchStore: InFlightBatchStore,
    private val authTokenStore: AuthTokenStore,
) {
    // Process-wide: the periodic (sync_push_periodic) and one-time (sync_push_now) WorkManager
    // requests are different unique-work names, so their SyncPushWorker instances can run
    // concurrently. Without this, two overlapping drains could both pack the same PENDING rows
    // into two different batch_ids and send them twice. @Singleton on this class (not just the
    // Mutex field) is what guarantees both worker instances share the same Mutex object.
    private val drainMutex = Mutex()

    suspend fun drain(): Result<Unit> = drainMutex.withLock { drainLocked() }

    private suspend fun drainLocked(): Result<Unit> {
        resumeInFlightBatch()?.let { resumed -> if (resumed.isFailure) return resumed }

        while (true) {
            val pending = repository.collectPendingRecords()
            if (pending.isEmpty()) return Result.success(Unit)
            val packed = packer.pack(pending)
            failOversizedRecordsLocally(packed.oversized)
            for (batch in packed.batches) {
                val result = sendAndApply(batch, alreadyPersisted = false)
                if (result.isFailure) return result
            }
        }
    }

    /** A record too large to ever fit the backend's per-batch ceiling on its own is marked
     *  FAILED locally without a network round trip, so it can't block every other PENDING row
     *  behind it. No batch_id/InFlightBatchStore involvement — nothing was sent. */
    private suspend fun failOversizedRecordsLocally(oversized: List<SyncRecordDto>) {
        for (record in oversized) {
            repository.applyAck(
                SyncResultDto(
                    table = record.table,
                    id = record.id,
                    status = "rejected",
                    code = "SAMD-SYNC-RECORD-TOO-LARGE",
                    message = "Record exceeds the outbox's per-record size budget (${SyncBatchPacker.MAX_BYTES} bytes).",
                ),
                sentLocalModifiedAt = record.clientUpdatedAt,
            )
        }
    }

    /** Null if there was nothing to resume. A non-null result must be checked by the caller —
     *  [drain] stops the whole run on failure rather than piling a fresh batch on top of an
     *  un-acked one. */
    private suspend fun resumeInFlightBatch(): Result<Unit>? {
        val saved = inFlightBatchStore.load().getOrElse { e ->
            // Unreadable, not "none": proceeding as if there were no in-flight batch could mint a
            // fresh batch_id for rows the backend already applied under the lost batch_id. Fail
            // the whole drain instead — see InFlightBatchStore.load's KDoc.
            return Result.failure(e)
        } ?: return null
        val savedRevisions = saved.members.associate { (it.table to it.id) to it.localModifiedAtEpochMilli }
        val records = repository.collectPendingRecords().filter { (it.table to it.id) in savedRevisions }

        if (records.isEmpty()) {
            // Every member of the saved batch already left PENDING some other way (e.g. a prior
            // partial ack-apply before the crash) — nothing left to resend under this batch_id.
            inFlightBatchStore.clear()
            return null
        }
        return sendAndApply(SyncBatch(saved.batchId, records), alreadyPersisted = true, revisionOverrides = savedRevisions)
    }

    /** [revisionOverrides] carries the *originally persisted* sent-revision per (table, id) for a
     *  resumed batch, since [records] were re-collected from the DB and their current
     *  `localModifiedAt` may already differ from what was actually sent before the crash. A fresh
     *  (non-resumed) batch has no override — its records' own `clientUpdatedAt` IS the sent
     *  revision, because nothing else can have touched them between [SyncOutboxRepository
     *  .collectPendingRecords] and this call. */
    private suspend fun sendAndApply(
        batch: SyncBatch,
        alreadyPersisted: Boolean,
        revisionOverrides: Map<Pair<String, String>, Long> = emptyMap(),
    ): Result<Unit> {
        if (!alreadyPersisted) {
            inFlightBatchStore.save(
                InFlightBatch(
                    batch.batchId,
                    batch.records.map { InFlightBatchMember(it.table, it.id, it.clientUpdatedAt.toEpochMilli()) },
                ),
            )
        }
        val request = SyncPushRequestDto(
            batchId = batch.batchId,
            deviceId = authTokenStore.deviceId(),
            clientTime = Instant.now(),
            records = batch.records,
        )
        return when (val result = pushService.push(request)) {
            is SyncPushResult.Success -> {
                result.data.results.forEach { ackResult ->
                    val key = ackResult.table to ackResult.id
                    val sentRevisionMillis = revisionOverrides[key]
                        ?: batch.records.first { it.table == ackResult.table && it.id == ackResult.id }.clientUpdatedAt.toEpochMilli()
                    repository.applyAck(ackResult, sentLocalModifiedAt = Instant.ofEpochMilli(sentRevisionMillis))
                }
                inFlightBatchStore.clear()
                Result.success(Unit)
            }
            is SyncPushResult.Failure -> {
                // Deliberately do NOT clear the persisted in-flight batch here: whether this
                // failure means "never reached the backend" or "backend applied it but the
                // response never arrived", resending under this same batch_id on the next run is
                // safe either way (see this class's KDoc).
                Result.failure(IllegalStateException(result.message))
            }
        }
    }
}
