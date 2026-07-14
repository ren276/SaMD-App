package com.example.samdapp.domain.model

import java.time.Instant

data class Consultation(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val chiefComplaint: String,
    val onset: String?,
    val durationBucket: String?,
    val severityScore: Int?,
    val aggravatingFactors: String?,
    val relievingFactors: String?,
    val impactOnDailyActivities: String?,
    val relevantHistory: String?,
    val transcription: String?,
    val attachments: List<Attachment>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Symptom(
    val id: String,
    val encounterId: String,
    val patientId: String,
    val description: String,
    val createdAt: Instant,
)
