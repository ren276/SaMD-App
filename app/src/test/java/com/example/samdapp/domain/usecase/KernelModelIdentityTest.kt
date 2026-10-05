package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.kernel.KernelApiResult
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.KernelTriageRules
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.testKernelReportOutput
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model identity on a kernel report (PR 4, R2 and R3, Q3): the version sentinels are gone, the
 * rule version is stamped on real rows only, and the backend's `X-Request-ID` is kept on every
 * `inferenceSource` that had a response to read it from.
 */
class KernelModelIdentityTest {

    private val validId = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"

    private fun payload() = KernelPayload(
        caseToken = "case-1",
        vitals = VitalsReading(),
        chiefComplaint = "fever",
        durationBucket = "few_days",
        severityScore = 5,
        relevantHistory = null,
        transcription = null,
        attachments = emptyList(),
    )

    private fun result(
        predictedCondition: String? = "low_risk",
        modelVersion: String? = "toy-v0.6",
        requestId: String? = validId,
        requestIdUnrecognised: Boolean = false,
        modelCalibrated: Boolean? = false,
    ) = KernelAssessmentResult(
        predictedCondition = predictedCondition,
        confidenceScore = if (predictedCondition == null) 0.0 else 0.82,
        triageUrgency = "ROUTINE",
        safetyScreenPassed = true,
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        differentials = emptyList(),
        recommendedInvestigations = emptyList(),
        modelVersion = modelVersion,
        modelCalibrated = modelCalibrated,
        requestId = requestId,
        requestIdUnrecognised = requestIdUnrecognised,
    )

    private class Source(private val outcome: KernelApiResult<KernelAssessmentResult>) : RemoteKernelSource {
        override suspend fun assess(
            payload: KernelPayload,
            patientAge: Int,
            patientSex: String,
        ): KernelApiResult<KernelAssessmentResult> = outcome
    }

    private class Harness(outcome: KernelApiResult<KernelAssessmentResult>, fallbackOutput: com.example.samdapp.domain.model.KernelReportOutput? = null) {
        val repo = FakeKernelReportRepository()
        val audit = FakeAuditLogger()
        val useCase = GenerateKernelReportUseCase(
            repo, FakeDeviceInfoProvider(), Source(outcome), FakeKernelFallbackSource(result = fallbackOutput), audit,
        )

        fun unrecognised() = audit.logged.filter { it.action == AuditAction.KERNEL_UNRECOGNISED_OUTPUT.value }
    }

    // ── R2: the sentinels ────────────────────────────────────────────────────────

    @Test
    fun `a real result with a model version stores it and records no breadcrumb`() = runTest {
        val h = Harness(KernelApiResult.Success(result(modelVersion = "toy-v0.6-observed-glucose-4tier")))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals("toy-v0.6-observed-glucose-4tier", output.modelVersion)
        assertTrue(h.unrecognised().isEmpty())
    }

    @Test
    fun `a missing model version is null, never remote-kernel, with exactly one event that names the field only`() = runTest {
        val h = Harness(KernelApiResult.Success(result(modelVersion = null)))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertNull(output.modelVersion)
        assertEquals(InferenceSource.REAL_INFERENCE, output.inferenceSource)
        val event = h.unrecognised().single()
        val payloadJson = JsonParser.parseString(event.payload).asJsonObject
        assertEquals("model_version", payloadJson.get("field").asString)
        assertEquals("the event carries the field name and nothing else", setOf("field"), payloadJson.keySet())
        assertEquals(output, h.repo.saved["case-1"])
    }

    @Test
    fun `a malformed model version is stored as null and its raw value never reaches the event`() = runTest {
        val h = Harness(KernelApiResult.Success(result(modelVersion = "bad version; DROP")))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertNull(output.modelVersion)
        val event = h.unrecognised().single()
        assertFalse(event.payload.contains("DROP"))
    }

    @Test
    fun `an UNAVAILABLE row has a null model version, not the unavailable sentinel`() = runTest {
        val h = Harness(KernelApiResult.Unreachable(java.io.IOException("offline")))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertNull(output.modelVersion)
        assertNull(h.repo.saved["case-1"]!!.modelVersion)
    }

