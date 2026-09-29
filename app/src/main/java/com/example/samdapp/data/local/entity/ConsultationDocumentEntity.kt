package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

/** H-18, Build 3a. Mirrors [com.example.samdapp.data.local.entity.AttachmentEntity]'s linkage
 *  shape: `consultationId` mandatory and indexed, `patientId` denormalised and indexed. Row is
 *  never deleted on retract — see [retractedAt]. */
@Entity(
    tableName = "consultation_documents",
    indices = [Index("consultationId"), Index("patientId")],
)
data class ConsultationDocumentEntity(
    @PrimaryKey val id: String,
    val consultationId: String,
    val patientId: String,
    val abhaNumber: String?,
    val label: String,
    val canonicalName: String,
    val departmentCode: DepartmentCode,
    val recordTypeCode: RecordTypeCode,
    val storageKey: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: DocumentSource,
    /** Non-null only for `CAMERA_ASSEMBLED` rows (Build 3b). Nullable with no default in SQL:
     *  every pre-3b row is legitimately null, so `MIGRATION_18_19` backfills nothing. */
    val pageCount: Int? = null,
    val uploadedAt: Instant,
    val uploaderUserId: String,
    val uploaderRole: String,
    /** Non-null means retracted. The row itself is never deleted or nulled elsewhere. */
    val retractedAt: Instant? = null,
    val retractionReason: String? = null,
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
    /** Sync metadata: when this row's bytes last changed on this device. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
