@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.doctorlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.presentation.common.doctorTrackerLabel

/**
 * Minimal read-only status tracker (Part B) — patient name/ID, one-line chief complaint, status.
 * No sort-by-urgency, no triage badges, no clinical action; tapping a Reviewed row opens the same
 * read-only report view everything else in this app reuses ([ReportRoute]).
 */
@Composable
fun DoctorListScreen(onOpenReport: (caseRecordId: String) -> Unit, viewModel: DoctorListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Sent to doctor") }) }) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                uiState.entries.isEmpty() -> Text(
                    text = "No cases sent to a doctor yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.entries, key = { it.caseRecordId }) { entry ->
                        DoctorTrackerRow(entry, onOpenReport = onOpenReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorTrackerRow(entry: DoctorTrackerEntry, onOpenReport: (caseRecordId: String) -> Unit) {
    val isReviewed = entry.status == CaseStatus.PRESCRIPTION_RECEIVED
    Card(
        modifier = Modifier.fillMaxWidth().let {
            if (isReviewed) it.clickable { onOpenReport(entry.caseRecordId) } else it
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = entry.patientFullName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = entry.chiefComplaint ?: "No main concern recorded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.doctorName?.let { it + (entry.doctorSpecialty?.let { s -> " · $s" } ?: "") }
                    ?: "Doctor not yet assigned",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ID ${entry.patientId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.status.doctorTrackerLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isReviewed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

