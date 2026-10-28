package com.example.samdapp.data.sync

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a drain's outcome becomes a WorkManager result (facility-scope memo 3.5): an owner-facility
 *  refusal ends the run without a retry; every other failure is retried as before. */
class SyncPushWorkResultTest {

    @Test
    fun `an owner-facility refusal and a held drain end the run without a retry`() {
        listOf(
            SyncPushFailedException(SYNC_OWNER_FACILITY_MISMATCH_CODE, "refused"),
            OwnerMismatchHeldException(),
        ).forEach { error ->
            assertEquals("$error", ListenableWorker.Result.failure(), syncPushWorkResult(Result.failure(error)))
        }
    }

    @Test
    fun `every other failure is retried, and a success succeeds`() {
        listOf(
            SyncPushFailedException(null, "offline"),
            SyncPushFailedException("SAMD-SYS-9004", "database down"),
            SyncPushFailedException("SAMD-AUTH-1002", "expired"),
            NoDataOwnerException(),
        ).forEach { error ->
            assertEquals("$error", ListenableWorker.Result.retry(), syncPushWorkResult(Result.failure(error)))
        }
        assertEquals(ListenableWorker.Result.success(), syncPushWorkResult(Result.success(Unit)))
    }
}
