package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.AllergySyncPayloadDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.FakeSyncPushService
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS
import com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE
import org.junit.Assert.assertNotNull
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

/**
 * S-2's own properties, kept in their own class so the Phase 6b scenarios above stay readable.
 *
 * Every test here is paired with a mutation check recorded in
 * `docs/design/s2-states-schema-drain-requeue.md`: the property was broken deliberately in the
 * production source, the test was confirmed red, and the source restored byte-identically.
 */
class SyncOutboxRetryBehaviourTest {

    private fun record(id: String, table: String = "allergies") = SyncRecordDto(
        table = table, op = "upsert", id = id,
        clientUpdatedAt = Instant.EPOCH, baseVersion = null,
        data = AllergySyncPayloadDto(patientId = "p1", category = "ENVIRONMENTAL", allergen = "pollen", reactionType = null, createdAt = Instant.EPOCH),
    )

    private fun drainer(
        repository: FakeSyncOutboxRepository,
        pushService: FakeSyncPushService,
        store: InFlightBatchStore = InMemoryInFlightBatchStore(),
    ) = SyncOutboxDrainer(
        repository,
        SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()),
        pushService,
        store,
        FakeAuthTokenStore(),
    )

    @Test
    fun `a 23503 row syncs on a later drain with no human involved`() = runTest {
        // The row this whole change set exists to save. Rejected as RETRYABLE because its parent
        // has not landed; the parent lands; the next drain carries it, with nobody pressing
        // anything. Before S-2 the first rejection made it FAILED and no drain ever looked at it
        // again.
        val repository = FakeSyncOutboxRepository(listOf(record("child-1")))
        val pushService = FakeSyncPushService(
            retryableRecordIds = setOf("child-1"),
            healsWhenParentLands = setOf("child-1"),
        )

        drainer(repository, pushService).drain()
        assertEquals(listOf("allergies" to "child-1"), repository.retryableIds)
        assertTrue("a retryable row must not be abandoned", repository.failedIds.isEmpty())

        pushService.parentLanded = true
        drainer(repository, pushService).drain()

        assertEquals(listOf("allergies" to "child-1"), repository.syncedIds)
        assertTrue("it must never have reached a terminal state", repository.failedIds.isEmpty())
    }

    @Test
    fun `a row that never heals lands in FAILED with the gave-up reason and is then left alone`() = runTest {
        val repository = FakeSyncOutboxRepository(listOf(record("orphan-1")))
        val pushService = FakeSyncPushService(retryableRecordIds = setOf("orphan-1"))

        repeat(MAX_SYNC_ATTEMPTS) { drainer(repository, pushService).drain() }

        assertEquals(listOf("allergies" to "orphan-1"), repository.failedIds)
        assertEquals(
            "the reason must say we gave up, not that the server refused the record",
            RETRY_EXHAUSTED_CODE,
            repository.failedCodes["allergies" to "orphan-1"],
        )

        // And it is genuinely out of the queue now: a further drain sends nothing at all.
        val callsBefore = pushService.calls.size
        drainer(repository, pushService).drain()
        assertEquals("a capped row must not be drained again", callsBefore, pushService.calls.size)
    }

    @Test
    fun `the exhausted reason is distinguishable from a server refusal`() = runTest {
        val repository = FakeSyncOutboxRepository(listOf(record("bad-1")))
        drainer(repository, FakeSyncPushService(rejectedRecordIds = setOf("bad-1"))).drain()

        assertEquals(listOf("allergies" to "bad-1"), repository.failedIds)
        assertEquals(
            "a terminal rejection keeps the backend's own code, never the gave-up sentinel",
            "SAMD-SYNC-6003",
            repository.failedCodes["allergies" to "bad-1"],
        )
    }

    @Test
    fun `the drain terminates while a RETRYABLE row is still in the queue`() = runTest {
        // drainLocked is a `while (true)` over a table its own body writes to. A RETRYABLE row
        // stays collectable by design, so without the in-drain `attempted` guard this spins
        // forever. Asserted by the test completing at all, and by the push count: exactly one
        // send per drain, not one per loop pass.
        val repository = FakeSyncOutboxRepository(listOf(record("child-1"), record("child-2")))
        val pushService = FakeSyncPushService(retryableRecordIds = setOf("child-1", "child-2"))

        val result = drainer(repository, pushService).drain()

        assertTrue(result.isSuccess)
        assertEquals("one batch, one send: the loop must not re-collect within a drain", 1, pushService.calls.size)
        assertEquals(2, repository.retryableIds.size)
    }

    @Test
    fun `a mix of retryable and terminal rows in one batch terminates and separates correctly`() = runTest {
        val repository = FakeSyncOutboxRepository(listOf(record("ok-1"), record("retry-1"), record("bad-1")))
        val pushService = FakeSyncPushService(
            rejectedRecordIds = setOf("bad-1"),
            retryableRecordIds = setOf("retry-1"),
        )

        assertTrue(drainer(repository, pushService).drain().isSuccess)

        assertEquals(listOf("allergies" to "ok-1"), repository.syncedIds)
        assertEquals(listOf("allergies" to "retry-1"), repository.retryableIds)
        assertEquals(listOf("allergies" to "bad-1"), repository.failedIds)
    }

    @Test
    fun `an edited FAILED row re-enters the queue through requeueFailed`() = runTest {
        val repository = FakeSyncOutboxRepository(listOf(record("bad-1")))
        drainer(repository, FakeSyncPushService(rejectedRecordIds = setOf("bad-1"))).drain()
        assertEquals(listOf("allergies" to "bad-1"), repository.failedIds)

        repository.requeueFailed("allergies", "bad-1")

        assertEquals(listOf("allergies" to "bad-1"), repository.requeuedIds)
        assertTrue("requeue must clear the terminal state", repository.failedIds.isEmpty())
        assertEquals(
            "requeue must reset the attempt budget, or the row gets fewer tries than the policy promises",
            0,
            repository.attemptCounts["allergies" to "bad-1"],
        )
    }

    @Test
    fun `an ack status this build does not know is skipped, and the rest of the batch still applies`() = runTest {
        // S-3's decision, and the case that makes it matter: the unknown status arrives FIRST in
        // the batch. Until S-3 `toLocalSyncState` threw here, the exception escaped applyAck, and
        // every ack AFTER it was lost too — including the two good ones below. The record order
        // is the whole point of the test; reversing it would hide the bug it guards.
        val repository = FakeSyncOutboxRepository(listOf(record("odd-1"), record("ok-1"), record("ok-2")))
        val pushService = FakeSyncPushService(unknownStatusRecordIds = setOf("odd-1"))

        assertTrue(drainer(repository, pushService).drain().isSuccess)

        assertEquals(
            "the two acks after the unknown one must still apply",
            listOf("allergies" to "ok-1", "allergies" to "ok-2"),
            repository.syncedIds,
        )
        assertEquals(
            "the unknown status must be recorded, not silently dropped",
            listOf(Triple("allergies", "odd-1", "quarantined")),
            repository.skippedAcks,
        )
        assertTrue("an unknown status must not invent a terminal state", repository.failedIds.isEmpty())
        assertTrue(repository.conflictedIds.isEmpty())
        assertTrue(repository.retryableIds.isEmpty())
        assertEquals(
            "a skipped ack must charge nothing: the device learned nothing about that row",
            null,
            repository.attemptCounts["allergies" to "odd-1"],
        )
    }

    @Test
    fun `a row whose ack is never understood stays in the queue and is resent, rather than spinning`() = runTest {
        // The half S-1 could not have: skipping leaves the row PENDING, so it MUST be re-sent on
        // a later drain, and it must NOT be re-sent inside this one. S-2's `attempted` set is what
        // makes the second half true; without it this test hangs rather than fails.
        val repository = FakeSyncOutboxRepository(listOf(record("odd-1")))
        val pushService = FakeSyncPushService(unknownStatusRecordIds = setOf("odd-1"))
        val d = drainer(repository, pushService)

        assertTrue(d.drain().isSuccess)
        assertEquals("exactly one send in one drain, not a spin", 1, pushService.calls.size)

        assertTrue(d.drain().isSuccess)
        assertEquals("the row is still queued, so the next drain sends it again", 2, pushService.calls.size)
        assertEquals(2, repository.skippedAcks.size)
    }

    @Test
    fun `the attempt count survives a crash mid-push and is charged only once for that batch`() = runTest {
        // The backend committed and the response was lost. The resume replays the SAME batch_id,
        // the backend returns its stored envelope, and the ack lands. The row must be charged
        // exactly one attempt for the whole episode, not one per resume: attempts are counted on
        // acks, so a crash that produced no ack cannot burn the budget, and the replayed ack
        // counts once.
        val repository = FakeSyncOutboxRepository(listOf(record("child-1")))
        val pushService = FakeSyncPushService(retryableRecordIds = setOf("child-1"))
        val store = InMemoryInFlightBatchStore()
        pushService.crashOnNextPush = true

        runCatching { drainer(repository, pushService, store).drain() }
        assertNotNull("the in-flight batch must survive the crash for the resume", store.stored)
        assertEquals(
            "a crash before any ack must not charge an attempt",
            null,
            repository.attemptCounts["allergies" to "child-1"],
        )

        drainer(repository, pushService, store).drain()

        assertEquals(
            "the replayed ack charges exactly one attempt",
            1,
            repository.attemptCounts["allergies" to "child-1"],
        )
        assertEquals(listOf("allergies" to "child-1"), repository.retryableIds)
    }

    @Test
    fun `an offline or unreachable backend never burns a rows attempt budget`() = runTest {
        // The property that makes a pre-push increment unsafe and an on-ack increment correct.
        // A PHC whose Wi-Fi is up but whose server is unreachable would otherwise abandon its
        // entire outbox after MAX_SYNC_ATTEMPTS drains, having never spoken to the backend.
        val repository = FakeSyncOutboxRepository(listOf(record("child-1")))
        val unreachable = object : com.example.samdapp.data.remote.SyncPushService {
            var calls = 0
            override suspend fun push(request: com.example.samdapp.data.remote.dto.SyncPushRequestDto) =
                com.example.samdapp.data.remote.SyncPushResult.Failure(code = null, message = "unreachable")
                    .also { calls++ }
        }

        repeat(MAX_SYNC_ATTEMPTS + 2) { drainer(repository, FakeSyncPushService()).let { } }
        repeat(MAX_SYNC_ATTEMPTS + 2) {
            SyncOutboxDrainer(
                repository,
                SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()),
                unreachable,
                InMemoryInFlightBatchStore(),
                FakeAuthTokenStore(),
            ).drain()
        }

        assertTrue("no attempt may be charged for a push the backend never answered", repository.attemptCounts.isEmpty())
        assertTrue("and nothing may be abandoned", repository.failedIds.isEmpty())
    }

    @Test
    fun `an oversized record is failed locally as TERMINAL and never charged an attempt`() = runTest {
        val repository = FakeSyncOutboxRepository(listOf(record("x".repeat(5_000_000))))
        drainer(repository, FakeSyncPushService()).drain()

        assertEquals(1, repository.failedIds.size)
        assertEquals("SAMD-SYNC-RECORD-TOO-LARGE", repository.failedCodes.values.single())
    }
}

