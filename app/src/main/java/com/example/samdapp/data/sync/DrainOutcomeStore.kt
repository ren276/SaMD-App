package com.example.samdapp.data.sync

import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.domain.sync.DrainFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of the most recent outbox drain, shared between [SyncOutboxDrainer] (which writes
 * it after every drain, from the periodic and the one-time worker alike, both in this process)
 * and [SyncStatusImpl] (which shows it on Home). In memory only: after process death it reads
 * null until the next drain, and Home's captions are written to stay true when it is unknown.
 */
@Singleton
class DrainOutcomeStore @Inject constructor() {
    private val _lastFailure = MutableStateFlow<DrainFailure?>(null)
    val lastFailure: StateFlow<DrainFailure?> = _lastFailure.asStateFlow()

    /** Null clears it: the drain succeeded. */
    fun record(failure: DrainFailure?) {
        _lastFailure.value = failure
    }
}

/** A whole-batch push failure, keeping the problem document's [code] so the drain's failure can
 *  be classified. Before this the code was flattened into an exception message and lost. */
class SyncPushFailedException(val code: String?, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The phone has no `device_data_owner` marker, so the drain pushed nothing (facility-scope memo
 *  3.5): a phone nobody has signed in on since the upgrade, or one whose marker is unreadable. */
class NoDataOwnerException : IllegalStateException("no device data owner: nothing was pushed")

/** The server refused the batch because its owner is not the session's facility (FS-6). */
const val SYNC_OWNER_FACILITY_MISMATCH_CODE = "SAMD-SYNC-6007"

/** A drain skipped because an earlier batch was refused with [SYNC_OWNER_FACILITY_MISMATCH_CODE]
 *  and nobody has signed in since ([OwnerMismatchHold]). */
class OwnerMismatchHeldException : IllegalStateException("held after an owner-facility refusal: nothing was pushed")

/** Pure, so the classification is testable without a drain. Any failure that is not a push
 *  failure comes from preparing the batch on this phone (the in-flight store, or no owner marker,
 *  [NoDataOwnerException]), see [SyncOutboxDrainer.drain].
 *
 *  [SYNC_OWNER_FACILITY_MISMATCH_CODE] and a drain held after it are [DrainFailure.OWNER_MISMATCH]:
 *  terminal for the batch under this session, with its own message. */
fun drainFailureFor(error: Throwable): DrainFailure = when (error) {
    is SyncPushFailedException -> when {
        error.cause is LocalNetworkNotAllowedException -> DrainFailure.LOCAL_NETWORK_NOT_ALLOWED
        error.code == null -> DrainFailure.NO_CONNECTION
        error.code.startsWith("SAMD-AUTH-") -> DrainFailure.SIGN_IN
        error.code == SYNC_OWNER_FACILITY_MISMATCH_CODE -> DrainFailure.OWNER_MISMATCH
        else -> DrainFailure.SERVER_REFUSED
    }
    is OwnerMismatchHeldException -> DrainFailure.OWNER_MISMATCH
    else -> DrainFailure.LOCAL_STORE
}
