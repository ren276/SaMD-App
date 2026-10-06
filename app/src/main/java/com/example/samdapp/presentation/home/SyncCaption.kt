package com.example.samdapp.presentation.home

import com.example.samdapp.domain.sync.DrainFailure
import com.example.samdapp.domain.sync.SyncOfflineException
import com.example.samdapp.domain.sync.SyncState

/**
 * What Home's sync caption says, as data. Pure and Android-free so the truth table is a JVM test;
 * the screen turns each value into a string resource. See [syncCaption].
 */
sealed interface SyncCaption {
    data object Sending : SyncCaption
    data class OfflineWaiting(val records: Int, val doctorCases: Int) : SyncCaption
    data object OfflineActivityLog : SyncCaption
    data object OfflineNothingElse : SyncCaption
    data object OfflineAllSent : SyncCaption
    data class CouldNotSend(val failure: DrainFailure, val waiting: Int) : SyncCaption
    data class Waiting(val records: Int, val doctorCases: Int) : SyncCaption
    data class DoctorCases(val count: Int) : SyncCaption
    data object ActivityLogWaiting : SyncCaption
    data class NeedsReview(val count: Int) : SyncCaption
    data object UpToDate : SyncCaption
}

/**
 * Home's caption truth table, evaluated top to bottom; the first row that matches wins.
 *
 * "Up to date" is reachable only when nothing is pending (records, doctor cases, activity log),
 * nothing needs review, nothing is blocked, and the phone is online. Before this, the caption read
 * only the doctor-assignment queue ([SyncState.pendingCount]) and said "Up to date" over a blocked
 * or failing outbox. The Home card for records needing review shows independently of the caption.
 *
 * A [SyncState.lastDrainFailure] left over with nothing queued is ignored: nothing can be blocked
 * when nothing is waiting.
 */
fun syncCaption(state: SyncState, isOnline: Boolean): SyncCaption {
    val records = state.outboxPending
    val doctorCases = state.pendingCount
    val audit = state.auditPending
    val review = state.failedCount
    val failure = state.lastDrainFailure
    return when {
        state.isSyncing -> SyncCaption.Sending
        !isOnline && (records > 0 || doctorCases > 0) -> SyncCaption.OfflineWaiting(records, doctorCases)
        !isOnline && audit > 0 -> SyncCaption.OfflineActivityLog
        !isOnline && review > 0 -> SyncCaption.OfflineNothingElse
        !isOnline -> SyncCaption.OfflineAllSent
        failure != null && records + audit > 0 -> SyncCaption.CouldNotSend(failure, records + audit)
        records > 0 -> SyncCaption.Waiting(records, doctorCases)
        doctorCases > 0 -> SyncCaption.DoctorCases(doctorCases)
        audit > 0 -> SyncCaption.ActivityLogWaiting
        review > 0 -> SyncCaption.NeedsReview(review)
        else -> SyncCaption.UpToDate
    }
}

/** The one message shown after the worker presses "Sync now". See [syncNowMessage]. */
sealed interface SyncNowMessage {
    data object Offline : SyncNowMessage
    data object StillTrying : SyncNowMessage
    data class Failed(val failure: DrainFailure) : SyncNowMessage
    data class SentNeedsReview(val count: Int) : SyncNowMessage
    data class SentRetryLater(val count: Int) : SyncNowMessage
    data object AllSent : SyncNowMessage
}

/**
 * The "Sync now" result, chosen from the Result and the state it left behind. A success Result
 * means only that the drain ran to the end without a transport failure: per-record rejections
 * still return success, so success is never on its own read as "everything is sent".
 *
 * A failure with no recorded drain failure is a sync that was enqueued and timed out waiting (or
 * a worker that is retrying): the request is still queued, so "still trying" is the true message.
 */
fun syncNowMessage(result: Result<Unit>, state: SyncState): SyncNowMessage = result.fold(
    onSuccess = {
        when {
            state.failedCount > 0 -> SyncNowMessage.SentNeedsReview(state.failedCount)
            state.outboxPending > 0 -> SyncNowMessage.SentRetryLater(state.outboxPending)
            else -> SyncNowMessage.AllSent
        }
    },
    onFailure = { error ->
        when {
            error is SyncOfflineException -> SyncNowMessage.Offline
            state.lastDrainFailure != null -> SyncNowMessage.Failed(state.lastDrainFailure)
            else -> SyncNowMessage.StillTrying
        }
    },
)
