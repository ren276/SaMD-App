@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.consultation

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.presentation.common.DropdownField
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.rememberPermissionAction
import java.io.File

@Composable
fun ConsultationScreen(
    patientId: String,
    encounterId: String,
    caseRecordId: String,
    initialChiefComplaint: String,
    onSent: (patientId: String, encounterId: String, caseRecordId: String, consultationId: String, audioUri: String?) -> Unit,
    viewModel: ConsultationViewModel = hiltViewModel<ConsultationViewModel, ConsultationViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId, encounterId, caseRecordId, initialChiefComplaint) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ConsultationEffect.Sent ->
                        onSent(effect.patientId, effect.encounterId, effect.caseRecordId, effect.consultationId, effect.audioUri)
                }
            }
        }
    }
    ConsultationContent(uiState = uiState, actions = viewModel)
}

private val DURATION_BUCKETS = listOf("today", "few_days", "week_plus", "month_plus", "chronic")

private val ONSET_OPTIONS = listOf(
    "Sudden (minutes to hours)",
    "Acute (1-3 days)",
    "Gradual (days to weeks)",
    "Insidious (weeks to months)",
    "Intermittent / episodic",
    "Not known",
)

private val HISTORY_CHIPS = listOf(
    "Diabetes", "Hypertension", "TB (past or current)", "Asthma / COPD",
    "Heart disease", "Thyroid disorder", "Pregnancy", "Known drug allergy",
    "Tobacco / alcohol use", "No known history",
)

private val IMPACT_CHIPS = listOf(
    "Unable to work / farm", "Missing school", "Cannot do household chores",
    "Bedridden", "Sleep disturbed", "Reduced appetite", "No impact on daily activity",
)

/** Appends [clause] to [current] as a comma-joined list, skipping a repeat tap of the same clause
 * (case-insensitive). Shared by the history and impact chip rows. */
internal fun appendClause(current: String, clause: String): String = when {
    current.isBlank() -> clause
    current.split(",").any { it.trim().equals(clause, ignoreCase = true) } -> current
    else -> "$current, $clause"
}

