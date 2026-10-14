package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.ble.GoldenFixture.hex
import com.example.samdapp.data.vitalssource.expect
import com.example.samdapp.data.vitalssource.only
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The protocol of addendum 5.2 over a scripted [FakeHubLink]: G-B2, G-B3, G-B4 (second half), G-B5,
 *  G-B9, G-B13 (order over two connections), G-B14 and G-B23, plus the 5.3 and 5.4 mappings, and the
 *  STOP 2 rulings F1 (the cap closes only), F2 (teardown cannot change the result), F3 (decode by the
 *  envelope's instrument) and F5 (Stop during connect), and the STOP 4 ruling B1 (only the caller's own
 *  cancellation may replace a decided result). */
class BleAcquisitionTest {

    private val nonce = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    private val received = Instant.parse("2026-10-10T08:00:00Z")
    private val bp = AcquisitionRequest(Instrument.BP, Scenario.NORMAL)

    private suspend fun TestScope.acquire(
        link: FakeHubLink,
        request: AcquisitionRequest = bp,
        hubId: String = "kernelhub2",
    ): BleAcquisition.Outcome = BleAcquisition.run(link, request, hubId, nonce) { received }

    private fun BleAcquisition.Outcome.reason(): RejectReason? = (result as? AcquisitionResult.Rejected)?.reason

    private fun HubLinkException.Failure.ex() = HubLinkException(this)

    // ---- the happy path ----------------------------------------------------------------------

    @Test
    fun `a BP reading is accepted, then STOP, disconnect and close, in that order`() = runTest {
        val link = FakeHubLink()

        val accepted = acquire(link).result.expect<AcquisitionResult.Accepted>()

        assertEquals(120, accepted.reading.bpSystolic)
        assertEquals(80, accepted.reading.bpDiastolic)
        assertEquals(72, accepted.reading.pulseBpm)
        assertEquals(nonce.toString(), accepted.sessionId)
        assertEquals("BLOOD_PRESSURE", accepted.deviceType)
        assertEquals("2026-10-07T10:30:15Z", accepted.measuredAt)
        assertEquals(true, accepted.synthetic)
        assertEquals("bba2c6b", accepted.emulatorBuild)
        assertEquals(
            listOf(
                "connect", "mtu(517)", "enable(CONTROL_POINT)", "enable(ENVELOPE)",
                "read(hub-serial)", "read(hub-software)", "write(START)", "write(STOP)", "disconnect", "close",
            ),
            link.log,
        )
        assertArrayEquals(ControlPoint.encodeStart(Instrument.BP, Scenario.NORMAL, ControlPoint.nonceBytes(nonce)), link.startWrites.only("START write"))
        assertArrayEquals(ControlPoint.encodeStop(ControlPoint.nonceBytes(nonce)), link.stopWrites.only("STOP write"))
    }

