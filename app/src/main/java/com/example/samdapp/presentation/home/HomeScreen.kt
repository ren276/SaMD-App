package com.example.samdapp.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.R
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.sync.FailedSyncRecord
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.presentation.common.LowResourceWarningDialog
import com.example.samdapp.presentation.common.PatientRosterRow
import com.example.samdapp.presentation.common.deviceResourceWarnings
import com.example.samdapp.presentation.common.displayLabel
import com.example.samdapp.presentation.sync.bodyRes
import com.example.samdapp.presentation.sync.recordTypeLabelFor
import com.example.samdapp.presentation.sync.titleRes
import com.example.samdapp.domain.model.SyncFailureAction
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
    onResumeEncounter: (patientId: String, encounterId: String, caseRecordId: String) -> Unit,
    isOnline: Boolean,
    session: UserSession,
    onSignOut: () -> Unit,
    /** True when the back stack already holds an entry for this case, in which case the resume
     *  prompt must not be offered: accepting it would push a second path to a case the worker is
     *  already inside. Defaults to "never suppressed" so previews and tests keep working. */
    resumeSuppressedFor: (caseRecordId: String) -> Boolean = { false },
    bottomBar: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.ShowSyncNowResult -> snackbarHostState.showSnackbar(effect.message.text(resources))
            }
        }
    }
    HomeContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        isOnline = isOnline,
        session = session,
        onSignOut = onSignOut,
        onRegisterNewPatient = onRegisterNewPatient,
        onOpenPatient = onOpenPatient,
        onOpenDoctorList = onOpenDoctorList,
        onResumeEncounter = onResumeEncounter,
        resumeSuppressedFor = resumeSuppressedFor,
        onSyncNow = viewModel::onSyncNow,
        onOpenFailedRecords = viewModel::onOpenFailedRecords,
        onDismissFailedRecords = viewModel::onDismissFailedRecords,
        onSendFailedRecordAgain = viewModel::onSendFailedRecordAgain,
        bottomBar = bottomBar,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState,
    isOnline: Boolean,
    session: UserSession,
    onSignOut: () -> Unit,
    onRegisterNewPatient: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onOpenDoctorList: () -> Unit,
    onResumeEncounter: (patientId: String, encounterId: String, caseRecordId: String) -> Unit,
    resumeSuppressedFor: (caseRecordId: String) -> Boolean,
    onSyncNow: () -> Unit,
    onOpenFailedRecords: () -> Unit,
    onDismissFailedRecords: () -> Unit,
    onSendFailedRecordAgain: (FailedSyncRecord) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var resourceWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    // Saveable, not `remember`: a dismissal that dies with the process means a worker who said
    // "no" gets asked again on relaunch, now that the restored stack has already put them where
    // they wanted to be.
    var dismissedResumeId by rememberSaveable { mutableStateOf<String?>(null) }

    if (FeatureFlags.RESUME_DRAFT_ENABLED) {
        uiState.resumableEncounter?.let { resumable ->
            // Two gates, not one. The dismissal is the worker's own "no". The suppression is
            // structural: if any entry in the stack is already about this case, offering to resume
            // it would create a second live path to one case.
            if (resumable.caseRecordId != dismissedResumeId && !resumeSuppressedFor(resumable.caseRecordId)) {
                ResumeEncounterDialog(
                    resumable = resumable,
                    onDismiss = { dismissedResumeId = resumable.caseRecordId },
                    onResume = { onResumeEncounter(resumable.patientId, resumable.encounterId, resumable.caseRecordId) },
                )
            }
        }
    }

    if (uiState.isFailedRecordsOpen) {
        FailedRecordsDialog(
            isLoading = uiState.isLoadingFailedRecords,
            records = uiState.failedRecords,
            onDismiss = onDismissFailedRecords,
            onSendAgain = onSendFailedRecordAgain,
        )
    }

    if (resourceWarnings.isNotEmpty()) {
        LowResourceWarningDialog(
            warnings = resourceWarnings,
            onDismiss = { resourceWarnings = emptyList() },
            onContinueAnyway = {
                resourceWarnings = emptyList()
                onRegisterNewPatient()
            },
        )
    }

    Scaffold(bottomBar = bottomBar, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding: PaddingValues ->
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

            // Only when there is something to act on. A card that says "0 problems" on every
            // launch is a card a worker stops seeing, and the count is already zero for almost
            // every install almost all of the time.
            if (uiState.sync.failedCount > 0) {
                FailedRecordsCard(
                    count = uiState.sync.failedCount,
                    onReview = onOpenFailedRecords,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

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
                onClick = {
                    val warnings = if (FeatureFlags.DEVICE_RESOURCE_CHECK_ENABLED) deviceResourceWarnings(context) else emptyList()
                    if (warnings.isEmpty()) onRegisterNewPatient() else resourceWarnings = warnings
                },
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
                // The truth table lives in syncCaption (SyncCaption.kt); "Up to date" only when
                // nothing is pending, failed, conflicted or blocked.
                val caption = syncCaption(sync, isOnline)
                Text(
                    text = caption.text(LocalContext.current.resources),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOnline && caption !is SyncCaption.CouldNotSend) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            OutlinedButton(onClick = onSyncNow, enabled = isOnline && !sync.isSyncing) {
                Text("Sync now")
            }
        }
    }
}

