package com.example.samdapp.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Visible at all times, above every screen's own top bar — a persistent clock plus the
 * online/offline status/toggle, so the compounder always knows which mode the app is in. */
@Composable
fun GlobalStatusBar(isOnline: Boolean, onToggleOnline: () -> Unit, modifier: Modifier = Modifier) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("hh:mm:ss a") }
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = now.format(dateFormatter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = now.format(timeFormatter),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = isOnline,
                        onCheckedChange = { onToggleOnline() },
                        colors = SwitchDefaults.colors(),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
