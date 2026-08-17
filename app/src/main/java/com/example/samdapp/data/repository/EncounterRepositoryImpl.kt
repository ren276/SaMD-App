package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.dao.EncounterHistoryRow
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.repository.EncounterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class EncounterRepositoryImpl @Inject constructor(
    private val encounterDao: EncounterDao,
) : EncounterRepository {

    override suspend fun startEncounter(patientId: String, followUpOfEncounterId: String?): Result<Encounter> = asDataResult {
        val now = Instant.now()
        val encounter = Encounter(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            startedAt = now,
            createdAt = now,
            updatedAt = now,
            followUpOfEncounterId = followUpOfEncounterId,
        )
        encounterDao.insert(encounter.toEntity())
        encounter
    }

    override fun observeEncounter(encounterId: String): Flow<Encounter?> =
        encounterDao.observeById(encounterId).map { it?.toDomain() }

    override fun observeHistoryForPatient(patientId: String): Flow<List<ConsultationHistoryEntry>> =
        encounterDao.observeHistoryForPatient(patientId).map { rows -> rows.map { it.toDomain() } }
}

private fun EncounterHistoryRow.toDomain() = ConsultationHistoryEntry(
    encounterId = encounterId,
    visitDate = startedAt,
    chiefComplaint = chiefComplaint,
    caseRecordId = caseRecordId,
    caseStatus = status,
    followUpOfEncounterId = followUpOfEncounterId,
    doctorName = doctorName,
    doctorSpecialty = doctorSpecialty,
)

private fun Encounter.toEntity() = EncounterEntity(
    id = id,
    patientId = patientId,
    startedAt = startedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    followUpOfEncounterId = followUpOfEncounterId,
    localModifiedAt = updatedAt,
)

private fun EncounterEntity.toDomain() = Encounter(
    id = id,
    patientId = patientId,
    startedAt = startedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    followUpOfEncounterId = followUpOfEncounterId,
)
