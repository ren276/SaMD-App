package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.AbhaApiService
import com.example.samdapp.data.remote.dto.AbhaIdentityDto
import com.example.samdapp.data.remote.dto.AbhaIdentityRequestDto
import com.example.samdapp.data.remote.dto.AbhaMobileOtpRequestDto
import com.example.samdapp.data.remote.dto.AbhaOtpRequestDto
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ProblemDetailDto
import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaIdentity
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.google.gson.Gson
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

/**
 * Retrofit-backed implementation of [AbdmAbhaSource]. Same `call { }` / error-parsing shape as
 * [RetrofitAuthService] (RFC 9457 [ProblemDetailDto] on non-2xx, `code = null` on a connectivity
 * failure that never reached the backend at all) — see [AbhaApiResult]'s KDoc for why that
 * distinction is load-bearing here, not just a style match.
 *
 * Uses [AbhaApiService], which rides the same authenticated [okhttp3.OkHttpClient] (Bearer token
 * attached via [BearerInterceptor], 401 handled by [TokenAuthenticator]) as every other
 * `backend/core` call in `di/NetworkModule.kt` — there is no separate, unauthenticated ABHA
 * client to accidentally use instead.
 */
class RetrofitAbhaSource @Inject constructor(
    private val abhaApiService: AbhaApiService,
) : AbdmAbhaSource {

    private val gson = Gson()

    override suspend fun startRegistrationSession(): AbhaApiResult<AbhaSessionSnapshot> =
        call { abhaApiService.startRegistrationSession() }.map { dto ->
            AbhaSessionSnapshot(sessionId = dto.sessionId, state = parseState(dto.state), expiresAt = dto.expiresAt)
        }

    override suspend fun submitIdentity(sessionId: String, aadhaarNumber: String): AbhaApiResult<AbhaSessionSnapshot> =
        call { abhaApiService.submitIdentity(sessionId, AbhaIdentityRequestDto(aadhaarNumber)) }.map { dto ->
            AbhaSessionSnapshot(sessionId = dto.sessionId, state = parseState(dto.state), maskedMobile = dto.maskedMobile)
        }

    override suspend fun verifyOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot> =
        call { abhaApiService.verifyOtp(sessionId, AbhaOtpRequestDto(otp)) }.map { dto ->
            AbhaSessionSnapshot(sessionId = dto.sessionId, state = parseState(dto.state))
        }

    override suspend fun verifyMobileOtp(sessionId: String, otp: String): AbhaApiResult<AbhaSessionSnapshot> =
        call { abhaApiService.verifyMobileOtp(sessionId, AbhaMobileOtpRequestDto(otp)) }.map { dto ->
            AbhaSessionSnapshot(sessionId = dto.sessionId, state = parseState(dto.state))
        }

    override suspend fun getSessionState(sessionId: String): AbhaApiResult<AbhaSessionSnapshot> =
        call { abhaApiService.getSessionState(sessionId) }.map { dto ->
            AbhaSessionSnapshot(sessionId = dto.sessionId, state = parseState(dto.state), lastError = dto.lastError)
        }

    override suspend fun getProfile(sessionId: String): AbhaApiResult<AbhaIdentity> =
        call { abhaApiService.getProfile(sessionId) }.map { it.toDomain() }

    /** Backend states are always one of [AbhaTransactionState]'s eleven values
     *  (`abha-internal-contract.md`'s state machine, confirmed unchanged from the plan doc) — an
     *  unrecognized string is a contract drift, not a value to silently coerce into some default
     *  state a caller might mistake for real progress. */
    private fun parseState(raw: String): AbhaTransactionState =
        AbhaTransactionState.entries.firstOrNull { it.name == raw }
            ?: error("Unknown ABHA session state \"$raw\" from backend")

    private fun AbhaIdentityDto.toDomain() = AbhaIdentity(
        abhaNumber = abhaNumber,
        abhaAddress = abhaAddress,
        name = name,
        dateOfBirth = dateOfBirth,
        gender = gender,
        address = address,
        district = district,
        state = state,
        pincode = pincode,
        mobileNumber = mobileNumber,
        emailAddress = emailAddress,
        photoUrl = photoUrl,
        kycVerified = kycVerified,
        verificationSource = verificationSource,
        verifiedAt = verifiedAt,
    )

    private inline fun <T, R> AbhaApiResult<T>.map(transform: (T) -> R): AbhaApiResult<R> = when (this) {
        is AbhaApiResult.Success -> AbhaApiResult.Success(transform(data))
        is AbhaApiResult.Failure -> this
    }

    private suspend inline fun <T> call(block: suspend () -> Response<ApiEnvelopeDto<T>>): AbhaApiResult<T> {
        val response = try {
            block()
        } catch (e: IOException) {
            return AbhaApiResult.Failure(code = null, message = e.message ?: "Network error.")
        }

        if (response.isSuccessful) {
            val body = response.body() ?: return AbhaApiResult.Failure(code = null, message = "Empty response body.")
            return AbhaApiResult.Success(body.data)
        }

        val problem = response.errorBody()?.charStream()?.use { reader ->
            runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
        }
        return AbhaApiResult.Failure(
            code = problem?.code,
            message = problem?.detail ?: "Request failed (HTTP ${response.code()}).",
        )
    }
}
