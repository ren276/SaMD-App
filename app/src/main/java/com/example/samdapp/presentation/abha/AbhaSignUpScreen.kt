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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.example.samdapp.presentation.common.filterDigitsOnly

@Composable
fun AbhaSignUpScreen(
    onCreated: (abhaId: String) -> Unit,
    viewModel: AbhaSignUpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AbhaSignUpEffect.Created -> onCreated(effect.abhaId)
                }
            }
        }
    }
    AbhaSignUpContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun AbhaSignUpContent(uiState: AbhaSignUpUiState, actions: AbhaSignUpActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Create ABHA ID") }) }) { padding: PaddingValues ->
        when (uiState.stage) {
            AbhaSignUpStage.REDIRECTING -> RedirectingContent(padding)
            AbhaSignUpStage.FORM -> SignUpFormContent(uiState, actions, padding)
        }
    }
}

@Composable
private fun RedirectingContent(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
        Text(text = "Redirecting to ABHA Portal (simulated)…", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "This is a mock flow — no real ABDM gateway is contacted.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SignUpFormContent(uiState: AbhaSignUpUiState, actions: AbhaSignUpActions, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Minimal Aadhaar-OTP KYC fields (mock — no real Aadhaar verification).",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = uiState.name,
            onValueChange = actions::onNameChange,
            label = { Text("Full name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uiState.dateOfBirth,
            onValueChange = actions::onDateOfBirthChange,
            label = { Text("Date of birth (YYYY-MM-DD) *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        GenderRow(selected = uiState.gender, onSelect = actions::onGenderChange)
        OutlinedTextField(
            value = uiState.mobileNumber,
            onValueChange = { actions.onMobileNumberChange(filterDigitsOnly(it, maxLength = 10)) },
            label = { Text("Mobile number *") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        uiState.errorMessage?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = actions::onSubmit,
            enabled = uiState.canSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
        ) { Text("Create ABHA ID", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun GenderRow(selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(text = "Gender *", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            listOf("Female", "Male", "Other").forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}
