package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.KernelAssessmentRequestDto
import com.example.samdapp.data.remote.dto.KernelAssessmentResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the backend's kernel proxy (`POST /api/v1/assess`, api-contract.md
 * §5.3), which forwards internally to the FastAPI + XGBoost ML kernel's `POST /v1/assess`; that
 * internal forwarding is the backend's job, invisible from here. Base URL:
 * `BuildConfig.BACKEND_BASE_URL`.
 *
 * This is a one-endpoint service — the full clinical assessment is a single synchronous
 * inference request. Retrofit suspends the coroutine internally, so callers are coroutine-safe.
 *
 * Returns [Response] rather than the envelope directly, so [com.example.samdapp.data.remote.RetrofitKernelSource]
 * can read the RFC 9457 problem document out of a non-2xx body and surface its `SAMD-*` code.
 * Declared as the bare envelope until this change, which meant Retrofit raised an `HttpException`
 * whose `message` is only the status line, and the code in the body was discarded unread. Same
 * shape as `AuthApiService` and the ABHA services, which have always returned [Response].
 */
interface KernelApiService {

    @POST("api/v1/assess")
    suspend fun assess(
        @Body request: KernelAssessmentRequestDto,
    ): Response<ApiEnvelopeDto<KernelAssessmentResponseDto>>
}
