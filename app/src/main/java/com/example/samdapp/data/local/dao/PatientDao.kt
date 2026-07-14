package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Insert
    suspend fun insert(patient: PatientEntity)

    @Query("SELECT * FROM patients WHERE id = :patientId")
    fun observeById(patientId: String): Flow<PatientEntity?>
}
