package com.example.samdapp.presentation.common

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

private const val LOW_BATTERY_PERCENT = 15
private const val LOW_STORAGE_BYTES = 200L * 1024 * 1024

/**
 * Non-blocking pre-flight nudge shown only before a worker starts a NEW patient/consultation —
 * never mid-consultation, which would be disruptive. Reads fresh every call, no caching (either
 * figure can change between checks). A missing/unreadable battery property reads as "not low"
 * rather than throwing, since this is a courtesy nudge, not a safety gate.
 */
fun deviceResourceWarnings(context: Context): List<String> = buildList {
    val batteryPercent = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    if (batteryPercent in 0 until LOW_BATTERY_PERCENT) {
        add("Battery below $LOW_BATTERY_PERCENT% — consider charging before starting a new patient.")
    }
    val availableBytes = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    if (availableBytes in 0 until LOW_STORAGE_BYTES) {
        add("Device storage is low — consider freeing up space before starting a new patient.")
    }
}

/** Shown when [warnings] is non-empty; [onContinueAnyway] always proceeds with the action the
 *  worker originally tapped — this dialog only ever delays it, never blocks it. */
@Composable
fun LowResourceWarningDialog(warnings: List<String>, onDismiss: () -> Unit, onContinueAnyway: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you start") },
        text = { Text(warnings.joinToString("\n\n")) },
        confirmButton = { TextButton(onClick = onContinueAnyway) { Text("Continue anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
