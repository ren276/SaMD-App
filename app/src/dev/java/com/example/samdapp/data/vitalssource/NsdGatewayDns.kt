package com.example.samdapp.data.vitalssource

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Resolves the Pi gateway's `.local` hostname over mDNS, because Android's system resolver does
 * not. `getaddrinfo` on Android has no mDNS path, so `InetAddress.getByName("kernel-hub.local")`
 * throws [UnknownHostException] and OkHttp reports the gateway as unreachable even when it is
 * sitting on the same Wi-Fi answering requests. [NsdManager] is the only supported way to resolve
 * an mDNS name from an app, hence this [Dns].
 *
 * This is deliberately a [Dns] and not an [okhttp3.Interceptor]. A Dns implementation maps a name
 * to addresses before the connection is opened; it never inspects a response, never rewrites a
 * URL and never re-issues a request. That keeps it on the right side of the rule established when
 * the dynamic-host failover was removed (see [com.example.samdapp.data.remote.dev.DevServerConfig]):
 * a failed call must propagate, not get replayed somewhere else. A slow or broken gateway fails
 * here exactly as it did before.
 *
 * Installed only on the Pi gateway's own client (see
 * [com.example.samdapp.di.PiGatewayNetworkModule]); the backend and ABHA stacks keep [Dns.SYSTEM].
 * The whole file lives in `src/dev/`, so staging and prod cannot compile a path to it.
 *
 * Requires the gateway to advertise a DNS-SD service of type [SERVICE_TYPE]. Avahi's default
 * `_workstation._tcp` record is NOT usable: it advertises port 9 (discard), not the gateway. See
 * `tools/kernel-hub-avahi.service` for the file that must be installed on the Pi.
 */
class NsdGatewayDns(
    private val systemDns: Dns = Dns.SYSTEM,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Resolves an mDNS instance name (`kernel-hub`) to an address, or null if it is not found in
     *  time. Seam so the cache and delegation logic are testable without a device or a LAN. */
    private val discover: (String) -> InetAddress?,
) : Dns {

    private class Entry(val address: InetAddress, val resolvedAtMillis: Long)

    private val cached = AtomicReference<Entry?>(null)

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.endsWith(MDNS_SUFFIX, ignoreCase = true)) {
            return systemDns.lookup(hostname)
        }

        cached.get()?.let { entry ->
            if (nowMillis() - entry.resolvedAtMillis < CACHE_TTL_MILLIS) {
                return listOf(entry.address)
            }
        }

        // ponytail: fixed 60s TTL, no invalidation on connect failure. A Dns does not see whether
        // the connection it fed succeeded, so a gateway that changes address mid-session stays
        // stale for up to a minute. Push invalidation down from the call site if that bites.
        val instanceName = hostname.dropLast(MDNS_SUFFIX.length)
        val address = discover(instanceName)
            ?: throw UnknownHostException(
                "mDNS: no '$SERVICE_TYPE' service named '$instanceName' on this network. " +
                    "Check the handset is on the gateway's LAN and that the Pi has " +
                    "tools/kernel-hub-avahi.service installed.",
            )

        cached.set(Entry(address, nowMillis()))
        return listOf(address)
    }

    companion object {
        private const val MDNS_SUFFIX = ".local"

        /** A dedicated type, not `_http._tcp`, so discovery matches the gateway and nothing else
         *  on a campus LAN full of HTTP advertisers. Must match the Pi's Avahi service file. */
        const val SERVICE_TYPE = "_samd-gw._tcp."

        /** Long enough that an acquisition (start, measurement, stop) resolves once, short enough
         *  that a DHCP lease change is picked up without reinstalling the app. */
        private const val CACHE_TTL_MILLIS = 60_000L

        /** Discovery plus resolve must finish inside the gateway client's 2s connect timeout
         *  budget with room to spare; the worker is standing at the instrument. */
        private const val DISCOVERY_TIMEOUT_MILLIS = 1_500L

        private val logger = Logger.getLogger("NsdGatewayDns")

        /**
         * The real [NsdManager]-backed discovery, suitable as the `discover` argument.
         *
         * Serialised: [NsdManager.resolveService] rejects a second listener while one is in
         * flight, and OkHttp may call [lookup] from more than one dispatcher thread.
         */
        fun nsdDiscovery(context: Context): (String) -> InetAddress? {
            val lock = Any()
            // getSystemService is deferred into the lambda so that merely constructing the Hilt
            // graph touches no Android framework class. Nothing here runs until a gateway call is
            // actually made, which on a dev build is only after a worker presses Start.
            return { instanceName ->
                synchronized(lock) {
                    val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
                    discoverOnce(nsdManager, instanceName)
                }
            }
        }

        @Suppress("DEPRECATION") // getHost()/resolveService: replacements are API 34, minSdk is 26.
        private fun discoverOnce(nsdManager: NsdManager, instanceName: String): InetAddress? {
            val latch = CountDownLatch(1)
            val found = AtomicReference<InetAddress?>(null)

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    found.set(info.host)
                    latch.countDown()
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    logger.warning("mDNS resolve failed for ${info.serviceName}: error $errorCode")
                    latch.countDown()
                }
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    // Avahi publishes the instance name as the Pi's hostname, so this is the same
                    // token the BuildConfig URL carries. An exact match keeps a second gateway
                    // (Avahi renames a clash to "kernel-hub #2") from being picked up silently.
                    if (info.serviceName == instanceName) {
                        nsdManager.resolveService(info, resolveListener)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    logger.warning("mDNS discovery failed to start: error $errorCode")
                    latch.countDown()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            return try {
                latch.await(DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                found.get()
            } finally {
                // Not optional. A discovery left running holds a listener registration that
                // NsdManager refuses to re-register, so skipping this breaks every later lookup.
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                    .onFailure { logger.warning("mDNS stopServiceDiscovery: ${it.message}") }
            }
        }
    }
}
