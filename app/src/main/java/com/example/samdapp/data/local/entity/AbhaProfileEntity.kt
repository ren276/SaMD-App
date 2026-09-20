package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.SyncState
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "abha_profiles")
data class AbhaProfileEntity(
    @PrimaryKey val abhaId: String,
    val abhaAddress: String?,
    val name: String,
    val dateOfBirth: LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val mobileNumber: String?,
    val emailAddress: String?,
    val photoUrlMock: String?,
    val kycVerified: Boolean,
    val createdAt: Instant,
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
    /** Sync metadata: when this row's bytes last changed on this device, set fresh on every
     *  call including the [com.example.samdapp.data.local.dao.AbhaProfileDao.upsert] REPLACE
     *  path, deliberately not sourced from [createdAt], whose preserved-vs-overwritten
     *  behavior on REPLACE is inconsistent across callers (flagged, not fixed, in
     *  PROGRESS.md). Maps to `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's
     *  KDoc. */
    val localModifiedAt: Instant,
)
