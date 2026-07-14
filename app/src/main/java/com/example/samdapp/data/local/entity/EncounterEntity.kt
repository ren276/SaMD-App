package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "encounters", indices = [Index("patientId")])
data class EncounterEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val startedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)
