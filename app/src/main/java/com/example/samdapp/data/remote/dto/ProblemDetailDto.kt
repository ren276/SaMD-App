package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Every non-2xx response body from `backend/core` has this shape (api-contract.md §0.6,
 * RFC 9457 Problem Details plus four extension members). Android branches on [code], never on
 * [title] or [detail]: those are human-facing and may be reworded without a contract change.
 * [detail] never contains PHI, so it is safe to surface directly in UI error text.
 */
data class ProblemDetailDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    @SerializedName("request_id") val requestId: String? = null,
    val timestamp: String? = null,
    val errors: List<FieldErrorDto>? = null,
)

data class FieldErrorDto(
    val field: String? = null,
    val message: String? = null,
)
