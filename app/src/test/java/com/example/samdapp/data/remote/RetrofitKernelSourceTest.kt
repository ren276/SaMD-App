package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.DifferentialDto
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.kernel.KernelApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stub [KernelApiService] returning a fixed response envelope, so [RetrofitKernelSource] can be
 *  exercised directly against a chosen `differential_diagnosis` shape without a real Retrofit
 *  client. */
private class FixedKernelApiService(private val response: KernelAssessmentResponseDto) : KernelApiService {
    override suspend fun assess(
        request: KernelAssessmentRequestDto,
    ): Response<ApiEnvelopeDto<KernelAssessmentResponseDto>> =
        Response.success(ApiEnvelopeDto(success = true, data = response, meta = null))
}

/**
 * Empty-differential fabrication fix: proves the absent/null-key case and the present-but-empty-
 * list case both collapse to the same `predictedCondition = null` domain result, rather than the
 * null-key case throwing an NPE at `.firstOrNull()` (which used to land in
 * GenerateKernelReportUseCase's generic catch and be indistinguishable from an unreachable
 * kernel). Also guards against the fabrication itself ever coming back.
 */
class RetrofitKernelSourceTest {

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

    private fun response(differentialDiagnosis: List<DifferentialDto>?) = KernelAssessmentResponseDto(
        caseToken = "case-1",
        safetyScreenPassed = true,
        triageUrgency = "ROUTINE",
        differentialDiagnosis = differentialDiagnosis,
        recommendedInvestigations = emptyList(),
        modelMetadata = null,
    )

    @Test
    fun `empty differential_diagnosis list yields null predictedCondition, not a fabricated one`() = runTest {
        val source = RetrofitKernelSource(FixedKernelApiService(response(differentialDiagnosis = emptyList())))

        val result = source.assess(payload(), patientAge = 30, patientSex = "U")

        val data = (result as KernelApiResult.Success).data
        assertNull(data.predictedCondition)
        assertEquals(0.0, data.confidenceScore, 0.0)
    }

    @Test
    fun `absent-null differential_diagnosis key yields the same null predictedCondition as an empty list`() = runTest {
        val source = RetrofitKernelSource(FixedKernelApiService(response(differentialDiagnosis = null)))

        val result = source.assess(payload(), patientAge = 30, patientSex = "U")

        val data = (result as KernelApiResult.Success).data
        assertNull(data.predictedCondition)
        assertEquals(0.0, data.confidenceScore, 0.0)
    }

    @Test
    fun `a real differential is mapped through unchanged`() = runTest {
        val diff = DifferentialDto(
            conditionTier = "Viral fever",
            probability = 0.82,
            evidenceFor = listOf("fever reported"),
            evidenceAgainst = emptyList(),
        )
        val source = RetrofitKernelSource(FixedKernelApiService(response(differentialDiagnosis = listOf(diff))))

        val result = source.assess(payload(), patientAge = 30, patientSex = "U")

        val data = (result as KernelApiResult.Success).data
        assertEquals("Viral fever", data.predictedCondition)
        assertEquals(0.82, data.confidenceScore, 0.0)
    }

    // ── The problem document, third instance of the house block ──────────────

    private class ErroringKernelApiService(
        private val status: Int,
        private val body: String?,
    ) : KernelApiService {
        override suspend fun assess(
            request: KernelAssessmentRequestDto,
        ): Response<ApiEnvelopeDto<KernelAssessmentResponseDto>> = Response.error(
            status,
            (body ?: "").toResponseBody("application/problem+json".toMediaType()),
        )
    }

    private suspend fun assessError(status: Int, body: String?): KernelApiResult.Failure {
        val source = RetrofitKernelSource(ErroringKernelApiService(status, body))
        val result = source.assess(payload(), patientAge = 30, patientSex = "U")
        assertTrue("expected a Failure, got $result", result is KernelApiResult.Failure)
        return result as KernelApiResult.Failure
    }

    @Test
    fun `a 404 problem document surfaces its SAMD code instead of only the status line`() = runTest {
        // The hop this PR exists to fix. Before it, KernelApiService returned the bare envelope,
        // Retrofit raised an HttpException whose message is "HTTP 404 Not Found", and this code
        // was never read.
        val failure = assessError(
            404,
            """{"type":"about:blank","title":"Not Found","status":404,""" +
                """"detail":"Case record not found.","code":"SAMD-ENC-4002"}""",
        )
        assertEquals("SAMD-ENC-4002", failure.code)
        assertEquals(404, failure.httpStatus)
    }

    @Test
    fun `an absent error body yields a null code, not a crash`() = runTest {
        val failure = assessError(502, body = null)
        assertEquals(null, failure.code)
        assertEquals(502, failure.httpStatus)
    }

    @Test
    fun `a body that is not a problem document yields a null code, not a crash`() = runTest {
        // Valid JSON, wrong shape. Gson fills every ProblemDetailDto field with null rather than
        // throwing, which is the same observable outcome as no body at all and is why this and
        // the test above assert the same thing: no contractual code was supplied.
        val failure = assessError(500, body = """{"oops":1}""")
        assertEquals(null, failure.code)
    }

    @Test
    fun `a body that is not JSON at all yields a null code, not a crash`() = runTest {
        // runCatching around the Gson call is what makes this a null rather than a thrown
        // JsonSyntaxException escaping into the caller. Same guard both other parsers have.
        val failure = assessError(500, body = "<html>502 Bad Gateway</html>")
        assertEquals(null, failure.code)
    }

    @Test
    fun `a code this build does not know is carried through unchanged for the classifier`() = runTest {
        // RetrofitKernelSource does not judge the code; classifyKernelFailure does. Surfacing an
        // unrecognised code verbatim is what lets the classifier fall back to the status class.
        val failure = assessError(503, """{"code":"SAMD-FUTURE-9999","status":503}""")
        assertEquals("SAMD-FUTURE-9999", failure.code)
        assertEquals(503, failure.httpStatus)
    }

    @Test
    fun `an IOException becomes Unreachable rather than escaping`() = runTest {
        val throwing = object : KernelApiService {
            override suspend fun assess(
                request: KernelAssessmentRequestDto,
            ): Response<ApiEnvelopeDto<KernelAssessmentResponseDto>> =
                throw java.net.SocketTimeoutException("read timed out")
        }
        val result = RetrofitKernelSource(throwing).assess(payload(), patientAge = 30, patientSex = "U")
        assertTrue(result is KernelApiResult.Unreachable)
        assertTrue((result as KernelApiResult.Unreachable).cause is java.net.SocketTimeoutException)
    }
}
