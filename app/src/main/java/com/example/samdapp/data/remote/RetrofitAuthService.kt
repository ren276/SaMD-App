package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ChangePinRequestDto
import com.example.samdapp.data.remote.dto.ChangePinResponseDto
import com.example.samdapp.data.remote.dto.LoginRequestDto
import com.example.samdapp.data.remote.dto.LoginResponseDto
import com.example.samdapp.data.remote.dto.LogoutRequestDto
import com.example.samdapp.data.remote.dto.LogoutResponseDto
import com.example.samdapp.data.remote.dto.MeResponseDto
import com.example.samdapp.data.remote.dto.ProblemDetailDto
import com.example.samdapp.data.remote.dto.RefreshRequestDto
import com.example.samdapp.data.remote.dto.TokenPairDto
import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

/**
 * Thin wrapper over [AuthApiService] that turns `Response<ApiEnvelopeDto<T>>` into [AuthApiResult],
 * parsing the RFC 9457 error body on failure (api-contract.md §0.6) instead of leaving that to
 * every caller. [com.example.samdapp.data.local.auth.BackendAuthSession] is the primary caller;
 * [refresh] is also called directly by the OkHttp `Authenticator` in `di/NetworkModule.kt`.
 */
class RetrofitAuthService @Inject constructor(
    private val authApiService: AuthApiService,
) {
    private val gson = Gson()

    suspend fun login(workerId: String, pin: String, deviceId: String): AuthApiResult<LoginResponseDto> =
        call { authApiService.login(LoginRequestDto(workerId, pin, deviceId)) }

    suspend fun refresh(refreshToken: String, deviceId: String): AuthApiResult<TokenPairDto> =
        call { authApiService.refresh(RefreshRequestDto(refreshToken, deviceId)) }

    suspend fun logout(refreshToken: String): AuthApiResult<LogoutResponseDto> =
        call { authApiService.logout(LogoutRequestDto(refreshToken)) }

    suspend fun me(): AuthApiResult<MeResponseDto> = call { authApiService.me() }

    suspend fun changePin(currentPin: String, newPin: String): AuthApiResult<ChangePinResponseDto> =
        call { authApiService.changePin(ChangePinRequestDto(currentPin, newPin)) }

    private suspend inline fun <T> call(block: suspend () -> Response<ApiEnvelopeDto<T>>): AuthApiResult<T> {
        val response = try {
            block()
        } catch (e: IOException) {
            return AuthApiResult.Failure(code = null, message = e.message ?: "Network error.")
        }

        if (response.isSuccessful) {
            val body = response.body() ?: return AuthApiResult.Failure(code = null, message = "Empty response body.")
            return AuthApiResult.Success(body.data)
        }

        val problem = response.errorBody()?.charStream()?.use { reader ->
            runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
        }
        return AuthApiResult.Failure(
            code = problem?.code,
            message = problem?.detail ?: "Request failed (HTTP ${response.code()}).",
        )
    }
}
