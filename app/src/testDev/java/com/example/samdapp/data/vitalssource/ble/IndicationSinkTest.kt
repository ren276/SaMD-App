package com.example.samdapp.data.vitalssource.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1: the one feed of [HubLink.events], shared by `BleHubManager` and [FakeHubLink]. */
class IndicationSinkTest {

    private fun indication(n: Int) = HubEvent.ControlPointIndication(byteArrayOf(n.toByte()))

    @Test
    fun `an indication offered after close neither throws nor reaches the consumer`() {
        val sink = IndicationSink()
        sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED, "the link was closed"))

        val offered = runCatching { sink.offer(indication(1)) }

        assertTrue("offer after close threw: ${offered.exceptionOrNull()}", offered.isSuccess)
        val received = sink.events.tryReceive()
        assertTrue("the indication reached the consumer: $received", received.isClosed)
        assertEquals(HubLinkException.Failure.DISCONNECTED, (received.exceptionOrNull() as HubLinkException).failure)
    }

    @Test
    fun `the default sink is unlimited, so no indication is refused while nobody receives`() {
        val sink = IndicationSink()
        repeat(10_000) { sink.offer(indication(it)) }
        sink.close(HubLinkException(HubLinkException.Failure.DISCONNECTED))

        var count = 0
        while (sink.events.tryReceive().isSuccess) count++
        assertEquals(10_000, count)
    }

    @Test
    fun `a refusal that is not a close closes the channel as a lost link, never a silent drop`() {
        val sink = IndicationSink(capacity = 1)
        sink.offer(indication(1))
        sink.offer(indication(2))

        assertTrue(sink.events.tryReceive().isSuccess)
        val next = sink.events.tryReceive()
        assertTrue("the refused indication was dropped in silence: $next", next.isClosed)
        assertEquals(HubLinkException.Failure.DISCONNECTED, (next.exceptionOrNull() as HubLinkException).failure)
    }
}
