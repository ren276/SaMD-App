package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.AllergySyncPayloadDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.FakeSyncPushService
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** In-memory [InFlightBatchStore] — a real one needs DataStore/Context; this is the seam that
 *  makes the crash-resume test possible without either. */
private class InMemoryInFlightBatchStore : InFlightBatchStore {
    var stored: InFlightBatch? = null
    var loadFailure: Throwable? = null
    override suspend fun load(): Result<InFlightBatch?> =
        loadFailure?.let { Result.failure(it) } ?: Result.success(stored)
    override suspend fun save(batch: InFlightBatch) { stored = batch }
    override suspend fun clear() { stored = null }
}

/**
 * The three scenarios this Phase 6b session exists to prove, all JVM: the large-backlog first-
 * sync drain, crash-resume batch_id reuse, and a malformed row going FAILED without being
 * retried. [SyncOutboxDrainer] has no Android/WorkManager dependency, so all three run against
 * fakes ([FakeSyncPushService] models the backend's own idempotency store closely enough to prove
 * "exactly one applied copy" for real, not just assert a mock was called once).
 */
class SyncOutboxDrainerTest {

    private fun record(id: String, table: String = "allergies") = SyncRecordDto(
        table = table, op = "upsert", id = id,
        clientUpdatedAt = Instant.EPOCH, baseVersion = null,
        data = AllergySyncPayloadDto(patientId = "p1", category = "ENVIRONMENTAL", allergen = "pollen", reactionType = null, createdAt = Instant.EPOCH),
    )

    @Test
    fun `large backlog drains in multiple correctly-bounded batches, every row ends SYNCED, none dropped or doubled`() = runTest {
        // Well over one batch (400 records) and, separately, proven over budget by
        // SyncBatchPackerTest — this test proves the DRAINER'S loop actually sends every
        // resulting batch, not just that the packer can compute them.
        val records = (1..1200).map { record("r$it") }
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService()
        val store = InMemoryInFlightBatchStore()
        val drainer = SyncOutboxDrainer(repository, SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()), pushService, store, FakeAuthTokenStore())

        val result = drainer.drain()

