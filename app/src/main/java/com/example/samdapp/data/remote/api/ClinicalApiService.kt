package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.EvaluateReportDto
import com.example.samdapp.data.remote.dto.EvaluateRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for the SaMDClassifier FastAPI backend's clinical-evaluation endpoint
 * (`POST /api/v1/evaluate`) — a distinct concern from [KernelApiService]'s `/v1/assess`
 * (ML differential-diagnosis kernel) even though both live on the same backend process.
 *
 * Returns `Response<EvaluateReportDto>` rather than a bare suspend-returning DTO because the
 * backend's failure-path body (see [com.example.samdapp.data.remote.dto.EvaluateErrorDto])
 * is not the same schema as the success body — callers need `response.isSuccessful` /
 * `response.code()` / `response.errorBody()` to branch correctly instead of relying on
 * Retrofit to throw HttpException and losing the error body's structure.
 */
interface ClinicalApiService {

    @POST("/api/v1/evaluate")
    suspend fun evaluate(@Body request: EvaluateRequestDto): Response<EvaluateReportDto>
}
