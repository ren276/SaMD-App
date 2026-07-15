@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.compounder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.presentation.common.filterDecimal
import com.example.samdapp.presentation.common.filterDigitsOnly

@Composable
fun CompounderScreen(
    patientId: String,
    onContinue: (patientId: String, encounterId: String, caseRecordId: String, chiefComplaint: String) -> Unit,
    viewModel: CompounderViewModel = hiltViewModel<CompounderViewModel, CompounderViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
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
                }
            }
        }
    }
    CompounderContent(uiState = uiState, actions = viewModel)
}

private val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
private val decimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
internal fun CompounderContent(uiState: CompounderUiState, actions: CompounderActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Compounder / Initial assessment") }) }) { padding: PaddingValues ->
        if (uiState.isLoadingPrefill) {
            CircularProgressIndicator(modifier = Modifier.padding(padding).padding(32.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ask the patient what's wrong first — this is a triage conversation, not a form.
            item { Text("What's happening with the patient?", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    uiState.chiefComplaint,
                    actions::onChiefComplaintChange,
                    label = { Text("Chief complaint *") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            uiState.symptoms.forEach { symptom -> item { Text("• $symptom") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.newSymptomText,
                        onValueChange = actions::onNewSymptomTextChange,
                        label = { Text("Add symptom") },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                    OutlinedButton(
                        onClick = actions::onAddSymptom,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Add", style = MaterialTheme.typography.titleMedium) }
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            // Now the checks — vitals and, only when clinically required, point-of-care tests.
            item { Text("Vitals", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(uiState.pulseBpm, { actions.onPulseChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Pulse (bpm)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(uiState.bpSystolic, { actions.onBpSystolicChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("BP systolic") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth(0.5f))
                    OutlinedTextField(uiState.bpDiastolic, { actions.onBpDiastolicChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("BP diastolic") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                OutlinedTextField(uiState.spo2Percent, { actions.onSpo2Change(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("SpO2 (%)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(uiState.temperatureCelsius, { actions.onTemperatureChange(filterDecimal(it)) }, label = { Text("Temperature (°C)") }, keyboardOptions = decimalKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(uiState.respiratoryRate, { actions.onRespiratoryRateChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Respiratory rate (breaths/min)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(uiState.weightKg, { actions.onWeightChange(filterDecimal(it)) }, label = { Text("Weight (kg)") }, keyboardOptions = decimalKeyboard, modifier = Modifier.fillMaxWidth(0.5f))
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
                    OutlinedTextField(uiState.bloodGlucoseMgDl, { actions.onBloodGlucoseChange(filterDigitsOnly(it, maxLength = 3)) }, label = { Text("Blood glucose (mg/dL)") }, keyboardOptions = numberKeyboard, modifier = Modifier.fillMaxWidth())
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
