package com.example.samdapp.domain.kernel

import com.example.samdapp.R
import com.example.samdapp.presentation.kernelassessment.kernelFailureCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * One case per [KernelFailure], plus the three malformed-input cases the two existing
 * problem-document parsers already handle (absent body, non-problem-document body, unknown code).
 *
 * Assertions are on the [KernelFailure], the [KernelRetryAdvice] and the string RESOURCE ID, never
 * on the rendered English. The English is the part a test cannot check and a person has to read;
 * pinning it here would only mean a reviewer has to edit two places to reword one sentence, and
 * would make the test pass for a sentence nobody re-read.
 */
class KernelFailureClassificationTest {

    private fun failure(code: String?, status: Int) =
        classifyKernelFailure(KernelApiResult.Failure(code = code, httpStatus = status, message = "x"))

    private fun unreachable(cause: Throwable) =
        classifyKernelFailure(KernelApiResult.Unreachable(cause))

    // ── One per failure class ────────────────────────────────────────────────

    @Test
    fun `no route to the backend is OFFLINE and waits for connectivity`() {
        assertEquals(KernelFailure.OFFLINE, unreachable(UnknownHostException("no dns")))
        assertEquals(KernelFailure.OFFLINE, unreachable(ConnectException("refused")))
        assertEquals(KernelRetryAdvice.RETRY_WHEN_CONNECTED, KernelFailure.OFFLINE.advice)
    }

    @Test
    fun `a socket timeout is TIMEOUT and is the one class worth pressing again now`() {
        assertEquals(KernelFailure.TIMEOUT, unreachable(SocketTimeoutException("read timed out")))
        assertEquals(KernelRetryAdvice.RETRY_NOW, KernelFailure.TIMEOUT.advice)
    }

