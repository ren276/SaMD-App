package com.example.samdapp.di

import com.example.samdapp.data.remote.BearerInterceptor
import com.example.samdapp.data.remote.NetworkLoggingInterceptor
import com.example.samdapp.data.remote.TokenAuthenticator
import com.example.samdapp.data.remote.dev.DevDynamicHostInterceptor
import com.example.samdapp.data.remote.dev.DevServerConfig
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.testutil.FakeAuthTokenStore
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.inject.Provider

/**
 * Runs only under `testDevDebugUnitTest`: the dev host rewrite exists only in `src/dev/`.
 *
 * The client is the one Hilt builds: [NetworkModule.provideOkHttpClient] fed the list
 * [DevNetworkModule.provideDevHostInterceptors] returns. A test-only terminal interceptor is
 * appended after the production chain, so a call that gets past the gate is answered there instead
 * of opening a socket. The load-bearing claim: the gate judges the address the dev interceptor
 * rewrote to (127.0.0.1 -> 10.203.2.52), not the address the caller wrote. Put the gate before the
 * rewrite and the denied case goes through.
 */
class DevLocalNetworkGateOrderTest {

    private class FakeDevServerConfig(var activeUrl: String = "http://10.203.2.52:8080/") : DevServerConfig {
        override fun getActiveBaseUrl(): String = activeUrl
        override fun setBaseUrl(newUrl: String) {
            activeUrl = newUrl
        }
    }

    private class Run(granted: Boolean) {
        val reached = mutableListOf<Request>()
        val events = mutableListOf<String>()

        private val terminal = Interceptor { chain ->
            reached += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        private val recorder = object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) {
                events += "dnsStart"
            }

            override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                events += "connectStart"
            }
        }

        val client: OkHttpClient = NetworkModule.provideOkHttpClient(
            loggingInterceptor = NetworkLoggingInterceptor({ }, HttpLoggingInterceptor.Level.NONE),
            bearerInterceptor = BearerInterceptor(FakeAuthTokenStore()),
            tokenAuthenticator = TokenAuthenticator(FakeAuthTokenStore(), Provider { error("no refresh in this test") }),
            devInterceptors = DevNetworkModule.provideDevHostInterceptors(DevDynamicHostInterceptor(FakeDevServerConfig())),
            localNetworkGate = LocalNetworkGate(sdkInt = 37) { granted },
        ).newBuilder().addInterceptor(terminal).eventListener(recorder).build()

        fun call(url: String): Response = client.newCall(Request.Builder().url(url).build()).execute()
    }

    @Test
    fun `denied, the rewritten 10 dot literal is stopped before the terminal and before any dns or connect`() {
        val run = Run(granted = false)
        assertThrows(LocalNetworkNotAllowedException::class.java) {
            run.call("http://127.0.0.1:8080/api/v1/auth/login")
        }
        assertTrue("terminal reached: ${run.reached}", run.reached.isEmpty())
        assertEquals(emptyList<String>(), run.events)
    }

    @Test
    fun `granted, the terminal sees the rewritten host and port`() {
        val run = Run(granted = true)
        run.call("http://127.0.0.1:8080/api/v1/auth/login").close()
        assertEquals(1, run.reached.size)
        assertEquals("10.203.2.52", run.reached[0].url.host)
        assertEquals(8080, run.reached[0].url.port)
        assertEquals("/api/v1/auth/login", run.reached[0].url.encodedPath)
    }

    @Test
    fun `denied, a public host is not blocked and keeps its host`() {
        val run = Run(granted = false)
        run.call("https://example.com/api/v1/health").close()
        assertEquals(1, run.reached.size)
        assertEquals("example.com", run.reached[0].url.host)
    }
}
