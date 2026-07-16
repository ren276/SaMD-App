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

/** How a vitals snapshot was captured — a data-quality signal for the kernel and regulatory
 *  review (REQ-TRS-05). One value per [com.example.samdapp.domain.model.VitalsSnapshot], applied
 *  to every [Observation] row it fans into — mirrors [ObservationSource]'s existing per-snapshot
 *  (not per-vital-type) granularity, so this doesn't fragment into eight separate pickers. */
enum class VitalsCaptureMethod { MANUAL_CUFF, DIGITAL_MONITOR, PULSE_OXIMETER, THERMOMETER, OTHER }

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
    val captureMethod: VitalsCaptureMethod? = null,
    val recordedAt: Instant,
    /** Offline-first dual timestamp: [recordedAt]/[createdAt] are the on-device capture time;
     *  this stays null until a (future, real) sync writes the reading upstream. */
    val syncedToCloudAt: Instant? = null,
    val createdAt: Instant,
)
