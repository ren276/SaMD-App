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

    private fun record(table: String, id: String, patientId: String?, reason: SyncFailureReason) =
        FailedSyncRecord(
            table = table, recordId = id, patientId = patientId, patientName = "Asha Devi",
            recordedAt = Instant.EPOCH, reason = reason,
        )

    @Test
    fun `children of a duplicate patient fold under it and offer no button`() {
        val folded = foldUnderNotAcceptedPatients(
            listOf(
                record("patients", "pat-1", "pat-1", SyncFailureReason.DUPLICATE_RECORD),
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

    @Test
    fun `a conflicted patient folds its children too`() {
        val folded = foldUnderNotAcceptedPatients(
            listOf(
                record("patients", "pat-1", "pat-1", SyncFailureReason.CONFLICT_ON_SERVER),
                record("encounters", "enc-1", "pat-1", SyncFailureReason.RETRIES_EXHAUSTED),
            ),
        )
        assertEquals(1, folded.single().heldRecordCount)
    }

    @Test
    fun `another patient's records and records with no patient are untouched`() {
        val input = listOf(
            record("patients", "pat-1", "pat-1", SyncFailureReason.DUPLICATE_RECORD),
            record("encounters", "enc-2", "pat-2", SyncFailureReason.RETRIES_EXHAUSTED),
            record("abha_profiles", "abha-1", null, SyncFailureReason.UNRECOGNISED),
        )

        val folded = foldUnderNotAcceptedPatients(input)

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
        assertEquals(input, foldUnderNotAcceptedPatients(input))
    }
}
