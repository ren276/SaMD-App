package com.example.samdapp.domain.audit

import java.time.Instant

/** Read-side view of one audit row — the Profile tab's summary, not a general query surface.
 *  [payload] omitted deliberately: it's the raw JSON blob, not needed for a summary line. */
data class AuditLogEntry(
    val id: String,
    val timestamp: Instant,
    val action: String,
    val patientId: String?,
    val caseRecordId: String?,
)
