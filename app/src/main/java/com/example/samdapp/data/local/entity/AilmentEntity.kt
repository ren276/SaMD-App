package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.Visibility
import java.time.Instant

@Entity(tableName = "ailments", indices = [Index("patientId"), Index("encounterId")])
data class AilmentEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val description: String,
    val measurementType: MeasurementType,
    val visibility: Visibility,
    val measuredValue: Double?,
    val measuredUnit: String?,
    val severity: Int?,
    val onset: String?,
    val duration: String?,
    val qualifiers: String?,
    val audioLocalUri: String?,
    val capturedAtOffline: Instant,
    val syncedToCloudAt: Instant?,
    val deletedAt: Instant?,
    val createdAt: Instant,
)
