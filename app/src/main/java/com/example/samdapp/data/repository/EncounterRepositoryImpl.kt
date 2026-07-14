package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.entity.EncounterEntity
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

    override suspend fun startEncounter(patientId: String): Result<Encounter> = asDataResult {
        val now = Instant.now()
        val encounter = Encounter(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            startedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        encounterDao.insert(encounter.toEntity())
        encounter
    }

    override fun observeEncounter(encounterId: String): Flow<Encounter?> =
        encounterDao.observeById(encounterId).map { it?.toDomain() }
}

private fun Encounter.toEntity() = EncounterEntity(
    id = id,
    patientId = patientId,
    startedAt = startedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EncounterEntity.toDomain() = Encounter(
    id = id,
    patientId = patientId,
    startedAt = startedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
