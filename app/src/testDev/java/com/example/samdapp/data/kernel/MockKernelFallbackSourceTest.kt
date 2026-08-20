package com.example.samdapp.data.kernel

import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Runs only under `testDevDebugUnitTest` — [MockKernelFallbackSource] doesn't exist in the
 * staging/prod compilation unit, so this test class doesn't either; that's the compile-time half
 * of the proof that no mock kernel scenario is reachable outside dev (see
 * `docs/quality/risk-management-file.md` H-09). This is the keyword-scenario-matching coverage
 * moved out of `GenerateKernelReportUseCaseTest` when the mock table was split into this class.
 */
class MockKernelFallbackSourceTest {

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
    fun `matched keyword scenario returns its curated differentials, tagged MOCK_FALLBACK`() = runTest {
        val source = MockKernelFallbackSource(FakeDeviceInfoProvider())

        val output = source.fallback("case-1", payload("Fever and chills for two days"), Instant.EPOCH, dataQualityScore = 1.0)

        requireNotNull(output)
        assertEquals("Viral fever", output.predictedCondition)
        assertEquals(3, output.differentials.size)
        assertTrue(output.reasoningSummary.isNotBlank())
        assertTrue(output.evidenceFor.isNotEmpty())
        assertEquals(InferenceSource.MOCK_FALLBACK, output.inferenceSource)
    }

    @Test
    fun `unmatched complaint falls back to the default lower-confidence scenario`() = runTest {
        val source = MockKernelFallbackSource(FakeDeviceInfoProvider())

        val output = source.fallback("case-2", payload("Patient feels generally unwell"), Instant.EPOCH, dataQualityScore = 1.0)

        assertEquals("Non-specific presentation", output.predictedCondition)
    }

    @Test
    fun `confidence always lands in 0-1 and drives requiredHumanVerification at the 90 percent threshold`() = runTest {
        val source = MockKernelFallbackSource(FakeDeviceInfoProvider())

        repeat(20) {
            val output = source.fallback("case-$it", payload("cough and cold"), Instant.EPOCH, dataQualityScore = 1.0)
            requireNotNull(output)
            assertTrue(output.confidenceScore in 0.0..1.0)
            assertEquals(
                output.confidenceScore < com.example.samdapp.domain.usecase.GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
                output.requiredHumanVerification,
            )
        }
    }
}
