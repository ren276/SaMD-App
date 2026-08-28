package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.toVitalsReading
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.VitalsRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.logging.Logger
import javax.inject.Inject

/**
 * The single orchestrator for a case's kernel + evaluate assessment, run from
 * [com.example.samdapp.data.assessment.AssessmentWorker] (the async submission queue) for both a
 * case's first assessment and every retry — the only place this logic lives, so the two paths
 * cannot drift apart.
 *
 * Resolution (case record, vitals, consultation, encounter, patient) is deliberately STRICT:
 * missing vitals or a missing consultation means the assessment cannot honestly run, and [run]
 * collapses straight to [GenerateKernelReportUseCase.recordUnavailable] rather than assessing on
 * a silently-substituted empty reading — never fabricate a result from data that isn't there.
 */
class AssessmentRunner @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val vitalsRepository: VitalsRepository,
    private val consultationRepository: ConsultationRepository,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val generateKernelReportUseCase: GenerateKernelReportUseCase,
    private val generateEvaluateReportUseCase: GenerateEvaluateReportUseCase,
    private val auditLogger: AuditLogger,
) {
    private val gson = Gson()

    private data class Resolved(
        val payload: KernelPayload,
        val consultationId: String,
        val patientId: String?,
        val patientAge: Int?,
        val patientSex: String?,
    )

    suspend fun run(caseRecordId: String) {
        // The one catch in this class: it wraps resolution and payload build only (stage 1/2),
        // and converts both their strict null returns and any unexpected exception into the same
        // branch. Stage 3 (kernel) never throws and already falls through to its own unavailable
        // state internally; stage 4 (evaluate) already writes its own failure marker and must be
        // audited, not swallowed. Neither is wrapped here.
        val resolved = try {
            resolve(caseRecordId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warning("Assessment resolve/build failed for case $caseRecordId: ${e.message}")
            null
        }

        if (resolved == null) {
            generateKernelReportUseCase.recordUnavailable(caseRecordId)
            return
        }

        val kernelResult = generateKernelReportUseCase(
            caseRecordId = caseRecordId,
            payload = resolved.payload,
            patientAge = resolved.patientAge,
            patientSex = resolved.patientSex,
        )

        // Fired alongside the kernel call above — a distinct clinical concern (NLEM
        // treatment/brand-mapping/vitals-triage), no mock fallback, failure just means the report
        // screen omits that section (see GenerateEvaluateReportUseCase KDoc).
        val evaluateResult = generateEvaluateReportUseCase(
            caseRecordId = caseRecordId,
            payload = resolved.payload,
            patientAge = resolved.patientAge,
            patientSex = resolved.patientSex,
        )

        evaluateResult.onSuccess { output ->
            auditLogger.log(
                action = AuditAction.EVALUATE_RESPONSE_RECEIVED,
                patientId = resolved.patientId,
                caseRecordId = caseRecordId,
                payload = gson.toJson(output),
            )
        }.onFailure { e ->
            auditLogger.log(
                action = AuditAction.EVALUATE_RESPONSE_FAILED,
                patientId = resolved.patientId,
                caseRecordId = caseRecordId,
                payload = auditPayload("error" to e.message),
            )
        }

        auditLogger.log(
            action = AuditAction.KERNEL_RESPONSE_RECEIVED,
            caseRecordId = caseRecordId,
            payload = auditPayload(
                "consultationId" to resolved.consultationId,
                "inferenceSource" to kernelResult.getOrNull()?.inferenceSource?.name,
            ),
        )
    }

    /** Null means the assessment cannot honestly run: no case record, no vitals, no consultation,
     *  or [sendToKernelUseCase] could not build a payload. Encounter and patient are soft reads —
     *  a missing patient still assesses, using [GenerateKernelReportUseCase]'s age/sex defaults,
     *  same as the callers this replaces. */
    private suspend fun resolve(caseRecordId: String): Resolved? {
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first() ?: return null
        val encounterId = caseRecord.encounterId

        val vitals = vitalsRepository.observeLatestForEncounter(encounterId).first()?.toVitalsReading()
            ?: return null
        val consultation = consultationRepository.observeForEncounter(encounterId).first() ?: return null
        val encounter = encounterRepository.observeEncounter(encounterId).first()
        val patient = encounter?.patientId?.let { patientRepository.observePatient(it).first() }

        val payload = sendToKernelUseCase(vitals = vitals, consultation = consultation, caseToken = caseRecordId)
            .getOrNull() ?: return null

        return Resolved(
            payload = payload,
            consultationId = consultation.id,
            patientId = patient?.id,
            patientAge = patient?.age,
            patientSex = patient?.biologicalSex,
        )
    }

    private companion object {
        val logger = Logger.getLogger("AssessmentRunner")
    }
}
