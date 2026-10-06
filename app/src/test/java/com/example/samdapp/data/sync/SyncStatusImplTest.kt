package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.sync.SyncOfflineException
import com.example.samdapp.data.remote.dto.AllergySyncPayloadDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeNetworkMonitor
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.FakeSyncOutboxScheduler
import com.example.samdapp.testutil.FakeSyncPushService
import kotlinx.coroutines.async
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
        drainOutcome: DrainOutcomeStore = DrainOutcomeStore(),
        drainer: SyncOutboxDrainer = drainerOver(outboxRepository, FakeSyncPushService(), drainOutcome),
    ) = SyncStatusImpl(caseRecordRepository, ConnectivityController(networkMonitor), outboxScheduler, outboxRepository, drainOutcome, drainer)

    private class InMemoryStore : InFlightBatchStore {
        var stored: InFlightBatch? = null
        override suspend fun load(): Result<InFlightBatch?> = Result.success(stored)
        override suspend fun save(batch: InFlightBatch) { stored = batch }
        override suspend fun clear() { stored = null }
    }

    private fun drainerOver(
        outboxRepository: FakeSyncOutboxRepository,
        pushService: FakeSyncPushService,
        drainOutcome: DrainOutcomeStore = DrainOutcomeStore(),
    ) = SyncOutboxDrainer(
        outboxRepository,
        SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()),
        pushService,
        InMemoryStore(),
        FakeAuthTokenStore(),
        drainOutcome,
    )

    private fun record(id: String) = SyncRecordDto(
        table = "allergies", op = "upsert", id = id,
        clientUpdatedAt = Instant.EPOCH, baseVersion = null,
        data = AllergySyncPayloadDto(patientId = "p1", category = "ENVIRONMENTAL", allergen = "pollen", reactionType = null, createdAt = Instant.EPOCH),
    )

    private fun queuedCase() = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.PENDING_SYNC,
        assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun `syncNowInProcess sends the queued cases and drains in process, never through the scheduler`() = runTest {
        val cases = FakeCaseRecordRepository(initial = listOf(queuedCase()))
        val scheduler = FakeSyncOutboxScheduler()
        val outbox = FakeSyncOutboxRepository(listOf(record("r1")))
        val push = FakeSyncPushService()

        val result = sync(
            caseRecordRepository = cases, outboxScheduler = scheduler, outboxRepository = outbox,
            drainer = drainerOver(outbox, push),
        ).syncNowInProcess()

        assertTrue(result.isSuccess)
        assertEquals(
            "operator ruling H3: the queued PENDING_SYNC case still goes to the doctor queue",
            CaseStatus.SENT_TO_DOCTOR, cases.observeCaseRecord("case-1").first()?.status,
        )
        assertEquals(0, scheduler.runNowAndAwaitCallCount)
        assertEquals(listOf("allergies" to "r1"), outbox.syncedIds)
    }

    @Test
    fun `syncNowInProcess is refused offline and sends nothing`() = runTest {
        val cases = FakeCaseRecordRepository(initial = listOf(queuedCase()))
        val outbox = FakeSyncOutboxRepository(listOf(record("r1")))

        val result = sync(
            caseRecordRepository = cases, networkMonitor = FakeNetworkMonitor(initial = false),
            outboxRepository = outbox,
        ).syncNowInProcess()

        assertTrue(result.exceptionOrNull() is SyncOfflineException)
        assertEquals(CaseStatus.PENDING_SYNC, cases.observeCaseRecord("case-1").first()?.status)
        assertTrue(outbox.syncedIds.isEmpty())
    }

    @Test
    fun `a failed in-process drain fails the result, records why, and leaves isSyncing false`() = runTest {
        val outbox = FakeSyncOutboxRepository(listOf(record("r1")))
        val drainOutcome = DrainOutcomeStore()
        val syncStatus = sync(
            outboxRepository = outbox, drainOutcome = drainOutcome,
            drainer = drainerOver(outbox, FakeSyncPushService().apply { unreachable = true }, drainOutcome),
        )

        val result = syncStatus.syncNowInProcess()

        assertTrue(result.isFailure)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.NO_CONNECTION, drainOutcome.lastFailure.value)
        assertFalse(syncStatus.state.first().isSyncing)
        assertNull(syncStatus.state.first().lastSyncedAt)
    }

    @Test
    fun `an in-process drain and a worker drain at once send every row exactly once`() = runTest {
        val outbox = FakeSyncOutboxRepository((1..50).map { record("r$it") })
        val drainer = drainerOver(outbox, FakeSyncPushService())
        val syncStatus = sync(outboxRepository = outbox, drainer = drainer)

        val inProcess = async { syncStatus.syncNowInProcess() }
        val worker = async { drainer.drain() }
        assertTrue(inProcess.await().isSuccess)
        assertTrue(worker.await().isSuccess)

        assertEquals(50, outbox.syncedIds.size)
        assertEquals(50, outbox.syncedIds.toSet().size)
    }

    @Test
    fun `stateNow reads the outbox counts directly, not through the Flow`() = runTest {
        val outbox = FakeSyncOutboxRepository().apply {
            outboxCounts.value = OutboxCounts()
            directOutboxCounts = OutboxCounts(pendingClinical = 1, needsReview = 2)
        }
        val drainOutcome = DrainOutcomeStore().apply { record(com.example.samdapp.domain.sync.DrainFailure.SIGN_IN) }

        val state = sync(outboxRepository = outbox, drainOutcome = drainOutcome).stateNow()

        assertEquals(2, state.failedCount)
        assertEquals(1, state.outboxPending)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.SIGN_IN, state.lastDrainFailure)
    }

    @Test
    fun `outbox counts and the last drain failure reach Home's sync state`() = runTest {
        val outbox = FakeSyncOutboxRepository()
        val drainOutcome = DrainOutcomeStore()
        val syncStatus = sync(outboxRepository = outbox, drainOutcome = drainOutcome)

        outbox.outboxCounts.value = OutboxCounts(pendingClinical = 3, pendingAudit = 5, needsReview = 2)
        drainOutcome.record(com.example.samdapp.domain.sync.DrainFailure.SERVER_REFUSED)

        val state = syncStatus.state.first()
        assertEquals(3, state.outboxPending)
        assertEquals(5, state.auditPending)
        assertEquals(2, state.failedCount)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.SERVER_REFUSED, state.lastDrainFailure)
    }

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
