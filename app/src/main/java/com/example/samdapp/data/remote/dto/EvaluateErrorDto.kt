package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Shape of the non-schema error body returned by `POST /api/v1/evaluate` on an unhandled
 * exception (app.py:184-193) — a 500 response body of `{"error", "message", "case_token"}`,
 * which is a DIFFERENT shape than the success schema [EvaluateReportDto]. This is why
 * [com.example.samdapp.data.remote.api.ClinicalApiService.evaluate] returns
 * `Response<EvaluateReportDto>` rather than a bare suspend-returning DTO: callers must
 * branch on `response.isSuccessful` before reading the body, and parse `response.errorBody()`
 * against this class instead on failure. Parsing/branching logic itself is future
 * repository-layer work, not implemented here — this class only defines the shape.
 */
data class EvaluateErrorDto(
    @SerializedName("error") val error: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("case_token") val caseToken: String? = null,
)
