package com.example.samdapp.presentation.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.assessment.AssessmentQueueScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Logger

sealed interface SendingEffect {
    data class Done(val caseRecordId: String, val consultationId: String, val audioUri: String?) : SendingEffect
}

data class SendingUiState(val enqueueFailed: Boolean = false)

/**
 * Async submission queue: the blocking kernel + evaluate calls this screen used to run inline
 * (this class's old init block) now live in `AssessmentRunner`, run by `AssessmentWorker` off a
 * single enqueue. This ViewModel's whole job is enqueue-then-[SendingEffect.Done]:
 * [SendingEffect.Done] fires the instant [AssessmentQueueScheduler.enqueueAssessment] returns,
 * not when the assessment itself finishes — `KernelAssessmentViewModel`'s collected Flow, not
 * this screen, is what shows the result landing.
 *
 * [AssessmentQueueScheduler.enqueueAssessment] is not expected to throw in normal operation
 * (WorkManager's own enqueue is a local, synchronous write), but a failed enqueue means no
 * assessment will ever run for this case — silently proceeding to [SendingEffect.Done] would be
 * worse than the blocking screen this replaces. A failure surfaces as
 * [SendingUiState.enqueueFailed] instead, with [retryEnqueue] as the remedy, rather than being
 * swallowed.
 */
@HiltViewModel(assistedFactory = SendingViewModel.Factory::class)
class SendingViewModel @AssistedInject constructor(
    @Assisted("caseRecordId") private val caseRecordId: String,
    @Assisted("consultationId") private val consultationId: String,
    @Assisted("audioUri") private val audioUri: String?,
    // Kept only for Factory/route-shape compatibility — the assessment itself now re-derives
    // encounterId from caseRecordId (AssessmentRunner), same as the old retry use case did.
    @Assisted("encounterId") encounterId: String,
    private val assessmentQueueScheduler: AssessmentQueueScheduler,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("caseRecordId") caseRecordId: String,
            @Assisted("consultationId") consultationId: String,
            @Assisted("audioUri") audioUri: String?,
            @Assisted("encounterId") encounterId: String,
        ): SendingViewModel
    }

    private val _effects = Channel<SendingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val _uiState = MutableStateFlow(SendingUiState())
    val uiState: StateFlow<SendingUiState> = _uiState.asStateFlow()

    init {
        enqueueAndProceed()
    }

    fun retryEnqueue() {
        _uiState.update { it.copy(enqueueFailed = false) }
        enqueueAndProceed()
    }

    private fun enqueueAndProceed() {
        viewModelScope.launch {
            try {
                assessmentQueueScheduler.enqueueAssessment(caseRecordId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warning("Could not enqueue assessment for case $caseRecordId: ${e.message}")
                _uiState.update { it.copy(enqueueFailed = true) }
                return@launch
            }
            _effects.send(SendingEffect.Done(caseRecordId, consultationId, audioUri))
        }
    }

    private companion object {
        val logger = Logger.getLogger("SendingViewModel")
    }
}
