package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import java.time.Instant

/**
 * What [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase] falls back to when the
 * real `/api/v1/assess` call fails. Bound per build flavor (see `MockBoundaryModule` for the
 * dev-only mock binding and the staging/prod no-op binding) so a fabricated clinical scenario can
 * only ever be compiled into a dev build — staging/prod bind an implementation that always
 * returns null, which [GenerateKernelReportUseCase] turns into an honest
 * [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE] result instead.
 */
interface KernelFallbackSource {
    /** Returns a fallback [KernelReportOutput], or null if this build has no fallback to offer. */
    suspend fun fallback(
        caseRecordId: String,
        payload: KernelPayload,
        inferenceStartedAt: Instant,
        dataQualityScore: Double,
    ): KernelReportOutput?
}
