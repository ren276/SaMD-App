package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.Visibility
import java.time.Instant

@Entity(tableName = "ailments", indices = [Index("patientId"), Index("encounterId")])
data class AilmentEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val description: String,
    val measurementType: MeasurementType,
    val visibility: Visibility,
    val measuredValue: Double?,
    val measuredUnit: String?,
    val severity: Int?,
    val onset: String?,
    val duration: String?,
    val qualifiers: String?,
    val audioLocalUri: String?,
    val capturedAtOffline: Instant,
    val syncedToCloudAt: Instant?,
    val deletedAt: Instant?,
    val createdAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device, including the soft
     *  delete in [com.example.samdapp.data.local.dao.AilmentDao.markDeleted]. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
