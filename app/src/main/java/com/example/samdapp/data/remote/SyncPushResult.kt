package com.example.samdapp.data.remote

/** Outcome of one [SyncPushService] call. [Failure.code] is a `SAMD-*` code from a whole-batch
 *  RFC 9457 error (413/422/403/etc, api-contract.md §6.1) — null on a connectivity failure. A
 *  per-record `rejected` result is NOT a [Failure]: the batch itself still returns [Success], see
 *  SyncOutboxDrainer's ack handling. */
sealed interface SyncPushResult<out T> {
    data class Success<T>(val data: T) : SyncPushResult<T>
    /** [cause] is the transport exception when the batch never reached the server, kept so the
     *  drain can tell a blocked local network apart from a dropped connection. */
    data class Failure(val code: String?, val message: String, val cause: Throwable? = null) : SyncPushResult<Nothing>
}
