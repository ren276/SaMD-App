package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "abha_profiles")
data class AbhaProfileEntity(
    @PrimaryKey val abhaId: String,
    val abhaAddress: String?,
    val name: String,
    val dateOfBirth: LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val mobileNumber: String?,
    val emailAddress: String?,
    val photoUrlMock: String?,
    val kycVerified: Boolean,
    val createdAt: Instant,
)
