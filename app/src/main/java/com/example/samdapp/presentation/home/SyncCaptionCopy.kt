package com.example.samdapp.presentation.home

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.sync.DrainFailure

/** A string or plural plus its format arguments, before [Resources] turns it into text. Pure, so
 *  the caption and the "Sync now" snackbar are JVM-testable on the same function the screen calls.
 *  A [CopyRef] among [args] is resolved first (the needs-review caption nests two plurals). */
sealed interface CopyRef {
    data class Str(@StringRes val res: Int, val args: List<Any> = emptyList()) : CopyRef

    /** The count is also the one format argument, as every plural here reads "%1$d ...". */
    data class Plural(@PluralsRes val res: Int, val count: Int) : CopyRef
}

/** The two Android calls [CopyRef.resolve] needs, so it is JVM-testable with a fake. */
interface CopyText {
    fun string(@StringRes res: Int, args: List<Any>): String
    fun plural(@PluralsRes res: Int, count: Int, args: List<Any>): String
}

/** Delegates to [Resources] and nothing else. A string with no arguments takes the plain
 *  `getString(res)`, which does not run `String.format` over the text. */
class ResourcesCopyText(private val resources: Resources) : CopyText {
    override fun string(res: Int, args: List<Any>): String =
        stringVia(args, plain = { resources.getString(res) }, formatted = { resources.getString(res, *it) })

    override fun plural(res: Int, count: Int, args: List<Any>): String =
        resources.getQuantityString(res, count, *args.toTypedArray())
}

/** Which `getString` overload a string takes, apart from [Resources] so a JVM test can see it. */
internal fun stringVia(args: List<Any>, plain: () -> String, formatted: (Array<Any>) -> String): String =
    if (args.isEmpty()) plain() else formatted(args.toTypedArray())

fun CopyRef.resolve(text: CopyText): String = when (this) {
    is CopyRef.Str -> text.string(res, args.map { if (it is CopyRef) it.resolve(text) else it })
    is CopyRef.Plural -> text.plural(res, count, listOf(count))
}

/** The words for each [SyncCaption]. The screen calls this; [copyRef] is what tests pin. */
fun SyncCaption.text(resources: Resources): String = copyRef().resolve(ResourcesCopyText(resources))

/** The "Sync now" snackbar. Same single path as [text] above. */
fun SyncNowMessage.text(resources: Resources): String = copyRef().resolve(ResourcesCopyText(resources))

fun SyncCaption.copyRef(): CopyRef = when (this) {
    SyncCaption.Sending -> CopyRef.Str(R.string.sync_caption_sending)
    is SyncCaption.OfflineWaiting -> when {
        records > 0 && doctorCases > 0 ->
            CopyRef.Str(R.string.sync_caption_offline_records_and_doctor_cases, listOf(records, doctorCases))
        records > 0 -> CopyRef.Plural(R.plurals.sync_caption_offline_records, records)
        else -> CopyRef.Plural(R.plurals.sync_caption_offline_doctor_cases, doctorCases)
    }
    SyncCaption.OfflineActivityLog -> CopyRef.Str(R.string.sync_caption_offline_activity_log)
    SyncCaption.OfflineNothingElse -> CopyRef.Str(R.string.sync_caption_offline_nothing_else)
    SyncCaption.OfflineAllSent -> CopyRef.Str(R.string.sync_caption_offline_all_sent)
    is SyncCaption.CouldNotSend -> when (failure) {
        // No count: the records are this phone's, held back on purpose, not "waiting".
        DrainFailure.OWNER_MISMATCH -> CopyRef.Str(R.string.sync_caption_owner_mismatch)
        DrainFailure.NO_CONNECTION -> CopyRef.Str(R.string.sync_caption_could_not_send_no_connection, listOf(waiting))
        DrainFailure.SIGN_IN -> CopyRef.Str(R.string.sync_caption_could_not_send_sign_in, listOf(waiting))
        DrainFailure.SERVER_REFUSED -> CopyRef.Str(R.string.sync_caption_could_not_send_server_refused, listOf(waiting))
        DrainFailure.LOCAL_STORE -> CopyRef.Str(R.string.sync_caption_could_not_send_local_store, listOf(waiting))
        DrainFailure.LOCAL_NETWORK_NOT_ALLOWED ->
            CopyRef.Str(R.string.sync_caption_could_not_send_local_network, listOf(waiting))
    }
    is SyncCaption.Waiting ->
        if (doctorCases > 0) {
            CopyRef.Str(R.string.sync_caption_waiting_records_and_doctor_cases, listOf(records, doctorCases))
        } else {
            CopyRef.Plural(R.plurals.sync_caption_waiting_records, records)
        }
    is SyncCaption.DoctorCases -> CopyRef.Plural(R.plurals.sync_caption_doctor_cases, count)
    SyncCaption.ActivityLogWaiting -> CopyRef.Str(R.string.sync_caption_activity_log)
    is SyncCaption.NeedsReview -> CopyRef.Plural(R.plurals.sync_caption_needs_review, count)
    is SyncCaption.NeedsReviewWithHeld -> CopyRef.Str(
        R.string.sync_caption_needs_review_with_held,
        listOf(
            CopyRef.Plural(R.plurals.sync_caption_needs_review_count, review),
            CopyRef.Plural(R.plurals.sync_caption_held_count, held),
        ),
    )
    SyncCaption.UpToDate -> CopyRef.Str(R.string.sync_caption_up_to_date)
}

fun SyncNowMessage.copyRef(): CopyRef = when (this) {
    SyncNowMessage.Offline -> CopyRef.Str(R.string.sync_now_offline)
    SyncNowMessage.StillTrying -> CopyRef.Str(R.string.sync_now_still_trying)
    is SyncNowMessage.Failed -> CopyRef.Str(
        when (failure) {
            DrainFailure.NO_CONNECTION -> R.string.sync_now_failed_no_connection
            DrainFailure.SIGN_IN -> R.string.sync_now_failed_sign_in
            DrainFailure.SERVER_REFUSED -> R.string.sync_now_failed_server_refused
            DrainFailure.LOCAL_STORE -> R.string.sync_now_failed_local_store
            DrainFailure.OWNER_MISMATCH -> R.string.sync_caption_owner_mismatch
            DrainFailure.LOCAL_NETWORK_NOT_ALLOWED -> R.string.sync_now_failed_local_network
        },
    )
    is SyncNowMessage.SentNeedsReview -> CopyRef.Plural(R.plurals.sync_now_sent_needs_review, count)
    is SyncNowMessage.SentRetryLater -> CopyRef.Plural(R.plurals.sync_now_sent_retry_later, count)
    SyncNowMessage.AllSent -> CopyRef.Str(R.string.sync_now_all_sent)
}
