package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.DoctorTrackerRow
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.repository.CaseRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class CaseRecordRepositoryImpl @Inject constructor(
    private val caseRecordDao: CaseRecordDao,
) : CaseRecordRepository {

    override suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord> = asDataResult {
        val now = Instant.now()
        caseRecordDao.abandonDraftsForPatient(patientId, now)
        val caseRecord = CaseRecord(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            status = CaseStatus.DRAFT,
            assignedDoctorId = null,
            createdAt = now,
            updatedAt = now,
        )
        caseRecordDao.insert(caseRecord.toEntity())
        caseRecord
    }

    override suspend fun markSavedLocally(caseRecordId: String): Result<Unit> = asDataResult {
        caseRecordDao.updateStatus(caseRecordId, CaseStatus.SAVED_LOCALLY, Instant.now())
    }

    override suspend fun assignDoctor(caseRecordId: String, doctorId: String, isOnline: Boolean): Result<Unit> = asDataResult {
        val status = if (isOnline) CaseStatus.SENT_TO_DOCTOR else CaseStatus.PENDING_SYNC
        caseRecordDao.assignDoctor(caseRecordId, doctorId, status, Instant.now())
    }

    override suspend fun sendAllPendingCases(): Result<Unit> = asDataResult {
        caseRecordDao.sendAllPendingSync(Instant.now())
    }

    override fun observePendingSyncCount(): Flow<Int> = caseRecordDao.observePendingSyncCount()

    override suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit> = asDataResult {
        caseRecordDao.updateStatus(caseRecordId, CaseStatus.PRESCRIPTION_RECEIVED, Instant.now())
    }

    override fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?> =
        caseRecordDao.observeById(caseRecordId).map { it?.toDomain() }

    override fun observeLatestForPatient(patientId: String): Flow<CaseRecord?> =
        caseRecordDao.observeLatestForPatient(patientId).map { it?.toDomain() }

    override fun observeByEncounterId(encounterId: String): Flow<CaseRecord?> =
        caseRecordDao.observeByEncounterId(encounterId).map { it?.toDomain() }

    override fun observeResumableDraftForUser(userId: String): Flow<CaseRecord?> =
        caseRecordDao.observeResumableDraftForUser(userId).map { it?.toDomain() }

    override fun observeOpenCaseCount(doctorId: String): Flow<Int> =
        caseRecordDao.observeOpenCaseCount(doctorId)

    override fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerEntry>> =
        caseRecordDao.observeDoctorTrackerRows().map { rows -> rows.map { it.toDomain() } }
}

private fun DoctorTrackerRow.toDomain() = DoctorTrackerEntry(
    caseRecordId = caseRecordId,
    patientId = patientId,
    patientFullName = patientFullName,
    chiefComplaint = chiefComplaint,
    status = status,
    updatedAt = updatedAt,
    doctorName = doctorName,
    doctorSpecialty = doctorSpecialty,
)

private fun CaseRecord.toEntity() = CaseRecordEntity(
    id = id, patientId = patientId, encounterId = encounterId, status = status,
    assignedDoctorId = assignedDoctorId, createdAt = createdAt, updatedAt = updatedAt,
)

private fun CaseRecordEntity.toDomain() = CaseRecord(
    id = id, patientId = patientId, encounterId = encounterId, status = status,
    assignedDoctorId = assignedDoctorId, createdAt = createdAt, updatedAt = updatedAt,
)
