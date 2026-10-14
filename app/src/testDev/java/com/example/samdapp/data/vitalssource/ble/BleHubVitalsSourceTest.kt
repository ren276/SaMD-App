package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.expect
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts every platform call, so a test can assert what was NOT touched. */
private class FakePlatform(
    var enabled: Boolean = true,
    var permitted: Boolean = true,
    var address: String? = "D8:3A:DD:9F:DC:16",
    var companionDevices: Boolean = true,
) : BleHubPlatform {
    val calls = mutableListOf<String>()
    val links = mutableListOf<FakeHubLink>()
    var nextLink: () -> FakeHubLink = { FakeHubLink() }

    override fun isBluetoothEnabled(): Boolean = enabled.also { calls += "enabled" }
    override fun hasConnectPermission(): Boolean = permitted.also { calls += "permission" }
    override fun canAssociate(): Boolean = companionDevices.also { calls += "canAssociate" }
    override fun associatedAddress(hubId: String): String? = address.also { calls += "associated($hubId)" }
    override fun forgetAssociation(hubId: String) {
        calls += "forget($hubId)"
        address = null
    }
    override fun openLink(address: String, instrument: Instrument): HubLink<*> {
        calls += "open($address,$instrument)"
        return nextLink().also { links += it }
    }
}

/** Preparation order (addendum 4.2), Q1 (G-B29), the Q2 ceiling mapping (G-B28) and Q10 at the
 *  source level (G-B5). */
class BleHubVitalsSourceTest {

    private val platform = FakePlatform()
    private val prepared = mutableListOf<BlePrepBridge.Request>()
    private var answer: BlePrepBridge.Answer? = BlePrepBridge.Answer.GRANTED
    private lateinit var bridge: BlePrepBridge
    private fun source(sdkInt: Int = 36): BleHubVitalsSource {
        bridge = BlePrepBridge(launch = { r ->
            prepared += r
            answer?.let { a ->
                if (r.kind == BlePrepBridge.Kind.ASSOCIATION && a == BlePrepBridge.Answer.GRANTED) platform.address = "D8:3A:DD:9F:DC:16"
                if (r.kind == BlePrepBridge.Kind.CONNECT_PERMISSION && a == BlePrepBridge.Answer.GRANTED) platform.permitted = true
                bridge.complete(r.id, a)
            }
        })
        return BleHubVitalsSource(platform, bridge, sdkInt)
    }

    private val bp = AcquisitionRequest(Instrument.BP)
    private var started = 0

    private suspend fun BleHubVitalsSource.startBp(): AcquisitionResult = startAcquisition(bp, "kernelhub2") { started++ }

    private fun AcquisitionResult.reason() = expect<AcquisitionResult.Rejected>().reason

    @Test
    fun `an associated, permitted hub is acquired from, and onStarted runs once`() = runTest {
        val result = source().startBp()

        assertTrue(result is AcquisitionResult.Accepted)
        assertEquals(1, started)
        assertTrue(prepared.isEmpty())
        assertEquals("open(D8:3A:DD:9F:DC:16,BP)", platform.calls.last())
    }

    // ---- G-B29 (Q1) --------------------------------------------------------------------------

