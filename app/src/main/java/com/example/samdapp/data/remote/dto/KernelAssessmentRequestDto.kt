package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Outbound request DTO for the local FastAPI + XGBoost kernel endpoint
 * (`POST /v1/assess`). Contains only pseudonymized clinical signals —
 * no patient name, Aadhaar, ABHA, or address fields.
 *
 * [caseToken] reuses the [CaseRecord.id] as the correlation key
 * (same rationale as [KernelPayload.caseToken] in the domain layer).
 * [age] and [sex] are clinical signals, not identity fields — included
 * here as required classifier inputs, flagged in PROGRESS.md.
 */
data class KernelAssessmentRequestDto(
    @SerializedName("case_token") val caseToken: String,
    @SerializedName("age") val age: Int,
    @SerializedName("sex") val sex: String,
    @SerializedName("systolic_bp") val systolicBp: Double,
    @SerializedName("diastolic_bp") val diastolicBp: Double,
    @SerializedName("bmi") val bmi: Double,
    @SerializedName("heart_rate") val heartRate: Double,
    @SerializedName("random_glucose") val randomGlucose: Double,
    @SerializedName("spo2") val spo2: Double,
)
