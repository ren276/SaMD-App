package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AilmentDao
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.repository.AilmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class AilmentRepositoryImpl @Inject constructor(
    private val ailmentDao: AilmentDao,
) : AilmentRepository {

    override suspend fun addAilment(ailment: AilmentEntry): Result<Unit> = asDataResult {
        ailmentDao.insert(ailment.toEntity())
    }

    override fun observeForEncounter(encounterId: String): Flow<List<AilmentEntry>> =
        ailmentDao.observeForEncounter(encounterId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun markDeleted(id: String): Result<Unit> = asDataResult {
        ailmentDao.markDeleted(id, Instant.now())
    }
}

private fun AilmentEntry.toEntity() = AilmentEntity(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    description = description,
    measurementType = measurementType,
    visibility = visibility,
    measuredValue = measuredValue,
    measuredUnit = measuredUnit,
    severity = severity,
    onset = onset,
    duration = duration,
    qualifiers = qualifiers,
    audioLocalUri = audioLocalUri,
    capturedAtOffline = capturedAtOffline,
    syncedToCloudAt = syncedToCloudAt,
    deletedAt = deletedAt,
    createdAt = createdAt,
    localModifiedAt = createdAt,
)

private fun AilmentEntity.toDomain() = AilmentEntry(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    description = description,
    measurementType = measurementType,
    visibility = visibility,
    measuredValue = measuredValue,
    measuredUnit = measuredUnit,
    severity = severity,
    onset = onset,
    duration = duration,
    qualifiers = qualifiers,
    audioLocalUri = audioLocalUri,
    capturedAtOffline = capturedAtOffline,
    syncedToCloudAt = syncedToCloudAt,
    deletedAt = deletedAt,
    createdAt = createdAt,
)
