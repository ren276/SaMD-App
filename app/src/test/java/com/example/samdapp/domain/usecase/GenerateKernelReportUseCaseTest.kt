package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.testKernelReportOutput
import kotlinx.coroutines.CancellationException
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

/** Stub RemoteKernelSource that always throws CancellationException — simulates the calling
 *  coroutine (e.g. the screen) being cancelled mid-assess. */
private class CancellingKernelSource : RemoteKernelSource {
    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult {
        throw CancellationException("Simulated coroutine cancellation")
    }
}

/** Stub RemoteKernelSource simulating a 200 response with an empty differential_diagnosis (or,
 *  equivalently after the empty-200 fabrication fix, an absent/null key — RetrofitKernelSource
 *  collapses both to the same null [KernelAssessmentResult.predictedCondition] before this use
 *  case ever sees the result; see RetrofitKernelSourceTest for that collapse). The kernel was
 *  reached and ran; it just produced nothing usable. */
private class EmptyDifferentialKernelSource : RemoteKernelSource {
    override suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult = KernelAssessmentResult(
        predictedCondition = null,
        confidenceScore = 0.0,
        triageUrgency = "ROUTINE",
        safetyScreenPassed = true,
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        differentials = emptyList(),
        recommendedInvestigations = emptyList(),
        modelVersion = "xgboost-v1",
    )
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
        val audit = FakeAuditLogger()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), WorkingKernelSource(), fallback, audit)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.REAL_INFERENCE, output.inferenceSource)
        assertEquals(0, fallback.callCount)
        assertEquals(output, repo.saved["case-1"])
        assertTrue(audit.logged.none { it.action == AuditAction.KERNEL_EMPTY_DIFFERENTIAL.value })
    }

    @Test
    fun `real API failure with a fallback source available returns exactly what the fallback returns`() = runTest {
        val repo = FakeKernelReportRepository()
        val mockResult = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK, predictedCondition = "Dev mock scenario")
        val fallback = FakeKernelFallbackSource(result = mockResult)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback, FakeAuditLogger())

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(mockResult, output)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `real API failure with no fallback source available (staging-prod shape) yields an honest UNAVAILABLE result, never a fabricated one`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = null) // mirrors NoFallbackKernelSource
        val audit = FakeAuditLogger()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback, audit)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertEquals("Assessment unavailable", output.predictedCondition)
        assertTrue(output.requiredHumanVerification)
        assertEquals(1, fallback.callCount)
        assertEquals(output, repo.saved["case-1"])
        // Genuinely-unreachable is distinguished from an empty-200 by NOT emitting the
        // KERNEL_EMPTY_DIFFERENTIAL breadcrumb: this branch never runs because the exception is
        // caught before the empty-differential check inside tryRealApi is ever reached.
        assertTrue(audit.logged.none { it.action == AuditAction.KERNEL_EMPTY_DIFFERENTIAL.value })
    }

    @Test
    fun `confidence always lands in 0-1 and drives requiredHumanVerification at the 90 percent threshold on the real path`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = null)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), WorkingKernelSource(), fallback, FakeAuditLogger())

        val output = useCase("case-1", payload()).getOrThrow()

        assertTrue(output.confidenceScore in 0.0..1.0)
        assertEquals(
            output.confidenceScore < GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
            output.requiredHumanVerification,
        )
    }

    @Test
    fun `cancellation during the real call is rethrown, never treated as a fallback trigger`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK))
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), CancellingKernelSource(), fallback, FakeAuditLogger())

        try {
            useCase("case-1", payload())
            org.junit.Assert.fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(0, fallback.callCount)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `empty-differential 200 routes to UNAVAILABLE, never calls the fallback source, and emits exactly one breadcrumb with no fabricated condition or confidence`() = runTest {
        val repo = FakeKernelReportRepository()
        val fallback = FakeKernelFallbackSource(result = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK))
        val audit = FakeAuditLogger()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), EmptyDifferentialKernelSource(), fallback, audit)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertEquals("Assessment unavailable", output.predictedCondition)
        assertEquals(0.0, output.confidenceScore, 0.0)
        assertTrue(output.requiredHumanVerification)
        // The empty-differential branch must never consult the fallback source — a dev build
        // could otherwise substitute a mock scenario for a reached-but-empty kernel and mislabel
        // it MOCK_FALLBACK instead of UNAVAILABLE.
        assertEquals(0, fallback.callCount)
        assertEquals(output, repo.saved["case-1"])

        val breadcrumbs = audit.logged.filter { it.action == AuditAction.KERNEL_EMPTY_DIFFERENTIAL.value }
        assertEquals(1, breadcrumbs.size)
        val payloadJson = breadcrumbs.single().payload
        // No fabricated clinical value in the audit trail: no condition string, no confidence.
        assertTrue(!payloadJson.contains("Non-specific"))
        assertTrue(!payloadJson.contains("confidence"))
        assertTrue(!payloadJson.contains("predictedCondition"))
        assertTrue(payloadJson.contains("\"differentialCount\":\"0\""))
    }

    @Test
    fun `recordUnavailable writes an honest UNAVAILABLE row with no payload to score`() = runTest {
        val repo = FakeKernelReportRepository()
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), FakeKernelFallbackSource(result = null), FakeAuditLogger())

        useCase.recordUnavailable("case-1")

        val saved = repo.saved["case-1"]
        requireNotNull(saved)
        assertEquals(InferenceSource.UNAVAILABLE, saved.inferenceSource)
        assertEquals("Assessment unavailable", saved.predictedCondition)
        assertEquals(0.0, saved.dataQualityScore ?: -1.0, 0.0)
    }

    @Test
    fun `save failure surfaces as a failed Result`() = runTest {
        val repo = FakeKernelReportRepository().apply { saveResult = Result.failure(RuntimeException("db error")) }
        val fallback = FakeKernelFallbackSource(result = null)
        val useCase = GenerateKernelReportUseCase(repo, FakeDeviceInfoProvider(), OfflineKernelSource(), fallback, FakeAuditLogger())

        val result = useCase("case-1", payload())

        assertTrue(result.isFailure)
    }
}
