package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.BleRoute
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason

/**
 * The Android facts the BLE source needs. The platform implementation answers them; tests use a
 * fake. None of these calls needs the Nearby devices permission except [openLink]'s later connect.
 */
interface BleHubPlatform {
    fun isBluetoothEnabled(): Boolean

    fun hasConnectPermission(): Boolean

    /** The device has the companion-device feature and its system service, so the association
     *  dialog can run at all. */
    fun canAssociate(): Boolean

    /** The address of the companion-device association stored for [hubId], found by its stored id
     *  (G1), or null when none is stored or the system no longer holds it. */
    fun associatedAddress(hubId: String): String?

    /** Disassociates [hubId] and deletes its stored id, so the next Start asks the human again (Q10). */
    fun forgetAssociation(hubId: String)

    /** A fresh link to [address], requiring the SIG service of [instrument]. One per acquisition. */
    fun openLink(address: String, instrument: Instrument): HubLink<*>
}

/**
 * The dev flavour's BLE acquisition (addendum section 5): preparation, then [BleAcquisition].
 *
 * Preparation, in this order (addendum 4.2), each refusal made before anything is connected:
 * 1. API 33 or later (Q1); below it, BLUETOOTH_UNAVAILABLE without touching Bluetooth at all;
 * 2. an instrument with a SIG decoder (BP, SPO2, THERMOMETER); the assignment allows no other;
 * 3. Bluetooth on, else BLUETOOTH_UNAVAILABLE;
 * 4. the stored association; with none, the companion-device dialog through [BlePrepBridge];
 *    dismissed or unanswered, NOT_SUPPORTED ("not paired yet"); the system unable to run it, or a
 *    device without the companion-device feature, BLUETOOTH_UNAVAILABLE;
 * 5. the Nearby devices permission, asked for through [BlePrepBridge] when missing; anything but a
 *    grant is BLUETOOTH_UNAVAILABLE.
 *
 * Preparation has its own 120 s ceiling and sits outside the 25 s cap (Q2). Only after it passes does
 * a real acquisition start: [startAcquisition]'s `onStarted` runs then, and not for a refusal above.
 */
class BleHubVitalsSource(
    private val platform: BleHubPlatform,
    private val bridge: BlePrepBridge,
    private val sdkInt: Int,
) : BleRoute {

    override suspend fun startAcquisition(
        request: AcquisitionRequest,
        hubId: String,
        onStarted: () -> Unit,
    ): AcquisitionResult {
        if (sdkInt < MIN_SDK) return refused(RejectReason.BLUETOOTH_UNAVAILABLE)
        if (request.instrument !in DECODED) return refused(RejectReason.NOT_SUPPORTED)
        if (!platform.isBluetoothEnabled()) return refused(RejectReason.BLUETOOTH_UNAVAILABLE)

        val address = platform.associatedAddress(hubId) ?: when (
            if (platform.canAssociate()) {
                try {
                    bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, hubId)
                } catch (e: HubLinkException) {
                    return linkFailure(e)
                }
            } else {
                BlePrepBridge.Answer.UNAVAILABLE
            }
        ) {
            BlePrepBridge.Answer.GRANTED -> platform.associatedAddress(hubId) ?: return refused(RejectReason.NOT_SUPPORTED)
            BlePrepBridge.Answer.REFUSED, BlePrepBridge.Answer.TIMED_OUT -> return refused(RejectReason.NOT_SUPPORTED)
            BlePrepBridge.Answer.UNAVAILABLE -> return refused(RejectReason.BLUETOOTH_UNAVAILABLE)
        }

        if (!platform.hasConnectPermission()) {
            val answer = try {
                bridge.prepare(BlePrepBridge.Kind.CONNECT_PERMISSION, hubId)
            } catch (e: HubLinkException) {
                return linkFailure(e)
            }
            if (answer != BlePrepBridge.Answer.GRANTED) return refused(RejectReason.BLUETOOTH_UNAVAILABLE)
        }

        onStarted()
        val link = try {
            platform.openLink(address, request.instrument)
        } catch (e: HubLinkException) {
            return linkFailure(e)
        }
        val outcome = BleAcquisition.run(link, request, hubId)
        if (outcome.disSerialMismatch) platform.forgetAssociation(hubId)
        return outcome.result
    }

    private fun refused(reason: RejectReason) = AcquisitionResult.Rejected(reason)

    /** A link failure from preparation or from opening the link maps as any link failure does
     *  (addendum 5.4), before anything is connected, rather than escaping the source. */
    private fun linkFailure(e: HubLinkException) = refused(BleAcquisition.reasonFor(e.failure, connected = false))

    companion object {
        /** `Build.VERSION_CODES.TIRAMISU`: `getMyAssociations()` and the executor `associate()` (3.2). */
        const val MIN_SDK = 33

        private val DECODED = setOf(Instrument.BP, Instrument.SPO2, Instrument.THERMOMETER)
    }
}
