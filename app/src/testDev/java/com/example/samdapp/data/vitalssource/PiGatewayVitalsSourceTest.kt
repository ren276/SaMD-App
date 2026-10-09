package com.example.samdapp.data.vitalssource

import com.example.samdapp.data.remote.SyncGson
import com.example.samdapp.di.PiGatewayNetworkModule
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
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
            .client(PiGatewayNetworkModule.piGatewayOkHttpClient(HUB))
            .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
            .build()
            .create(PiGatewayApi::class.java)
        source = PiGatewayVitalsSource(api, expectedHubId = HUB)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun startBody(sessionId: String, hubId: String = HUB) =
        """{"hub_id":"$hubId","status":"STARTED","session_id":"$sessionId","instrument":"BP","reading_count":1}"""

    private fun bpMeasurementBody(sessionId: String, hubId: String = HUB) = """
        {
          "hub_id": "$hubId",
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

    /** A response as the hub sends it: the `X-SaMDPi-Hub-Id` header on every answer. */
    private fun hubResponse(code: Int, body: String, header: String? = HUB) =
        MockResponse().setResponseCode(code).setBody(body).apply {
            if (header != null) setHeader("X-SaMDPi-Hub-Id", header)
        }

    private fun ok(body: String) = hubResponse(200, body)

    private fun errorBody(error: String, hubId: String = HUB) = """{"error":"$error","hub_id":"$hubId"}"""

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
        server.enqueue(ok("""{"hub_id":"$HUB","status":"STOPPED","readings_emitted":1}"""))
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
            hubResponse(404, errorBody("NO_MEASUREMENT_AVAILABLE")),
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
        server.enqueue(ok("""{"hub_id":"$HUB","status":"STOPPED","readings_emitted":1}"""))

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

    // --- B4: every result names the hub and the transport -------------------------------------

    @Test
    fun `an accepted result carries the Wi-Fi transport, the hub and the emulator build`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(
            ok(
                bpMeasurementBody("session-A").replace(
                    "\"synthetic\": true,",
                    "\"synthetic\": true, \"emulator_build\": {\"sha\": \"abc1234\", \"dirty\": false, \"merged\": true},",
                ),
            ),
        )

        val accepted = source.startAcquisition(bpRequest) as AcquisitionResult.Accepted

        assertEquals(HUB, accepted.hubId)
        assertEquals(AcquisitionTransport.WIFI, accepted.transport)
        assertEquals("abc1234", accepted.emulatorBuild)
    }

    @Test
    fun `a dirty or unmerged emulator build carries the hub's own suffixes`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(
            ok(
                bpMeasurementBody("session-A").replace(
                    "\"synthetic\": true,",
                    "\"synthetic\": true, \"emulator_build\": {\"sha\": \"94c997ddf\", \"dirty\": false, \"merged\": false},",
                ),
            ),
        )

        val accepted = source.startAcquisition(bpRequest) as AcquisitionResult.Accepted

        assertEquals("94c997ddf-unmerged", accepted.emulatorBuild)
    }

    @Test
    fun `an accepted result with no emulator build reports none rather than a default`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A")))

        val accepted = source.startAcquisition(bpRequest) as AcquisitionResult.Accepted

        assertNull(accepted.emulatorBuild)
    }

    @Test
    fun `a rejected result carries the Wi-Fi transport and the assigned hub`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(hubResponse(404, errorBody("NO_MEASUREMENT_AVAILABLE")))

        val rejected = source.startAcquisition(bpRequest) as AcquisitionResult.Rejected

        assertEquals(HUB, rejected.hubId)
        assertEquals(AcquisitionTransport.WIFI, rejected.transport)
    }

    // --- G-B16: the hub_id check, on every response and in both places --------------------------

    private suspend fun reasonOf(result: AcquisitionResult) =
        (result as? AcquisitionResult.Rejected)?.reason ?: error("expected Rejected, was $result")

    @Test
    fun `a wrong or missing hub header on the start call is HUB_MISMATCH for a 200, a 400 and a 404`() = runTest {
        for (code in listOf(200, 400, 404)) for (header in listOf("laptop-docker", null)) {
            // The body names the right hub, so only the header can be what is refused.
            val body = if (code == 200) startBody("session-A") else errorBody("MALFORMED_REQUEST")
            server.enqueue(hubResponse(code, body, header))

            val result = source.startAcquisition(bpRequest)

            assertEquals("start $code header=$header", RejectReason.HUB_MISMATCH, reasonOf(result))
        }
    }

    @Test
    fun `a wrong or missing hub header on the measurement call is HUB_MISMATCH for a 200, a 400 and a 404`() = runTest {
        for (code in listOf(200, 400, 404)) for (header in listOf("laptop-docker", null)) {
            server.enqueue(ok(startBody("session-A")))
            val body = when (code) {
                200 -> bpMeasurementBody("session-A")
                400 -> errorBody("INVALID_SESSION_CONFIG")
                else -> errorBody("NO_MEASUREMENT_AVAILABLE")
            }
            server.enqueue(hubResponse(code, body, header))

            val result = source.startAcquisition(bpRequest)

            assertEquals("measurement $code header=$header", RejectReason.HUB_MISMATCH, reasonOf(result))
        }
    }

    @Test
    fun `a body hub_id that disagrees with a matching header is HUB_MISMATCH on every answer`() = runTest {
        server.enqueue(ok(startBody("session-A", hubId = "laptop-docker")))
        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(source.startAcquisition(bpRequest)))

        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A", hubId = "laptop-docker")))
        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(source.startAcquisition(bpRequest)))

        server.enqueue(ok(startBody("session-A")))
        server.enqueue(hubResponse(404, errorBody("NO_MEASUREMENT_AVAILABLE", hubId = "laptop-docker")))
        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(source.startAcquisition(bpRequest)))
    }

    @Test
    fun `a body with no hub_id at all is HUB_MISMATCH, never a pass`() = runTest {
        server.enqueue(ok("""{"status":"STARTED","session_id":"session-A"}"""))
        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(source.startAcquisition(bpRequest)))

        server.enqueue(ok(startBody("session-A")))
        server.enqueue(hubResponse(404, """{"error":"NO_MEASUREMENT_AVAILABLE"}"""))
        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(source.startAcquisition(bpRequest)))
    }

    @Test
    fun `a mismatched stop call is swallowed, clears the session and does not throw`() = runTest {
        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A")))
        source.startAcquisition(bpRequest)
        assertEquals("session-A", source.activeSessionId)
        server.enqueue(hubResponse(200, """{"hub_id":"laptop-docker","status":"STOPPED"}""", header = "laptop-docker"))

        source.stopAcquisition()

        assertNull(source.activeSessionId)
    }

    @Test
    fun `with no Wi-Fi hub assigned every answer is refused, even one with an empty hub header`() = runTest {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(PiGatewayNetworkModule.piGatewayOkHttpClient(expectedHubId = ""))
            .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
            .build()
            .create(PiGatewayApi::class.java)
        val unassigned = PiGatewayVitalsSource(api, expectedHubId = "")
        server.enqueue(hubResponse(200, startBody("session-A", hubId = ""), header = ""))

        assertEquals(RejectReason.HUB_MISMATCH, reasonOf(unassigned.startAcquisition(bpRequest)))
    }

    // --- G-B17: the error-body table, one case per row ----------------------------------------

    private data class ErrorRow(val status: Int, val error: String, val expected: RejectReason)

    private val errorTable = listOf(
        ErrorRow(400, "INSTRUMENT_NOT_SERVED", RejectReason.HUB_MISMATCH),
        ErrorRow(400, "INVALID_SESSION_CONFIG", RejectReason.MALFORMED),
        ErrorRow(400, "MALFORMED_REQUEST", RejectReason.MALFORMED),
        ErrorRow(400, "MALFORMED_JSON", RejectReason.MALFORMED),
        ErrorRow(400, "HTTP_ERROR", RejectReason.MALFORMED),
        ErrorRow(431, "HTTP_ERROR", RejectReason.MALFORMED),
        ErrorRow(501, "HTTP_ERROR", RejectReason.MALFORMED),
        ErrorRow(404, "NO_MEASUREMENT_AVAILABLE", RejectReason.NO_MEASUREMENT),
        ErrorRow(404, "NOT_FOUND", RejectReason.MALFORMED),
        ErrorRow(500, "SOMETHING_NEW", RejectReason.MALFORMED),
        // The same codes under another status are not the table's rows, so they fall to the default.
        ErrorRow(500, "INSTRUMENT_NOT_SERVED", RejectReason.MALFORMED),
        ErrorRow(500, "NO_MEASUREMENT_AVAILABLE", RejectReason.MALFORMED),
        ErrorRow(404, "INSTRUMENT_NOT_SERVED", RejectReason.MALFORMED),
    )

    @Test
    fun `every row of the error table maps as documented on the start call`() = runTest {
        for (row in errorTable) {
            server.enqueue(hubResponse(row.status, errorBody(row.error)))

            val result = source.startAcquisition(bpRequest)

            assertEquals("start ${row.status} ${row.error}", row.expected, reasonOf(result))
        }
    }

    @Test
    fun `every row of the error table maps as documented on the measurement call`() = runTest {
        for (row in errorTable) {
            server.enqueue(ok(startBody("session-A")))
            server.enqueue(hubResponse(row.status, errorBody(row.error)))

            val result = source.startAcquisition(bpRequest)

            assertEquals("measurement ${row.status} ${row.error}", row.expected, reasonOf(result))
        }
    }

    @Test
    fun `an error body that cannot be read is MALFORMED`() = runTest {
        for (body in listOf("", "not json", "[1,2]", "null")) {
            server.enqueue(hubResponse(400, body))

            assertEquals("body '$body'", RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))
        }
    }

    @Test
    fun `no Wi-Fi failure ever reports a Bluetooth reason`() = runTest {
        val seen = mutableSetOf<RejectReason>()
        for (row in errorTable) {
            server.enqueue(hubResponse(row.status, errorBody(row.error)))
            seen += reasonOf(source.startAcquisition(bpRequest))
        }
        server.enqueue(hubResponse(200, startBody("session-A"), header = null))
        seen += reasonOf(source.startAcquisition(bpRequest))
        server.shutdown()
        seen += reasonOf(source.startAcquisition(bpRequest))

        assertTrue("a Wi-Fi path produced $seen", RejectReason.BLUETOOTH_UNAVAILABLE !in seen)
        assertTrue(RejectReason.UNREACHABLE in seen)
    }

    // --- R3: a body that is not JSON, or stops part way, is MALFORMED and never UNREACHABLE ------

    @Test
    fun `a 200 that is not JSON is MALFORMED on the start call and on the measurement call`() = runTest {
        server.enqueue(ok("this is not json"))
        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))

        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok("<html>502 Bad Gateway</html>"))
        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))
    }

    @Test
    fun `a 200 whose JSON stops part way is MALFORMED on the start call and on the measurement call`() = runTest {
        server.enqueue(ok("""{"hub_id":"kernelhub1","status":"STAR"""))
        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))

        server.enqueue(ok(startBody("session-A")))
        server.enqueue(ok(bpMeasurementBody("session-A").dropLast(40)))
        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))
    }

    @Test
    fun `a 200 with a syntax error inside the object is MALFORMED`() = runTest {
        // A missing comma: Gson's reader raises MalformedJsonException, which is an IOException.
        server.enqueue(ok("""{"hub_id":"kernelhub1" "status":"STARTED"}"""))

        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))
    }

    @Test
    fun `a 200 of the wrong JSON shape is MALFORMED`() = runTest {
        server.enqueue(ok("[1,2,3]"))

        assertEquals(RejectReason.MALFORMED, reasonOf(source.startAcquisition(bpRequest)))
    }

    // --- G-B18, the half that exists without BLE: the session id is compared as an exact string ---

    @Test
    fun `an uppercase or undashed echo of the session id fails RC-1, the exact string passes`() = runTest {
        val id = "3f2b8c1e-5a7d-4e9f-8a1b-0c2d3e4f5a6b"
        for ((echo, expected) in listOf(
            id.uppercase() to RejectReason.SESSION_ID_MISMATCH,
            id.replace("-", "") to RejectReason.SESSION_ID_MISMATCH,
            " $id" to RejectReason.SESSION_ID_MISMATCH,
        )) {
            server.enqueue(ok(startBody(id)))
            server.enqueue(ok(bpMeasurementBody(echo)))

            assertEquals("echo '$echo'", expected, reasonOf(source.startAcquisition(bpRequest)))
        }

        server.enqueue(ok(startBody(id)))
        server.enqueue(ok(bpMeasurementBody(id)))
        assertTrue(source.startAcquisition(bpRequest) is AcquisitionResult.Accepted)
    }

    @Test
    fun `equality is by string, not by UUID version, so the fixture's version 6 nonce passes`() = runTest {
        val nonce = "00112233-4455-6677-8899-aabbccddeeff"
        server.enqueue(ok(startBody(nonce)))
        server.enqueue(ok(bpMeasurementBody(nonce)))

        assertTrue(source.startAcquisition(bpRequest) is AcquisitionResult.Accepted)
    }

    private companion object {
        const val HUB = "kernelhub1"
    }
}
