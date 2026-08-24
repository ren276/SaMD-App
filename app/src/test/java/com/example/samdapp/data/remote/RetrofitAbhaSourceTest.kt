package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.AbhaApiService
import com.example.samdapp.data.remote.dto.AbhaIdentityDto
import com.example.samdapp.data.remote.dto.AbhaIdentityRequestDto
import com.example.samdapp.data.remote.dto.AbhaIdentitySubmitResponseDto
import com.example.samdapp.data.remote.dto.AbhaMobileOtpRequestDto
import com.example.samdapp.data.remote.dto.AbhaOtpRequestDto
import com.example.samdapp.data.remote.dto.AbhaOtpResponseDto
import com.example.samdapp.data.remote.dto.AbhaRegistrationSessionDto
import com.example.samdapp.data.remote.dto.AbhaSessionStateResponseDto
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.domain.abha.AbhaApiResult
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** Every call [RetrofitAbhaSource] makes goes through this fake instead of a real Retrofit
 *  client. [getProfileThrows] stands in for a converter-layer failure (Gson throws when the
 *  response body cannot be parsed into [AbhaIdentityDto]), and from `RetrofitAbhaSource.call`'s own
 *  vantage point, a Retrofit call that fails inside the Gson converter and a fake that throws
 *  directly are indistinguishable: both surface as an exception out of `block()`. */
private class ThrowingAbhaApiService(private val getProfileThrows: Throwable) : AbhaApiService {
    override suspend fun startRegistrationSession(): Response<ApiEnvelopeDto<AbhaRegistrationSessionDto>> =
        throw UnsupportedOperationException("not used by this test")

    override suspend fun submitIdentity(sessionId: String, request: AbhaIdentityRequestDto): Response<ApiEnvelopeDto<AbhaIdentitySubmitResponseDto>> =
        throw UnsupportedOperationException("not used by this test")

    override suspend fun verifyOtp(sessionId: String, request: AbhaOtpRequestDto): Response<ApiEnvelopeDto<AbhaOtpResponseDto>> =
        throw UnsupportedOperationException("not used by this test")

    override suspend fun verifyMobileOtp(sessionId: String, request: AbhaMobileOtpRequestDto): Response<ApiEnvelopeDto<AbhaOtpResponseDto>> =
        throw UnsupportedOperationException("not used by this test")

    override suspend fun getSessionState(sessionId: String): Response<ApiEnvelopeDto<AbhaSessionStateResponseDto>> =
        throw UnsupportedOperationException("not used by this test")

    override suspend fun getProfile(sessionId: String): Response<ApiEnvelopeDto<AbhaIdentityDto>> = throw getProfileThrows
}

/**
 * `call {}` previously caught only [java.io.IOException]. A converter exception (Gson failing to
 * parse a malformed or contract-drifted response body, such as `date_of_birth: "1991"` before
 * `SyncGsonAdapters`' year-only fallback existed) is a [RuntimeException], not an [IOException],
 * so it escaped `call` entirely and crashed the caller's coroutine.
 */
class RetrofitAbhaSourceTest {

    @Test
    fun `a converter exception on getProfile is classified as ProtocolViolation, not thrown`() = runTest {
        val source = RetrofitAbhaSource(ThrowingAbhaApiService(JsonSyntaxException("malformed date_of_birth")))

        val result = source.getProfile("session-1")

        assertTrue(result is AbhaApiResult.ProtocolViolation)
    }

    @Test
    fun `a plain RuntimeException on getProfile is also classified, not thrown`() = runTest {
        val source = RetrofitAbhaSource(ThrowingAbhaApiService(IllegalStateException("unexpected shape")))

        val result = source.getProfile("session-1")

        assertTrue(result is AbhaApiResult.ProtocolViolation)
    }

    @Test(expected = CancellationException::class)
    fun `coroutine cancellation is rethrown, not swallowed as a failure result`() = runTest {
        val source = RetrofitAbhaSource(ThrowingAbhaApiService(CancellationException("cancelled")))

        source.getProfile("session-1")
    }
}
