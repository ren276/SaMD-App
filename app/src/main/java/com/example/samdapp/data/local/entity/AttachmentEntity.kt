package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.AttachmentType
import java.time.Instant

@Entity(tableName = "attachments", indices = [Index("consultationId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val consultationId: String,
    val type: AttachmentType,
    val uri: String,
    val createdAt: Instant,
)
