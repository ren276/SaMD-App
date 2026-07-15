@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.login

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.presentation.common.displayLabel

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LoginContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun LoginContent(uiState: LoginUiState, actions: LoginActions) {
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

            Button(
                onClick = actions::onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
            ) {
                Text(if (uiState.isSubmitting) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun RoleRow(selected: UserRole?, onSelect: (UserRole) -> Unit) {
    Column {
        Text(text = "Role *", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            UserRole.entries.forEach { role ->
                FilterChip(selected = selected == role, onClick = { onSelect(role) }, label = { Text(role.displayLabel()) })
            }
        }
    }
}
