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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDigitsOnly

@Composable
fun AbhaLoginScreen(
    onContinue: (abhaId: String) -> Unit,
    viewModel: AbhaLoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AbhaLoginContent(uiState = uiState, actions = viewModel, onContinue = { onContinue(uiState.abhaId) })
}

@Composable
internal fun AbhaLoginContent(uiState: AbhaLoginUiState, actions: AbhaLoginActions, onContinue: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Enter ABHA ID") }) }) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepProgressIndicator(current = 1, total = 4, label = "ABHA ID")
            Text(text = "Enter the patient's 14-digit ABHA number.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = uiState.abhaId,
                onValueChange = { actions.onAbhaIdChange(filterDigitsOnly(it, maxLength = 14)) },
                label = { Text("ABHA number *") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onContinue,
                enabled = uiState.canContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
