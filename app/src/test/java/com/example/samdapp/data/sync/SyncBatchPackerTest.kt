package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.data.remote.dto.AllergySyncPayloadDto
import com.example.samdapp.data.remote.dto.SyncRecordDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Boundary cases for the byte/record budget (the locked Phase 6b decision: 400 records or
 *  4.5 MB, whichever comes first). Uses the real [SyncGson] instance, the same one the real
 *  Retrofit call serializes with — "measured against the actual JSON that will be sent, not an
 *  estimate" only holds if the test and the app share one Gson config. */
class SyncBatchPackerTest {

    private val gson = SyncGson.create()
    private val packer = SyncBatchPacker(gson)

    private fun record(id: String, allergen: String = "pollen") = SyncRecordDto(
        table = "allergies", op = "upsert", id = id,
        clientUpdatedAt = Instant.EPOCH, baseVersion = null,
        data = AllergySyncPayloadDto(patientId = "p1", category = "ENVIRONMENTAL", allergen = allergen, reactionType = null, createdAt = Instant.EPOCH),
    )

    @Test
    fun `empty input produces no batches`() {
        assertEquals(emptyList<SyncBatch>(), packer.pack(emptyList()).batches)
    }

    @Test
    fun `exactly at the record limit fits in one batch`() {
        val records = (1..SyncBatchPacker.MAX_RECORDS).map { record("r$it") }

        val batches = packer.pack(records).batches

        assertEquals(1, batches.size)
        assertEquals(SyncBatchPacker.MAX_RECORDS, batches[0].records.size)
    }

    @Test
    fun `one over the record limit splits into two batches`() {
        val records = (1..SyncBatchPacker.MAX_RECORDS + 1).map { record("r$it") }

        val batches = packer.pack(records).batches

        assertEquals(2, batches.size)
        assertEquals(SyncBatchPacker.MAX_RECORDS, batches[0].records.size)
        assertEquals(1, batches[1].records.size)
    }

    @Test
    fun `every batch gets its own unique batch_id`() {
        val records = (1..SyncBatchPacker.MAX_RECORDS + 1).map { record("r$it") }

        val batches = packer.pack(records).batches

        assertEquals(2, batches.map { it.batchId }.distinct().size)
    }

    @Test
    fun `combined size crossing the byte budget splits by bytes, not just count`() {
        // Each record padded to ~60 KB via a long free-text field; 100 of them (~6 MB) is well
        // under MAX_RECORDS (400) but over MAX_BYTES (4.5 MB) — this must split on bytes alone.
        val bigText = "x".repeat(60_000)
        val records = (1..100).map { record("r$it", allergen = bigText) }

        val batches = packer.pack(records).batches

        assertTrue("expected more than one batch, got ${batches.size}", batches.size > 1)
        batches.forEach { batch ->
            val batchBytes = batch.records.sumOf { gson.toJson(it).toByteArray(Charsets.UTF_8).size.toLong() }
            assertTrue("batch exceeded MAX_BYTES: $batchBytes", batchBytes <= SyncBatchPacker.MAX_BYTES)
            assertTrue(batch.records.size <= SyncBatchPacker.MAX_RECORDS)
        }
        assertEquals(100, batches.sumOf { it.records.size })
    }

    @Test
    fun `a single record larger than the byte budget is rejected, not packed into an oversized batch`() {
        // A singleton batch over MAX_BYTES could still 413 against the backend's real 5 MB
        // ceiling once the request envelope is added, and SyncOutboxDrainer preserves a failed
        // in-flight batch and retries it before anything else — so packing this in would block
        // every other PENDING record behind it forever. It must be reported as oversized instead,
        // not silently dropped and not packed.
        val hugeText = "x".repeat(5_000_000) // one record alone exceeds MAX_BYTES
        val records = listOf(record("huge", allergen = hugeText))

        val result = packer.pack(records)

        assertTrue(result.batches.isEmpty())
        assertEquals(listOf("huge"), result.oversized.map { it.id })
    }

    @Test
    fun `large backlog drains into multiple correctly-bounded batches, none dropped or duplicated`() {
        // The first-sync-drain shape this session exists for: well over one batch's worth of rows.
        val records = (1..1200).map { record("r$it") }

        val batches = packer.pack(records).batches

        val allIds = batches.flatMap { it.records.map { r -> r.id } }
        assertEquals(1200, allIds.size)
        assertEquals(1200, allIds.toSet().size) // none duplicated
        assertEquals(records.map { it.id }.toSet(), allIds.toSet()) // none dropped
        batches.forEach { assertTrue(it.records.size <= SyncBatchPacker.MAX_RECORDS) }
    }
}
