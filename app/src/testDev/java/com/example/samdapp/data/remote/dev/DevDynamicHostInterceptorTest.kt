package com.example.samdapp.data.remote.dev

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Runs only under `testDevDebugUnitTest`, since [DevDynamicHostInterceptor] exists only in
 * `src/dev/` (same arrangement as `PiGatewayVitalsSourceTest`).
 *
 * The load-bearing case is [timedOutPost_isNotReplayed]. An earlier revision caught
 * `SocketTimeoutException`, probed `/health`, and re-issued the request against another host. On
 * the ABHA client that replayed an Aadhaar identity submit, which makes ABDM send a second OTP
 * and invalidate the first: the confirmed OTP regression (AUDIT5 section 3.2). A slow-but-healthy
 * backend was enough to trigger it, because `/health` answers instantly while the ABDM leg is the
 * slow part. That test fails the moment any replay path comes back.
 */
class DevDynamicHostInterceptorTest {

    private class FakeDevServerConfig(
        var activeUrl: String = "http://10.203.2.52:8080/",
    ) : DevServerConfig {
        override fun getActiveBaseUrl(): String = activeUrl
        override fun setBaseUrl(newUrl: String) {
            activeUrl = newUrl
        }
    }

    private class FakeChain(
        private var currentRequest: Request,
        private val handler: (Request) -> Response,
    ) : Interceptor.Chain {
        val executedRequests = mutableListOf<Request>()

        override fun request(): Request = currentRequest

        override fun proceed(request: Request): Response {
            executedRequests.add(request)
            currentRequest = request
            return handler(request)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 1000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 1000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun okResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

    /** The OTP-fix regression guard: exactly one `chain.proceed`, and the timeout propagates. */
    @Test
    fun timedOutPost_isNotReplayed() {
        val interceptor = DevDynamicHostInterceptor(FakeDevServerConfig())

        val identitySubmit = Request.Builder()
            .url("http://10.0.2.2:8080/api/v1/abha/registration-sessions/s-1/identity")
            .post("{\"aadhaar\":\"x\"}".toRequestBody("application/json".toMediaType()))
            .build()

        val chain = FakeChain(identitySubmit) { throw SocketTimeoutException("timeout") }

        assertThrows(SocketTimeoutException::class.java) { interceptor.intercept(chain) }
        assertEquals(1, chain.executedRequests.size)
        assertEquals("POST", chain.executedRequests.single().method)
    }

    @Test
    fun localBackendRequests_areRewrittenToActiveDevUrl() {
        val interceptor = DevDynamicHostInterceptor(FakeDevServerConfig("http://10.203.2.52:8080/"))

        val request = Request.Builder()
            .url("http://127.0.0.1:8080/api/v1/auth/login")
            .build()

        val chain = FakeChain(request) { req -> okResponse(req) }
        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals(1, chain.executedRequests.size)
        val executed = chain.executedRequests.single().url
        assertEquals("10.203.2.52", executed.host)
        assertEquals(8080, executed.port)
        assertEquals("/api/v1/auth/login", executed.encodedPath)
    }

    /** The rewrite carries host and port only. Copying the active URL's scheme would downgrade
     *  an https request to cleartext. */
    @Test
    fun rewriteKeepsTheRequestScheme() {
        val interceptor = DevDynamicHostInterceptor(FakeDevServerConfig("http://10.203.2.52:8080/"))

        val request = Request.Builder()
            .url("https://10.0.2.2:8080/api/v1/auth/login")
            .build()

        val chain = FakeChain(request) { req -> okResponse(req) }
        interceptor.intercept(chain)

        assertEquals("https", chain.executedRequests.single().url.scheme)
        assertEquals("10.203.2.52", chain.executedRequests.single().url.host)
    }

    /** 172.16.0.0/12 is private; 172.217.0.0/16 is Google. The old guard matched any `172.`
     *  host and classified public address space as the local backend. */
    @Test
    fun publicHostOutsidePrivateRanges_isNotRewritten() {
        val interceptor = DevDynamicHostInterceptor(FakeDevServerConfig("http://10.203.2.52:8080/"))

        val request = Request.Builder()
            .url("https://172.217.14.206/v1beta/models")
            .build()

        val chain = FakeChain(request) { req -> okResponse(req) }
        interceptor.intercept(chain)

        assertEquals("172.217.14.206", chain.executedRequests.single().url.host)
    }
}
