package com.example.samdapp.presentation.patientsummary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.model.Patient
import java.time.LocalDate
import java.time.Period

@Composable
fun PatientSummaryScreen(
    patientId: String,
    onStartConsultation: (patientId: String) -> Unit,
    viewModel: PatientSummaryViewModel = hiltViewModel<PatientSummaryViewModel, PatientSummaryViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (uiState.isLoading || uiState.patient == null) {
                CircularProgressIndicator()
                return@Column
            }
            val patient = uiState.patient!!

            Text(
                text = "Hello, ${patient.fullName}",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Ready to start the consultation for this patient.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryRow("Age", patient.displayAge())
                    SummaryRow("Biological sex", patient.biologicalSex)
                    patient.mobileNumber?.let { SummaryRow("Mobile", it) }
                    val address = listOfNotNull(patient.village, patient.block, patient.district).joinToString(", ")
                    if (address.isNotBlank()) SummaryRow("Address", address)
                    patient.bloodGroup?.let { SummaryRow("Blood group", it) }
                    patient.emergencyContact?.let { SummaryRow("Emergency contact", it) }
                }
            }

            Button(
                onClick = { onStartConsultation(patientId) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 32.dp),
            ) {
                Text("Start consultation", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Text(text = "$label: $value", style = MaterialTheme.typography.bodyLarge)
}

private fun Patient.displayAge(): String {
    dateOfBirth?.let { dob -> return "${Period.between(dob, LocalDate.now()).years} yrs" }
    age?.let { return "$it yrs" }
    return "Unknown"
}
