package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.DifferentialDto
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stub [KernelApiService] returning a fixed response envelope, so [RetrofitKernelSource] can be
 *  exercised directly against a chosen `differential_diagnosis` shape without a real Retrofit
 *  client. */
private class FixedKernelApiService(private val response: KernelAssessmentResponseDto) : KernelApiService {
    override suspend fun assess(request: KernelAssessmentRequestDto): ApiEnvelopeDto<KernelAssessmentResponseDto> =
        ApiEnvelopeDto(success = true, data = response, meta = null)
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

        assertNull(result.predictedCondition)
        assertEquals(0.0, result.confidenceScore, 0.0)
    }

    @Test
    fun `absent-null differential_diagnosis key yields the same null predictedCondition as an empty list`() = runTest {
        val source = RetrofitKernelSource(FixedKernelApiService(response(differentialDiagnosis = null)))

        val result = source.assess(payload(), patientAge = 30, patientSex = "U")

        assertNull(result.predictedCondition)
        assertEquals(0.0, result.confidenceScore, 0.0)
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

        assertEquals("Viral fever", result.predictedCondition)
        assertEquals(0.82, result.confidenceScore, 0.0)
    }
}
