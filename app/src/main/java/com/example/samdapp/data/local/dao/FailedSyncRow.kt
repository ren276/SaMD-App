package com.example.samdapp.data.local.dao

import com.example.samdapp.domain.model.SyncState
import java.time.Instant

/**
 * One `FAILED` or `CONFLICT` outbox row, projected down to what a worker-facing list needs: which record it is,
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
 *
 * **[syncState] is projected so the cause is classified on it first.** A CONFLICT row carries no
 * error code (a conflict ack has none), and classifying it by code alone would read it as
 * unrecognised and offer a "Send again" that the FAILED-only requeue guard turns into a no-op.
 * Non-null on purpose: a projection alias mismatch on a non-null field fails the build, where a
 * nullable one only warns.
 */
data class FailedSyncRow(
    val tableName: String,
    val syncState: SyncState,
    val recordId: String,
    val patientId: String?,
    val recordedAt: Instant,
    val syncErrorCode: String?,
    val syncErrorMessage: String?,
    /** What [com.example.samdapp.data.sync.RoomSyncOutboxRepository] needs to fold a record under
     *  the ancestor that holds it: whether the server has ever held this row, and its ancestors'
     *  ids (null where the table has no such ancestor). The ancestor columns are nullable, so a
     *  misspelt alias only warns; `FailedSyncReviewQueryTest` pins them. */
    val serverVersion: Int?,
    val encounterId: String?,
    val caseRecordId: String?,
    /** The consultation an attachment belongs to, or the prescription a medication line belongs to. */
    val parentId: String?,
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
