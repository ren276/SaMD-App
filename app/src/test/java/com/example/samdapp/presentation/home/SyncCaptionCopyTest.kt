package com.example.samdapp.presentation.home

import com.example.samdapp.R
import com.example.samdapp.domain.sync.DrainFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Memo section 7, caption copy: each drain failure has its own words on Home and in the Sync now
 *  snackbar, the local-network kind included. */
class SyncCaptionCopyTest {

    @Test
    fun `every drain failure has its own caption and its own snackbar`() {
        assertEquals(DrainFailure.entries.size, DrainFailure.entries.map { it.captionRes }.distinct().size)
        assertEquals(DrainFailure.entries.size, DrainFailure.entries.map { it.syncNowFailedRes }.distinct().size)
    }

    @Test
    fun `the local network kind names the setting, not the server`() {
        assertEquals(R.string.sync_caption_could_not_send_local_network, DrainFailure.LOCAL_NETWORK_NOT_ALLOWED.captionRes)
        assertEquals(R.string.sync_now_failed_local_network, DrainFailure.LOCAL_NETWORK_NOT_ALLOWED.syncNowFailedRes)
    }
}
