package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.EvaluateReportDto
import com.example.samdapp.data.remote.dto.EvaluateRequestDto
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the backend's clinical-evaluation proxy (`POST /api/v1/evaluate`,
 * api-contract.md §5.4), a distinct concern from [KernelApiService]'s `/v1/assess` (ML
 * differential-diagnosis kernel) even though both are forwarded by the same backend process.
 *
 * A bare suspend function, not `Response<EvaluateReportDto>`: the backend now translates the
 * kernel's differently-shaped upstream failure body into the standard RFC 9457 error envelope
 * before it ever reaches Android (§5.4 "Upstream error translation"), so a non-2xx response is a
 * normal `HttpException` here like every other endpoint: there is no longer a second error
 * schema for a caller to lose by not inspecting `Response<T>` directly.
 */
interface ClinicalApiService {

    @POST("/api/v1/evaluate")
    suspend fun evaluate(@Body request: EvaluateRequestDto): ApiEnvelopeDto<EvaluateReportDto>
}
