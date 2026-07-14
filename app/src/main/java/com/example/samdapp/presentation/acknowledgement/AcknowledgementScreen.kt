package com.example.samdapp.presentation.acknowledgement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AcknowledgementScreen(
    caseRecordId: String,
    onContinue: (caseRecordId: String) -> Unit,
    viewModel: AcknowledgementViewModel = hiltViewModel<AcknowledgementViewModel, AcknowledgementViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = uiState.errorMessage ?: "Case saved locally",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Button(
                    onClick = { onContinue(caseRecordId) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 32.dp),
                ) {
                    Text("Choose a doctor", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
