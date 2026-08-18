package com.example.samdapp.data.remote

import com.example.samdapp.testutil.FakeAuthTokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** api-contract.md §0.7/§2.3: a possibly-expired access token must never be attached to the
 *  login or refresh calls themselves: attaching it is the classic self-inflicted 401 loop. */
class BearerInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(FakeAuthTokenStore(accessToken = "the-access-token")))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(path: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
    }

    @Test
    fun `does not attach a token to the login call`() {
        call(BearerInterceptor.LOGIN_PATH)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `does not attach a token to the refresh call`() {
        call(BearerInterceptor.REFRESH_PATH)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `attaches the bearer token to every other call`() {
        call("/api/v1/kernel/assess")
        assertEquals("Bearer the-access-token", server.takeRequest().getHeader("Authorization"))
    }
}
