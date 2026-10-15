package com.example.samdapp.data.remote

import java.io.IOException

/** Outcome of one [RetrofitAuthService] call. [Failure.code] is a `SAMD-*` code from
 *  api-contract.md §9.1 (null if the body could not be parsed at all, for example on a
 *  connectivity failure surfaced as [Failure] rather than a thrown exception by the caller). */
sealed interface AuthApiResult<out T> {
    data class Success<T>(val data: T) : AuthApiResult<T>
    /** [httpStatus] is the server's status when it answered, null when it did not. [cause] is the
     *  transport exception when the request never got an answer; the refresh path rethrows it
     *  rather than ending the session (memo 11.5, Q10). */
    data class Failure(
        val code: String?,
        val message: String,
        val httpStatus: Int? = null,
        val cause: IOException? = null,
    ) : AuthApiResult<Nothing>
}
