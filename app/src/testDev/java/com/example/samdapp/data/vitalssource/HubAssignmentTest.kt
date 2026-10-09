package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.vitalssource.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** G-B19. The `PI_HUB_ASSIGNMENT` grammar and every refusal in it, one case each. */
class HubAssignmentTest {

    /** A literal of this test's own, not a constant from production code: Gradle holds the one default. */
    private val SHIPPED_DEFAULT = "SPO2=wifi:kernelhub1,BP=ble:kernelhub2"

    /** The rule a refusal names is part of the contract: each case pins WHICH rule fired, so a
     *  value refused for the wrong reason (or by an unrelated check) does not pass for the right one. */
    private fun assertRefused(raw: String, rule: String) {
        try {
            HubAssignment.parse(raw)
            fail("'$raw' should have been refused ($rule)")
        } catch (e: IllegalArgumentException) {
            assertTrue("'$raw' was refused for another reason: ${e.message}", e.message.orEmpty().contains(rule))
        }
    }

    @Test
    fun `the default assignment parses and routes SPO2 to Wi-Fi and BP to BLE`() {
        val assignment = HubAssignment.parse(SHIPPED_DEFAULT)

        assertEquals(HubRoute.Wifi("kernelhub1"), assignment.routeFor(Instrument.SPO2))
        assertEquals(HubRoute.Ble("kernelhub2"), assignment.routeFor(Instrument.BP))
        assertEquals("kernelhub1", assignment.wifiHubId)
    }

    @Test
    fun `an unassigned instrument has no route`() {
        val assignment = HubAssignment.parse(SHIPPED_DEFAULT)

        assertNull(assignment.routeFor(Instrument.GLUCOMETER))
        assertNull(assignment.routeFor(Instrument.THERMOMETER))
    }

    @Test
    fun `an all BLE assignment has no Wi-Fi hub`() {
        assertNull(HubAssignment.parse("BP=ble:kernelhub2").wifiHubId)
    }

    @Test
    fun `several Wi-Fi instruments may share one hub`() {
        val assignment = HubAssignment.parse("SPO2=wifi:kernelhub1,GLUCOMETER=wifi:kernelhub1")

        assertEquals("kernelhub1", assignment.wifiHubId)
        assertEquals(HubRoute.Wifi("kernelhub1"), assignment.routeFor(Instrument.GLUCOMETER))
    }

    @Test
    fun `thermometer and spo2 may use BLE`() {
        val assignment = HubAssignment.parse("THERMOMETER=ble:kernelhub2,SPO2=ble:kernelhub2")

        assertEquals(HubRoute.Ble("kernelhub2"), assignment.routeFor(Instrument.THERMOMETER))
        assertEquals(HubRoute.Ble("kernelhub2"), assignment.routeFor(Instrument.SPO2))
    }

    @Test
    fun `the laptop desk assignment parses`() {
        assertEquals("laptop-docker", HubAssignment.parse("SPO2=wifi:laptop-docker").wifiHubId)
    }

    @Test
    fun `an empty string is refused`() = assertRefused("", "is empty")

    @Test
    fun `whitespace anywhere is refused`() {
        assertRefused(" SPO2=wifi:kernelhub1", "whitespace")
        assertRefused("SPO2=wifi:kernelhub1 ", "whitespace")
        assertRefused("SPO2=wifi:kernelhub1, BP=ble:kernelhub2", "whitespace")
        assertRefused("SPO2=wifi:kernel hub1", "whitespace")
        assertRefused("SPO2=wifi:kernelhub1\n", "whitespace")
    }

    @Test
    fun `a malformed entry is refused`() {
        assertRefused("SPO2", "malformed entry")
        assertRefused("SPO2=wifi", "malformed entry")
        assertRefused("SPO2=wifi:kernelhub1:extra", "malformed entry")
        assertRefused("SPO2=wifi:kernelhub1,", "malformed entry")
        assertRefused("SPO2=wifi=kernelhub1", "malformed entry")
    }

    @Test
    fun `an unknown instrument is refused`() {
        assertRefused("PULSE=wifi:kernelhub1", "unknown instrument")
        assertRefused("spo2=wifi:kernelhub1", "unknown instrument")
    }

    @Test
    fun `an unknown transport is refused`() {
        assertRefused("SPO2=usb:kernelhub1", "unknown transport")
        assertRefused("SPO2=WIFI:kernelhub1", "unknown transport")
        assertRefused("SPO2=:kernelhub1", "unknown transport")
    }

    @Test
    fun `a hub_id outside the SaMDPi rule is refused`() {
        assertRefused("SPO2=wifi:ab", "hub_id")
        assertRefused("SPO2=wifi:" + "a".repeat(33), "hub_id")
        assertRefused("SPO2=wifi:KernelHub1", "hub_id")
        assertRefused("SPO2=wifi:kernel_hub1", "hub_id")
        assertRefused("SPO2=wifi:", "hub_id")
        assertRefused("SPO2=wifi:kernel.hub1", "hub_id")
    }

    @Test
    fun `a hub_id at the length limits is accepted`() {
        HubAssignment.parse("SPO2=wifi:abc")
        HubAssignment.parse("SPO2=wifi:" + "a".repeat(32))
    }

    @Test
    fun `a duplicate instrument is refused`() {
        assertRefused("SPO2=wifi:kernelhub1,SPO2=wifi:kernelhub1", "duplicate instrument")
        assertRefused("BP=ble:kernelhub2,BP=wifi:kernelhub1", "duplicate instrument")
    }

    @Test
    fun `more than one distinct Wi-Fi hub is refused`() {
        assertRefused("SPO2=wifi:kernelhub1,GLUCOMETER=wifi:kernelhub9", "more than one Wi-Fi hub_id")
    }

    @Test
    fun `BLE for an instrument other than BP, SPO2 and THERMOMETER is refused`() {
        assertRefused("GLUCOMETER=ble:kernelhub2", "ble is not allowed")
        assertRefused("WEIGHT_SCALE=ble:kernelhub2", "ble is not allowed")
        assertRefused("HEART_RATE=ble:kernelhub2", "ble is not allowed")
    }

    @Test
    fun `parseOrNone turns every refusal into an assignment that serves nothing`() {
        val none = HubAssignment.parseOrNone("not a valid value")

        Instrument.entries.forEach { assertNull("$it must be unassigned", none.routeFor(it)) }
        assertNull(none.wifiHubId)
    }
}
