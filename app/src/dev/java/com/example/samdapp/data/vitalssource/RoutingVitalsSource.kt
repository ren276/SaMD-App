package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.VitalsSource

/**
 * The single [VitalsSource] binding of the dev flavour. It sends each acquisition to the transport
 * [assignment] names for that instrument, and to nothing else:
 *
 * - an unassigned instrument is `Rejected(NOT_SUPPORTED)`, carrying no transport and no hub;
 * - a Wi-Fi instrument goes to [wifi];
 * - a BLE instrument is `Rejected(NOT_SUPPORTED)` carrying the assigned hub and no transport,
 *   until the BLE client exists. There is no BLE delegate here and no call is made.
 *
 * There is no fallback from one transport to the other: a failed Wi-Fi acquisition is a failed
 * acquisition, never a second attempt over Bluetooth, because a reading that came from a different
 * wire than the one assigned is a reading from a source nobody chose. Every result is stamped with
 * the transport and hub it was routed to.
 *
 * [readVitals] returns an empty reading and calls no delegate. Prefill runs on screen open, and an
 * instrument read there would be data nobody asked for (see [PiGatewayVitalsSource]).
 */
class RoutingVitalsSource(
    private val wifi: VitalsSource,
    private val assignment: HubAssignment,
) : VitalsSource {

    /** The transport of the last Start that opened a session, so Stop closes that one and no other. */
    @Volatile
    private var lastStarted: AcquisitionTransport? = null

    override suspend fun readVitals(): VitalsReading = VitalsReading()

    override suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult =
        when (val route = assignment.routeFor(request.instrument)) {
            null -> AcquisitionResult.Rejected(RejectReason.NOT_SUPPORTED)
            is HubRoute.Wifi -> {
                lastStarted = AcquisitionTransport.WIFI
                wifi.startAcquisition(request).stamped(AcquisitionTransport.WIFI, route.hubId)
            }
            is HubRoute.Ble -> {
                // No transport on the result: it is keyed to the generic not-supported text, and
                // the BLE "not paired yet" text must not be reachable while no BLE client exists.
                // lastStarted is left alone: this stub opens no session, so it must not displace a
                // Wi-Fi session still waiting for its Stop.
                AcquisitionResult.Rejected(RejectReason.NOT_SUPPORTED, hubId = route.hubId)
            }
        }

    override suspend fun stopAcquisition() {
        val transport = lastStarted
        lastStarted = null
        when (transport) {
            AcquisitionTransport.WIFI -> wifi.stopAcquisition()
            AcquisitionTransport.BLE, null -> Unit
        }
    }

    private fun AcquisitionResult.stamped(transport: AcquisitionTransport, hubId: String): AcquisitionResult = when (this) {
        is AcquisitionResult.Accepted -> copy(hubId = hubId, transport = transport)
        is AcquisitionResult.Rejected -> copy(hubId = hubId, transport = transport)
    }
}
