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
    /** Sync metadata: when this row's bytes last changed on this device. Deliberately
     *  redundant with [updatedAt] (a clinical fact) so Phase 6 always reads one column
     *  regardless of which entity it's syncing, see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
