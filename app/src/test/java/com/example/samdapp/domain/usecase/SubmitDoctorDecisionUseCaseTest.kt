package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeDiagnosisFeedbackRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H-16 prescription visibility gate (Build 1): the REJECT reason field and the
 *  PRESCRIPTION_APPROVED audit action added at the decision commit. */
class SubmitDoctorDecisionUseCaseTest {

    private fun useCase(
        prescriptionRepository: FakePrescriptionRepository = FakePrescriptionRepository(),
        auditLogger: FakeAuditLogger = FakeAuditLogger(),
    ) = SubmitDoctorDecisionUseCase(
        caseRecordRepository = FakeCaseRecordRepository(),
        prescriptionRepository = prescriptionRepository,
        evaluateReportRepository = FakeEvaluateReportRepository(),
        diagnosisFeedbackRepository = FakeDiagnosisFeedbackRepository(),
        auditLogger = auditLogger,
    )

    @Test
    fun `REJECT with a written reason persists that reason verbatim as the prescription diagnosis`() = runTest {
        val repo = FakePrescriptionRepository()
        useCase(prescriptionRepository = repo)(
            caseRecordId = "case-1", patientId = "p1", encounterId = "enc-1",
            decision = PhysicianDecision.REJECT,
            manualDrugName = "", manualDosage = "", manualBrandName = "",
            rejectReason = "Vitals inconsistent with the AI candidate; refer for specialist review.",
        )
        assertEquals(
            "Vitals inconsistent with the AI candidate; refer for specialist review.",
            repo.saved.getValue("case-1").diagnosis,
        )
    }

    @Test
    fun `REJECT with a blank reason falls back to the fixed not-clinically-supported string`() = runTest {
        val repo = FakePrescriptionRepository()
        useCase(prescriptionRepository = repo)(
            caseRecordId = "case-1", patientId = "p1", encounterId = "enc-1",
            decision = PhysicianDecision.REJECT,
            manualDrugName = "", manualDosage = "", manualBrandName = "",
            rejectReason = "",
        )
        assertTrue(repo.saved.getValue("case-1").diagnosis.contains("not clinically supported"))
    }

    @Test
    fun `AGREE is unaffected by rejectReason — it is REJECT-only`() = runTest {
        val repo = FakePrescriptionRepository()
        useCase(prescriptionRepository = repo)(
            caseRecordId = "case-1", patientId = "p1", encounterId = "enc-1",
            decision = PhysicianDecision.AGREE,
            manualDrugName = "", manualDosage = "", manualBrandName = "",
            rejectReason = "should be ignored",
        )
        assertFalse(repo.saved.getValue("case-1").diagnosis.contains("should be ignored"))
    }

    @Test
    fun `a committed decision emits PRESCRIPTION_APPROVED with kernelDecision, prescriptionId, and medicationCount — never the drug name`() = runTest {
        val audit = FakeAuditLogger()
        val repo = FakePrescriptionRepository()
        useCase(prescriptionRepository = repo, auditLogger = audit)(
            caseRecordId = "case-1", patientId = "p1", encounterId = "enc-1",
            decision = PhysicianDecision.MODIFY,
            manualDrugName = "Amoxicillin", manualDosage = "250 mg", manualBrandName = "",
            correctedIcdCandidate = null,
        )
        val entry = audit.logged.single { it.action == "prescription_approved" }
        assertEquals("case-1", entry.caseRecordId)
        val prescriptionId = repo.saved.getValue("case-1").id
        assertTrue(entry.payload.contains("\"kernelDecision\":\"${KernelDecision.MODIFY.name}\""))
        assertTrue(entry.payload.contains(prescriptionId))
        assertTrue(entry.payload.contains("\"medicationCount\":\"1\""))
        assertFalse(entry.payload.contains("Amoxicillin"))
    }
}
