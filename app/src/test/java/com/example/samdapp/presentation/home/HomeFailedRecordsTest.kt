package com.example.samdapp.presentation.home

import com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE
import com.example.samdapp.domain.model.SYNC_RECORD_INVALID_CODE
import com.example.samdapp.domain.model.SyncFailureAction
import com.example.samdapp.domain.model.SyncFailureReason
import com.example.samdapp.domain.model.syncFailureReasonFor
import com.example.samdapp.domain.sync.FailedSyncRecord
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeSyncStatus
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * S-3's visibility surface, at the ViewModel seam: the count that makes the card appear, the
 * list the card opens, and the one action a row can offer.
 *
 * The composables are not exercised here (no Robolectric in this module, and the Compose UI
 * tests need a device). What IS exercised is every decision the screen reads off the state: the
 * card's visibility condition, that the list is not fetched until it is opened, and that a
 * requeue takes the row out of the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeFailedRecordsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(sync: FakeSyncStatus) = HomeViewModel(
        GetTodaysPatientsUseCase(FakePatientRepository()),
        sync,
        FakeAuthSession(),
        FakeCaseRecordRepository(),
        FakePatientRepository(),
    )

    private fun failed(
        id: String,
        table: String = "patients",
        code: String? = RETRY_EXHAUSTED_CODE,
        message: String? = null,
        patientName: String? = "Asha Devi",
    ) = FailedSyncRecord(
        table = table,
        recordId = id,
        patientName = patientName,
        recordedAt = Instant.EPOCH,
        reason = syncFailureReasonFor(code, message),
    )

    @Test
    fun `the card is hidden at zero and its count is non-zero once a record has failed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sync = FakeSyncStatus()
            val viewModel = viewModel(sync)
            advanceUntilIdle()
            assertEquals("no card when nothing has failed", 0, viewModel.uiState.value.sync.failedCount)

            sync.setFailedRecords(listOf(failed("p1")))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.sync.failedCount)
        }

    @Test
    fun `the list is not fetched until a worker opens it`() = runTest(mainDispatcherRule.dispatcher) {
        // The whole cost argument for this PR (perf audit F2A-01): the count rides a flow that
        // was already collected, and the twenty queries behind the list never touch the launch
        // path. A collector added here would make this assertion fail.
        val sync = FakeSyncStatus()
        sync.setFailedRecords(listOf(failed("p1"), failed("p2")))
        val viewModel = viewModel(sync)
        advanceUntilIdle()

        assertEquals("the list must not be read at construction", 0, sync.failedRecordsCalls)
        assertTrue(viewModel.uiState.value.failedRecords.isEmpty())
        assertFalse(viewModel.uiState.value.isFailedRecordsOpen)

        viewModel.onOpenFailedRecords()
        advanceUntilIdle()

        assertEquals(1, sync.failedRecordsCalls)
        assertTrue(viewModel.uiState.value.isFailedRecordsOpen)
        assertFalse(viewModel.uiState.value.isLoadingFailedRecords)
        assertEquals(listOf("p1", "p2"), viewModel.uiState.value.failedRecords.map { it.recordId })
    }

    @Test
    fun `sending a record again takes it out of the list and out of the count`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sync = FakeSyncStatus()
            sync.setFailedRecords(listOf(failed("p1"), failed("p2")))
            val viewModel = viewModel(sync)
            viewModel.onOpenFailedRecords()
            advanceUntilIdle()

            val target = viewModel.uiState.value.failedRecords.single { it.recordId == "p1" }
            viewModel.onSendFailedRecordAgain(target)
            advanceUntilIdle()

            assertEquals(listOf(target), sync.sentAgain)
            assertEquals(
                "the requeued row must leave the list, or a worker presses it again on a row " +
                    "that is already back in the queue",
                listOf("p2"),
                viewModel.uiState.value.failedRecords.map { it.recordId },
            )
            assertEquals(1, viewModel.uiState.value.sync.failedCount)
        }

    @Test
    fun `closing the list drops it, so a stale snapshot is never shown on reopen`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sync = FakeSyncStatus()
            sync.setFailedRecords(listOf(failed("p1")))
            val viewModel = viewModel(sync)
            viewModel.onOpenFailedRecords()
            advanceUntilIdle()

            viewModel.onDismissFailedRecords()

            assertFalse(viewModel.uiState.value.isFailedRecordsOpen)
            assertTrue(viewModel.uiState.value.failedRecords.isEmpty())

            viewModel.onOpenFailedRecords()
            advanceUntilIdle()
            assertEquals("reopening must re-read, not replay", 2, sync.failedRecordsCalls)
        }

    @Test
    fun `an unknown code reaches the screen as the fallback cause, with a button, not as a blank`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sync = FakeSyncStatus()
            sync.setFailedRecords(listOf(failed("x1", code = "SAMD-SYNC-6099", message = "something new")))
            val viewModel = viewModel(sync)
            viewModel.onOpenFailedRecords()
            advanceUntilIdle()

            val row = viewModel.uiState.value.failedRecords.single()
            assertEquals(SyncFailureReason.UNRECOGNISED, row.reason)
            assertEquals(SyncFailureAction.SEND_AGAIN, row.reason.action)
        }

    @Test
    fun `a duplicate reaches the screen as its own cause and offers no button`() =
        runTest(mainDispatcherRule.dispatcher) {
            val sync = FakeSyncStatus()
            sync.setFailedRecords(
                listOf(
                    failed(
                        "p1",
                        code = SYNC_RECORD_INVALID_CODE,
                        message = com.example.samdapp.domain.model.BackendConstraintMessages.UNIQUE_VIOLATION,
                    ),
                ),
            )
            val viewModel = viewModel(sync)
            viewModel.onOpenFailedRecords()
            advanceUntilIdle()

            val row = viewModel.uiState.value.failedRecords.single()
            assertEquals(SyncFailureReason.DUPLICATE_RECORD, row.reason)
            assertEquals(
                "nothing on this phone can resolve a duplicate, so there must be no button",
                SyncFailureAction.TELL_SUPERVISOR,
                row.reason.action,
            )
        }
}
