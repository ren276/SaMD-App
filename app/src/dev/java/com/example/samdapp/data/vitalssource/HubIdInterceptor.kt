package com.example.samdapp.data.vitalssource

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** The gateway answered, but not as the hub this instrument is assigned to: the `X-SaMDPi-Hub-Id`
 *  header was missing or named another hub. An [IOException] so that it travels through OkHttp and
 *  Retrofit's asynchronous call path as an ordinary failed call, and cannot escape as an unchecked
 *  exception on the dispatcher thread. */
class HubIdMismatchException(val expected: String, val actual: String?) :
    IOException("X-SaMDPi-Hub-Id was ${actual?.let { "'$it'" } ?: "missing"}, expected '$expected'")

/**
 * Checks `X-SaMDPi-Hub-Id` on EVERY response, error responses included, before any body is read.
 * The hub sets that header on every answer (SaMDPi `server.py`), so a response without it, or with
 * another hub's id, is not from the hub the phone meant to talk to: a stale desk container, a proxy,
 * or the wrong box on the LAN. Nothing in such a response is trusted, which is why this runs as an
 * application interceptor on the client and not as a check the caller has to remember to make.
 *
 * An empty [expectedHubId] (no Wi-Fi hub assigned) matches nothing, so the client fails closed.
 */
class HubIdInterceptor(private val expectedHubId: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val actual = response.header(HEADER)
        if (expectedHubId.isEmpty() || actual != expectedHubId) {
            response.close()
            throw HubIdMismatchException(expectedHubId, actual)
        }
        return response
    }

    companion object {
        const val HEADER = "X-SaMDPi-Hub-Id"
    }
}
