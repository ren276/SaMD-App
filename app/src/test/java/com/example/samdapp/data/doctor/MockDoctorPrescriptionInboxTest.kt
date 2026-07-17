package com.example.samdapp.data.doctor

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MockDoctorPrescriptionInboxTest {

    private fun caseRecord(assignedDoctorId: String? = "doc-1") = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "e1", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = assignedDoctorId, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun kernelOutput() = KernelReportOutput(
        id = "k1", caseRecordId = "case-1", predictedCondition = "Viral fever",
        confidenceScore = 0.85, differentials = listOf("Dengue", "Typhoid"),
        reasoningSummary = "reasoning", evidenceFor = listOf("fever"), evidenceAgainst = emptyList(),
        modelVersion = "mock-kernel-v0.1", inferenceTimestamp = Instant.EPOCH, requiredHumanVerification = true,
    )

    @Test
    fun `no doctor assigned yet returns null, not a fabricated response`() = runTest {
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord(assignedDoctorId = null)))
        val inbox = MockDoctorPrescriptionInbox(caseRepo, FakeKernelReportRepository())

        assertNull(inbox.fetchPrescription("case-1").getOrThrow())
    }

    @Test
    fun `unknown case record returns null rather than throwing`() = runTest {
        val inbox = MockDoctorPrescriptionInbox(FakeCaseRecordRepository(), FakeKernelReportRepository())

        assertNull(inbox.fetchPrescription("no-such-case").getOrThrow())
    }

    @Test
    fun `an assigned doctor with a kernel output produces a real prescription with a medication line`() = runTest {
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord()))
        val kernelRepo = FakeKernelReportRepository().apply { saved["case-1"] = kernelOutput() }
        val inbox = MockDoctorPrescriptionInbox(caseRepo, kernelRepo)

        val incoming = inbox.fetchPrescription("case-1").getOrThrow()!!

        assertEquals("doc-1", incoming.doctorId)
        assertTrue(incoming.diagnosis.isNotBlank())
        assertTrue(incoming.medications.isNotEmpty())
    }

    @Test
    fun `no kernel output on record still produces a fallback prescription (MODIFY)`() = runTest {
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord()))
        val inbox = MockDoctorPrescriptionInbox(caseRepo, FakeKernelReportRepository())

        val incoming = inbox.fetchPrescription("case-1").getOrThrow()!!

        assertEquals(com.example.samdapp.domain.model.KernelDecision.MODIFY, incoming.kernelDecision)
    }
}
