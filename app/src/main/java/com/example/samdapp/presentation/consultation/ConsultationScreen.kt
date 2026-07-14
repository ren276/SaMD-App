@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.consultation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.domain.model.AttachmentType
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

private val DURATION_BUCKETS = listOf("today", "few_days", "week_plus", "chronic")

@Composable
private fun ConsultationContent(uiState: ConsultationUiState, actions: ConsultationActions) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val type = if (mimeType.startsWith("video")) AttachmentType.VIDEO else AttachmentType.IMAGE
            actions.onAddAttachment(type, uri.toString())
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) {
            pendingCameraUri?.let { actions.onAddAttachment(AttachmentType.AFFECTED_AREA_PHOTO, it.toString()) }
        }
    }

    val requestVoiceForChiefComplaint =
        rememberPermissionAction(Manifest.permission.RECORD_AUDIO, actions::onRecordChiefComplaintVoice)
    val requestVoiceForAttachment =
        rememberPermissionAction(Manifest.permission.RECORD_AUDIO, actions::onRecordAudioAttachment)
    val requestCameraForAffectedArea = rememberPermissionAction(Manifest.permission.CAMERA) {
        val uri = createCameraOutputUri(context)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Consultation") }) }) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Chief complaint", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !uiState.isVoiceMode, onClick = { if (uiState.isVoiceMode) actions.onToggleVoiceMode() }, label = { Text("Text") })
                    FilterChip(selected = uiState.isVoiceMode, onClick = { if (!uiState.isVoiceMode) actions.onToggleVoiceMode() }, label = { Text("Voice") })
                }
            }
            if (uiState.isVoiceMode) {
                item {
                    OutlinedButton(onClick = requestVoiceForChiefComplaint, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (uiState.isRecordingVoice) "Listening…" else "Record chief complaint", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = uiState.chiefComplaint,
                    onValueChange = actions::onChiefComplaintChange,
                    label = { Text("Chief complaint *") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { Text("History of present illness", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(uiState.onset, actions::onOnsetChange, label = { Text("Symptom onset") }, modifier = Modifier.fillMaxWidth()) }
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
            item { OutlinedTextField(uiState.impactOnDailyActivities, actions::onImpactChange, label = { Text("Impact on daily activities") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(uiState.relevantHistory, actions::onRelevantHistoryChange, label = { Text("Other relevant history") }, modifier = Modifier.fillMaxWidth()) }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { Text("Attachments", style = MaterialTheme.typography.titleMedium) }
            uiState.pendingAttachments.forEach { attachment -> item { Text("• ${attachment.type}") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickVisualMediaLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Image / video", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(
                        onClick = requestCameraForAffectedArea,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("Affected area photo", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                OutlinedButton(onClick = requestVoiceForAttachment, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (uiState.isRecordingVoice) "Listening…" else "Record audio note", style = MaterialTheme.typography.titleMedium)
                }
            }

            uiState.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }

            item {
                Button(
                    onClick = actions::onSend,
                    enabled = uiState.canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                ) {
                    Text(if (uiState.isSaving) "Sending…" else "Send", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Dangerous permissions need a runtime prompt, not just the manifest entry — without this,
 * [android.speech.SpeechRecognizer] and the camera capture silently fail. */
@Composable
private fun rememberPermissionAction(permission: String, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted()
    }
    return {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            launcher.launch(permission)
        }
    }
}

private fun createCameraOutputUri(context: Context): Uri {
    val directory = File(context.cacheDir, "attachments").apply { mkdirs() }
    val file = File(directory, "affected-area-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
