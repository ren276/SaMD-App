package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.AuthApiService
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
}
