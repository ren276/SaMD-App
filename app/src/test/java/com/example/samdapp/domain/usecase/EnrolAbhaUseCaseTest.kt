package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.testutil.FakeAbdmAbhaSource
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.testAbhaIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 of the real ABHA create flow: OTP verification, the conditional mobile-verification
 *  branch, the resulting profile save, and error classification on every failure shape
 *  [com.example.samdapp.domain.abha.AbhaApiResult] can produce. */
class EnrolAbhaUseCaseTest {

    private fun useCase(source: FakeAbdmAbhaSource, repo: FakeAbhaProfileRepository = FakeAbhaProfileRepository()) =
        EnrolAbhaUseCase(source, repo) to repo

    @Test
    fun `otp shape is validated before the backend is called`() = runTest {
        val source = FakeAbdmAbhaSource()
        val (useCase, _) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "123", mobileNumber = "9998887776")

        assertTrue(result is AbhaEnrolResult.Error)
        assertTrue(source.verifyOtpCalls.isEmpty())
    }

    @Test
    fun `mobile number shape is validated before the backend is called`() = runTest {
        val source = FakeAbdmAbhaSource()
        val (useCase, _) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "123456", mobileNumber = "12345")

        assertTrue(result is AbhaEnrolResult.Error)
        assertTrue(source.verifyOtpCalls.isEmpty())
    }

    @Test
    fun `linear path verifies otp, fetches profile, and saves it`() = runTest {
        val identity = testAbhaIdentity(abhaNumber = "43422151056749")
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "s1", state = AbhaTransactionState.ENROLLED)),
            getProfileResult = AbhaApiResult.Success(identity),
        )
        val (useCase, repo) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        assertEquals(Triple("s1", "654321", "9998887776"), source.verifyOtpCalls.single())
        assertTrue(source.getProfileCalled)
        val outcome = (result as AbhaEnrolResult.Success).data as AbhaEnrolOutcome.Enrolled
        assertEquals(identity.abhaNumber, outcome.profile.abhaId)
        assertEquals(identity.abhaNumber, repo.profiles.keys.single())
    }

    @Test
    fun `mobile verification required branch does not fetch profile until the second otp is verified`() = runTest {
        val identity = testAbhaIdentity(abhaNumber = "43422151056749")
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s1", state = AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED),
            ),
            verifyMobileOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s1", state = AbhaTransactionState.MOBILE_VERIFIED),
            ),
            getProfileResult = AbhaApiResult.Success(identity),
        )
        val (useCase, repo) = useCase(source)

        val firstRound = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        assertEquals(AbhaEnrolOutcome.MobileVerificationRequired, (firstRound as AbhaEnrolResult.Success).data)
        assertFalse("must not fetch/save a profile before the account is actually enrolled", source.getProfileCalled)
        assertTrue(repo.profiles.isEmpty())

        val secondRound = useCase.verifyCommunicationMobile(sessionId = "s1", otp = "112233")

        assertEquals("s1" to "112233", source.verifyMobileOtpCalls.single())
        assertTrue(source.getProfileCalled)
        val outcome = (secondRound as AbhaEnrolResult.Success).data as AbhaEnrolOutcome.Enrolled
        assertEquals(identity.abhaNumber, outcome.profile.abhaId)
        assertEquals(identity.abhaNumber, repo.profiles.keys.single())
    }

    @Test
    fun `an unexpected session state is a non-retryable error, never a mock-filled outcome`() = runTest {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "s1", state = AbhaTransactionState.STARTED)),
        )
        val (useCase, repo) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        val error = result as AbhaEnrolResult.Error
        assertFalse(error.retryable)
        assertFalse(source.getProfileCalled)
        assertTrue(repo.profiles.isEmpty())
    }

    // --- Three-way AbhaApiResult branching, at the verifyOtp call site ---

    @Test
    fun `Failure with null code is retryable, offline-worded, and never fabricates an outcome`() = runTest {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Failure(code = null, message = "timeout connecting to 10.0.2.2"),
        )
        val (useCase, repo) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        val error = result as AbhaEnrolResult.Error
        assertTrue(error.retryable)
        assertFalse(error.message.contains("10.0.2.2"))
        assertFalse(source.getProfileCalled)
        assertTrue(repo.profiles.isEmpty())
    }

    @Test
    fun `Failure with a backend code is actionable, not retryable, and is never treated as offline`() = runTest {
        val source = FakeAbdmAbhaSource(
            // 2004: OTP incorrect, per api-contract.md 9.1 — a decision, not a transport failure.
            verifyOtpResult = AbhaApiResult.Failure(code = "SAMD-ABHA-2004", message = "otp mismatch"),
        )
        val (useCase, repo) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "000000", mobileNumber = "9998887776")

        val error = result as AbhaEnrolResult.Error
        assertFalse("a wrong OTP must not be retried automatically", error.retryable)
        assertTrue(error.message.contains("incorrect", ignoreCase = true))
        assertFalse(source.getProfileCalled)
        assertTrue(repo.profiles.isEmpty())
    }

    @Test
    fun `SAMD-ABHA-2007 upstream timeout is the one backend code that is retryable`() = runTest {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Failure(code = "SAMD-ABHA-2007", message = "gateway timeout"),
        )
        val (useCase, _) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        assertTrue((result as AbhaEnrolResult.Error).retryable)
    }

    @Test
    fun `ProtocolViolation is a distinct error, not retryable, and not the offline message`() = runTest {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.ProtocolViolation("Unknown ABHA session state \"WEIRD\" from backend"),
        )
        val (useCase, repo) = useCase(source)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        val error = result as AbhaEnrolResult.Error
        assertFalse(error.retryable)
        assertFalse("must not read as the generic offline message", error.message.contains("No connection"))
        assertFalse(source.getProfileCalled)
        assertTrue(repo.profiles.isEmpty())
    }

    @Test
    fun `a failed local save after real enrolment is retryable, since ABDM already created the account`() = runTest {
        val identity = testAbhaIdentity()
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(AbhaSessionSnapshot(sessionId = "s1", state = AbhaTransactionState.ENROLLED)),
            getProfileResult = AbhaApiResult.Success(identity),
        )
        val repo = FakeAbhaProfileRepository().apply {
            saveResult = Result.failure(RuntimeException("disk full"))
        }
        val useCase = EnrolAbhaUseCase(source, repo)

        val result = useCase(sessionId = "s1", otp = "654321", mobileNumber = "9998887776")

        assertTrue((result as AbhaEnrolResult.Error).retryable)
    }
}
