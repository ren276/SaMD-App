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
    /** ABDM-verified provenance (Phase 6c, MIGRATION_13_14) — the final, verified identity
     *  fields from a real `AbhaIdentity` response (api-contract.md §8), distinct from
     *  [abhaNumber]: registration STATE (session id, in-progress step) never lands on Patient,
     *  it lives in the backend transaction; only this proof-of-verification does. All five
     *  nullable: a manually-registered patient with no ABHA has none of them. */
    val abhaAddress: String? = null,
    /** Mirrors `AbhaIdentity.kyc_verified`/[com.example.samdapp.domain.model.AbhaProfile.kycVerified]
     *  — deliberately not a multi-state "status" column: the pinned `AbhaIdentity` shape has no
     *  ABDM account-status field at all (`status`/`"ACTIVE"` is confirmed dropped, never mapped,
     *  see docs/requirements/abha-internal-contract.md's field-by-field diff), so a `kycStatus`/
     *  `abhaStatus` column would sit permanently unpopulated. See PROGRESS.md for the full note. */
    val kycVerified: Boolean? = null,
    /** `AbhaIdentity.verification_source`, e.g. `"ABDM_AADHAAR_OTP"` — a backend-assigned
     *  workflow constant, not derived from any single ABDM field. */
    val verificationSource: String? = null,
    /** `AbhaIdentity.verified_at` — the SaMD backend's own timestamp of when this verification
     *  transaction completed, distinct from the ABHA account's own (much older) creation date. */
    val verifiedAt: Instant? = null,
)
