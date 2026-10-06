package com.example.samdapp.data.sync

import com.example.samdapp.data.local.dao.FailedSyncRow
import com.example.samdapp.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** How a review row becomes a record the fold can place: who holds it, and whether it holds. */
class FailedSyncRowRecordTest {

    private fun row(
        table: String,
        state: SyncState,
        serverVersion: Int? = null,
        patientId: String? = "pat-1",
        encounterId: String? = null,
        caseRecordId: String? = null,
        parentId: String? = null,
    ) = FailedSyncRow(
        tableName = table, syncState = state, recordId = "rec-1", patientId = patientId,
        recordedAt = Instant.EPOCH, syncErrorCode = null, syncErrorMessage = null,
        serverVersion = serverVersion, encounterId = encounterId, caseRecordId = caseRecordId, parentId = parentId,
    )

    @Test
    fun `a FAILED never-held row of a holder table holds its descendants`() {
        listOf("patients", "encounters", "consultations", "case_records", "prescriptions").forEach {
            assertTrue(it, row(it, SyncState.FAILED).toRecord(emptyMap()).holdsDescendants)
        }
        assertFalse("a version means the server has it", row("encounters", SyncState.FAILED, serverVersion = 2).toRecord(emptyMap()).holdsDescendants)
        assertFalse("CONFLICT holds nothing", row("patients", SyncState.CONFLICT).toRecord(emptyMap()).holdsDescendants)
        assertFalse("not a holder table", row("observations", SyncState.FAILED).toRecord(emptyMap()).holdsDescendants)
    }

    @Test
    fun `only PENDING and RETRYABLE rows are held`() {
        assertTrue(row("observations", SyncState.PENDING).toRecord(emptyMap()).held)
        assertTrue(row("observations", SyncState.RETRYABLE).toRecord(emptyMap()).held)
        assertFalse(row("observations", SyncState.FAILED).toRecord(emptyMap()).held)
        assertFalse(row("observations", SyncState.CONFLICT).toRecord(emptyMap()).held)
    }

    @Test
    fun `ancestors run patient, encounter, case, then the parent by table`() {
        val medicationLine = row("medication_lines", SyncState.PENDING, encounterId = "enc-1", caseRecordId = "case-1", parentId = "rx-1")
        assertEquals(
            listOf("patients" to "pat-1", "encounters" to "enc-1", "case_records" to "case-1", "prescriptions" to "rx-1"),
            medicationLine.toRecord(emptyMap()).ancestors,
        )
        val attachment = row("attachments", SyncState.PENDING, parentId = "cons-1")
        assertEquals(listOf("patients" to "pat-1", "consultations" to "cons-1"), attachment.toRecord(emptyMap()).ancestors)
        assertEquals(emptyList<Pair<String, String>>(), row("abha_profiles", SyncState.FAILED, patientId = null).toRecord(emptyMap()).ancestors)
    }
}
