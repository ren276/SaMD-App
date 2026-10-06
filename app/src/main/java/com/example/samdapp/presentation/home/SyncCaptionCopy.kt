package com.example.samdapp.presentation.home

import android.content.res.Resources
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
    is SyncCaption.CouldNotSend -> resources.getString(
        when (failure) {
            DrainFailure.NO_CONNECTION -> R.string.sync_caption_could_not_send_no_connection
            DrainFailure.SIGN_IN -> R.string.sync_caption_could_not_send_sign_in
            DrainFailure.SERVER_REFUSED -> R.string.sync_caption_could_not_send_server_refused
            DrainFailure.LOCAL_STORE -> R.string.sync_caption_could_not_send_local_store
        },
        waiting,
    )
    is SyncCaption.Waiting ->
        if (doctorCases > 0) {
            resources.getString(R.string.sync_caption_waiting_records_and_doctor_cases, records, doctorCases)
        } else {
            resources.getQuantityString(R.plurals.sync_caption_waiting_records, records, records)
        }
    is SyncCaption.DoctorCases -> resources.getQuantityString(R.plurals.sync_caption_doctor_cases, count, count)
    SyncCaption.ActivityLogWaiting -> resources.getString(R.string.sync_caption_activity_log)
    is SyncCaption.NeedsReview -> resources.getQuantityString(R.plurals.sync_caption_needs_review, count, count)
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
        },
    )
    is SyncNowMessage.SentNeedsReview -> resources.getQuantityString(R.plurals.sync_now_sent_needs_review, count, count)
    is SyncNowMessage.SentRetryLater -> resources.getQuantityString(R.plurals.sync_now_sent_retry_later, count, count)
    SyncNowMessage.AllSent -> resources.getString(R.string.sync_now_all_sent)
}
