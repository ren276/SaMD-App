@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.samdapp.presentation.medicalbackground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicationKind

@Composable
fun MedicalBackgroundScreen(
    patientId: String,
    onContinue: (patientId: String) -> Unit,
    viewModel: MedicalBackgroundViewModel = hiltViewModel<MedicalBackgroundViewModel, MedicalBackgroundViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MedicalBackgroundContent(uiState = uiState, actions = viewModel, onContinue = { onContinue(patientId) })
}

@Composable
private fun MedicalBackgroundContent(
    uiState: MedicalBackgroundUiState,
    actions: MedicalBackgroundActions,
    onContinue: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Medical background") }) }) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                MedicalHistorySection(
                    items = uiState.medicalHistoryItems.map { "${it.category}: ${it.description}" },
                    onAdd = actions::onAddMedicalHistoryItem,
                )
            }
            item { HorizontalDivider() }
            item {
                MedicationSection(
                    items = uiState.medications.map { "${it.kind} — ${it.name} ${it.dosage.orEmpty()} ${it.frequency.orEmpty()}" },
                    onAdd = actions::onAddMedication,
                )
            }
            item { HorizontalDivider() }
            item {
                AllergySection(
                    items = uiState.allergies.map { "${it.category}: ${it.allergen}${it.reactionType?.let { r -> " ($r)" }.orEmpty()}" },
                    onAdd = actions::onAddAllergy,
                )
            }
            item { HorizontalDivider() }
            item {
                FamilyHistorySection(
                    items = uiState.familyHistory.map { "${it.condition}${it.relation?.let { r -> " ($r)" }.orEmpty()}" },
                    onAdd = actions::onAddFamilyHistoryEntry,
                )
            }
            item { HorizontalDivider() }
            item { SocialHistorySection(onSave = actions::onSaveSocialHistory) }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Skip for now", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Continue", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ExistingItemsList(items: List<String>) {
    items.forEach { line -> Text(text = "• $line", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun MedicalHistorySection(
    items: List<String>,
    onAdd: (MedicalHistoryCategory, String, String?) -> Unit,
) {
    var category by remember { mutableStateOf(MedicalHistoryCategory.CHRONIC_CONDITION) }
    var description by remember { mutableStateOf("") }
    var yearOrDate by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Medical & surgical history")
        ExistingItemsList(items)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MedicalHistoryCategory.entries.forEach { option ->
                FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option.label()) })
            }
        }
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = yearOrDate,
            onValueChange = { yearOrDate = it },
            label = { Text("Year (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                if (description.isNotBlank()) {
                    onAdd(category, description, yearOrDate.ifBlank { null })
                    description = ""
                    yearOrDate = ""
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Add", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun MedicationSection(items: List<String>, onAdd: (MedicationKind, String, String?, String?) -> Unit) {
    var kind by remember { mutableStateOf(MedicationKind.MEDICATION) }
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Current medications & supplements")
        ExistingItemsList(items)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MedicationKind.entries.forEach { option ->
                FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option.label()) })
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = dosage, onValueChange = { dosage = it }, label = { Text("Dosage (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = frequency, onValueChange = { frequency = it }, label = { Text("Frequency (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = {
                if (name.isNotBlank()) {
                    onAdd(kind, name, dosage.ifBlank { null }, frequency.ifBlank { null })
                    name = ""; dosage = ""; frequency = ""
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Add", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun AllergySection(items: List<String>, onAdd: (AllergyCategory, String, String?) -> Unit) {
    var category by remember { mutableStateOf(AllergyCategory.DRUG) }
    var allergen by remember { mutableStateOf("") }
    var reaction by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Allergies")
        ExistingItemsList(items)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AllergyCategory.entries.forEach { option ->
                FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option.label()) })
            }
        }
        OutlinedTextField(value = allergen, onValueChange = { allergen = it }, label = { Text("Allergen") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = reaction, onValueChange = { reaction = it }, label = { Text("Reaction type (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = {
                if (allergen.isNotBlank()) {
                    onAdd(category, allergen, reaction.ifBlank { null })
                    allergen = ""; reaction = ""
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Add", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun FamilyHistorySection(items: List<String>, onAdd: (String, String?) -> Unit) {
    var condition by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Family medical history")
        ExistingItemsList(items)
        OutlinedTextField(value = condition, onValueChange = { condition = it }, label = { Text("Condition") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = relation, onValueChange = { relation = it }, label = { Text("Relation (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = {
                if (condition.isNotBlank()) {
                    onAdd(condition, relation.ifBlank { null })
                    condition = ""; relation = ""
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Add", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun SocialHistorySection(
    onSave: (String?, String?, String?, String?, String?, String?) -> Unit,
) {
    var occupation by remember { mutableStateOf("") }
    var tobacco by remember { mutableStateOf("") }
    var alcohol by remember { mutableStateOf("") }
    var drugs by remember { mutableStateOf("") }
    var exposure by remember { mutableStateOf("") }
    var travel by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Family & social history")
        OutlinedTextField(value = occupation, onValueChange = { occupation = it }, label = { Text("Occupation") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tobacco, onValueChange = { tobacco = it }, label = { Text("Tobacco use") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = alcohol, onValueChange = { alcohol = it }, label = { Text("Alcohol use") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = drugs, onValueChange = { drugs = it }, label = { Text("Recreational drug use") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = exposure, onValueChange = { exposure = it }, label = { Text("Environmental exposure") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = travel, onValueChange = { travel = it }, label = { Text("Recent travel") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = {
                onSave(
                    occupation.ifBlank { null }, tobacco.ifBlank { null }, alcohol.ifBlank { null },
                    drugs.ifBlank { null }, exposure.ifBlank { null }, travel.ifBlank { null },
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Save social history", style = MaterialTheme.typography.titleMedium) }
    }
}

private fun MedicalHistoryCategory.label() = name.lowercase().replace('_', ' ')
private fun MedicationKind.label() = name.lowercase().replace('_', ' ')
private fun AllergyCategory.label() = name.lowercase().replace('_', ' ')
