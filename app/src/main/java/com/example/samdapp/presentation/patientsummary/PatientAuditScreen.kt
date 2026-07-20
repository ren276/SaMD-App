@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.patientsummary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.audit.PatientFacingAuditEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DPDP right-to-access gesture: a plain-language, patient-presentable view of who has touched
 * this record — reuses the existing audit trail ([com.example.samdapp.domain.audit.AuditLogEntry]),
 * mapped through [com.example.samdapp.domain.audit.toPatientFacingEntries] so nothing technical
 * (raw action codes, entity ids, payload JSON) ever reaches this screen.
 */
@Composable
fun PatientAuditScreen(
    patientId: String,
    viewModel: PatientAuditViewModel = hiltViewModel<PatientAuditViewModel, PatientAuditViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PatientAuditContent(uiState = uiState)
}

@Composable
internal fun PatientAuditContent(uiState: PatientAuditUiState) {
    Scaffold(topBar = { TopAppBar(title = { Text("Who has seen your file") }) }) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = "This is a record of everyone and everything that has accessed or updated your file.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            when {
                uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                uiState.entries.isEmpty() -> Text(
                    text = "No activity recorded on your file yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.entries) { entry -> AuditEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun AuditEntryRow(entry: PatientFacingAuditEntry) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy, hh:mm a").withZone(ZoneId.systemDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatter.format(entry.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

