package com.example.samdapp.presentation.patientsummary

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.kernel.BrandLookupSource
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationChain
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.domain.model.groupIntoChains
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.usecase.SubmitDoctorDecisionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Investor-demo-facing explanation of what each decision means for the training pipeline — see
 *  `refine_diagnosis.py`'s `DiagnosisFeedback` schema docstring / [com.example.samdapp.domain.model.DiagnosisFeedback] KDoc. */
fun PhysicianDecision.outcomeExplanation(): String = when (this) {
    PhysicianDecision.AGREE ->
        "Confirmed correct. This case will be added to the training dataset as a confirmed " +
            "example — helps refine the model."
    PhysicianDecision.MODIFY ->
        "Corrected treatment captured. This becomes a NEW training example using the physician's " +
            "own prescription, not the AI's original candidate."
    PhysicianDecision.REJECT ->
        "Discarded. This case will NOT be used for retraining — there is no reliable ground truth " +
            "to trust once the AI's candidate is rejected outright."
}

/**
 * [caseRecordId]/[caseStatus] back the doctor-review flow (REQ-RX-01/03): the doctor's own review
 * happens on this device for the demo — AGREE/MODIFY/REJECT (mirrors `refine_diagnosis.py`'s
 * `DiagnosisFeedback` schema), which builds the final prescription and marks the case reviewed.
 */
data class PatientSummaryUiState(
    val patient: Patient? = null,
    val isLoading: Boolean = true,
    val caseRecordId: String? = null,
    val encounterId: String? = null,
    val caseStatus: CaseStatus? = null,
    val showDoctorReviewPicker: Boolean = false,
    val evaluateOutput: EvaluateReportOutput? = null,
    /** Kernel-mock production safety fix: the AI-assessment source behind the case the physician
     *  is about to AGREE/MODIFY/REJECT — null once evaluate output exists and is REAL_INFERENCE
     *  (nothing to flag), or MOCK_FALLBACK/UNAVAILABLE so the physician performs the safety gate
     *  with full knowledge the content wasn't real inference. The doctor must not decide blind. */
    val kernelInferenceSource: InferenceSource? = null,
    val selectedDecision: PhysicianDecision? = null,
    val manualDrugName: String = "",
    val manualDosage: String = "",
    val manualBrandName: String = "",
    val isLookingUpBrand: Boolean = false,
    val isSubmittingDecision: Boolean = false,
    /** MODIFY-only, independent of the prescription fields above — feeds [com.example.samdapp.domain.model.DiagnosisFeedback.physicianFinalDiagnosis]
     *  (a future training-reimport record), never [com.example.samdapp.domain.model.MedicationLine]. Must be
     *  one of [com.example.samdapp.domain.model.TRAINED_ICD_CANDIDATES] to actually be reimportable. */
    val correctedIcdCandidate: String? = null,
    /** MODIFY-only free-text audit note — captured for clinical record-keeping, never reimported. */
    val clinicalNoteText: String = "",
    /** Flat visit history, newest first — the source for the "mark as follow-up" picker (you follow
     *  up a specific prior visit, so this stays ungrouped). */
    val history: List<ConsultationHistoryEntry> = emptyList(),
    /** [history] grouped into follow-up chains — one entry per chain, represented by its latest
     *  visit. This is what Consultation History renders, so the list stays clean (one row per
     *  chain, not one per follow-up). */
    val chains: List<ConsultationChain> = emptyList(),
    val isLoadingHistory: Boolean = true,
) {
    val canOpenDoctorReview: Boolean get() = caseStatus == CaseStatus.SENT_TO_DOCTOR && !showDoctorReviewPicker
    val canViewReport: Boolean get() = caseRecordId != null
    /** True when [kernelInferenceSource] is anything other than real inference — the marker the
     *  physician review card renders. */
    val isAssessmentNotReal: Boolean
        get() = kernelInferenceSource != null && kernelInferenceSource != InferenceSource.REAL_INFERENCE
    val canConfirmDecision: Boolean
        get() = when (selectedDecision) {
            null -> false
            PhysicianDecision.AGREE -> true
            PhysicianDecision.MODIFY -> manualDrugName.isNotBlank() && manualDosage.isNotBlank() && correctedIcdCandidate != null
            PhysicianDecision.REJECT -> manualDrugName.isNotBlank() && manualDosage.isNotBlank()
        }
}

