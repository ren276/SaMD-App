package com.example.samdapp.domain.connectivity

import com.example.samdapp.data.remote.LocalNetworkGateDns
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

/**
 * Memo Q13: IPv6 unique local fc00::/7 is local network, decided from the address bytes. Every
 * literal goes through the host entry point, the address entry point and the Dns wrapper, so the
 * three cannot disagree.
 */
class LocalNetworkUlaTest {

    private val local = listOf(
        "fc00::", "fc00::1", "fd00::1", "fd12:3456:789a::1",
        "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "::ffff:192.168.1.10",
    )

    private val notLocalByThisRule = listOf(
        "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1", "2001:db8::1", "::ffff:8.8.8.8",
    )

    // Unchanged by Q13: pinned to what the code did before it.
    private val unchanged = mapOf("::1" to false, "fe80::1" to true, "127.0.0.1" to false)

    private fun dnsFor(literal: String, granted: Boolean = false) = LocalNetworkGateDns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(literal))
        },
        LocalNetworkGate(sdkInt = 37) { granted },
    )

    @Test
    fun `local literals are local through the host, address and Dns entry points`() {
        for (literal in local) {
            assertEquals("host $literal", true, isLocalNetworkHost(literal))
            assertEquals("address $literal", true, isLocalNetworkAddress(InetAddress.getByName(literal)))
            assertThrows("dns $literal", LocalNetworkNotAllowedException::class.java) { dnsFor(literal).lookup("name.test") }
        }
    }

    @Test
    fun `literals outside the rule are not local through all three entry points`() {
        for (literal in notLocalByThisRule) {
            assertEquals("host $literal", false, isLocalNetworkHost(literal))
            assertEquals("address $literal", false, isLocalNetworkAddress(InetAddress.getByName(literal)))
            assertEquals("dns $literal", listOf(InetAddress.getByName(literal)), dnsFor(literal).lookup("name.test"))
        }
    }

    @Test
    fun `current behavior for loopback and link-local is unchanged`() {
        for ((literal, expected) in unchanged) {
            assertEquals("host $literal", expected, isLocalNetworkHost(literal))
            assertEquals("address $literal", expected, isLocalNetworkAddress(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun `a granted gate lets a ULA answer through the Dns wrapper`() {
        assertEquals(listOf(InetAddress.getByName("fd00::1")), dnsFor("fd00::1", granted = true).lookup("name.test"))
    }

    @Test
    fun `a bracketed and an unbracketed literal classify the same`() {
        assertEquals(true, isLocalNetworkHost("fd00::1"))
        assertEquals(isLocalNetworkHost("fd00::1"), isLocalNetworkHost("[fd00::1]"))
        assertEquals(isLocalNetworkHost("fe00::1"), isLocalNetworkHost("[fe00::1]"))
    }

    @Test
    fun `a hostname without a colon is decided by its text and never resolved`() {
        // By construction: isLocalNetworkHost reaches InetAddress.getByName only when the host has a
        // ':', so these names never leave the string checks. The .invalid TLD cannot resolve, so a
        // regression that tried would fail slowly here rather than answer.
        for (name in listOf("example.com", "no-such-host.invalid", "kernelhub1.local")) {
            assertEquals(name, name.endsWith(".local"), isLocalNetworkHost(name))
        }
    }
}
