package com.example.samdapp.domain.model

import java.time.Instant

/** Ephemeral device-poll shape — never persisted as-is. [com.example.samdapp.domain.vitalssource.VitalsSource] returns
 * this; the repository fans it into [Observation] rows. Only device-pollable vitals live here. */
data class VitalsReading(
    val pulseBpm: Int? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val spo2Percent: Int? = null,
    val temperatureCelsius: Double? = null,
    val respiratoryRate: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val bloodGlucoseMgDl: Int? = null,
    val deviceId: String? = null,
)

/** Editable form state for the Compounder screen and the shape [VitalsRepository][com.example.samdapp.domain.repository.VitalsRepository]
 * reassembles from [Observation] rows on read. Adds manual-only fields no device can supply. */
data class VitalsSnapshot(
    val encounterId: String,
    val patientId: String,
    val pulseBpm: Int? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val spo2Percent: Int? = null,
    val temperatureCelsius: Double? = null,
    val respiratoryRate: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val bloodGlucoseMgDl: Int? = null,
    val painScore: Int? = null,
    val urinalysisResult: String? = null,
    val deviceId: String? = null,
    val source: ObservationSource = ObservationSource.MANUAL,
    val recordedAt: Instant,
) {
    val bmi: Double?
        get() {
            val w = weightKg ?: return null
            val h = heightCm ?: return null
            if (h <= 0) return null
            val heightMeters = h / 100.0
            return ((w / (heightMeters * heightMeters)) * 10.0).let { Math.round(it) / 10.0 }
        }
}

fun VitalsReading.toSnapshot(encounterId: String, patientId: String, recordedAt: Instant): VitalsSnapshot =
    VitalsSnapshot(
        encounterId = encounterId,
        patientId = patientId,
        pulseBpm = pulseBpm,
        bpSystolic = bpSystolic,
        bpDiastolic = bpDiastolic,
        spo2Percent = spo2Percent,
        temperatureCelsius = temperatureCelsius,
        respiratoryRate = respiratoryRate,
        weightKg = weightKg,
        heightCm = heightCm,
        bloodGlucoseMgDl = bloodGlucoseMgDl,
        deviceId = deviceId,
        source = ObservationSource.DEVICE,
        recordedAt = recordedAt,
    )
