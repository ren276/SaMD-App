package com.example.samdapp.domain.connectivity

import android.os.Build
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memo section 7, host table and gate table. */
class LocalNetworkHostTest {

    private val local = listOf(
        "10.0.0.1", "10.255.255.255", "10.203.2.52",
        "172.16.0.1", "172.31.255.255",
        "192.168.0.1", "192.168.255.255",
        "169.254.0.1", "169.254.255.255",
        "100.64.0.1", "100.127.255.255",
        "224.0.0.251", "239.255.255.255",
        "255.255.255.255",
        "fe80::1", "febf::1",
        "ff02::fb",
        "kernelhub1.local", "KERNELHUB1.LOCAL",
    )

    private val notLocal = listOf(
        "127.0.0.1", "127.1.2.3", "::1", "localhost", "0.0.0.0",
        "172.15.255.255", "172.32.0.1",
        "100.63.255.255", "100.128.0.1",
        "8.8.8.8", "223.255.255.255", "240.0.0.1",
        "2001:db8::1",
        "api.samd.example.com", "staging.samd.example.com", "local", "notlocal",
        "10.0.0", "10.0.0.256", "1o.0.0.1",
    )

    @Test
    fun `local network hosts are classified local`() {
        val wrong = local.filterNot(::isLocalNetworkHost)
        assertTrue("classified not local: $wrong", wrong.isEmpty())
    }

    @Test
    fun `loopback, public and malformed hosts are not local`() {
        val wrong = notLocal.filter(::isLocalNetworkHost)
        assertTrue("classified local: $wrong", wrong.isEmpty())
    }

    @Test
    fun `resolved addresses use the same table`() {
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(isLocalNetworkAddress(InetAddress.getByName("::ffff:192.168.1.5")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("::1")))
        assertFalse(isLocalNetworkAddress(InetAddress.getByName("93.184.216.34")))
    }

    @Test
    fun `the gate blocks only an enforcing OS without the grant for a local target`() {
        val enforced = Build.VERSION_CODES.CINNAMON_BUN
        val rows = mapOf(
            Triple(enforced, false, true) to true,
            Triple(enforced + 1, false, true) to true,
            Triple(enforced, true, true) to false,
            Triple(enforced, false, false) to false,
            Triple(enforced - 1, false, true) to false,
            Triple(enforced - 1, true, false) to false,
        )
        for ((input, expected) in rows) {
            val (sdk, granted, isLocal) = input
            assertEquals("gate($sdk, $granted, $isLocal)", expected, isLocalNetworkBlocked(sdk, granted, isLocal))
        }
    }

    @Test
    fun `the constants come from the platform`() {
        assertEquals(37, ACCESS_LOCAL_NETWORK_ENFORCED_SDK)
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", ACCESS_LOCAL_NETWORK_PERMISSION)
    }
}
