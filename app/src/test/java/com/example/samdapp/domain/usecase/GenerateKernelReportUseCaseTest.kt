package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeKernelReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Stub RemoteKernelSource that always throws IOException — simulates the ML server being offline.
 * All tests verify the graceful fallback to mock inference (Phase 4 behaviour unchanged).
 */
private class OfflineKernelSource : RemoteKernelSource {
    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult {
        throw IOException("Simulated network unavailability")
    }
}

/** Stub RemoteKernelSource that always succeeds — simulates the ML server being reachable, to
 *  exercise the REAL_INFERENCE stamping path. */
private class WorkingKernelSource : RemoteKernelSource {
    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult = KernelAssessmentResult(
        predictedCondition = "Viral fever",
        confidenceScore = 0.82,
        triageUrgency = "ROUTINE",
        safetyScreenPassed = true,
        evidenceFor = listOf("fever reported"),
        evidenceAgainst = emptyList(),
        differentials = listOf("Dengue", "Typhoid"),
        recommendedInvestigations = emptyList(),
        modelVersion = "xgboost-v1",
    )
}

/** REQ-HAN-07: fuller kernel response — differentials, reasoning, confidence-driven verification flag. */
class GenerateKernelReportUseCaseTest {

    private fun payload(chiefComplaint: String) = KernelPayload(
        caseToken = "case-1",
        vitals = VitalsReading(),
        chiefComplaint = chiefComplaint,
        durationBucket = "few_days",
        severityScore = 5,
        relevantHistory = null,
        transcription = null,
        attachments = emptyList<Attachment>(),
    )

    @Test
    fun `matched keyword scenario returns its curated differentials and persists via the repository`() = runTest {
        val repo = FakeKernelReportRepository()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource())

        val result = useCase("case-1", payload("Fever and chills for two days"))

        val output = result.getOrThrow()
        assertEquals("Viral fever", output.predictedCondition)
        assertEquals(3, output.differentials.size)
        assertTrue(output.reasoningSummary.isNotBlank())
        assertTrue(output.evidenceFor.isNotEmpty())
        assertEquals(output, repo.saved["case-1"])
        assertEquals(InferenceSource.MOCK_FALLBACK, output.inferenceSource)
    }

    @Test
    fun `successful real API call stamps REAL_INFERENCE`() = runTest {
        val repo = FakeKernelReportRepository()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), WorkingKernelSource())

        val output = useCase("case-1", payload("fever")).getOrThrow()

        assertEquals(InferenceSource.REAL_INFERENCE, output.inferenceSource)
    }

    @Test
    fun `unmatched complaint falls back to the default lower-confidence scenario`() = runTest {
        val repo = FakeKernelReportRepository()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource())

        val output = useCase("case-2", payload("Patient feels generally unwell")).getOrThrow()

        assertEquals("Non-specific presentation", output.predictedCondition)
    }

    @Test
    fun `confidence always lands in 0-1 and drives requiredHumanVerification at the 90 percent threshold`() = runTest {
        val repo = FakeKernelReportRepository()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource())

        repeat(20) { i ->
            val output = useCase("case-$i", payload("cough and cold")).getOrThrow()
            assertTrue(output.confidenceScore in 0.0..1.0)
            assertEquals(
                output.confidenceScore < GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
                output.requiredHumanVerification,
            )
        }
    }

    @Test
    fun `save failure surfaces as a failed Result`() = runTest {
        val repo = FakeKernelReportRepository().apply { saveResult = Result.failure(RuntimeException("db error")) }
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource())

        val result = useCase("case-1", payload("fever"))

        assertTrue(result.isFailure)
    }
}
