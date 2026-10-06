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
)

/**
 * Folds the records of a patient the server will not accept under that patient's own row.
 *
 * "Will not accept" is a patient row whose cause needs a person ([SyncFailureAction.TELL_SUPERVISOR]):
 * a duplicate ABHA, a conflict, a refused or oversize record. Such a patient never lands, so its
 * children fail their foreign key, exhaust their retries and, listed one by one, each offered a
 * "Send again" that cannot work. They are removed from the list and counted on the patient's row
 * instead. A patient whose own cause is retryable keeps its children's rows: a press may still
 * land them once the patient lands.
 */
fun foldUnderNotAcceptedPatients(records: List<FailedSyncRecord>): List<FailedSyncRecord> {
    val notAccepted = records
        .filter { it.table == PATIENTS_TABLE && it.reason.action == SyncFailureAction.TELL_SUPERVISOR }
        .map { it.recordId }
        .toSet()
    if (notAccepted.isEmpty()) return records
    val (held, kept) = records.partition { it.table != PATIENTS_TABLE && it.patientId in notAccepted }
    val heldPerPatient = held.groupingBy { it.patientId }.eachCount()
    return kept.map { record ->
        if (record.table == PATIENTS_TABLE) record.copy(heldRecordCount = heldPerPatient[record.recordId] ?: 0) else record
    }
}

private const val PATIENTS_TABLE = "patients"
