@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.compounder

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsCaptureMethod
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.Scenario
import com.example.samdapp.presentation.common.DropdownField
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDecimal
import com.example.samdapp.presentation.common.filterDigitsOnly
import com.example.samdapp.presentation.common.rememberLocalNetworkPermissionAction
import com.example.samdapp.presentation.common.rememberPermissionAction

@Composable
fun CompounderScreen(
    patientId: String,
    followUpOfEncounterId: String? = null,
    resumeEncounterId: String? = null,
    resumeCaseRecordId: String? = null,
    onContinue: (patientId: String, encounterId: String, caseRecordId: String, chiefComplaint: String) -> Unit,
    onEmergencyOverride: (reasons: List<String>) -> Unit,
    viewModel: CompounderViewModel = hiltViewModel<CompounderViewModel, CompounderViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId, followUpOfEncounterId, resumeEncounterId, resumeCaseRecordId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is CompounderEffect.Continue ->
                        onContinue(effect.patientId, effect.encounterId, effect.caseRecordId, effect.chiefComplaint)
                    is CompounderEffect.EmergencyOverride -> onEmergencyOverride(effect.reasons)
                }
            }
        }
    }
    CompounderContent(uiState = uiState, actions = viewModel)
}

private val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
private val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)

private fun captureMethodLabel(method: VitalsCaptureMethod): String = when (method) {
    VitalsCaptureMethod.MANUAL_CUFF -> "Manual (stethoscope/cuff)"
    VitalsCaptureMethod.DIGITAL_MONITOR -> "Digital monitor"
    VitalsCaptureMethod.PULSE_OXIMETER -> "Pulse oximeter"
    VitalsCaptureMethod.THERMOMETER -> "Thermometer"
    VitalsCaptureMethod.OTHER -> "Other"
}

private fun instrumentLabel(instrument: Instrument): String = when (instrument) {
    Instrument.BP -> "Blood pressure"
    Instrument.SPO2 -> "SpO2"
    Instrument.THERMOMETER -> "Thermometer"
    Instrument.GLUCOMETER -> "Glucometer"
    Instrument.WEIGHT_SCALE -> "Weight scale"
    Instrument.HEART_RATE -> "Heart rate"
}

/** Exactly the five scenarios the gateway contract offers. The emulator also carries
 *  STALE_TIMESTAMP, DUPLICATE and MALFORMED profiles; those are testing artifacts and are
 *  deliberately not offered here. */
private fun scenarioLabel(scenario: Scenario): String = when (scenario) {
    Scenario.NORMAL -> "Normal"
    Scenario.LOW -> "Low"
    Scenario.HIGH -> "High"
    Scenario.DEVICE_ERROR -> "Device error (fault)"
    Scenario.SENSOR_UNAVAILABLE -> "Sensor unavailable (fault)"
}

/** Field level, never screen level. The [CompounderUiState.isLoadingPrefill] path below replaces
 *  the whole form with a spinner, which during an acquisition would hide both the fields the worker
 *  is watching and anything they have already typed. */
@Composable
private fun AcquiringIndicator() {
    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
}

