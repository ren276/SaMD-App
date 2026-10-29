package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.vitalssource.Instrument
import org.junit.Assert.assertEquals
import org.junit.Test

/** Memo Q3, P2-R10, dev implementation: a request only for a Wi-Fi route the gate blocks. */
class DevHubLocalNetworkNeedTest {

    // SPO2 is routed over Wi-Fi to the hub at the gateway; BP over BLE to a second hub.
    private val assignment = HubAssignment.parse("SPO2=wifi:kernelhub1,BP=ble:kernelhub2")

    private fun need(sdkInt: Int = 37, granted: Boolean = false, host: String = "kernelhub1.local") =
        DevHubLocalNetworkNeed(assignment, LocalNetworkGate(sdkInt) { granted }) { host }

    @Test
    fun `a Wi-Fi route to a blocked local gateway requests`() {
        assertEquals(true, need().needsRequest(Instrument.SPO2))
        assertEquals("an IP literal gateway too", true, need(host = "192.168.1.20").needsRequest(Instrument.SPO2))
    }

    @Test
    fun `a Wi-Fi route with the grant does not request`() {
        assertEquals(false, need(granted = true).needsRequest(Instrument.SPO2))
    }

    @Test
    fun `a BLE route never requests, in every permission state`() {
        for (granted in listOf(false, true)) for (sdk in listOf(36, 37)) {
            assertEquals("granted=$granted sdk=$sdk", false, need(sdk, granted).needsRequest(Instrument.BP))
        }
    }

    @Test
    fun `an unassigned instrument does not request`() {
        assertEquals(false, need().needsRequest(Instrument.THERMOMETER))
    }

    @Test
    fun `a loopback gateway does not request`() {
        assertEquals(false, need(host = "127.0.0.1").needsRequest(Instrument.SPO2))
    }

    @Test
    fun `below SDK 37 nothing requests`() {
        assertEquals(false, need(sdkInt = 36).needsRequest(Instrument.SPO2))
    }

    @Test
    fun `the gateway host is read on every call, and so is the grant`() {
        var host = "127.0.0.1"
        var granted = false
        val need = DevHubLocalNetworkNeed(assignment, LocalNetworkGate(37) { granted }) { host }
        assertEquals(false, need.needsRequest(Instrument.SPO2))
        host = "kernelhub1.local"
        assertEquals(true, need.needsRequest(Instrument.SPO2))
        granted = true
        assertEquals(false, need.needsRequest(Instrument.SPO2))
    }
}