@Stable
interface PatientSummaryActions {
    fun onOpenDoctorReviewPicker()
    fun onDecisionSelected(decision: PhysicianDecision)
    fun onManualDrugNameChange(text: String)
    fun onManualDosageChange(text: String)
    fun onManualBrandNameChange(text: String)
    fun onLookupBrand()
    fun onCorrectedIcdCandidateSelected(icdCode: String)
    fun onClinicalNoteChange(text: String)
    fun onConfirmDoctorDecision()
}

@HiltViewModel(assistedFactory = PatientSummaryViewModel.Factory::class)
class PatientSummaryViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val patientRepository: PatientRepository,
    private val caseRecordRepository: CaseRecordRepository,
    private val encounterRepository: EncounterRepository,
    private val evaluateReportRepository: EvaluateReportRepository,
    private val kernelReportRepository: KernelReportRepository,
    private val brandLookupSource: BrandLookupSource,
    private val submitDoctorDecisionUseCase: SubmitDoctorDecisionUseCase,
) : ViewModel(), PatientSummaryActions {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): PatientSummaryViewModel
    }

    private val _uiState = MutableStateFlow(PatientSummaryUiState())
    val uiState: StateFlow<PatientSummaryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                patientRepository.observePatient(patientId),
                caseRecordRepository.observeLatestForPatient(patientId),
            ) { patient, caseRecord -> patient to caseRecord }
                .collect { (patient, caseRecord) ->
                    _uiState.update {
                        it.copy(
                            patient = patient,
                            isLoading = false,
                            caseRecordId = caseRecord?.id,
                            encounterId = caseRecord?.encounterId,
                            caseStatus = caseRecord?.status,
                        )
                    }
                }
        }
        viewModelScope.launch {
            encounterRepository.observeHistoryForPatient(patientId).collect { history ->
                _uiState.update { it.copy(history = history, chains = history.groupIntoChains(), isLoadingHistory = false) }
            }
        }
    }

    override fun onOpenDoctorReviewPicker() {
        val caseRecordId = _uiState.value.caseRecordId ?: return
        if (!_uiState.value.canOpenDoctorReview) return
        viewModelScope.launch {
            val evaluateOutput = evaluateReportRepository.getForCase(caseRecordId)
            val kernelInferenceSource = kernelReportRepository.getForCase(caseRecordId)?.inferenceSource
            _uiState.update {
                it.copy(
                    showDoctorReviewPicker = true,
                    evaluateOutput = evaluateOutput,
                    kernelInferenceSource = kernelInferenceSource,
                )
            }
        }
    }

    override fun onDecisionSelected(decision: PhysicianDecision) =
        _uiState.update { it.copy(selectedDecision = decision) }

    override fun onManualDrugNameChange(text: String) = _uiState.update { it.copy(manualDrugName = text) }
    override fun onManualDosageChange(text: String) = _uiState.update { it.copy(manualDosage = text) }
    override fun onManualBrandNameChange(text: String) = _uiState.update { it.copy(manualBrandName = text) }
    override fun onCorrectedIcdCandidateSelected(icdCode: String) =
        _uiState.update { it.copy(correctedIcdCandidate = icdCode) }
    override fun onClinicalNoteChange(text: String) = _uiState.update { it.copy(clinicalNoteText = text) }

    override fun onLookupBrand() {
        val drug = _uiState.value.manualDrugName.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLookingUpBrand = true) }
            val brand = brandLookupSource.lookupTopIndianBrand(drug)
            _uiState.update {
                it.copy(isLookingUpBrand = false, manualBrandName = brand?.displayName ?: it.manualBrandName)
            }
        }
    }

    override fun onConfirmDoctorDecision() {
        val state = _uiState.value
        val decision = state.selectedDecision ?: return
        val caseRecordId = state.caseRecordId ?: return
        val encounterId = state.encounterId ?: return
        if (!state.canConfirmDecision) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingDecision = true) }
            submitDoctorDecisionUseCase(
                caseRecordId = caseRecordId,
                patientId = patientId,
                encounterId = encounterId,
                decision = decision,
                manualDrugName = state.manualDrugName,
                manualDosage = state.manualDosage,
                manualBrandName = state.manualBrandName,
                correctedIcdCandidate = state.correctedIcdCandidate,
                clinicalNote = state.clinicalNoteText,
            )
            _uiState.update { it.copy(isSubmittingDecision = false, showDoctorReviewPicker = false) }
        }
    }
}
