@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.abha

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Entry point before registration (REQ-ABH-01/02): the ABHA step precedes and autofills
 * registration, it doesn't replace it. "Skip" stays available for a patient with no ABHA id —
 * mirrors the existing "Skip for now" pattern already used on Medical background.
 */
@Composable
fun AbhaEntryScreen(
    onCreateAbha: () -> Unit,
    onLoginWithAbha: () -> Unit,
    onSkip: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Patient ABHA ID") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Does the patient have an Ayushman Bharat Health Account (ABHA) ID?",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Linking ABHA autofills the registration form and is the standard entry point " +
                    "for a real PHC visit. This is a simulated (mock) ABHA flow for the demo.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = onLoginWithAbha,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("I have an ABHA ID") }

            OutlinedButton(
                onClick = onCreateAbha,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Create ABHA ID") }

            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip — register without ABHA")
            }
        }
    }
}
