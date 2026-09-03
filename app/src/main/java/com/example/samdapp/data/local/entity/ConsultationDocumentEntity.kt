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
    val uploadedAt: Instant,
    val uploaderUserId: String,
    val uploaderRole: String,
    /** Non-null means retracted. The row itself is never deleted or nulled elsewhere. */
    val retractedAt: Instant? = null,
    val retractionReason: String? = null,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
