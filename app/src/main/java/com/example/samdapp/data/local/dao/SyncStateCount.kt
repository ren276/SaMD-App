package com.example.samdapp.data.local.dao

import com.example.samdapp.domain.model.SyncState

/**
 * One row of a table's `observeSyncStateCounts()`: how many of its rows sit in [syncState].
 *
 * One grouped query per table replaces the old FAILED-only counter one for one, so Home learns
 * pending, failed and conflicted counts from the same twenty observers it already had (the launch
 * path stays at twenty, perf audit F2A-01). SYNCED is excluded in SQL.
 */
data class SyncStateCount(
    val syncState: SyncState,
    /** True for rows held by an ancestor the server will never accept (see `SyncSql.HELD_*`). Always
     *  false for the three root tables. Non-null on purpose: a projection alias mismatch on a
     *  non-null field fails the build, where a nullable one only warns. */
    val held: Boolean,
    val rowCount: Int,
)
