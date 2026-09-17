package com.example.samdapp.data.vitalssource

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Runs only under `testDevDebugUnitTest`, since [NsdGatewayDns] exists only in `src/dev/`.
 *
 * The [NsdManager][android.net.nsd.NsdManager] half is not exercised here — it needs a device and
 * a LAN. What is exercised is everything wrapped around it: which names go to mDNS at all, that a
 * miss fails rather than falling through to a wrong address, and the TTL cache, all of which are
 * where a bug would silently repoint or hang instrument traffic.
 */
class NsdGatewayDnsTest {

    private val gatewayAddress: InetAddress = InetAddress.getByAddress(
        "kernel-hub.local",
        byteArrayOf(10, (203).toByte(), 12, 57),
    )

    private class RecordingDns(private val answer: InetAddress) : Dns {
        var lookups = mutableListOf<String>()
        override fun lookup(hostname: String): List<InetAddress> {
            lookups += hostname
            return listOf(answer)
        }
    }

    private fun dns(
        systemDns: Dns = RecordingDns(gatewayAddress),
        now: () -> Long = { 0L },
        discover: (String) -> InetAddress?,
    ) = NsdGatewayDns(systemDns = systemDns, nowMillis = now, discover = discover)

    @Test
    fun `dot-local host is resolved over mDNS, not the system resolver`() {
        val system = RecordingDns(gatewayAddress)
        var askedFor: String? = null

        val result = dns(systemDns = system, discover = { askedFor = it; gatewayAddress })
            .lookup("kernel-hub.local")

        assertEquals(listOf(gatewayAddress), result)
        // The instance name handed to NSD is the hostname minus ".local"; DNS-SD has no such suffix.
        assertEquals("kernel-hub", askedFor)
        assertTrue("system resolver must not see a .local name", system.lookups.isEmpty())
    }

    @Test
    fun `non-local host is delegated to the system resolver and never to mDNS`() {
        val system = RecordingDns(gatewayAddress)
        var discoverCalls = 0

        dns(systemDns = system, discover = { discoverCalls++; null }).lookup("api.example.com")

        assertEquals(listOf("api.example.com"), system.lookups)
        assertEquals(0, discoverCalls)
    }

    /** The failure that matters: an unfound gateway must surface as an unreachable host, never as
     *  a fallback to whatever the system resolver would say. Instrument traffic reaching some
     *  other machine is worse than it reaching nothing. */
    @Test
    fun `mDNS miss throws instead of falling back to the system resolver`() {
        val system = RecordingDns(gatewayAddress)

        assertThrows(UnknownHostException::class.java) {
            dns(systemDns = system, discover = { null }).lookup("kernel-hub.local")
        }
        assertTrue(system.lookups.isEmpty())
    }

    @Test
    fun `repeat lookups inside the TTL discover only once`() {
        var discoverCalls = 0
        var clock = 0L
        val resolver = dns(now = { clock }, discover = { discoverCalls++; gatewayAddress })

        resolver.lookup("kernel-hub.local")
        clock = 59_000L
        resolver.lookup("kernel-hub.local")

        assertEquals(1, discoverCalls)
    }

    @Test
    fun `cache expires after the TTL so a moved gateway is picked up`() {
        val moved = InetAddress.getByAddress("kernel-hub.local", byteArrayOf(10, (203).toByte(), 12, 99))
        var clock = 0L
        var discoverCalls = 0
        val resolver = dns(
            now = { clock },
            discover = { discoverCalls++; if (discoverCalls == 1) gatewayAddress else moved },
        )

        assertEquals(listOf(gatewayAddress), resolver.lookup("kernel-hub.local"))
        clock = 60_001L
        assertEquals(listOf(moved), resolver.lookup("kernel-hub.local"))
        assertEquals(2, discoverCalls)
    }

    /** Avahi's default `_workstation._tcp` record advertises port 9, so discovery has to be
     *  pinned to the gateway's own type or it will "find" the Pi and talk to discard. */
    @Test
    fun `service type is the dedicated gateway type`() {
        assertEquals("_samd-gw._tcp.", NsdGatewayDns.SERVICE_TYPE)
    }
}
