package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import java.time.Instant

@Entity(tableName = "referrals", indices = [Index("patientUid"), Index("caseRecordId")])
data class ReferralEntity(
    @PrimaryKey val id: String,
    val patientUid: String,
    val caseRecordId: String,
    val urgencyLevel: UrgencyLevel,
    val reason: String,
    val sendingPhcId: String,
    val status: ReferralStatus,
    val timestamp: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device, including a
     *  [status] transition via [com.example.samdapp.data.local.dao.ReferralDao.updateStatus].
     *  Existing rows backfill from [timestamp] (creation time). Status history before this
     *  column existed is unrecoverable; see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
