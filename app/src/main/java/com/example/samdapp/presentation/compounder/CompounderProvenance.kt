package com.example.samdapp.presentation.compounder

import com.example.samdapp.domain.vitalssource.Instrument

/**
 * Where the value currently sitting in a vitals field came from.
 *
 * Named [VitalsFieldProvenance] rather than the shorter FieldProvenance the design memo proposed,
 * because [com.example.samdapp.domain.model.FieldProvenance] already exists and means something
 * else: the ASR track's per-field voice provenance (TYPED, VOICE_CONFIRMED and so on), which IS
 * persisted, on a Consultation column with its own migration. Two same-named enums about per-field
 * provenance, one persisted and one that must never be, is the kind of pair that eventually gets
 * saved to the wrong column. The names differ so that cannot happen quietly.
 *
 * Deliberately NOT modelled as extra [com.example.samdapp.domain.model.ObservationSource] values.
 * That enum is persisted directly by `ObservationEntity.source`, so growing it would mean a Room
 * migration and a new value for the backend sync accept-set to learn. This one is presentation and
 * audit only and never reaches Room.
 *
 * [DEVICE_EDITED] is the interesting state: an instrument put the number there and a human then
 * typed over it. It does not count as device-sourced in the roll-up, because a number a human typed
 * over is a human's number whatever put it there first.
 */
enum class VitalsFieldProvenance { MANUAL, DEVICE, DEVICE_EDITED }

/**
 * The nine vitals the form holds a provenance mark for. An absent key in
 * [CompounderUiState.fieldProvenance] means [VitalsFieldProvenance.MANUAL], so an untouched form carries
 * no marks at all rather than nine explicit MANUALs.
 *
 * [RESPIRATORY_RATE] and [HEIGHT_CM] are in the enum but appear in no instrument's
 * [writtenFields]: no instrument on the gateway supplies them, so they are always worker-typed.
 * They are here so a later instrument that does supply one has a mark waiting.
 */
enum class VitalsField {
    PULSE_BPM,
    BP_SYSTOLIC,
    BP_DIASTOLIC,
    SPO2_PERCENT,
    TEMPERATURE_CELSIUS,
    RESPIRATORY_RATE,
    WEIGHT_KG,
    HEIGHT_CM,
    BLOOD_GLUCOSE_MG_DL,
}

/**
 * Which form fields an accepted reading from this instrument can populate. Drives the field-level
 * acquiring indicator, so the spinner appears on the fields the worker is actually waiting on.
 *
 * This MUST agree with the instrument-to-field table in `PiMeasurementMapper`, which lives in
 * `src/dev/` and cannot be referenced from here. `PiInstrumentFieldAgreementTest` (testDev) pins
 * the two together so they cannot drift silently.
 */
fun Instrument.writtenFields(): Set<VitalsField> = when (this) {
    Instrument.BP -> setOf(VitalsField.BP_SYSTOLIC, VitalsField.BP_DIASTOLIC, VitalsField.PULSE_BPM)
    Instrument.SPO2 -> setOf(VitalsField.SPO2_PERCENT, VitalsField.PULSE_BPM)
    Instrument.THERMOMETER -> setOf(VitalsField.TEMPERATURE_CELSIUS)
    Instrument.GLUCOMETER -> setOf(VitalsField.BLOOD_GLUCOSE_MG_DL)
    Instrument.WEIGHT_SCALE -> setOf(VitalsField.WEIGHT_KG)
    Instrument.HEART_RATE -> setOf(VitalsField.PULSE_BPM)
}