/**
 * The failed-sync card. Same idiom as [SyncStatusRow] directly above it — a [Card] with a
 * text column and one trailing button — because it sits next to it and a second card shape
 * would read as a second kind of thing. The error container colour is the only difference, and
 * it is the difference: this one is asking for something.
 *
 * The count comes from [SyncState.failedCount], which was already being collected and rendered
 * nowhere. No new query, no new collector.
 */
@Composable
private fun FailedRecordsCard(count: Int, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.failed_sync_card_title, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.failed_sync_card_body),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            OutlinedButton(onClick = onReview) { Text(stringResource(R.string.failed_sync_card_action)) }
        }
    }
}

/**
 * One row per failed record: what it is, when it was written, what went wrong in plain words,
 * and at most one thing to press.
 *
 * A dialog rather than a destination, per the same argument as the card: a worker who does not
 * know they have a problem will not navigate to a screen they have no reason to open.
 */
@Composable
private fun FailedRecordsDialog(
    isLoading: Boolean,
    records: List<FailedSyncRecord>,
    onDismiss: () -> Unit,
    onSendAgain: (FailedSyncRecord) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.failed_sync_list_title)) },
        text = {
            when {
                isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(24.dp))
                records.isEmpty() -> Text(stringResource(R.string.failed_sync_list_empty))
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(records, key = { "${it.table}/${it.recordId}" }) { record ->
                        FailedRecordRow(record = record, onSendAgain = { onSendAgain(record) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.failed_sync_list_close)) } },
    )
}

@Composable
private fun FailedRecordRow(record: FailedSyncRecord, onSendAgain: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()) }
    val recordType = stringResource(recordTypeLabelFor(record.table))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            // The patient is what a worker recognises. Without one (an abha_profiles row, or a
            // patient row this device no longer holds) the record type stands alone, which is
            // worse than a name and much better than a blank.
            text = record.patientName
                ?.let { stringResource(R.string.failed_sync_row_header, it, recordType) }
                ?: recordType,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.failed_sync_row_date, formatter.format(record.recordedAt)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(record.reason.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(text = stringResource(record.reason.bodyRes), style = MaterialTheme.typography.bodySmall)
        // No button at all for a cause nothing this worker can press will change. S-4's rule:
        // naming an action that cannot work teaches a worker to distrust every action.
        if (record.reason.action == SyncFailureAction.SEND_AGAIN) {
            OutlinedButton(onClick = onSendAgain, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.failed_sync_action_send_again))
            }
        }
    }
}

/** Crash-recovery resume prompt — offered on Home whenever this worker has a `DRAFT` case that
 *  never reached save (app killed, device power loss, etc. mid-consultation). "Not now" only
 *  hides the prompt for this Home session; it reappears next launch until the case is finished. */
@Composable
private fun ResumeEncounterDialog(resumable: ResumableEncounter, onDismiss: () -> Unit, onResume: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume in-progress consultation?") },
        text = {
            Text(
                "You have an unfinished consultation" +
                    (resumable.patientName?.let { " for $it" } ?: "") +
                    " that wasn't saved — pick up where you left off?",
            )
        },
        confirmButton = { TextButton(onClick = onResume) { Text("Resume") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun TodaysRoster(uiState: HomeUiState, onOpenPatient: (String) -> Unit, modifier: Modifier = Modifier) {
    when {
        uiState.isLoadingRoster -> SamdLoadingIndicator(modifier = modifier.padding(24.dp))
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

