package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
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
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. This table has no
     *  write-time column of its own ([inferenceStartedAt]/[inferenceEndedAt] are clinical
     *  inference timing, not DB write time), and [com.example.samdapp.data.repository.KernelReportRepositoryImpl]
     *  upserts one row per case, replacing it wholesale on retry. Maps to `client_updated_at` on
     *  the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
