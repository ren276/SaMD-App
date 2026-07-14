package com.example.samdapp.domain.model

import java.time.Instant

enum class AllergyCategory { DRUG, FOOD, ENVIRONMENTAL }

data class Allergy(
    val id: String,
    val patientId: String,
    val category: AllergyCategory,
    val allergen: String,
    val reactionType: String?,
    val createdAt: Instant,
)
