package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.MedicationKind
import java.time.Instant

@Entity(tableName = "medication_entries", indices = [Index("patientId")])
data class MedicationEntryEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String?,
    val kind: MedicationKind,
    val name: String,
    val dosage: String?,
    val frequency: String?,
    val active: Boolean,
    val createdAt: Instant,
)
