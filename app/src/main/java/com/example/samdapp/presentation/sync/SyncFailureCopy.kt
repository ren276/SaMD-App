package com.example.samdapp.presentation.sync

import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.model.SyncFailureReason

/**
 * The worker-facing words for a failed sync, as string resource ids.
 *
 * Separate from the composables that render them so both mappings are testable without Compose,
 * and so the totality that matters can be asserted: every [SyncFailureReason] has copy, and
 * every syncable table has a noun. A `when` with no `else` over the enum makes the first a
 * compile error rather than a test failure; the table map needs the test, because a table name
 * is a string.
 *
 * Follows S-4's `kernel_failure_*` precedent in using `strings.xml` at all. Two features now sit
 * on that side of the convention fork and everything else in the app still uses inline
 * constants; the fork is still undecided.
 */
@get:StringRes
val SyncFailureReason.titleRes: Int
    get() = when (this) {
        SyncFailureReason.DUPLICATE_RECORD -> R.string.failed_sync_duplicate_title
        SyncFailureReason.RECORD_REJECTED -> R.string.failed_sync_rejected_title
        SyncFailureReason.RETRIES_EXHAUSTED -> R.string.failed_sync_retries_exhausted_title
        SyncFailureReason.RECORD_TOO_LARGE -> R.string.failed_sync_too_large_title
        SyncFailureReason.UNRECOGNISED -> R.string.failed_sync_unrecognised_title
        SyncFailureReason.CONFLICT_ON_SERVER -> R.string.failed_sync_conflict_title
    }

@get:StringRes
val SyncFailureReason.bodyRes: Int
    get() = when (this) {
        SyncFailureReason.DUPLICATE_RECORD -> R.string.failed_sync_duplicate_body
        SyncFailureReason.RECORD_REJECTED -> R.string.failed_sync_rejected_body
        SyncFailureReason.RETRIES_EXHAUSTED -> R.string.failed_sync_retries_exhausted_body
        SyncFailureReason.RECORD_TOO_LARGE -> R.string.failed_sync_too_large_body
        SyncFailureReason.UNRECOGNISED -> R.string.failed_sync_unrecognised_body
        SyncFailureReason.CONFLICT_ON_SERVER -> R.string.failed_sync_conflict_body
    }

/**
 * The twenty syncable table names, collapsed onto the seven nouns a worker actually uses.
 *
 * A worker does not distinguish an allergy row from a family-history row; both are "part of this
 * patient's notes". Twenty labels would be twenty strings saying five things. This is the same
 * collapse S-4 made for `SAMD-KERN-5001/5002/5004/5006/5007`, and it is design for the same
 * reason: the distinction is kept where it is useful (the table name is still carried on the
 * record, and is what the requeue dispatches on) and dropped where it is not.
 *
 * An unrecognised table falls back rather than throwing. The list exists to tell a worker about
 * a record that failed; a table this build does not know is still a record that failed, and
 * blanking the row would hide it.
 */
@StringRes
fun recordTypeLabelFor(table: String): Int = when (table) {
    "patients", "abha_profiles" -> R.string.sync_record_type_patient
    "encounters", "consultations", "case_records", "attachments", "observations", "ailments" ->
        R.string.sync_record_type_visit
    "medical_history_items", "allergies", "family_history_entries", "social_histories", "medication_entries" ->
        R.string.sync_record_type_history
    "kernel_reports", "evaluate_reports", "diagnosis_feedback" -> R.string.sync_record_type_assessment
    "prescriptions", "medication_lines" -> R.string.sync_record_type_prescription
    "referrals" -> R.string.sync_record_type_referral
    "audit_log" -> R.string.sync_record_type_activity
    else -> R.string.sync_record_type_other
}
