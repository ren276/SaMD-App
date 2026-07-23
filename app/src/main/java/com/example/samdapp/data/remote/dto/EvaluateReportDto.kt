package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Response DTO tree for `POST /api/v1/evaluate`. Deliberately named `EvaluateReportDto`
 * (not `KernelReportOutput`) to avoid collision with the unrelated, pre-existing domain
 * model [com.example.samdapp.domain.model.KernelReportOutput], which belongs to the OLD
 * `/v1/assess` flow and has a completely different flat shape.
 *
 * WIRE-FORMAT WARNING: fields mix snake_case and camelCase inconsistently WITHIN the same
 * class (verified against api_schemas.py, not a typo) — e.g. [NlemTreatmentDto] has
 * `recommendedDrug`/`dosageForms` (camelCase) next to `citation`/`confidence`, and
 * [SafetyAndTriageDto] has `requiresHumanReview` (camelCase) next to `pediatric_referral_flag`
 * (snake_case). Every field below has an explicit @SerializedName; do not replace with a
 * FieldNamingPolicy.
 */
data class EvaluateReportDto(
    @SerializedName("diagnostic_summary") val diagnosticSummary: DiagnosticSummaryDto,
    @SerializedName("nlem_treatment") val nlemTreatment: NlemTreatmentDto,
    @SerializedName("brand_mapping") val brandMapping: BrandMappingDto? = null,
    @SerializedName("safety_and_triage") val safetyAndTriage: SafetyAndTriageDto,
)

data class DiagnosticSummaryDto(
    @SerializedName("primary_icd_candidate") val primaryIcdCandidate: String? = null,
    @SerializedName("primary_ailment_name") val primaryAilmentName: String? = null,
    @SerializedName("differential") val differential: List<RankedCandidateDto> = emptyList(),
)

data class RankedCandidateDto(
    @SerializedName("icd_candidate") val icdCandidate: String,
    @SerializedName("adjusted_confidence") val adjustedConfidence: Double,
    @SerializedName("original_symptom_confidence") val originalSymptomConfidence: Double,
    @SerializedName("vitals_tier_alignment") val vitalsTierAlignment: Double,
    @SerializedName("why") val why: String,
)

data class CitationDto(
    @SerializedName("source") val source: String? = null,
    @SerializedName("page") val page: Int? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("subsection") val subsection: String? = null,
    @SerializedName("item_num") val itemNum: String? = null,
)

data class MatchedDiseaseDto(
    @SerializedName("icd_candidate") val icdCandidate: String,
    @SerializedName("disease_name") val diseaseName: String,
)

/** Note: `levelOfHealthcare` defaults to `null` in the Python model (not `[]`) — kept nullable, not emptyList(). */
data class NlemTreatmentDto(
    @SerializedName("recommendedDrug") val recommendedDrug: String? = null,
    @SerializedName("levelOfHealthcare") val levelOfHealthcare: List<String>? = null,
    @SerializedName("availableAtPHC") val availableAtPHC: Boolean? = null,
    @SerializedName("dosageForms") val dosageForms: List<String> = emptyList(),
    @SerializedName("pediatricDose") val pediatricDose: String? = null,
    @SerializedName("citation") val citation: CitationDto? = null,
    @SerializedName("confidence") val confidence: String? = null,
    @SerializedName("referralReason") val referralReason: String? = null,
    @SerializedName("matchedDisease") val matchedDisease: MatchedDiseaseDto? = null,
)

data class BrandMappingDto(
    @SerializedName("generic_name") val genericName: String,
    @SerializedName("jan_aushadhi_brand") val janAushadhiBrand: String? = null,
    @SerializedName("commercial_brands") val commercialBrands: List<String> = emptyList(),
    @SerializedName("brand_mapping_available") val brandMappingAvailable: Boolean,
)

data class VitalsTriageDto(
    @SerializedName("bp_grade") val bpGrade: String,
    @SerializedName("pulse") val pulse: String,
    @SerializedName("respiratory_rate") val respiratoryRate: String,
    @SerializedName("spo2") val spo2: String,
    @SerializedName("temperature") val temperature: String,
    @SerializedName("bmi") val bmi: String,
    @SerializedName("glucose") val glucose: String? = null,
    @SerializedName("overall_urgency") val overallUrgency: String,
)

data class SafetyAndTriageDto(
    @SerializedName("vitals_triage") val vitalsTriage: VitalsTriageDto? = null,
    @SerializedName("requiresHumanReview") val requiresHumanReview: Boolean,
    @SerializedName("pediatric_referral_flag") val pediatricReferralFlag: Boolean,
    @SerializedName("failure_reason") val failureReason: String? = null,
)
