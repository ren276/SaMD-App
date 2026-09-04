package com.example.samdapp.presentation.patientsummary

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.usecase.SubmitDoctorDecisionUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeBrandLookupSource
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDiagnosisFeedbackRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testKernelReportOutput
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Part C: consultation history — the empty-state case (new patient, zero prior encounters) and
 *  the populated case (multiple encounters, most recent first per the repository contract). */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientSummaryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        patientId: String = "p1",
        history: List<ConsultationHistoryEntry> = emptyList(),
        caseRecordRepository: FakeCaseRecordRepository = FakeCaseRecordRepository(),
        kernelReportRepository: FakeKernelReportRepository = FakeKernelReportRepository(),
        evaluateReportRepository: FakeEvaluateReportRepository = FakeEvaluateReportRepository(),
        // Defaults to a DOCTOR session so the H-16 role gate (canOpenDoctorReview) doesn't change
        // the behaviour of every pre-existing test in this file; the gate itself is covered by a
        // dedicated test below.
        authSession: FakeAuthSession = FakeAuthSession(UserSession("doc-1", "Dr. Test", UserRole.DOCTOR)),
    ): PatientSummaryViewModel {
        val patientRepo = FakePatientRepository().apply { registered = testPatient(patientId) }
        return PatientSummaryViewModel(
            patientId = patientId,
            patientRepository = patientRepo,
            caseRecordRepository = caseRecordRepository,
            encounterRepository = FakeEncounterRepository(history = history),
            evaluateReportRepository = evaluateReportRepository,
            kernelReportRepository = kernelReportRepository,
            brandLookupSource = FakeBrandLookupSource(),
            submitDoctorDecisionUseCase = SubmitDoctorDecisionUseCase(
                caseRecordRepository = FakeCaseRecordRepository(),
                prescriptionRepository = FakePrescriptionRepository(),
                evaluateReportRepository = FakeEvaluateReportRepository(),
                diagnosisFeedbackRepository = FakeDiagnosisFeedbackRepository(),
                auditLogger = FakeAuditLogger(),
            ),
            authSession = authSession,
            consultationRepository = FakeConsultationRepository(),
            consultationDocumentRepository = FakeConsultationDocumentRepository(),
        )
    }

    @Test
    fun `new patient with zero prior encounters shows an empty history, not a stuck loading state`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(history = emptyList())

        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.history.isEmpty())
        assertFalse(state.isLoadingHistory)
    }

    @Test
    fun `returning patient's history reflects every prior encounter, most recent first`() = runTest(mainDispatcherRule.dispatcher) {
        val history = listOf(
            ConsultationHistoryEntry(
                encounterId = "enc-2", visitDate = Instant.EPOCH.plusSeconds(200), chiefComplaint = "Cough",
                caseRecordId = "case-2", caseStatus = CaseStatus.SENT_TO_DOCTOR, followUpOfEncounterId = "enc-1",
                doctorName = null, doctorSpecialty = null,
            ),
            ConsultationHistoryEntry(
                encounterId = "enc-1", visitDate = Instant.EPOCH.plusSeconds(100), chiefComplaint = "Fever",
                caseRecordId = "case-1", caseStatus = CaseStatus.PRESCRIPTION_RECEIVED, followUpOfEncounterId = null,
                doctorName = null, doctorSpecialty = null,
            ),
        )
        val vm = viewModel(history = history)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("enc-2", "enc-1"), state.history.map { it.encounterId })
        // enc-2 follows up enc-1, so both collapse into one chain, represented by the latest (enc-2).
        assertEquals(1, state.chains.size)
        assertEquals("enc-1", state.chains.single().rootEncounterId)
        assertEquals("enc-2", state.chains.single().latest.encounterId)
        assertEquals(2, state.chains.single().visitCount)
        assertFalse(state.isLoadingHistory)
    }

    /** Kernel-mock production safety fix: the doctor's AGREE/MODIFY/REJECT decision point must
     *  surface whether the case's AI assessment was real inference — the physician must not
     *  perform the safety gate blind to a mock/unavailable result. */
    @Test
    fun `opening the doctor review picker surfaces a MOCK_FALLBACK kernel assessment as not-real`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val kernelRepo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK)
        }
        val vm = viewModel(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            kernelReportRepository = kernelRepo,
        )
        advanceUntilIdle()

        vm.onOpenDoctorReviewPicker()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(InferenceSource.MOCK_FALLBACK, state.kernelInferenceSource)
        assertTrue(state.isAssessmentNotReal)
    }

    @Test
    fun `opening the doctor review picker on a real-inference case does not flag it as not-real`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val kernelRepo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE)
        }
        val vm = viewModel(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            kernelReportRepository = kernelRepo,
        )
        advanceUntilIdle()

        vm.onOpenDoctorReviewPicker()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isAssessmentNotReal)
    }

    /** H-14: the doctor's AGREE/MODIFY/REJECT decision point must surface an `/api/v1/evaluate`
     *  failure as an explicit "evaluation failed" state, not a silently missing treatment
     *  section the physician might not notice is absent. */
    @Test
    fun `opening the doctor review picker surfaces a failed evaluate call, not a silent gap`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val evaluateRepo = FakeEvaluateReportRepository().apply { failures["case-1"] = "IOException" }
        val vm = viewModel(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            evaluateReportRepository = evaluateRepo,
        )
        advanceUntilIdle()

        vm.onOpenDoctorReviewPicker()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("IOException", state.evaluateFailureCode)
        assertEquals(null, state.evaluateOutput)
    }

    /** H-16 (Build 1): the decision surface itself must be role-gated, not just the report
     *  render — otherwise gating the render alone is a control that looks like a control and
     *  isn't one (a non-doctor could still commit the decision they're shielded from seeing). */
    @Test
    fun `a non-doctor role cannot open the doctor review picker even when the case is sent to doctor`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val vm = viewModel(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            authSession = FakeAuthSession(UserSession("worker-1", "A Worker", UserRole.ASHA_WORKER)),
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canOpenDoctorReview)
    }

    @Test
    fun `a doctor role can open the doctor review picker when the case is sent to doctor`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val vm = viewModel(caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canOpenDoctorReview)
    }

    /** H-17 (Build 1): REJECT is not confirmable without the free-text reason the worker will
     *  see on the final report ("not blankness"), and requires nothing else — REJECT has no
     *  medication, so a doctor should never have to type placeholder drug/dosage values just to
     *  submit a rejection (PR 36 review). */
    @Test
    fun `REJECT cannot be confirmed without a reject reason, and needs nothing else`() = runTest(mainDispatcherRule.dispatcher) {
        val caseRecord = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = java.time.Instant.EPOCH, updatedAt = java.time.Instant.EPOCH,
        )
        val vm = viewModel(caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)))
        advanceUntilIdle()
        vm.onOpenDoctorReviewPicker()
        advanceUntilIdle()

        vm.onDecisionSelected(com.example.samdapp.domain.model.PhysicianDecision.REJECT)
        assertFalse(vm.uiState.value.canConfirmDecision)

        // No manual drug name or dosage entered — REJECT has no medication, only the reason.
        vm.onRejectReasonChange("Vitals inconsistent with the AI candidate; refer for specialist review.")
        assertTrue(vm.uiState.value.canConfirmDecision)
    }
}
