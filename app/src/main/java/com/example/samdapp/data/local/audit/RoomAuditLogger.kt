package com.example.samdapp.data.local.audit

import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditLogger
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** No real auth yet — see agent_docs/hardening.md RBAC note. Single placeholder user until it exists. */
private const val PLACEHOLDER_USER_ID = "phc_field_worker"

class RoomAuditLogger @Inject constructor(
    private val auditLogDao: AuditLogDao,
) : AuditLogger {
    override suspend fun log(action: String, patientId: String?, caseRecordId: String?, payload: String) {
        auditLogDao.insert(
            AuditLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                userId = PLACEHOLDER_USER_ID,
                patientId = patientId,
                caseRecordId = caseRecordId,
                action = action,
                payload = payload,
            ),
        )
    }
}
