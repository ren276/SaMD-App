package com.example.samdapp.domain.vitalssource

import com.example.samdapp.domain.model.VitalsReading

/** Seam a real BLE/device integration plugs into later. [readVitals] returns whichever
 * device-pollable vitals are available — never [com.example.samdapp.domain.model.VitalsSnapshot]'s
 * manual-only fields (pain score, urinalysis), and the caller may still edit every value before save.
 *
 * [startAcquisition] and [stopAcquisition] widen the seam for worker-triggered acquisition from an
 * external instrument gateway. Both carry default bodies on purpose: every existing implementer
 * ([com.example.samdapp.data.vitalssource.UnavailableVitalsSource], the dev-only mock),
 * [com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase] and both production DI modules
 * compile unchanged, so there is exactly one vitals seam and one binding rather than a second
 * clinical pipeline alongside this one. A source that has no instrument behind it rejects with
 * [RejectReason.NOT_SUPPORTED] rather than fabricating a reading. */
interface VitalsSource {
    suspend fun readVitals(): VitalsReading

    /** Asks the instrument named in [request] for one reading. Implementations must reject rather
     *  than substitute: there is no default value for a measurement that did not arrive. */
    suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult =
        AcquisitionResult.Rejected(RejectReason.NOT_SUPPORTED)

    /** Ends any acquisition session this source has open. Idempotent, best effort, never throws. */
    suspend fun stopAcquisition() = Unit
}

/** Instruments an acquisition can ask for. One instrument per acquisition; there is no composite
 *  route. The name is what goes on the wire. */
enum class Instrument { BP, SPO2, THERMOMETER, GLUCOMETER, WEIGHT_SCALE, HEART_RATE }

/** Which reading profile the gateway should emit. Dev/demo control only: it selects a synthetic
 *  profile on an emulator, it does not configure a clinical instrument. */
enum class Scenario { NORMAL, LOW, HIGH, DEVICE_ERROR, SENSOR_UNAVAILABLE }

/** Why a measurement was not admitted. Every one of these is a refusal to write a number into the
 *  form; none of them degrades into a partial or substituted reading. */
enum class RejectReason {
    /** This source has no instrument gateway behind it. The default seam answer. */
    NOT_SUPPORTED,

    /** RC-2: `quality_status` was not exactly "OK". Covers the gateway's fault and lead-off
     *  profiles, which report 0.0 values that would otherwise read as an emergency. */
    QUALITY_STATUS_NOT_OK,

    /** RC-1: the measurement did not carry the session id this acquisition started. A missing id
     *  counts as a mismatch. */
    SESSION_ID_MISMATCH,

    /** RC-1: the measurement came from a different instrument than the one requested. */
    DEVICE_TYPE_MISMATCH,

    /** The gateway had no measurement to give. */
    NO_MEASUREMENT,

    /** The gateway could not be reached. */
    UNREACHABLE,

    /** Local-network access is denied to this app. */
    PERMISSION_DENIED,

    /** The gateway answered with something this app cannot parse or that omits the session id. */
    MALFORMED,

    /** The gateway accepted the connection and then did not answer in time. */
    TIMEOUT,
}

data class AcquisitionRequest(
    val instrument: Instrument,
    val scenario: Scenario = Scenario.NORMAL,
)

sealed interface AcquisitionResult {

    /** [reading] carries only the fields the requested instrument supplies; every other field is
     *  null. The remaining values are provenance for the audit trail, not clinical data. */
    data class Accepted(
        val reading: VitalsReading,
        val instrument: Instrument,
        val sessionId: String,
        val deviceType: String,
        val measuredAt: String? = null,
        val synthetic: Boolean? = null,
    ) : AcquisitionResult

    data class Rejected(val reason: RejectReason) : AcquisitionResult
}
