package com.example.samdapp.presentation.patientsummary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import com.example.samdapp.presentation.common.SamdLoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationChain
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.domain.model.TRAINED_ICD_CANDIDATES
import com.example.samdapp.presentation.common.DropdownField
import com.example.samdapp.presentation.common.LowResourceWarningDialog
import com.example.samdapp.presentation.common.deviceResourceWarnings
import com.example.samdapp.presentation.common.historyLabel
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PatientSummaryScreen(
    patientId: String,
    onStartConsultation: (patientId: String, followUpOfEncounterId: String?) -> Unit,
    onViewReport: (caseRecordId: String) -> Unit,
    onContinueToDoctorAssignment: (caseRecordId: String) -> Unit,
    onOpenChain: (patientId: String, rootEncounterId: String) -> Unit,
    onOpenAuditTrail: (patientId: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    viewModel: PatientSummaryViewModel = hiltViewModel<PatientSummaryViewModel, PatientSummaryViewModel.Factory>(
        creationCallback = { factory -> factory.create(patientId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFollowUpDialog by remember { mutableStateOf(false) }
    var resourceWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

    fun beginConsultationFlow() {
        if (uiState.history.isNotEmpty()) showFollowUpDialog = true else onStartConsultation(patientId, null)
    }

    if (resourceWarnings.isNotEmpty()) {
        LowResourceWarningDialog(
            warnings = resourceWarnings,
            onDismiss = { resourceWarnings = emptyList() },
            onContinueAnyway = {
                resourceWarnings = emptyList()
                beginConsultationFlow()
            },
        )
    }

    if (showFollowUpDialog) {
        FollowUpPickerDialog(
            history = uiState.history,
            onDismiss = { showFollowUpDialog = false },
            onPick = { followUpOfEncounterId ->
                showFollowUpDialog = false
                onStartConsultation(patientId, followUpOfEncounterId)
            },
        )
    }

    Scaffold(bottomBar = bottomBar) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isLoading || uiState.patient == null) {
                SamdLoadingIndicator()
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

            if (FeatureFlags.PATIENT_AUDIT_ENABLED) {
                OutlinedButton(
                    onClick = { onOpenAuditTrail(patientId) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Who has seen your file") }
            }

            Button(
                onClick = {
                    val warnings = if (FeatureFlags.DEVICE_RESOURCE_CHECK_ENABLED) deviceResourceWarnings(context) else emptyList()
                    if (warnings.isEmpty()) beginConsultationFlow() else resourceWarnings = warnings
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(1f).padding(top = 32.dp),
            ) {
                Text("Consultation", style = MaterialTheme.typography.titleMedium)
            }

            // Case is saved on-device only, no doctor assigned yet — this is the one action that
            // moves it out of local-only storage and into the doctor's queue.
            if (uiState.caseStatus == CaseStatus.SAVED_LOCALLY && uiState.caseRecordId != null) {
                Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Case saved locally — doctor not yet assigned",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Button(
                        onClick = { onContinueToDoctorAssignment(uiState.caseRecordId!!) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Continue — choose doctor") }
                }
            }

            // Doctor assigned but no network at the time — queued locally, sent automatically the
            // next time the worker taps Sync Up on Home while online (offline-first send path).
            if (uiState.caseStatus == CaseStatus.PENDING_SYNC) {
                Column(modifier = Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Case queued — will send when back online",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Tap Sync Up on Home once you have network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Doctor's review — for this demo, the reviewer decides AGREE/MODIFY/REJECT right here
            // (mirrors refine_diagnosis.py's DiagnosisFeedback schema) instead of a randomly-mocked
            // async channel response.
            if (uiState.caseStatus == CaseStatus.SENT_TO_DOCTOR || uiState.caseStatus == CaseStatus.PRESCRIPTION_RECEIVED) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (uiState.caseStatus == CaseStatus.PRESCRIPTION_RECEIVED) {
                            "Doctor's review received"
                        } else {
                            "Awaiting doctor's review"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (uiState.caseStatus == CaseStatus.SENT_TO_DOCTOR) {
                        if (uiState.showDoctorReviewPicker) {
                            DoctorReviewCard(uiState, viewModel)
                        } else {
                            OutlinedButton(
                                onClick = viewModel::onOpenDoctorReviewPicker,
                                enabled = uiState.canOpenDoctorReview,
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Review AI diagnosis (doctor)") }
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

            Text(
                text = "Consultation History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp),
            )
            ConsultationHistorySection(
                isLoading = uiState.isLoadingHistory,
                chains = uiState.chains,
                onOpenReport = onViewReport,
                onOpenChain = { rootEncounterId -> onOpenChain(patientId, rootEncounterId) },
            )
        }
    }
}

/**
 * Investor-demo capture point for `refine_diagnosis.py`'s `DiagnosisFeedback` schema — the
 * doctor-sided AGREE/MODIFY/REJECT decision on the AI kernel's diagnosis, right where the worker
 * checks for the doctor's review. AGREE prescribes exactly what the kernel recommended (drug,
 * dosage, the Gemini-suggested top India brand); MODIFY/REJECT let the reviewer write their own
 * drug/dosage/brand (with a one-tap brand lookup) before generating the report.
 */
@Composable
private fun DoctorReviewCard(uiState: PatientSummaryUiState, actions: PatientSummaryActions) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Physician Review", style = MaterialTheme.typography.titleMedium)
            if (uiState.isAssessmentNotReal) {
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        when (uiState.kernelInferenceSource) {
                            InferenceSource.MOCK_FALLBACK ->
                                "⚠ This case's AI assessment used the offline mock fallback — not real inference. Review accordingly."
                            InferenceSource.UNAVAILABLE ->
                                "⚠ No AI assessment was generated for this case — the server was unreachable."
                            else -> "⚠ This case's AI assessment was not real inference."
                        },
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            uiState.evaluateOutput?.diagnosticSummary?.primaryAilmentName?.let {
                Text("AI diagnosis: $it", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhysicianDecision.entries.forEach { decision ->
                    FilterChip(
                        selected = uiState.selectedDecision == decision,
                        onClick = { actions.onDecisionSelected(decision) },
                        label = { Text(decision.name) },
                    )
                }
            }
            when (uiState.selectedDecision) {
                PhysicianDecision.AGREE -> {
                    val treatment = uiState.evaluateOutput?.nlemTreatment
                    Text(
                        "Will prescribe: ${treatment?.recommendedDrug ?: "No drug recommendation"}" +
                            (treatment?.dosageForms?.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString(", ")})" } ?: "") +
                            (uiState.evaluateOutput?.topIndianBrand?.let { " — brand: ${it.displayName}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                PhysicianDecision.MODIFY -> {
                    // Corrected-diagnosis picker + clinical note are entirely independent of the
                    // prescription fields below — these feed DiagnosisFeedback (the future
                    // training-reimport record), the prescription fields feed MedicationLine.
                    DropdownField(
                        label = "Corrected diagnosis (must be one of the trained classes)",
                        value = TRAINED_ICD_CANDIDATES.firstOrNull { it.icdCode == uiState.correctedIcdCandidate }?.label.orEmpty(),
                        options = TRAINED_ICD_CANDIDATES.map { it.label },
                        onValueChange = { label ->
                            TRAINED_ICD_CANDIDATES.firstOrNull { it.label == label }
                                ?.let { actions.onCorrectedIcdCandidateSelected(it.icdCode) }
                        },
                    )
                    OutlinedTextField(
                        value = uiState.clinicalNoteText,
                        onValueChange = actions::onClinicalNoteChange,
                        label = { Text("Clinical note (audit only, not used for retraining)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ManualPrescriptionFields(uiState, actions)
                }
                PhysicianDecision.REJECT -> ManualPrescriptionFields(uiState, actions)
                null -> Unit
            }
            uiState.selectedDecision?.let { decision ->
                Text(
                    decision.outcomeExplanation(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = actions::onConfirmDoctorDecision,
                enabled = uiState.canConfirmDecision && !uiState.isSubmittingDecision,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (uiState.isSubmittingDecision) "Generating report…" else "Confirm & generate report") }
        }
    }
}

/** Drug/dosage/brand — the prescription (MedicationLine) fields, shared by MODIFY and REJECT.
 *  Never feeds DiagnosisFeedback/training data — see [PatientSummaryScreen] MODIFY branch. */
@Composable
private fun ManualPrescriptionFields(uiState: PatientSummaryUiState, actions: PatientSummaryActions) {
    OutlinedTextField(
        value = uiState.manualDrugName,
        onValueChange = actions::onManualDrugNameChange,
        label = { Text("Drug name") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.manualDosage,
        onValueChange = actions::onManualDosageChange,
        label = { Text("Dosage") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = uiState.manualBrandName,
            onValueChange = actions::onManualBrandNameChange,
            label = { Text("Brand name") },
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = actions::onLookupBrand,
            enabled = uiState.manualDrugName.isNotBlank() && !uiState.isLookingUpBrand,
        ) { Text(if (uiState.isLookingUpBrand) "…" else "Get brand") }
    }
}

@Composable
private fun ConsultationHistorySection(
    isLoading: Boolean,
    chains: List<ConsultationChain>,
    onOpenReport: (caseRecordId: String) -> Unit,
    onOpenChain: (rootEncounterId: String) -> Unit,
) {
    when {
        isLoading -> SamdLoadingIndicator(modifier = Modifier.padding(16.dp))
        chains.isEmpty() -> Text(
            text = "No prior visits.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        else -> Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chains.forEach { chain -> ConsultationChainRow(chain, onOpenReport, onOpenChain) }
        }
    }
}

/**
 * One row per follow-up chain, represented by its latest visit. A single-visit chain opens that
 * visit's report directly; a multi-visit chain shows a "N visits" badge and opens the chain-detail
 * screen (each visit's own report, never merged).
 */
@Composable
private fun ConsultationChainRow(
    chain: ConsultationChain,
    onOpenReport: (caseRecordId: String) -> Unit,
    onOpenChain: (rootEncounterId: String) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy, hh:mm a").withZone(ZoneId.systemDefault()) }
    val latest = chain.latest
    val isMultiVisit = chain.visitCount > 1
    val clickable = isMultiVisit || latest.caseRecordId != null
    Card(
        modifier = Modifier.fillMaxWidth().let {
            if (!clickable) {
                it
            } else if (isMultiVisit) {
                it.clickable { onOpenChain(chain.rootEncounterId) }
            } else {
                it.clickable { onOpenReport(latest.caseRecordId!!) }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = latest.chiefComplaint ?: "Incomplete visit — no consultation recorded",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isMultiVisit) {
                    Text(
                        text = "${chain.visitCount} visits",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            DoctorInLoopLine(latest.doctorName, latest.doctorSpecialty)
            Text(
                text = latest.caseStatus?.historyLabel() ?: "No case record",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatter.format(latest.visitDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Dr. X · Dept" line for history/tracker rows — or a muted "Not yet assigned" before send. */
@Composable
private fun DoctorInLoopLine(doctorName: String?, doctorSpecialty: String?) {
    if (doctorName != null) {
        Text(
            text = "$doctorName" + (doctorSpecialty?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        Text(
            text = "Doctor not yet assigned",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FollowUpPickerDialog(
    history: List<ConsultationHistoryEntry>,
    onDismiss: () -> Unit,
    onPick: (followUpOfEncounterId: String?) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Is this a follow-up visit?") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(history, key = { it.encounterId }) { entry ->
                    TextButton(onClick = { onPick(entry.encounterId) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${formatter.format(entry.visitDate)} — ${entry.chiefComplaint ?: "No concern recorded"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(null) }) { Text("Not a follow-up") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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

