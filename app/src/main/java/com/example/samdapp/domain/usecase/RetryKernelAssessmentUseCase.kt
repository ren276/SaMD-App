package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.toVitalsReading
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.VitalsRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Re-runs [GenerateKernelReportUseCase] for a case whose assessment came back
 * [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE] — the "retry" affordance on
 * `KernelAssessmentScreen`'s unavailable state. Rebuilds the same [com.example.samdapp.domain.model.KernelPayload]
 * [com.example.samdapp.presentation.sending.SendingViewModel] originally built, purely from
 * [caseRecordId] (via [CaseRecordRepository.observeCaseRecord] for the encounter id), so the
 * retry button on the assessment screen doesn't need the encounter id threaded through the nav
 * route.
 */
class RetryKernelAssessmentUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val vitalsRepository: VitalsRepository,
    private val consultationRepository: ConsultationRepository,
    private val encounterRepository: EncounterRepository,
    private val patientRepository: PatientRepository,
    private val sendToKernelUseCase: SendToKernelUseCase,
    private val generateKernelReportUseCase: GenerateKernelReportUseCase,
) {
    suspend operator fun invoke(caseRecordId: String): Result<KernelReportOutput> {
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first()
            ?: return Result.failure(IllegalStateException("No case record for id $caseRecordId"))
        val encounterId = caseRecord.encounterId

        val vitals = vitalsRepository.observeLatestForEncounter(encounterId).first()?.toVitalsReading()
            ?: com.example.samdapp.domain.model.VitalsReading()
        val consultation = consultationRepository.observeForEncounter(encounterId).filterNotNull().first()
        val encounter = encounterRepository.observeEncounter(encounterId).first()
        val patient = encounter?.patientId?.let { patientRepository.observePatient(it).first() }

        val payload = sendToKernelUseCase(vitals = vitals, consultation = consultation, caseToken = caseRecordId)
            .getOrNull()
            ?: return Result.failure(IllegalStateException("Unable to rebuild kernel payload for retry"))

        return generateKernelReportUseCase(
            caseRecordId = caseRecordId,
            payload = payload,
            patientAge = patient?.age,
            patientSex = patient?.biologicalSex,
        )
    }
}
