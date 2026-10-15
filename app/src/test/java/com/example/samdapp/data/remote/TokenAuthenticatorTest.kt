package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.testutil.FakeAuthTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * Proves [TokenAuthenticator]'s three hard rules against a real MockWebServer: real OkHttp
 * request/response/retry semantics, not a hand-rolled `Interceptor.Chain` mock. [BearerInterceptor]
 * is exercised too since it runs first in the same `OkHttpClient`.
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeAuthTokenStore
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeAuthTokenStore(accessToken = "old-access", refreshToken = "old-refresh")

        // A bare Retrofit/OkHttpClient for RetrofitAuthService, no BearerInterceptor, no
        // TokenAuthenticator. Production uses one shared client with path-based skipping instead
        // of a second bare client (di/NetworkModule.kt); this test isolates the refresh call from
        // the authenticator that calls it, so a bug here can't self-mask as a passing test.
        val authRetrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val retrofitAuthService = RetrofitAuthService(authRetrofit.create(AuthApiService::class.java))

        val authenticator = TokenAuthenticator(tokenStore, Provider { retrofitAuthService })
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(tokenStore))
            .authenticator(authenticator)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(path: String): okhttp3.Response =
        okHttpClient.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun refreshSuccessBody(accessToken: String, refreshToken: String) =
        """{"success":true,"data":{"access_token":"$accessToken","refresh_token":"$refreshToken","token_type":"Bearer","expires_in":3600},"meta":null}"""

    @Test
    fun `401 triggers exactly one refresh then retries with the new token`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(refreshSuccessBody("new-access", "new-refresh")),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val response = call("/api/v1/kernel/assess")

        assertEquals(200, response.code)
        assertEquals(3, server.requestCount)
        server.takeRequest() // original, 401
        val refreshRequest = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refreshRequest.path)
        assertNull("refresh call must carry no Authorization header", refreshRequest.getHeader("Authorization"))
        val retried = server.takeRequest()
        assertEquals("Bearer new-access", retried.getHeader("Authorization"))
    }

    @Test
    fun `gives up after one refresh attempt per original request`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(refreshSuccessBody("new-access", "new-refresh")),
        )
        // The retried request ALSO fails: a real, still-invalid situation. An authenticator that
        // retries unconditionally would fire a second refresh here and loop.
        server.enqueue(MockResponse().setResponseCode(401))

        val response = call("/api/v1/kernel/assess")

        assertEquals(401, response.code)
        assertEquals(
            "exactly one refresh attempt: original + refresh + one retry, no second refresh",
            3,
            server.requestCount,
        )
    }

    @Test
    fun `refresh failure clears the session and does not retry`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"code":"SAMD-AUTH-1004","detail":"Refresh token reuse detected."}"""),
        )

        val response = call("/api/v1/kernel/assess")

        assertEquals(401, response.code)
        assertEquals("no retry after a failed refresh", 2, server.requestCount)
        val snapshot = runBlocking { tokenStore.snapshot() }
        assertNull("access token must be cleared", snapshot.accessToken)
        assertNull("refresh token must be cleared", snapshot.refreshToken)
    }

    @Test
    fun `two concurrent 401s produce one refresh, not two`() {
        val refreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/v1/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(refreshSuccessBody("new-access", "new-refresh"))
                }
                request.getHeader("Authorization") == "Bearer old-access" -> MockResponse().setResponseCode(401)
                else -> MockResponse().setResponseCode(200).setBody("{\"ok\":true}")
            }
        }

        val results = runBlocking {
            val first = async(Dispatchers.IO) { call("/api/v1/a") }
            val second = async(Dispatchers.IO) { call("/api/v1/b") }
            listOf(first.await(), second.await())
        }

        assertTrue("both original callers must still succeed", results.all { it.code == 200 })
        assertEquals("single-flight: exactly one refresh network call", 1, refreshCount.get())
    }

    // ---- Q10 (memo 11.5): only a definitive refresh answer ends the session ----------------

    /** The protected call goes to [server]; the refresh call goes through [authClient] to
     *  [authBaseUrl], so a test can make the refresh alone fail in transport. */
    private fun clientWithRefreshVia(authClient: OkHttpClient, authBaseUrl: String): OkHttpClient {
        val authRetrofit = Retrofit.Builder()
            .baseUrl(authBaseUrl)
            .client(authClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = RetrofitAuthService(authRetrofit.create(AuthApiService::class.java))
        return OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, Provider { service }))
            .build()
    }

    private fun assertSessionKept() {
        val snapshot = runBlocking { tokenStore.snapshot() }
        assertEquals("access token must be kept", "old-access", snapshot.accessToken)
        // clear() is the only thing that ends the session and routes to sign-in in production.
        assertEquals("refresh token must be kept", "old-refresh", snapshot.refreshToken)
    }

    private fun failedCall(client: OkHttpClient): IOException = try {
        client.newCall(Request.Builder().url(server.url("/api/v1/sync/push")).build()).execute().close()
        fail("expected the original call to fail with the refresh's transport error")
        error("unreachable")
    } catch (e: IOException) {
        e
    }

    @Test
    fun `a refresh blocked by the local network gate keeps the session and surfaces the typed exception`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val blockedAuth = OkHttpClient.Builder()
            .addInterceptor(LocalNetworkGateInterceptor(LocalNetworkGate(sdkInt = 37) { false }))
            .build()

        val thrown = failedCall(clientWithRefreshVia(blockedAuth, "http://10.203.2.52:8080/"))

        assertSame(LocalNetworkNotAllowedException::class.java, thrown.javaClass)
        assertSessionKept()
    }

    @Test
    fun `a refresh that drops in transport keeps the session`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val droppingAuth = OkHttpClient.Builder()
            .addInterceptor { throw IOException("connection reset") }
            .build()

        val thrown = failedCall(clientWithRefreshVia(droppingAuth, server.url("/").toString()))

        assertEquals("connection reset", thrown.message)
        assertSessionKept()
    }

    @Test
    fun `a refresh answered 503 keeps the session and lets the original 401 through`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))

        val response = call("/api/v1/kernel/assess")

        assertEquals(401, response.code)
        assertSessionKept()
    }

    // ---- Q10a: the 403 allowlist ---------------------------------------------------------

    private fun refreshAnswered403(code: String): okhttp3.Response {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"code":"$code","detail":"x"}"""),
        )
        return call("/api/v1/kernel/assess")
    }

    private fun assertSessionEnded() {
        val snapshot = runBlocking { tokenStore.snapshot() }
        assertNull("access token must be cleared", snapshot.accessToken)
        assertNull("refresh token must be cleared", snapshot.refreshToken)
    }

    @Test
    fun `a refresh answered 403 account disabled ends the session`() {
        assertEquals(401, refreshAnswered403("SAMD-AUTH-1006").code)
        assertSessionEnded()
    }

    @Test
    fun `a refresh answered 403 device mismatch ends the session`() {
        assertEquals(401, refreshAnswered403("SAMD-AUTH-1007").code)
        assertSessionEnded()
    }

    @Test
    fun `a refresh answered 403 with any other code keeps the session`() {
        assertEquals(401, refreshAnswered403("SAMD-AUTH-1005").code)
        assertSessionKept()
        // A code this build does not know is not a reason to sign anyone out either.
        assertEquals(401, refreshAnswered403("SAMD-FUTURE-9999").code)
        assertSessionKept()
    }

    @Test
    fun `a refresh answered with invalid_grant ends the session`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""{"code":"invalid_grant","detail":"x"}"""),
        )
        call("/api/v1/kernel/assess")
        assertSessionEnded()
    }

    @Test
    fun `a refresh with an empty 200 body keeps the session`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        // Gson reads an empty body as an EOFException, which reaches RetrofitAuthService as an
        // IOException, so the original call fails with it. The session is what Q10a protects.
        val thrown = failedCall(okHttpClient)
        assertTrue(thrown is java.io.EOFException)
        assertSessionKept()
    }
}
