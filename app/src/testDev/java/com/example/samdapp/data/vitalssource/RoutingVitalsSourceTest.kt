package com.example.samdapp.data.vitalssource

import com.example.samdapp.R
import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.di.PiGatewayNetworkModule
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.VitalsSource
import com.example.samdapp.presentation.compounder.acquisitionRejectionRes
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Counts what reaches it, so a test can assert what was NOT called. */
private class CountingSource(var next: AcquisitionResult) : VitalsSource {
    var reads = 0
    val starts = mutableListOf<AcquisitionRequest>()
    var stops = 0

    override suspend fun readVitals(): VitalsReading {
        reads++
        return VitalsReading(pulseBpm = 99)
    }

    override suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult {
        starts += request
        return next
    }

    override suspend fun stopAcquisition() {
        stops++
    }
}

/** The BLE side as the router sees it. [begin] says whether preparation passed, so `onStarted` runs. */
private class CountingBle(var next: AcquisitionResult, var begin: Boolean = true) : BleRoute {
    val starts = mutableListOf<Pair<AcquisitionRequest, String>>()

    override suspend fun startAcquisition(request: AcquisitionRequest, hubId: String, onStarted: () -> Unit): AcquisitionResult {
        starts += request to hubId
        if (begin) onStarted()
        return next
    }
}

/** G-B1 and G-B8. */
class RoutingVitalsSourceTest {

    /** A literal of this test's own, not a constant from production code: Gradle holds the one default. */
    private val SHIPPED_DEFAULT = "SPO2=wifi:kernelhub1,BP=ble:kernelhub2"

    private val assignment = HubAssignment.parse(SHIPPED_DEFAULT) // SPO2 wifi:kernelhub1, BP ble:kernelhub2
    private val wifiAccepted = AcquisitionResult.Accepted(
        reading = VitalsReading(spo2Percent = 97),
        instrument = Instrument.SPO2,
        sessionId = "s",
        deviceType = "PULSE_OXIMETER",
    )
    private val bleAccepted = AcquisitionResult.Accepted(
        reading = VitalsReading(bpSystolic = 120, bpDiastolic = 80),
        instrument = Instrument.BP,
        sessionId = "n",
        deviceType = "BLOOD_PRESSURE",
    )
    private val wifi = CountingSource(wifiAccepted)
    private val ble = CountingBle(bleAccepted)
    private val router = RoutingVitalsSource(wifi, ble, assignment)

    private fun request(instrument: Instrument) = AcquisitionRequest(instrument)

    @Test
    fun `a Wi-Fi instrument goes to the Wi-Fi source, stamped with its transport and hub`() = runTest {
        val result = router.startAcquisition(request(Instrument.SPO2)).expect<AcquisitionResult.Accepted>()

        assertEquals(listOf(request(Instrument.SPO2)), wifi.starts)
        assertEquals(AcquisitionTransport.WIFI, result.transport)
        assertEquals("kernelhub1", result.hubId)
        assertEquals(97, result.reading.spo2Percent)
    }

    @Test
    fun `a Wi-Fi rejection is stamped too and is never retried over another wire`() = runTest {
        wifi.next = AcquisitionResult.Rejected(RejectReason.UNREACHABLE)

        val result = router.startAcquisition(request(Instrument.SPO2)).expect<AcquisitionResult.Rejected>()

        assertEquals(RejectReason.UNREACHABLE, result.reason)
        assertEquals(AcquisitionTransport.WIFI, result.transport)
        assertEquals("kernelhub1", result.hubId)
        assertEquals("one attempt, no fallback", 1, wifi.starts.size)
    }

    @Test
    fun `a BLE instrument goes to the BLE source with its hub, stamped BLE, without touching Wi-Fi`() = runTest {
        val result = router.startAcquisition(request(Instrument.BP)).expect<AcquisitionResult.Accepted>()

        assertEquals(listOf(request(Instrument.BP) to "kernelhub2"), ble.starts)
        assertEquals(AcquisitionTransport.BLE, result.transport)
        assertEquals("kernelhub2", result.hubId)
        assertTrue(wifi.starts.isEmpty())
        assertEquals(0, wifi.reads)
        assertEquals(0, wifi.stops)
    }

    @Test
    fun `a BLE rejection is stamped and never retried over Wi-Fi`() = runTest {
        ble.next = AcquisitionResult.Rejected(RejectReason.UNREACHABLE)

        val result = router.startAcquisition(request(Instrument.BP)).expect<AcquisitionResult.Rejected>()

        assertEquals(RejectReason.UNREACHABLE, result.reason)
        assertEquals(AcquisitionTransport.BLE, result.transport)
        assertEquals("kernelhub2", result.hubId)
        assertEquals("one attempt", 1, ble.starts.size)
        assertTrue("a BLE reject must never reach the Wi-Fi source", wifi.starts.isEmpty())
    }

