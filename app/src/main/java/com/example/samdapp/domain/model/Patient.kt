package com.example.samdapp.domain.model

import java.time.Instant
import java.time.LocalDate

data class Patient(
    val id: String,
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val age: Int?,
    val biologicalSex: String,
    val guardianOrSpouseName: String?,
    val mobileNumber: String?,
    val aadhaarNumber: String?,
    val abhaNumber: String?,
    val village: String?,
    val block: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val category: String?,
    val maritalStatus: String?,
    val bloodGroup: String?,
    val emergencyContact: String?,
    val primaryCareClinicName: String?,
    val referringPhysicianName: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
