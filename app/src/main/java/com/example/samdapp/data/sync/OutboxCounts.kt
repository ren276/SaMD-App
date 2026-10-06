package com.example.samdapp.data.sync

import com.example.samdapp.data.local.dao.SyncStateCount
import com.example.samdapp.domain.model.SyncState

/**
 * The three outbox numbers Home reads, folded from every table's grouped state counts.
 *
 * - [pendingClinical]: PENDING plus RETRYABLE rows across the nineteen clinical tables. Rows the
 *   device still owes the server and is still working on.
 * - [pendingAudit]: the same for `audit_log`, kept apart because an audit-only backlog is shown
 *   as "The activity log is waiting to send", not as a record count (operator ruling H4).
 * - [needsReview]: FAILED plus CONFLICT across all twenty tables. Rows nothing will resend on its
 *   own, which is the Home card's number and the length of the list it opens.
 */
data class OutboxCounts(
    val pendingClinical: Int = 0,
    val pendingAudit: Int = 0,
    val needsReview: Int = 0,
)

private val PENDING_STATES = setOf(SyncState.PENDING, SyncState.RETRYABLE)
private val REVIEW_STATES = setOf(SyncState.FAILED, SyncState.CONFLICT)

private fun List<SyncStateCount>.sumOf(states: Set<SyncState>) =
    filter { it.syncState in states }.sumOf { it.rowCount }

fun outboxCountsOf(clinical: List<SyncStateCount>, audit: List<SyncStateCount>): OutboxCounts = OutboxCounts(
    pendingClinical = clinical.sumOf(PENDING_STATES),
    pendingAudit = audit.sumOf(PENDING_STATES),
    needsReview = clinical.sumOf(REVIEW_STATES) + audit.sumOf(REVIEW_STATES),
)
