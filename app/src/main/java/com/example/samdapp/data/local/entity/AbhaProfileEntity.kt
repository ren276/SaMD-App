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
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device, set fresh on every
     *  call including the [com.example.samdapp.data.local.dao.AbhaProfileDao.upsert] REPLACE
     *  path, deliberately not sourced from [createdAt], whose preserved-vs-overwritten
     *  behavior on REPLACE is inconsistent across callers (flagged, not fixed, in
     *  PROGRESS.md). Maps to `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's
     *  KDoc. */
    val localModifiedAt: Instant,
)
