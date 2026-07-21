package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.KernelPayload

/**
 * Domain boundary for the remote clinical AI kernel — mirrors the "named mock boundary" pattern
 * used by [com.example.samdapp.domain.vitalssource.VitalsSource] and
 * [com.example.samdapp.domain.transcription.TranscriptionService].
 *
 * Implementations:
 * - [com.example.samdapp.data.remote.RetrofitKernelSource] — calls the real FastAPI endpoint.
 *
 * The use case depends only on this interface; the data layer's Retrofit types never enter
 * the domain layer (Clean Architecture boundary maintained).
 */
interface RemoteKernelSource {

    /**
     * Sends [payload] to the remote kernel and returns a parsed [KernelAssessmentResult].
     * Throws on any failure (IOException, HttpException, etc.) — the caller ([GenerateKernelReportUseCase])
     * catches and falls back to the local mock.
     *
     * [patientAge] and [patientSex] are clinical signals (not PII) required by the XGBoost
     * classifier and are NOT part of [KernelPayload] (which enforces the pseudonymization
     * boundary from [SendToKernelUseCase]).
     */
    suspend fun assess(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): KernelAssessmentResult
}

/**
 * The parsed, domain-typed result from the remote kernel — decoupled from the Retrofit DTO shapes.
 * [predictedCondition] is the top-ranked differential's condition tier.
 * [evidenceFor]/[evidenceAgainst] are SHAP-based reasoning strings from the top differential.
 * [differentials] contains all OTHER ranked differentials (excluding the top one).
 */
data class KernelAssessmentResult(
    val predictedCondition: String,
    val confidenceScore: Double,
    val triageUrgency: String,
    val safetyScreenPassed: Boolean,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    /** All differentials EXCEPT the top one (whose details populate the above fields). */
    val differentials: List<String>,
    val recommendedInvestigations: List<String>,
    val modelVersion: String?,
)
