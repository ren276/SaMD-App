package com.example.samdapp.presentation.kernelassessment

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.assessment.AssessmentQueueScheduler
import com.example.samdapp.data.assessment.AssessmentWorkState
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** True when the real kernel call failed and this build had no fallback to offer
     *  ([InferenceSource.UNAVAILABLE] — always true in staging/prod on failure, since those
     *  flavors bind no mock). Distinguishes "the AI genuinely couldn't run" from a mock result,
     *  so the screen can show a retry affordance instead of a fabricated assessment. */
    val isUnavailable: Boolean,
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
        isUnavailable = false,
        sourceLabel = "Real-time AI inference (/api/v1/evaluate)",
        differentialLines = summary.differential.map {
            "${it.icdCandidate} (${(it.adjustedConfidence * 100).toInt()}%) — ${it.why}"
        },
        reasoningLines = listOfNotNull(top?.why),
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
    )
}

/** Shared with [KernelReportOutput.toDisplay]'s own [InferenceSource.UNAVAILABLE] branch below,
 *  so a stalled case (no row) and a written UNAVAILABLE row (a real one) can never read
 *  differently for what is the same clinical state. */
private const val UNAVAILABLE_SOURCE_LABEL = "Assessment unavailable: no AI result was produced"

/** Synthesized when no report row exists AND no assessment work is live for the case (stalled:
 *  enqueued work finished without ever writing a row, was cancelled, or was never enqueued).
 *  Deliberately identical in shape and wording to [KernelReportOutput]'s own
 *  [InferenceSource.UNAVAILABLE] rendering below — the same retryable state, not a second
 *  failure-looking one, for a case where not even a DB row exists to render it from. Shares its
 *  predictedCondition/reasoningLines text with [GenerateKernelReportUseCase]'s own written
 *  UNAVAILABLE row (that class's `UNAVAILABLE_PREDICTED_CONDITION`/`UNAVAILABLE_REASONING_SUMMARY`
 *  constants), not a second copy of the same wording. */
private fun stalledDisplay(): AssessmentDisplay = AssessmentDisplay(
    predictedCondition = GenerateKernelReportUseCase.UNAVAILABLE_PREDICTED_CONDITION,
    icdCode = null,
    confidencePercent = 0,
    requiresHumanVerification = true,
    isMockFallback = false,
    isUnavailable = true,
    sourceLabel = UNAVAILABLE_SOURCE_LABEL,
    differentialLines = emptyList(),
    reasoningLines = listOf(GenerateKernelReportUseCase.UNAVAILABLE_REASONING_SUMMARY),
    evidenceFor = emptyList(),
    evidenceAgainst = emptyList(),
)

private fun KernelReportOutput.toDisplay(): AssessmentDisplay = AssessmentDisplay(
    predictedCondition = predictedCondition,
    icdCode = icdCode,
    confidencePercent = (confidenceScore * 100).toInt(),
    requiresHumanVerification = requiredHumanVerification,
    isMockFallback = inferenceSource == InferenceSource.MOCK_FALLBACK,
    isUnavailable = inferenceSource == InferenceSource.UNAVAILABLE,
    sourceLabel = when (inferenceSource) {
        InferenceSource.REAL_INFERENCE -> "Real-time AI inference (/v1/assess)"
        InferenceSource.MOCK_FALLBACK -> "Offline fallback (mock) — ML server unavailable"
        // Reach-neutral: UNAVAILABLE covers both an unreachable kernel and one that answered
        // with an empty differential. Naming a cause here would be wrong half the time.
        InferenceSource.UNAVAILABLE -> UNAVAILABLE_SOURCE_LABEL
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
    val isRetrying: Boolean = false,
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
    fun onRetry()
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
    private val assessmentQueueScheduler: AssessmentQueueScheduler,
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
        // Collected, not one-shot: the async submission queue means no report row is guaranteed
        // to exist yet when this screen opens. workState tells apart "still processing" (show a
        // wait state) from "stalled" (no row, nothing running - offer the same retry affordance
        // an UNAVAILABLE row already offers, via stalledDisplay()).
        viewModelScope.launch {
            combine(
                evaluateReportRepository.observeForCase(caseRecordId),
                kernelReportRepository.observeForCase(caseRecordId),
                assessmentQueueScheduler.observeWorkState(caseRecordId),
            ) { evaluateOutput, kernelOutput, workState ->
                Triple(evaluateOutput, kernelOutput, workState)
            }.collect { (evaluateOutput, kernelOutput, workState) ->
                val reportDisplay = evaluateOutput?.toDisplay() ?: kernelOutput?.toDisplay()
                _uiState.update {
                    when {
                        reportDisplay != null -> it.copy(
                            isLoading = false,
                            display = reportDisplay,
                            isRetrying = workState != AssessmentWorkState.NONE,
                        )
                        workState != AssessmentWorkState.NONE -> it.copy(isLoading = true, display = null)
                        else -> it.copy(isLoading = false, display = stalledDisplay(), isRetrying = false)
                    }
                }
            }
        }
    }

    override fun onLiabilityAcknowledgedChange(acknowledged: Boolean) =
        _uiState.update { it.copy(liabilityAcknowledged = acknowledged) }

    /** Retry affordance on the UNAVAILABLE/stalled state: re-enqueues the same unique assessment
     *  work as the original send, via [assessmentQueueScheduler]. Fire-and-forget - the result
     *  reaches the screen through the collected Flow in [init], not through this call's return.
     *  May legitimately come back UNAVAILABLE again if the server is still unreachable; that's an
     *  honest result, not an error in this use case. */
    override fun onRetry() {
        if (_uiState.value.isRetrying) return
        assessmentQueueScheduler.enqueueAssessment(caseRecordId)
    }

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
