package com.example.samdapp.presentation.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.usecase.SendToKernelUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface SendingEffect {
    data class Done(val caseRecordId: String, val consultationId: String, val audioUri: String?) : SendingEffect
}

@HiltViewModel(assistedFactory = SendingViewModel.Factory::class)
class SendingViewModel @AssistedInject constructor(
    @Assisted("caseRecordId") private val caseRecordId: String,
    @Assisted("consultationId") private val consultationId: String,
    @Assisted("audioUri") private val audioUri: String?,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("caseRecordId") caseRecordId: String,
            @Assisted("consultationId") consultationId: String,
            @Assisted("audioUri") audioUri: String?,
        ): SendingViewModel
    }

    private val _effects = Channel<SendingEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            sendToKernelUseCase()
            auditLogger.log(
                action = "kernel_response_received",
                caseRecordId = caseRecordId,
                payload = auditPayload("consultationId" to consultationId),
            )
            _effects.send(SendingEffect.Done(caseRecordId, consultationId, audioUri))
        }
    }
}
