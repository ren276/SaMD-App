package com.example.samdapp.data.vitalssource.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** G-B15: the Device Information search runs over every 0x180A instance. */
class DisSearchTest {

    private fun sig(short: Int) = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    private fun ch(short: Int, ref: String) = DisSearch.Characteristic(sig(short), ref)

    private fun dis(vararg chars: DisSearch.Characteristic<String>) =
        DisSearch.Service(SigUuids.DEVICE_INFORMATION_SERVICE, chars.toList())

    /** BlueZ's own 0x180A: one PnP ID, no serial (parent memo 14.1 item 10). */
    private val bluez = dis(ch(0x2A50, "bluez-pnp"))

    /** The hub's: manufacturer, model, serial, software revision. */
    private val hub = dis(ch(0x2A29, "mfr"), ch(0x2A24, "model"), ch(0x2A25, "hub-serial"), ch(0x2A28, "hub-sw"))

    @Test
    fun `one serial across two Device Information services is found, whichever comes first`() {
        val expected = DisSearch.Result.Found("hub-serial", "hub-sw")
        assertEquals(expected, DisSearch.pick(listOf(bluez, hub)))
        assertEquals(expected, DisSearch.pick(listOf(hub, bluez)))
        assertEquals(expected, DisSearch.pick(listOf(hub)))
    }

    @Test
    fun `no serial anywhere is not unique`() {
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(bluez)))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(bluez, dis(ch(0x2A29, "mfr")))))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(emptyList<DisSearch.Service<String>>()))
    }

    @Test
    fun `two serials are not unique, in one service or across two`() {
        val second = dis(ch(0x2A25, "other-serial"))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(hub, second)))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(hub, bluez, second)))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(dis(ch(0x2A25, "a"), ch(0x2A25, "b")))))
    }

    @Test
    fun `a serial characteristic in a service that is not Device Information is ignored`() {
        val stray = DisSearch.Service(ControlPoint.VENDOR_SERVICE, listOf(ch(0x2A25, "stray-serial")))
        assertEquals(DisSearch.Result.Found("hub-serial", "hub-sw"), DisSearch.pick(listOf(stray, bluez, hub)))
        assertEquals(DisSearch.Result.NotUnique, DisSearch.pick(listOf(stray, bluez)))
    }

    @Test
    fun `the software revision comes from the same service as the serial`() {
        val bluezWithRevision = dis(ch(0x2A50, "bluez-pnp"), ch(0x2A28, "bluez-sw"))
        assertEquals(DisSearch.Result.Found("hub-serial", "hub-sw"), DisSearch.pick(listOf(bluezWithRevision, hub)))
        val noRevision = dis(ch(0x2A25, "hub-serial"))
        val found = DisSearch.pick(listOf(bluezWithRevision, noRevision)) as DisSearch.Result.Found
        assertEquals("hub-serial", found.serial)
        assertNull(found.softwareRevision)
    }
}
