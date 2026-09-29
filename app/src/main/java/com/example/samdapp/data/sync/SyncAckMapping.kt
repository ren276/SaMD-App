package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.CONSERVATIVE_SYNC_RETRY_CLASS
import com.example.samdapp.domain.model.SyncRetryClass
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.parseSyncRetryClass

/** api-contract.md section 6.1's Android handling rule, as one table-driven mapping, pulled out
 *  of [RoomSyncOutboxRepository.applyAck] so it is unit-testable without a Room database
 *  (SyncAckMappingTest). `applied`/`stale`/`duplicate` all mean "the server has this row, stop
 *  pushing it"; `conflict` means "surfaced for review, do not retry blindly".
 *
 *  `rejected` is no longer one answer. It branches on the backend's own `retry_class`, which S-1
 *  put on the wire and which this function must read rather than infer:
 *
 *  - [SyncRetryClass.RETRYABLE] to [SyncState.RETRYABLE], NOT terminal. The record is fine and
 *    something outside it has to change first, almost always a parent row still in this same
 *    outbox. Before this, such a row became FAILED, which no drain re-collects, so it was
 *    destroyed permanently instead of syncing on the next drain.
 *  - [SyncRetryClass.TERMINAL] and [SyncRetryClass.CONFLICT] both to [SyncState.FAILED],
 *    exactly as every rejection did before. They are one state here and two different screens in
 *    S-3, which is what `syncErrorCode` and `syncErrorMessage` are now persisted for.
 *
 *  The cap is NOT applied here. A [SyncState.RETRYABLE] returned by this function becomes FAILED
 *  carrying [RETRY_EXHAUSTED_CODE] inside the DAO's own `applySyncResult`, in the same statement
 *  that increments the count, because only there is the row's current count readable without a
 *  second round trip that another writer could race.
 *
 *  **Null is an unknown status, and it is no longer a throw. Decided in S-3.**
 *
 *  Until S-3 this called `error(...)`. That was the same defect S-1 had already fixed one layer
 *  down for an unknown TABLE, in the very function that consumes this one: an exception here
 *  escapes [RoomSyncOutboxRepository.applyAck], so every ack AFTER it in the same batch is never
 *  applied either, the valid ones included, and `InFlightBatchStore` is never cleared. One
 *  unrecognised word from the backend became a whole-outbox outage, and a silent one, because
 *  nobody watches a WorkManager failure.
 *
 *  S-1 left it throwing anyway, and was right to at the time. The safe alternative is to skip
 *  the ack, which leaves the row PENDING, and before the drain loop was bounded a permanently
 *  unackable row could be re-collected inside a single drain without end. S-2 bounded it twice
 *  over: the in-drain `attempted` set, and `RETRY_MIN_INTERVAL` in every drain predicate. The
 *  skip now costs one record's bytes per fifteen-minute drain, which is exactly what any PENDING
 *  row costs.
 *
 *  So an unknown status from a future backend is recorded in
 *  `RoomSyncOutboxRepository.skippedAcks`, logged, and dropped. The row keeps the state it had,
 *  which is PENDING, and is resent on the next drain; S-1's absence analysis establishes that
 *  resending any record type is safe. The rest of the batch applies normally. It never reaches a
 *  worker, deliberately: an unknown ack status is a device/backend mirror break, which is a bug
 *  for an engineer, not a task for a health worker with a patient in front of them. */
fun SyncResultDto.toLocalSyncState(): SyncState? = when (status) {
    "applied", "stale", "duplicate" -> SyncState.SYNCED
    "conflict" -> SyncState.CONFLICT
    "rejected" -> when (retryClassOrConservative()) {
        SyncRetryClass.RETRYABLE -> SyncState.RETRYABLE
        SyncRetryClass.TERMINAL, SyncRetryClass.CONFLICT -> SyncState.FAILED
    }
    else -> null
}

/**
 * The backend's classification for this result, or [CONSERVATIVE_SYNC_RETRY_CLASS] when none is
 * available. Total over every wire value by construction: [parseSyncRetryClass] returns null for
 * anything it does not recognise and this collapses null to the conservative value, so there is
 * no input that throws and none that silently succeeds.
 *
 * **This is live as of S-2.** Under S-1 it was deliberately inert: [toLocalSyncState] mapped
 * every `rejected` to [SyncState.FAILED] whatever this returned, because acting on a
 * [SyncRetryClass.RETRYABLE] rejection needs a SyncState that did not exist and a drain that
 * re-collects it. Both now exist, so the branch above reads this. The inertness assertion that
 * guarded the gap has been replaced, not deleted, by
 * `SyncRetryClassMirrorTest.a RETRYABLE rejection is no longer terminal`.
 */
fun SyncResultDto.retryClassOrConservative(): SyncRetryClass =
    parseSyncRetryClass(retryClass) ?: CONSERVATIVE_SYNC_RETRY_CLASS
