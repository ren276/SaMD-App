package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.google.gson.Gson
import java.util.UUID
import javax.inject.Inject

/** One outbox batch: a fresh `batch_id` (the backend's idempotency key, api-contract.md §6.1)
 *  and the records it carries, both bounded by [SyncBatchPacker]'s budgets. */
data class SyncBatch(val batchId: String, val records: List<SyncRecordDto>)

/**
 * Packs PENDING rows into batches under two budgets (the locked Phase 6b decision): 400 records
 * or 4.5 MB serialized, whichever comes first — headroom under the backend's real ceiling of
 * 500 records / 5 MB (SAMD-SYNC-6001). This makes the first-sync drain (every pre-existing row is
 * PENDING, MIGRATION_12_13) structurally incapable of tripping a 413, so no retry-with-smaller
 * fallback is needed anywhere in this codebase.
 *
 * Byte accounting sums each record's *actual* Gson-serialized size (the same [Gson] instance
 * di/NetworkModule.kt wires into the real Retrofit call), not an estimate — and the 0.5 MB gap
 * to the backend's 5 MB ceiling is deliberately generous headroom for the request envelope's
 * fixed `batch_id`/`device_id`/`client_time` overhead (a few hundred bytes), which this per-record
 * sum does not itself include.
 *
 * Pure: no I/O, no suspend, no DB/network access — see SyncBatchPackerTest for the boundary
 * cases (exactly-at-limit, one-over, a single oversized record, empty input).
 */
/** [batches] are ready to send. [oversized] are records whose own serialized size already
 *  exceeds [SyncBatchPacker.MAX_BYTES] — packing one alone into a singleton batch would still
 *  risk a 413 from the backend's real ceiling, and that one malformed record would then block
 *  every other record behind it forever (SyncOutboxDrainer preserves an in-flight batch on
 *  failure and retries it before anything else). The caller marks each of these `FAILED` locally
 *  instead, without spending a network round trip. */
data class SyncPackResult(val batches: List<SyncBatch>, val oversized: List<SyncRecordDto>)

class SyncBatchPacker @Inject constructor(private val gson: Gson) {

    fun pack(records: List<SyncRecordDto>): SyncPackResult {
        val batches = mutableListOf<SyncBatch>()
        val oversized = mutableListOf<SyncRecordDto>()
        var current = mutableListOf<SyncRecordDto>()
        var currentBytes = 0L

        for (record in records) {
            val recordBytes = gson.toJson(record).toByteArray(Charsets.UTF_8).size.toLong()
            if (recordBytes > MAX_BYTES) {
                oversized += record
                continue
            }
            val wouldOverflow = current.isNotEmpty() &&
                (current.size + 1 > MAX_RECORDS || currentBytes + recordBytes > MAX_BYTES)
            if (wouldOverflow) {
                batches += SyncBatch(UUID.randomUUID().toString(), current)
                current = mutableListOf()
                currentBytes = 0
            }
            current.add(record)
            currentBytes += recordBytes
        }
        if (current.isNotEmpty()) {
            batches += SyncBatch(UUID.randomUUID().toString(), current)
        }
        return SyncPackResult(batches, oversized)
    }

    companion object {
        const val MAX_RECORDS = 400
        val MAX_BYTES = 4_500_000L // 4.5 MB, headroom under the backend's 5 MB (SAMD-SYNC-6001).

        init {
            // Sanity guard against an accidental future edit widening these past the backend's
            // real ceiling (500 / 5_000_000), which would reopen the 413 this design exists to
            // rule out structurally.
            check(MAX_RECORDS < 500) { "MAX_RECORDS must stay under the backend's 500-record ceiling" }
            check(MAX_BYTES < 5_000_000L) { "MAX_BYTES must stay under the backend's 5 MB ceiling" }
        }
    }
}
