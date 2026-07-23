package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.EvaluateBrandMapping
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.KernelPayload

/**
 * Domain boundary for the real `/api/v1/evaluate` NLEM-treatment kernel — mirrors
 * [RemoteKernelSource]'s "named mock boundary" pattern, kept as a separate interface since this
 * is a distinct clinical concern (treatment/brand/vitals-triage) from `/v1/assess`'s differential
 * diagnosis, not a replacement for it.
 *
 * Implementations:
 * - [com.example.samdapp.data.remote.RetrofitEvaluateSource] — calls the real FastAPI endpoint.
 */
interface EvaluateKernelSource {

    /**
     * Sends [payload] to `/api/v1/evaluate` and returns a parsed [EvaluateResult]. Throws on any
     * failure (IOException, HttpException, etc.) — the caller ([com.example.samdapp.domain.usecase.GenerateEvaluateReportUseCase])
     * catches it and surfaces [Result.failure]; unlike [RemoteKernelSource.assess], there is no
     * mock fallback for this endpoint.
     */
    suspend fun evaluate(
        payload: KernelPayload,
        patientAge: Int,
        patientSex: String,
    ): EvaluateResult
}

/** The parsed, domain-typed result from `/api/v1/evaluate` — decoupled from the Retrofit DTO shapes. */
data class EvaluateResult(
    val diagnosticSummary: EvaluateDiagnosticSummary,
    val nlemTreatment: EvaluateNlemTreatment,
    val brandMapping: EvaluateBrandMapping?,
    val safetyAndTriage: EvaluateSafetyAndTriage,
)
