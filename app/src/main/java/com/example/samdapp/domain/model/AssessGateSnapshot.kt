package com.example.samdapp.domain.model

/** One row's outbox facts, as the assess gate needs them. */
data class SyncChainRow(
    val syncState: SyncState,
    val serverVersion: Int?,
    val syncErrorCode: String?,
    val syncErrorMessage: String?,
)

/**
 * A case's own sync chain: its case record, and the encounter and patient it hangs off (null when
 * that row is not on this phone). Read in one query, see `CaseRecordDao.getAssessGateRow`.
 */
data class AssessGateSnapshot(
    val caseRecord: SyncChainRow,
    val encounter: SyncChainRow?,
    val patient: SyncChainRow?,
)

/**
 * Whether the server holds this row: it has acked it with a version, or it is SYNCED.
 *
 * `serverVersion` is set only from an ack that carries one (applied or stale) and never by a
 * conflict, and a local edit resets the record state to PENDING but keeps the version. So a row
 * the server already holds still reads as present after a worker edits it, which `syncState ==
 * SYNCED` alone would get wrong. The single rule for "is this on the server"; the assess gate and
 * every "sent to the doctor" surface use it.
 */
fun isServerPresent(syncState: SyncState, serverVersion: Int?): Boolean =
    serverVersion != null || syncState == SyncState.SYNCED

fun SyncChainRow.isServerPresent(): Boolean = isServerPresent(syncState, serverVersion)
