package com.example.samdapp.presentation.home

import com.example.samdapp.domain.sync.DrainFailure
import com.example.samdapp.domain.sync.SyncOfflineException
import com.example.samdapp.domain.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home's sync caption truth table (memo section 4.2), one case per row, evaluated top to bottom.
 * "Up to date" is allowed in exactly one row: nothing pending, failed, conflicted or blocked.
 * On master the caption read only the doctor-assignment queue, so rows 7, 9 and 10 (records
 * waiting, audit log waiting, records needing review) all rendered "Up to date".
 */
class SyncCaptionTest {

    private val idle = SyncState()

    @Test fun `row 1 syncing wins over everything`() =
        assertEquals(SyncCaption.Sending, syncCaption(idle.copy(isSyncing = true, outboxPending = 3), isOnline = false))

    @Test fun `row 2 offline with records and doctor cases`() =
        assertEquals(SyncCaption.OfflineWaiting(records = 3, doctorCases = 2), syncCaption(idle.copy(outboxPending = 3, pendingCount = 2), isOnline = false))

    @Test fun `row 2 offline with doctor cases only`() =
        assertEquals(SyncCaption.OfflineWaiting(records = 0, doctorCases = 1), syncCaption(idle.copy(pendingCount = 1), isOnline = false))

    @Test fun `row 3 offline with only the activity log`() =
        assertEquals(SyncCaption.OfflineActivityLog, syncCaption(idle.copy(auditPending = 4, failedCount = 2), isOnline = false))

    @Test fun `row 4 offline with only records needing review`() =
        assertEquals(SyncCaption.OfflineNothingElse, syncCaption(idle.copy(failedCount = 2), isOnline = false))

    @Test fun `row 5 offline with nothing at all`() =
        assertEquals(SyncCaption.OfflineAllSent, syncCaption(idle, isOnline = false))

    @Test fun `row 6 online, last drain failed, records waiting`() =
        assertEquals(
            SyncCaption.CouldNotSend(DrainFailure.NO_CONNECTION, waiting = 5),
            syncCaption(idle.copy(outboxPending = 2, auditPending = 3, pendingCount = 1, lastDrainFailure = DrainFailure.NO_CONNECTION), isOnline = true),
        )

    @Test fun `row 6 does not fire on a stale failure with nothing queued`() =
        assertEquals(SyncCaption.UpToDate, syncCaption(idle.copy(lastDrainFailure = DrainFailure.SERVER_REFUSED), isOnline = true))

    @Test fun `row 7 online, records waiting, never Up to date`() =
        assertEquals(SyncCaption.Waiting(records = 3, doctorCases = 0), syncCaption(idle.copy(outboxPending = 3), isOnline = true))

    @Test fun `row 7 online, records and doctor cases waiting`() =
        assertEquals(SyncCaption.Waiting(records = 3, doctorCases = 2), syncCaption(idle.copy(outboxPending = 3, pendingCount = 2), isOnline = true))

    @Test fun `row 8 online, only doctor cases waiting`() =
        assertEquals(SyncCaption.DoctorCases(2), syncCaption(idle.copy(pendingCount = 2, auditPending = 1), isOnline = true))

    @Test fun `row 9 online, only the activity log waiting, never Up to date`() =
        assertEquals(SyncCaption.ActivityLogWaiting, syncCaption(idle.copy(auditPending = 4, failedCount = 1), isOnline = true))

    @Test fun `row 10 online, only records needing review, never Up to date`() =
        assertEquals(SyncCaption.NeedsReview(2), syncCaption(idle.copy(failedCount = 2), isOnline = true))

    @Test fun `row 10a online, nothing waiting, records need review and others are held behind them`() =
        assertEquals(
            SyncCaption.NeedsReviewWithHeld(review = 1, held = 17),
            syncCaption(idle.copy(failedCount = 1, heldCount = 17), isOnline = true),
        )

    @Test fun `row 10b keeps the plain wording when nothing is held`() =
        assertEquals(SyncCaption.NeedsReview(2), syncCaption(idle.copy(failedCount = 2), isOnline = true))

    @Test fun `held rows alone never read as waiting, and never as Up to date`() {
        val caption = syncCaption(idle.copy(failedCount = 1, heldCount = 3), isOnline = true)
        assertEquals(SyncCaption.NeedsReviewWithHeld(1, 3), caption)
    }

    @Test fun `a leftover drain failure with only held rows queued is ignored, as with any empty queue`() =
        assertEquals(
            SyncCaption.NeedsReviewWithHeld(1, 3),
            syncCaption(idle.copy(failedCount = 1, heldCount = 3, lastDrainFailure = DrainFailure.NO_CONNECTION), isOnline = true),
        )

    @Test fun `row 11 Up to date only when nothing is pending, failed, conflicted or blocked`() =
        assertEquals(SyncCaption.UpToDate, syncCaption(idle, isOnline = true))

    // Sync now result (memo section 4.3). A success Result never alone means "everything is sent".

    @Test fun `sync now refused offline`() =
        assertEquals(SyncNowMessage.Offline, syncNowMessage(Result.failure(SyncOfflineException()), idle))

    @Test fun `sync now failed with a recorded drain failure`() =
        assertEquals(
            SyncNowMessage.Failed(DrainFailure.SIGN_IN),
            syncNowMessage(Result.failure(IllegalStateException("x")), idle.copy(lastDrainFailure = DrainFailure.SIGN_IN)),
        )

    @Test fun `sync now failed with no recorded failure is still trying`() =
        assertEquals(SyncNowMessage.StillTrying, syncNowMessage(Result.failure(IllegalStateException("timed out")), idle))

    @Test fun `sync now succeeded but records need review`() =
        assertEquals(SyncNowMessage.SentNeedsReview(2), syncNowMessage(Result.success(Unit), idle.copy(failedCount = 2, outboxPending = 1)))

    @Test fun `sync now succeeded with records left to retry`() =
        assertEquals(SyncNowMessage.SentRetryLater(1), syncNowMessage(Result.success(Unit), idle.copy(outboxPending = 1)))

    @Test fun `sync now succeeded with nothing left`() =
        assertEquals(SyncNowMessage.AllSent, syncNowMessage(Result.success(Unit), idle))
}
