package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

@Entity(tableName = "attachments", indices = [Index("consultationId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val consultationId: String,
    val type: AttachmentType,
    val uri: String,
    val createdAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
