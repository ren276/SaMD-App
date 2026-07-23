package com.example.samdapp.domain.model

import java.time.Instant

/**
 * Domain result of the real `/api/v1/evaluate` NLEM-treatment kernel — decoupled from
 * [com.example.samdapp.data.remote.dto.EvaluateReportDto] (Clean Architecture boundary, same
 * pattern as [KernelReportOutput] / [com.example.samdapp.domain.kernel.KernelAssessmentResult]).
 * Prefixed `Evaluate*` throughout this file to avoid any ambiguity with the unrelated, pre-existing
 * `Kernel*` domain models (old `/v1/assess` flow) and the `*Dto` data-layer types.
 */
data class EvaluateReportOutput(
    val id: String,
    val caseRecordId: String,
    val diagnosticSummary: EvaluateDiagnosticSummary,
    val nlemTreatment: EvaluateNlemTreatment,
    val brandMapping: EvaluateBrandMapping?,
    val safetyAndTriage: EvaluateSafetyAndTriage,
    /** Best-effort, AI-suggested top India-manufactured brand for [EvaluateNlemTreatment.recommendedDrug]
     *  (looked up via Gemini, see [com.example.samdapp.domain.kernel.BrandLookupSource]) — null when
     *  there's no drug to look up or the lookup failed/is unconfigured. Never blocks the rest of the
     *  evaluate pipeline. */
    val topIndianBrand: IndianBrandSuggestion?,
    val inferenceStartedAt: Instant,
    val inferenceEndedAt: Instant,
)

data class EvaluateDiagnosticSummary(
    val primaryIcdCandidate: String?,
    val primaryAilmentName: String?,
    val differential: List<EvaluateRankedCandidate>,
)

data class EvaluateRankedCandidate(
    val icdCandidate: String,
    val adjustedConfidence: Double,
    val originalSymptomConfidence: Double,
    val vitalsTierAlignment: Double,
    val why: String,
)

data class EvaluateCitation(
    val source: String?,
    val page: Int?,
    val section: String?,
    val subsection: String?,
    val itemNum: String?,
)

data class EvaluateMatchedDisease(
    val icdCandidate: String,
    val diseaseName: String,
)

data class EvaluateNlemTreatment(
    val recommendedDrug: String?,
    val levelOfHealthcare: List<String>?,
    val availableAtPHC: Boolean?,
    val dosageForms: List<String>,
    val pediatricDose: String?,
    val citation: EvaluateCitation?,
    val confidence: String?,
    val referralReason: String?,
    val matchedDisease: EvaluateMatchedDisease?,
)

data class EvaluateBrandMapping(
    val genericName: String,
    val janAushadhiBrand: String?,
    val commercialBrands: List<String>,
    val brandMappingAvailable: Boolean,
)

data class EvaluateVitalsTriage(
    val bpGrade: String,
    val pulse: String,
    val respiratoryRate: String,
    val spo2: String,
    val temperature: String,
    val bmi: String,
    val glucose: String?,
    val overallUrgency: String,
)

data class EvaluateSafetyAndTriage(
    val vitalsTriage: EvaluateVitalsTriage?,
    val requiresHumanReview: Boolean,
    val pediatricReferralFlag: Boolean,
    val failureReason: String?,
)

/** Brand name + manufacturer, from [com.example.samdapp.domain.kernel.BrandLookupSource]. */
data class IndianBrandSuggestion(val brandName: String, val companyName: String) {
    val displayName: String get() = "$brandName ($companyName)"
}
