package com.example.samdapp.domain.model

import java.time.Instant

/** One row per patient — [patientId] is the identity, no separate surrogate id needed for a 1:1 relation. */
data class SocialHistory(
    val patientId: String,
    val occupation: String?,
    val tobaccoUse: String?,
    val alcoholUse: String?,
    val recreationalDrugUse: String?,
    val environmentalExposure: String?,
    val recentTravel: String?,
    val updatedAt: Instant,
)
