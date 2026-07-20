@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.consultationchain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.presentation.common.historyLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Chain-detail screen: every visit in one follow-up chain, newest first. Each row opens that
 * visit's own single-consult report — reports are never merged (see PROGRESS.md). Read-only.
 */
@Composable
fun ConsultationChainScreen(
    patientId: String,
    rootEncounterId: String,
    onOpenReport: (caseRecordId: String) -> Unit,
    viewModel: ConsultationChainViewModel = hiltViewModel<ConsultationChainViewModel, ConsultationChainViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId, rootEncounterId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Follow-up history") }) }) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                uiState.visits.isEmpty() -> Text(
                    text = "No visits in this chain.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(uiState.visits) { index, visit ->
                        ChainVisitRow(visit = visit, isLatest = index == 0, onOpenReport = onOpenReport)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainVisitRow(
    visit: ConsultationHistoryEntry,
    isLatest: Boolean,
    onOpenReport: (caseRecordId: String) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy, hh:mm a").withZone(ZoneId.systemDefault()) }
    val clickable = visit.caseRecordId != null
    Card(
        modifier = Modifier.fillMaxWidth().let {
            if (clickable) it.clickable { onOpenReport(visit.caseRecordId!!) } else it
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = visit.chiefComplaint ?: "Incomplete visit — no concern recorded",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (isLatest) "Latest" else "Follow-up",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (visit.doctorName != null) {
                Text(
                    text = visit.doctorName + (visit.doctorSpecialty?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "Doctor not yet assigned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = visit.caseStatus?.historyLabel() ?: "No case record",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatter.format(visit.visitDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

