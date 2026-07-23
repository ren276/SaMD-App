package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosisFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feedback: DiagnosisFeedbackEntity)

    @Query("SELECT * FROM diagnosis_feedback WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<DiagnosisFeedbackEntity?>
}
