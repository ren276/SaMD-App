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
