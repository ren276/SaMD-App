package com.example.samdapp.domain.repository

import com.example.samdapp.domain.audit.AuditLogEntry
import kotlinx.coroutines.flow.Flow

/** Read-side of the audit trail — [com.example.samdapp.domain.audit.AuditLogger] stays the only
 *  write path; nothing here inserts, updates, or deletes. */
interface AuditLogRepository {
    /** [limit]-bounded, most recent first — the Profile tab's audit summary, not a full-log export. */
    fun observeRecentForUser(userId: String, limit: Int = 20): Flow<List<AuditLogEntry>>

    /** Every audit row for one patient, most recent first — backs the patient-facing "who has
     *  seen your file" view (DPDP right-to-access). Unbounded by design: it's one patient's own
     *  trail, not a cross-patient query. */
    fun observeForPatient(patientId: String): Flow<List<AuditLogEntry>>
}
