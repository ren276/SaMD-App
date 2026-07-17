package com.example.samdapp.presentation.emergency

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen, high-contrast, terminal state (REQ-TRS-02) — reached only from
 * [com.example.samdapp.presentation.compounder.CompounderViewModel] when
 * [com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase] trips. There is
 * deliberately no "continue to consultation" path from here: store-and-forward telemedicine is
 * disallowed for acute emergencies, so the only next step the app offers is returning to Home —
 * the actual next step is a real-world physical referral, which this app does not mediate.
 */
@Composable
fun EmergencyOverrideScreen(reasons: List<String>, onAcknowledged: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFB00020))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "EMERGENCY PROTOCOL",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            "आपातकालीन प्रोटोकॉल",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Do not wait for sync. Refer to the nearest physical hospital immediately.",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            "सिंक होने का इंतज़ार न करें। तुरंत नज़दीकी अस्पताल भेजें।",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        reasons.forEach { reason ->
            Text(
                "• $reason",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Button(
            onClick = onAcknowledged,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB00020)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 40.dp),
        ) { Text("Acknowledged — return to Home", style = MaterialTheme.typography.titleMedium) }
    }
}
