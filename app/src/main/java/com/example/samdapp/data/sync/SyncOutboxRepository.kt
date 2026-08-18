package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Aggregates the twenty syncable tables' DAOs (MIGRATION_12_13) behind one seam for
 * [SyncOutboxDrainer]. Deliberately a `data`-layer interface, not `domain/repository`: its shape
 * is the wire record ([SyncRecordDto]/[SyncResultDto]), not a domain model, and nothing above the
 * drainer needs to see it — [com.example.samdapp.data.sync.SyncStatusImpl] only reaches
 * [observeFailedCount] for the FAILED-count-observable requirement.
 *
 * [collectPendingRecords] selects only `syncState = PENDING` rows, per table, ordered by
 * `localModifiedAt` — never `CONFLICT` (surfaced for review, not retried blindly, per the ack
 * rule below) and never `FAILED`/`SYNCED` (terminal). [applyAck] is the single place that maps a
 * backend result's `status` to the local `syncState` transition, table-driven:
 *
 * - `applied`/`stale`/`duplicate` -> `SYNCED`, `serverVersion` updated when the ack carries one
 *   (`stale`/`duplicate` may not; [SyncState] is left untouched via `COALESCE` in that case, see
 *   each DAO's `applySyncResult`).
 * - `conflict` -> `CONFLICT`. Not re-packed by [collectPendingRecords] on the next run, so a
 *   conflicted row is never blindly resent with its stale `base_version` — it sits surfaced for
 *   review instead (api-contract.md §6.1: last-write-wins already ran server side, so a conflict
 *   here means a real `base_version` mismatch, not something worth silently retrying).
 * - `rejected` -> `FAILED`, `syncErrorCode` set to the returned SAMD-SYNC-6xxx code. Also excluded
 *   from the next [collectPendingRecords] call, which is what "stop retrying" means here: no
 *   separate retry-count column, the state transition itself is the guard.
 */
interface SyncOutboxRepository {
    suspend fun collectPendingRecords(): List<SyncRecordDto>

    /** [sentLocalModifiedAt] is the row's `localModifiedAt` at the moment this ack's record was
     *  packed into a batch (see [SyncRecordDto.clientUpdatedAt]). Every per-table `applySyncResult`
     *  guards its UPDATE on `localModifiedAt = :sentLocalModifiedAt`: a clinical edit made after
     *  the record was sent but before this ack arrives changes `localModifiedAt`, so the guarded
     *  UPDATE matches zero rows and the row is left exactly as the edit left it (still `PENDING`
     *  if it already was, so the newer content is picked up on the next drain) instead of being
     *  stamped `SYNCED` for content the server never saw. */
    suspend fun applyAck(result: SyncResultDto, sentLocalModifiedAt: Instant)
    fun observeFailedCount(): Flow<Int>
}
