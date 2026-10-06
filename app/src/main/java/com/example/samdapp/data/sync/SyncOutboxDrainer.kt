package com.example.samdapp.data.sync

import com.example.samdapp.data.local.auth.AuthTokenStore
import com.example.samdapp.data.remote.SyncPushResult
import com.example.samdapp.data.remote.SyncPushService
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.SyncRetryClass
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
    /** Defaulted so the many drainer tests that do not care about it stay unchanged; Hilt
     *  injects the singleton. */
    private val drainOutcomeStore: DrainOutcomeStore = DrainOutcomeStore(),
) {
    // Process-wide: the periodic (sync_push_periodic) and one-time (sync_push_now) WorkManager
    // requests are different unique-work names, so their SyncPushWorker instances can run
    // concurrently. Without this, two overlapping drains could both pack the same PENDING rows
    // into two different batch_ids and send them twice. @Singleton on this class (not just the
    // Mutex field) is what guarantees both worker instances share the same Mutex object.
    private val drainMutex = Mutex()

    /** Records the outcome in [drainOutcomeStore] either way, so Home can say "Could not send"
     *  while the outbox cannot drain and stop saying it the moment a drain succeeds. */
    suspend fun drain(): Result<Unit> = drainMutex.withLock { drainLocked() }
        .also { result -> drainOutcomeStore.record(result.exceptionOrNull()?.let(::drainFailureFor)) }

    /**
     * **Termination, stated exhaustively, because this is a `while (true)` over a table the loop
     * body also writes to.**
     *
     * Each pass collects rows, sends them, and applies their acks. A pass ends the loop when the
     * collect comes back empty or a send fails. The danger S-2 introduces is a row that leaves
     * the pass still eligible: before RETRYABLE existed every acked row moved to a state the
     * predicate excludes, so the set strictly shrank. A RETRYABLE row does NOT leave the
     * predicate, so it would be collected again on the very next pass, inside the same drain,
     * forever.
     *
     * Two independent guards, and the loop needs only one of them to terminate:
     *
     * 1. `RETRY_MIN_INTERVAL`, in SQL. Every DAO's drain query excludes a row whose
     *    `lastSyncAttemptAt` is newer than the cutoff, and `applySyncResult` stamps that column
     *    on every ack. So an acked RETRYABLE row is ineligible for the next five minutes and
     *    cannot be re-collected by this drain.
     * 2. [attempted], here, in memory. A row whose ack never applied (the guarded UPDATE matched
     *    zero rows, because a clinical edit changed `localModifiedAt` while the record was in
     *    flight) keeps its old timestamp and IS still eligible, so guard 1 does not cover it.
     *    That case predates S-2 and could already spin. This set closes it: a pass that produces
     *    no record this drain has not already attempted ends the loop.
     *
     * Guard 2 alone bounds the loop at one pass per distinct record id, which is finite. Guard 1
     * is what stops the NEXT drain from immediately re-trying, and the attempt cap is what stops
     * the drain after that. A row re-collected on a later drain is the intended behaviour, not a
     * spin: that is how a 23503 child syncs once its parent lands.
     */
    private suspend fun drainLocked(): Result<Unit> {
        val attempted = mutableSetOf<Pair<String, String>>()

        // Seeded with the resumed batch's members BEFORE the loop, not left empty. A resumed
        // batch is an attempt like any other, and a row it acks as RETRYABLE is still collectable
        // the instant the loop below starts. MEASURED: without this seeding, a crash-then-resume
        // charged that row two attempts in one drain rather than one, because the resume's ack
        // landed outside the guard and the first loop pass picked the row straight back up.
        // Guard 1 (the SQL interval) would have hidden this in production and not in the fake,
        // which is exactly why the guard is not allowed to depend on it.
        resumeInFlightBatch()?.let { resumed ->
            attempted += resumed.members
            if (resumed.result.isFailure) return resumed.result
        }
        while (true) {
            val pending = repository.collectPendingRecords()
                .filter { (it.table to it.id) !in attempted }
            if (pending.isEmpty()) return Result.success(Unit)
            attempted += pending.map { it.table to it.id }
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
                    // The second, device-local writer of a `rejected` ack, and the only one the
                    // backend never sees: this record was never sent, so it is absent server side
                    // by construction rather than by the savepoint analysis that covers real
                    // rejections. TERMINAL because the same bytes will exceed the same ceiling
                    // forever; only a smaller record helps. Set explicitly, because a synthesized
                    // ack that left this null would silently inherit the conservative default and
                    // this path's own reasoning would live nowhere.
                    retryClass = SyncRetryClass.TERMINAL.name,
                ),
                sentLocalModifiedAt = record.clientUpdatedAt,
            )
        }
    }

    /** What a resume did: the outcome, plus which (table, id) pairs it attempted so
     *  [drainLocked] can exclude them from its own first pass. */
    private data class ResumedBatch(val result: Result<Unit>, val members: Set<Pair<String, String>>)

    /** Null if there was nothing to resume. A non-null result must be checked by the caller:
     *  [drain] stops the whole run on failure rather than piling a fresh batch on top of an
     *  un-acked one. */
    private suspend fun resumeInFlightBatch(): ResumedBatch? {
        val saved = inFlightBatchStore.load().getOrElse { e ->
            // Unreadable, not "none": proceeding as if there were no in-flight batch could mint a
            // fresh batch_id for rows the backend already applied under the lost batch_id. Fail
            // the whole drain instead — see InFlightBatchStore.load's KDoc.
            return ResumedBatch(Result.failure(e), emptySet())
        } ?: return null
        val savedRevisions = saved.members.associate { (it.table to it.id) to it.localModifiedAtEpochMilli }
        val records = repository.collectPendingRecords().filter { (it.table to it.id) in savedRevisions }

        if (records.isEmpty()) {
            // Every member of the saved batch already left PENDING some other way (e.g. a prior
            // partial ack-apply before the crash) — nothing left to resend under this batch_id.
            inFlightBatchStore.clear()
            return null
        }
        return ResumedBatch(
            sendAndApply(SyncBatch(saved.batchId, records), alreadyPersisted = true, revisionOverrides = savedRevisions),
            records.map { it.table to it.id }.toSet(),
        )
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
                Result.failure(SyncPushFailedException(result.code, result.message))
            }
        }
    }
}
