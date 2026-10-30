package com.example.samdapp.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/**
 * The backend client's network log (Q9, Q11, memo 11.7). Whatever [level] the local.properties
 * flag asks for:
 * - `Authorization`, `Cookie` and `Set-Cookie` are always redacted;
 * - no body is logged for any `/api/v1/auth/` call (PINs, tokens) or for the SLM read-back (its
 *   prompt and answer carry patient data, audit finding A-02); those log headers only.
 *
 * Two loggers rather than one whose `level` is switched per request: the client is shared across
 * dispatcher threads, and a mutable level would race.
 */
class NetworkLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger,
    level: HttpLoggingInterceptor.Level,
) : Interceptor {

    private val full = redacted(HttpLoggingInterceptor(logger), level)
    private val withoutBodies = redacted(HttpLoggingInterceptor(logger), minOf(level, HttpLoggingInterceptor.Level.HEADERS))

    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        val logger = if (AUTH_PATH_PREFIX in path || path.endsWith(SLM_READBACK_PATH)) withoutBodies else full
        return logger.intercept(chain)
    }

    private fun redacted(interceptor: HttpLoggingInterceptor, level: HttpLoggingInterceptor.Level) =
        interceptor.apply {
            this.level = level
            REDACTED_HEADERS.forEach(::redactHeader)
        }

    companion object {
        val REDACTED_HEADERS = listOf("Authorization", "Cookie", "Set-Cookie")
        const val AUTH_PATH_PREFIX = "/api/v1/auth/"
        const val SLM_READBACK_PATH = "/api/v1/slm/readback"
    }
}
