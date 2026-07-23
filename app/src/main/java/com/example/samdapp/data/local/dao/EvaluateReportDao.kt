package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EvaluateReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: EvaluateReportEntity)

    @Query("SELECT * FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<EvaluateReportEntity?>
}
