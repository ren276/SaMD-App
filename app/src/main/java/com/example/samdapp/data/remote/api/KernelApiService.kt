package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the local FastAPI + XGBoost ML kernel.
 * Base URL: Injected via BuildConfig.KERNEL_BASE_URL (from local.properties).
 *
 * This is a one-endpoint service — the full clinical assessment is a single synchronous
 * inference request. Retrofit suspends the coroutine internally, so callers are
 * coroutine-safe. IOException / HttpException propagate naturally; [GenerateKernelReportUseCase]
 * catches both and falls back to the local mock.
 */
interface KernelApiService {

    @POST("/v1/assess")
    suspend fun assess(@Body request: KernelAssessmentRequestDto): KernelAssessmentResponseDto
}
