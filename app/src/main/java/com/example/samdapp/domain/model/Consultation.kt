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
    /** [FieldProvenance] of [impactOnDailyActivities]. Null on every row that predates this
     *  column and on any row written before a caller starts setting it. See
     *  [FieldProvenance]'s KDoc for the full contract; this PR adds only the column, nothing
     *  writes a non-null value yet. */
    val impactOnDailyActivitiesProvenance: FieldProvenance?,
    val relevantHistory: String?,
    val transcription: String?,
    val attachments: List<Attachment>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
