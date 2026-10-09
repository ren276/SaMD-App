package com.example.samdapp.data.vitalssource

import com.example.samdapp.R
import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.di.PiGatewayNetworkModule
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
    private val wifi = CountingSource(wifiAccepted)
    private val router = RoutingVitalsSource(wifi, assignment)

    private fun request(instrument: Instrument) = AcquisitionRequest(instrument)

    @Test
    fun `a Wi-Fi instrument goes to the Wi-Fi source, stamped with its transport and hub`() = runTest {
        val result = router.startAcquisition(request(Instrument.SPO2)) as AcquisitionResult.Accepted

        assertEquals(listOf(request(Instrument.SPO2)), wifi.starts)
        assertEquals(AcquisitionTransport.WIFI, result.transport)
        assertEquals("kernelhub1", result.hubId)
        assertEquals(97, result.reading.spo2Percent)
    }

    @Test
    fun `a Wi-Fi rejection is stamped too and is never retried over another wire`() = runTest {
        wifi.next = AcquisitionResult.Rejected(RejectReason.UNREACHABLE)

        val result = router.startAcquisition(request(Instrument.SPO2)) as AcquisitionResult.Rejected

        assertEquals(RejectReason.UNREACHABLE, result.reason)
        assertEquals(AcquisitionTransport.WIFI, result.transport)
        assertEquals("kernelhub1", result.hubId)
        assertEquals("one attempt, no fallback", 1, wifi.starts.size)
    }

    @Test
    fun `a BLE instrument is refused as NOT_SUPPORTED without touching the Wi-Fi source`() = runTest {
        val result = router.startAcquisition(request(Instrument.BP)) as AcquisitionResult.Rejected

        assertEquals(RejectReason.NOT_SUPPORTED, result.reason)
        assertNull("no BLE client exists, so no result may claim the BLE transport", result.transport)
        assertEquals("kernelhub2", result.hubId)
        assertTrue("a BLE reject must never reach the Wi-Fi source", wifi.starts.isEmpty())
        assertEquals(0, wifi.reads)
        assertEquals(0, wifi.stops)
    }

    /** R1. While there is no BLE client the stub's refusal must read as the generic not-supported
     *  text. "This instrument has not been paired with the app yet" is keyed to BLE and would tell
     *  a worker something false. */
    @Test
    fun `a BP Start shows the generic not-supported text, never the BLE not-paired text`() = runTest {
        val result = router.startAcquisition(request(Instrument.BP)) as AcquisitionResult.Rejected

        assertEquals(R.string.acq_reject_not_supported, acquisitionRejectionRes(result.reason, result.transport, sdkInt = 36))
    }

    @Test
    fun `an unassigned instrument is NOT_SUPPORTED with no transport and no hub`() = runTest {
        val result = router.startAcquisition(request(Instrument.GLUCOMETER)) as AcquisitionResult.Rejected

        assertEquals(RejectReason.NOT_SUPPORTED, result.reason)
        assertNull(result.transport)
        assertNull(result.hubId)
        assertTrue(wifi.starts.isEmpty())
    }

    @Test
    fun `an assignment that serves nothing refuses every instrument`() = runTest {
        val none = RoutingVitalsSource(wifi, HubAssignment.NONE)

        Instrument.entries.forEach { instrument ->
            val result = none.startAcquisition(request(instrument))
            assertEquals("$instrument", RejectReason.NOT_SUPPORTED, (result as AcquisitionResult.Rejected).reason)
        }
        assertTrue(wifi.starts.isEmpty())
    }

    @Test
    fun `Stop closes only the transport the last Start went to, and only once`() = runTest {
        router.startAcquisition(request(Instrument.SPO2))
        router.stopAcquisition()
        assertEquals(1, wifi.stops)

        router.stopAcquisition()
        assertEquals("the second Stop has nothing to close", 1, wifi.stops)
    }

    /** A refused BLE Start opens no session, so it must not displace the Wi-Fi session that is
     *  still waiting for its Stop (for example after NO_MEASUREMENT). */
    @Test
    fun `a refused BLE Start leaves the Wi-Fi session open for Stop`() = runTest {
        router.startAcquisition(request(Instrument.SPO2))
        router.startAcquisition(request(Instrument.BP))
        router.stopAcquisition()

        assertEquals(1, wifi.stops)
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
    }

    @Test
    fun `readVitals through the real Wi-Fi source makes zero HTTP requests`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(PiGatewayNetworkModule.piGatewayOkHttpClient("kernelhub1"))
                .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
                .build()
                .create(PiGatewayApi::class.java)
            val real = RoutingVitalsSource(PiGatewayVitalsSource(api, "kernelhub1"), assignment)

            val reading = real.readVitals()

            assertEquals(0, server.requestCount)
            assertTrue(!reading.hasAnyValue())
        } finally {
            server.shutdown()
        }
    }
}
