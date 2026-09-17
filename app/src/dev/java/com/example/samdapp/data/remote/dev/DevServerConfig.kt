package com.example.samdapp.data.remote.dev

import android.content.Context
import com.example.samdapp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holder and persister of the backend base URL the dev flavor is currently pointed at.
 *
 * There is deliberately no failover here. An earlier revision probed candidate hosts against
 * `/health` when a call failed and re-issued the call elsewhere; that replayed non-idempotent
 * requests (it is the confirmed cause of the ABHA OTP regression, AUDIT5 §3.2) and it diagnosed
 * the wrong thing besides: `/health` answers instantly on a backend whose ABDM leg is the slow
 * part. The URL now changes only when a developer changes it, via [DevServerReceiver].
 *
 * This whole package lives in `src/dev/` and does not exist in staging or prod builds — the same
 * containment `PiGatewayVitalsSource` uses (see `app/build.gradle.kts`'s dev flavor block).
 */
interface DevServerConfig {
    fun getActiveBaseUrl(): String
    fun setBaseUrl(newUrl: String)
}

/**
 * True for the hosts a dev build's local backend can legitimately live on: the adb reverse
 * tunnel, the emulator's host loopback, and RFC 1918 private space (10/8, 172.16/12, 192.168/16).
 *
 * Shared by [DevDynamicHostInterceptor], to decide what may be rewritten, and by
 * [com.example.samdapp.receiver.DevServerReceiver], to decide what may be accepted as a new URL.
 */
internal fun isLocalDevHost(host: String): Boolean = when {
    host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2" -> true
    host.startsWith("10.") || host.startsWith("192.168.") -> true
    // 172.16.0.0/12 only. A bare startsWith("172.") would also match public space such as
    // Google's 172.217.0.0/16.
    host.startsWith("172.") -> (host.split('.').getOrNull(1)?.toIntOrNull() ?: -1) in 16..31
    else -> false
}

@Singleton
class RealDevServerConfig @Inject constructor(
    @ApplicationContext private val context: Context,
) : DevServerConfig {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var activeBaseUrl: String = loadInitialUrl()

    /** Last URL a developer set, else the LAN address baked in at build time by resolveDevHostIp(). */
    private fun loadInitialUrl(): String {
        val saved = prefs.getString(KEY_BASE_URL, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.BACKEND_BASE_URL
    }

    override fun getActiveBaseUrl(): String = activeBaseUrl

    override fun setBaseUrl(newUrl: String) {
        val normalized = if (newUrl.endsWith("/")) newUrl else "$newUrl/"
        activeBaseUrl = normalized
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        logger.info("Active dev backend URL set to: $normalized")
    }

    companion object {
        private const val PREFS_NAME = "samd_dev_server_config"
        private const val KEY_BASE_URL = "dev_backend_base_url"
        private val logger = Logger.getLogger("DevServerConfig")
    }
}