@Composable
internal fun CompounderContent(uiState: CompounderUiState, actions: CompounderActions) {
    if (uiState.showPrivateHandoffInterstitial) {
        PrivateHandoffInterstitial(
            onAcknowledged = actions::onPrivateHandoffAcknowledged,
            onCancelled = actions::onPrivateHandoffCancelled,
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Compounder / Initial assessment") }) }) { padding: PaddingValues ->
        if (uiState.isLoadingPrefill) {
            SamdLoadingIndicator(modifier = Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StepProgressIndicator(current = 4, total = 4, label = "Ailments & vitals") }
            // ── Demo shortcut — investor-demo only ──────────────────────────────────
            item {
                OutlinedButton(
                    onClick = actions::fillDemoData,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("👤 Fill demo patient data", style = MaterialTheme.typography.labelLarge)
                }
            }
            // Ask the patient what's wrong first — this is a triage conversation, not a form.
            item { Text("What's happening with the patient?", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    uiState.chiefComplaint,
                    actions::onChiefComplaintChange,
                    label = { Text("Main concern *") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text("Ailments", style = MaterialTheme.typography.titleMedium) }
            items(uiState.ailments) { ailment -> AilmentRow(ailment, onDelete = actions::onDeleteAilment) }
            item { NewAilmentCard(uiState, actions) }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            // Now the checks — vitals and, only when clinically required, point-of-care tests.
            item { Text("Vitals", style = MaterialTheme.typography.titleMedium) }
            // Dev only, twice over: this flag is false in staging and prod, and the gateway client
            // it drives lives in src/dev/ and does not exist in those builds at all.
            if (com.example.samdapp.BuildConfig.PI_GATEWAY_ENABLED) {
                item { AcquisitionControls(uiState, actions) }
            }
            item {
                // One capture-method value for the whole snapshot (REQ-TRS-05) — mirrors the
                // existing per-snapshot ObservationSource granularity rather than one dropdown
                // per vital row.
                DropdownField(
                    label = "How were these vitals captured?",
                    value = uiState.captureMethod?.let(::captureMethodLabel).orEmpty(),
                    options = VitalsCaptureMethod.entries.map(::captureMethodLabel),
                    onValueChange = { label ->
                        VitalsCaptureMethod.entries.firstOrNull { captureMethodLabel(it) == label }
                            ?.let(actions::onCaptureMethodChange)
                    },
                )
            }
            item {
                OutlinedTextField(uiState.pulseBpm, { actions.onPulseChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Pulse (bpm)") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.PULSE_BPM)) AcquiringIndicator() }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(uiState.bpSystolic, { actions.onBpSystolicChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("BP systolic") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.BP_SYSTOLIC)) AcquiringIndicator() }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth(0.5f))
                    OutlinedTextField(uiState.bpDiastolic, { actions.onBpDiastolicChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("BP diastolic") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.BP_DIASTOLIC)) AcquiringIndicator() }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                OutlinedTextField(uiState.spo2Percent, { actions.onSpo2Change(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("SpO2 (%)") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.SPO2_PERCENT)) AcquiringIndicator() }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(uiState.temperatureCelsius, { actions.onTemperatureChange(filterDecimal(it)) }, label = { Text("Temperature (°C)") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.TEMPERATURE_CELSIUS)) AcquiringIndicator() }, keyboardOptions = decimalKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(uiState.respiratoryRate, { actions.onRespiratoryRateChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Respiratory rate (breaths/min)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(uiState.weightKg, { actions.onWeightChange(filterDecimal(it)) }, label = { Text("Weight (kg)") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.WEIGHT_KG)) AcquiringIndicator() }, keyboardOptions = decimalKeyboard, modifier = Modifier.fillMaxWidth(0.5f))
                    OutlinedTextField(uiState.heightCm, { actions.onHeightChange(filterDecimal(it)) }, label = { Text("Height (cm)") }, keyboardOptions = decimalKeyboard, modifier = Modifier.fillMaxWidth())
                }
            }
            uiState.bmi?.let { bmi -> item { Text("BMI: $bmi kg/m²", style = MaterialTheme.typography.bodyMedium) } }
            item {
                OutlinedTextField(uiState.painScore, { actions.onPainScoreChange(filterDigitsOnly(it, maxLength = 2)) }, label = { Text("Pain score (0-10)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item {
                OutlinedButton(
                    onClick = actions::onTogglePointOfCareTests,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        if (uiState.showPointOfCareTests) "Hide point-of-care tests" else "Add point-of-care tests (only if clinically required)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (uiState.showPointOfCareTests) {
                item {
                    OutlinedTextField(uiState.bloodGlucoseMgDl, { actions.onBloodGlucoseChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Blood glucose (mg/dL)") }, trailingIcon = { if (uiState.isAcquiring(VitalsField.BLOOD_GLUCOSE_MG_DL)) AcquiringIndicator() }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(uiState.urinalysisResult, actions::onUrinalysisChange, label = { Text("Urinalysis result") }, modifier = Modifier.fillMaxWidth())
                }
            }

            uiState.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }

            item {
                Button(
                    onClick = actions::onContinue,
                    enabled = uiState.canContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp).testTag("continue_button"),
                ) {
                    Text(if (uiState.isSaving) "Saving…" else "Continue", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * Start, Stop and the two selectors for the instrument gateway. Structurally separate from the
 * "How were these vitals captured?" dropdown above the vitals fields, and it must stay that way:
 * that one is the worker's own attestation of how they took the reading (REQ-TRS-05) and autofill
 * never writes it. This one only chooses which instrument the gateway is asked for.
 *
 * Nothing here persists anything. An accepted reading lands in form state that the worker still
 * edits and still has to save; the Continue button below remains the only way into the record.
 */
@Composable
private fun AcquisitionControls(uiState: CompounderUiState, actions: CompounderActions) {
    val startAcquisition = rememberLocalNetworkPermissionAction(
        onGranted = actions::onStartAcquisition,
        onDenied = actions::onLocalNetworkPermissionDenied,
    )
    Card(modifier = Modifier.fillMaxWidth().testTag("acquisition_controls")) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Instrument reading (dev)", style = MaterialTheme.typography.titleSmall)
            DropdownField(
                label = "Instrument",
                value = instrumentLabel(uiState.selectedInstrument),
                options = Instrument.entries.map(::instrumentLabel),
                onValueChange = { label ->
                    Instrument.entries.firstOrNull { instrumentLabel(it) == label }?.let(actions::onInstrumentSelected)
                },
            )
            DropdownField(
                label = "Scenario",
                value = scenarioLabel(uiState.selectedScenario),
                options = Scenario.entries.map(::scenarioLabel),
                onValueChange = { label ->
                    Scenario.entries.firstOrNull { scenarioLabel(it) == label }?.let(actions::onScenarioSelected)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = startAcquisition,
                    enabled = uiState.canStartAcquisition,
                    modifier = Modifier.fillMaxWidth(0.5f).heightIn(min = 48.dp).testTag("start_acquisition_button"),
                ) {
                    Text(if (uiState.acquiringInstrument != null) "Acquiring…" else "Start")
                }
                OutlinedButton(
                    onClick = actions::onStopAcquisition,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop_acquisition_button"),
                ) { Text("Stop") }
            }
            uiState.acquisitionError?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * A saved [Visibility.PRIVATE] ailment renders as a locked placeholder here — [AilmentListItem]
 * never carries its text/values in the first place (see [toListItem]), so there is nothing to
 * accidentally reveal even if this composable had a bug. This is the worker's "subsequent view."
 */
@Composable
private fun AilmentRow(ailment: AilmentListItem, onDelete: (id: String, audioUri: String?) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        if (ailment.visibility == Visibility.PRIVATE) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("🔒 Private entry", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Hidden from worker view" + if (ailment.hasAudio) " · has a voice note" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onDelete(ailment.id, ailment.audioUriForDelete) }) { Text("Delete") }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(ailment.description.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                val detail = buildList {
                    if (ailment.measurementType == MeasurementType.MEASURABLE && ailment.measuredValue != null) {
                        add("${ailment.measuredValue}${ailment.measuredUnit.orEmpty()}")
                    }
                    ailment.severity?.let { add("severity $it/10") }
                    ailment.duration?.let { add(it) }
                }.joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NewAilmentCard(uiState: CompounderUiState, actions: CompounderActions) {
    val requestAudioPermission = rememberPermissionAction(
        permission = Manifest.permission.RECORD_AUDIO,
        onGranted = actions::onStartAilmentAudioRecording,
        onDenied = actions::onAilmentAudioPermissionDenied,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.newAilmentDescription,
                onValueChange = actions::onAilmentDescriptionChange,
                label = { Text("Ailment description") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.newAilmentMeasurementType == MeasurementType.NON_MEASURABLE,
                    onClick = { actions.onAilmentMeasurementTypeChange(MeasurementType.NON_MEASURABLE) },
                    label = { Text("Non-measurable") },
                )
                FilterChip(
                    selected = uiState.newAilmentMeasurementType == MeasurementType.MEASURABLE,
                    onClick = { actions.onAilmentMeasurementTypeChange(MeasurementType.MEASURABLE) },
                    label = { Text("Measurable") },
                )
            }
            if (uiState.newAilmentMeasurementType == MeasurementType.MEASURABLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.newAilmentMeasuredValue,
                        onValueChange = { actions.onAilmentMeasuredValueChange(filterDecimal(it)) },
                        label = { Text("Value *") },
                        keyboardOptions = decimalKeyboard,
                        modifier = Modifier.fillMaxWidth(0.5f),
                    )
                    OutlinedTextField(
                        value = uiState.newAilmentMeasuredUnit,
                        onValueChange = actions::onAilmentMeasuredUnitChange,
                        label = { Text("Unit (e.g. °F)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                OutlinedTextField(
                    value = uiState.newAilmentSeverity,
                    onValueChange = { actions.onAilmentSeverityChange(filterDigitsOnly(it, maxLength = 2)) },
                    label = { Text("Severity (0-10)") },
                    keyboardOptions = numberKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.newAilmentDuration,
                    onValueChange = actions::onAilmentDurationChange,
                    label = { Text("Duration (e.g. 3 days)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.newAilmentOnset,
                    onValueChange = actions::onAilmentOnsetChange,
                    label = { Text("Onset (e.g. sudden)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.newAilmentQualifiers,
                    onValueChange = actions::onAilmentQualifiersChange,
                    label = { Text("Qualifiers (e.g. sharp, dull)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (uiState.newAilmentVisibility == Visibility.PRIVATE) "Private" else "Public")
                Switch(
                    checked = uiState.newAilmentVisibility == Visibility.PRIVATE,
                    onCheckedChange = { actions.onAilmentVisibilityToggle() },
                )
            }

            // Recording is only offered for a private entry — see AilmentAudioRecorder's KDoc for
            // why there is no corresponding playback affordance anywhere.
            if (uiState.newAilmentVisibility == Visibility.PRIVATE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.isRecordingAilmentAudio) {
                        OutlinedButton(onClick = actions::onStopAilmentAudioRecording, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop recording")
                        }
                    } else {
                        OutlinedButton(onClick = requestAudioPermission, modifier = Modifier.fillMaxWidth()) {
                            Text(if (uiState.pendingAilmentAudioUri != null) "Re-record voice note" else "Record voice note")
                        }
                    }
                }
            }

            Button(
                onClick = actions::onAddAilment,
                enabled = uiState.canAddAilment,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Add ailment") }
        }
    }
}

/**
 * Full-screen, high-contrast, Hindi + English — the worker taps Continue and physically hands the
 * device to the patient before anything is typed for a PRIVATE entry (REQ-AIL-02). This is a
 * workflow/social cue, not a technical data-hiding mechanism — the technical guarantee is that a
 * saved private entry never renders its text back into the worker's UI state (see [AilmentRow]).
 */
@Composable
private fun PrivateHandoffInterstitial(onAcknowledged: () -> Unit, onCancelled: () -> Unit) {
    Dialog(onDismissRequest = onCancelled) {
        Card(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Hand the device to the patient",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "उपकरण मरीज़ को दें",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "This information stays private and will not be shown to the health worker.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    "यह जानकारी निजी रहेगी और स्वास्थ्य कर्मी को नहीं दिखाई जाएगी।",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = onAcknowledged,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 32.dp),
                ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = onCancelled, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
            }
        }
    }
}

