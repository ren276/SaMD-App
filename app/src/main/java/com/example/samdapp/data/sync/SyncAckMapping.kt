package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.SyncState

/** api-contract.md §6.1's Android handling rule, as one table-driven mapping — pulled out of
 *  [RoomSyncOutboxRepository.applyAck] so it is unit-testable without a Room database
 *  (SyncAckMappingTest). `applied`/`stale`/`duplicate` all mean "the server has this row, stop
 *  pushing it"; `conflict` means "surfaced for review, do not retry blindly"; `rejected` means
 *  "malformed, stop retrying forever" (the locked Phase 6b decision). */
fun SyncResultDto.toLocalSyncState(): SyncState = when (status) {
    "applied", "stale", "duplicate" -> SyncState.SYNCED
    "conflict" -> SyncState.CONFLICT
    "rejected" -> SyncState.FAILED
    else -> error("Unknown sync ack status \"$status\" for $table/$id")
}