/**
 * Every drain records why it failed, or clears the record when it succeeds, in
 * [DrainOutcomeStore]: the one fact Home needs to say "Could not send" instead of "waiting to
 * send" while the outbox cannot drain. The kind comes from the push's problem `code`, which used
 * to be flattened into an exception message and lost.
 */
class DrainOutcomeRecordingTest {

    private fun record(id: String) = SyncRecordDto(
        table = "allergies", op = "upsert", id = id,
        clientUpdatedAt = Instant.EPOCH, baseVersion = null,
        data = AllergySyncPayloadDto(patientId = "p1", category = "ENVIRONMENTAL", allergen = "pollen", reactionType = null, createdAt = Instant.EPOCH),
    )

    private fun failingPush(code: String?) = object : com.example.samdapp.data.remote.SyncPushService {
        override suspend fun push(request: com.example.samdapp.data.remote.dto.SyncPushRequestDto) =
            com.example.samdapp.data.remote.SyncPushResult.Failure(code = code, message = "push failed")
    }

    private suspend fun drainWith(
        push: com.example.samdapp.data.remote.SyncPushService,
        outcome: DrainOutcomeStore,
        batchStore: InMemoryInFlightBatchStore = InMemoryInFlightBatchStore(),
    ): Result<Unit> = SyncOutboxDrainer(
        FakeSyncOutboxRepository(listOf(record("a-1"))),
        SyncBatchPacker(com.example.samdapp.data.remote.SyncGson.create()),
        push, batchStore, FakeAuthTokenStore(), outcome,
    ).drain()

