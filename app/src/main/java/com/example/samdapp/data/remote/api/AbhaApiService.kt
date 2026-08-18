package com.example.samdapp.data.remote.api

import com.example.samdapp.data.remote.dto.AbhaIdentityDto
import com.example.samdapp.data.remote.dto.AbhaIdentityRequestDto
import com.example.samdapp.data.remote.dto.AbhaIdentitySubmitResponseDto
import com.example.samdapp.data.remote.dto.AbhaMobileOtpRequestDto
import com.example.samdapp.data.remote.dto.AbhaOtpRequestDto
import com.example.samdapp.data.remote.dto.AbhaOtpResponseDto
import com.example.samdapp.data.remote.dto.AbhaRegistrationSessionDto
import com.example.samdapp.data.remote.dto.AbhaSessionStateResponseDto
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for `backend/core`'s ABHA registration surface (api-contract.md §8), P0
 * only — see [com.example.samdapp.domain.abha.AbdmAbhaSource]'s KDoc for the P1 login-side scope
 * cut and the current wiring status. Same `Response<ApiEnvelopeDto<T>>` shape as
 * [AuthApiService] for the same reason: the RFC 9457 error body on a non-2xx response carries a
 * `SAMD-ABHA-2xxx` code the caller must branch on, not just a bare `HttpException`.
 * [com.example.samdapp.data.remote.RetrofitAbhaSource] is the one place that does this parsing.
 */
interface AbhaApiService {

    @POST("api/v1/abha/registration-sessions")
    suspend fun startRegistrationSession(): Response<ApiEnvelopeDto<AbhaRegistrationSessionDto>>

    @POST("api/v1/abha/registration-sessions/{id}/identity")
    suspend fun submitIdentity(
        @Path("id") sessionId: String,
        @Body request: AbhaIdentityRequestDto,
    ): Response<ApiEnvelopeDto<AbhaIdentitySubmitResponseDto>>

    @POST("api/v1/abha/registration-sessions/{id}/otp")
    suspend fun verifyOtp(
        @Path("id") sessionId: String,
        @Body request: AbhaOtpRequestDto,
    ): Response<ApiEnvelopeDto<AbhaOtpResponseDto>>

    @POST("api/v1/abha/registration-sessions/{id}/mobile-otp")
    suspend fun verifyMobileOtp(
        @Path("id") sessionId: String,
        @Body request: AbhaMobileOtpRequestDto,
    ): Response<ApiEnvelopeDto<AbhaOtpResponseDto>>

    @GET("api/v1/abha/registration-sessions/{id}")
    suspend fun getSessionState(@Path("id") sessionId: String): Response<ApiEnvelopeDto<AbhaSessionStateResponseDto>>

    @GET("api/v1/abha/registration-sessions/{id}/profile")
    suspend fun getProfile(@Path("id") sessionId: String): Response<ApiEnvelopeDto<AbhaIdentityDto>>
}