    @Test
    fun `SPO2 and THERMOMETER readings decode as their own characteristics`() = runTest {
        val spo2Link = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(instrument = 2, nonce = n, sig = hex("0062004800"))) }
        }
        val spo2 = acquire(spo2Link, AcquisitionRequest(Instrument.SPO2)).result.expect<AcquisitionResult.Accepted>()
        assertEquals(98, spo2.reading.spo2Percent)
        assertEquals(72, spo2.reading.pulseBpm)
        assertEquals("PULSE_OXIMETER", spo2.deviceType)

        val htsLink = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(instrument = 3, nonce = n, sig = hex("046f0100ff02"))) }
        }
        val hts = acquire(htsLink, AcquisitionRequest(Instrument.THERMOMETER)).result.expect<AcquisitionResult.Accepted>()
        assertEquals(36.7, hts.reading.temperatureCelsius!!, 0.0)
        assertEquals("THERMOMETER", hts.deviceType)
    }

    // ---- G-B2 --------------------------------------------------------------------------------

    @Test
    fun `an envelope echoing a different nonce is SESSION_ID_MISMATCH`() = runTest {
        val other = ControlPoint.nonceBytes(UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val link = FakeHubLink().apply { onStart = { answer(0x01); envelope(envelopeBytes(nonce = other)) } }

        assertEquals(RejectReason.SESSION_ID_MISMATCH, acquire(link).reason())
    }

    // ---- G-B3 and step 7 ---------------------------------------------------------------------

    @Test
    fun `an envelope before the START answer is discarded, then TIMEOUT`() = runTest {
        val link = FakeHubLink().apply { onStart = { n -> envelope(envelopeBytes(nonce = n)); answer(0x01) } }

        assertEquals(RejectReason.TIMEOUT, acquire(link).reason())
        assertEquals("the envelope wait, not the cap", BleAcquisition.ENVELOPE_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `a Control Point answer queued before START is drained, not taken as the answer`() = runTest {
        val link = FakeHubLink().apply {
            answer(0x01) // stale, from no session of ours
            onStart = { n -> answer(0x05); envelope(envelopeBytes(nonce = n)) }
        }

        assertEquals(RejectReason.UNREACHABLE, acquire(link).reason())
    }

    // ---- G-B4, second half: step 13 ------------------------------------------------------------

    @Test
    fun `quality OK with a special value in any present field is QUALITY_STATUS_NOT_OK`() = runTest {
        val pulseNaN = hex("0478005000a5f3ff07")
        val link = FakeHubLink().apply { onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, sig = pulseNaN)) } }
        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, acquire(link).reason())

        val allNaN = hex(GoldenFixture.json.getAsJsonObject("bp")["fault_ts"].asJsonObject["hex"].asString)
        val faultLink = FakeHubLink().apply { onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, sig = allNaN)) } }
        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, acquire(faultLink).reason())
    }

    @Test
    fun `a quality other than OK is QUALITY_STATUS_NOT_OK`() = runTest {
        val link = FakeHubLink().apply { onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, quality = 2)) } }

        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, acquire(link).reason())
    }

    // ---- G-B5 --------------------------------------------------------------------------------

    @Test
    fun `a DIS serial naming another hub is HUB_MISMATCH and drops the association`() = runTest {
        val link = FakeHubLink(hubId = "kernelhub9").apply {
            // The envelope still names the assigned hub, so only the DIS comparison can refuse it.
            onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n)) }
        }

        val outcome = acquire(link)

        assertEquals(RejectReason.HUB_MISMATCH, outcome.reason())
        assertTrue(outcome.disSerialMismatch)
        assertTrue("nothing is started on the wrong hub", link.startWrites.isEmpty())
    }

    @Test
    fun `an envelope naming another hub is HUB_MISMATCH and keeps the association`() = runTest {
        val link = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, hubIdBytes = "kernelhub9".toByteArray())) }
        }

        val outcome = acquire(link)

        assertEquals(RejectReason.HUB_MISMATCH, outcome.reason())
        assertFalse(outcome.disSerialMismatch)
    }

    @Test
    fun `no serial, or two, is HUB_MISMATCH and keeps the association`() = runTest {
        val none = FakeHubLink().apply { services = hubServices().take(1) }
        val noneOutcome = acquire(none)
        assertEquals(RejectReason.HUB_MISMATCH, noneOutcome.reason())
        assertFalse(noneOutcome.disSerialMismatch)

        val two = FakeHubLink().apply { services = hubServices() + hubServices()[1] }
        val twoOutcome = acquire(two)
        assertEquals(RejectReason.HUB_MISMATCH, twoOutcome.reason())
        assertFalse(twoOutcome.disSerialMismatch)
    }

    // ---- G-B9 --------------------------------------------------------------------------------

    @Test
    fun `a link that drops while the envelope is awaited is UNREACHABLE within the cap`() = runTest {
        val link = FakeHubLink().apply { onStart = { answer(0x01); dropLink() } }

        assertEquals(RejectReason.UNREACHABLE, acquire(link).reason())
        assertTrue(testScheduler.currentTime < BleAcquisition.CAP_MS)
    }

    @Test
    fun `a link that drops during a read is UNREACHABLE`() = runTest {
        val link = FakeHubLink().apply { readFailure = HubLinkException.Failure.DISCONNECTED.ex() }

        assertEquals(RejectReason.UNREACHABLE, acquire(link).reason())
    }

    // ---- G-B13 and G-B14 ---------------------------------------------------------------------

    @Test
    fun `on every connection the CCCDs and MTU come before the first Control Point write, and SIG is never enabled`() = runTest {
        val first = FakeHubLink()
        val second = FakeHubLink()

        acquire(first)
        acquire(second)

        for ((i, link) in listOf(first, second).withIndex()) {
            val write = link.log.indexOf("write(START)")
            assertTrue("connection $i wrote START", write >= 0)
            assertTrue("connection $i: MTU before START, ${link.log}", link.log.indexOf("mtu(517)") in 0 until write)
            assertTrue("connection $i: CP CCCD before START, ${link.log}", link.log.indexOf("enable(CONTROL_POINT)") in 0 until write)
            assertTrue("connection $i: ENV CCCD before START, ${link.log}", link.log.indexOf("enable(ENVELOPE)") in 0 until write)
            assertTrue("connection $i enabled something other than CP and ENV: ${link.log}", link.log.none { it.startsWith("enable(") && it !in setOf("enable(CONTROL_POINT)", "enable(ENVELOPE)") })
        }
    }

    @Test
    fun `a recorded init failure is MALFORMED and no START is written`() = runTest {
        for (failed in HubInitPlan.steps()) {
            val link = FakeHubLink(initFailures = listOf(failed))
            assertEquals("$failed", RejectReason.MALFORMED, acquire(link).reason())
            assertTrue("$failed", link.startWrites.isEmpty())
        }
    }

    @Test
    fun `a negotiated MTU of 88 is MALFORMED and no START is written`() = runTest {
        val link = FakeHubLink(mtu = 88)

        assertEquals(RejectReason.MALFORMED, acquire(link).reason())
        assertTrue(link.startWrites.isEmpty())
        assertTrue(link.log.contains("mtu(517)"))
    }

    @Test
    fun `an MTU of exactly 89 is enough`() = runTest {
        assertTrue(acquire(FakeHubLink(mtu = 89)).result is AcquisitionResult.Accepted)
    }

    // ---- G-B23: the cap and the teardown -------------------------------------------------------

    @Test
    fun `a hung connect ends in TIMEOUT at the 25 s cap, aborts and closes, and writes no STOP`() = runTest {
        val link = FakeHubLink().apply { hang = "connect" }

        assertEquals(RejectReason.TIMEOUT, acquire(link).reason())
        assertEquals("the 25 s cap", 25_000L, testScheduler.currentTime)
        assertEquals(1, link.aborts)
        assertEquals(1, link.closes)
        assertTrue(link.stopWrites.isEmpty())
        assertEquals(listOf("connect", "abort", "close"), link.log)
    }

    @Test
    fun `the cap after a START SUCCESS calls abort then close, and writes nothing after abort`() = runTest {
        // 16 s to connect leaves 9 s of the cap for the 10 s envelope wait: the cap fires first.
        val link = FakeHubLink().apply {
            connectDelayMs = 16_000
            onStart = { answer(0x01) }
        }

        assertEquals(RejectReason.TIMEOUT, acquire(link).reason())
        assertEquals("the 25 s cap", 25_000L, testScheduler.currentTime)
        assertEquals(1, link.aborts)
        assertEquals(1, link.closes)
        assertTrue("an aborted link cannot carry a STOP", link.stopWrites.isEmpty())
        assertEquals(listOf("abort", "close"), link.log.dropWhile { it != "abort" })
    }

    @Test
    fun `the closed-link fake refuses every operation after abort and after close`() = runTest {
        for (kill in listOf<(FakeHubLink) -> Unit>({ it.abort() }, { it.close() })) {
            val link = FakeHubLink()
            kill(link)

            val refused = listOf<suspend () -> Unit>(
                { link.connect(1, emptyList()) }, { link.mtu() }, { link.disServices() }, { link.read("hub-serial", 1) },
                { link.writeControlPoint(byteArrayOf(0x02), 1) }, { link.disconnect(1) }, { link.abort() },
            ).count { op -> try { op(); false } catch (e: HubLinkException) { e.failure == HubLinkException.Failure.DISCONNECTED } }
            assertEquals(7, refused)
            link.close()
            link.close()
            assertTrue("close may always be repeated", link.closes >= 2)
        }
    }

    @Test
    fun `an abort that throws on the cap path keeps TIMEOUT and still closes`() = runTest {
        val link = FakeHubLink().apply {
            hang = "connect"
            teardownFailures["abort"] = IllegalStateException("library fault")
        }

        val outcome = runCatching { acquire(link) }
        assertTrue("the abort failure escaped: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(RejectReason.TIMEOUT, outcome.getOrThrow().reason())
        assertEquals(listOf("connect", "abort", "close"), link.log)
        assertEquals(1, link.closes)
    }

    @Test
    fun `a refused START writes no STOP, and still closes`() = runTest {
        val link = FakeHubLink().apply { onStart = { answer(0x05) } }

        assertEquals(RejectReason.UNREACHABLE, acquire(link).reason())
        assertTrue(link.stopWrites.isEmpty())
        assertEquals(listOf("disconnect", "close"), link.log.takeLast(2))
    }

    @Test
    fun `Stop during the envelope wait cancels, and the teardown still writes STOP and closes`() = runTest {
        val link = FakeHubLink().apply { onStart = { answer(0x01) } }

        val job = async { acquire(link) }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancelAndJoin()

        assertArrayEquals(ControlPoint.encodeStop(ControlPoint.nonceBytes(nonce)), link.stopWrites.only("STOP write"))
        assertEquals(listOf("write(STOP)", "disconnect", "close"), link.log.takeLast(3))
    }

    @Test
    fun `Stop during connect cancels, and the teardown writes no STOP, disconnects and closes`() = runTest {
        val link = FakeHubLink().apply { connectDelayMs = 5_000 }

        val job = async { acquire(link) }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancelAndJoin()

        assertTrue("Stop cancels the acquisition, so it returns no result at all", job.isCancelled)
        assertTrue(job.getCompletionExceptionOrNull() is CancellationException)
        assertTrue("START never succeeded", link.startWrites.isEmpty() && link.stopWrites.isEmpty())
        assertEquals("Stop is not the cap", 0, link.aborts)
        assertEquals(listOf("connect", "disconnect", "close"), link.log)
    }

    @Test
    fun `F6a a caller cancelled mid-measurement still STOPs, disconnects and closes once, then propagates`() = runTest {
        val link = FakeHubLink().apply { onStart = { answer(0x01) } }
        // Not Stop and not the cap: the job the caller runs in is cancelled from above.
        val caller = Job()
        val outside = CoroutineScope(StandardTestDispatcher(testScheduler) + caller)
        val outcome = outside.async { acquire(link) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("START is answered and the envelope is awaited", link.startWrites.size == 1 && !outcome.isCompleted)

        caller.cancel()
        val ended = withTimeoutOrNull(BleAcquisition.TEARDOWN_TIMEOUT_MS * 3) { outcome.join(); true }

        assertEquals("the teardown did not end", true, ended)
        assertEquals(listOf("write(START)", "write(STOP)", "disconnect", "close"), link.log.dropWhile { it != "write(START)" })
        assertArrayEquals(ControlPoint.encodeStop(ControlPoint.nonceBytes(nonce)), link.stopWrites.only("STOP write"))
        assertEquals(1, link.closes)
        assertTrue("the cancellation propagates", outcome.isCancelled)
        assertTrue("no result at all, so no Accepted", outcome.getCompletionExceptionOrNull() is CancellationException)
    }

    @Test
    fun `F6b a STOP write that never returns is bounded, and disconnect and close still run`() = runTest {
        val link = FakeHubLink().apply { hang = "write(STOP)" }
        // Outside runTest's own scope, as in the hung disconnect case: an unbounded NonCancellable
        // await could be neither timed out nor cancelled.
        val outside = CoroutineScope(StandardTestDispatcher(testScheduler))
        val outcome = outside.async { acquire(link) }

        val ended = withTimeoutOrNull(BleAcquisition.TEARDOWN_TIMEOUT_MS * 3) { outcome.join(); true }

        assertEquals("the STOP write was not bounded", true, ended)
        assertTrue(outcome.await().result is AcquisitionResult.Accepted)
        assertEquals(listOf("write(STOP)", "disconnect", "close"), link.log.takeLast(3))
        assertEquals(1, link.closes)
        outside.cancel()
    }

    @Test
    fun `B1 a STOP write that throws CancellationException leaves Accepted unchanged, and disconnect and close run once`() = runTest {
        val link = FakeHubLink().apply { teardownFailures["write(STOP)"] = CancellationException("the library cancelled it") }

        val outcome = runCatching { acquire(link) }

        assertTrue("the teardown cancellation escaped: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        outcome.getOrThrow().result.expect<AcquisitionResult.Accepted>()
        assertEquals(listOf("write(STOP)", "disconnect", "close"), link.log.takeLast(3))
        assertEquals(1, link.log.count { it == "disconnect" })
        assertEquals(1, link.closes)
    }

    @Test
    fun `B1 a STOP write that throws CancellationException leaves Rejected unchanged, and disconnect and close run once`() = runTest {
        val link = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, quality = 2)) }
            teardownFailures["write(STOP)"] = CancellationException("the library cancelled it")
        }

        val outcome = runCatching { acquire(link) }

        assertTrue("the teardown cancellation escaped: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, outcome.getOrThrow().reason())
        assertEquals(listOf("write(STOP)", "disconnect", "close"), link.log.takeLast(3))
        assertEquals(1, link.log.count { it == "disconnect" })
        assertEquals(1, link.closes)
    }

    @Test
    fun `B1 a caller cancelled during the teardown of a decided result gets its cancellation, not the result`() = runTest {
        val link = FakeHubLink().apply { hang = "write(STOP)" }
        val caller = Job()
        val outside = CoroutineScope(StandardTestDispatcher(testScheduler) + caller)
        var seen: BleAcquisition.Outcome? = null
        val outcome = outside.async { acquire(link).also { seen = it } }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Accepted is decided and the STOP write is in flight", "write(STOP)", link.log.last())

        caller.cancel()
        val ended = withTimeoutOrNull(BleAcquisition.TEARDOWN_TIMEOUT_MS * 3) { outcome.join(); true }

        assertEquals("the teardown did not end", true, ended)
        assertEquals(listOf("write(STOP)", "disconnect", "close"), link.log.takeLast(3))
        assertEquals(1, link.closes)
        assertNull("the caller went on with a result after its own cancellation", seen)
        assertTrue(outcome.isCancelled)
    }

    @Test
    fun `a teardown step that throws cannot change the result`() = runTest {
        for (step in listOf("write(STOP)", "disconnect", "close")) {
            val accepted = FakeHubLink().apply { teardownFailures[step] = IllegalStateException("library fault") }
            val acceptedOutcome = runCatching { acquire(accepted) }
            assertTrue("$step: the teardown failure escaped: ${acceptedOutcome.exceptionOrNull()}", acceptedOutcome.isSuccess)
            acceptedOutcome.getOrThrow().result.expect<AcquisitionResult.Accepted>()
            assertEquals("$step: close still runs", "close", accepted.log.last())

            val refused = FakeHubLink().apply {
                onStart = { answer(0x05) }
                teardownFailures[step] = IllegalStateException("library fault")
            }
            val refusedOutcome = runCatching { acquire(refused) }
            assertTrue("$step: the teardown failure escaped: ${refusedOutcome.exceptionOrNull()}", refusedOutcome.isSuccess)
            assertEquals(step, RejectReason.UNREACHABLE, refusedOutcome.getOrThrow().reason())
        }
    }

    @Test
    fun `a hung disconnect is bounded and close still runs`() = runTest {
        val link = FakeHubLink().apply { hang = "disconnect" }
        // Outside runTest's own scope, so an unbounded teardown fails the assertion below instead of
        // hanging the test: a NonCancellable await can be neither timed out nor cancelled.
        val outside = CoroutineScope(StandardTestDispatcher(testScheduler))
        val outcome = outside.async { acquire(link) }

        advanceTimeBy(BleAcquisition.TEARDOWN_TIMEOUT_MS + 1)
        runCurrent()

        assertTrue("the teardown did not end within its 2 s bound", outcome.isCompleted)
        assertTrue(outcome.await().result is AcquisitionResult.Accepted)
        assertEquals(1, link.closes)
        assertEquals(2_000L, BleAcquisition.TEARDOWN_TIMEOUT_MS)
        outside.cancel()
    }

    // ---- 5.3 and 5.4 -------------------------------------------------------------------------

    @Test
    fun `START answers map by the result table, and only SUCCESS opens a session to STOP`() = runTest {
        val cases = mapOf(0x02 to RejectReason.MALFORMED, 0x03 to RejectReason.MALFORMED, 0x04 to RejectReason.HUB_MISMATCH,
            0x05 to RejectReason.UNREACHABLE, 0x07 to RejectReason.MALFORMED, 0x08 to RejectReason.UNREACHABLE)
        for ((code, reason) in cases) {
            val link = FakeHubLink().apply { onStart = { answer(code) } }
            assertEquals("0x%02x".format(code), reason, acquire(link).reason())
            assertTrue(link.stopWrites.isEmpty())
        }
    }

    @Test
    fun `link failures during connect map by addendum 5_4`() = runTest {
        val cases = mapOf(
            HubLinkException.Failure.BLUETOOTH_DISABLED to RejectReason.BLUETOOTH_UNAVAILABLE,
            HubLinkException.Failure.DISCONNECTED to RejectReason.UNREACHABLE,
            HubLinkException.Failure.NOT_SUPPORTED to RejectReason.HUB_MISMATCH,
            HubLinkException.Failure.TIMED_OUT to RejectReason.UNREACHABLE,
            HubLinkException.Failure.FAILED to RejectReason.UNREACHABLE,
            HubLinkException.Failure.INVALID_REQUEST to RejectReason.MALFORMED,
            HubLinkException.Failure.PERMISSION_REVOKED to RejectReason.BLUETOOTH_UNAVAILABLE,
        )
        assertEquals(HubLinkException.Failure.entries.toSet(), cases.keys)
        for ((failure, reason) in cases) {
            val link = FakeHubLink().apply { connectFailure = failure.ex() }
            assertEquals("$failure", reason, acquire(link).reason())
            assertEquals(1, link.closes)
        }
    }

    @Test
    fun `a request timeout after connect is TIMEOUT, not UNREACHABLE`() = runTest {
        val link = FakeHubLink().apply { readFailure = HubLinkException.Failure.TIMED_OUT.ex() }

        assertEquals(RejectReason.TIMEOUT, acquire(link).reason())
    }

    @Test
    fun `a START write the link drops is UNREACHABLE`() = runTest {
        val link = FakeHubLink().apply { startWriteFailure = HubLinkException.Failure.DISCONNECTED.ex() }

        assertEquals(RejectReason.UNREACHABLE, acquire(link).reason())
    }

    // ---- steps 11 and 13: refusals reach a reason through the parser and decoders ----------------

    @Test
    fun `an envelope the parser rejects is MALFORMED`() = runTest {
        val link = FakeHubLink().apply { onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, flags = 0x00)) } }

        assertEquals(RejectReason.MALFORMED, acquire(link).reason())
    }

    @Test
    fun `SIG bytes that are not the envelope's own characteristic are MALFORMED`() = runTest {
        val link = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, instrument = 1, sig = hex("0062004800"))) }
        }

        assertEquals(RejectReason.MALFORMED, acquire(link).reason())
    }

    @Test
    fun `an envelope instrument with no SIG decoder is MALFORMED`() = runTest {
        val link = FakeHubLink().apply {
            onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, instrument = 4, sig = hex("0062004800"))) }
        }

        assertEquals(RejectReason.MALFORMED, acquire(link).reason())
    }

    @Test
    fun `a BP request answered by a well-formed SPO2 envelope is the mapper's DEVICE_TYPE_MISMATCH`() = runTest {
        val spo2 = hex("0062004800")
        val link = FakeHubLink().apply { onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, instrument = 2, sig = spo2)) } }
        assertEquals(RejectReason.DEVICE_TYPE_MISMATCH, acquire(link).reason())

        // The mapper's own gate order, session before device type: the same envelope with a foreign
        // nonce is SESSION_ID_MISMATCH, so the device type is judged by PiMeasurementMapper, after RC-1's
        // session gate, and not by any check of BleAcquisition's own before it.
        val other = ControlPoint.nonceBytes(UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val foreign = FakeHubLink().apply { onStart = { answer(0x01); envelope(envelopeBytes(nonce = other, instrument = 2, sig = spo2)) } }
        assertEquals(RejectReason.SESSION_ID_MISMATCH, acquire(foreign).reason())

        // And PiMeasurementMapper is the only producer of the reason.
        val producers = File(listOf(File("src/dev"), File("app/src/dev")).first { it.isDirectory }, "java")
            .walkTopDown().filter { it.isFile && it.extension == "kt" && "RejectReason.DEVICE_TYPE_MISMATCH)" in it.readText() }
            .map { it.name }.toList()
        assertEquals(listOf("PiMeasurementMapper.kt"), producers)
    }
}
