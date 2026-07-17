@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.presentation.common.displayLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bottom-nav "Profile" tab: signed-in worker's session info, an audit-trail summary scoped to
 * this worker (see [ProfileViewModel]), the offline/sync toggle (same shared
 * [com.example.samdapp.presentation.connectivity.ConnectivityViewModel] instance the top status
 * bar uses — this is a second, more discoverable entry point to it, not a second source of truth),
 * and sign-out.
 */
@Composable
fun ProfileScreen(
    session: UserSession,
    isOnline: Boolean,
    onToggleOnline: () -> Unit,
    onSignOut: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel<ProfileViewModel, ProfileViewModel.Factory>(
        creationCallback = { factory -> factory.create(session.userId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Profile") }) },
        bottomBar = bottomBar,
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = session.name, style = MaterialTheme.typography.titleLarge)
                    Text(text = session.role.displayLabel(), style = MaterialTheme.typography.bodyMedium)
                    // No PHC identifier exists on the worker session (unlike Patient.primaryCareClinicName,
                    // which is per-patient, not per-worker) — showing a fabricated PHC name here would be
                    // worse than omitting it. Flagged, not silently invented.
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isOnline) "Online" else "Offline — saved locally, syncs when back online",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = isOnline, onCheckedChange = { onToggleOnline() })
                }
            }

            Text(text = "Recent activity", style = MaterialTheme.typography.titleMedium)
            when {
                uiState.isLoadingAudit -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                uiState.recentActions.isEmpty() -> Text(
                    text = "No actions recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    uiState.recentActions.forEach { entry -> AuditRow(entry) }
                }
            }

            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntry) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM, hh:mm a").withZone(ZoneId.systemDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = entry.action.replace('_', ' ').replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatter.format(entry.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
