package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "social_histories")
data class SocialHistoryEntity(
    @PrimaryKey val patientId: String,
    val occupation: String?,
    val tobaccoUse: String?,
    val alcoholUse: String?,
    val recreationalDrugUse: String?,
    val environmentalExposure: String?,
    val recentTravel: String?,
    val updatedAt: Instant,
)
