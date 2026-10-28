package com.example.samdapp.slm

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline proof for the dev on-device engine (on-device SLM memo 1: no egress on the local path).
 * It first asserts that `ConnectivityManager` reports **no active network**, and fails if one
 * exists, then runs the S2 smoke ([LocalSlmSmokeTest], probes (a) and (b)) unchanged, on the backend
 * given by `-e backend gpu|cpu`. Run it with airplane mode on and Wi-Fi and Bluetooth off.
 *
 * The state it saw goes to the status stream as values only (no text): `slm_offline_*`.
 */
@RunWith(AndroidJUnit4::class)
class LocalSlmOfflineSmokeTest {

    @Suppress("DEPRECATION")
    @Test
    fun withNoNetworkTheLocalEngineStartsAndAnswersTheTwoProbes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        instrumentation.sendStatus(0, Bundle().apply {
            putString("slm_offline_active_network", active?.toString() ?: "none")
            putString("slm_offline_known_networks", connectivity.allNetworks.size.toString())
            putString("slm_offline_airplane_mode", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1).toString())
        })

        assertNull("A network is active ($active): turn airplane mode on and Wi-Fi and Bluetooth off", active)

        LocalSlmSmokeTest().startsAndAnswersTheTwoProbes()
    }
}
