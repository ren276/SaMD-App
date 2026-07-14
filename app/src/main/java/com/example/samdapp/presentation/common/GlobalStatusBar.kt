package com.example.samdapp.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    val formatter = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy  HH:mm:ss") }
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = now.format(formatter), style = MaterialTheme.typography.labelLarge)
            FilterChip(
                selected = isOnline,
                onClick = onToggleOnline,
                label = {
                    Text(if (isOnline) "Online" else "Offline", style = MaterialTheme.typography.labelLarge)
                },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}
