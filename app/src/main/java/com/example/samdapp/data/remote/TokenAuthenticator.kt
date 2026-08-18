package com.example.samdapp.data.remote

import com.example.samdapp.data.local.auth.AuthTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider

/**
 * Handles a 401 by refreshing once and retrying, with rotation (api-contract.md §2.3). Three hard
 * rules, all load-bearing:
 *
 * 1. Gives up after one refresh attempt per original request ([responseCount]): an authenticator
 *    that retries unconditionally loops forever against a rejected refresh token.
 * 2. Never retries a failing `/auth/refresh` call itself: a revoked chain never becomes valid by
 *    retrying, and without this check a failed refresh would recursively re-invoke this
 *    authenticator (this client's own authenticator) for the refresh call's own 401.
 * 3. Single-flight: [mutex] serializes refresh attempts, and the token comparison inside the lock
 *    means a second caller that was merely waiting for the first refresh to land retries with the
 *    already-rotated token instead of firing a second refresh, which is exactly the shape that
 *    would trip the backend's own reuse detection (SAMD-AUTH-1004) against this app.
 *
 * [authService] is `Provider<RetrofitAuthService>`, not a direct dependency, to break the DI
 * cycle: the authenticated `OkHttpClient` needs this authenticator, which needs
 * `RetrofitAuthService`, which needs a `Retrofit` built on that same `OkHttpClient`.
 */
class TokenAuthenticator @Inject constructor(
    private val tokenStore: AuthTokenStore,
    private val authService: Provider<RetrofitAuthService>,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val path = response.request.url.encodedPath
        if (path == BearerInterceptor.LOGIN_PATH || path == BearerInterceptor.REFRESH_PATH) return null
        if (responseCount(response) >= 2) return null

        val failedAccessToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        // See BearerInterceptor's runBlocking note: same justification applies here.
        // Authenticator.authenticate is a synchronous OkHttp contract running on OkHttp's
        // dispatcher thread pool.
        return runBlocking {
            mutex.withLock {
                val current = tokenStore.snapshot()

                if (current.accessToken != null && current.accessToken != failedAccessToken) {
                    // Another caller already refreshed while this one waited for the lock.
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer ${current.accessToken}")
                        .build()
                }

                val refreshToken = current.refreshToken
                if (refreshToken == null) {
                    tokenStore.clear()
                    return@withLock null
                }

                when (val result = authService.get().refresh(refreshToken, current.deviceId)) {
                    is AuthApiResult.Success -> {
                        val tokens = result.data
                        tokenStore.saveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn)
                        response.request.newBuilder()
                            .header("Authorization", "Bearer ${tokens.accessToken}")
                            .build()
                    }
                    is AuthApiResult.Failure -> {
                        // SAMD-AUTH-1004 (reuse detected, chain revoked) or any other refresh
                        // failure. Clearing here is what routes the app to sign-in: AuthViewModel
                        // observes AuthTokenStore.session going null and AppNavHost's existing
                        // sign-in gate reacts, so no separate navigation signal is needed.
                        tokenStore.clear()
                        null
                    }
                }
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
