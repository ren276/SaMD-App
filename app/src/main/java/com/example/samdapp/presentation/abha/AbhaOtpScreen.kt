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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.domain.model.formatAbhaId
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDigitsOnly

@Composable
fun AbhaOtpScreen(
    abhaId: String,
    onVerified: (abhaId: String) -> Unit,
    onCreateInstead: () -> Unit,
    viewModel: AbhaOtpViewModel = hiltViewModel<AbhaOtpViewModel, AbhaOtpViewModel.Factory>(
        creationCallback = { factory -> factory.create(abhaId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AbhaOtpEffect.Verified -> onVerified(effect.abhaId)
                }
            }
        }
    }
    AbhaOtpContent(uiState = uiState, actions = viewModel, onCreateInstead = onCreateInstead)
}

@Composable
internal fun AbhaOtpContent(uiState: AbhaOtpUiState, actions: AbhaOtpActions, onCreateInstead: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Verify OTP") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepProgressIndicator(current = 1, total = 4, label = "ABHA ID")
            Text(text = "ABHA ID: ${formatAbhaId(uiState.abhaId)}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = uiState.otp,
                onValueChange = { actions.onOtpChange(filterDigitsOnly(it, maxLength = 6)) },
                label = { Text("Mock OTP — any 6 digits accepted") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            uiState.errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
                Text(
                    text = "Not found? It may not have been created on this device yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = actions::onVerify,
                enabled = uiState.canVerify,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) { Text(if (uiState.isVerifying) "Verifying…" else "Verify", style = MaterialTheme.typography.titleMedium) }

            if (uiState.errorMessage != null) {
                Button(onClick = onCreateInstead, modifier = Modifier.fillMaxWidth()) {
                    Text("Create ABHA ID instead")
                }
            }
        }
    }
}
