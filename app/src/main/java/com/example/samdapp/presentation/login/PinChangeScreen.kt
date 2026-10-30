@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.login

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.R

@Composable
fun PinChangeScreen(viewModel: PinChangeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PinChangeContent(
        uiState = uiState,
        actions = viewModel,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}

@Composable
internal fun PinChangeContent(uiState: PinChangeUiState, actions: PinChangeActions, onOpenSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Change your PIN") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Your administrator-issued PIN must be changed before you can continue.",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = uiState.currentPin,
                onValueChange = actions::onCurrentPinChange,
                label = { Text("Current PIN *") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.newPin,
                onValueChange = actions::onNewPinChange,
                label = { Text("New PIN * (6-12 digits)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.confirmPin,
                onValueChange = actions::onConfirmPinChange,
                label = { Text("Confirm new PIN *") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            val errorText = when (val error = uiState.error) {
                is PinChangeError.ServerDetail -> error.detail
                is PinChangeError.Worded -> stringResource(error.res)
                null -> null
            }
            errorText?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
            if (uiState.error.offersOpenSettings()) {
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().testTag("pin_change_open_settings"),
                ) { Text(stringResource(R.string.lnp_open_settings)) }
            }

            Button(
                onClick = actions::onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) {
                Text(if (uiState.isSubmitting) "Changing…" else "Change PIN", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
