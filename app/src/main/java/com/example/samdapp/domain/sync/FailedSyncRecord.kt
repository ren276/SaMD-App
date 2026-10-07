package com.example.samdapp.domain.sync

import com.example.samdapp.domain.model.SyncFailureAction
import com.example.samdapp.domain.model.SyncFailureReason
import java.time.Instant

/**
 * One record that did not reach the server and has stopped trying, as a worker needs to see it.
 *
 * S-2 left the outbox able to distinguish four terminal causes and nothing able to say so. This
 * is the shape that carries one of them to a screen: whose record it is, when it was written,
 * what went wrong in a form the UI can turn into worker-facing words, and (through
 * [SyncFailureReason.action]) the one thing to offer.
 *
 * [patientName] is null for a record with no patient (`abha_profiles`) or whose patient row is
 * not on this device. The row still renders: its label falls back to the record type alone,
 * which is worse but is not a blank.
 *
 * [table] and [recordId] are the outbox's own identifiers, carried so
 * [SyncStatus.sendFailedRecordAgain] can dispatch the requeue. They are never shown.
 */
data class FailedSyncRecord(
    val table: String,
    val recordId: String,
    val patientName: String?,
    val recordedAt: Instant,
    val reason: SyncFailureReason,
    /** The patient this record belongs to, when there is one. Never shown; it is what
     *  [foldUnderNotAcceptedPatients] groups on. */
    val patientId: String? = null,
    /** On a patient's own row: how many of its records are folded under it, see
     *  [foldUnderNotAcceptedPatients]. Zero everywhere else. */
    val heldRecordCount: Int = 0,
    /** This row's ancestors as (table, id), nearest-to-the-patient first: patient, encounter,
     *  case record, then the consultation or prescription. What [foldHeldRecords] looks a holder up by. */
    val ancestors: List<Pair<String, String>> = emptyList(),
    /** FAILED and never held by the server: the row that holds its descendants. */
    val holdsDescendants: Boolean = false,
    /** A PENDING or RETRYABLE row held behind such an ancestor. Never shown on its own. */
    val held: Boolean = false,
)

/**
 * Folds each record under the ancestor that holds it, so the review list shows the cause once.
 *
 * A holder is a FAILED row the server has never held ([FailedSyncRecord.holdsDescendants]: a patient,
 * encounter, case record, consultation or prescription). A CONFLICT row, or a FAILED one the server
 * already holds, holds nothing: the server has the row, so its descendants can still land (operator
 * ruling Q2), and the SQL that excludes held rows from the drain agrees.
 * A record folds under its first ancestor that is a holder (patient before encounter before case)
 * when it is held (PENDING or RETRYABLE behind that ancestor, so it can never send), or when the
 * holder's cause needs a person (a child that already exhausted its retries against it, whose
 * "Send again" cannot work). A FAILED child of a holder whose cause is retryable keeps its row: a
 * press may still land it once the holder lands.
 *
 * A held row with no holder in the list is dropped, not shown: it has nothing to say on its own.
 * Folded rows are counted on the holder as [FailedSyncRecord.heldRecordCount], once each.
 */
fun foldHeldRecords(records: List<FailedSyncRecord>): List<FailedSyncRecord> {
    val holders = records.filter { it.holdsDescendants }.associateBy { it.table to it.recordId }
    fun holderOf(record: FailedSyncRecord): FailedSyncRecord? {
        val holder = record.ancestors.firstNotNullOfOrNull { holders[it] } ?: return null
        val folds = record.held || holder.reason.action == SyncFailureAction.TELL_SUPERVISOR
        return holder.takeIf { folds && it !== record }
    }
    val heldPerHolder = mutableMapOf<Pair<String, String>, Int>()
    val kept = mutableListOf<FailedSyncRecord>()
    for (record in records) {
        val holder = holderOf(record)
        when {
            holder != null -> heldPerHolder.merge(holder.table to holder.recordId, 1, Int::plus)
            record.held -> Unit
            else -> kept += record
        }
    }
    return kept.map { it.copy(heldRecordCount = heldPerHolder[it.table to it.recordId] ?: 0) }
}