    @Test
    fun `recordUnavailable also writes a null model version`() = runTest {
        val h = Harness(KernelApiResult.Unreachable(java.io.IOException("offline")))

        h.useCase.recordUnavailable("case-1")

        assertNull(h.repo.saved["case-1"]!!.modelVersion)
    }

    // ── R3: the derivation rule version ──────────────────────────────────────────

    @Test
    fun `a REAL row carries the device rule version and the calibrated flag`() = runTest {
        val h = Harness(KernelApiResult.Success(result(modelCalibrated = true)))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals(KernelTriageRules.DERIVATION_RULE_VERSION, output.derivationRuleVersion)
        assertEquals(true, output.modelCalibrated)
    }

    @Test
    fun `a MOCK_FALLBACK row carries no rule version and an UNAVAILABLE row carries none either`() = runTest {
        val mock = Harness(
            KernelApiResult.Unreachable(java.io.IOException("offline")),
            fallbackOutput = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK),
        )
        val mockOutput = mock.useCase("case-1", payload()).getOrThrow()
        assertEquals(InferenceSource.MOCK_FALLBACK, mockOutput.inferenceSource)
        assertNull(mockOutput.derivationRuleVersion)
        assertNull(mockOutput.modelCalibrated)

        val unavailable = Harness(KernelApiResult.Unreachable(java.io.IOException("offline")))
        val unavailableOutput = unavailable.useCase("case-1", payload()).getOrThrow()
        assertNull(unavailableOutput.derivationRuleVersion)
        assertNull(unavailableOutput.modelCalibrated)
    }

    // ── Q3: request_id on any inferenceSource ────────────────────────────────────

    @Test
    fun `a REAL row stores the request id`() = runTest {
        val h = Harness(KernelApiResult.Success(result()))

        assertEquals(validId, h.useCase("case-1", payload()).getOrThrow().requestId)
    }

    @Test
    fun `a MOCK_FALLBACK result with a valid header stores the request id`() = runTest {
        val h = Harness(
            KernelApiResult.Failure(code = "SAMD-KERN-5001", httpStatus = 502, message = "m", requestId = validId),
            fallbackOutput = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK),
        )

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.MOCK_FALLBACK, output.inferenceSource)
        assertEquals(validId, output.requestId)
        assertEquals(validId, h.repo.saved["case-1"]!!.requestId)
    }

    @Test
    fun `an UNAVAILABLE result after a failed call with a valid header stores the request id`() = runTest {
        val h = Harness(
            KernelApiResult.Failure(code = "SAMD-KERN-5001", httpStatus = 502, message = "m", requestId = validId),
        )

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertEquals(validId, output.requestId)
        assertEquals(validId, h.repo.saved["case-1"]!!.requestId)
    }

    @Test
    fun `an UNAVAILABLE result from an empty differential with a valid header stores the request id`() = runTest {
        val h = Harness(KernelApiResult.Success(result(predictedCondition = null)))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
        assertEquals(validId, output.requestId)
        assertNull(output.derivationRuleVersion)
    }

    @Test
    fun `an unreachable server has no response so no request id and no breadcrumb`() = runTest {
        val h = Harness(KernelApiResult.Unreachable(java.io.IOException("offline")))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertNull(output.requestId)
        assertTrue(h.unrecognised().isEmpty())
    }

    @Test
    fun `a missing or malformed header stores null and records a request_id event with no raw value`() = runTest {
        val h = Harness(KernelApiResult.Success(result(requestId = null, requestIdUnrecognised = true)))

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertNull(output.requestId)
        val event = h.unrecognised().single()
        val payloadJson = JsonParser.parseString(event.payload).asJsonObject
        assertEquals("request_id", payloadJson.get("field").asString)
        assertEquals(setOf("field"), payloadJson.keySet())
    }

    @Test
    fun `a failed call whose header was malformed records the event and stores null on the fallback row`() = runTest {
        val h = Harness(
            KernelApiResult.Failure(
                code = null, httpStatus = 502, message = "m", requestId = null, requestIdUnrecognised = true,
            ),
            fallbackOutput = testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK),
        )

        val output = h.useCase("case-1", payload()).getOrThrow()

        assertNull(output.requestId)
        assertEquals("request_id", JsonParser.parseString(h.unrecognised().single().payload).asJsonObject.get("field").asString)
    }
}
