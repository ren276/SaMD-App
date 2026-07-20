package com.example.samdapp.domain.report

import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ReportFormatterTest {

    private val formatter = ReportFormatter()

    private fun patient(
        age: Int? = 34,
        dob: LocalDate? = null,
        guardian: String? = null,
        guardianRelation: String? = null,
        abha: String? = "43422151056749",
    ) = Patient(
        id = "UID123456789", fullName = "Anita Kumari", dateOfBirth = dob, age = age,
        biologicalSex = "Female", guardianOrSpouseName = guardian, guardianRelation = guardianRelation,
        mobileNumber = "9998887776", aadhaarNumber = null, abhaNumber = abha, village = "Rampur",
        block = null, district = "Sitapur", state = "Uttar Pradesh", pincode = "261001", category = "General",
        maritalStatus = null, bloodGroup = null, emergencyContact = null, primaryCareClinicName = "PHC Rampur",
        referringPhysicianName = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun ailment(desc: String, visibility: Visibility, type: MeasurementType, severity: Int? = 6) = AilmentEntry(
        id = desc, patientId = "p", encounterId = "e", description = desc, measurementType = type,
        visibility = visibility, measuredValue = if (type == MeasurementType.MEASURABLE) 101.0 else null,
        measuredUnit = if (type == MeasurementType.MEASURABLE) "°F" else null, severity = severity, onset = null,
        duration = "3 days", qualifiers = null, audioLocalUri = null, capturedAtOffline = Instant.EPOCH,
        syncedToCloudAt = null, deletedAt = null, createdAt = Instant.EPOCH,
    )

    private fun format(
        audience: ReportAudience,
        ailments: List<AilmentEntry> = emptyList(),
        patient: Patient = patient(),
        prescription: Prescription? = null,
        doctor: Doctor? = null,
        attachments: List<com.example.samdapp.domain.model.Attachment> = emptyList(),
    ) = formatter.format(
        audience = audience, patient = patient, abhaProfile = null,
        consultationChiefComplaint = "Fever and body ache for two days", ailments = ailments,
        vitals = null, consultationAttachments = attachments, consultationRecordNo = "CR-001",
        visitDateTime = Instant.EPOCH, kernelOutput = null, prescription = prescription,
        prescribingDoctor = doctor,
    )

    @Test
    fun `header and patient block bind to real fields`() {
        val report = format(ReportAudience.WORKER)
        assertEquals("CR-001", report.header.consultationRecordNo)
        assertEquals("UID123456789", report.header.patientUid)
        assertEquals("PHC Rampur", report.header.phcName)
        assertEquals("Anita Kumari", report.patient.fullName)
        assertEquals("34y / Female", report.patient.ageSex)
        assertEquals("XX-XXXX-XXXX-6749", report.patient.abhaNumberFormatted)
        assertTrue(report.patient.address!!.contains("Sitapur"))
    }

    @Test
    fun `chief complaint is carried verbatim`() {
        assertEquals("Fever and body ache for two days", format(ReportAudience.WORKER).chiefComplaintVerbatim)
    }

    @Test
    fun `guardian details only appear for a minor`() {
        val adult = format(ReportAudience.PHYSICIAN, patient = patient(age = 34, guardian = "Sunita", guardianRelation = "Mother"))
        assertNull(adult.patient.guardianName)
        val minor = format(ReportAudience.PHYSICIAN, patient = patient(age = 8, guardian = "Sunita", guardianRelation = "Mother"))
        assertEquals("Sunita", minor.patient.guardianName)
        assertEquals("Mother", minor.patient.guardianRelation)
    }

    @Test
    fun `measurable ailments are ordered before non-measurable`() {
        val report = format(
            ReportAudience.PHYSICIAN,
            ailments = listOf(
                ailment("Body ache", Visibility.PUBLIC, MeasurementType.NON_MEASURABLE),
                ailment("Fever", Visibility.PUBLIC, MeasurementType.MEASURABLE),
            ),
        )
        assertEquals(MeasurementType.MEASURABLE, report.ailments.first().measurementType)
        assertEquals("Fever", report.ailments.first().description)
    }

    @Test
    fun `WORKER audience redacts a private ailment — text is absent, not hidden`() {
        val report = format(
            ReportAudience.WORKER,
            ailments = listOf(ailment("Sensitive issue", Visibility.PRIVATE, MeasurementType.NON_MEASURABLE)),
        )
        val line = report.ailments.single()
        assertTrue(line.isRedacted)
        assertNull(line.description)
        assertNull(line.detail)
    }

    @Test
    fun `PHYSICIAN audience shows a private ailment in full`() {
        val report = format(
            ReportAudience.PHYSICIAN,
            ailments = listOf(ailment("Sensitive issue", Visibility.PRIVATE, MeasurementType.NON_MEASURABLE)),
        )
        val line = report.ailments.single()
        assertFalse(line.isRedacted)
        assertEquals("Sensitive issue", line.description)
    }

    @Test
    fun `preliminary report has no prescription, kernel, or signature and is not final`() {
        val report = format(ReportAudience.WORKER)
        assertTrue(report.prescription.isEmpty())
        assertNull(report.kernelOutput)
        assertNull(report.signature)
        assertFalse(report.isFinal)
    }

    @Test
    fun `medication line formats with full-text frequency and binds every field`() {
        val line = MedicationLine(
            genericName = "Paracetamol", brandName = "Crocin", strength = "500 mg", dosage = "1 tablet",
            frequency = "twice daily", route = "oral", duration = "5 days", quantity = "10 tablets",
            foodRelation = "after food", instructions = null,
        )
        val text = formatter.formatMedicationLine(line)
        assertEquals("Paracetamol (Crocin) - 500 mg | oral | twice daily | 5 days | 10 tablets", text)
    }

    @Test
    fun `a banned Latin frequency abbreviation is rejected`() {
        val bad = MedicationLine(
            genericName = "Paracetamol", brandName = null, strength = "500 mg", dosage = "1 tablet",
            frequency = "BD", route = "oral", duration = "5 days", quantity = "10 tablets",
            foodRelation = null, instructions = null,
        )
        assertThrows(IllegalArgumentException::class.java) { formatter.formatMedicationLine(bad) }
    }

    @Test
    fun `a full prescription flips the report to final with a signature block`() {
        val prescription = Prescription(
            id = "rx1", patientId = "p", encounterId = "e", caseRecordId = "CR-001", doctorId = "doc-gen-001",
            diagnosis = "Viral fever",
            medications = listOf(
                MedicationLine("Paracetamol", "Crocin", "500 mg", "1 tablet", "twice daily", "oral", "5 days", "10 tablets", "after food", null),
            ),
            kernelDecision = com.example.samdapp.domain.model.KernelDecision.AGREE,
            createdAt = Instant.EPOCH,
        )
        val doctor = Doctor("doc-gen-001", "Dr. Anjali Sharma", "General Physician", true, "PHC Rampur", "NMC/TS/2011/45231")
        val report = format(ReportAudience.PHYSICIAN, prescription = prescription, doctor = doctor)
        assertTrue(report.isFinal)
        assertEquals("Viral fever", report.diagnosis)
        assertEquals(1, report.prescription.size)
        assertEquals("Dr. Anjali Sharma", report.signature!!.doctorName)
        assertEquals("NMC/TS/2011/45231", report.signature!!.registrationNumber)
        assertEquals("General Physician", report.signature!!.specialty)
        assertEquals("PHC Rampur", report.signature!!.facilityName)
        assertEquals(com.example.samdapp.domain.model.KernelDecision.AGREE, report.kernelDecision)
    }

    /** REQ-REF-01: referral eligibility — decision recorded in PROGRESS.md ("always visible,
     *  enabled only when suggestsReferral"), computed here so the button/sheet stay dumb. */
    @Test
    fun `low severity and no kernel rejection does not suggest a referral`() {
        val report = format(
            ReportAudience.PHYSICIAN,
            ailments = listOf(ailment("Mild headache", Visibility.PUBLIC, MeasurementType.NON_MEASURABLE, severity = 4)),
        )
        assertFalse(report.suggestsReferral)
    }

    @Test
    fun `severity at or above the threshold suggests a referral`() {
        val report = format(
            ReportAudience.PHYSICIAN,
            ailments = listOf(ailment("Severe pain", Visibility.PUBLIC, MeasurementType.NON_MEASURABLE, severity = 8)),
        )
        assertTrue(report.suggestsReferral)
        assertTrue(report.referralReasonSuggestion.contains("8/10"))
    }

    @Test
    fun `a doctor REJECT of the kernel differential suggests a referral regardless of severity`() {
        val prescription = Prescription(
            id = "rx1", patientId = "p", encounterId = "e", caseRecordId = "CR-001", doctorId = "doc-gen-001",
            diagnosis = "Needs specialist review", medications = emptyList(),
            kernelDecision = com.example.samdapp.domain.model.KernelDecision.REJECT, createdAt = Instant.EPOCH,
        )
        val report = format(
            ReportAudience.PHYSICIAN,
            ailments = listOf(ailment("Mild issue", Visibility.PUBLIC, MeasurementType.NON_MEASURABLE, severity = 2)),
            prescription = prescription,
        )
        assertTrue(report.suggestsReferral)
        assertTrue(report.referralReasonSuggestion.contains("did not concur"))
    }

    @Test
    fun `referralReasonSuggestion is never blank even with no ailments and no prescription`() {
        val report = format(ReportAudience.WORKER)
        assertFalse(report.referralReasonSuggestion.isBlank())
    }

    /** Attachments pass through to the report unmodified — same posture as KernelPayload. */
    @Test
    fun `attachments are carried through with type and a numbered label`() {
        val attachments = listOf(
            com.example.samdapp.domain.model.Attachment(
                id = "a1", consultationId = "c1", type = com.example.samdapp.domain.model.AttachmentType.IMAGE,
                uri = "content://photo1", createdAt = Instant.EPOCH,
            ),
            com.example.samdapp.domain.model.Attachment(
                id = "a2", consultationId = "c1", type = com.example.samdapp.domain.model.AttachmentType.AFFECTED_AREA_PHOTO,
                uri = "content://photo2", createdAt = Instant.EPOCH,
            ),
            com.example.samdapp.domain.model.Attachment(
                id = "a3", consultationId = "c1", type = com.example.samdapp.domain.model.AttachmentType.AUDIO,
                uri = "content://audio1", createdAt = Instant.EPOCH,
            ),
        )
        val report = format(ReportAudience.WORKER, attachments = attachments)

        assertEquals(3, report.attachments.size)
        assertEquals("content://photo1", report.attachments[0].uri)
        assertEquals("Photo 1", report.attachments[0].label)
        assertEquals("Affected area photo 1", report.attachments[1].label)
        assertEquals("Audio 1", report.attachments[2].label)
    }

    @Test
    fun `attachment numbering is per-type, not global`() {
        val attachments = listOf(
            com.example.samdapp.domain.model.Attachment("a1", "c1", com.example.samdapp.domain.model.AttachmentType.IMAGE, "u1", Instant.EPOCH),
            com.example.samdapp.domain.model.Attachment("a2", "c1", com.example.samdapp.domain.model.AttachmentType.AUDIO, "u2", Instant.EPOCH),
            com.example.samdapp.domain.model.Attachment("a3", "c1", com.example.samdapp.domain.model.AttachmentType.IMAGE, "u3", Instant.EPOCH),
        )
        val report = format(ReportAudience.WORKER, attachments = attachments)

        assertEquals("Photo 1", report.attachments[0].label)
        assertEquals("Audio 1", report.attachments[1].label)
        assertEquals("Photo 2", report.attachments[2].label)
    }
}
