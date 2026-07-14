package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE consultationId = :consultationId ORDER BY createdAt ASC")
    fun observeForConsultation(consultationId: String): Flow<List<AttachmentEntity>>
}