    @Test
    fun `a TLS failure is not treated as offline and asks for escalation`() {
        // SSLException is an IOException, so a type check ordered after the generic IOException
        // branch would silently call this OFFLINE and tell a worker to keep sending patient data
        // over a connection that may be intercepted.
        assertEquals(KernelFailure.SECURE_CONNECTION_FAILED, unreachable(SSLException("bad cert")))
        assertEquals(KernelFailure.SECURE_CONNECTION_FAILED, unreachable(SSLHandshakeException("handshake")))
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.SECURE_CONNECTION_FAILED.advice)
        assertNotEquals(KernelFailure.OFFLINE, unreachable(SSLException("bad cert")))
    }

    @Test
    fun `401 and 403 are NOT_AUTHORIZED`() {
        assertEquals(KernelFailure.NOT_AUTHORIZED, failure(code = null, status = 401))
        assertEquals(KernelFailure.NOT_AUTHORIZED, failure(code = null, status = 403))
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.NOT_AUTHORIZED.advice)
    }

    @Test
    fun `SAMD-ENC-4002 is CASE_NOT_ON_SERVER and is never retryable`() {
        // The duplicate-ABHA terminus. The assessment did not fail; the record never arrived.
        assertEquals(KernelFailure.CASE_NOT_ON_SERVER, failure("SAMD-ENC-4002", 404))
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.CASE_NOT_ON_SERVER.advice)
    }

    @Test
    fun `SAMD-KERN-5003 and 5005 are PAYLOAD_REJECTED and are never retryable`() {
        assertEquals(KernelFailure.PAYLOAD_REJECTED, failure("SAMD-KERN-5003", 422))
        assertEquals(KernelFailure.PAYLOAD_REJECTED, failure("SAMD-KERN-5005", 422))
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.PAYLOAD_REJECTED.advice)
    }

    @Test
    fun `the whole 5001-5002-5004-5006-5007 family is KERNEL_UNAVAILABLE`() {
        listOf(
            "SAMD-KERN-5001" to 502,
            "SAMD-KERN-5002" to 504,
            "SAMD-KERN-5004" to 502,
            "SAMD-KERN-5006" to 503,
            "SAMD-KERN-5007" to 502,
        ).forEach { (code, status) ->
            assertEquals("$code should be KERNEL_UNAVAILABLE", KernelFailure.KERNEL_UNAVAILABLE, failure(code, status))
        }
        assertEquals(KernelRetryAdvice.RETRY_WHEN_CONNECTED, KernelFailure.KERNEL_UNAVAILABLE.advice)
    }

    @Test
    fun `an unintelligible body is MALFORMED_RESPONSE and not OFFLINE`() {
        val result = classifyKernelFailure(KernelApiResult.ProtocolViolation("bad body"))
        assertEquals(KernelFailure.MALFORMED_RESPONSE, result)
        assertNotEquals(KernelFailure.OFFLINE, result)
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.MALFORMED_RESPONSE.advice)
    }

    @Test
    fun `DEVICE_ERROR needs a person and is never presented as a network problem`() {
        // Produced by the use case's catch, not by classifyKernelFailure, so this pins the
        // property that matters: it is not in the wait-and-it-will-fix-itself bucket.
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.DEVICE_ERROR.advice)
    }

    // ── The malformed-input cases the house pattern already handles ──────────

    @Test
    fun `an unknown code falls back to the status class before giving up`() {
        // A code this build has never heard of, on a status that IS contractual, must still
        // classify by the status rather than dropping straight to UNKNOWN.
        assertEquals(KernelFailure.CASE_NOT_ON_SERVER, failure("SAMD-FUTURE-9999", 404))
        assertEquals(KernelFailure.KERNEL_UNAVAILABLE, failure("SAMD-FUTURE-9999", 503))
    }

    @Test
    fun `an unknown code on an unrecognised status is UNKNOWN, never a crash and never silence`() {
        assertEquals(KernelFailure.UNKNOWN, failure("SAMD-FUTURE-9999", 418))
        assertEquals(KernelRetryAdvice.NEEDS_ACTION, KernelFailure.UNKNOWN.advice)
    }

    @Test
    fun `an absent body is a null code and still classifies by status`() {
        // RetrofitKernelSource passes code = null when errorBody() is null. This is the
        // "body that is absent" case RetrofitAuthService and RetrofitAbhaSource already handle.
        assertEquals(KernelFailure.CASE_NOT_ON_SERVER, failure(code = null, status = 404))
        assertEquals(KernelFailure.PAYLOAD_REJECTED, failure(code = null, status = 422))
        assertEquals(KernelFailure.UNKNOWN, failure(code = null, status = 418))
    }

    @Test
    fun `a body that is not a problem document is a null code, same as an absent one`() {
        // Gson returning an object with every field null, which is what parsing `{"oops":1}`
        // into ProblemDetailDto produces, is indistinguishable here from no body at all, and
        // deliberately so: both mean "no contractual code was supplied".
        assertEquals(KernelFailure.UNKNOWN, failure(code = null, status = 500))
    }

    // ── The regression guard ─────────────────────────────────────────────────

    @Test
    fun `the ten failure classes do not collapse onto one outcome`() {
        // The defect this PR undoes: ten typed exception classes reaching one
        // `catch (e: Exception)` and leaving it as a single InferenceSource.UNAVAILABLE with a
        // single piece of advice. Asserted on observable behaviour, not on line numbers: if a
        // future change routes everything back through one bucket, the distinct-count drops.
        val classified = listOf(
            unreachable(UnknownHostException("x")),
            unreachable(SocketTimeoutException("x")),
            unreachable(SSLException("x")),
            failure(null, 401),
            failure("SAMD-ENC-4002", 404),
            failure("SAMD-KERN-5003", 422),
            failure("SAMD-KERN-5001", 502),
            classifyKernelFailure(KernelApiResult.ProtocolViolation("x")),
            failure("SAMD-FUTURE-9999", 418),
        )
        assertEquals("every sampled input must classify distinctly", classified.size, classified.distinct().size)

        // And the advice must actually differ, which is the half that reaches the worker. A
        // vocabulary with ten names that all say "Retry" would pass the line above and still be
        // the bug.
        val advices = KernelFailure.entries.map { it.advice }.distinct()
        assertEquals("all three advice classes must be in use", 3, advices.size)
    }

    @Test
    fun `every failure class has its own copy, and only the non-failure states share it`() {
        // Guards the copy table against a new enum value being added with no string, which would
        // otherwise fail the build, and against two classes being pointed at one string, which
        // would not.
        val byFailure = KernelFailure.entries.associateWith { kernelFailureCopy(it) }
        assertEquals(
            "no two failure classes may share copy",
            KernelFailure.entries.size,
            byFailure.values.distinct().size,
        )
        // The null case (reached-but-empty kernel, or a stalled case) is the only reach-neutral
        // one, and it must not be reachable from any real failure.
        val neutral = kernelFailureCopy(null)
        assertTrue("the reach-neutral copy must not be reused by a classified failure", neutral !in byFailure.values)
    }

    @Test
    fun `the duplicate-ABHA case names the patient record and does not mention the AI`() {
        // The one copy assertion that is on identity rather than English: a 404 must NOT select
        // the "Assessment unavailable / the AI did not produce a result" pair, because the AI is
        // working correctly and the patient was never saved.
        val copy = kernelFailureCopy(KernelFailure.CASE_NOT_ON_SERVER)
        assertEquals(R.string.kernel_failure_case_not_on_server_title, copy.titleRes)
        assertEquals(R.string.kernel_failure_case_not_on_server_body, copy.bodyRes)
        assertNotEquals(R.string.kernel_failure_none_title, copy.titleRes)
        assertNotEquals(R.string.kernel_failure_none_body, copy.bodyRes)
    }

    @Test
    fun `an IOException that is none of the special cases is OFFLINE`() {
        assertEquals(KernelFailure.OFFLINE, unreachable(IOException("something else")))
    }
}
