package com.example.samdapp.data.sync

import androidx.work.ListenableWorker
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.domain.sync.DrainFailure
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

    @Test
    fun `each drain failure kind has its WorkManager result (memo P2-R8)`() {
        val expected = listOf(
            DrainFailure.NO_CONNECTION to ListenableWorker.Result.retry(),
            DrainFailure.SIGN_IN to ListenableWorker.Result.retry(),
            DrainFailure.SERVER_REFUSED to ListenableWorker.Result.retry(),
            DrainFailure.LOCAL_STORE to ListenableWorker.Result.retry(),
            DrainFailure.OWNER_MISMATCH to ListenableWorker.Result.failure(),
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED to ListenableWorker.Result.failure(),
        )
        assertEquals("every kind is listed", DrainFailure.entries.toSet(), expected.map { it.first }.toSet())
        val errorFor = mapOf(
            DrainFailure.NO_CONNECTION to SyncPushFailedException(null, "offline"),
            DrainFailure.SIGN_IN to SyncPushFailedException("SAMD-AUTH-1003", "refused"),
            DrainFailure.SERVER_REFUSED to SyncPushFailedException("SAMD-SYS-9004", "down"),
            DrainFailure.LOCAL_STORE to NoDataOwnerException(),
            DrainFailure.OWNER_MISMATCH to OwnerMismatchHeldException(),
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED to SyncPushFailedException(null, "push failed", LocalNetworkNotAllowedException()),
        )
        expected.forEach { (kind, result) ->
            val error = errorFor.getValue(kind)
            assertEquals("$kind is what the error classifies as", kind, drainFailureFor(error))
            assertEquals("$kind", result, syncPushWorkResult(Result.failure(error)))
        }
    }
}
