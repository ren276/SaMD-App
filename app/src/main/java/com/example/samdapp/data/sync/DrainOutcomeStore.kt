package com.example.samdapp.data.sync

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
class SyncPushFailedException(val code: String?, message: String) : IllegalStateException(message)

/** Pure, so the classification is testable without a drain. Any failure that is not a push
 *  failure comes from preparing the batch on this phone (the in-flight store), see
 *  [SyncOutboxDrainer.drain]. */
fun drainFailureFor(error: Throwable): DrainFailure = when (error) {
    is SyncPushFailedException -> when {
        error.code == null -> DrainFailure.NO_CONNECTION
        error.code.startsWith("SAMD-AUTH-") -> DrainFailure.SIGN_IN
        else -> DrainFailure.SERVER_REFUSED
    }
    else -> DrainFailure.LOCAL_STORE
}
