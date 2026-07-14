@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.register

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.samdapp.presentation.common.DropdownField

@Composable
fun RegisterScreen(
    onRegistered: (patientId: String) -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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

private data class FieldSpec(val field: RegisterField, val label: String, val required: Boolean = false)

private val CORE_FIELDS = listOf(
    FieldSpec(RegisterField.FULL_NAME, "Full name", required = true),
    FieldSpec(RegisterField.DATE_OF_BIRTH, "Date of birth (YYYY-MM-DD)"),
    FieldSpec(RegisterField.AGE, "Age (if DOB unknown)"),
    FieldSpec(RegisterField.MOBILE_NUMBER, "Mobile number"),
    FieldSpec(RegisterField.GUARDIAN_OR_SPOUSE_NAME, "Guardian / spouse name"),
    FieldSpec(RegisterField.EMERGENCY_CONTACT, "Emergency contact"),
)

private val ADDRESS_FIELDS = listOf(
    FieldSpec(RegisterField.VILLAGE, "Village"),
    FieldSpec(RegisterField.BLOCK, "Block"),
    FieldSpec(RegisterField.DISTRICT, "District"),
    FieldSpec(RegisterField.PINCODE, "Pincode"),
)

private val OTHER_FIELDS = listOf(
    FieldSpec(RegisterField.AADHAAR_NUMBER, "Aadhaar number"),
    FieldSpec(RegisterField.ABHA_NUMBER, "ABHA number"),
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
            item { SectionLabel("Core details") }
            items(CORE_FIELDS) { spec -> FieldRow(spec, uiState, actions) }
            item { BiologicalSexRow(uiState.biologicalSex, actions::onBiologicalSexChange) }

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
    OutlinedTextField(
        value = uiState.fields[spec.field].orEmpty(),
        onValueChange = { actions.onFieldChange(spec.field, it) },
        label = { Text(spec.label + if (spec.required) " *" else "") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun BiologicalSexRow(selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(text = "Biological sex", style = MaterialTheme.typography.labelLarge)
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
