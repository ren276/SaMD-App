package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.testKernelReportOutput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Stub RemoteKernelSource that always throws IOException — simulates the ML server being offline. */
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

/**
 * REQ-HAN-07/kernel-mock production safety fix: this use case now only orchestrates real API →
 * fallback source → honest-unavailable. The dev-only mock scenario table itself is tested against
 * the real `MockKernelFallbackSource` class in `src/testDev/.../MockKernelFallbackSourceTest.kt` —
 * that class doesn't exist outside the dev flavor, so it can't be referenced from this
 * flavor-independent test source set.
 */
class GenerateKernelReportUseCaseTest {

    private fun payload(chiefComplaint: String = "fever") = KernelPayload(
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
    fun `successful real API call stamps REAL_INFERENCE and never calls the fallback source`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK))
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), WorkingKernelSource(), fallback)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.REAL_INFERENCE, output.inferenceSource)
        assertEquals(0, fallback.callCount)
        assertEquals(output, repo.saved["case-1"])
    }

    @Test
    fun `real API failure with a fallback source available returns exactly what the fallback returns`() = runTest {
        val repo = FakeKernelReportRepository()
        val mockResult = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK, predictedCondition = "Dev mock scenario")
        val fallback = FakeKernelFallbackSource(result = mockResult)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(mockResult, output)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `real API failure with no fallback source available (staging-prod shape) yields an honest UNAVAILABLE result, never a fabricated one`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = null) // mirrors NoFallbackKernelSource
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertEquals("Assessment unavailable", output.predictedCondition)
        assertTrue(output.requiredHumanVerification)
        assertEquals(1, fallback.callCount)
        assertEquals(output, repo.saved["case-1"])
    }

    @Test
    fun `confidence always lands in 0-1 and drives requiredHumanVerification at the 90 percent threshold on the real path`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = null)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), WorkingKernelSource(), fallback)

        val output = useCase("case-1", payload()).getOrThrow()

        assertTrue(output.confidenceScore in 0.0..1.0)
        assertEquals(
            output.confidenceScore < GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
            output.requiredHumanVerification,
        )
    }

    @Test
    fun `save failure surfaces as a failed Result`() = runTest {
        val repo = FakeKernelReportRepository().apply { saveResult = Result.failure(RuntimeException("db error")) }
        val fallback = FakeKernelFallbackSource(result = null)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback)

        val result = useCase("case-1", payload())

        assertTrue(result.isFailure)
    }
}
