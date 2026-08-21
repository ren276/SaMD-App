package com.example.samdapp.domain.abha

/**
 * What the ABHA create-flow use cases hand the UI. Deliberately NOT [AbhaApiResult] and NOT
 * [kotlin.Result]: both carry text the UI must never render. [AbhaApiResult.Failure.message] is
 * either the backend's RFC 9457 `detail` (which for `SAMD-ABHA-2006` explicitly preserves the raw
 * ABDM upstream error string, api-contract.md section 9.1) or, on a connectivity failure, the raw
 * `IOException.message`. Neither is a sentence a health worker should see. This type carries a
 * curated message instead, produced only by [toEnrolResult].
 *
 * [retryable] is the transport-versus-decision split: true means "the request never landed, or the
 * far side timed out, press the button again", false means "the backend reached a decision and
 * said no, pressing again changes nothing". It is NOT a licence to fabricate anything. Nothing in
 * this flow falls back to a mock on any failure, retryable or not, which is the whole reason
 * [AbhaApiResult]'s own KDoc warns about that pattern: a fabricated ABHA identity is an
 * identity-fraud hazard, unlike a fabricated ML confidence.
 */
sealed interface AbhaEnrolResult<out T> {
    data class Success<T>(val data: T) : AbhaEnrolResult<T>
    data class Error(val message: String, val retryable: Boolean) : AbhaEnrolResult<Nothing>
}

internal inline fun <T, R> AbhaEnrolResult<T>.map(transform: (T) -> R): AbhaEnrolResult<R> = when (this) {
    is AbhaEnrolResult.Success -> AbhaEnrolResult.Success(transform(data))
    is AbhaEnrolResult.Error -> this
}

internal inline fun <T, R> AbhaEnrolResult<T>.flatMap(transform: (T) -> AbhaEnrolResult<R>): AbhaEnrolResult<R> =
    when (this) {
        is AbhaEnrolResult.Success -> transform(data)
        is AbhaEnrolResult.Error -> this
    }

/**
 * The one place an [AbhaApiResult] becomes something the UI may render. Three-way, matching
 * [AbhaApiResult]'s own three cases and keeping them distinct:
 *
 * - [AbhaApiResult.Failure] with `code == null`: the backend was never reached. Retryable, and the
 *   message is a fixed offline string, never the underlying exception text.
 * - [AbhaApiResult.Failure] with a `SAMD-ABHA-2xxx` code: the backend reached a decision. Mapped
 *   to a curated sentence per [messageForCode]; the backend's own `detail` is discarded rather
 *   than shown, so an upstream ABDM string can never surface on a patient-facing screen.
 * - [AbhaApiResult.ProtocolViolation]: contract drift on a live backend. Kept distinct from the
 *   offline case exactly as that type's KDoc requires, so it is never treated as "just offline".
 */
internal fun <T> AbhaApiResult<T>.toEnrolResult(): AbhaEnrolResult<T> = when (this) {
    is AbhaApiResult.Success -> AbhaEnrolResult.Success(data)

    is AbhaApiResult.ProtocolViolation -> AbhaEnrolResult.Error(
        message = "The server sent a response this app did not understand. Report this to support.",
        retryable = false,
    )

    is AbhaApiResult.Failure -> if (code == null) {
        AbhaEnrolResult.Error(
            message = "No connection to the SaMD server. Check the network and try again.",
            retryable = true,
        )
    } else {
        AbhaEnrolResult.Error(message = messageForCode(code), retryable = code == ABHA_UPSTREAM_TIMEOUT)
    }
}

/** `SAMD-ABHA-2007`, a 504 from ABDM itself. The only backend-signalled code worth another press:
 *  an upstream timeout is a transport outcome wearing an error code, not a decision about this
 *  Aadhaar number. Every other 2xxx code is a decision, and retrying it changes nothing. */
private const val ABHA_UPSTREAM_TIMEOUT = "SAMD-ABHA-2007"

/** Curated, worker-facing text for each code in api-contract.md section 9.1. An unrecognized code
 *  gets the generic line rather than the backend's `detail`: a code this app has never heard of is
 *  precisely the case where the accompanying text is least likely to be safe to render. */
private fun messageForCode(code: String): String = when (code) {
    "SAMD-ABHA-2001" -> "This ABHA registration was not found. Start again from the Aadhaar step."
    "SAMD-ABHA-2002" -> "This step is out of order for this registration. Start again from the Aadhaar step."
    "SAMD-ABHA-2003" -> "This registration timed out. Start again from the Aadhaar step."
    "SAMD-ABHA-2004" -> "That OTP is incorrect. Check the code and enter it again."
    "SAMD-ABHA-2005" -> "That OTP has expired. Start again from the Aadhaar step to get a new one."
    "SAMD-ABHA-2006" -> "ABDM rejected this request. Check the Aadhaar number and start again."
    ABHA_UPSTREAM_TIMEOUT -> "ABDM did not respond in time. Try again."
    "SAMD-ABHA-2008" -> "ABHA creation is not available on this installation. Register without ABHA."
    "SAMD-ABHA-2009" -> "This ABHA number is already linked to a different patient. Do not merge; ask your supervisor."
    else -> "The ABHA request could not be completed. Try again, or register without ABHA."
}
