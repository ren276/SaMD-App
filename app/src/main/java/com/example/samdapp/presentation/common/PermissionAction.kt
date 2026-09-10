package com.example.samdapp.presentation.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.samdapp.domain.connectivity.ACCESS_LOCAL_NETWORK_PERMISSION

/** Dangerous permissions need a runtime prompt, not just the manifest entry — without this,
 *  [android.media.AudioRecord], [android.media.MediaRecorder], and camera capture all
 *  silently fail. Returns a callback that requests [permission] if needed, then calls [onGranted].
 *
 *  [onDenied] fires when the worker declines the prompt. It defaults to a no-op so the existing
 *  call sites are unchanged, but a call site whose control is user-reachable must pass something:
 *  without it a decline is a silent dead end, the button simply stops responding with no
 *  explanation and no way back (`scratchpad/pr4b-flag-flip-design-memo.md` D.3). The fix is here
 *  rather than at one call site because all four requests route through this helper. */
@Composable
fun rememberPermissionAction(
    permission: String,
    onGranted: () -> Unit,
    onDenied: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else onDenied()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(permission)
        }
    }
}

/** Entry point for PR4's Pi Start control: call this before the first LAN socket to the Pi
 *  relay, so the runtime prompt (Android 17+ / API 37+) happens ahead of the connection attempt
 *  rather than being discovered as a socket failure. TODO(PR4): call the returned action from
 *  the Start button, with onDenied routing to a "grant local network access" message. */
@Composable
fun rememberLocalNetworkPermissionAction(
    onGranted: () -> Unit,
    onDenied: () -> Unit = {},
): () -> Unit = rememberPermissionAction(
    permission = ACCESS_LOCAL_NETWORK_PERMISSION,
    onGranted = onGranted,
    onDenied = onDenied,
)
