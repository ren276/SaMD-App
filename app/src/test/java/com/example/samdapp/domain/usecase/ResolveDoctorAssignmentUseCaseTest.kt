package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeDoctorRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Part B: "empanelment/attribution" for follow-ups, least-busy auto-assign otherwise. */
class ResolveDoctorAssignmentUseCaseTest {

    private fun doctor(id: String, specialty: String = "General Physician", available: Boolean = true) =
        Doctor(id = id, name = "Dr. $id", specialty = specialty, available = available, facilityName = null, registrationNumber = null)

    @Test
    fun `fresh case with no follow-up link auto-assigns the least-busy active doctor`() = runTest {
        val doctors = listOf(doctor("busy"), doctor("free"))
        val caseRecords = FakeCaseRecordRepository(
            listOf(
                CaseRecord(id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SAVED_LOCALLY, assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
                CaseRecord(id = "case-open", patientId = "p2", encounterId = "enc-open", status = CaseStatus.SENT_TO_DOCTOR, assignedDoctorId = "busy", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            ),
        )
        val encounters = FakeEncounterRepository(
            initialEncounters = listOf(Encounter(id = "enc-1", patientId = "p1", startedAt = Instant.EPOCH, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = null)),
        )
        val useCase = ResolveDoctorAssignmentUseCase(encounters, caseRecords, FakeDoctorRepository(doctors), FakeKernelReportRepository(), FakeEvaluateReportRepository())

        val proposal = useCase("case-1", "enc-1").getOrThrow()

        assertEquals("free", proposal.doctor.id)
        assertFalse(proposal.isContinuity)
    }

    @Test
    fun `follow-up to a prior visit defaults to that visit's doctor`() = runTest {
        val doctors = listOf(doctor("prior-doc"), doctor("other-doc"))
        val caseRecords = FakeCaseRecordRepository(
            listOf(
                CaseRecord(id = "case-prior", patientId = "p1", encounterId = "enc-prior", status = CaseStatus.PRESCRIPTION_RECEIVED, assignedDoctorId = "prior-doc", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
                CaseRecord(id = "case-new", patientId = "p1", encounterId = "enc-new", status = CaseStatus.SAVED_LOCALLY, assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            ),
        )
        val encounters = FakeEncounterRepository(
            initialEncounters = listOf(Encounter(id = "enc-new", patientId = "p1", startedAt = Instant.EPOCH, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = "enc-prior")),
        )
        val useCase = ResolveDoctorAssignmentUseCase(encounters, caseRecords, FakeDoctorRepository(doctors), FakeKernelReportRepository(), FakeEvaluateReportRepository())

        val proposal = useCase("case-new", "enc-new").getOrThrow()

        assertEquals("prior-doc", proposal.doctor.id)
        assertTrue(proposal.isContinuity)
    }

    @Test
    fun `follow-up whose prior doctor is no longer active falls back to auto-assign`() = runTest {
        val doctors = listOf(doctor("prior-doc", available = false), doctor("other-doc"))
        val caseRecords = FakeCaseRecordRepository(
            listOf(
                CaseRecord(id = "case-prior", patientId = "p1", encounterId = "enc-prior", status = CaseStatus.PRESCRIPTION_RECEIVED, assignedDoctorId = "prior-doc", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
                CaseRecord(id = "case-new", patientId = "p1", encounterId = "enc-new", status = CaseStatus.SAVED_LOCALLY, assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            ),
        )
        val encounters = FakeEncounterRepository(
            initialEncounters = listOf(Encounter(id = "enc-new", patientId = "p1", startedAt = Instant.EPOCH, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = "enc-prior")),
        )
        val useCase = ResolveDoctorAssignmentUseCase(encounters, caseRecords, FakeDoctorRepository(doctors), FakeKernelReportRepository(), FakeEvaluateReportRepository())

        val proposal = useCase("case-new", "enc-new").getOrThrow()

        assertEquals("other-doc", proposal.doctor.id)
        assertFalse(proposal.isContinuity)
    }

    @Test
    fun `same-specialty alternatives exclude the proposed doctor and other specialties`() = runTest {
        val doctors = listOf(
            doctor("gp-1", specialty = "General Physician"),
            doctor("gp-2", specialty = "General Physician"),
            doctor("doc-1", specialty = "Dermatology"),
        )
        val useCase = ResolveDoctorAssignmentUseCase(FakeEncounterRepository(), FakeCaseRecordRepository(), FakeDoctorRepository(doctors), FakeKernelReportRepository(), FakeEvaluateReportRepository())

        val alternatives = useCase.sameSpecialtyAlternatives("General Physician", excludingDoctorId = "gp-1").getOrThrow()

        assertEquals(listOf("gp-2"), alternatives.map { it.id })
    }
}
