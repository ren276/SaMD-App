package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ChangePinRequestDto
import com.example.samdapp.data.remote.dto.ChangePinResponseDto
import com.example.samdapp.data.remote.dto.LoginRequestDto
import com.example.samdapp.data.remote.dto.LoginResponseDto
import com.example.samdapp.data.remote.dto.LogoutRequestDto
import com.example.samdapp.data.remote.dto.LogoutResponseDto
import com.example.samdapp.data.remote.dto.MeResponseDto
import com.example.samdapp.data.remote.dto.RefreshRequestDto
import com.example.samdapp.data.remote.dto.TokenPairDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for `backend/core`'s five auth endpoints (api-contract.md §2). Every method
 * returns `Response<ApiEnvelopeDto<T>>`, not a bare suspend body, because the error body on a
 * non-2xx response is a different shape ([com.example.samdapp.data.remote.dto.ProblemDetailDto],
 * RFC 9457): callers need `response.isSuccessful` to branch instead of losing that shape to a
 * bare `HttpException`. [com.example.samdapp.data.remote.RetrofitAuthService] is the one place
 * that does this parsing; nothing else should call this interface directly.
 *
 * [login] and [refresh] require no `Authorization` header (§0.7): [com.example.samdapp.data.remote.BearerInterceptor]
 * skips attaching a token to these two paths specifically, so this
 * interface does not need separate unauthenticated/authenticated variants.
 */
interface AuthApiService {

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequestDto): Response<ApiEnvelopeDto<LoginResponseDto>>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): Response<ApiEnvelopeDto<TokenPairDto>>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body request: LogoutRequestDto): Response<ApiEnvelopeDto<LogoutResponseDto>>

    @GET("api/v1/auth/me")
    suspend fun me(): Response<ApiEnvelopeDto<MeResponseDto>>

    @POST("api/v1/auth/change-pin")
    suspend fun changePin(@Body request: ChangePinRequestDto): Response<ApiEnvelopeDto<ChangePinResponseDto>>
}
