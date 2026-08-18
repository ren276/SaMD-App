package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.SyncPushApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ProblemDetailDto
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.data.remote.dto.SyncPushResponseDto
import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

/** Thin wrapper over [SyncPushApiService], same shape as [RetrofitAuthService]: turns
 *  `Response<ApiEnvelopeDto<T>>` into [SyncPushResult], parsing the RFC 9457 error body on a
 *  whole-batch failure. Reuses the shared, sync-aware [Gson] (di/NetworkModule.kt) purely to
 *  parse [ProblemDetailDto] error bodies here — unrelated to the packer's own use of that same
 *  instance for byte-budget measurement. */
class RetrofitSyncPushService @Inject constructor(
    private val syncPushApiService: SyncPushApiService,
    private val gson: Gson,
) : SyncPushService {

    override suspend fun push(request: SyncPushRequestDto): SyncPushResult<SyncPushResponseDto> {
        val response = try {
            syncPushApiService.push(request)
        } catch (e: IOException) {
            return SyncPushResult.Failure(code = null, message = e.message ?: "Network error.")
        }
        return unwrap(response)
    }

    private fun unwrap(response: Response<ApiEnvelopeDto<SyncPushResponseDto>>): SyncPushResult<SyncPushResponseDto> {
        if (response.isSuccessful) {
            val body = response.body() ?: return SyncPushResult.Failure(code = null, message = "Empty response body.")
            return SyncPushResult.Success(body.data)
        }
        val problem = response.errorBody()?.charStream()?.use { reader ->
            runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
        }
        return SyncPushResult.Failure(
            code = problem?.code,
            message = problem?.detail ?: "Request failed (HTTP ${response.code()}).",
        )
    }
}
