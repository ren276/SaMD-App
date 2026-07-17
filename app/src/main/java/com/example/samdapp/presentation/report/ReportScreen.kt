@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.report

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.report.ClinicalReport
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
            uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(padding).padding(32.dp))
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
            )
        }
    }

    if (uiState.showReferralSheet) {
        ReferralSheet(uiState = uiState, actions = viewModel)
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
) {
    val context = LocalContext.current
    val logoBitmap by produceState<android.graphics.Bitmap?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { decodeReportLogo(context) }
    }
    val attachmentBitmaps by produceState(initialValue = emptyMap(), report) {
        value = withContext(Dispatchers.IO) {
            report.attachments.associate { it.uri to decodeAttachmentBitmap(context, it.uri) }
        }
    }
    val renderer = remember(logoBitmap, attachmentBitmaps) {
        ReportCanvasRenderer(logoBitmap = logoBitmap, imageLoader = { uri -> attachmentBitmaps[uri] })
    }
    val pageCount = remember(report) { renderer.pageCount(report) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(pageCount) { pageIndex ->
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ReportCanvasRenderer.PAGE_WIDTH / ReportCanvasRenderer.PAGE_HEIGHT),
            ) {
                drawIntoCanvas { canvas ->
                    val scale = size.width / ReportCanvasRenderer.PAGE_WIDTH
                    val native = canvas.nativeCanvas
                    val save = native.save()
                    native.scale(scale, scale)
                    renderer.drawPage(native, report, pageIndex)
                    native.restoreToCount(save)
                }
            }
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
    }
}

@Composable
private fun ReferralSheet(uiState: ReportUiState, actions: ReportReferralActions) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = actions::onDismissReferralSheet, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Refer to Higher Facility", style = MaterialTheme.typography.titleLarge)
            Text("Urgency", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