@Composable
internal fun ConsultationContent(uiState: ConsultationUiState, actions: ConsultationActions) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showReview by remember { mutableStateOf(false) }

    if (showReview) {
        ConsultationReviewDialog(
            uiState = uiState,
            onConfirm = {
                showReview = false
                actions.onSend()
            },
            onDismiss = { showReview = false },
        )
    }

    // H-18, Build 3a: a document upload failure holds the Sent navigation back
    // (ConsultationViewModel.onSend) until this is explicitly dismissed, so the worker actually
    // sees it rather than it being raced off-screen by an immediate navigation.
    if (uiState.documentUploadFailures.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = actions::onDismissDocumentUploadFailures,
            title = { Text("Some documents could not be uploaded") },
            text = {
                Column {
                    uiState.documentUploadFailures.forEach { failure -> Text("• $failure") }
                }
            },
            confirmButton = {
                TextButton(onClick = actions::onDismissDocumentUploadFailures) { Text("Continue") }
            },
        )
    }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val type = if (mimeType.startsWith("video")) AttachmentType.VIDEO else AttachmentType.IMAGE
            actions.onAddAttachment(type, uri.toString())
        }
    }
    // H-18, Build 3a: PDF/JPEG/PNG only — a generic file picker (OpenDocument), not
    // PickVisualMedia, since PDFs aren't visual media. The claimed MIME type from the picker is
    // carried through only as a cross-check; the upload path validates the actual bytes by magic
    // number and never trusts this.
    val pickDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            actions.onDocumentPicked(uri.toString(), context.contentResolver.getType(uri))
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) {
            pendingCameraUri?.let { actions.onAddAttachment(AttachmentType.AFFECTED_AREA_PHOTO, it.toString()) }
        }
    }

    // H-18, Build 3b. Same platform `TakePicture` primitive as the affected-area photo above, but
    // deliberately NOT its storage posture: that path hands the camera a `cacheDir/attachments`
    // JPEG and leaves it there in plaintext (the H-19 gap). Here the frame lands in its own
    // staging directory and `onDocumentPageCaptured` encrypts it into the capture session and
    // deletes the plaintext before the next page can be taken.
    val takeDocumentPageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        actions.onDocumentPageCaptured(saved)
    }
    val capture = uiState.documentCapture
    LaunchedEffect(capture?.pendingPageId) {
        val stagingPath = capture?.pendingStagingPath ?: return@LaunchedEffect
        takeDocumentPageLauncher.launch(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(stagingPath)),
        )
    }
    if (capture != null) {
        LaunchFirstCapturePage(capture = capture, actions = actions)
        DocumentCaptureSurface(capture = capture, actions = actions)
    }
    val requestCameraForDocumentScan = rememberPermissionAction(
        permission = Manifest.permission.CAMERA,
        onGranted = actions::onStartDocumentCapture,
    )

    val requestVoiceForChiefComplaint =
        rememberPermissionAction(Manifest.permission.RECORD_AUDIO, actions::onRecordChiefComplaintVoice)
    val requestVoiceForAttachment =
        rememberPermissionAction(Manifest.permission.RECORD_AUDIO, actions::onRecordAudioAttachment)
    // onDenied is passed here and nowhere else on purpose: this is the one voice control a worker
    // can actually reach (FeatureFlags.VOICE_FIELD_IMPACT_ENABLED is on), so a declined prompt has
    // to say so instead of leaving a button that silently does nothing. The other two RECORD_AUDIO
    // call sites stay on the helper's no-op default while VOICE_INPUT_ENABLED keeps them hidden.
    val requestVoiceForImpact = rememberPermissionAction(
        permission = Manifest.permission.RECORD_AUDIO,
        onGranted = actions::onRecordImpactVoice,
        onDenied = actions::onVoicePermissionDenied,
    )
    // Named, not a trailing lambda: `onDenied` is the last parameter now, so a trailing lambda
    // would silently bind to it instead of to `onGranted`.
    val requestCameraForAffectedArea = rememberPermissionAction(
        permission = Manifest.permission.CAMERA,
        onGranted = {
            val uri = createCameraOutputUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        },
    )

    Scaffold(topBar = { TopAppBar(title = { Text("Consultation") }) }) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StepProgressIndicator(current = 4, total = 4, label = "Consultation") }
            // ── Demo shortcut — investor-demo only ──────────────────────────────────
            item {
                OutlinedButton(
                    onClick = actions::fillDemoData,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("👤 Fill demo patient data", style = MaterialTheme.typography.labelLarge)
                }
            }
            item { Text("Main concern", style = MaterialTheme.typography.titleMedium) }
            // Voice affordance hidden, not merely disabled, while FeatureFlags.VOICE_INPUT_ENABLED
            // is off (see its KDoc): the off-device recognizer exposure means no dead button
            // should sit here for a worker to tap. chiefComplaint stays fully keyboard-editable
            // via the OutlinedTextField below, unaffected by this flag.
            if (FeatureFlags.VOICE_INPUT_ENABLED) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !uiState.isVoiceMode, onClick = { if (uiState.isVoiceMode) actions.onToggleVoiceMode() }, label = { Text("Text") })
                        FilterChip(selected = uiState.isVoiceMode, onClick = { if (!uiState.isVoiceMode) actions.onToggleVoiceMode() }, label = { Text("Voice") })
                    }
                }
                if (uiState.isVoiceMode) {
                    item {
                        OutlinedButton(onClick = requestVoiceForChiefComplaint, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(if (uiState.isRecordingVoice) "Listening…" else "Record main concern", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = uiState.chiefComplaint,
                    onValueChange = actions::onChiefComplaintChange,
                    label = { Text("Main concern *") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { Text("History of present illness", style = MaterialTheme.typography.titleMedium) }
            item {
                DropdownField(
                    label = "Symptom onset",
                    value = uiState.onset,
                    options = ONSET_OPTIONS,
                    onValueChange = actions::onOnsetChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATION_BUCKETS.forEach { bucket ->
                        FilterChip(
                            selected = uiState.durationBucket == bucket,
                            onClick = { actions.onDurationBucketChange(bucket) },
                            label = { Text(bucket.replace('_', ' ')) },
                        )
                    }
                }
            }
            item {
                Text("Severity: ${uiState.severityScore} / 10")
                Slider(
                    value = uiState.severityScore.toFloat(),
                    onValueChange = { actions.onSeverityScoreChange(it.toInt()) },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
            item { OutlinedTextField(uiState.aggravatingFactors, actions::onAggravatingFactorsChange, label = { Text("Aggravating factors") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(uiState.relievingFactors, actions::onRelievingFactorsChange, label = { Text("Relieving factors") }, modifier = Modifier.fillMaxWidth()) }
            // Hidden, not merely disabled, while FeatureFlags.VOICE_FIELD_IMPACT_ENABLED is off:
            // see its KDoc. Independent of VOICE_INPUT_ENABLED (which stays off and gates
            // chiefComplaint voice + the audio attachment); this flag governs only this field.
            item {
                // Typed val so Kotlin infers @Composable on the lambda correctly.
                // trailingIcon is @Composable (() -> Unit)? — the if-expression below
                // must resolve to that type; parenthesised lambdas alone don't carry
                // the @Composable annotation without an explicit type ascription.
                val micTrailingIcon: @Composable (() -> Unit)? =
                    if (FeatureFlags.VOICE_FIELD_IMPACT_ENABLED) {
                        {
                            IconButton(
                                onClick = requestVoiceForImpact,
                                enabled = !uiState.isCapturingImpactVoice,
                                modifier = Modifier.testTag("impact_voice_mic_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = if (uiState.isCapturingImpactVoice)
                                        "Listening…" else "Record impact on daily activities",
                                    tint = if (uiState.isCapturingImpactVoice)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else
                                        MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IMPACT_CHIPS.forEach { chip ->
                        FilterChip(
                            selected = false,
                            enabled = !uiState.isCapturingImpactVoice,
                            onClick = { actions.onImpactChange(appendClause(uiState.impactOnDailyActivities, chip)) },
                            label = { Text(chip) },
                        )
                    }
                }
                OutlinedTextField(
                    value = uiState.impactOnDailyActivities,
                    onValueChange = actions::onImpactChange,
                    label = { Text("Impact on daily activities") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = micTrailingIcon,
                )
            }
            if (FeatureFlags.VOICE_FIELD_IMPACT_ENABLED) {
                uiState.impactVoiceSuggestion?.let { suggestion ->
                    item { ImpactVoiceSuggestionSurface(suggestion = suggestion, actions = actions) }
                }
            }
            // Rendered here, immediately below the impact mic control, rather than after the
            // attachments section below: a permission denial (or any other error on this shared
            // state field) must be visible without scrolling past the rest of the form.
            uiState.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HISTORY_CHIPS.forEach { chip ->
                        FilterChip(
                            selected = false,
                            onClick = { actions.onRelevantHistoryChange(appendClause(uiState.relevantHistory, chip)) },
                            label = { Text(chip) },
                        )
                    }
                }
            }
            item { OutlinedTextField(uiState.relevantHistory, actions::onRelevantHistoryChange, label = { Text("Other relevant history") }, modifier = Modifier.fillMaxWidth()) }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { Text("Attachments", style = MaterialTheme.typography.titleMedium) }
            uiState.pendingAttachments.forEach { attachment -> item { Text("• ${attachment.type}") } }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickVisualMediaLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) { Text("Image / video", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        onClick = requestCameraForAffectedArea,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Text("Affected area photo", style = MaterialTheme.typography.titleMedium)
                    }
                    // Hidden, not merely disabled, while FeatureFlags.VOICE_INPUT_ENABLED is off:
                    // see its KDoc for the off-device recognizer exposure this defers to.
                    if (FeatureFlags.VOICE_INPUT_ENABLED) {
                        OutlinedButton(
                            onClick = requestVoiceForAttachment,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        ) {
                            Text(if (uiState.isRecordingVoice) "Listening…" else "Record audio", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { Text("Upload reports, if any", style = MaterialTheme.typography.titleMedium) }
            uiState.pendingDocuments.forEach { document ->
                item {
                    Text(
                        "• ${document.label.ifBlank { document.recordTypeCode.name }} " +
                            "(${document.departmentCode.name} · ${document.recordTypeCode.name})",
                    )
                }
            }
            item {
                DropdownField(
                    label = "Department",
                    value = uiState.documentDraftDepartment?.name.orEmpty(),
                    options = DepartmentCode.entries.map { it.name },
                    onValueChange = { name -> actions.onDocumentDepartmentSelected(DepartmentCode.valueOf(name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                DropdownField(
                    label = "Record type",
                    value = uiState.documentDraftRecordType?.name.orEmpty(),
                    options = RecordTypeCode.entries.map { it.name },
                    onValueChange = { name -> actions.onDocumentRecordTypeSelected(RecordTypeCode.valueOf(name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.documentDraftLabel,
                    onValueChange = actions::onDocumentLabelChange,
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = { pickDocumentLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
                    enabled = uiState.canPickDocument,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Pick file (PDF, JPEG, or PNG)", style = MaterialTheme.typography.titleMedium) }
            }
            item {
                // H-18, Build 3b, PATH B. Gated on the same controlled-vocabulary selections as
                // the file picker: a scanned report is never queued with a guessed department.
                OutlinedButton(
                    onClick = requestCameraForDocumentScan,
                    enabled = uiState.canPickDocument && uiState.documentCapture == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("scanReportPages"),
                ) { Text("Scan report pages with camera", style = MaterialTheme.typography.titleMedium) }
            }

            item {
                Button(
                    onClick = { showReview = true },
                    enabled = uiState.canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                ) {
                    Text(if (uiState.isSaving) "Sending…" else "Review & send", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Deliberate confirmation gate before a consultation is committed and handed off — the
 * worker re-reads a summary rather than submitting on a single hurried tap (ISO 14971
 * human-in-the-loop). */
@Composable
private fun ConsultationReviewDialog(
    uiState: ConsultationUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review before sending") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { ReviewLine("Main concern", uiState.chiefComplaint) }
                item { ReviewLine("Symptom onset", uiState.onset) }
                item { ReviewLine("Duration", uiState.durationBucket?.replace('_', ' ')) }
                item { ReviewLine("Severity", "${uiState.severityScore} / 10") }
                item { ReviewLine("Aggravating factors", uiState.aggravatingFactors) }
                item { ReviewLine("Relieving factors", uiState.relievingFactors) }
                item { ReviewLine("Impact on daily activities", uiState.impactOnDailyActivities) }
                item { ReviewLine("Other relevant history", uiState.relevantHistory) }
                item { ReviewLine("Attachments", uiState.pendingAttachments.size.toString()) }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirm & send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
    )
}

/**
 * The gate's Suggested state (`scratchpad/pr3-voice-gate-design-memo.md` A.4). Rendered in its
 * own [Card], adjacent to the impact `OutlinedTextField` above it, never inside it: the
 * suggestion is [uiState.impactVoiceSuggestion][ConsultationUiState.impactVoiceSuggestion], a
 * separate field from the committed value, so this composable cannot mutate the text field even
 * by construction.
 *
 * The three actions are deliberately identical [OutlinedButton]s, same shape, same weight, same
 * row. Making "Use it" a filled primary button with "Discard" as a small text button would be a
 * visual hierarchy that pushes toward accept without reading, which is exactly the rubber-stamp
 * failure the design memo cites evidence for (71% of ED notes carrying an error under this same
 * class of control). Equal weight is a safety property here, not a style choice.
 */
@Composable
internal fun ImpactVoiceSuggestionSurface(suggestion: String, actions: ConsultationActions) {
    Card(modifier = Modifier.fillMaxWidth().testTag("impact_voice_suggestion_surface")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Voice suggestion", style = MaterialTheme.typography.labelLarge)
            Text(suggestion, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = actions::onUseImpactSuggestion,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("impact_voice_use_button"),
                ) { Text("Use it") }
                OutlinedButton(
                    onClick = actions::onEditImpactSuggestion,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("impact_voice_edit_button"),
                ) { Text("Edit") }
                OutlinedButton(
                    onClick = actions::onDiscardImpactSuggestion,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("impact_voice_discard_button"),
                ) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelLarge)
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun createCameraOutputUri(context: Context): Uri {
    val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(directory, "affected-area-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
