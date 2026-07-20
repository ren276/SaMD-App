@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.register

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.presentation.common.DropdownField
import com.example.samdapp.presentation.common.StepProgressIndicator
import com.example.samdapp.presentation.common.filterDigitsOnly
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun RegisterScreen(
    abhaId: String? = null,
    onRegistered: (patientId: String) -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(abhaId) {
        abhaId?.let(viewModel::loadAbhaProfile)
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is RegisterEffect.Registered -> onRegistered(effect.patientId)
                }
            }
        }
    }
    RegisterContent(uiState = uiState, actions = viewModel)
}

private data class FieldSpec(
    val field: RegisterField,
    val label: String,
    val required: Boolean = false,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val maxLength: Int = Int.MAX_VALUE,
)

private val CORE_FIELDS = listOf(
    FieldSpec(RegisterField.FULL_NAME, "Full name", required = true),
    FieldSpec(RegisterField.AGE, "Age (if DOB unknown)", keyboardType = KeyboardType.Number, maxLength = 3),
    FieldSpec(RegisterField.MOBILE_NUMBER, "Mobile number", keyboardType = KeyboardType.Phone, maxLength = 10),
    FieldSpec(RegisterField.GUARDIAN_OR_SPOUSE_NAME, "Guardian / spouse name"),
    FieldSpec(RegisterField.EMERGENCY_CONTACT, "Emergency contact", keyboardType = KeyboardType.Phone, maxLength = 10),
)

private val ADDRESS_FIELDS = listOf(
    FieldSpec(RegisterField.VILLAGE, "Village"),
    FieldSpec(RegisterField.BLOCK, "Block"),
    FieldSpec(RegisterField.DISTRICT, "District"),
    FieldSpec(RegisterField.PINCODE, "Pincode", keyboardType = KeyboardType.Number, maxLength = 6),
)

private val OTHER_FIELDS = listOf(
    FieldSpec(RegisterField.AADHAAR_NUMBER, "Aadhaar number", keyboardType = KeyboardType.Number, maxLength = 12),
    FieldSpec(RegisterField.ABHA_NUMBER, "ABHA number", keyboardType = KeyboardType.Number, maxLength = 14),
    FieldSpec(RegisterField.PRIMARY_CARE_CLINIC_NAME, "Primary care clinic"),
    FieldSpec(RegisterField.REFERRING_PHYSICIAN_NAME, "Referring physician"),
)

private val STATE_OPTIONS = listOf(
    "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat",
    "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh",
    "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab",
    "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh",
    "Uttarakhand", "West Bengal", "Delhi", "Jammu and Kashmir", "Ladakh", "Puducherry",
    "Chandigarh",
)
private val CATEGORY_OPTIONS = listOf("General", "OBC", "SC", "ST")
private val MARITAL_STATUS_OPTIONS = listOf("Single", "Married", "Widowed", "Divorced", "Separated")
private val BLOOD_GROUP_OPTIONS = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

@Composable
internal fun RegisterContent(uiState: RegisterUiState, actions: RegisterActions) {
    Scaffold(topBar = { TopAppBar(title = { Text("Register patient") }) }) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StepProgressIndicator(current = 2, total = 4, label = "Registration") }
            // ── Demo shortcut — investor-demo only ──────────────────────────────────
            item {
                OutlinedButton(
                    onClick = actions::fillDemoData,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("👤 Fill demo patient data", style = MaterialTheme.typography.labelLarge)
                }
            }
            item { SectionLabel("Core details") }
            item { FieldRow(CORE_FIELDS[0], uiState, actions) }
            item {
                DateOfBirthField(
                    value = uiState.fields[RegisterField.DATE_OF_BIRTH].orEmpty(),
                    onValueChange = { actions.onFieldChange(RegisterField.DATE_OF_BIRTH, it) },
                    isFromAbha = RegisterField.DATE_OF_BIRTH in uiState.autofilledFields,
                )
            }
            items(CORE_FIELDS.drop(1)) { spec -> FieldRow(spec, uiState, actions) }
            item {
                BiologicalSexRow(
                    selected = uiState.biologicalSex,
                    isFromAbha = uiState.sexAutofilledFromAbha,
                    onSelect = actions::onBiologicalSexChange,
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Address (phone or address required)") }
            items(ADDRESS_FIELDS) { spec -> FieldRow(spec, uiState, actions) }
            item {
                DropdownField(
                    label = "State",
                    value = uiState.fields[RegisterField.STATE].orEmpty(),
                    options = STATE_OPTIONS,
                    onValueChange = { actions.onFieldChange(RegisterField.STATE, it) },
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Other details (optional)") }
            item {
                DropdownField(
                    label = "Category",
                    value = uiState.fields[RegisterField.CATEGORY].orEmpty(),
                    options = CATEGORY_OPTIONS,
                    onValueChange = { actions.onFieldChange(RegisterField.CATEGORY, it) },
                )
            }
            item {
                DropdownField(
                    label = "Marital status",
                    value = uiState.fields[RegisterField.MARITAL_STATUS].orEmpty(),
                    options = MARITAL_STATUS_OPTIONS,
                    onValueChange = { actions.onFieldChange(RegisterField.MARITAL_STATUS, it) },
                )
            }
            item {
                DropdownField(
                    label = "Blood group",
                    value = uiState.fields[RegisterField.BLOOD_GROUP].orEmpty(),
                    options = BLOOD_GROUP_OPTIONS,
                    onValueChange = { actions.onFieldChange(RegisterField.BLOOD_GROUP, it) },
                )
            }
            items(OTHER_FIELDS) { spec -> FieldRow(spec, uiState, actions) }

            uiState.errorMessage?.let { message ->
                item { Text(text = message, color = MaterialTheme.colorScheme.error) }
            }

            item {
                Button(
                    onClick = actions::onSubmit,
                    enabled = uiState.canSubmit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp).testTag("submit_button"),
                ) {
                    Text(if (uiState.isSubmitting) "Saving…" else "Register patient", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun FieldRow(spec: FieldSpec, uiState: RegisterUiState, actions: RegisterActions) {
    val error = uiState.fieldError(spec.field)
    val isFromAbha = spec.field in uiState.autofilledFields
    OutlinedTextField(
        value = uiState.fields[spec.field].orEmpty(),
        onValueChange = { raw ->
            val filtered = when (spec.keyboardType) {
                KeyboardType.Number, KeyboardType.Phone -> filterDigitsOnly(raw, spec.maxLength)
                else -> raw
            }
            actions.onFieldChange(spec.field, filtered)
        },
        label = { Text(spec.label + if (spec.required) " *" else "") },
        isError = error != null,
        supportingText = {
            when {
                error != null -> Text(error)
                isFromAbha -> Text("From ABHA", color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = spec.keyboardType),
    )
}

@Composable
private fun DateOfBirthField(value: String, onValueChange: (String) -> Unit, isFromAbha: Boolean = false) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val displayDate = value.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    OutlinedTextField(
        value = displayDate?.toString().orEmpty(),
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text("Date of birth") },
        trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = "Pick date of birth") },
        supportingText = if (isFromAbha) { { Text("From ABHA", color = MaterialTheme.colorScheme.primary) } } else null,
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable { showPicker = true },
    )

    if (showPicker) {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (displayDate ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onValueChange(picked.toString())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun BiologicalSexRow(selected: String, onSelect: (String) -> Unit, isFromAbha: Boolean = false) {
    Column {
        Text(
            text = "Biological sex" + if (isFromAbha) " (from ABHA)" else "",
            style = MaterialTheme.typography.labelLarge,
            color = if (isFromAbha) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            listOf("Female", "Male", "Other").forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}
