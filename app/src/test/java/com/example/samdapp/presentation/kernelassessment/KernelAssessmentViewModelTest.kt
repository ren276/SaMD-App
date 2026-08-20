package com.example.samdapp.presentation.kernelassessment

import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import com.example.samdapp.domain.usecase.RetryKernelAssessmentUseCase
import com.example.samdapp.domain.usecase.SendToKernelUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testConsultation
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

/** Kernel-mock production safety fix: the UNAVAILABLE state (staging/prod, real `/assess`
 *  failure, no fallback bound) must be distinguishable at the display layer, and its retry
 *  affordance must actually re-run the assessment. */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelAssessmentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private object AlwaysFailsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            throw java.io.IOException("unreachable")
    }

    private object AlwaysSucceedsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            KernelAssessmentResult(
                predictedCondition = "Viral fever", confidenceScore = 0.9, triageUrgency = "ROUTINE",
                safetyScreenPassed = true, evidenceFor = emptyList(), evidenceAgainst = emptyList(),
                differentials = emptyList(), recommendedInvestigations = emptyList(), modelVersion = "v1",
            )
    }

    private fun viewModel(
        caseRecordId: String,
        kernelReportRepository: FakeKernelReportRepository,
        retryKernelSource: RemoteKernelSource = AlwaysFailsKernelSource,
        retryFallback: FakeKernelFallbackSource = FakeKernelFallbackSource(result = null),
    ): KernelAssessmentViewModel {
        val encounter = Encounter(
            id = "enc-1", patientId = "p1", startedAt = Instant.EPOCH,
            createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = null,
        )
        val caseRecord = CaseRecord(
            id = caseRecordId, patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val patientRepo = FakePatientRepository().apply { registered = testPatient("p1") }
        val retryUseCase = RetryKernelAssessmentUseCase(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            vitalsRepository = FakeVitalsRepository(),
            consultationRepository = FakeConsultationRepository(byEncounter = mapOf("enc-1" to testConsultation("enc-1"))),
            encounterRepository = FakeEncounterRepository(initialEncounters = listOf(encounter)),
            patientRepository = patientRepo,
            sendToKernelUseCase = SendToKernelUseCase(),
            generateKernelReportUseCase = GenerateKernelReportUseCase(
                FakeKernelReportRepository(), FakeDeviceInfoProvider(), retryKernelSource, retryFallback,
            ),
        )
        return KernelAssessmentViewModel(
            caseRecordId = caseRecordId,
            evaluateReportRepository = FakeEvaluateReportRepository(),
            kernelReportRepository = kernelReportRepository,
            retryKernelAssessmentUseCase = retryUseCase,
            auditLogger = FakeAuditLogger(),
        )
    }

    @Test
    fun `UNAVAILABLE kernel result maps to isUnavailable true and a distinguishing source label`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE, predictedCondition = "Assessment unavailable", requiredHumanVerification = true)
        }
        val vm = viewModel("case-1", repo)

        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertTrue(display.isUnavailable)
        assertFalse(display.isMockFallback)
        assertTrue(display.sourceLabel.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `REAL_INFERENCE kernel result is not flagged unavailable or mock`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE)
        }
        val vm = viewModel("case-1", repo)

        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(display.isUnavailable)
        assertFalse(display.isMockFallback)
    }

    @Test
    fun `retry that succeeds updates the display to REAL_INFERENCE and clears isUnavailable`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
        }
        val vm = viewModel("case-1", repo, retryKernelSource = AlwaysSucceedsKernelSource)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.display!!.isUnavailable)

        vm.onRetry()
        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(vm.uiState.value.isRetrying)
        assertFalse(display.isUnavailable)
        assertEquals("Viral fever", display.predictedCondition)
    }

    @Test
    fun `retry that fails again stays honestly unavailable, not a silently kept-stale display`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
        }
        val vm = viewModel("case-1", repo, retryKernelSource = AlwaysFailsKernelSource)
        advanceUntilIdle()

        vm.onRetry()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.display!!.isUnavailable)
        assertFalse(vm.uiState.value.isRetrying)
    }
}