    @Test
    fun `below API 33 the source refuses BLUETOOTH_UNAVAILABLE without touching Bluetooth`() = runTest {
        val result = source(sdkInt = 32).startBp()

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, result.reason())
        assertTrue("no platform call at all: ${platform.calls}", platform.calls.isEmpty())
        assertTrue(platform.links.isEmpty())
        assertEquals(0, started)
    }

    @Test
    fun `API 33 itself is allowed`() = runTest {
        assertTrue(source(sdkInt = 33).startBp() is AcquisitionResult.Accepted)
    }

    // ---- preparation, addendum 4.2 -------------------------------------------------------------

    @Test
    fun `Bluetooth off is BLUETOOTH_UNAVAILABLE before anything else is asked`() = runTest {
        platform.enabled = false

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, source().startBp().reason())
        assertEquals(listOf("enabled"), platform.calls)
        assertEquals(0, started)
    }

    @Test
    fun `no association runs the companion-device flow first, then the permission, then the acquisition`() = runTest {
        platform.address = null
        platform.permitted = false

        assertTrue(source().startBp() is AcquisitionResult.Accepted)
        assertEquals(listOf(BlePrepBridge.Kind.ASSOCIATION, BlePrepBridge.Kind.CONNECT_PERMISSION), prepared.map { it.kind })
        assertEquals(1, started)
    }

    @Test
    fun `a dismissed association is NOT_SUPPORTED and nothing is started`() = runTest {
        platform.address = null
        answer = BlePrepBridge.Answer.REFUSED

        assertEquals(RejectReason.NOT_SUPPORTED, source().startBp().reason())
        assertEquals(0, started)
        assertTrue(platform.links.isEmpty())
    }

    @Test
    fun `an association the system cannot run is BLUETOOTH_UNAVAILABLE`() = runTest {
        platform.address = null
        answer = BlePrepBridge.Answer.UNAVAILABLE

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, source().startBp().reason())
        assertEquals(0, started)
    }

    @Test
    fun `a device without companion-device support is BLUETOOTH_UNAVAILABLE and no dialog is launched`() = runTest {
        platform.address = null
        platform.companionDevices = false

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, source().startBp().reason())
        assertTrue("the association dialog was launched", prepared.isEmpty())
        assertEquals(0, started)
    }

    @Test
    fun `a denied permission is BLUETOOTH_UNAVAILABLE and nothing is started`() = runTest {
        platform.permitted = false
        answer = BlePrepBridge.Answer.REFUSED

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, source().startBp().reason())
        assertEquals(0, started)
        assertTrue(platform.links.isEmpty())
    }

    // ---- G-B28: the 120 s ceiling, outside the 25 s cap -----------------------------------------

    @Test
    fun `an association nobody answers in 120 s is NOT_SUPPORTED`() = runTest {
        platform.address = null
        answer = null
        val src = source()

        val result = async { src.startBp() }
        advanceTimeBy(BleAcquisition.CAP_MS + 1)
        runCurrent()
        assertFalse("preparation is not under the 25 s cap", result.isCompleted)
        advanceTimeBy(BlePrepBridge.CEILING_MS)
        runCurrent()

        assertEquals(RejectReason.NOT_SUPPORTED, result.await().reason())
        assertEquals(0, started)
    }

    @Test
    fun `a permission nobody answers in 120 s is BLUETOOTH_UNAVAILABLE`() = runTest {
        platform.permitted = false
        answer = null
        val src = source()

        val result = async { src.startBp() }
        advanceTimeBy(BlePrepBridge.CEILING_MS + 1)
        runCurrent()

        assertEquals(RejectReason.BLUETOOTH_UNAVAILABLE, result.await().reason())
        assertEquals(0, started)
    }

    // ---- G-B5 at the source: only a DIS serial mismatch drops the association (Q10) -------------

    @Test
    fun `a DIS serial mismatch drops the association`() = runTest {
        platform.nextLink = { FakeHubLink(hubId = "kernelhub9") }

        assertEquals(RejectReason.HUB_MISMATCH, source().startBp().reason())
        assertTrue(platform.calls.contains("forget(kernelhub2)"))
    }

    @Test
    fun `an envelope hub_id mismatch keeps the association`() = runTest {
        platform.nextLink = {
            FakeHubLink().apply {
                onStart = { n -> answer(0x01); envelope(envelopeBytes(nonce = n, hubIdBytes = "kernelhub9".toByteArray())) }
            }
        }

        assertEquals(RejectReason.HUB_MISMATCH, source().startBp().reason())
        assertFalse(platform.calls.any { it.startsWith("forget(") })
    }

    @Test
    fun `an instrument with no SIG decoder is refused before Bluetooth is touched`() = runTest {
        val result = source().startAcquisition(AcquisitionRequest(Instrument.GLUCOMETER), "kernelhub2") { started++ }

        assertEquals(RejectReason.NOT_SUPPORTED, result.reason())
        assertTrue(platform.calls.isEmpty())
    }
}
