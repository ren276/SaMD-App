package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.UrgencyLevel
import java.time.Instant

/** [differentials]/[evidenceFor]/[evidenceAgainst] persist as JSON string lists (Converters).
 *  [icdCode]/[dataQualityScore]/[uncertaintyScore] are the only genuinely-nullable additions
 *  from the report-capture schema addendum — [deviceId]/[softwareVersion]/[riskCategory]/
 *  [urgencyLevel]/[inferenceStartedAt]/[inferenceSource] are always populated by the use case. */
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
    val icdCode: String?,
    val deviceId: String,
    val softwareVersion: String,
    val dataQualityScore: Double?,
    val uncertaintyScore: Double?,
    val riskCategory: RiskCategory,
    val urgencyLevel: UrgencyLevel,
    val inferenceStartedAt: Instant,
    val inferenceEndedAt: Instant,
    val requiredHumanVerification: Boolean,
    val inferenceSource: InferenceSource,
)
