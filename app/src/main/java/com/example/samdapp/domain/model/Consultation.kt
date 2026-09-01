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
    /** [FieldProvenance] of [impactOnDailyActivities]. Null only when [impactOnDailyActivities]
     *  itself is null; every existing row was backfilled to [FieldProvenance.TYPED] by
     *  `MIGRATION_16_17` and every new save stamps `TYPED` when a value is present. See
     *  [FieldProvenance]'s KDoc for the full contract; nothing writes `VOICE_*` yet. */
    val impactOnDailyActivitiesProvenance: FieldProvenance?,
    val relevantHistory: String?,
    val transcription: String?,
    val attachments: List<Attachment>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
