package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.SyncState
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey val id: String,
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val age: Int?,
    val biologicalSex: String,
    val guardianOrSpouseName: String?,
    val guardianRelation: String?,
    val mobileNumber: String?,
    val aadhaarNumber: String?,
    val abhaNumber: String?,
    val village: String?,
    val block: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val category: String?,
    val maritalStatus: String?,
    val bloodGroup: String?,
    val emergencyContact: String?,
    val primaryCareClinicName: String?,
    val referringPhysicianName: String?,
    val createdAt: Instant,
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
