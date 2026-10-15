package com.example.samdapp.domain.connectivity

import android.Manifest
import android.os.Build

/** The runtime permission LAN sockets need on Android 17+ (API 37+). Both this and
 *  [ACCESS_LOCAL_NETWORK_ENFORCED_SDK] are compile-time constants in the compileSdk 37 jar, so they
 *  inline and stay usable from JVM tests. */
const val ACCESS_LOCAL_NETWORK_PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

/** First API level that enforces [ACCESS_LOCAL_NETWORK_PERMISSION] for LAN sockets. Below this,
 *  LAN access is implicit via INTERNET and the Android permission is inert, though a vendor-level
 *  toggle outside the Android permission model (for example vivo/Funtouch) can still block it. */
const val ACCESS_LOCAL_NETWORK_ENFORCED_SDK = Build.VERSION_CODES.CINNAMON_BUN

enum class LocalNetworkFailure { PERMISSION_DENIED, UNREACHABLE, UNREACHABLE_OR_BLOCKED }

/** Classifies a failed LAN socket attempt (to the Pi relay or any other local-network peer).
 *
 *  [permissionGranted] must come from `ContextCompat.checkSelfPermission`. [cause] exists for
 *  logging only, never for branching: errno text (EPERM, ECONNABORTED) and exception messages are
 *  OEM- and OS-version-dependent and are not a contract a device's skin has to preserve.
 *
 *  Below [ACCESS_LOCAL_NETWORK_ENFORCED_SDK] the platform does not enforce this permission, so a
 *  denied reading from checkSelfPermission there is meaningless (it can read denied even though
 *  LAN access actually works) and must never produce [LocalNetworkFailure.PERMISSION_DENIED]. But
 *  a socket failure on that OS is still a real failure; some vendors (vivo/Funtouch on the current
 *  demo handset) gate LAN access with their own toggle outside the Android permission model, which
 *  checkSelfPermission cannot see either way. So a pre-enforcement failure classifies as
 *  [LocalNetworkFailure.UNREACHABLE_OR_BLOCKED]: genuine unreachability and a vendor block are
 *  both possible and this function does not pretend to tell them apart. */
fun classifyLocalNetworkFailure(
    permissionGranted: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
    cause: Throwable? = null,
): LocalNetworkFailure {
    val enforced = sdkInt >= ACCESS_LOCAL_NETWORK_ENFORCED_SDK
    return when {
        !enforced -> LocalNetworkFailure.UNREACHABLE_OR_BLOCKED
        !permissionGranted -> LocalNetworkFailure.PERMISSION_DENIED
        else -> LocalNetworkFailure.UNREACHABLE
    }
}
