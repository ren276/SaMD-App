package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface CaseRecordDao {
    @Insert
    suspend fun insert(caseRecord: CaseRecordEntity)

    @Query("UPDATE case_records SET status = :status, updatedAt = :updatedAt WHERE id = :caseRecordId")
    suspend fun updateStatus(caseRecordId: String, status: CaseStatus, updatedAt: Instant)

    @Query(
        "UPDATE case_records SET status = :status, assignedDoctorId = :doctorId, updatedAt = :updatedAt " +
            "WHERE id = :caseRecordId",
    )
    suspend fun assignDoctor(caseRecordId: String, doctorId: String, status: CaseStatus, updatedAt: Instant)

    @Query("SELECT * FROM case_records WHERE id = :caseRecordId")
    fun observeById(caseRecordId: String): Flow<CaseRecordEntity?>

    @Query("SELECT * FROM case_records WHERE patientId = :patientId ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestForPatient(patientId: String): Flow<CaseRecordEntity?>
}
