package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Every 2xx response body from `backend/core` has this shape (api-contract.md §0.5). `T` is the
 * per-endpoint `data` payload. [com.example.samdapp.data.remote.api.AuthApiService],
 * [com.example.samdapp.data.remote.api.KernelApiService], and
 * [com.example.samdapp.data.remote.api.ClinicalApiService] all parse into this wrapper rather
 * than the bare body, now that those calls go through the backend proxy instead of hitting the
 * kernel or nothing at all directly.
 */
data class ApiEnvelopeDto<T>(
    val success: Boolean,
    val data: T,
    val meta: ApiMetaDto?,
)

data class ApiMetaDto(
    @SerializedName("request_id") val requestId: String?,
    val timestamp: String?,
    @SerializedName("api_version") val apiVersion: String?,
)
