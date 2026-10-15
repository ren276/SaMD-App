package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.VitalsSource

/** The BLE side of the router: one acquisition from the hub [hubId] names. [onStarted] runs once
 *  preparation has passed and a real BLE acquisition begins, and never for a refusal before that. */
fun interface BleRoute {
    suspend fun startAcquisition(request: AcquisitionRequest, hubId: String, onStarted: () -> Unit): AcquisitionResult
}

/**
 * The single [VitalsSource] binding of the dev flavour. It sends each acquisition to the transport
 * [assignment] names for that instrument, and to nothing else:
 *
 * - an unassigned instrument is `Rejected(NOT_SUPPORTED)`, carrying no transport and no hub;
 * - a Wi-Fi instrument goes to [wifi];
 * - a BLE instrument goes to [ble].
 *
 * There is no fallback from one transport to the other: a failed acquisition is a failed
 * acquisition, never a second attempt over the other wire, because a reading that came from a
 * different wire than the one assigned is a reading from a source nobody chose. Every routed result
 * is stamped with the transport and hub it was routed to.
 *
 * [readVitals] returns an empty reading and calls no delegate. Prefill runs on screen open, and an
 * instrument read there would be data nobody asked for (see [PiGatewayVitalsSource]).
 */
class RoutingVitalsSource(
    private val wifi: VitalsSource,
    private val ble: BleRoute,
    private val assignment: HubAssignment,
) : VitalsSource {

    /** The transport of the last Start that opened a session, so Stop closes that one and no other.
     *  BLE counts only once its preparation passed: a refused BLE Start opens nothing and must not
     *  displace a Wi-Fi session still waiting for its Stop. */
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
            is HubRoute.Ble ->
                ble.startAcquisition(request, route.hubId) { lastStarted = AcquisitionTransport.BLE }
                    .stamped(AcquisitionTransport.BLE, route.hubId)
        }

    /** A BLE session needs nothing here: the acquisition owns its connection, and Stop or screen exit
     *  cancels it, which runs its own STOP, disconnect and close. */
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