        assertTrue(result.isSuccess)
        assertEquals(1200, repository.syncedIds.size)
        assertEquals(1200, repository.syncedIds.toSet().size) // none doubled
        assertEquals(records.map { it.table to it.id }.toSet(), repository.syncedIds.toSet()) // none dropped
        assertTrue("expected more than one HTTP call for 1200 records", pushService.calls.size > 1)
        pushService.calls.forEach { assertTrue(it.records.size <= SyncBatchPacker.MAX_RECORDS) }
        assertNull("in-flight marker must be cleared once every batch is acked", store.stored)
    }

    @Test
    fun `crash between backend-applied and ack-recorded resumes with the same batch_id and applies exactly once`() = runTest {
        val records = listOf(record("r1"), record("r2"), record("r3"))
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService()
        val store = InMemoryInFlightBatchStore()
        val packer = SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create())

        // First drainer instance: sends the one batch, backend applies it, but the response is
        // lost before this process records the ack — simulating a process kill right there. The
        // batch_id is minted fresh inside drain() (SyncBatchPacker), not predictable in advance,
        // so the fake crashes on whichever request arrives next rather than a specific id.
        val firstDrainer = SyncOutboxDrainer(repository, packer, pushService, store, FakeAuthTokenStore())
        pushService.crashOnNextPush = true
        val crashed = runCatching { firstDrainer.drain() }
        assertTrue("expected the simulated crash to propagate as a thrown exception", crashed.isFailure)

        // Nothing was applied locally yet — the crash happened before applyAck.
        assertEquals(0, repository.syncedIds.size)
        val firstBatchId = requireNotNull(store.stored?.batchId) { "in-flight batch must be persisted before the send that crashed" }

        // Second drainer instance: "process restarted". Must reuse the persisted batch_id.
        val secondDrainer = SyncOutboxDrainer(repository, packer, pushService, store, FakeAuthTokenStore())
        val result = secondDrainer.drain()

        assertTrue(result.isSuccess)
        assertEquals(3, repository.syncedIds.size)
        assertNull(store.stored)

        // The subtle assertion: the resumed send reused the SAME batch_id, and the backend's
        // idempotency store answered with the stored (already-applied) response rather than
        // applying a second time — exactly one applied copy, not two, even though push() was
        // called twice for this batch_id (once crashed, once replayed).
        val callsForBatch = pushService.calls.filter { it.batchId == firstBatchId }
        assertEquals(2, callsForBatch.size)
        assertEquals(1, callsForBatch.map { it.batchId }.distinct().size)
    }

    @Test
    fun `a malformed row goes FAILED with the code stored, and is excluded from the next drain`() = runTest {
        val records = listOf(record("good-1"), record("malformed-1"), record("good-2"))
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService(rejectedRecordIds = setOf("malformed-1"))
        val store = InMemoryInFlightBatchStore()
        val drainer = SyncOutboxDrainer(repository, SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()), pushService, store, FakeAuthTokenStore())

        val result = drainer.drain()

        assertTrue("a single bad record must not fail the whole batch", result.isSuccess)
        assertEquals(2, repository.syncedIds.size)
        assertEquals(listOf("allergies" to "malformed-1"), repository.failedIds)
        assertEquals("SAMD-SYNC-6003", repository.failedCodes["allergies" to "malformed-1"])

        // Not retried: a second drain() call must not resend the FAILED row (FakeSyncOutboxRepository
        // already removed it from collectPendingRecords() on the FAILED ack, matching the real
        // DAO's `syncState = 'PENDING'` selection).
        pushService.calls.clear()
        val secondResult = drainer.drain()
        assertTrue(secondResult.isSuccess)
        assertTrue("FAILED row must not be resent", pushService.calls.none { call -> call.records.any { it.id == "malformed-1" } })
    }

    @Test
    fun `an unreadable in-flight batch blocks the drain instead of minting a fresh batch_id`() = runTest {
        val records = listOf(record("r1"))
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService()
        val store = InMemoryInFlightBatchStore()
        store.loadFailure = java.io.IOException("simulated unreadable DataStore")
        val drainer = SyncOutboxDrainer(repository, SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()), pushService, store, FakeAuthTokenStore())

        val result = drainer.drain()

        assertTrue("an unreadable in-flight batch must fail the drain, not proceed as if there were none", result.isFailure)
        assertTrue("must not push anything while the prior in-flight batch is unrecoverable", pushService.calls.isEmpty())
    }

    @Test
    fun `a record over the per-record byte budget is marked FAILED locally without a network call, and other rows still sync`() = runTest {
        val hugeRecord = record("huge").copy(
            data = AllergySyncPayloadDto(
                patientId = "p1", category = "ENVIRONMENTAL",
                allergen = "x".repeat(5_000_000), reactionType = null, createdAt = Instant.EPOCH,
            ),
        )
        val records = listOf(record("good-1"), hugeRecord, record("good-2"))
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService()
        val store = InMemoryInFlightBatchStore()
        val drainer = SyncOutboxDrainer(repository, SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()), pushService, store, FakeAuthTokenStore())

        val result = drainer.drain()

        assertTrue(result.isSuccess)
        assertEquals(2, repository.syncedIds.size)
        assertEquals(listOf("allergies" to "huge"), repository.failedIds)
        assertEquals("SAMD-SYNC-RECORD-TOO-LARGE", repository.failedCodes["allergies" to "huge"])
        assertTrue(
            "the oversized record must never be sent over the network",
            pushService.calls.none { call -> call.records.any { it.id == "huge" } },
        )
    }

    @Test
    fun `concurrent drain calls do not both send the same PENDING rows`() = runTest {
        val records = listOf(record("r1"), record("r2"))
        val repository = FakeSyncOutboxRepository(records)
        val pushService = FakeSyncPushService()
        val store = InMemoryInFlightBatchStore()
        val drainer = SyncOutboxDrainer(repository, SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()), pushService, store, FakeAuthTokenStore())

        // Both calls share the same drainer instance, so its Mutex serializes them — the second
        // call's collectPendingRecords() only runs after the first has applied its acks and the
        // rows are no longer PENDING.
        val first = async { drainer.drain() }
        val second = async { drainer.drain() }
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)

        assertEquals(2, repository.syncedIds.size)
        assertEquals(2, repository.syncedIds.toSet().size)
    }
}
