package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Response DTO from the FastAPI + XGBoost kernel endpoint (`POST /v1/assess`).
 * Maps to [com.example.samdapp.domain.model.KernelReportOutput] in [GenerateKernelReportUseCase].
 *
 * The [differentialDiagnosis] list is the ML model's ranked differentials — each entry carries
 * its own probability, SHAP evidence strings, and a condition tier label. Declared nullable
 * because Gson populates fields by reflection and bypasses Kotlin's non-null enforcement: an
 * absent or JSON-null key on the wire silently lands as Kotlin `null`, not a runtime error, no
 * matter what this property's declared type claims. A non-null declaration here previously masked
 * that gap and let an absent-key response NPE at the call site's `.firstOrNull()`, which landed in
 * the generic failure catch and was indistinguishable from an unreachable kernel in the audit
 * trail. Declaring it nullable makes the absent-key and empty-list cases equivalent on purpose:
 * both mean "the kernel answered but produced no differential."
 */
data class KernelAssessmentResponseDto(
    @SerializedName("case_token") val caseToken: String,
    @SerializedName("safety_screen_passed") val safetyScreenPassed: Boolean,
    @SerializedName("triage_urgency") val triageUrgency: String,
    @SerializedName("differential_diagnosis") val differentialDiagnosis: List<DifferentialDto>?,
    @SerializedName("recommended_investigations") val recommendedInvestigations: List<String>,
    @SerializedName("model_metadata") val modelMetadata: ModelMetadataDto?,
)

/**
 * A single ranked differential from the ML model.
 *
 * Field mappings to [com.example.samdapp.domain.model.KernelReportOutput]:
 * - [conditionTier]  → `predictedCondition` (top-ranked entry only)
 * - [probability]    → `confidenceScore`    (top-ranked entry only)
 * - [evidenceFor]    → `evidenceFor`        (top-ranked entry only)
 * - [evidenceAgainst]→ `evidenceAgainst`    (top-ranked entry only)
 */
data class DifferentialDto(
    @SerializedName("condition_tier") val conditionTier: String,
    @SerializedName("probability") val probability: Double,
    @SerializedName("evidence_for") val evidenceFor: List<String>,
    @SerializedName("evidence_against") val evidenceAgainst: List<String>,
)

/** Optional model metadata included in the kernel response for audit / report rendering. */
data class ModelMetadataDto(
    @SerializedName("model_version") val modelVersion: String?,
    @SerializedName("inference_time_ms") val inferenceTimeMs: Long?,
)
