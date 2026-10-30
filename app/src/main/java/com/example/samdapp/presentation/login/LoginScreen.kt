@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.login

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.R
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.connectivity.ACCESS_LOCAL_NETWORK_PERMISSION
import com.example.samdapp.presentation.common.BiometricResult
import com.example.samdapp.presentation.common.displayLabel
import com.example.samdapp.presentation.common.rememberLocalNetworkPermissionAction
import com.example.samdapp.presentation.common.rememberBiometricAuthenticator

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val requestBiometric = rememberBiometricAuthenticator { result ->
        when (result) {
            is BiometricResult.Success -> viewModel.onBiometricSucceeded()
            is BiometricResult.Failed -> viewModel.onBiometricFailed(result.message)
            is BiometricResult.Unavailable ->
                viewModel.onBiometricFailed("no fingerprint/face/screen lock set up on this device")
        }
    }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is LoginEffect.RequestBiometricAuth -> requestBiometric(effect.subtitle)
                }
            }
        }
    }

    // Memo 5.4, P2-R2: the rationale flag is read right after a denial. No Activity means it
    // cannot be read, and the safe reading is "may ask again", which keeps the rationale.
    val activity = LocalActivity.current
    val requestLocalNetwork = rememberLocalNetworkPermissionAction(
        onGranted = { viewModel.onLocalNetworkPermissionResult(granted = true, showRationale = true) },
        onDenied = {
            val showRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, ACCESS_LOCAL_NETWORK_PERMISSION)
            } ?: true
            viewModel.onLocalNetworkPermissionResult(granted = false, showRationale = showRationale)
        },
    )
    LaunchedEffect(uiState.localNetworkStep) {
        if (uiState.localNetworkStep == LocalNetworkStep.REQUEST) {
            viewModel.onLocalNetworkRequestLaunched()
            requestLocalNetwork()
        }
    }

    val context = LocalContext.current
    LoginContent(
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
internal fun LoginContent(uiState: LoginUiState, actions: LoginActions, onOpenSettings: () -> Unit) {
    if (uiState.localNetworkStep == LocalNetworkStep.RATIONALE) {
        AlertDialog(
            onDismissRequest = actions::onRationaleNotNow,
            title = { Text(stringResource(R.string.lnp_rationale_title)) },
            text = { Text(stringResource(R.string.lnp_rationale_body)) },
            confirmButton = { TextButton(onClick = actions::onRationaleContinue) { Text(stringResource(R.string.lnp_rationale_continue)) } },
            dismissButton = { TextButton(onClick = actions::onRationaleNotNow) { Text(stringResource(R.string.lnp_rationale_not_now)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Sign in") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Who's using the app right now?",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = uiState.name,
                onValueChange = actions::onNameChange,
                label = { Text("Your name *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RoleRow(selected = uiState.role, onSelect = actions::onRoleSelect)

            OutlinedTextField(
                value = uiState.pin,
                onValueChange = actions::onPinChange,
                label = { Text("PIN *") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            val errorText = when (val error = uiState.error) {
                is LoginError.ServerDetail -> error.detail
                is LoginError.Worded -> stringResource(error.res)
                is LoginError.Refusal -> when (val refusal = error.refusal) {
                    is SignInRefusal.FacilityBlocked -> stringResource(R.string.sign_in_facility_blocked, refusal.ownerFacilityName)
                    SignInRefusal.NoFacility -> stringResource(R.string.sign_in_no_facility)
                    SignInRefusal.Unavailable -> stringResource(R.string.sign_in_failed_generic)
                }
                is LoginError.Biometric -> error.message
                null -> null
            }
            errorText?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
            if (uiState.error.offersOpenSettings()) {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lnp_open_settings))
                }
            }

            Button(
                onClick = actions::onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) {
                Text(if (uiState.isSubmitting) "Verifying…" else "Sign in", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun RoleRow(selected: UserRole?, onSelect: (UserRole) -> Unit) {
    Column {
        Text(text = "Role *", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            UserRole.entries.forEach { role ->
                FilterChip(selected = selected == role, onClick = { onSelect(role) }, label = { Text(role.displayLabel()) })
            }
        }
    }
}
