package com.example.samdapp.data.remote.dev

import com.example.samdapp.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Points local-backend requests at whatever address the dev backend is on today, so a changed
 * LAN IP is a broadcast (see [com.example.samdapp.receiver.DevServerReceiver]) rather than a
 * rebuild.
 *
 * It rewrites host and port, then calls `chain.proceed` exactly once. A failure propagates
 * untouched. There is no retry, no failover, no mid-flight host switch: this interceptor sits on
 * the ABHA client too, where a replayed `POST` means a second Aadhaar identity submission and a
 * second OTP to the patient's phone (AUDIT5 §3.2). OkHttp's own retry-and-follow-up behaviour is
 * unaffected; what is removed is this layer re-issuing a request the transport already gave up on.
 *
 * The scheme is left alone. Copying the active URL's scheme would silently downgrade an `https`
 * request to `http`.
 *
 * `src/dev/` only, and installed only by [com.example.samdapp.di.DevNetworkModule]. Staging and
 * prod bind an empty interceptor list, so neither this class nor [RealDevServerConfig] is
 * constructed there.
 */
@Singleton
class DevDynamicHostInterceptor @Inject constructor(
    private val devServerConfig: DevServerConfig,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isLocalBackendTarget(request.url.host)) {
            return chain.proceed(request)
        }

        val activeBaseUrl = devServerConfig.getActiveBaseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(request)

        val rewrittenUrl = request.url.newBuilder()
            .host(activeBaseUrl.host)
            .port(activeBaseUrl.port)
            .build()

        return chain.proceed(request.newBuilder().url(rewrittenUrl).build())
    }

    private fun isLocalBackendTarget(host: String): Boolean {
        if (isLocalDevHost(host)) return true
        if (host == BuildConfig.BACKEND_BASE_URL.toHttpUrlOrNull()?.host) return true
        return host == devServerConfig.getActiveBaseUrl().toHttpUrlOrNull()?.host
    }
}
