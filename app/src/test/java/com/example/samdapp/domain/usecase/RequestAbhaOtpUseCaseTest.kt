package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.testutil.FakeAbdmAbhaSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 1 of the real ABHA create flow: consent gate, Aadhaar shape validation, and the
 *  masked-mobile pass-through from `submitIdentity`'s response. */
class RequestAbhaOtpUseCaseTest {

    private val validAadhaar = "123456789012"

    @Test
    fun `consent not given rejects without calling the backend at all`() = runTest {
        val source = FakeAbdmAbhaSource()

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = false)

        assertTrue(result is AbhaEnrolResult.Error)
        assertFalse((result as AbhaEnrolResult.Error).retryable)
        assertFalse("startRegistrationSession must not be called without consent", source.startCalled)
        assertFalse("submitIdentity must not be called without consent", source.submitIdentityCalled)
    }

    @Test
    fun `non-12-digit aadhaar is rejected without calling the backend`() = runTest {
        val source = FakeAbdmAbhaSource()

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = "1234", consentGiven = true)

        assertTrue(result is AbhaEnrolResult.Error)
        assertFalse((result as AbhaEnrolResult.Error).retryable)
        assertFalse(source.startCalled)
        assertFalse(source.submitIdentityCalled)
    }

    @Test
    fun `non-digit aadhaar is rejected without calling the backend`() = runTest {
        val source = FakeAbdmAbhaSource()

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = "12345678901a", consentGiven = true)

        assertTrue(result is AbhaEnrolResult.Error)
        assertFalse(source.submitIdentityCalled)
    }

    @Test
    fun `consented valid aadhaar starts a session then submits identity with that exact number`() = runTest {
        val source = FakeAbdmAbhaSource()

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = true)

        assertTrue(source.startCalled)
        assertTrue(source.submitIdentityCalled)
        assertEquals(validAadhaar, source.submitIdentityAadhaar)
        val requested = (result as AbhaEnrolResult.Success).data
        assertEquals("session-1", requested.sessionId)
    }

    @Test
    fun `masked mobile from submitIdentity passes through unmodified`() = runTest {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-9", state = AbhaTransactionState.OTP_REQUESTED, maskedMobile = "XXXXXX3210"),
            ),
        )

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = true)

        assertEquals("XXXXXX3210", (result as AbhaEnrolResult.Success).data.maskedMobile)
    }

    @Test
    fun `null masked mobile from submitIdentity passes through as null, not fabricated`() = runTest {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-9", state = AbhaTransactionState.OTP_REQUESTED, maskedMobile = null),
            ),
        )

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = true)

        assertNull((result as AbhaEnrolResult.Success).data.maskedMobile)
    }

    @Test
    fun `backend unreachable on startRegistrationSession is retryable and never falls back to a fabricated session`() = runTest {
        val source = FakeAbdmAbhaSource(startResult = AbhaApiResult.Failure(code = null, message = "Unable to resolve host"))

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = true)

        val error = result as AbhaEnrolResult.Error
        assertTrue(error.retryable)
        // The raw exception text must never reach this result.
        assertFalse(error.message.contains("resolve host"))
        assertFalse(source.submitIdentityCalled)
    }

    @Test
    fun `backend-signalled failure on submitIdentity is not retryable and is not swallowed`() = runTest {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.Failure(code = "SAMD-ABHA-2006", message = "upstream rejected"),
        )

        val result = RequestAbhaOtpUseCase(source)(aadhaarNumber = validAadhaar, consentGiven = true)

        val error = result as AbhaEnrolResult.Error
        assertFalse(error.retryable)
        assertFalse(error.message.contains("upstream rejected"))
    }
}
