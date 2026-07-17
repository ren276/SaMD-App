package com.example.samdapp.presentation.kernelassessment

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.KernelReportOutput
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

data class KernelAssessmentUiState(
    val isLoading: Boolean = true,
    val output: KernelReportOutput? = null,
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
 * shown right after the mocked kernel handoff, before the case moves on to transcription/save.
 * Reads what [com.example.samdapp.presentation.sending.SendingViewModel] already persisted via
 * [KernelReportRepository] — this screen doesn't generate the assessment, it presents it.
 */
@HiltViewModel(assistedFactory = KernelAssessmentViewModel.Factory::class)
class KernelAssessmentViewModel @AssistedInject constructor(
    @Assisted private val caseRecordId: String,
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
            val output = kernelReportRepository.getForCase(caseRecordId)
            _uiState.update { it.copy(isLoading = false, output = output) }
        }
    }

    override fun onLiabilityAcknowledgedChange(acknowledged: Boolean) =
        _uiState.update { it.copy(liabilityAcknowledged = acknowledged) }

    override fun onContinue() {
        if (!_uiState.value.canContinue) return
        viewModelScope.launch {
            auditLogger.log(
                action = AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED,
                caseRecordId = caseRecordId,
                payload = auditPayload(
                    "predictedCondition" to _uiState.value.output?.predictedCondition,
                    "requiredHumanVerification" to _uiState.value.output?.requiredHumanVerification?.toString(),
                ),
            )
            _effects.send(KernelAssessmentEffect.Continue)
        }
    }
}
