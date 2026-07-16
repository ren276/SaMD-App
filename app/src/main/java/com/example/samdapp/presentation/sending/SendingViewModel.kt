package com.example.samdapp.presentation.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.toVitalsReading
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import com.example.samdapp.domain.usecase.SendToKernelUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    @Assisted("encounterId") private val encounterId: String,
    private val vitalsRepository: VitalsRepository,
    private val consultationRepository: ConsultationRepository,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val generateKernelReportUseCase: GenerateKernelReportUseCase,
    private val auditLogger: AuditLogger,
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

    init {
        viewModelScope.launch {
            // Fetched by encounterId, not passed a Patient — see SendToKernelUseCase KDoc for
            // the structural pseudonymization boundary this enforces.
            val vitals = vitalsRepository.observeLatestForEncounter(encounterId).first()?.toVitalsReading()
                ?: VitalsReading()
            val consultation = consultationRepository.observeForEncounter(encounterId).filterNotNull().first()

            val payload = sendToKernelUseCase(vitals = vitals, consultation = consultation, caseToken = caseRecordId)
                .getOrNull()

            if (payload != null) {
                generateKernelReportUseCase(caseRecordId, payload)
            }

            auditLogger.log(
                action = "kernel_response_received",
                caseRecordId = caseRecordId,
                payload = auditPayload("consultationId" to consultationId),
            )
            _effects.send(SendingEffect.Done(caseRecordId, consultationId, audioUri))
        }
    }
}
