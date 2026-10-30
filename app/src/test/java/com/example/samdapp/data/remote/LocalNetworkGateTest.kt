package com.example.samdapp.data.remote

import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * Memo 11.1 and 11.8: the gate stops a blocked local call before any socket, through either hook,
 * and the caller receives [LocalNetworkNotAllowedException] itself (E2).
 */
class LocalNetworkGateTest {

    /** Counts every socket OkHttp asks for and refuses it, so a test never touches a network and
     *  "no socket was opened" is a number. */
    private class CountingSocketFactory : SocketFactory() {
        val sockets = AtomicInteger()
        override fun createSocket(): Socket {
            sockets.incrementAndGet()
            throw IOException("counted socket")
        }
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket()
    }

    private val denied = LocalNetworkGate(sdkInt = 37) { false }
    private val granted = LocalNetworkGate(sdkInt = 37) { true }

    /** Resolves every name to [answer] without a real lookup. */
    private fun fakeDns(vararg answer: String): Dns = object : Dns {
        override fun lookup(hostname: String) = answer.map { InetAddress.getByName(it) }
    }

    private fun client(
        gate: LocalNetworkGate,
        sockets: CountingSocketFactory,
        delegateDns: Dns = Dns.SYSTEM,
        retry: Boolean = true,
    ): OkHttpClient = OkHttpClient.Builder()
        .dns(LocalNetworkGateDns(delegateDns, gate))
        .addInterceptor(LocalNetworkGateInterceptor(gate))
        .socketFactory(sockets)
        .retryOnConnectionFailure(retry)
        .build()

    private fun call(client: OkHttpClient, url: String): IOException = try {
        client.newCall(Request.Builder().url(url).build()).execute().close()
        fail("expected an IOException")
        error("unreachable")
    } catch (e: IOException) {
        e
    }

    @Test
    fun `interceptor stops a local literal before any socket`() {
        val sockets = CountingSocketFactory()
        val e = call(client(denied, sockets), "http://10.203.2.52:8080/api/v1/auth/login")
        assertSame(LocalNetworkNotAllowedException::class.java, e.javaClass)
        assertEquals(0, sockets.sockets.get())
    }

    @Test
    fun `interceptor stops a dot-local name before any lookup or socket`() {
        val sockets = CountingSocketFactory()
        val lookups = AtomicInteger()
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                return listOf(InetAddress.getByName("10.0.0.9"))
            }
        }
        val e = call(client(denied, sockets, delegateDns = dns), "http://kernelhub1.local:8090/session/start")
        assertSame(LocalNetworkNotAllowedException::class.java, e.javaClass)
        assertEquals(0, lookups.get())
        assertEquals(0, sockets.sockets.get())
    }

    @Test
    fun `with the grant the same literal reaches the socket`() {
        val sockets = CountingSocketFactory()
        val e = call(client(granted, sockets), "http://10.203.2.52:8080/api/v1/auth/login")
        assertTrue(e !is LocalNetworkNotAllowedException)
        assertTrue(sockets.sockets.get() > 0)
    }

    @Test
    fun `Dns hook stops a public-looking name that resolves to a private address`() {
        val sockets = CountingSocketFactory()
        val e = call(client(denied, sockets, delegateDns = fakeDns("10.1.2.3")), "http://clinic.example/api/v1/sync/push")
        assertSame(LocalNetworkNotAllowedException::class.java, e.javaClass)
        assertEquals(0, sockets.sockets.get())
    }

    @Test
    fun `Dns hook keeps the public half of a mixed answer`() {
        val dns = LocalNetworkGateDns(fakeDns("10.1.2.3", "93.184.216.34"), denied)
        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), dns.lookup("clinic.example"))
    }

    @Test
    fun `Dns hook passes everything with the grant, and below the enforcing SDK`() {
        val answer = listOf(InetAddress.getByName("10.1.2.3"), InetAddress.getByName("93.184.216.34"))
        assertEquals(answer, LocalNetworkGateDns(fakeDns("10.1.2.3", "93.184.216.34"), granted).lookup("clinic.example"))
        val pre37 = LocalNetworkGate(sdkInt = 36) { false }
        assertEquals(answer, LocalNetworkGateDns(fakeDns("10.1.2.3", "93.184.216.34"), pre37).lookup("clinic.example"))
    }

    private interface Ping {
        @GET("api/v1/auth/me")
        suspend fun ping(): ResponseBody
    }

    /** E2: at a Retrofit suspend call site, through each hook, with retries on and off, the caller
     *  gets exactly the typed exception and no socket was opened. */
    @Test
    fun `Retrofit caller receives the typed exception itself, with and without retry`() {
        val cases = listOf(
            "http://10.203.2.52:8080/" to Dns.SYSTEM,
            "http://clinic.example/" to fakeDns("192.168.4.20"),
        )
        for ((baseUrl, dns) in cases) {
            for (retry in listOf(true, false)) {
                val sockets = CountingSocketFactory()
                val api = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client(denied, sockets, delegateDns = dns, retry = retry))
                    .build()
                    .create(Ping::class.java)
                val thrown = runBlocking { runCatching { api.ping() }.exceptionOrNull() }
                val label = "$baseUrl retry=$retry"
                assertSame(label, LocalNetworkNotAllowedException::class.java, thrown?.javaClass)
                assertEquals(label, 0, sockets.sockets.get())
            }
        }
    }
}
