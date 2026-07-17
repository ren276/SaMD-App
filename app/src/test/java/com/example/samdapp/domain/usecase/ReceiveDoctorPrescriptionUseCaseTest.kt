package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.doctor.IncomingPrescription
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private class FakeDoctorPrescriptionInbox(private val response: IncomingPrescription? = null) : DoctorPrescriptionInbox {
    var callCount = 0
    var failure: Throwable? = null
    override suspend fun fetchPrescription(caseRecordId: String): Result<IncomingPrescription?> {
        callCount++
        failure?.let { return Result.failure(it) }
        return Result.success(response)
    }
}

/** REQ-RX-01/03: the receiving half of the out-of-app doctor review. */
class ReceiveDoctorPrescriptionUseCaseTest {

    private val medication = MedicationLine("Paracetamol", null, "500 mg", "1 tablet", "twice daily", "oral", "5 days", "10 tablets", null, null)

    private fun caseRecord() = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "e1", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun `no response yet returns success with null, not a failure`() = runTest {
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord()))
        val prescriptionRepo = FakePrescriptionRepository()
        val useCase = ReceiveDoctorPrescriptionUseCase(FakeDoctorPrescriptionInbox(response = null), caseRepo, prescriptionRepo)

        val result = useCase("case-1", "p1", "e1")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertTrue(prescriptionRepo.saved.isEmpty())
        assertEquals(CaseStatus.SENT_TO_DOCTOR, caseRepo.records["case-1"]!!.status)
    }

    @Test
    fun `an arriving prescription is persisted and flips the case to PRESCRIPTION_RECEIVED`() = runTest {
        val incoming = IncomingPrescription(
            doctorId = "doc-1", diagnosis = "Viral fever", medications = listOf(medication),
            kernelDecision = KernelDecision.AGREE,
        )
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord()))
        val prescriptionRepo = FakePrescriptionRepository()
        val useCase = ReceiveDoctorPrescriptionUseCase(FakeDoctorPrescriptionInbox(incoming), caseRepo, prescriptionRepo)

        val result = useCase("case-1", "p1", "e1")

        val prescription = result.getOrThrow()!!
        assertEquals("Viral fever", prescription.diagnosis)
        assertEquals(KernelDecision.AGREE, prescription.kernelDecision)
        assertEquals(prescription, prescriptionRepo.saved["case-1"])
        assertEquals(CaseStatus.PRESCRIPTION_RECEIVED, caseRepo.records["case-1"]!!.status)
    }

    @Test
    fun `inbox failure surfaces as a failed Result and does not touch the case status`() = runTest {
        val caseRepo = FakeCaseRecordRepository(listOf(caseRecord()))
        val prescriptionRepo = FakePrescriptionRepository()
        val inbox = FakeDoctorPrescriptionInbox().apply { failure = RuntimeException("transport error") }
        val useCase = ReceiveDoctorPrescriptionUseCase(inbox, caseRepo, prescriptionRepo)

        val result = useCase("case-1", "p1", "e1")

        assertTrue(result.isFailure)
        assertEquals(CaseStatus.SENT_TO_DOCTOR, caseRepo.records["case-1"]!!.status)
    }
}
