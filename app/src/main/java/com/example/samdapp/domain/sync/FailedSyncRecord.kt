package com.example.samdapp.domain.sync

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
)
