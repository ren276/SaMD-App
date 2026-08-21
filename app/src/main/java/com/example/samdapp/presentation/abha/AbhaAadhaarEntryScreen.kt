@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.abha

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDigitsOnly

/**
 * Aadhaar step of the real ABHA create flow, a distinct screen before any of the mock sign-up
 * surface. Owns the Aadhaar field, the consent checkbox that gates it, and the notice explaining
 * where the number goes.
 *
 * This route is in `Routes.kt`'s `SECURED_ROUTE_TYPES`, so `FLAG_SECURE` is applied while it is
 * shown: an Aadhaar number on screen is exactly the class of content that block list exists for.
 */
@Composable
fun AbhaAadhaarEntryScreen(
    onOtpRequested: (sessionId: String, maskedMobile: String?) -> Unit,
    viewModel: AbhaAadhaarEntryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AbhaAadhaarEntryEffect.OtpRequested -> onOtpRequested(effect.sessionId, effect.maskedMobile)
                }
            }
        }
    }
    AbhaAadhaarEntryContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun AbhaAadhaarEntryContent(uiState: AbhaAadhaarEntryUiState, actions: AbhaAadhaarEntryActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Create ABHA ID") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepProgressIndicator(current = 1, total = 4, label = "ABHA ID")

            Text(
                text = "Enter the patient's Aadhaar number. ABDM will send a one-time code to the " +
                    "mobile number linked to that Aadhaar.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = uiState.aadhaarNumber,
                onValueChange = { actions.onAadhaarNumberChange(filterDigitsOnly(it, maxLength = AADHAAR_LENGTH)) },
                label = { Text("Aadhaar number (12 digits) *") },
                singleLine = true,
                enabled = !uiState.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "This Aadhaar number is sent to ABDM through the SaMD server to create the " +
                    "ABHA account. It is not saved on this device or in the patient's record. " +
                    "Only the ABHA number ABDM returns is kept.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Checkbox(
                    checked = uiState.consentGiven,
                    onCheckedChange = actions::onConsentChange,
                    enabled = !uiState.isSubmitting,
                )
                Text(
                    text = "I have read this to the patient and they consent to creating an ABHA " +
                        "using their Aadhaar",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            uiState.errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
                if (uiState.errorRetryable) {
                    Text(
                        text = "Nothing was sent. You can try again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = actions::onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) {
                Text(
                    text = if (uiState.isSubmitting) "Sending…" else "Send OTP",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
