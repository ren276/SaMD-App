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
    /** H-14, MIGRATION_14_15: non-null means this row is an honest "`/api/v1/evaluate` failed"
     *  marker, not a real report — [payloadJson] is then a placeholder ("{}"), never a
     *  half-real payload, and callers must check this field before deserializing it. Deliberately
     *  excluded from [com.example.samdapp.data.remote.dto.EvaluateReportSyncPayloadDto] and from
     *  [EvaluateReportDao.getPendingForSync]'s predicate: this column exists specifically so a
     *  failure row is structurally unable to reach the sync outbox and be pushed to the backend
     *  as a real evaluate report (see the H-14 decision doc, docs/quality/h-14-evaluate-failure-decision.md,
     *  Option 2). A later successful retry's [com.example.samdapp.data.repository.EvaluateReportRepositoryImpl.save]
     *  REPLACEs this same row with a real one, clearing this field back to null. */
    val failureCode: String? = null,
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
