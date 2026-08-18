package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncPushResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for `POST /api/v1/sync/push` (api-contract.md §6.1). `Response<ApiEnvelopeDto<T>>`,
 * not a bare suspend body, for the same reason as [AuthApiService]: a non-2xx (whole-batch
 * failure — 413/422/403, never a per-record rejection, which comes back inside a 200) carries the
 * RFC 9457 [com.example.samdapp.data.remote.dto.ProblemDetailDto] shape, and
 * [com.example.samdapp.data.remote.RetrofitSyncPushService] is the one place that parses it.
 */
interface SyncPushApiService {

    @POST("api/v1/sync/push")
    suspend fun push(@Body request: SyncPushRequestDto): Response<ApiEnvelopeDto<SyncPushResponseDto>>
}
