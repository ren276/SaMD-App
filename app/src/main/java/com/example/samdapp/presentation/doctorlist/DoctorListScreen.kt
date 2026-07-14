@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.doctorlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.example.samdapp.domain.model.Doctor

@Composable
fun DoctorListScreen(
    caseRecordId: String,
    onDone: () -> Unit,
    viewModel: DoctorListViewModel = hiltViewModel<DoctorListViewModel, DoctorListViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is DoctorListEffect.Done -> onDone()
                }
            }
        }
    }
    DoctorListContent(uiState = uiState, actions = viewModel)
}

@Composable
private fun DoctorListContent(uiState: DoctorListUiState, actions: DoctorListActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Choose a doctor") }) }) { padding: PaddingValues ->
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        val sentToDoctor = uiState.sentToDoctor
        if (sentToDoctor != null) {
            SuccessContent(doctor = sentToDoctor, onDone = actions::onDone, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(16.dp).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.doctors, key = { it.id }) { doctor ->
                    DoctorRow(
                        doctor = doctor,
                        selected = uiState.selectedDoctorId == doctor.id,
                        onSelect = { actions.onSelectDoctor(doctor.id) },
                    )
                }
            }
            uiState.errorMessage?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Button(
                onClick = actions::onSend,
                enabled = uiState.canSend,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp),
            ) {
                Text(if (uiState.isSending) "Sending…" else "Send to doctor", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SuccessContent(doctor: Doctor, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Case sent", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "${doctor.name} (${doctor.specialty}) has been notified.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text("Back to home", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DoctorRow(doctor: Doctor, selected: Boolean, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = selected, onClick = onSelect, enabled = doctor.available)
                Column {
                    Text(text = doctor.name, style = MaterialTheme.typography.titleMedium)
                    Text(text = doctor.specialty, style = MaterialTheme.typography.bodyMedium)
                    doctor.facilityName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        text = if (doctor.available) "Available" else "Unavailable",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (doctor.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
