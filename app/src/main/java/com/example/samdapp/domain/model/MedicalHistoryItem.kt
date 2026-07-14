package com.example.samdapp.domain.model

import java.time.Instant

enum class MedicalHistoryCategory { CHRONIC_CONDITION, SURGERY, HOSPITALIZATION }

data class MedicalHistoryItem(
    val id: String,
    val patientId: String,
    val category: MedicalHistoryCategory,
    val description: String,
    val yearOrDate: String?,
    val createdAt: Instant,
)
