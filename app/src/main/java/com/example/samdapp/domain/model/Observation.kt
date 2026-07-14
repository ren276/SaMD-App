package com.example.samdapp.domain.model

import java.time.Instant

enum class ObservationType(val defaultUnit: String?) {
    PULSE("bpm"),
    BP_SYSTOLIC("mmHg"),
    BP_DIASTOLIC("mmHg"),
    SPO2("%"),
    TEMPERATURE("°C"),
    RESPIRATORY_RATE("breaths/min"),
    WEIGHT("kg"),
    HEIGHT("cm"),
    BMI("kg/m²"),
    BLOOD_GLUCOSE("mg/dL"),
    PAIN_SCORE(null),
    URINALYSIS(null),
}

enum class ObservationSource { MANUAL, DEVICE }

data class Observation(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val type: ObservationType,
    val valueNumeric: Double?,
    val valueText: String?,
    val unit: String?,
    val deviceId: String?,
    val source: ObservationSource,
    val recordedAt: Instant,
    val createdAt: Instant,
)
