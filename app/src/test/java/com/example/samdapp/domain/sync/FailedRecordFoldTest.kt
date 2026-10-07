package com.example.samdapp.domain.sync

import com.example.samdapp.domain.model.SyncFailureAction
import com.example.samdapp.domain.model.SyncFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * A patient the server will not accept (a duplicate ABHA, a conflict, a refused or oversize
 * record) can never land, so nothing that belongs to it can land either. Its children fail their
 * foreign key, exhaust their retries, and on master each one was listed separately with "Send
 * again", a button that cannot work. They are folded under the patient's row instead.
 */
class FailedRecordFoldTest {

    private fun record(
        table: String,
        id: String,
        patientId: String?,
        reason: SyncFailureReason,
        ancestors: List<Pair<String, String>> = listOfNotNull(patientId?.let { "patients" to it }),
        holdsDescendants: Boolean = false,
        held: Boolean = false,
    ) = FailedSyncRecord(
        table = table, recordId = id, patientId = patientId, patientName = "Asha Devi",
        recordedAt = Instant.EPOCH, reason = reason,
        ancestors = ancestors, holdsDescendants = holdsDescendants, held = held,
    )

    @Test
    fun `children of a duplicate patient fold under it and offer no button`() {
        val folded = foldHeldRecords(
            listOf(
                record("patients", "pat-1", "pat-1", SyncFailureReason.DUPLICATE_RECORD, holdsDescendants = true),
                record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
                record("case_records", "case-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
                record("observations", "obs-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
            ),
        )

        assertEquals(listOf("patients" to "pat-1"), folded.map { it.table to it.recordId })
        assertEquals(3, folded.single().heldRecordCount)
        assertTrue(
            "no folded child may surface a Send again that cannot work",
            folded.none { it.reason.action == SyncFailureAction.SEND_AGAIN },
        )
    }

    /** Operator ruling Q2: a CONFLICT patient is on the server, so it holds nothing and its children
     *  keep their own rows and their own Send again. */
    @Test
    fun `a conflicted patient holds nothing, so its children keep their rows`() {
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.CONFLICT_ON_SERVER),
            record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
        )
        assertEquals(input, foldHeldRecords(input))
    }

    @Test
    fun `a failed patient the server already holds is not a holder either`() {
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.RECORD_REJECTED),
            record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
        )
        assertEquals(input, foldHeldRecords(input))
    }

    @Test
    fun `another patient's records and records with no patient are untouched`() {
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.DUPLICATE_RECORD, holdsDescendants = true),
            record("encounters", "enc-2", "pat-2", SyncFailureReason.RETRIES_EXHAUSTED),
            record("abha_profiles", "abha-1", null, SyncFailureReason.UNRECOGNISED),
        )

        val folded = foldHeldRecords(input)

        assertEquals(input.map { it.recordId }, folded.map { it.recordId })
        assertEquals(0, folded.first().heldRecordCount)
    }

    @Test
    fun `a patient that only needs Send again does not fold its children`() {
        // RETRIES_EXHAUSTED on the patient itself is the one cause a press can change, so its
        // children keep their own rows and their own Send again.
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
            record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
        )
        assertEquals(input, foldHeldRecords(input))
    }

    @Test
    fun `rows held behind a failed patient fold under it whatever its cause, and are counted once`() {
        val folded = foldHeldRecords(
            listOf(
                record("patients", "pat-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED, holdsDescendants = true),
                record("encounters", "enc-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true),
                record("observations", "obs-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true),
                record("case_records", "case-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true),
            ),
        )

        assertEquals(listOf("patients" to "pat-1"), folded.map { it.table to it.recordId })
        assertEquals(3, folded.single().heldRecordCount)
    }

    @Test
    fun `a failed encounter holds its own rows, and the patient wins when both hold`() {
        val encounterOnly = foldHeldRecords(
            listOf(
                record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED, holdsDescendants = true),
                record(
                    "observations", "obs-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true,
                    ancestors = listOf("patients" to "pat-1", "encounters" to "enc-1"),
                ),
            ),
        )
        assertEquals(listOf("encounters" to "enc-1"), encounterOnly.map { it.table to it.recordId })
        assertEquals(1, encounterOnly.single().heldRecordCount)

        val both = foldHeldRecords(
            listOf(
                record("patients", "pat-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED, holdsDescendants = true),
                record(
                    "encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED, holdsDescendants = true,
                    ancestors = listOf("patients" to "pat-1"),
                ),
                record(
                    "observations", "obs-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true,
                    ancestors = listOf("patients" to "pat-1", "encounters" to "enc-1"),
                ),
            ),
        )
        // The encounter is FAILED, not held, and its patient's cause is retryable: it keeps its row.
        assertEquals(listOf("patients" to "pat-1", "encounters" to "enc-1"), both.map { it.table to it.recordId })
        assertEquals(1, both.first().heldRecordCount)
        assertEquals(0, both.last().heldRecordCount)
    }

    @Test
    fun `a held row with no holder in the list is dropped, not shown`() {
        val folded = foldHeldRecords(
            listOf(record("observations", "obs-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true)),
        )
        assertTrue(folded.isEmpty())
    }

    @Test
    fun `a held row under a conflicted patient has no holder and is dropped`() {
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.CONFLICT_ON_SERVER),
            record("encounters", "enc-1", "pat-1", SyncFailureReason.UNRECOGNISED, held = true),
        )
        // The SQL never marks a row held behind a CONFLICT ancestor, so this cannot arise from the
        // database; the fold still must not invent a holder.
        assertEquals(listOf("patients" to "pat-1"), foldHeldRecords(input).map { it.table to it.recordId })
    }
}
