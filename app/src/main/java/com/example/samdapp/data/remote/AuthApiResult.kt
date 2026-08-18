package com.example.samdapp.data.remote

/** Outcome of one [RetrofitAuthService] call. [Failure.code] is a `SAMD-*` code from
 *  api-contract.md §9.1 (null if the body could not be parsed at all, for example on a
 *  connectivity failure surfaced as [Failure] rather than a thrown exception by the caller). */
sealed interface AuthApiResult<out T> {
    data class Success<T>(val data: T) : AuthApiResult<T>
    data class Failure(val code: String?, val message: String) : AuthApiResult<Nothing>
}
