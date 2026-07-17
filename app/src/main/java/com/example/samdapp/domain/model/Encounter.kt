package com.example.samdapp.domain.model

import java.time.Instant

data class Encounter(
    val id: String,
    val patientId: String,
    val startedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val followUpOfEncounterId: String?,
)
