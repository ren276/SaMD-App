package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

@Entity(tableName = "prescriptions", indices = [Index("caseRecordId"), Index("patientId")])
data class PrescriptionEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val doctorId: String,
    val diagnosis: String,
    val kernelDecision: KernelDecision? = null,
    val createdAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)

/** Child rows of [PrescriptionEntity], one per [com.example.samdapp.domain.model.MedicationLine].
 *  [position] preserves the doctor's ordering; mirrors the Consultation→Attachment relationship. */
@Entity(tableName = "medication_lines", indices = [Index("prescriptionId")])
data class MedicationLineEntity(
    @PrimaryKey val id: String,
    val prescriptionId: String,
    val position: Int,
    val genericName: String,
    val brandName: String?,
    val strength: String,
    val dosage: String,
    val frequency: String,
    val route: String,
    val duration: String,
    val quantity: String,
    val foodRelation: String?,
    val instructions: String?,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device. No timestamp of its
     *  own, written atomically with, and never independently updated from, its parent
     *  [PrescriptionEntity], so this mirrors that row's [PrescriptionEntity.createdAt]. Maps to
     *  `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
