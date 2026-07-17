package com.example.samdapp.presentation.home

import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.R
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.presentation.common.PatientRosterRow
import com.example.samdapp.presentation.common.displayLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// TODO(nav-role-scoping): Home is one shared dashboard for every role right now. Whether
// Compounder-role workers should see a role-specific landing (e.g. their ailment-capture queue
// instead of this general dashboard) is an open product decision, not settled here — don't
// silently branch this composable on session.role until that decision is made.
@Composable
fun HomeScreen(
    onRegisterNewPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onOpenDoctorList: () -> Unit,
    isOnline: Boolean,
    session: UserSession,
    onSignOut: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        uiState = uiState,
        isOnline = isOnline,
        session = session,
        onSignOut = onSignOut,
        onRegisterNewPatient = onRegisterNewPatient,
        onOpenPatient = onOpenPatient,
        onOpenDoctorList = onOpenDoctorList,
        onSyncNow = viewModel::onSyncNow,
        bottomBar = bottomBar,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    isOnline: Boolean,
    session: UserSession,
    onSignOut: () -> Unit,
    onRegisterNewPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onOpenDoctorList: () -> Unit,
    onSyncNow: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    Scaffold(bottomBar = bottomBar) { padding: PaddingValues ->
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

            SignedInRow(session = session, onSignOut = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))

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
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 16.dp),
            ) {
                Text(text = "Register new patient", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = onOpenDoctorList,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp, bottom = 16.dp),
            ) {
                Text(text = "Sent to doctor", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SignedInRow(session: UserSession, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                append("Welcome, ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("${session.name} (${session.role.displayLabel()})")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

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
