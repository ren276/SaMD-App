package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "family_history_entries", indices = [Index("patientId")])
data class FamilyHistoryEntryEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val condition: String,
    val relation: String?,
    val createdAt: Instant,
)
