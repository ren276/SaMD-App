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
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.domain.usecase.AbhaEnrolOutcome
import com.example.samdapp.domain.usecase.EnrolAbhaUseCase
import com.example.samdapp.testutil.FakeAbdmAbhaSource
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** [getProfile] is the only method this fixture exercises; everything else is unreachable from
 *  this test and throws if called. */
private class GetProfileOnlyAbhaApiService(
    private val response: Response<ApiEnvelopeDto<AbhaIdentityDto>>,
) : AbhaApiService {
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

    override suspend fun getProfile(sessionId: String): Response<ApiEnvelopeDto<AbhaIdentityDto>> = response
}

/**
 * The 2026-08-25 live `get_profile` envelope, verbatim in shape — key names, nesting, `null`s,
 * the single-letter `"M"` gender, the `"YYYY-MM-DD"` date, and the 10-digit **unmasked** mobile
 * number all kept byte-identical to what ABDM actually returned. PHI values themselves are
 * replaced with equivalently-shaped placeholders. This is the fixture BUILD 2's design memo
 * (§(d)) requires: the test starts at this JSON string, not a hand-built [AbhaIdentityDto(...)]
 * constructor call, so it can never silently drift from the real wire shape the way a
 * hand-authored approximation could (the same rule PR #18 was caught by).
 */
private const val LIVE_GET_PROFILE_ENVELOPE = """
{
  "success": true,
  "data": {
    "abha_number": "12345678901234",
    "abha_address": "testworker@sbx",
    "name": "Test Patient",
    "date_of_birth": "1985-06-15",
    "gender": "M",
    "address": "123 Test Street",
    "district": "Test District",
    "state": "Test State",
    "pincode": "123456",
    "mobile_number": "9999999999",
    "email_address": null,
    "photo_url": null,
    "kyc_verified": true,
    "verification_source": "ABDM_AADHAAR_OTP",
    "verified_at": "2026-08-25T10:00:00Z"
  },
  "meta": null
}
"""

/**
 * Parses the verbatim envelope through [SyncGson.create] (the app's real [com.google.gson.Gson],
 * [com.example.samdapp.di.NetworkModule]'s `provideGson`), then runs the resulting [AbhaIdentityDto]
 * through the real [RetrofitAbhaSource]/[EnrolAbhaUseCase] pipeline — never a hand-reimplemented
 * copy of that mapping — to assert the [com.example.samdapp.domain.model.AbhaProfile] the UI
 * screen actually reads.
 */
class AbhaProfileWireShapeTest {

    private val envelopeType = object : TypeToken<ApiEnvelopeDto<AbhaIdentityDto>>() {}.type

    @Test
    fun `the live envelope parses into AbhaIdentityDto with every field intact`() {
        val gson = SyncGson.create()

        val envelope = gson.fromJson<ApiEnvelopeDto<AbhaIdentityDto>>(LIVE_GET_PROFILE_ENVELOPE, envelopeType)
        val dto = envelope.data

        assertEquals("12345678901234", dto.abhaNumber)
        assertEquals("testworker@sbx", dto.abhaAddress)
        assertEquals("Test Patient", dto.name)
        assertEquals(LocalDate.of(1985, 6, 15), dto.dateOfBirth)
        assertEquals("M", dto.gender)
        assertEquals("123 Test Street", dto.address)
        assertEquals("Test District", dto.district)
        assertEquals("Test State", dto.state)
        assertEquals("123456", dto.pincode)
        assertEquals("9999999999", dto.mobileNumber)
        assertNull(dto.emailAddress)
        assertNull(dto.photoUrl)
        assertTrue(dto.kycVerified)
        assertEquals("ABDM_AADHAAR_OTP", dto.verificationSource)
    }

    @Test
    fun `the parsed identity flows through RetrofitAbhaSource and EnrolAbhaUseCase into the AbhaProfile the UI reads`() = runTest {
        val gson = SyncGson.create()
        val envelope = gson.fromJson<ApiEnvelopeDto<AbhaIdentityDto>>(LIVE_GET_PROFILE_ENVELOPE, envelopeType)
        val retrofitSource = RetrofitAbhaSource(GetProfileOnlyAbhaApiService(Response.success(envelope)))

        val identityResult = retrofitSource.getProfile("session-1")
        val identity = (identityResult as AbhaApiResult.Success).data

        val fakeSource = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "session-1", state = AbhaTransactionState.ENROLLED)),
            getProfileResult = AbhaApiResult.Success(identity),
        )
        val useCase = EnrolAbhaUseCase(fakeSource, FakeAbhaProfileRepository())

        val result = useCase(sessionId = "session-1", otp = "654321", mobileNumber = "9999999999")

        val profile = ((result as AbhaEnrolResult.Success).data as AbhaEnrolOutcome.Enrolled).profile
        assertEquals("12345678901234", profile.abhaId)
        assertEquals("testworker@sbx", profile.abhaAddress)
        assertEquals("Test Patient", profile.name)
        assertEquals(LocalDate.of(1985, 6, 15), profile.dateOfBirth)
        assertEquals("M", profile.gender)
        assertEquals("9999999999", profile.mobileNumber)
        assertNull(profile.emailAddress)
        assertNull(profile.photoUrlMock)
        assertTrue(profile.kycVerified)
    }
}
