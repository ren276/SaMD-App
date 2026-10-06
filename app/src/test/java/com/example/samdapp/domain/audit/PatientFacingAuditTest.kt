package com.example.samdapp.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Item 3, patient-facing audit view: raw audit rows must map to plain language only — never a
 *  raw action code, entity id, or payload field. */
class PatientFacingAuditTest {

    private fun entry(action: String) = AuditLogEntry(
        id = "log-1", timestamp = Instant.EPOCH, action = action, patientId = "p1", caseRecordId = "case-1",
    )

    @Test
    fun `maps known actions to plain language`() {
        val entries = listOf(
            entry("patient_registered"),
            entry(AuditAction.CONSENT_RECORDED.value),
            entry(AuditAction.AILMENT_CAPTURED.value),
            entry(AuditAction.ENCOUNTER_RESUMED.value),
        ).toPatientFacingEntries()

        assertEquals(
            listOf(
                "PHC worker created your patient record",
                "You gave consent for this visit",
                "PHC worker recorded your symptoms",
                "PHC worker resumed your consultation",
            ),
            entries.map { it.description },
        )
    }

    @Test
    fun `assigning a doctor reads as queued, not sent`() {
        // CASE_SENT_TO_DOCTOR is logged when the case is assigned on this phone
        // (DoctorAssignmentConfirmViewModel, after a local status change); nothing has reached the
        // server or the doctor at that moment. The history must say what actually happened. The
        // words live in strings.xml, so the entry carries a message key, not a sentence.
        val entry = listOf(entry(AuditAction.CASE_SENT_TO_DOCTOR.value)).toPatientFacingEntries().single()

        assertEquals(PatientFacingMessage.QUEUED_FOR_DOCTOR_REVIEW, entry.message)
        assertEquals(null, entry.description)
    }

    @Test
    fun `unmapped action falls back to a generic sentence, never the raw code`() {
        val entries = listOf(entry("some_future_action_code")).toPatientFacingEntries()

        assertEquals("PHC worker updated your record", entries.single().description)
    }

    @Test
    fun `preserves timestamp and order`() {
        val entries = listOf(entry("patient_registered")).toPatientFacingEntries()

        assertEquals(Instant.EPOCH, entries.single().timestamp)
    }
}
