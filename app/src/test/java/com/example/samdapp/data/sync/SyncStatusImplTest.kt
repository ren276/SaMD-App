package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeNetworkMonitor
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.FakeSyncOutboxScheduler
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

/**
 * Ports `MockSyncStatusTest`'s six behaviors to [SyncStatusImpl] unchanged in substance — same
 * assertions, same [FakeCaseRecordRepository]/[FakeNetworkMonitor], only a
 * [FakeSyncOutboxScheduler]/[FakeSyncOutboxRepository] added so the two new dependencies never
 * touch WorkManager/Android framework in these cases. Also covers the new [failedCount] surface.
 */
class SyncStatusImplTest {

    private fun sync(
        caseRecordRepository: FakeCaseRecordRepository = FakeCaseRecordRepository(),
        networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor(),
        outboxScheduler: FakeSyncOutboxScheduler = FakeSyncOutboxScheduler(),
        outboxRepository: FakeSyncOutboxRepository = FakeSyncOutboxRepository(),
    ) = SyncStatusImpl(caseRecordRepository, ConnectivityController(networkMonitor), outboxScheduler, outboxRepository)

    @Test
    fun `initial state is not synced`() = runTest {
        val state = sync().state.first()
        assertNull(state.lastSyncedAt)
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
        assertEquals(0, state.failedCount)
    }

    @Test
    fun `syncNow stamps lastSyncedAt and settles not syncing`() = runTest {
        val syncStatus = sync()

        val result = syncStatus.syncNow()

        assertEquals(Result.success(Unit), result)
        val state = syncStatus.state.first()
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
        assert(state.lastSyncedAt != null) { "lastSyncedAt should be set after sync" }
    }

    @Test
    fun `syncNow refuses to run while offline`() = runTest {
        val syncStatus = sync(networkMonitor = FakeNetworkMonitor(initial = false))

        val result = syncStatus.syncNow()

        assertTrue(result.isFailure)
        assertNull(syncStatus.state.first().lastSyncedAt)
    }

    @Test
    fun `syncNow sends every queued case and clears pendingCount`() = runTest {
        val queued = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.PENDING_SYNC,
            assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val repository = FakeCaseRecordRepository(initial = listOf(queued))
        val syncStatus = sync(caseRecordRepository = repository)

        assertEquals(1, syncStatus.state.first().pendingCount)
        syncStatus.syncNow()

        assertEquals(0, syncStatus.state.first().pendingCount)
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
        sync(caseRecordRepository = repository, networkMonitor = networkMonitor)

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
        sync(caseRecordRepository = repository, networkMonitor = FakeNetworkMonitor(initial = true))

        delay(500)

        assertEquals(CaseStatus.PENDING_SYNC, repository.records["case-1"]?.status)
    }

    @Test
    fun `ensures the periodic outbox worker is scheduled on construction`() = runTest {
        val scheduler = FakeSyncOutboxScheduler()
        sync(outboxScheduler = scheduler)

        assertEquals(1, scheduler.ensurePeriodicWorkCallCount)
    }

    @Test
    fun `syncNow also runs the generic outbox drain, not just the case-assignment queue`() = runTest {
        val scheduler = FakeSyncOutboxScheduler()
        val syncStatus = sync(outboxScheduler = scheduler)

        syncStatus.syncNow()

        assertEquals(1, scheduler.runNowAndAwaitCallCount)
    }

    @Test
    fun `FAILED outbox rows are surfaced through state failedCount`() = runTest {
        val outboxRepository = FakeSyncOutboxRepository()
        val syncStatus = sync(outboxRepository = outboxRepository)
        outboxRepository.applyAck(
            com.example.samdapp.data.remote.dto.SyncResultDto(
                table = "patients", id = "p1", status = "rejected", code = "SAMD-SYNC-6003",
            ),
            sentLocalModifiedAt = java.time.Instant.EPOCH,
        )

        assertEquals(1, syncStatus.state.first().failedCount)
    }
}
