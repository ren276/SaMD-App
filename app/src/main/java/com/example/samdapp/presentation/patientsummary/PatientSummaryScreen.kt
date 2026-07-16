package com.example.samdapp.presentation.patientsummary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Patient
import java.time.LocalDate
import java.time.Period

@Composable
fun PatientSummaryScreen(
    patientId: String,
    onStartConsultation: (patientId: String) -> Unit,
    onViewReport: (caseRecordId: String) -> Unit,
    viewModel: PatientSummaryViewModel = hiltViewModel<PatientSummaryViewModel, PatientSummaryViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
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
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(1f).padding(top = 32.dp),
            ) {
                Text("Consultation", style = MaterialTheme.typography.titleMedium)
            }

            // Doctor's own review happens on a separate channel (out of scope here) — this is
            // where the worker checks whether that channel has produced a response yet.
            if (uiState.caseStatus == CaseStatus.SENT_TO_DOCTOR || uiState.caseStatus == CaseStatus.PRESCRIPTION_RECEIVED) {
                Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (uiState.caseStatus == CaseStatus.PRESCRIPTION_RECEIVED) {
                            "Doctor's response received"
                        } else {
                            "Awaiting doctor's response"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (uiState.caseStatus == CaseStatus.SENT_TO_DOCTOR) {
                        OutlinedButton(
                            onClick = viewModel::onCheckForDoctorResponse,
                            enabled = uiState.canCheckForDoctorResponse,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(if (uiState.isCheckingForResponse) "Checking…" else "Check for doctor's response (mock)")
                        }
                        if (uiState.noResponseYet) {
                            Text(
                                "No response yet — check again later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (uiState.canViewReport) {
                OutlinedButton(
                    onClick = { onViewReport(uiState.caseRecordId!!) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("View report") }
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
