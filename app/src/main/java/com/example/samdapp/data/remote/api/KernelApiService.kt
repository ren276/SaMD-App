package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the backend's kernel proxy (`POST /api/v1/assess`, api-contract.md
 * §5.3), which forwards internally to the FastAPI + XGBoost ML kernel's `POST /v1/assess`; that
 * internal forwarding is the backend's job, invisible from here. Base URL:
 * `BuildConfig.BACKEND_BASE_URL`.
 *
 * This is a one-endpoint service — the full clinical assessment is a single synchronous
 * inference request. Retrofit suspends the coroutine internally, so callers are
 * coroutine-safe. IOException / HttpException propagate naturally; [GenerateKernelReportUseCase]
 * catches both and falls back to the local mock.
 */
interface KernelApiService {

    @POST("api/v1/assess")
    suspend fun assess(@Body request: KernelAssessmentRequestDto): ApiEnvelopeDto<KernelAssessmentResponseDto>
}
