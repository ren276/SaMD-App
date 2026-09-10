package com.example.samdapp.data.vitalssource

import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.di.PiGatewayNetworkModule
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Runs only under `testDevDebugUnitTest`, since [PiGatewayVitalsSource] exists only in `src/dev/`.
 *
 * The client under test is the one [PiGatewayNetworkModule] actually provides, not a convenience
 * client built here, so the "no Authorization header" test below is a real assertion about the
 * production wiring rather than about this file's own setup.
 */
class PiGatewayVitalsSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: PiGatewayVitalsSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(PiGatewayNetworkModule.providePiGatewayOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
            .build()
            .create(PiGatewayApi::class.java)
        source = PiGatewayVitalsSource(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun startBody(sessionId: String) =
        """{"status":"STARTED","session_id":"$sessionId","instrument":"BP","reading_count":1}"""

    private fun bpMeasurementBody(sessionId: String) = """
        {
          "device_type": "BLOOD_PRESSURE",
          "quality_status": "OK",
          "primary_value": 173.5,
          "secondary_value": 105.0,
          "tertiary_value": 112.0,
          "measured_at": "2026-09-10T06:00:00Z",
          "synthetic": true,
          "provenance": {"session_id": "$sessionId", "transport": "WIFI"}
        }
    """.trimIndent()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private val bpRequest = AcquisitionRequest(Instrument.BP, Scenario.HIGH)

    @Test
    fun `accepts a correlated measurement and maps its values`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A")))

        val result = source.startAcquisition(bpRequest)

        assertTrue("expected Accepted, was $result", result is AcquisitionResult.Accepted)
        val accepted = result as AcquisitionResult.Accepted
        assertEquals(174, accepted.reading.bpSystolic)
        assertEquals(105, accepted.reading.bpDiastolic)
        assertEquals(112, accepted.reading.pulseBpm)
        assertEquals("session-A", accepted.sessionId)

        val startRequest = server.takeRequest()
        assertEquals("/api/v1/session/start", startRequest.path)
        val sent = startRequest.body.readUtf8()
        assertTrue(sent, sent.contains("\"instrument\":\"BP\""))
        assertTrue(sent, sent.contains("\"transport\":\"WIFI\""))
        assertTrue(sent, sent.contains("\"scenario\":\"HIGH\""))
        assertTrue(sent, sent.contains("\"reading_count\":1"))
        assertEquals("/api/v1/measurement", server.takeRequest().path)
    }

    /**
     * The gateway leaves its last measurement in place after a stop, so the very next acquisition
     * can be answered with the previous session's reading. Written to pass whether or not the
     * gateway ever fixes that residue: the rejection is ours, and it does not depend on the
     * accessory behaving.
     */
    @Test
    fun `rejects stale residue from a previous session after a stop`() = runTest {
        server.enqueue(ok("""{"status":"STOPPED","readings_emitted":1}"""))
        server.enqueue(ok(startBody("session-B")))
        server.enqueue(ok(bpMeasurementBody("session-A")))

        source.stopAcquisition()
        val result = source.startAcquisition(bpRequest)

        assertEquals(
            RejectReason.SESSION_ID_MISMATCH,
            (result as AcquisitionResult.Rejected).reason,
        )
    }

    @Test
    fun `rejects a 404 as no measurement, not as an empty reading`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"detail":"NO_MEASUREMENT_AVAILABLE"}"""),
        )

        val result = source.startAcquisition(bpRequest)

        assertTrue("expected Rejected, was $result", result is AcquisitionResult.Rejected)
        assertEquals(RejectReason.NO_MEASUREMENT, (result as AcquisitionResult.Rejected).reason)
    }

    /**
     * RC-4, the absence half of the accessory-boundary argument: opening the screen runs prefill,
     * and prefill must not reach the instrument. Nothing is acquired until a worker asks.
     */
    @Test
    fun `readVitals touches the network zero times and returns an empty reading`() = runTest {
        val reading = source.readVitals()

        assertEquals(0, server.requestCount)
        assertNull(reading.bpSystolic)
        assertNull(reading.pulseBpm)
        assertNull(reading.spo2Percent)
        assertNull(reading.temperatureCelsius)
        assertTrue(!reading.hasAnyValue())
    }

    @Test
    fun `rejects an unreachable gateway`() = runTest {
        server.shutdown()

        val result = source.startAcquisition(bpRequest)

        assertTrue("expected Rejected, was $result", result is AcquisitionResult.Rejected)
        assertEquals(RejectReason.UNREACHABLE, (result as AcquisitionResult.Rejected).reason)
    }

    /**
     * Pins the structural absence of `BearerInterceptor` and `TokenAuthenticator` on this stack.
     * The gateway is an accessory on the same Wi-Fi as the handset; the backend access token has
     * no business travelling there, and a 401 from it must not drive a token refresh.
     */
    @Test
    fun `sends no Authorization header to the gateway on any call`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A")))
        server.enqueue(ok("""{"status":"STOPPED","readings_emitted":1}"""))

        source.startAcquisition(bpRequest)
        source.stopAcquisition()

        assertEquals(3, server.requestCount)
        repeat(3) {
            val request = server.takeRequest()
            assertNull(
                "Authorization must never be sent to ${request.path}",
                request.getHeader("Authorization"),
            )
        }
    }
}
