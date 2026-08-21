@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.abha

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
import com.example.samdapp.presentation.common.OtpInputField
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDigitsOnly

/**
 * OTP step of the real ABHA create flow. Distinct from [AbhaOtpScreen], which verifies a mock
 * login OTP against a locally stored profile.
 *
 * Two rounds live on this one screen, driven by [AbhaCreateOtpUiState.round]: the Aadhaar OTP that
 * enrols the account, and the conditional communication-mobile OTP that ABDM sometimes demands
 * afterwards. Neither the session id nor either OTP is ever rendered outside its own input field.
 */
@Composable
fun AbhaCreateOtpScreen(
    sessionId: String,
    maskedMobile: String?,
    onEnrolled: (abhaId: String) -> Unit,
    viewModel: AbhaCreateOtpViewModel = hiltViewModel<AbhaCreateOtpViewModel, AbhaCreateOtpViewModel.Factory>(
        creationCallback = { factory -> factory.create(sessionId, maskedMobile) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AbhaCreateOtpEffect.Enrolled -> onEnrolled(effect.abhaId)
                }
            }
        }
    }
    AbhaCreateOtpContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun AbhaCreateOtpContent(uiState: AbhaCreateOtpUiState, actions: AbhaCreateOtpActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Create ABHA ID") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepProgressIndicator(current = 1, total = 4, label = "ABHA ID")

            when (uiState.round) {
                AbhaOtpRound.AADHAAR -> AadhaarRound(uiState, actions)
                AbhaOtpRound.COMMUNICATION_MOBILE -> CommunicationMobileRound(uiState, actions)
            }

            uiState.errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
                if (uiState.errorRetryable) {
                    Text(text = "You can try again.", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = actions::onVerify,
                enabled = uiState.canVerify,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) {
                Text(
                    text = if (uiState.isVerifying) "Verifying…" else "Verify",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Round one. The communication mobile is collected here, immediately above the OTP that it is
 *  sent alongside, because that is the only call that consumes it. */
@Composable
private fun AadhaarRound(uiState: AbhaCreateOtpUiState, actions: AbhaCreateOtpActions) {
    Text(
        text = uiState.maskedMobile?.let { "ABDM sent a code to the Aadhaar-linked mobile ending $it." }
            ?: "ABDM sent a code to the mobile number linked to this Aadhaar.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = uiState.mobileNumber,
        onValueChange = { actions.onMobileNumberChange(filterDigitsOnly(it, maxLength = MOBILE_LENGTH)) },
        label = { Text("ABHA communication mobile (10 digits) *") },
        supportingText = { Text("The number the patient wants on their ABHA account. Not the OTP.") },
        singleLine = true,
        enabled = !uiState.isVerifying,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )

    OtpInputField(
        value = uiState.otp,
        onValueChange = actions::onOtpChange,
        label = "Aadhaar OTP (6 digits) *",
        enabled = !uiState.isVerifying,
    )
}

/** Round two, reached only when the backend reported `MOBILE_VERIFICATION_REQUIRED`. The mobile
 *  field is gone: the backend already holds the number submitted in round one. */
@Composable
private fun CommunicationMobileRound(uiState: AbhaCreateOtpUiState, actions: AbhaCreateOtpActions) {
    Text(text = "One more step", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "ABDM sent a second code to the ABHA communication mobile you entered. Enter it to " +
            "finish creating the account.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OtpInputField(
        value = uiState.otp,
        onValueChange = actions::onOtpChange,
        label = "Mobile OTP (6 digits) *",
        enabled = !uiState.isVerifying,
    )
}
