package com.example.samdapp.presentation.compounder

import com.example.samdapp.R
import com.example.samdapp.domain.vitalssource.AcquisitionTransport
import com.example.samdapp.domain.vitalssource.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-B25. The refusal copy table, asserted on identity rather than on English: WHICH resource a
 * (reason, transport) pair selects, never the words, which are allowed to be reworded without
 * touching this file.
 */
class AcquisitionRejectionCopyTest {

    private val transports = listOf(null) + AcquisitionTransport.entries
    private val sdkLevels = listOf(0, 36, 37)

    private val wifiUnreachableKeys = setOf(
        R.string.acq_reject_unreachable_wifi,
        R.string.acq_reject_unreachable_or_blocked_wifi,
    )

    @Test
    fun `every reason has a resource for every transport at every sdk level`() {
        for (reason in RejectReason.entries) for (transport in transports) for (sdk in sdkLevels) {
            assertTrue(
                "$reason over $transport at sdk $sdk resolves to no resource",
                acquisitionRejectionRes(reason, transport, sdk) != 0,
            )
        }
    }

    @Test
    fun `a BLE unreachable result shows the BLE text, never a Wi-Fi one, at any sdk level`() {
        // A hub that is busy with another phone is reported as UNREACHABLE (addendum B5), so this
        // is also the text a BLE BUSY result shows.
        for (sdk in sdkLevels) {
            val res = acquisitionRejectionRes(RejectReason.UNREACHABLE, AcquisitionTransport.BLE, sdk)
            assertEquals(R.string.acq_reject_unreachable_ble, res)
            assertTrue("BLE unreachable fell back to a Wi-Fi key", res !in wifiUnreachableKeys)
        }
    }

    @Test
    fun `BLE timeout and not-supported have their own text`() {
        assertEquals(R.string.acq_reject_timeout_ble, acquisitionRejectionRes(RejectReason.TIMEOUT, AcquisitionTransport.BLE, 36))
        assertEquals(R.string.acq_reject_not_paired_ble, acquisitionRejectionRes(RejectReason.NOT_SUPPORTED, AcquisitionTransport.BLE, 36))
        assertNotEquals(
            acquisitionRejectionRes(RejectReason.TIMEOUT, AcquisitionTransport.WIFI, 36),
            acquisitionRejectionRes(RejectReason.TIMEOUT, AcquisitionTransport.BLE, 36),
        )
        assertNotEquals(
            acquisitionRejectionRes(RejectReason.NOT_SUPPORTED, AcquisitionTransport.WIFI, 36),
            acquisitionRejectionRes(RejectReason.NOT_SUPPORTED, AcquisitionTransport.BLE, 36),
        )
    }

    @Test
    fun `Wi-Fi and unrouted results keep the local-network classification at sdk 36 and 37`() {
        for (transport in listOf(null, AcquisitionTransport.WIFI)) {
            assertEquals(
                R.string.acq_reject_unreachable_or_blocked_wifi,
                acquisitionRejectionRes(RejectReason.UNREACHABLE, transport, 36),
            )
            assertEquals(
                R.string.acq_reject_unreachable_wifi,
                acquisitionRejectionRes(RejectReason.UNREACHABLE, transport, 37),
            )
            assertEquals(
                R.string.acq_reject_permission_denied_wifi,
                acquisitionRejectionRes(RejectReason.PERMISSION_DENIED, transport, 37),
            )
        }
    }

    @Test
    fun `every other reason reads the same over every transport`() {
        val transportDependent = setOf(RejectReason.UNREACHABLE, RejectReason.TIMEOUT, RejectReason.NOT_SUPPORTED)
        for (reason in RejectReason.entries - transportDependent) {
            val expected = acquisitionRejectionRes(reason, null, 36)
            for (transport in transports) {
                assertEquals("$reason differs over $transport", expected, acquisitionRejectionRes(reason, transport, 36))
            }
        }
    }

    @Test
    fun `the two new reasons have their own text, shared with nothing else`() {
        val bluetooth = acquisitionRejectionRes(RejectReason.BLUETOOTH_UNAVAILABLE, null, 36)
        val hub = acquisitionRejectionRes(RejectReason.HUB_MISMATCH, null, 36)
        assertEquals(R.string.acq_reject_bluetooth_unavailable, bluetooth)
        assertEquals(R.string.acq_reject_hub_mismatch, hub)
        val others = (RejectReason.entries - RejectReason.BLUETOOTH_UNAVAILABLE - RejectReason.HUB_MISMATCH)
            .flatMap { reason -> transports.map { acquisitionRejectionRes(reason, it, 36) } }
            .toSet()
        assertTrue("BLUETOOTH_UNAVAILABLE shares a text", bluetooth !in others)
        assertTrue("HUB_MISMATCH shares a text", hub !in others)
    }
}
