package com.example.samdapp.domain.kernel

/**
 * Outcome of one [RemoteKernelSource.assess] call. The third instance of the house shape already
 * written by [com.example.samdapp.data.remote.AuthApiResult] and
 * [com.example.samdapp.domain.abha.AbhaApiResult]: a sealed result whose failure case carries the
 * `SAMD-*` code lifted out of the backend's RFC 9457 problem document, rather than a thrown
 * `HttpException` whose `message` is only the status line.
 *
 * One case more than [com.example.samdapp.domain.abha.AbhaApiResult] has, and the addition is
 * deliberate. That type folds every connectivity failure into `Failure(code = null)`, which is
 * enough for the ABHA flow because its copy says the same thing for all of them ("No connection
 * to the SaMD server"). The kernel flow must tell [KernelFailure.OFFLINE],
 * [KernelFailure.TIMEOUT] and [KernelFailure.SECURE_CONNECTION_FAILED] apart, because their
 * worker-facing advice differs (wait, press again, escalate), so the transport exception survives
 * to the classifier in [Unreachable] instead of being flattened into a null code.
 *
 * `assess` returns this instead of throwing so that the exhaustive `when` in
 * [classifyKernelFailure] is the compiler's problem, not a reviewer's. Adding a case here fails
 * the build at every call site until it is handled, which is the structural guard against this
 * path collapsing back onto one outcome.
 */
sealed interface KernelApiResult<out T> {

    data class Success<T>(val data: T) : KernelApiResult<T>

    /** The request never reached `backend/core`. [cause] is an `IOException` subclass and is used
     *  for classification by type only, never rendered: an exception message can quote a host,
     *  a port or the offending value. */
    data class Unreachable(val cause: Throwable) : KernelApiResult<Nothing>

    /** `backend/core` was reached and answered non-2xx. [code] is the problem document's `code`,
     *  or null when the body was absent or not a problem document. [message] is kept for logs
     *  only; the screen renders curated copy chosen from [KernelFailure], never the backend's
     *  `detail`, matching `AbhaEnrolResult`'s rule.
     *
     *  [requestId] and [requestIdUnrecognised] carry the response's `X-Request-ID` exactly as
     *  [KernelAssessmentResult] does: an error response is still a response, and the backend may
     *  hold a stored assessment for it. */
    data class Failure(
        val code: String?,
        val httpStatus: Int,
        val message: String,
        val requestId: String? = null,
        val requestIdUnrecognised: Boolean = false,
    ) : KernelApiResult<Nothing>

    /** `backend/core` was reached, answered 2xx, and the body could not be turned into a
     *  [KernelAssessmentResult]. Named as in [com.example.samdapp.domain.abha.AbhaApiResult] and
     *  kept apart from [Unreachable] for that type's stated reason: a server that answered
     *  unintelligibly is not a server that was absent. */
    data class ProtocolViolation(val message: String) : KernelApiResult<Nothing>
}

/** 404. The case record is not on the server, so there was nothing to assess. api-contract.md
 *  section 9.1. */
private const val CODE_CASE_RECORD_NOT_FOUND = "SAMD-ENC-4002"

/** 422. The kernel rejected the payload, and the H-10 identity guard respectively. */
private val PAYLOAD_REJECTED_CODES = setOf("SAMD-KERN-5003", "SAMD-KERN-5005")

/** 502/503/504. Unreachable, timeout, unparseable kernel response, circuit open, kernel internal
 *  error. Five server-side conditions, one worker action; see [KernelFailure]'s KDoc. */
private val KERNEL_SIDE_CODES = setOf(
    "SAMD-KERN-5001",
    "SAMD-KERN-5002",
    "SAMD-KERN-5004",
    "SAMD-KERN-5006",
    "SAMD-KERN-5007",
)

/**
 * The single place a [KernelApiResult] that is not a [KernelApiResult.Success] becomes a
 * [KernelFailure]. Pure, total and Android-free, so it is unit testable without a device and
 * without a Retrofit stub.
 *
 * Classification prefers the `SAMD-*` code over the HTTP status wherever a code is present,
 * because the code is the contract (`ProblemDetailDto`'s own KDoc: "Android branches on code,
 * never on title or detail"). The status is the fallback for a body that was absent or not a
 * problem document, which is the case `RetrofitAuthService` and `RetrofitAbhaSource` already
 * handle by passing `code = null`.
 */
fun classifyKernelFailure(result: KernelApiResult<*>): KernelFailure = when (result) {
    is KernelApiResult.Success -> error("classifyKernelFailure called on a Success")

    is KernelApiResult.ProtocolViolation -> KernelFailure.MALFORMED_RESPONSE

    // Classified by exception TYPE, never by message. An IOException's message is OEM- and
    // OS-dependent and is not a contract, the same reasoning classifyLocalNetworkFailure records
    // for its own `cause` parameter.
    is KernelApiResult.Unreachable -> when (result.cause) {
        is java.net.SocketTimeoutException -> KernelFailure.TIMEOUT
        is javax.net.ssl.SSLException -> KernelFailure.SECURE_CONNECTION_FAILED
        // UnknownHostException, ConnectException, and every other IOException that means the
        // bytes did not get there. Not TIMEOUT: only a SocketTimeoutException proves the
        // connection was actually established first.
        else -> KernelFailure.OFFLINE
    }

    is KernelApiResult.Failure -> when {
        result.code == CODE_CASE_RECORD_NOT_FOUND -> KernelFailure.CASE_NOT_ON_SERVER
        result.code in PAYLOAD_REJECTED_CODES -> KernelFailure.PAYLOAD_REJECTED
        result.code in KERNEL_SIDE_CODES -> KernelFailure.KERNEL_UNAVAILABLE
        // No code, or one this build does not know. Fall back to the status class, which is
        // still contractual, before giving up to UNKNOWN.
        result.httpStatus == 401 || result.httpStatus == 403 -> KernelFailure.NOT_AUTHORIZED
        result.httpStatus == 404 -> KernelFailure.CASE_NOT_ON_SERVER
        result.httpStatus == 422 -> KernelFailure.PAYLOAD_REJECTED
        result.httpStatus in 502..504 -> KernelFailure.KERNEL_UNAVAILABLE
        else -> KernelFailure.UNKNOWN
    }
}
