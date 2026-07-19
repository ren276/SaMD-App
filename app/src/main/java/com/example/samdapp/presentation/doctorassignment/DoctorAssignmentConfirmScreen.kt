@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.doctorassignment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.domain.model.Doctor

/**
 * Doctor-assignment confirmation (Part B) — every "Send to doctor" tap lands here, continuity
 * (follow-up) or fresh case alike, so the mock assignment is always visible and always
 * switchable, never a silent background action. "Switch" is scoped to the same specialty, not
 * the full roster — an informed choice, not the blind pick this replaced.
 */
@Composable
fun DoctorAssignmentConfirmScreen(
    caseRecordId: String,
    onDone: () -> Unit,
    viewModel: DoctorAssignmentConfirmViewModel = hiltViewModel<DoctorAssignmentConfirmViewModel, DoctorAssignmentConfirmViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DoctorAssignmentConfirmEffect.Done -> onDone()
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Send to doctor") }) }) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            when {
                uiState.isLoading -> CircularProgressIndicator()
                uiState.selectedDoctor == null -> {
                    Text(
                        text = uiState.errorMessage ?: "Could not resolve a doctor for this case",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Back to home")
                    }
                }
                uiState.queuedOffline -> {
                    Text(
                        text = "No network — case saved locally",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Dr. ${uiState.selectedDoctor!!.name} is assigned, but nothing has been sent yet. " +
                            "It will go out automatically the next time you tap Sync Up on Home once you're back online.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("Back to home")
                    }
                }
                else -> {
                    val doctor = uiState.selectedDoctor!!
                    Text(
                        text = if (uiState.isContinuity) {
                            "This visit is a follow-up. Continue with the same doctor who saw this patient last time?"
                        } else {
                            "This case will be sent to the least-busy available doctor (mock auto-assignment):"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = doctor.name, style = MaterialTheme.typography.titleMedium)
                            Text(text = doctor.specialty, style = MaterialTheme.typography.bodyMedium)
                            doctor.facilityName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }

                    if (uiState.showAlternatives) {
                        Text(
                            text = "Other ${doctor.specialty} doctors:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                        if (uiState.alternatives.isEmpty()) {
                            Text(
                                text = "No other ${doctor.specialty} doctors available.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                                items(uiState.alternatives, key = { it.id }) { alt -> AlternativeRow(alt, onClick = { viewModel.onSelectAlternative(alt) }) }
                            }
                        }
                    } else {
                        OutlinedButton(onClick = viewModel::onShowAlternatives, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Switch doctor")
                        }
                    }

                    uiState.errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }

                    Button(
                        onClick = viewModel::onConfirm,
                        enabled = !uiState.isConfirming,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 16.dp),
                    ) {
                        Text(if (uiState.isConfirming) "Sending…" else "Continue with ${doctor.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlternativeRow(doctor: Doctor, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = doctor.name, style = MaterialTheme.typography.titleSmall)
            doctor.facilityName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
