package com.example.samdapp.presentation.home

import com.example.samdapp.R
import com.example.samdapp.domain.sync.DrainFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Memo section 7, caption copy: each drain failure has its own words on Home and in the Sync now
 *  snackbar, the local-network kind included. These pin [copyRef], the one function the screen's
 *  `text(resources)` resolves, so there is no second mapping to drift from. */
class SyncCaptionCopyTest {

    private fun caption(f: DrainFailure) = (SyncCaption.CouldNotSend(f, waiting = 2).copyRef() as CopyRef.Str)
    private fun snackbar(f: DrainFailure) = (SyncNowMessage.Failed(f).copyRef() as CopyRef.Str)

    @Test
    fun `every drain failure has its own caption and its own snackbar`() {
        assertEquals(DrainFailure.entries.size, DrainFailure.entries.map { caption(it).res }.distinct().size)
        assertEquals(DrainFailure.entries.size, DrainFailure.entries.map { snackbar(it).res }.distinct().size)
    }

    @Test
    fun `the local network kind names the setting, not the server`() {
        assertEquals(R.string.sync_caption_could_not_send_local_network, caption(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED).res)
        assertEquals(R.string.sync_now_failed_local_network, snackbar(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED).res)
    }

    @Test
    fun `each drain failure caption is its own string with its own arguments`() {
        val expected = mapOf(
            DrainFailure.NO_CONNECTION to CopyRef.Str(R.string.sync_caption_could_not_send_no_connection, listOf(2)),
            DrainFailure.SIGN_IN to CopyRef.Str(R.string.sync_caption_could_not_send_sign_in, listOf(2)),
            DrainFailure.SERVER_REFUSED to CopyRef.Str(R.string.sync_caption_could_not_send_server_refused, listOf(2)),
            DrainFailure.LOCAL_STORE to CopyRef.Str(R.string.sync_caption_could_not_send_local_store, listOf(2)),
            // No count: the records are this phone's, held back on purpose.
            DrainFailure.OWNER_MISMATCH to CopyRef.Str(R.string.sync_caption_owner_mismatch),
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED to CopyRef.Str(R.string.sync_caption_could_not_send_local_network, listOf(2)),
        )
        assertEquals("a new DrainFailure needs a row here", DrainFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, ref) -> assertEquals("$failure", ref, caption(failure)) }
    }

    @Test
    fun `each drain failure snackbar is its own string`() {
        val expected = mapOf(
            DrainFailure.NO_CONNECTION to R.string.sync_now_failed_no_connection,
            DrainFailure.SIGN_IN to R.string.sync_now_failed_sign_in,
            DrainFailure.SERVER_REFUSED to R.string.sync_now_failed_server_refused,
            DrainFailure.LOCAL_STORE to R.string.sync_now_failed_local_store,
            DrainFailure.OWNER_MISMATCH to R.string.sync_caption_owner_mismatch,
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED to R.string.sync_now_failed_local_network,
        )
        assertEquals("a new DrainFailure needs a row here", DrainFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, res) -> assertEquals("$failure", CopyRef.Str(res), snackbar(failure)) }
    }

    @Test
    fun `every other caption state maps to its own resource and arguments`() {
        assertEquals(CopyRef.Str(R.string.sync_caption_sending), SyncCaption.Sending.copyRef())
        assertEquals(
            CopyRef.Str(R.string.sync_caption_offline_records_and_doctor_cases, listOf(3, 2)),
            SyncCaption.OfflineWaiting(records = 3, doctorCases = 2).copyRef(),
        )
        assertEquals(CopyRef.Plural(R.plurals.sync_caption_offline_records, 3), SyncCaption.OfflineWaiting(3, 0).copyRef())
        assertEquals(CopyRef.Plural(R.plurals.sync_caption_offline_doctor_cases, 2), SyncCaption.OfflineWaiting(0, 2).copyRef())
        assertEquals(CopyRef.Str(R.string.sync_caption_offline_activity_log), SyncCaption.OfflineActivityLog.copyRef())
        assertEquals(CopyRef.Str(R.string.sync_caption_offline_nothing_else), SyncCaption.OfflineNothingElse.copyRef())
        assertEquals(CopyRef.Str(R.string.sync_caption_offline_all_sent), SyncCaption.OfflineAllSent.copyRef())
        assertEquals(
            CopyRef.Str(R.string.sync_caption_waiting_records_and_doctor_cases, listOf(3, 2)),
            SyncCaption.Waiting(records = 3, doctorCases = 2).copyRef(),
        )
        assertEquals(CopyRef.Plural(R.plurals.sync_caption_waiting_records, 3), SyncCaption.Waiting(3, 0).copyRef())
        assertEquals(CopyRef.Plural(R.plurals.sync_caption_doctor_cases, 4), SyncCaption.DoctorCases(4).copyRef())
        assertEquals(CopyRef.Str(R.string.sync_caption_activity_log), SyncCaption.ActivityLogWaiting.copyRef())
        assertEquals(CopyRef.Plural(R.plurals.sync_caption_needs_review, 5), SyncCaption.NeedsReview(5).copyRef())
        assertEquals(
            CopyRef.Str(
                R.string.sync_caption_needs_review_with_held,
                listOf(
                    CopyRef.Plural(R.plurals.sync_caption_needs_review_count, 5),
                    CopyRef.Plural(R.plurals.sync_caption_held_count, 6),
                ),
            ),
            SyncCaption.NeedsReviewWithHeld(review = 5, held = 6).copyRef(),
        )
        assertEquals(CopyRef.Str(R.string.sync_caption_up_to_date), SyncCaption.UpToDate.copyRef())
    }

    @Test
    fun `every other Sync now state maps to its own resource`() {
        assertEquals(CopyRef.Str(R.string.sync_now_offline), SyncNowMessage.Offline.copyRef())
        assertEquals(CopyRef.Str(R.string.sync_now_still_trying), SyncNowMessage.StillTrying.copyRef())
        assertEquals(CopyRef.Plural(R.plurals.sync_now_sent_needs_review, 2), SyncNowMessage.SentNeedsReview(2).copyRef())
        assertEquals(CopyRef.Plural(R.plurals.sync_now_sent_retry_later, 3), SyncNowMessage.SentRetryLater(3).copyRef())
        assertEquals(CopyRef.Str(R.string.sync_now_all_sent), SyncNowMessage.AllSent.copyRef())
    }
}
