package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.domain.repository.AuditLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AuditLogRepositoryImpl @Inject constructor(
    private val auditLogDao: AuditLogDao,
) : AuditLogRepository {

    override fun observeRecentForUser(userId: String, limit: Int): Flow<List<AuditLogEntry>> =
        auditLogDao.observeByUserId(userId, limit).map { rows -> rows.map { it.toDomain() } }
}

private fun AuditLogEntity.toDomain() = AuditLogEntry(
    id = id,
    timestamp = timestamp,
    action = action,
    patientId = patientId,
    caseRecordId = caseRecordId,
)
