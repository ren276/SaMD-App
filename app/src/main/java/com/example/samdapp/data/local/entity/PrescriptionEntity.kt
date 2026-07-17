package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.KernelDecision
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
)