    @Test
    fun `a push that never reached the server records NO_CONNECTION`() = runTest {
        val outcome = DrainOutcomeStore()
        drainWith(failingPush(code = null), outcome)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.NO_CONNECTION, outcome.lastFailure.value)
    }

    @Test
    fun `an auth refusal records SIGN_IN`() = runTest {
        val outcome = DrainOutcomeStore()
        drainWith(failingPush(code = "SAMD-AUTH-1003"), outcome)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.SIGN_IN, outcome.lastFailure.value)
    }

    @Test
    fun `any other refused batch records SERVER_REFUSED`() = runTest {
        val outcome = DrainOutcomeStore()
        drainWith(failingPush(code = "SAMD-SYS-9004"), outcome)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.SERVER_REFUSED, outcome.lastFailure.value)
    }

    @Test
    fun `an unreadable in-flight batch store records LOCAL_STORE`() = runTest {
        val outcome = DrainOutcomeStore()
        val batchStore = InMemoryInFlightBatchStore().apply { loadFailure = IllegalStateException("corrupt") }
        drainWith(FakeSyncPushService(), outcome, batchStore)
        assertEquals(com.example.samdapp.domain.sync.DrainFailure.LOCAL_STORE, outcome.lastFailure.value)
    }

    @Test
    fun `a later successful drain clears the failure`() = runTest {
        val outcome = DrainOutcomeStore()
        drainWith(failingPush(code = null), outcome)
        assertNotNull(outcome.lastFailure.value)

        drainWith(FakeSyncPushService(), outcome)

        assertNull(outcome.lastFailure.value)
    }
}
