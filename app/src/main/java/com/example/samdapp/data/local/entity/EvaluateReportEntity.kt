package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

/** [payloadJson] is the whole diagnosticSummary/nlemTreatment/brandMapping/safetyAndTriage tree
 *  Gson-serialized into one column — the nesting is too deep for a per-field TypeConverter scheme
 *  to pay for itself (see [com.example.samdapp.data.repository.EvaluateReportRepositoryImpl]). */
@Entity(tableName = "evaluate_reports", indices = [Index("caseRecordId")])
data class EvaluateReportEntity(
    @PrimaryKey val id: String,
    val caseRecordId: String,
    val payloadJson: String,
    val inferenceStartedAt: Instant,
    val inferenceEndedAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. This table has no
     *  write-time column of its own ([inferenceStartedAt]/[inferenceEndedAt] are clinical
     *  inference timing, not DB write time), and its repository upserts one row per case,
     *  replacing it wholesale on retry. Maps to `client_updated_at` on the wire (Phase 6),
     *  see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
