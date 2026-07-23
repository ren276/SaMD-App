@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.kernelassessment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun KernelAssessmentScreen(
    caseRecordId: String,
    onContinue: () -> Unit,
    viewModel: KernelAssessmentViewModel = hiltViewModel<KernelAssessmentViewModel, KernelAssessmentViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is KernelAssessmentEffect.Continue -> onContinue()
                }
            }
        }
    }
    KernelAssessmentContent(uiState = uiState, actions = viewModel)
}

@Composable
internal fun KernelAssessmentContent(uiState: KernelAssessmentUiState, actions: KernelAssessmentActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("AI Assessment") }) }) { padding: PaddingValues ->
        if (uiState.isLoading) {
            SamdLoadingIndicator(modifier = Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        val display = uiState.display
        if (display == null) {
            Text(
                "No AI assessment available for this case.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConfidenceGauge(display)
            ExplainabilityCard(display)
            if (display.isMockFallback) {
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "This assessment used the offline fallback (mock kernel) — the live AI server was unavailable.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (display.requiresHumanVerification) {
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "⚠ Confidence below 90% — this case requires physician verification before any diagnosis is finalized.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            LiabilityRow(uiState.liabilityAcknowledged, actions::onLiabilityAcknowledgedChange)
            Button(
                onClick = actions::onContinue,
                enabled = uiState.canContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun ConfidenceGauge(display: AssessmentDisplay) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Predicted: ${display.predictedCondition}" + (display.icdCode?.let { " (ICD-10: $it)" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                display.sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                LinearProgressIndicator(
                    progress = { display.confidencePercent / 100f },
                    modifier = Modifier.fillMaxWidth(0.75f).heightIn(min = 8.dp),
                    color = if (display.requiresHumanVerification) Color(0xFFB00020) else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${display.confidencePercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (display.differentialLines.isNotEmpty()) {
                Text(
                    "Differentials",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                display.differentialLines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun ExplainabilityCard(display: AssessmentDisplay) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (display.reasoningLines.isNotEmpty()) {
                Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                display.reasoningLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (display.evidenceFor.isNotEmpty()) {
                Text("Evidence for", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                display.evidenceFor.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (display.evidenceAgainst.isNotEmpty()) {
                Text("Evidence against", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                display.evidenceAgainst.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun LiabilityRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            "I understand this is an AI-generated assessment — not a diagnosis — and requires " +
                "physician verification.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

