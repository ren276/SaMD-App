package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.ObservationType
import com.example.samdapp.domain.model.VitalsCaptureMethod
import java.time.Instant

@Entity(tableName = "observations", indices = [Index("patientId"), Index("encounterId")])
data class ObservationEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val encounterId: String,
    val type: ObservationType,
    val valueNumeric: Double?,
    val valueText: String?,
    val unit: String?,
    val deviceId: String?,
    val source: ObservationSource,
    val captureMethod: VitalsCaptureMethod? = null,
    val recordedAt: Instant,
    val syncedToCloudAt: Instant? = null,
    val createdAt: Instant,
)
