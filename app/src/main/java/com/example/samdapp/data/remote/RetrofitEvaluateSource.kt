package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.ClinicalApiService
import com.example.samdapp.data.remote.dto.EvaluateErrorDto
import com.example.samdapp.data.remote.dto.EvaluateReportDto
import com.example.samdapp.data.remote.dto.EvaluateRequestDto
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.EvaluateResult
import com.example.samdapp.domain.model.EvaluateBrandMapping
import com.example.samdapp.domain.model.EvaluateCitation
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateMatchedDisease
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateRankedCandidate
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.EvaluateVitalsTriage
import com.example.samdapp.domain.model.KernelPayload
import com.google.gson.Gson
import java.io.IOException
import javax.inject.Inject

/**
 * Retrofit-backed implementation of [EvaluateKernelSource]. Talks to the FastAPI backend at the
 * LAN IP configured in [com.example.samdapp.di.NetworkModule] via `POST /api/v1/evaluate`.
 *
 * On a non-2xx response, [ClinicalApiService.evaluate]'s `Response<EvaluateReportDto>` return type
 * lets this class inspect the error body (shaped as [EvaluateErrorDto], NOT [EvaluateReportDto] —
 * see that class's KDoc) rather than losing it to a bare `HttpException`.
 */
class RetrofitEvaluateSource @Inject constructor(
    private val clinicalApiService: ClinicalApiService,
) : EvaluateKernelSource {

    private val gson = Gson()

    override suspend fun evaluate(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): EvaluateResult {
        val vitals = payload.vitals

        // Same BMI-from-vitals heuristic as RetrofitKernelSource; default 22.0 (normal) when absent.
        val bmi = if (vitals.weightKg != null && vitals.heightCm != null && vitals.heightCm > 0) {
            val hm = vitals.heightCm / 100.0
            (vitals.weightKg / (hm * hm) * 10.0).let { kotlin.math.round(it) / 10.0 }
        } else {
            22.0
        }

        val symptomString = listOfNotNull(
            payload.chiefComplaint.takeIf { it.isNotBlank() },
            payload.transcription?.takeIf { it.isNotBlank() },
        ).joinToString(". ")

        val request = EvaluateRequestDto(
            caseToken = payload.caseToken,
            symptomString = symptomString,
            age = patientAge,
            // Backend checks `sex.upper() == "M"` exactly (app.py) — Patient.biologicalSex is a
            // full word ("Male"/"Female"/"Other"), which never equals "M" and silently mis-codes
            // every male patient as female in Classifier A's risk-tier input. Normalize to the
            // single letter the backend actually expects.
            sex = patientSex.take(1).uppercase(),
            systolicBp = vitals.bpSystolic?.toDouble() ?: 120.0,
            diastolicBp = vitals.bpDiastolic?.toDouble() ?: 80.0,
            bmi = bmi,
            heartRate = vitals.pulseBpm?.toDouble() ?: 72.0,
            // Genuinely optional on the backend (Optional[float] = None) — pass real nulls
            // instead of fabricating vitals data, unlike the required fields defaulted above.
            randomGlucose = vitals.bloodGlucoseMgDl?.toDouble(),
            spo2 = vitals.spo2Percent?.toDouble() ?: 98.0,
            respiratoryRate = vitals.respiratoryRate?.toDouble(),
            temperature = vitals.temperatureCelsius,
        )

        val response = clinicalApiService.evaluate(request)

        if (!response.isSuccessful) {
            val parsedError = response.errorBody()?.charStream()?.use { reader ->
                runCatching { gson.fromJson(reader, EvaluateErrorDto::class.java) }.getOrNull()
            }
            throw IOException(parsedError?.message ?: "Evaluate endpoint returned HTTP ${response.code()}")
        }

        val body = response.body() ?: throw IOException("Evaluate endpoint returned an empty body")
        return body.toEvaluateResult()
    }
}

private fun EvaluateReportDto.toEvaluateResult(): EvaluateResult = EvaluateResult(
    diagnosticSummary = diagnosticSummary.toDomain(),
    nlemTreatment = nlemTreatment.toDomain(),
    brandMapping = brandMapping?.toDomain(),
    safetyAndTriage = safetyAndTriage.toDomain(),
)

private fun com.example.samdapp.data.remote.dto.DiagnosticSummaryDto.toDomain(): EvaluateDiagnosticSummary =
    EvaluateDiagnosticSummary(
        primaryIcdCandidate = primaryIcdCandidate,
        primaryAilmentName = primaryAilmentName,
        differential = differential.map { it.toDomain() },
    )

private fun com.example.samdapp.data.remote.dto.RankedCandidateDto.toDomain(): EvaluateRankedCandidate =
    EvaluateRankedCandidate(
        icdCandidate = icdCandidate,
        adjustedConfidence = adjustedConfidence,
        originalSymptomConfidence = originalSymptomConfidence,
        vitalsTierAlignment = vitalsTierAlignment,
        why = why,
    )

private fun com.example.samdapp.data.remote.dto.CitationDto.toDomain(): EvaluateCitation =
    EvaluateCitation(source = source, page = page, section = section, subsection = subsection, itemNum = itemNum)

private fun com.example.samdapp.data.remote.dto.MatchedDiseaseDto.toDomain(): EvaluateMatchedDisease =
    EvaluateMatchedDisease(icdCandidate = icdCandidate, diseaseName = diseaseName)

private fun com.example.samdapp.data.remote.dto.NlemTreatmentDto.toDomain(): EvaluateNlemTreatment =
    EvaluateNlemTreatment(
        recommendedDrug = recommendedDrug,
        levelOfHealthcare = levelOfHealthcare,
        availableAtPHC = availableAtPHC,
        dosageForms = dosageForms,
        pediatricDose = pediatricDose,
        citation = citation?.toDomain(),
        confidence = confidence,
        referralReason = referralReason,
        matchedDisease = matchedDisease?.toDomain(),
    )

private fun com.example.samdapp.data.remote.dto.BrandMappingDto.toDomain(): EvaluateBrandMapping =
    EvaluateBrandMapping(
        genericName = genericName,
        janAushadhiBrand = janAushadhiBrand,
        commercialBrands = commercialBrands,
        brandMappingAvailable = brandMappingAvailable,
    )

private fun com.example.samdapp.data.remote.dto.VitalsTriageDto.toDomain(): EvaluateVitalsTriage =
    EvaluateVitalsTriage(
        bpGrade = bpGrade,
        pulse = pulse,
        respiratoryRate = respiratoryRate,
        spo2 = spo2,
        temperature = temperature,
        bmi = bmi,
        glucose = glucose,
        overallUrgency = overallUrgency,
    )

private fun com.example.samdapp.data.remote.dto.SafetyAndTriageDto.toDomain(): EvaluateSafetyAndTriage =
    EvaluateSafetyAndTriage(
        vitalsTriage = vitalsTriage?.toDomain(),
        requiresHumanReview = requiresHumanReview,
        pediatricReferralFlag = pediatricReferralFlag,
        failureReason = failureReason,
    )
