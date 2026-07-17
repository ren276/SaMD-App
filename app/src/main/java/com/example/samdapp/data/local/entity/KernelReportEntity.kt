package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** [differentials]/[evidenceFor]/[evidenceAgainst] persist as JSON string lists (Converters). */
@Entity(tableName = "kernel_reports", indices = [Index("caseRecordId")])
data class KernelReportEntity(
    @PrimaryKey val id: String,
    val caseRecordId: String,
    val predictedCondition: String,
    val confidenceScore: Double,
    val differentials: List<String>,
    val reasoningSummary: String,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val modelVersion: String,
    val inferenceTimestamp: Instant,
    val requiredHumanVerification: Boolean,
)
