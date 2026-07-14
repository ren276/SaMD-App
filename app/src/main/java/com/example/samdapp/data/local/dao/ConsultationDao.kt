package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.SymptomEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ConsultationDao {
    @Insert
    suspend fun insert(consultation: ConsultationEntity)

    @Query("UPDATE consultations SET transcription = :transcription, updatedAt = :updatedAt WHERE id = :consultationId")
    suspend fun updateTranscription(consultationId: String, transcription: String, updatedAt: Instant)

    @Query("SELECT * FROM consultations WHERE encounterId = :encounterId LIMIT 1")
    fun observeForEncounter(encounterId: String): Flow<ConsultationEntity?>
}

@Dao
interface SymptomDao {
    @Insert
    suspend fun insert(symptom: SymptomEntity)

    @Query("SELECT * FROM symptoms WHERE encounterId = :encounterId ORDER BY createdAt ASC")
    fun observeForEncounter(encounterId: String): Flow<List<SymptomEntity>>
}
