package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.FamilyHistoryEntryDao
import com.example.samdapp.data.local.dao.MedicalHistoryItemDao
import com.example.samdapp.data.local.dao.MedicationEntryDao
import com.example.samdapp.data.local.dao.SocialHistoryDao
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.domain.model.Allergy
import com.example.samdapp.domain.model.FamilyHistoryEntry
import com.example.samdapp.domain.model.MedicalHistoryItem
import com.example.samdapp.domain.model.MedicationEntry
import com.example.samdapp.domain.model.SocialHistory
import com.example.samdapp.domain.repository.MedicalBackgroundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MedicalBackgroundRepositoryImpl @Inject constructor(
    private val medicalHistoryItemDao: MedicalHistoryItemDao,
    private val medicationEntryDao: MedicationEntryDao,
    private val allergyDao: AllergyDao,
    private val familyHistoryEntryDao: FamilyHistoryEntryDao,
    private val socialHistoryDao: SocialHistoryDao,
) : MedicalBackgroundRepository {

    override suspend fun addMedicalHistoryItem(item: MedicalHistoryItem): Result<Unit> = asDataResult {
        medicalHistoryItemDao.insert(item.toEntity())
    }

    override suspend fun addMedication(entry: MedicationEntry): Result<Unit> = asDataResult {
        medicationEntryDao.insert(entry.toEntity())
    }

    override suspend fun addAllergy(allergy: Allergy): Result<Unit> = asDataResult {
        allergyDao.insert(allergy.toEntity())
    }

    override suspend fun addFamilyHistoryEntry(entry: FamilyHistoryEntry): Result<Unit> = asDataResult {
        familyHistoryEntryDao.insert(entry.toEntity())
    }

    override suspend fun upsertSocialHistory(socialHistory: SocialHistory): Result<Unit> = asDataResult {
        socialHistoryDao.upsert(socialHistory.toEntity())
    }

    override fun observeMedicalHistory(patientId: String): Flow<List<MedicalHistoryItem>> =
        medicalHistoryItemDao.observeForPatient(patientId).map { rows -> rows.map { it.toDomain() } }

    override fun observeMedications(patientId: String): Flow<List<MedicationEntry>> =
        medicationEntryDao.observeForPatient(patientId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAllergies(patientId: String): Flow<List<Allergy>> =
        allergyDao.observeForPatient(patientId).map { rows -> rows.map { it.toDomain() } }

    override fun observeFamilyHistory(patientId: String): Flow<List<FamilyHistoryEntry>> =
        familyHistoryEntryDao.observeForPatient(patientId).map { rows -> rows.map { it.toDomain() } }

    override fun observeSocialHistory(patientId: String): Flow<SocialHistory?> =
        socialHistoryDao.observeForPatient(patientId).map { it?.toDomain() }
}

private fun MedicalHistoryItem.toEntity() = MedicalHistoryItemEntity(
    id = id, patientId = patientId, category = category, description = description,
    yearOrDate = yearOrDate, createdAt = createdAt,
)

private fun MedicalHistoryItemEntity.toDomain() = MedicalHistoryItem(
    id = id, patientId = patientId, category = category, description = description,
    yearOrDate = yearOrDate, createdAt = createdAt,
)

private fun MedicationEntry.toEntity() = MedicationEntryEntity(
    id = id, patientId = patientId, encounterId = encounterId, kind = kind, name = name,
    dosage = dosage, frequency = frequency, active = active, createdAt = createdAt,
)

private fun MedicationEntryEntity.toDomain() = MedicationEntry(
    id = id, patientId = patientId, encounterId = encounterId, kind = kind, name = name,
    dosage = dosage, frequency = frequency, active = active, createdAt = createdAt,
)

private fun Allergy.toEntity() = AllergyEntity(
    id = id, patientId = patientId, category = category, allergen = allergen,
    reactionType = reactionType, createdAt = createdAt,
)

private fun AllergyEntity.toDomain() = Allergy(
    id = id, patientId = patientId, category = category, allergen = allergen,
    reactionType = reactionType, createdAt = createdAt,
)

private fun FamilyHistoryEntry.toEntity() = FamilyHistoryEntryEntity(
    id = id, patientId = patientId, condition = condition, relation = relation, createdAt = createdAt,
)

private fun FamilyHistoryEntryEntity.toDomain() = FamilyHistoryEntry(
    id = id, patientId = patientId, condition = condition, relation = relation, createdAt = createdAt,
)

private fun SocialHistory.toEntity() = SocialHistoryEntity(
    patientId = patientId, occupation = occupation, tobaccoUse = tobaccoUse, alcoholUse = alcoholUse,
    recreationalDrugUse = recreationalDrugUse, environmentalExposure = environmentalExposure,
    recentTravel = recentTravel, updatedAt = updatedAt,
)

private fun SocialHistoryEntity.toDomain() = SocialHistory(
    patientId = patientId, occupation = occupation, tobaccoUse = tobaccoUse, alcoholUse = alcoholUse,
    recreationalDrugUse = recreationalDrugUse, environmentalExposure = environmentalExposure,
    recentTravel = recentTravel, updatedAt = updatedAt,
)
