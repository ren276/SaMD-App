package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason

/**
 * Turns one gateway measurement into an [AcquisitionResult]. Pure, no Android and no coroutines,
 * so the risk controls it carries are unit-testable directly.
 *
 * The ORDER is part of the control, not an implementation detail: reject first, map second. No
 * value is converted before all three gates pass, so a rejected payload can never leave a
 * half-written number behind. A payload that fails more than one gate reports the first, which is
 * why the sequence below is fixed and pinned by a test.
 *
 * 1. [RejectReason.QUALITY_STATUS_NOT_OK] (RC-2), an exact case-sensitive "OK" allowlist.
 * 2. [RejectReason.SESSION_ID_MISMATCH] (RC-1), a missing session id counts as a mismatch.
 * 3. [RejectReason.DEVICE_TYPE_MISMATCH] (RC-1).
 * 4. Only now, map.
 *
 * There is NO default substitution anywhere below. A null secondary or tertiary value maps to a
 * null field, never to 0.0 and never to a plausible-looking stand-in: a substituted value is
 * indistinguishable from a measured one once it is in the form.
 */
object PiMeasurementMapper {

    private const val ADMISSIBLE_QUALITY_STATUS = "OK"
    private const val SESSION_ID_KEY = "session_id"

    fun map(
        dto: NormalizedMeasurementDto,
        expectedInstrument: Instrument,
        expectedSessionId: String,
    ): AcquisitionResult {
        // RC-2. Exact and case sensitive: WARNING, ERROR, UNAVAILABLE and any unrecognised value
        // are all inadmissible. An allowlist, not a denylist, so a status this app has never heard
        // of is rejected rather than waved through.
        if (dto.qualityStatus != ADMISSIBLE_QUALITY_STATUS) {
            return AcquisitionResult.Rejected(RejectReason.QUALITY_STATUS_NOT_OK)
        }

        // RC-1, first half. Only inside provenance; absent or non-string is a mismatch.
        val actualSessionId = dto.provenance?.get(SESSION_ID_KEY) as? String
        if (actualSessionId != expectedSessionId) {
            return AcquisitionResult.Rejected(RejectReason.SESSION_ID_MISMATCH)
        }

        // RC-1, second half. Top level, so it is read from a different level than the session id.
        val expectedDeviceType = expectedDeviceType(expectedInstrument)
        if (dto.deviceType != expectedDeviceType) {
            return AcquisitionResult.Rejected(RejectReason.DEVICE_TYPE_MISMATCH)
        }

        val reading = when (expectedInstrument) {
            Instrument.BP -> VitalsReading(
                bpSystolic = dto.primaryValue.toVitalInt(),
                bpDiastolic = dto.secondaryValue.toVitalInt(),
                pulseBpm = dto.tertiaryValue.toVitalInt(),
            )

            Instrument.SPO2 -> VitalsReading(
                spo2Percent = dto.primaryValue.toVitalInt(),
                pulseBpm = dto.secondaryValue.toVitalInt(),
            )

            // Cel on the wire already, so a passthrough. No rounding: 36.65 is a temperature a
            // clinician may act on at that precision and rounding it here would be this app
            // inventing a reading the instrument did not give.
            Instrument.THERMOMETER -> VitalsReading(temperatureCelsius = dto.primaryValue)

            Instrument.GLUCOMETER -> VitalsReading(bloodGlucoseMgDl = dto.primaryValue.toVitalInt())

            // kg on the wire already. Passthrough for the same reason as temperature.
            Instrument.WEIGHT_SCALE -> VitalsReading(weightKg = dto.primaryValue)

            Instrument.HEART_RATE -> VitalsReading(pulseBpm = dto.primaryValue.toVitalInt())
        }

        return AcquisitionResult.Accepted(
            reading = reading,
            instrument = expectedInstrument,
            sessionId = expectedSessionId,
            deviceType = expectedDeviceType,
            measuredAt = dto.measuredAt,
            synthetic = dto.synthetic,
        )
    }

    /** The frozen instrument to `device_type` table. These strings are the gateway's wire
     *  vocabulary, which is why they live in this dev-only file and not on [Instrument]. */
    fun expectedDeviceType(instrument: Instrument): String = when (instrument) {
        Instrument.BP -> "BLOOD_PRESSURE"
        Instrument.SPO2 -> "PULSE_OXIMETER"
        Instrument.THERMOMETER -> "THERMOMETER"
        Instrument.GLUCOMETER -> "GLUCOMETER"
        Instrument.WEIGHT_SCALE -> "WEIGHT_SCALE"
        Instrument.HEART_RATE -> "HEART_RATE_MONITOR"
    }

    /** Round half up, via [Math.round]. Not `toInt()`, which truncates 173.9 to 173, and not
     *  `roundToInt()`, whose half-way rule is one more thing to argue about in a review. A null
     *  stays null: an absent reading is absent, it is not a zero. */
    private fun Double?.toVitalInt(): Int? = this?.let { Math.round(it).toInt() }
}
