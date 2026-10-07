package com.example.samdapp.data.sync

import com.example.samdapp.data.local.dao.SyncStateCount
import com.example.samdapp.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fold from per-table grouped state counts to the three numbers Home reads. Pending means
 * the device still owes the server the row (PENDING and RETRYABLE); needs review means a person
 * has to act (FAILED and CONFLICT); SYNCED is excluded in SQL and must never be counted here.
 * Audit rows are counted apart from clinical ones, because "the activity log is waiting to send"
 * and "3 records waiting to send" are different sentences (operator ruling H4).
 */
class OutboxCountsTest {

    @Test
    fun `pending is PENDING plus RETRYABLE, review is FAILED plus CONFLICT, audit kept apart`() {
        val counts = outboxCountsOf(
            clinical = listOf(
                SyncStateCount(SyncState.PENDING, held = false, rowCount = 2), SyncStateCount(SyncState.RETRYABLE, held = false, rowCount = 1),
                SyncStateCount(SyncState.FAILED, held = false, rowCount = 4), SyncStateCount(SyncState.CONFLICT, held = false, rowCount = 3),
                SyncStateCount(SyncState.PENDING, held = false, rowCount = 5),
            ),
            audit = listOf(SyncStateCount(SyncState.PENDING, held = false, rowCount = 7), SyncStateCount(SyncState.FAILED, held = false, rowCount = 1)),
        )

        assertEquals(OutboxCounts(pendingClinical = 8, pendingAudit = 7, needsReview = 8), counts)
    }

    @Test
    fun `nothing unsynced is all zero`() {
        assertEquals(OutboxCounts(), outboxCountsOf(clinical = emptyList(), audit = emptyList()))
    }

    @Test
    fun `a SYNCED group, if one ever arrived, counts as nothing`() {
        assertEquals(
            OutboxCounts(),
            outboxCountsOf(clinical = listOf(SyncStateCount(SyncState.SYNCED, held = false, rowCount = 9)), audit = emptyList()),
        )
    }

    @Test
    fun `held rows are counted apart and never as pending`() {
        val counts = outboxCountsOf(
            clinical = listOf(
                SyncStateCount(SyncState.PENDING, held = false, rowCount = 2),
                SyncStateCount(SyncState.PENDING, held = true, rowCount = 14),
                SyncStateCount(SyncState.RETRYABLE, held = true, rowCount = 3),
                SyncStateCount(SyncState.FAILED, held = false, rowCount = 1),
            ),
            audit = listOf(SyncStateCount(SyncState.PENDING, held = false, rowCount = 4)),
        )

        assertEquals(OutboxCounts(pendingClinical = 2, heldClinical = 17, pendingAudit = 4, needsReview = 1), counts)
    }
}
