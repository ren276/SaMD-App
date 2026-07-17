package com.example.samdapp.presentation.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
fun ConsentScreen(
    patientId: String,
    onContinue: () -> Unit,
    viewModel: ConsentViewModel = hiltViewModel<ConsentViewModel, ConsentViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ConsentEffect.Continue -> onContinue()
                }
            }
        }
    }
    ConsentContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun ConsentContent(uiState: ConsentUiState, actions: ConsentActions) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Before we begin", style = MaterialTheme.typography.headlineSmall)
            Text("शुरू करने से पहले", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 2.dp))

            Text(
                "The patient consents to offline data collection and understands this is not a " +
                    "live consultation with a doctor.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "मरीज़ ऑफ़लाइन डेटा संग्रह के लिए सहमति देता है और समझता है कि यह डॉक्टर के साथ " +
                    "लाइव परामर्श नहीं है।",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Checkbox(checked = uiState.agreed, onCheckedChange = actions::onAgreedChange)
                Text("I have read this to the patient and they agree", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = actions::onContinue,
                enabled = uiState.canContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 24.dp),
            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
