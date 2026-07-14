package com.example.samdapp.domain.model

import java.time.Instant

data class FamilyHistoryEntry(
    val id: String,
    val patientId: String,
    val condition: String,
    val relation: String?,
    val createdAt: Instant,
)
