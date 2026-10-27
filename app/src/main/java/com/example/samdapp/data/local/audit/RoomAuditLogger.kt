package com.example.samdapp.data.local.audit

import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.auth.AuthSession
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Only reached if log() is somehow called with no session active — every screen that logs
 * sits behind the sign-in gate (see AppNavHost), so this should never happen in practice. */
private const val PLACEHOLDER_USER_ID = "phc_field_worker"

class RoomAuditLogger @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val authSession: AuthSession,
) : AuditLogger {
    override suspend fun log(action: AuditAction, patientId: String?, caseRecordId: String?, payload: String) =
        insert(authSession.currentUser().first()?.userId ?: PLACEHOLDER_USER_ID, action, patientId, caseRecordId, payload)

    override suspend fun logAs(actorUserId: String, action: AuditAction, payload: String) =
        insert(actorUserId, action, patientId = null, caseRecordId = null, payload = payload)

    private suspend fun insert(userId: String, action: AuditAction, patientId: String?, caseRecordId: String?, payload: String) {
        val now = Instant.now()
        auditLogDao.insert(
            AuditLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                userId = userId,
                patientId = patientId,
                caseRecordId = caseRecordId,
                action = action.value,
                payload = payload,
                localModifiedAt = now,
            ),
        )
    }
}
