package com.example.samdapp.presentation.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Dangerous permissions need a runtime prompt, not just the manifest entry — without this,
 *  [android.speech.SpeechRecognizer], [android.media.MediaRecorder], and camera capture all
 *  silently fail. Returns a callback that requests [permission] if needed, then calls [onGranted]. */
@Composable
fun rememberPermissionAction(permission: String, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(permission)
        }
    }
}
