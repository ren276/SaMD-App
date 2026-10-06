@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.kernelassessment

import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.samdapp.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun KernelAssessmentScreen(
    caseRecordId: String,
    consultationId: String,
    /** [audioUri] is resolved by the ViewModel from this consultation's attachment row and handed
     *  up here, because the navigation decision it feeds cannot suspend. */
    onContinue: (audioUri: String?) -> Unit,
    viewModel: KernelAssessmentViewModel = hiltViewModel<KernelAssessmentViewModel, KernelAssessmentViewModel.Factory>(
        creationCallback = { factory -> factory.create(caseRecordId, consultationId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is KernelAssessmentEffect.Continue -> onContinue(effect.audioUri)
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
        // Every fixed string below is chosen by assessmentCopy, so the no-score rule for anything
        // but a real result is tested in one place.
        val copy = assessmentCopy(display, uiState.showModelScore)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val failureCopy = copy.failure
            if (failureCopy != null) {
                UnavailableCard(
                    copy = failureCopy,
                    offerRetry = copy.offerRetry,
                    isRetrying = uiState.isRetrying,
                    onRetry = actions::onRetry,
                )
            } else {
                ConfidenceGauge(display, copy.showModelScore, copy.mockSourceLabel)
                // A worker on /evaluate has nothing to show here (the classifier's scored explanation
                // is physician-only), and an empty Card still draws as a blank box.
                if (hasExplainabilityContent(display, copy.showModelScore)) {
                    ExplainabilityCard(display, copy.showModelScore)
                }
            }
            copy.mockNotice?.let { notice ->
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        stringResource(notice),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            copy.verificationNotice?.let { notice ->
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(notice),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            LiabilityRow(
                checked = uiState.liabilityAcknowledged,
                onCheckedChange = actions::onLiabilityAcknowledgedChange,
                text = copy.liability,
            )
            Button(
                onClick = actions::onContinue,
                enabled = uiState.canContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Honest failure state, from the kernel-mock production safety fix. Shown instead of the confidence
 *  gauge/explainability cards when [AssessmentDisplay.isUnavailable] is true: no fabricated
 *  diagnosis, just what went wrong and what to do about it.
 *
 *  The title and body come from [kernelFailureCopy], so they name the record that failed rather
 *  than the feature that did not run. Until this change both were the same two hardcoded
 *  sentences for all ten failure classes, which told a worker whose patient had never reached
 *  the server that the AI had failed, and to press Retry forever.
 *
 *  The button follows [com.example.samdapp.domain.kernel.KernelRetryAdvice] (via
 *  [AssessmentCopy.offerRetry]) and is ABSENT where a person must act first. A button that cannot
 *  work is worse than no button: it teaches a worker that pressing is the remedy, and the remedy
 *  here is on another screen or with a supervisor. */
@Composable
private fun UnavailableCard(copy: KernelFailureCopy, offerRetry: Boolean, isRetrying: Boolean, onRetry: () -> Unit) {
    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(copy.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(copy.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (offerRetry) {
                Button(onClick = onRetry, enabled = !isRetrying) {
                    Text(
                        stringResource(
                            if (isRetrying) R.string.kernel_failure_retrying else R.string.kernel_failure_retry,
                        ),
                    )
                }
            }
        }
    }
}


/** A worker sees the candidate only. A physician sees its score, labelled uncalibrated, and the
 *  classifier's own explanation. */
@Composable
private fun differentialText(line: DifferentialLine, showModelScore: Boolean): String {
    if (!showModelScore || line.scorePercent == null) return line.label
    val scored = stringResource(R.string.assessment_differential_with_score, line.label, line.scorePercent)
    return line.why?.let { "$scored — $it" } ?: scored
}

@Composable
internal fun ConfidenceGauge(
    display: AssessmentDisplay,
    showModelScore: Boolean,
    @StringRes mockSourceLabel: Int? = null,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Predicted: ${display.predictedCondition}" + (display.icdCode?.let { " (ICD-10: $it)" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                mockSourceLabel?.let { stringResource(it) } ?: display.sourceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (display.isEmergency) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(EMERGENCY_BANNER_TAG),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "EMERGENCY",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            if (display.isCriticalVitalsFlag) "Critical vitals (rule-based)" else "Emergency referral",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            // The red flag's 1.0 is a rule's literal, so it gets no bar. An EMERGENCY urgency on a
            // real model class keeps its prediction on screen. The score itself is physician-only:
            // a worker gets no bar and no number, only the verification notice below.
            if (showModelScore && !display.isCriticalVitalsFlag) Column(
                modifier = Modifier.padding(top = 12.dp).testTag(CONFIDENCE_BAR_TAG),
            ) {
                Text(
                    stringResource(R.string.assessment_model_score, display.confidencePercent),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { display.confidencePercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 8.dp),
                    color = if (display.requiresHumanVerification) Color(0xFFB00020) else MaterialTheme.colorScheme.primary,
                )
            }
            if (display.differentialLines.isNotEmpty()) {
                Text(
                    "Differentials",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                display.differentialLines.forEach {
                    Text("• ${differentialText(it, showModelScore)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Whether [ExplainabilityCard] has anything to draw for this role. The card is skipped when it
 *  would be empty: for a worker on the /evaluate path the scored explanation is physician-only, so
 *  without this the screen showed a blank rounded box. */
internal fun hasExplainabilityContent(display: AssessmentDisplay, showModelScore: Boolean): Boolean =
    display.reasoningLines.isNotEmpty() ||
        (showModelScore && display.modelExplanationLines.isNotEmpty()) ||
        display.evidenceFor.isNotEmpty() ||
        display.evidenceAgainst.isNotEmpty()

@Composable
private fun ExplainabilityCard(display: AssessmentDisplay, showModelScore: Boolean) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val reasoning = display.reasoningLines + if (showModelScore) display.modelExplanationLines else emptyList()
            if (reasoning.isNotEmpty()) {
                Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                reasoning.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
private fun LiabilityRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes text: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal const val EMERGENCY_BANNER_TAG = "kernel_emergency_banner"
internal const val CONFIDENCE_BAR_TAG = "kernel_confidence_bar"
