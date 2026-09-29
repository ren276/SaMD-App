package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.kernel.KernelFailure
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import java.time.Instant

/** [differentials]/[evidenceFor]/[evidenceAgainst] persist as JSON string lists (Converters).
 *  [icdCode]/[dataQualityScore]/[uncertaintyScore] are the only genuinely-nullable additions
 *  from the report-capture schema addendum — [deviceId]/[softwareVersion]/[riskCategory]/
 *  [urgencyLevel]/[inferenceStartedAt]/[inferenceSource] are always populated by the use case. */
@Entity(tableName = "kernel_reports", indices = [Index("caseRecordId", unique = true)])
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
    /** Why the assessment produced no result, when that is known. Nullable and defaulted so
     *  MIGRATION_19_20 is a bare ADD COLUMN and every pre-existing row reads null, which is the
     *  honest value for a row written before the cause was ever classified. Device-local: not in
     *  `KernelReportSyncPayloadDto`, same as `EvaluateReportEntity.failureCode`. */
    val failureCode: KernelFailure? = null,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    /** The backend's PHI-safe `message` for the last rejection, kept beside
     *  [syncErrorCode] because a code alone cannot tell a worker "this record already
     *  exists with different data". Carried on the wire since Phase 4 and discarded at
     *  the seam until MIGRATION_20_21. Device-local: absent from every `*SyncPayloadDto`. */
    val syncErrorMessage: String? = null,
    /** Acked push attempts charged against this row, reset to 0 on a successful sync and
     *  on any edit that rewrites the row. Only attempts the BACKEND ANSWERED are counted:
     *  an offline drain or an unreachable server must never burn a row's budget, which is
     *  why this is incremented by `applySyncResult` and nowhere else. Reaching
     *  [com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS] turns a
     *  [com.example.samdapp.domain.model.SyncState.RETRYABLE] row into FAILED carrying
     *  [com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE]. */
    val syncAttemptCount: Int = 0,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. This table has no
     *  write-time column of its own ([inferenceStartedAt]/[inferenceEndedAt] are clinical
     *  inference timing, not DB write time, and on a failed/UNAVAILABLE assessment they are
     *  placeholders), so this is also MIGRATION_15_16's newest-wins ordering key when it de-dups
     *  pre-existing rows. Maps to `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's
     *  KDoc.
     *
     *  One row per case is real as of MIGRATION_15_16 and not before: until then
     *  [com.example.samdapp.data.repository.KernelReportRepositoryImpl.save] upserted by a
     *  freshly-minted `id`, so every re-assessment inserted an ADDITIONAL row and the
     *  "replacing it wholesale on retry" this KDoc used to claim never happened. The unique index
     *  above is the enforcement; `getIdForCase` is the write-side fix. */
    val localModifiedAt: Instant,
)
