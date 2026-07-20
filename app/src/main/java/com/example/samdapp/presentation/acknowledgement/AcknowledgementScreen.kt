package com.example.samdapp.presentation.acknowledgement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun AcknowledgementScreen(
    caseRecordId: String,
    onSendToDoctor: (caseRecordId: String) -> Unit,
    onViewReport: (caseRecordId: String) -> Unit,
    viewModel: AcknowledgementViewModel = hiltViewModel<AcknowledgementViewModel, AcknowledgementViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AcknowledgementEffect.SendToDoctor -> onSendToDoctor(effect.caseRecordId)
                }
            }
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isSaving) {
                SamdLoadingIndicator()
            } else {
                Text(
                    text = uiState.errorMessage ?: "Case saved locally",
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (uiState.errorMessage == null) {
                    // REQ-TRS-03: patient-facing, the worker can read this aloud. hoursUntilReview
                    // comes from SyncWindowProvider — never hardcode this number in the composable.
                    Text(
                        text = "Your file is secured. A doctor will review this within ${uiState.hoursUntilReview} hours.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = "आपकी फ़ाइल सुरक्षित है। एक डॉक्टर ${uiState.hoursUntilReview} घंटों के भीतर इसकी समीक्षा करेंगे।",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (uiState.errorMessage == null) {
                    OutlinedButton(
                        onClick = { onViewReport(caseRecordId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 24.dp),
                    ) {
                        Text("View preliminary report", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Button(
                    onClick = viewModel::onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 12.dp),
                ) {
                    Text("Send to doctor", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

