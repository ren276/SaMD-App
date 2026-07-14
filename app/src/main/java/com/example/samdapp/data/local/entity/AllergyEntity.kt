package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.AllergyCategory
import java.time.Instant

@Entity(tableName = "allergies", indices = [Index("patientId")])
data class AllergyEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val category: AllergyCategory,
    val allergen: String,
    val reactionType: String?,
    val createdAt: Instant,
)