    /** With the BLE client in place a BLE NOT_SUPPORTED means "not paired yet", and reads so. */
    @Test
    fun `a BLE NOT_SUPPORTED shows the not-paired text`() = runTest {
        ble.next = AcquisitionResult.Rejected(RejectReason.NOT_SUPPORTED)

        val result = router.startAcquisition(request(Instrument.BP)).expect<AcquisitionResult.Rejected>()

        assertEquals(R.string.acq_reject_not_paired_ble, acquisitionRejectionRes(result.reason, result.transport, sdkInt = 36))
    }

    @Test
    fun `an unassigned instrument is NOT_SUPPORTED with no transport and no hub`() = runTest {
        val result = router.startAcquisition(request(Instrument.GLUCOMETER)).expect<AcquisitionResult.Rejected>()

        assertEquals(RejectReason.NOT_SUPPORTED, result.reason)
        assertNull(result.transport)
        assertNull(result.hubId)
        assertTrue(wifi.starts.isEmpty())
    }

    @Test
    fun `an assignment that serves nothing refuses every instrument`() = runTest {
        val none = RoutingVitalsSource(wifi, ble, HubAssignment.NONE)

        Instrument.entries.forEach { instrument ->
            val result = none.startAcquisition(request(instrument))
            assertEquals("$instrument", RejectReason.NOT_SUPPORTED, result.expect<AcquisitionResult.Rejected>().reason)
        }
        assertTrue(wifi.starts.isEmpty())
        assertTrue(ble.starts.isEmpty())
    }

    @Test
    fun `Stop closes only the transport the last Start went to, and only once`() = runTest {
        router.startAcquisition(request(Instrument.SPO2))
        router.stopAcquisition()
        assertEquals(1, wifi.stops)

        router.stopAcquisition()
        assertEquals("the second Stop has nothing to close", 1, wifi.stops)
    }

    /** A BLE Start refused in preparation opens no session, so it must not displace the Wi-Fi
     *  session that is still waiting for its Stop (for example after NO_MEASUREMENT). */
    @Test
    fun `a BLE Start refused in preparation leaves the Wi-Fi session open for Stop`() = runTest {
        ble.begin = false
        ble.next = AcquisitionResult.Rejected(RejectReason.BLUETOOTH_UNAVAILABLE)
        router.startAcquisition(request(Instrument.SPO2))
        router.startAcquisition(request(Instrument.BP))
        router.stopAcquisition()

        assertEquals(1, wifi.stops)
    }

    /** A BLE acquisition that began owns the session now; its own teardown closes it, so Stop sends
     *  nothing to the Wi-Fi hub. */
    @Test
    fun `a BLE acquisition that began takes the Stop away from Wi-Fi`() = runTest {
        router.startAcquisition(request(Instrument.SPO2))
        router.startAcquisition(request(Instrument.BP))
        router.stopAcquisition()

        assertEquals(0, wifi.stops)
    }

    @Test
    fun `Stop after only a BLE Start closes nothing`() = runTest {
        router.startAcquisition(request(Instrument.BP))
        router.stopAcquisition()

        assertEquals(0, wifi.stops)
    }

    @Test
    fun `Stop with nothing started is a no-op`() = runTest {
        router.stopAcquisition()

        assertEquals(0, wifi.stops)
    }

    // --- G-B8 ----------------------------------------------------------------------------------

    @Test
    fun `readVitals returns an empty reading and calls no delegate`() = runTest {
        val reading = router.readVitals()

        assertTrue(!reading.hasAnyValue())
        assertEquals(0, wifi.reads)
        assertTrue(wifi.starts.isEmpty())
        assertEquals(0, wifi.stops)
        assertTrue("readVitals makes no BLE call", ble.starts.isEmpty())
    }

    @Test
    fun `readVitals through the real Wi-Fi source makes zero HTTP requests`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(PiGatewayNetworkModule.piGatewayOkHttpClient("kernelhub1", gate = LocalNetworkGate(sdkInt = 0) { true }))
                .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
                .build()
                .create(PiGatewayApi::class.java)
            val real = RoutingVitalsSource(PiGatewayVitalsSource(api, "kernelhub1"), ble, assignment)

            val reading = real.readVitals()

            assertEquals(0, server.requestCount)
            assertTrue(!reading.hasAnyValue())
        } finally {
            server.shutdown()
        }
    }
}
