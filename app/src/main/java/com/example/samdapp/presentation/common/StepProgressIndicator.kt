package com.example.samdapp.presentation.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Step X of Y — Label" shown consistently below the top app bar across the multi-screen
 * ABHA -> Registration -> Medical background -> Ailments & Vitals worker flow, so a PHC worker
 * always knows how much of the visit's paperwork remains. Reused as-is on every screen in that
 * sequence — do not fork a per-screen copy.
 */
@Composable
fun StepProgressIndicator(current: Int, total: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = "Step $current of $total — $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { current / total.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
