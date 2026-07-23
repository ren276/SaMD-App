package com.example.samdapp.presentation.sending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.toVitalsReading
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.usecase.GenerateEvaluateReportUseCase
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import com.example.samdapp.domain.usecase.SendToKernelUseCase
import com.google.gson.Gson
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
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val generateKernelReportUseCase: GenerateKernelReportUseCase,
    private val generateEvaluateReportUseCase: GenerateEvaluateReportUseCase,
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
    private val gson = Gson()

    init {
        viewModelScope.launch {
            // Fetched by encounterId, not passed a Patient — see SendToKernelUseCase KDoc for
            // the structural pseudonymization boundary this enforces.
            val vitals = vitalsRepository.observeLatestForEncounter(encounterId).first()?.toVitalsReading()
                ?: VitalsReading()
            val consultation = consultationRepository.observeForEncounter(encounterId).filterNotNull().first()

            // Resolve age + sex for the XGBoost classifier — these are clinical signals, not PII.
            // The encounter → patient lookup is a one-hop read; null is safe (the use case
            // uses safe defaults when age/sex are unavailable).
            val encounter = encounterRepository.observeEncounter(encounterId).first()
            val patient = encounter?.patientId?.let { patientRepository.observePatient(it).first() }
            val patientAge = patient?.age
            val patientSex = patient?.biologicalSex

            val payload = sendToKernelUseCase(vitals = vitals, consultation = consultation, caseToken = caseRecordId)
                .getOrNull()

            val kernelResult = if (payload != null) {
                generateKernelReportUseCase(
                    caseRecordId = caseRecordId,
                    payload = payload,
                    patientAge = patientAge,
                    patientSex = patientSex,
                )
            } else {
                null
            }

            // Fire alongside the /v1/assess call above — a distinct clinical concern (NLEM
            // treatment/brand-mapping/vitals-triage), no mock fallback, failure just means the
            // report screen omits that section (see GenerateEvaluateReportUseCase KDoc).
            val evaluateResult = if (payload != null) {
                generateEvaluateReportUseCase(
                    caseRecordId = caseRecordId,
                    payload = payload,
                    patientAge = patientAge,
                    patientSex = patientSex,
                )
            } else {
                null
            }

            // Full raw backend response dump (inference start/end, diagnostic summary, treatment,
            // safety/triage) into the audit trail — distinct from the curated subset the report/
            // prescription page actually renders (see AuditAction.EVALUATE_RESPONSE_RECEIVED KDoc).
            evaluateResult?.onSuccess { output ->
                auditLogger.log(
                    action = AuditAction.EVALUATE_RESPONSE_RECEIVED,
                    patientId = patient?.id,
                    caseRecordId = caseRecordId,
                    payload = gson.toJson(output),
                )
            }?.onFailure { e ->
                auditLogger.log(
                    action = AuditAction.EVALUATE_RESPONSE_FAILED,
                    patientId = patient?.id,
                    caseRecordId = caseRecordId,
                    payload = auditPayload("error" to e.message),
                )
            }

            auditLogger.log(
                action = AuditAction.KERNEL_RESPONSE_RECEIVED,
                caseRecordId = caseRecordId,
                payload = auditPayload(
                    "consultationId" to consultationId,
                    "inferenceSource" to kernelResult?.getOrNull()?.inferenceSource?.name,
                ),
            )
            _effects.send(SendingEffect.Done(caseRecordId, consultationId, audioUri))
        }
    }
}
