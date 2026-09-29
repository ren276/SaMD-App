@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.report

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.R
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.domain.slm.MAX_QUESTION_CHARS
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReportScreen(
    caseRecordId: String,
    viewModel: ReportViewModel = hiltViewModel<ReportViewModel, ReportViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ReportEffect.SharePdf -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, effect.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share report PDF"))
                    }
                    is ReportEffect.ExportFailed -> Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Preliminary report") }) },
    ) { padding ->
        when {
            uiState.isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(padding).padding(32.dp))
            uiState.report == null ->
                Text(
                    uiState.errorMessage ?: "Could not build report",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            else -> ReportContent(
                report = uiState.report!!,
                isExporting = uiState.isExporting,
                onExport = viewModel::onExportPdf,
                padding = padding,
                actions = viewModel,
                // Hidden, not disabled, while the flag is off: no dead control for a feature that
                // is off in every build. Same posture the VOICE_* flags take on the consultation
                // screen.
                canOpenReadback = uiState.canOpenReadback,
                onOpenReadback = viewModel::onOpenReadback,
            )
        }
    }

    if (uiState.showReferralSheet) {
        ReferralSheet(uiState = uiState, actions = viewModel)
    }
    if (uiState.showReadbackSheet) {
        ReadbackSheet(uiState = uiState, actions = viewModel)
    }
    uiState.referralConfirmationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::onDismissReferralConfirmation,
            confirmButton = { TextButton(onClick = viewModel::onDismissReferralConfirmation) { Text("OK") } },
            title = { Text("Referral sent") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ReportContent(
    report: ClinicalReport,
    isExporting: Boolean,
    onExport: () -> Unit,
    padding: PaddingValues,
    actions: ReportReferralActions,
    canOpenReadback: Boolean,
    onOpenReadback: () -> Unit,
) {
    val context = LocalContext.current
    val logoBitmap by produceState<android.graphics.Bitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { decodeReportLogo(context) }
    }
    val signatureBitmap by produceState<android.graphics.Bitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { decodeReportSignature(context) }
    }
    val attachmentBitmaps by produceState(initialValue = emptyMap(), report) {
        value = withContext(Dispatchers.IO) {
            report.attachments.associate { it.uri to decodeAttachmentBitmap(context, it.uri) }
        }
    }
    // Rendered to plain software bitmaps (same path as ReportPdfExporter) rather than drawn
    // straight into Compose's hardware-accelerated Canvas: on some OEM skins the hardware
    // RenderNode path corrupts/misplaces content across stacked Canvas composables, while a
    // software Bitmap+Canvas always renders correctly regardless of GPU driver quirks.
    val pageBitmaps by produceState(initialValue = emptyList<androidx.compose.ui.graphics.ImageBitmap>(), report, logoBitmap, signatureBitmap, attachmentBitmaps) {
        value = withContext(Dispatchers.Default) {
            val renderer = ReportCanvasRenderer(
                logoBitmap = logoBitmap,
                imageLoader = { uri -> attachmentBitmaps[uri] },
                signatureBitmap = signatureBitmap,
            )
            val bitmapWidth = 1000
            val scale = bitmapWidth / ReportCanvasRenderer.PAGE_WIDTH
            val bitmapHeight = (ReportCanvasRenderer.PAGE_HEIGHT * scale).toInt()
            List(renderer.pageCount(report)) { pageIndex ->
                val bitmap = android.graphics.Bitmap.createBitmap(bitmapWidth, bitmapHeight, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(scale, scale)
                renderer.drawPage(canvas, report, pageIndex)
                bitmap.asImageBitmap()
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (pageBitmaps.isEmpty()) {
            SamdLoadingIndicator(modifier = Modifier.padding(32.dp))
        }
        pageBitmaps.forEach { pageBitmap ->
            Image(
                bitmap = pageBitmap,
                contentDescription = "Report page",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ReportCanvasRenderer.PAGE_WIDTH / ReportCanvasRenderer.PAGE_HEIGHT),
            )
        }
        Button(
            onClick = onExport,
            enabled = !isExporting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(if (isExporting) "Generating PDF…" else "Export / Share PDF", style = MaterialTheme.typography.titleMedium) }

        // Always visible, enabled only when the report suggests escalation (REQ-REF-01 — decision
        // recorded in PROGRESS.md: visible-but-disabled over hidden, for discoverability).
        OutlinedButton(
            onClick = actions::onOpenReferralSheet,
            enabled = report.suggestsReferral,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Refer to Higher Facility", style = MaterialTheme.typography.titleMedium) }
        if (!report.suggestsReferral) {
            Text(
                "No high-severity finding or AI-rejection on this case yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Below the report and below the two actions that concern the record itself, because it
        // is an aid to reading the thing above it rather than a step in the workflow. Absent, not
        // greyed out, when the flag is off or no physician decision has been committed.
        if (canOpenReadback) {
            OutlinedButton(
                onClick = onOpenReadback,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.readback_open), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The read-back surface: one question, one answer or one refusal, and no third thing.
 *
 * A [ModalBottomSheet] over the report rather than a route of its own, following this screen's own
 * `ReferralSheet` idiom. That is not only consistency. A route would need a new `NavKey`, an entry
 * in the secured-route set, a row in the PHI-free back-stack fixture and a decision about what it
 * restores to after process death; a sheet whose state lives in the ViewModel needs none of those
 * and restores, correctly, to a closed sheet over a re-assembled report.
 *
 * **There is no follow-up input anywhere in here, and the type system is what guarantees it.**
 * [ReadbackState] has one answer slot and no list, so `when` below has exactly one branch that
 * draws a text field, and it is the branch with no answer in it. "Ask something else" returns to
 * that branch with the previous question and the previous answer both cleared, so at most one turn
 * is ever on screen and nothing from a previous turn travels with the next.
 */
@Composable
private fun ReadbackSheet(uiState: ReportUiState, actions: ReportReadbackActions) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = actions::onDismissReadback, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.readback_title), style = MaterialTheme.typography.titleLarge)

            when (val state = uiState.readback) {
                ReadbackState.Idle -> {
                    Text(
                        stringResource(R.string.readback_question_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = uiState.readbackQuestion,
                        onValueChange = actions::onReadbackQuestionChange,
                        label = { Text(stringResource(R.string.readback_question_label)) },
                        minLines = 2,
                        isError = uiState.readbackQuestion.length > MAX_QUESTION_CHARS,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = actions::onAskReadback,
                        enabled = uiState.canAskReadback,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.readback_ask), style = MaterialTheme.typography.titleMedium)
                    }
                }

                ReadbackState.Generating -> {
                    // Indeterminate, and never a bar or a percentage. Nothing on this device knows
                    // how far along a generation is, and a progress bar that is invented is a lie
                    // a worker calibrates against.
                    SamdLoadingIndicator(modifier = Modifier.padding(vertical = 24.dp))
                    Text(stringResource(R.string.readback_generating), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.readback_generating_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = actions::onCancelReadback,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.readback_stop), style = MaterialTheme.typography.titleMedium)
                    }
                }

                is ReadbackState.Answer -> {
                    Text(stringResource(R.string.readback_answer_title), style = MaterialTheme.typography.titleMedium)
                    Text(state.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.readback_answer_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = actions::onAskAnotherReadback,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.readback_ask_another), style = MaterialTheme.typography.titleMedium)
                    }
                }

                is ReadbackState.Refused -> {
                    // Every refusal renders here, from its own copy pair. There is no fall-through
                    // branch and no generic message: slmRefusalCopy is total over the enum.
                    val copy = slmRefusalCopy(state.reason)
                    Text(stringResource(copy.titleRes), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(copy.bodyRes), style = MaterialTheme.typography.bodyMedium)
                    // No text is rendered here under any refusal, because Refused carries none.
                    when (copy.retry) {
                        SlmRetryOffer.ASK_AGAIN -> OutlinedButton(
                            onClick = actions::onAskAnotherReadback,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(R.string.readback_ask_another), style = MaterialTheme.typography.titleMedium)
                        }
                        SlmRetryOffer.RETRY_NOW -> OutlinedButton(
                            onClick = actions::onRetryReadback,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(R.string.readback_retry), style = MaterialTheme.typography.titleMedium)
                        }
                        // Nothing. The copy for these names the remedy, which is the report above
                        // or a supervisor, and a button here would offer a fourth thing that is
                        // not one.
                        SlmRetryOffer.NONE -> Unit
                    }
                }
            }

            TextButton(onClick = actions::onDismissReadback, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.readback_close))
            }
        }
    }
}

@Composable
private fun ReferralSheet(uiState: ReportUiState, actions: ReportReferralActions) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = actions::onDismissReferralSheet, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Refer to Higher Facility", style = MaterialTheme.typography.titleLarge)
            Text("Urgency", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UrgencyLevel.entries.forEach { level ->
                    FilterChip(
                        selected = uiState.referralUrgency == level,
                        onClick = { actions.onReferralUrgencyChange(level) },
                        label = { Text(level.name) },
                    )
                }
            }
            OutlinedTextField(
                value = uiState.referralReason,
                onValueChange = actions::onReferralReasonChange,
                label = { Text("Reason (auto-filled, editable)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            uiState.referralErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = actions::onSubmitReferral,
                enabled = uiState.canSubmitReferral,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (uiState.isSubmittingReferral) "Sending…" else "Confirm referral", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

