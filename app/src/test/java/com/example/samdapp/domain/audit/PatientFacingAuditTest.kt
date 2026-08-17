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
            entry("case_sent_to_doctor"),
            entry(AuditAction.ENCOUNTER_RESUMED.value),
        ).toPatientFacingEntries()

        assertEquals(
            listOf(
                "PHC worker created your patient record",
                "You gave consent for this visit",
                "PHC worker recorded your symptoms",
                "Sent for doctor review",
                "PHC worker resumed your consultation",
            ),
            entries.map { it.description },
        )
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
