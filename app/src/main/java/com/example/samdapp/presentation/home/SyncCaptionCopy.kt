package com.example.samdapp.presentation.home

import android.content.res.Resources
import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.sync.DrainFailure

/** The words for each [SyncCaption]. Takes [Resources] rather than being a composable so the
 *  caption and the "Sync now" snackbar resolve strings the same way. */
fun SyncCaption.text(resources: Resources): String = when (this) {
    SyncCaption.Sending -> resources.getString(R.string.sync_caption_sending)
    is SyncCaption.OfflineWaiting -> when {
        records > 0 && doctorCases > 0 ->
            resources.getString(R.string.sync_caption_offline_records_and_doctor_cases, records, doctorCases)
        records > 0 -> resources.getQuantityString(R.plurals.sync_caption_offline_records, records, records)
        else -> resources.getQuantityString(R.plurals.sync_caption_offline_doctor_cases, doctorCases, doctorCases)
    }
    SyncCaption.OfflineActivityLog -> resources.getString(R.string.sync_caption_offline_activity_log)
    SyncCaption.OfflineNothingElse -> resources.getString(R.string.sync_caption_offline_nothing_else)
    SyncCaption.OfflineAllSent -> resources.getString(R.string.sync_caption_offline_all_sent)
    is SyncCaption.CouldNotSend -> when (failure) {
        // No count: the records are this phone's, held back on purpose, not "waiting".
        DrainFailure.OWNER_MISMATCH -> resources.getString(R.string.sync_caption_owner_mismatch)
        DrainFailure.NO_CONNECTION -> resources.getString(R.string.sync_caption_could_not_send_no_connection, waiting)
        DrainFailure.SIGN_IN -> resources.getString(R.string.sync_caption_could_not_send_sign_in, waiting)
        DrainFailure.SERVER_REFUSED -> resources.getString(R.string.sync_caption_could_not_send_server_refused, waiting)
        DrainFailure.LOCAL_STORE -> resources.getString(R.string.sync_caption_could_not_send_local_store, waiting)
        DrainFailure.LOCAL_NETWORK_NOT_ALLOWED ->
            resources.getString(R.string.sync_caption_could_not_send_local_network, waiting)
    }
    is SyncCaption.Waiting ->
        if (doctorCases > 0) {
            resources.getString(R.string.sync_caption_waiting_records_and_doctor_cases, records, doctorCases)
        } else {
            resources.getQuantityString(R.plurals.sync_caption_waiting_records, records, records)
        }
    is SyncCaption.DoctorCases -> resources.getQuantityString(R.plurals.sync_caption_doctor_cases, count, count)
    SyncCaption.ActivityLogWaiting -> resources.getString(R.string.sync_caption_activity_log)
    is SyncCaption.NeedsReview -> resources.getQuantityString(R.plurals.sync_caption_needs_review, count, count)
    is SyncCaption.NeedsReviewWithHeld -> resources.getString(
        R.string.sync_caption_needs_review_with_held,
        resources.getQuantityString(R.plurals.sync_caption_needs_review_count, review, review),
        resources.getQuantityString(R.plurals.sync_caption_held_count, held, held),
    )
    SyncCaption.UpToDate -> resources.getString(R.string.sync_caption_up_to_date)
}

fun SyncNowMessage.text(resources: Resources): String = when (this) {
    SyncNowMessage.Offline -> resources.getString(R.string.sync_now_offline)
    SyncNowMessage.StillTrying -> resources.getString(R.string.sync_now_still_trying)
    is SyncNowMessage.Failed -> resources.getString(
        when (failure) {
            DrainFailure.NO_CONNECTION -> R.string.sync_now_failed_no_connection
            DrainFailure.SIGN_IN -> R.string.sync_now_failed_sign_in
            DrainFailure.SERVER_REFUSED -> R.string.sync_now_failed_server_refused
            DrainFailure.LOCAL_STORE -> R.string.sync_now_failed_local_store
            DrainFailure.OWNER_MISMATCH -> R.string.sync_caption_owner_mismatch
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED -> R.string.sync_now_failed_local_network
        },
    )
    is SyncNowMessage.SentNeedsReview -> resources.getQuantityString(R.plurals.sync_now_sent_needs_review, count, count)
    is SyncNowMessage.SentRetryLater -> resources.getQuantityString(R.plurals.sync_now_sent_retry_later, count, count)
    SyncNowMessage.AllSent -> resources.getString(R.string.sync_now_all_sent)
}

/** The Home caption for a failed drain, as a string id so the mapping is testable without
 *  [Resources]. Takes the waiting count as its one format argument, except
 *  [DrainFailure.OWNER_MISMATCH], which takes none. */
@get:StringRes
val DrainFailure.captionRes: Int
    get() = when (this) {
        DrainFailure.NO_CONNECTION -> R.string.sync_caption_could_not_send_no_connection
        DrainFailure.SIGN_IN -> R.string.sync_caption_could_not_send_sign_in
        DrainFailure.SERVER_REFUSED -> R.string.sync_caption_could_not_send_server_refused
        DrainFailure.LOCAL_STORE -> R.string.sync_caption_could_not_send_local_store
        DrainFailure.OWNER_MISMATCH -> R.string.sync_caption_owner_mismatch
        DrainFailure.LOCAL_NETWORK_NOT_ALLOWED -> R.string.sync_caption_could_not_send_local_network
    }

/** The "Sync now" snackbar for a failed drain. */
@get:StringRes
val DrainFailure.syncNowFailedRes: Int
    get() = when (this) {
        DrainFailure.NO_CONNECTION -> R.string.sync_now_failed_no_connection
        DrainFailure.SIGN_IN -> R.string.sync_now_failed_sign_in
        DrainFailure.SERVER_REFUSED -> R.string.sync_now_failed_server_refused
        DrainFailure.LOCAL_STORE -> R.string.sync_now_failed_local_store
        DrainFailure.OWNER_MISMATCH -> R.string.sync_caption_owner_mismatch
        DrainFailure.LOCAL_NETWORK_NOT_ALLOWED -> R.string.sync_now_failed_local_network
    }
