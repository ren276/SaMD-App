@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.referrals

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.presentation.common.displayLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bottom-nav "Referrals" tab: this device's own sent-referral outbox
 * ([com.example.samdapp.domain.repository.ReferralRepository.observeAll]) — real data, no
 * receiving-side system exists so [ReferralRequest.status] never advances past QUEUED in this
 * mock (see Phase 6 notes in PROGRESS.md).
 */
@Composable
fun ReferralsScreen(bottomBar: @Composable () -> Unit = {}, viewModel: ReferralsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Referrals") }) },
        bottomBar = bottomBar,
    ) { padding: PaddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                uiState.referrals.isEmpty() -> Text(
                    text = "No referrals sent yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.referrals, key = { it.id }) { referral -> ReferralRow(referral) }
                }
            }
        }
    }
}

@Composable
private fun ReferralRow(referral: ReferralRequest) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM, hh:mm a").withZone(ZoneId.systemDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Patient UID ${referral.patientUid}", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = referral.urgencyLevel.displayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (referral.urgencyLevel) {
                        UrgencyLevel.EMERGENCY -> MaterialTheme.colorScheme.error
                        UrgencyLevel.URGENT -> MaterialTheme.colorScheme.tertiary
                        UrgencyLevel.ROUTINE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(text = referral.status.displayLabel(), style = MaterialTheme.typography.labelLarge)
            }
            Text(text = referral.reason, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatter.format(referral.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
