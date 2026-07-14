package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.MedicalHistoryCategory
import java.time.Instant

@Entity(tableName = "medical_history_items", indices = [Index("patientId")])
data class MedicalHistoryItemEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val category: MedicalHistoryCategory,
    val description: String,
    val yearOrDate: String?,
    val createdAt: Instant,
)
