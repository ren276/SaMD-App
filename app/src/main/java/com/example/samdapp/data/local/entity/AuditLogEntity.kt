package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "audit_log", indices = [Index("patientId"), Index("caseRecordId")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Instant,
    val userId: String,
    val patientId: String?,
    val caseRecordId: String?,
    val action: String,
    val payload: String,
)
