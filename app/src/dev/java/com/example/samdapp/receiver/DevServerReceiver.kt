package com.example.samdapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.samdapp.data.remote.dev.DevServerConfig
import com.example.samdapp.data.remote.dev.isLocalDevHost
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Dev-only broadcast receiver letting a developer or script repoint the app's backend URL over
 * adb without a rebuild:
 *
 * `adb shell am broadcast -a com.example.samdapp.dev.SET_BACKEND_URL --es url http://10.203.2.52:8080/`
 *
 * The broadcast is guarded two ways. The manifest requires the signature-level permission
 * `com.example.samdapp.dev.permission.SET_BACKEND_URL`, so another installed app cannot send it.
 * And the URL is validated here rather than trusted: `toHttpUrlOrNull` rejects anything
 * unparseable or not http/https (HttpUrl parses no other scheme), and [isLocalDevHost] rejects
 * any host outside the loopback/emulator/RFC 1918 space a local dev backend can live on. Without
 * both, a single broadcast repoints Aadhaar identity submission and OTP verification at an
 * attacker's host.
 */
@AndroidEntryPoint
class DevServerReceiver : BroadcastReceiver() {

    @Inject
    lateinit var devServerConfig: DevServerConfig

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_BACKEND_URL) return

        val raw = intent.getStringExtra(EXTRA_URL)?.trim()
        val url = raw?.toHttpUrlOrNull()
        if (url == null || !isLocalDevHost(url.host)) {
            logger.warning("Rejected backend URL broadcast: not a parseable local http(s) URL")
            return
        }

        val formatted = url.toString().let { if (it.endsWith("/")) it else "$it/" }
        logger.info("DevServerReceiver received updated backend URL: $formatted")
        devServerConfig.setBaseUrl(formatted)
    }

    companion object {
        const val ACTION_SET_BACKEND_URL = "com.example.samdapp.dev.SET_BACKEND_URL"
        const val EXTRA_URL = "url"
        private val logger = Logger.getLogger("DevServerReceiver")
    }
}
