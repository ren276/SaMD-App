package com.example.samdapp.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.R
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.sync.SyncState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    onRegisterNewPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    isOnline: Boolean,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        uiState = uiState,
        isOnline = isOnline,
        onRegisterNewPatient = onRegisterNewPatient,
        onOpenPatient = onOpenPatient,
        onSyncNow = viewModel::onSyncNow,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    isOnline: Boolean,
    onRegisterNewPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onSyncNow: () -> Unit,
) {
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp).padding(top = 16.dp, bottom = 8.dp),
            )
            Text(text = "PHC Patient Care", style = MaterialTheme.typography.headlineMedium)

            SyncStatusRow(
                sync = uiState.sync,
                isOnline = isOnline,
                onSyncNow = onSyncNow,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            Text(
                text = "Today's patients",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
            )
            TodaysRoster(
                uiState = uiState,
                onOpenPatient = onOpenPatient,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Button(
                onClick = onRegisterNewPatient,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 16.dp),
            ) {
                Text(text = "Register new patient", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SyncStatusRow(
    sync: SyncState,
    isOnline: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM, hh:mm a").withZone(ZoneId.systemDefault()) }
    val statusText = when {
        sync.isSyncing -> "Syncing…"
        sync.lastSyncedAt == null -> "Not synced yet"
        else -> "Last synced ${formatter.format(sync.lastSyncedAt)}"
    }
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                val caption = when {
                    !isOnline -> "Offline — saved locally, syncs when back online"
                    sync.pendingCount > 0 -> "${sync.pendingCount} pending"
                    else -> "Up to date"
                }
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onSyncNow, enabled = isOnline && !sync.isSyncing) {
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun TodaysRoster(uiState: HomeUiState, onOpenPatient: (String) -> Unit, modifier: Modifier = Modifier) {
    when {
        uiState.isLoadingRoster -> CircularProgressIndicator(modifier = modifier.padding(24.dp))
        uiState.todaysPatients.isEmpty() -> Text(
            text = "No patients seen today yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(24.dp),
        )
        else -> LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.todaysPatients, key = { it.id }) { patient ->
                PatientRosterRow(patient = patient, onClick = { onOpenPatient(patient.id) })
            }
        }
    }
}

@Composable
private fun PatientRosterRow(patient: Patient, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text = patient.fullName, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "ID ${patient.id.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ageOrSex = patient.age?.let { "Age $it" } ?: patient.biologicalSex
                Text(
                    text = ageOrSex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
