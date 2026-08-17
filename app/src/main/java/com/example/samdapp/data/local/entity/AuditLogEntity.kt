package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.SyncState
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
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
