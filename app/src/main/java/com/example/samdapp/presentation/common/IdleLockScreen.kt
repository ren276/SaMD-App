package com.example.samdapp.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Idle auto-lock screen (item 2, privacy hardening) — drawn over the still-mounted app content
 * rather than replacing it in the back stack, so in-progress screen/ViewModel state (e.g. an
 * open Compounder visit) survives the lock/unlock cycle untouched. The full-surface `clickable`
 * with no visual indication exists purely to consume touches so they can't pass through to the
 * content underneath; it has no other effect.
 */
@Composable
fun IdleLockScreen(workerName: String, onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val requestBiometric = rememberBiometricAuthenticator { result ->
        when (result) {
            is BiometricResult.Success -> onUnlocked()
            is BiometricResult.Failed -> errorMessage = result.message
            is BiometricResult.Unavailable -> errorMessage = "No fingerprint/face/screen lock set up on this device"
        }
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text("Session locked", style = MaterialTheme.typography.headlineSmall)
            Text("Verify it's still you, $workerName", style = MaterialTheme.typography.bodyMedium)
            errorMessage?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { requestBiometric("Unlock to continue") }) { Text("Unlock") }
        }
    }
}
