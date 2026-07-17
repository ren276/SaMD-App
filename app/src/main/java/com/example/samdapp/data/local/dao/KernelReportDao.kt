package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.KernelReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KernelReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: KernelReportEntity)

    @Query("SELECT * FROM kernel_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<KernelReportEntity?>
}
