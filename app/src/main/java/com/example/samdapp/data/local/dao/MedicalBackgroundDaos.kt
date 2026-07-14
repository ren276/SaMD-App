package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicalHistoryItemDao {
    @Insert
    suspend fun insert(item: MedicalHistoryItemEntity)

    @Query("SELECT * FROM medical_history_items WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<MedicalHistoryItemEntity>>
}

@Dao
interface MedicationEntryDao {
    @Insert
    suspend fun insert(entry: MedicationEntryEntity)

    @Query("SELECT * FROM medication_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<MedicationEntryEntity>>
}

@Dao
interface AllergyDao {
    @Insert
    suspend fun insert(allergy: AllergyEntity)

    @Query("SELECT * FROM allergies WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<AllergyEntity>>
}

@Dao
interface FamilyHistoryEntryDao {
    @Insert
    suspend fun insert(entry: FamilyHistoryEntryEntity)

    @Query("SELECT * FROM family_history_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<FamilyHistoryEntryEntity>>
}

@Dao
interface SocialHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(socialHistory: SocialHistoryEntity)

    @Query("SELECT * FROM social_histories WHERE patientId = :patientId")
    fun observeForPatient(patientId: String): Flow<SocialHistoryEntity?>
}
