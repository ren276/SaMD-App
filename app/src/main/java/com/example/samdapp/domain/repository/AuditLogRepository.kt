package com.example.samdapp.domain.repository

import com.example.samdapp.domain.audit.AuditLogEntry
import kotlinx.coroutines.flow.Flow

/** Read-side of the audit trail — [com.example.samdapp.domain.audit.AuditLogger] stays the only
 *  write path; nothing here inserts, updates, or deletes. */
interface AuditLogRepository {
    /** [limit]-bounded, most recent first — the Profile tab's audit summary, not a full-log export. */
    fun observeRecentForUser(userId: String, limit: Int = 20): Flow<List<AuditLogEntry>>
}
