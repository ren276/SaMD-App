@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.transcription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TranscriptionScreen(
    consultationId: String,
    audioUri: String,
    onContinue: () -> Unit,
    viewModel: TranscriptionViewModel = hiltViewModel<TranscriptionViewModel, TranscriptionViewModel.Factory>(
        creationCallback = { factory -> factory.create(consultationId, audioUri) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Transcription") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (uiState.isLoading) {
                SamdLoadingIndicator()
            } else {
                Text(
                    text = uiState.transcription ?: uiState.errorMessage ?: "No transcription available",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

