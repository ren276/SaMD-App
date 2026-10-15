package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.only
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-B28: abandonment and the 120 s preparation ceiling. */
class BlePrepBridgeTest {

    private val launched = mutableListOf<BlePrepBridge.Request>()
    private val bridge = BlePrepBridge(launch = { launched += it })

    @Test
    fun `the activity is launched with the request, and its answer resumes the source`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")
        assertEquals(BlePrepBridge.Kind.ASSOCIATION, request.kind)
        assertEquals("kernelhub2", request.hubId)
        assertEquals(request, bridge.pending(request.id))

        assertTrue(bridge.complete(request.id, BlePrepBridge.Answer.GRANTED))
        assertEquals(BlePrepBridge.Answer.GRANTED, waiting.await())
        assertNull("answered requests leave the table", bridge.pending(request.id))
    }

    @Test
    fun `cancellation abandons the request, and a later answer resumes nothing`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.CONNECT_PERMISSION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")

        waiting.cancelAndJoin()

        assertNull("the activity must see the request as abandoned", bridge.pending(request.id))
        assertFalse("an answer after abandonment resumes nothing", bridge.complete(request.id, BlePrepBridge.Answer.GRANTED))
    }

    @Test
    fun `nobody answering within 120 s is TIMED_OUT, and the request is abandoned`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")

        advanceTimeBy(BlePrepBridge.CEILING_MS - 1)
        runCurrent()
        assertFalse("still waiting just before the ceiling", waiting.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(BlePrepBridge.Answer.TIMED_OUT, waiting.await())
        assertEquals(120_000L, BlePrepBridge.CEILING_MS)
        assertNull(bridge.pending(request.id))
        assertFalse(bridge.complete(request.id, BlePrepBridge.Answer.GRANTED))
    }

    @Test
    fun `an unknown id, as after process death, is not pending and resumes nothing`() {
        assertNull(bridge.pending(42))
        assertFalse(bridge.complete(42, BlePrepBridge.Answer.GRANTED))
    }

    // ---- review fix G7: the companion-device callbacks reach the live instance through here --------

    private class RecordingHost : BlePrepBridge.Host {
        val shown = mutableListOf<Any>()
        var finishes = 0
        override fun showDialog(dialog: Any) { shown += dialog }
        override fun finish() { finishes++ }
    }

    @Test
    fun `G7 both signals of a created association store one ID, answer GRANTED once, and the second is a no-op`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")
        val host = RecordingHost().also { bridge.attach(request.id, it) }
        var stores = 0

        assertTrue("the first signal wins", bridge.associationCreated(request.id) { stores++ })
        assertFalse("the second signal is a no-op", bridge.associationCreated(request.id) { stores++ })

        assertEquals("one ID stored", 1, stores)
        assertEquals(BlePrepBridge.Answer.GRANTED, waiting.await())
        assertEquals("the attached instance is finished once", 1, host.finishes)
    }

    @Test
    fun `G7 an association created after the request was abandoned still stores its ID once and resumes nothing`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")
        waiting.cancelAndJoin()
        var stores = 0

        assertTrue(bridge.associationCreated(request.id) { stores++ })
        assertFalse(bridge.associationCreated(request.id) { stores++ })

        assertEquals(1, stores)
        assertNull(bridge.pending(request.id))
    }

    @Test
    fun `G7 a dialog ready while an instance is attached is shown by it`() {
        val host = RecordingHost().also { bridge.attach(7, it) }

        bridge.dialogReady(7, "sender")

        assertEquals(listOf<Any>("sender"), host.shown)
    }

    @Test
    fun `G7 a dialog ready between a destroyed instance and its recreation is shown once, by the recreated one`() {
        val destroyed = RecordingHost()
        bridge.attach(7, destroyed)
        bridge.detach(7, destroyed)

        bridge.dialogReady(7, "sender")
        val recreated = RecordingHost().also { bridge.attach(7, it) }
        val later = RecordingHost().also { bridge.attach(7, it) }

        assertEquals(emptyList<Any>(), destroyed.shown)
        assertEquals(listOf<Any>("sender"), recreated.shown)
        assertEquals("a dialog is shown once", emptyList<Any>(), later.shown)
    }

    @Test
    fun `G7 a dialog left waiting by an abandoned request is not shown to a later instance`() = runTest {
        val waiting = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val request = launched.only("launched request")
        bridge.dialogReady(request.id, "sender")
        waiting.cancelAndJoin()

        val late = RecordingHost().also { bridge.attach(request.id, it) }

        assertEquals(emptyList<Any>(), late.shown)
    }

    @Test
    fun `G7 detaching a destroyed instance leaves a newer one attached`() {
        val old = RecordingHost().also { bridge.attach(7, it) }
        val recreated = RecordingHost().also { bridge.attach(7, it) }

        bridge.detach(7, old)
        bridge.dialogReady(7, "sender")

        assertEquals(listOf<Any>("sender"), recreated.shown)
    }

    @Test
    fun `G7 a failure after the dialog is REFUSED, before it UNAVAILABLE, and the attached instance finishes`() = runTest {
        val before = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val first = launched.last()
        val firstHost = RecordingHost().also { bridge.attach(first.id, it) }
        bridge.associationFailed(first.id)
        assertEquals(BlePrepBridge.Answer.UNAVAILABLE, before.await())
        assertEquals(1, firstHost.finishes)

        val after = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        runCurrent()
        val second = launched.last()
        val secondHost = RecordingHost().also { bridge.attach(second.id, it) }
        bridge.dialogReady(second.id, "sender")
        bridge.associationFailed(second.id)
        assertEquals(BlePrepBridge.Answer.REFUSED, after.await())
        assertEquals(1, secondHost.finishes)
    }

    @Test
    fun `two requests get distinct ids`() = runTest {
        val a = async { bridge.prepare(BlePrepBridge.Kind.ASSOCIATION, "kernelhub2") }
        val b = async { bridge.prepare(BlePrepBridge.Kind.CONNECT_PERMISSION, "kernelhub2") }
        runCurrent()
        assertEquals(2, launched.map { it.id }.toSet().size)
        launched.forEach { bridge.complete(it.id, BlePrepBridge.Answer.REFUSED) }
        assertEquals(BlePrepBridge.Answer.REFUSED, a.await())
        assertEquals(BlePrepBridge.Answer.REFUSED, b.await())
    }
}
