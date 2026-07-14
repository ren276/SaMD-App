package com.example.samdapp.domain.model

import java.time.Instant

/** Persisted as its [name] string in Room, so adding a status later is additive — same
 * forward-compatibility the brief asked for, plus compile-time exhaustiveness in `when` blocks. */
enum class CaseStatus { DRAFT, SAVED_LOCALLY, SENT_TO_DOCTOR }

data class CaseRecord(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val status: CaseStatus,
    val assignedDoctorId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
