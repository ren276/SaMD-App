package com.example.samdapp.domain.connectivity

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether Android 17 treats [address] as local network, per
 * https://developer.android.com/privacy-and-security/local-network-definition (memo section 1.2):
 * IPv4 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10, multicast 224/4 and the broadcast
 * 255.255.255.255; IPv6 link-local and multicast. Loopback is not local: measured exempt on the
 * Pixel (memo 1.3), and treating it as local would put a prompt in front of the `adb reverse` setup.
 */
fun isLocalNetworkAddress(address: InetAddress): Boolean = when {
    address.isLoopbackAddress || address.isAnyLocalAddress -> false
    address is Inet4Address -> isLocalIpv4(address.address)
    address is Inet6Address -> address.isLinkLocalAddress || address.isMulticastAddress
    else -> false
}

/**
 * Whether [host] (as OkHttp's `HttpUrl.host` gives it) is local network by its text alone: a
 * `.local` mDNS name, or an IP literal that [isLocalNetworkAddress] calls local. Any other name is
 * false here, because only its resolved addresses can say; [LocalNetworkGateDns] classifies those.
 * Never resolves a name: an IPv4 literal is parsed by hand and an IPv6 one only when it has a ':'.
 */
fun isLocalNetworkHost(host: String): Boolean {
    if (host.endsWith(".local", ignoreCase = true)) return true
    parseIpv4(host)?.let { return isLocalIpv4(it) }
    if (':' in host) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return isLocalNetworkAddress(address)
    }
    return false
}

/** True only on an enforcing OS, without the grant, for a local target. Every other case lets the
 *  call run and classify as it always has. */
fun isLocalNetworkBlocked(sdkInt: Int, granted: Boolean, local: Boolean): Boolean =
    sdkInt >= ACCESS_LOCAL_NETWORK_ENFORCED_SDK && !granted && local

private fun isLocalIpv4(b: ByteArray): Boolean {
    val a0 = b[0].toInt() and 0xff
    val a1 = b[1].toInt() and 0xff
    return when {
        a0 == 10 -> true
        a0 == 172 && a1 in 16..31 -> true
        a0 == 192 && a1 == 168 -> true
        a0 == 169 && a1 == 254 -> true
        a0 == 100 && a1 in 64..127 -> true
        a0 in 224..239 -> true
        b.all { it == 0xff.toByte() } -> true
        else -> false
    }
}

private fun parseIpv4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}
