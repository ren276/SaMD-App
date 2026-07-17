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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.presentation.common.displayLabel

/**
 * Bottom-nav "Profile" tab: signed-in worker's session info, the offline/sync toggle (same shared
 * [com.example.samdapp.presentation.connectivity.ConnectivityViewModel] instance the top status
 * bar uses — a second, more discoverable entry point, not a second source of truth), and sign-out.
 *
 * The per-worker audit trail is deliberately NOT shown here — it was UI clutter on Profile. The
 * audit log still records every clinical action and persists in the `audit_log` table
 * (insert-only, never deleted — see docs/data-retention.md and [com.example.samdapp.data.local.dao.AuditLogDao]);
 * the read-side [com.example.samdapp.domain.repository.AuditLogRepository] is kept for a future
 * audit-export/review surface rather than crowding the worker's Profile screen.
 */
@Composable
fun ProfileScreen(
    session: UserSession,
    isOnline: Boolean,
    onToggleOnline: () -> Unit,
    onSignOut: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
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

            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}
