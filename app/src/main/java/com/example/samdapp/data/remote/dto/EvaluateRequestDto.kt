package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Outbound request DTO for `POST /api/v1/evaluate` (SaMDClassifier FastAPI backend).
 * Mirrors `ClinicalEvaluationRequest` in api_schemas.py / app.py exactly — field set,
 * types, and optionality all match the Pydantic model 1:1.
 *
 * Distinct from [KernelAssessmentRequestDto] (`POST /v1/assess`) — a different endpoint/
 * contract on the same backend host, additive to the existing kernel flow.
 *
 * Gson omits null fields when serializing by default, which matches FastAPI's
 * `Optional[x] = None` semantics (an absent key is treated the same as an explicit `None`).
 */
data class EvaluateRequestDto(
    @SerializedName("case_token") val caseToken: String,
    @SerializedName("symptom_string") val symptomString: String,
    @SerializedName("age") val age: Int,
    @SerializedName("sex") val sex: String,
    @SerializedName("systolic_bp") val systolicBp: Double,
    @SerializedName("diastolic_bp") val diastolicBp: Double,
    @SerializedName("bmi") val bmi: Double,
    @SerializedName("heart_rate") val heartRate: Double,
    @SerializedName("random_glucose") val randomGlucose: Double? = null,
    @SerializedName("spo2") val spo2: Double,
    @SerializedName("respiratory_rate") val respiratoryRate: Double? = null,
    @SerializedName("temperature") val temperature: Double? = null,
)
