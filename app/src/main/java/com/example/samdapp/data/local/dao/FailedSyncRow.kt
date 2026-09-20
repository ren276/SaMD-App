package com.example.samdapp.data.local.dao

import java.time.Instant

/**
 * One `FAILED` outbox row, projected down to what a worker-facing list needs: which record it is,
 * whose it is, when it was written, and the two columns S-2 persisted and nothing read.
 *
 * Every syncable table's `getFailedForReview()` returns this same shape, so
 * [com.example.samdapp.data.sync.RoomSyncOutboxRepository] can concatenate twenty results without
 * twenty mappings. [tableName] is a literal in each query rather than a Kotlin-side constant,
 * because the string must be the one `requeueFailed` dispatches on and a literal in the same
 * statement cannot drift from the table it selects from.
 *
 * **[patientId] is resolved in SQL, one hop at most.** Fourteen tables carry it; five reach it
 * through their parent (`attachments` via its consultation, the three report tables via their
 * case record, `medication_lines` via its prescription); `abha_profiles` has no patient at all
 * and returns null. A null here is not an error, it is a row whose label falls back to the record
 * type alone.
 *
 * **[recordedAt] is `localModifiedAt`, not `lastSyncAttemptAt`.** A worker recognises the day the
 * visit happened. The day the phone last tried to send it means nothing to them.
 */
data class FailedSyncRow(
    val tableName: String,
    val recordId: String,
    val patientId: String?,
    val recordedAt: Instant,
    val syncErrorCode: String?,
    val syncErrorMessage: String?,
)

/**
 * A patient's display name, looked up in one batch for a whole page of [FailedSyncRow]s.
 *
 * One query for N rows rather than one per row: the failed list is opened by a worker who is
 * mid-consultation, and this runs on the same SQLCipher pool the perf audit found already
 * contended (F2A-01). Narrow on purpose, `id` and `fullName` only, so it cannot become a way to
 * pull the patient table: the caller must already hold the ids, which come from FAILED rows.
 */
data class PatientNameRow(val id: String, val fullName: String)
