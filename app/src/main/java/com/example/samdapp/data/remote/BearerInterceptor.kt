package com.example.samdapp.data.remote

import com.example.samdapp.data.local.auth.AuthTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches `Authorization: Bearer <access_token>` to every backend request except login and
 * refresh (api-contract.md §0.7): those two carry no token by definition, and attaching a
 * possibly-expired access token to the refresh call specifically is the classic self-inflicted
 * 401 loop this must avoid.
 *
 * `runBlocking` here is a deliberate, bounded exception to "no blocking calls on the network
 * thread": `Interceptor.intercept` is a synchronous OkHttp contract (no suspend variant exists),
 * and it runs on OkHttp's own dispatcher thread pool, not a caller-owned coroutine dispatcher, so
 * there is no risk of starving a fixed pool the rest of the app depends on. The DataStore read it
 * awaits is a local, already-warm, single-file read.
 */
class BearerInterceptor @Inject constructor(
    private val tokenStore: AuthTokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (path == LOGIN_PATH || path == REFRESH_PATH) {
            return chain.proceed(request)
        }

        val accessToken = runBlocking { tokenStore.snapshot().accessToken }
        val authenticatedRequest = if (accessToken != null) {
            request.newBuilder().header("Authorization", "Bearer $accessToken").build()
        } else {
            request
        }
        return chain.proceed(authenticatedRequest)
    }

    companion object {
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
    }
}
