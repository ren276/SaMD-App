package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeNetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** REQ-SYN-01: sync starts un-synced; a sync round stamps lastSyncedAt and clears pending. Also
 *  covers the offline-first fix: syncing must refuse to run offline, and only queued cases move. */
class MockSyncStatusTest {

    @Test
    fun `initial state is not synced`() = runTest {
        val state = MockSyncStatus(FakeCaseRecordRepository(), ConnectivityController(FakeNetworkMonitor())).state.first()
        assertNull(state.lastSyncedAt)
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
    }

    @Test
    fun `syncNow stamps lastSyncedAt and settles not syncing`() = runTest {
        val sync = MockSyncStatus(FakeCaseRecordRepository(), ConnectivityController(FakeNetworkMonitor()))

        val result = sync.syncNow()

        assertEquals(Result.success(Unit), result)
        val state = sync.state.first()
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
        assert(state.lastSyncedAt != null) { "lastSyncedAt should be set after sync" }
    }

    @Test
    fun `syncNow refuses to run while offline`() = runTest {
        val sync = MockSyncStatus(FakeCaseRecordRepository(), ConnectivityController(FakeNetworkMonitor(initial = false)))

        val result = sync.syncNow()

        assertTrue(result.isFailure)
        assertNull(sync.state.first().lastSyncedAt)
    }

    @Test
    fun `syncNow sends every queued case and clears pendingCount`() = runTest {
        val queued = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.PENDING_SYNC,
            assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val repository = FakeCaseRecordRepository(initial = listOf(queued))
        val sync = MockSyncStatus(repository, ConnectivityController(FakeNetworkMonitor()))

        assertEquals(1, sync.state.first().pendingCount)
        sync.syncNow()

        assertEquals(0, sync.state.first().pendingCount)
        assertEquals(CaseStatus.SENT_TO_DOCTOR, repository.records["case-1"]?.status)
    }

    // Real time, not runTest's virtual scheduler — the auto-sync watcher runs on its own
    // Dispatchers.Default scope, independent of whichever screen/ViewModel is on screen.
    @Test
    fun `auto-syncs queued cases the moment connectivity comes back online`() = runBlocking {
        val queued = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.PENDING_SYNC,
            assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val repository = FakeCaseRecordRepository(initial = listOf(queued))
        val networkMonitor = FakeNetworkMonitor(initial = false)
        MockSyncStatus(repository, ConnectivityController(networkMonitor))

        // Give the auto-sync watcher's background collector a moment to attach and observe the
        // initial offline snapshot before flipping — otherwise this transition could race it.
        delay(300)
        networkMonitor.setAvailable(true)

        withTimeout(5_000) {
            while (repository.records["case-1"]?.status != CaseStatus.SENT_TO_DOCTOR) delay(50)
        }
        assertEquals(CaseStatus.SENT_TO_DOCTOR, repository.records["case-1"]?.status)
    }

    @Test
    fun `does not auto-sync just from starting up already online`() = runBlocking {
        val queued = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.PENDING_SYNC,
            assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val repository = FakeCaseRecordRepository(initial = listOf(queued))
        MockSyncStatus(repository, ConnectivityController(FakeNetworkMonitor(initial = true)))

        delay(500)

        assertEquals(CaseStatus.PENDING_SYNC, repository.records["case-1"]?.status)
    }
}
