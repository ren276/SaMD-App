package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.vitalssource.HubLocalNetworkNeed
import com.example.samdapp.domain.vitalssource.Instrument

/**
 * Memo P2-R10: the hub Start asks for ACCESS_LOCAL_NETWORK only when the instrument is routed over
 * Wi-Fi AND the gate blocks the gateway host. A BLE route never asks (Q3). [gatewayHost] is read on
 * every call, and the gate reads the grant on every call, so nothing is cached.
 */
class DevHubLocalNetworkNeed(
    private val assignment: HubAssignment,
    private val gate: LocalNetworkGate,
    private val gatewayHost: () -> String,
) : HubLocalNetworkNeed {

    override fun needsRequest(instrument: Instrument): Boolean =
        assignment.routeFor(instrument) is HubRoute.Wifi && gate.blocksHost(gatewayHost())
}
