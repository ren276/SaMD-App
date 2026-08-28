@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.patients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.samdapp.presentation.common.SamdLoadingIndicator
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
import com.example.samdapp.presentation.common.PatientRosterRow

/**
 * Bottom-nav "Patients" tab: last 7 days, name/ID searchable - a bounded window, not the full
 * patient table (see [com.example.samdapp.domain.usecase.GetRecentPatientsUseCase]). Unlike
 * Home, includes a patient registered but not yet seen (tapping still opens PatientSummary,
 * same as any other row - [onOpenPatient] does not care whether the patient has an encounter).
 */
@Composable
fun PatientsScreen(
    onOpenPatient: (String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    viewModel: PatientsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Patients") }) },
        bottomBar = bottomBar,
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search by name or ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            when {
                uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                uiState.patients.isEmpty() -> Text(
                    text = if (uiState.query.isBlank()) {
                        "No patients registered or seen in the last 7 days."
                    } else {
                        "No patients match \"${uiState.query}\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.patients, key = { it.patient.id }) { entry ->
                        PatientRosterRow(
                            patient = entry.patient,
                            onClick = { onOpenPatient(entry.patient.id) },
                            subtitle = if (entry.lastSeenAt == null) "Registered, not yet seen" else null,
                        )
                    }
                }
            }
        }
    }
}

