package com.example.samdapp.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Q9, Q11, memo 11.7: with BODY logging on, credentials and auth or read-back bodies still never
 *  reach the log. */
class NetworkLoggingInterceptorTest {

    private lateinit var server: MockWebServer
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(NetworkLoggingInterceptor({ lines += it }, HttpLoggingInterceptor.Level.BODY))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun post(path: String, body: String, vararg headers: Pair<String, String>) {
        val request = Request.Builder()
            .url(server.url(path))
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().close()
    }

    private val log get() = lines.joinToString("\n")

    @Test
    fun `Authorization is redacted`() {
        server.enqueue(MockResponse().setBody("{}"))
        post("/api/v1/sync/push", "{}", "Authorization" to "Bearer eyJsecret-access")
        assertFalse(log, "eyJsecret-access" in log)
        assertTrue(log, "Authorization: ██" in log)
    }

    @Test
    fun `Cookie and Set-Cookie are redacted`() {
        server.enqueue(MockResponse().setBody("{}").setHeader("Set-Cookie", "sid=server-session-secret"))
        post("/api/v1/sync/push", "{}", "Cookie" to "sid=client-session-secret")
        assertFalse(log, "client-session-secret" in log)
        assertFalse(log, "server-session-secret" in log)
    }

    @Test
    fun `no body is logged on an auth path, either way`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"eyJissued-token"}"""))
        post("/api/v1/auth/login", """{"worker_id":"w","pin":"482915"}""")
        assertFalse(log, "482915" in log)
        assertFalse(log, "eyJissued-token" in log)
        assertTrue("headers are still logged", "--> POST" in log)
    }

    @Test
    fun `no body is logged on the refresh path, either way`() {
        server.enqueue(MockResponse().setBody("""{"access_token":"eyJnew-access-secret","refresh_token":"eyJnew-refresh-secret"}"""))
        post("/api/v1/auth/refresh", """{"refresh_token":"eyJold-refresh-secret","device_id":"dev-1"}""")
        assertFalse(log, "eyJold-refresh-secret" in log)
        assertFalse(log, "eyJnew-access-secret" in log)
        assertFalse(log, "eyJnew-refresh-secret" in log)
        assertTrue("the logger ran", "--> POST" in log)
    }

    @Test
    fun `no body is logged on the change-pin path, either way`() {
        server.enqueue(MockResponse().setBody("""{"pin_changed":true,"note":"eyJchange-response-secret"}"""))
        post("/api/v1/auth/change-pin", """{"current_pin":"246810","new_pin":"135791"}""")
        assertFalse(log, "246810" in log)
        assertFalse(log, "135791" in log)
        assertFalse(log, "eyJchange-response-secret" in log)
        assertTrue("the logger ran", "--> POST" in log)
    }

    @Test
    fun `no body is logged on the logout path, either way`() {
        server.enqueue(MockResponse().setBody("""{"revoked":true,"token":"eyJlogout-response-secret"}"""))
        post("/api/v1/auth/logout", """{"refresh_token":"eyJlogout-refresh-secret"}""")
        assertFalse(log, "eyJlogout-refresh-secret" in log)
        assertFalse(log, "eyJlogout-response-secret" in log)
        assertTrue("the logger ran", "--> POST" in log)
    }

    @Test
    fun `no body is logged on the SLM read-back, either way`() {
        server.enqueue(MockResponse().setBody("""{"text":"Take paracetamol for the fever"}"""))
        post("/api/v1/slm/readback", """{"question":"Patient Asha has a fever"}""")
        assertFalse(log, "Patient Asha" in log)
        assertFalse(log, "paracetamol" in log)
    }

    @Test
    fun `other bodies are still logged when BODY is on`() {
        server.enqueue(MockResponse().setBody("""{"accepted":1}"""))
        post("/api/v1/sync/push", """{"batch_id":"b-123"}""")
        assertTrue(log, "b-123" in log)
        assertTrue(log, "accepted" in log)
    }
}
