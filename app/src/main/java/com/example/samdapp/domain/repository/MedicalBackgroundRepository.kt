package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Allergy
import com.example.samdapp.domain.model.FamilyHistoryEntry
import com.example.samdapp.domain.model.MedicalHistoryItem
import com.example.samdapp.domain.model.MedicationEntry
import com.example.samdapp.domain.model.SocialHistory
import kotlinx.coroutines.flow.Flow

interface MedicalBackgroundRepository {
    suspend fun addMedicalHistoryItem(item: MedicalHistoryItem): Result<Unit>
    suspend fun addMedication(entry: MedicationEntry): Result<Unit>
    suspend fun addAllergy(allergy: Allergy): Result<Unit>
    suspend fun addFamilyHistoryEntry(entry: FamilyHistoryEntry): Result<Unit>
    suspend fun upsertSocialHistory(socialHistory: SocialHistory): Result<Unit>

    fun observeMedicalHistory(patientId: String): Flow<List<MedicalHistoryItem>>
    fun observeMedications(patientId: String): Flow<List<MedicationEntry>>
    fun observeAllergies(patientId: String): Flow<List<Allergy>>
    fun observeFamilyHistory(patientId: String): Flow<List<FamilyHistoryEntry>>
    fun observeSocialHistory(patientId: String): Flow<SocialHistory?>
}
