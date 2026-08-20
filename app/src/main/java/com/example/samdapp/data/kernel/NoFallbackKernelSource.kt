package com.example.samdapp.data.kernel

import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import java.time.Instant
import javax.inject.Inject

/**
 * Staging/prod's [KernelFallbackSource] binding — never fabricates a scenario. A real
 * `/api/v1/assess` failure with no fallback is what
 * [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase] turns into an honest
 * [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE] result. Dev binds
 * `MockKernelFallbackSource` (in `src/dev/`) instead.
 */
class NoFallbackKernelSource @Inject constructor() : KernelFallbackSource {
    override suspend fun fallback(
        caseRecordId: String,
        payload: KernelPayload,
        inferenceStartedAt: Instant,
        dataQualityScore: Double,
    ): KernelReportOutput? = null
}
