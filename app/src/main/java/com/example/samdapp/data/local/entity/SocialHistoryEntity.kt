package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

@Entity(tableName = "social_histories")
data class SocialHistoryEntity(
    @PrimaryKey val patientId: String,
    val occupation: String?,
    val tobaccoUse: String?,
    val alcoholUse: String?,
    val recreationalDrugUse: String?,
    val environmentalExposure: String?,
    val recentTravel: String?,
    val updatedAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. Deliberately
     *  redundant with [updatedAt] (a clinical fact) so Phase 6 always reads one column
     *  regardless of which entity it's syncing, see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
