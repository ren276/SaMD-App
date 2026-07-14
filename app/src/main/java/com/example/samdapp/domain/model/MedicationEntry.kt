package com.example.samdapp.domain.model

import java.time.Instant

enum class MedicationKind { MEDICATION, SUPPLEMENT }

data class MedicationEntry(
    val id: String,
    val patientId: String,
    val encounterId: String?,
    val kind: MedicationKind,
    val name: String,
    val dosage: String?,
    val frequency: String?,
    val active: Boolean,
    val createdAt: Instant,
)
