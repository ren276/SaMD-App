package com.example.samdapp.domain.connectivity

import android.os.Build

/** android.permission.ACCESS_LOCAL_NETWORK, the runtime permission LAN sockets need on
 *  Android 17+ (API 37+). Kept as a string constant, not [android.Manifest.permission], because
 *  it may not exist in every compileSdk this module has built against historically. */
const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** First API level that enforces [ACCESS_LOCAL_NETWORK_PERMISSION] for LAN sockets. Below this,
 *  LAN access is implicit via INTERNET and the permission is inert. */
const val ACCESS_LOCAL_NETWORK_ENFORCED_SDK = 37

enum class LocalNetworkFailure { PERMISSION_DENIED, UNREACHABLE }

/** Classifies a failed LAN socket attempt (to the Pi relay or any other local-network peer).
 *
 *  [permissionGranted] must come from `ContextCompat.checkSelfPermission` and is the PRIMARY and
 *  only branch condition alongside [sdkInt]. [cause] exists for logging only, never for
 *  branching: errno text (EPERM, ECONNABORTED) and exception messages are OEM- and
 *  OS-version-dependent and are not a contract a device's skin (e.g. Funtouch) has to preserve.
 *
 *  Below [ACCESS_LOCAL_NETWORK_ENFORCED_SDK], the platform does not enforce this permission, and
 *  `checkSelfPermission` for a permission it does not recognize can read as denied even though
 *  LAN access actually works there. So on those OS versions a failure is always classified
 *  UNREACHABLE, regardless of [permissionGranted] — a real permission denial cannot be the cause
 *  of a failure the permission system isn't enforcing yet. */
fun classifyLocalNetworkFailure(
    permissionGranted: Boolean,
    sdkInt: Int = Build.VERSION.SDK_INT,
    cause: Throwable? = null,
): LocalNetworkFailure {
    val enforced = sdkInt >= ACCESS_LOCAL_NETWORK_ENFORCED_SDK
    return if (enforced && !permissionGranted) {
        LocalNetworkFailure.PERMISSION_DENIED
    } else {
        LocalNetworkFailure.UNREACHABLE
    }
}
