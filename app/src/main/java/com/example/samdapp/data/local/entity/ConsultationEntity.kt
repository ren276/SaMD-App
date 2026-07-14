package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "consultations", indices = [Index("patientId"), Index("encounterId")])
data class ConsultationEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val chiefComplaint: String,
    val onset: String?,
    val durationBucket: String?,
    val severityScore: Int?,
    val aggravatingFactors: String?,
    val relievingFactors: String?,
    val impactOnDailyActivities: String?,
    val relevantHistory: String?,
    val transcription: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "symptoms", indices = [Index("encounterId"), Index("patientId")])
data class SymptomEntity(
    @PrimaryKey val id: String,
    val encounterId: String,
    val patientId: String,
    val description: String,
    val createdAt: Instant,
)
