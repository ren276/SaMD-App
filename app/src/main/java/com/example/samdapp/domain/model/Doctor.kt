package com.example.samdapp.domain.model

data class Doctor(
    val id: String,
    val name: String,
    val specialty: String,
    val available: Boolean,
    val facilityName: String?,
)
