package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.CaseStatus
import java.time.Instant

@Entity(tableName = "case_records", indices = [Index("patientId"), Index("encounterId")])
data class CaseRecordEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val status: CaseStatus,
    val assignedDoctorId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
