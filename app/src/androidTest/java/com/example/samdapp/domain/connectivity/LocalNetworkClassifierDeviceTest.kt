package com.example.samdapp.domain.connectivity

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/**
 * Memo Q13, device half: the B-1 vector tables on a real Android runtime, where
 * `InetAddress.getByName` is libcore's, not the JVM's. Pure functions only: no Context, no storage,
 * no network. Every literal is parsed, never resolved, so nothing here touches DNS. The permission
 * state is a fake lambda, so the result does not depend on this phone's grants.
 */
class LocalNetworkClassifierDeviceTest {

    private val localLiterals = listOf(
        "fc00::", "fc00::1", "fd00::1", "fd12:3456:789a::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "::ffff:192.168.1.10", "fe80::1", "192.168.1.10", "10.203.2.52",
    )

    private val notLocalLiterals = listOf(
        "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fe00::1", "2001:db8::1", "::ffff:8.8.8.8",
        "::1", "127.0.0.1", "8.8.8.8",
    )

    @Test
    fun localLiteralsAreLocalThroughHostAndAddressEntryPoints() {
        for (literal in localLiterals) {
            assertEquals("host $literal", true, isLocalNetworkHost(literal))
            assertEquals("address $literal", true, isLocalNetworkAddress(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun otherLiteralsAreNotLocalThroughHostAndAddressEntryPoints() {
        for (literal in notLocalLiterals) {
            assertEquals("host $literal", false, isLocalNetworkHost(literal))
            assertEquals("address $literal", false, isLocalNetworkAddress(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun aBracketedAndAnUnbracketedLiteralClassifyTheSame() {
        assertEquals(true, isLocalNetworkHost("fd00::1"))
        assertEquals(isLocalNetworkHost("fd00::1"), isLocalNetworkHost("[fd00::1]"))
        assertEquals(isLocalNetworkHost("fe00::1"), isLocalNetworkHost("[fe00::1]"))
    }

    @Test
    fun aLocalNameIsLocalByItsSuffixAndAPublicNameIsNot() {
        assertEquals(true, isLocalNetworkHost("kernelhub1.local"))
        assertEquals(false, isLocalNetworkHost("example.com"))
    }

    @Test
    fun blocksHostFollowsTheFakePermissionAtSdk37() {
        val denied = LocalNetworkGate(sdkInt = 37) { false }
        val granted = LocalNetworkGate(sdkInt = 37) { true }
        for (host in localLiterals + "kernelhub1.local") {
            assertEquals("denied, $host", true, denied.blocksHost(host))
            assertEquals("granted, $host", false, granted.blocksHost(host))
        }
        for (host in notLocalLiterals + "example.com") {
            assertEquals("denied, $host", false, denied.blocksHost(host))
            assertEquals("granted, $host", false, granted.blocksHost(host))
        }
    }

    @Test
    fun blocksHostNeverBlocksBelowSdk37() {
        val denied = LocalNetworkGate(sdkInt = 36) { false }
        for (host in localLiterals + "kernelhub1.local") {
            assertEquals("sdk 36, $host", false, denied.blocksHost(host))
        }
    }
}
