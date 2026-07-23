package com.example.samdapp.presentation.kernelassessment

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Unified view of "the AI assessment for this case" — sourced PRIMARILY from the real
 * `/api/v1/evaluate` output ([EvaluateReportOutput], no mock fallback of its own), falling back
 * to the old `/v1/assess`-derived [KernelReportOutput] (which DOES have a mock fallback) only
 * when no evaluate output exists for this case yet.
 */
data class AssessmentDisplay(
    val predictedCondition: String,
    val icdCode: String?,
    val confidencePercent: Int,
    val requiresHumanVerification: Boolean,
    val isMockFallback: Boolean,
    val sourceLabel: String,
    /** Per-candidate lines. Evaluate source: `"ICD (confidence%) — why"`. Kernel fallback: plain
     *  differential names (no per-candidate confidence/reasoning in that older contract shape). */
    val differentialLines: List<String>,
    val reasoningLines: List<String>,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
)

private fun EvaluateReportOutput.toDisplay(): AssessmentDisplay {
    val summary = diagnosticSummary
    val top = summary.differential.firstOrNull { it.icdCandidate == summary.primaryIcdCandidate }
        ?: summary.differential.firstOrNull()
    return AssessmentDisplay(
        predictedCondition = summary.primaryAilmentName ?: "No primary candidate",
        icdCode = summary.primaryIcdCandidate,
        confidencePercent = ((top?.adjustedConfidence ?: 0.0) * 100).toInt(),
        requiresHumanVerification = safetyAndTriage.requiresHumanReview,
        isMockFallback = false,
        sourceLabel = "Real-time AI inference (/api/v1/evaluate)",
        differentialLines = summary.differential.map {
            "${it.icdCandidate} (${(it.adjustedConfidence * 100).toInt()}%) — ${it.why}"
        },
        reasoningLines = listOfNotNull(top?.why),
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
    )
}

private fun KernelReportOutput.toDisplay(): AssessmentDisplay = AssessmentDisplay(
    predictedCondition = predictedCondition,
    icdCode = icdCode,
    confidencePercent = (confidenceScore * 100).toInt(),
    requiresHumanVerification = requiredHumanVerification,
    isMockFallback = inferenceSource == InferenceSource.MOCK_FALLBACK,
    sourceLabel = when (inferenceSource) {
        InferenceSource.REAL_INFERENCE -> "Real-time AI inference (/v1/assess)"
        InferenceSource.MOCK_FALLBACK -> "Offline fallback (mock) — ML server unavailable"
    },
    differentialLines = differentials,
    reasoningLines = listOf(reasoningSummary),
    evidenceFor = evidenceFor,
    evidenceAgainst = evidenceAgainst,
)

data class KernelAssessmentUiState(
    val isLoading: Boolean = true,
    val display: AssessmentDisplay? = null,
    val liabilityAcknowledged: Boolean = false,
) {
    val canContinue: Boolean get() = !isLoading && liabilityAcknowledged
}

sealed interface KernelAssessmentEffect {
    data object Continue : KernelAssessmentEffect
}

@Stable
interface KernelAssessmentActions {
    fun onLiabilityAcknowledgedChange(acknowledged: Boolean)
    fun onContinue()
}

/**
 * The "AI Assessment Panel" (REQ-HAN-07): confidence gauge + explainability + liability checkbox,
 * shown right after the kernel handoff, before the case moves on to transcription/save. Reads
 * what [com.example.samdapp.presentation.sending.SendingViewModel] already persisted via
 * [EvaluateReportRepository] (primary) / [KernelReportRepository] (fallback) — this screen doesn't
 * generate the assessment, it presents it.
 */
@HiltViewModel(assistedFactory = KernelAssessmentViewModel.Factory::class)
class KernelAssessmentViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
    private val evaluateReportRepository: EvaluateReportRepository,
    private val kernelReportRepository: KernelReportRepository,
    private val auditLogger: AuditLogger,
) : ViewModel(), KernelAssessmentActions {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): KernelAssessmentViewModel
    }

    private val _uiState = MutableStateFlow(KernelAssessmentUiState())
    val uiState: StateFlow<KernelAssessmentUiState> = _uiState.asStateFlow()

    private val _effects = Channel<KernelAssessmentEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val evaluateOutput = evaluateReportRepository.getForCase(caseRecordId)
            val kernelOutput = kernelReportRepository.getForCase(caseRecordId)
            val display = evaluateOutput?.toDisplay() ?: kernelOutput?.toDisplay()
            _uiState.update { it.copy(isLoading = false, display = display) }
        }
    }

    override fun onLiabilityAcknowledgedChange(acknowledged: Boolean) =
        _uiState.update { it.copy(liabilityAcknowledged = acknowledged) }

    override fun onContinue() {
        if (!_uiState.value.canContinue) return
        viewModelScope.launch {
            val display = _uiState.value.display
            auditLogger.log(
                action = AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED,
                caseRecordId = caseRecordId,
                payload = auditPayload(
                    "predictedCondition" to display?.predictedCondition,
                    "requiredHumanVerification" to display?.requiresHumanVerification?.toString(),
                    "isMockFallback" to display?.isMockFallback?.toString(),
                    "sourceLabel" to display?.sourceLabel,
                ),
            )
            _effects.send(KernelAssessmentEffect.Continue)
        }
    }
}
