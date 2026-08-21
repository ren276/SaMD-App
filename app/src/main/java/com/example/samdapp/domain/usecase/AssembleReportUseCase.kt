package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.domain.report.ReportAudience
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.repository.AbhaProfileRepository
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import com.example.samdapp.domain.repository.PatientRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import com.example.samdapp.domain.repository.VitalsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Gathers every piece a [ClinicalReport] needs for one case and hands them to [ReportFormatter].
 * Reads a single snapshot from each observing repository (`.first()`) — a report is a point-in-time
 * document, not a live view. Kernel output and prescription are absent on the preliminary path and
 * simply come back null; the same use case produces the final report once Phases 4/5 have written
 * them, satisfying "one report object, sections added progressively."
 */
class AssembleReportUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val abhaProfileRepository: AbhaProfileRepository,
    private val consultationRepository: ConsultationRepository,
    private val ailmentRepository: AilmentRepository,
    private val vitalsRepository: VitalsRepository,
    private val prescriptionRepository: PrescriptionRepository,
    private val kernelReportRepository: KernelReportRepository,
    private val evaluateReportRepository: EvaluateReportRepository,
    private val doctorRepository: DoctorRepository,
    private val reportFormatter: ReportFormatter,
) {
    suspend operator fun invoke(caseRecordId: String, audience: ReportAudience): Result<ClinicalReport> {
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first()
            ?: return Result.failure(NoSuchElementException("No case record $caseRecordId"))
        val patient = patientRepository.observePatient(caseRecord.patientId).first()
            ?: return Result.failure(NoSuchElementException("No patient ${caseRecord.patientId}"))

        val encounter = encounterRepository.observeEncounter(caseRecord.encounterId).first()
        val abhaProfile = patient.abhaNumber?.takeIf { it.isNotBlank() }?.let { abhaProfileRepository.getProfile(it) }
        val consultation = consultationRepository.observeForEncounter(caseRecord.encounterId).first()
        val ailments = ailmentRepository.observeForEncounter(caseRecord.encounterId).first()
        val vitals = vitalsRepository.observeLatestForEncounter(caseRecord.encounterId).first()
        val prescription = prescriptionRepository.getForCase(caseRecordId)
        val kernelOutput = kernelReportRepository.getForCase(caseRecordId)
        val evaluateOutput = evaluateReportRepository.getForCase(caseRecordId)
        val evaluateFailureCode = evaluateReportRepository.getFailureCodeForCase(caseRecordId)
        val prescribingDoctor = prescription?.doctorId?.let { docId ->
            doctorRepository.getDoctors().getOrNull()?.firstOrNull { it.id == docId }
        }

        return Result.success(
            reportFormatter.format(
                audience = audience,
                patient = patient,
                abhaProfile = abhaProfile,
                consultationChiefComplaint = consultation?.chiefComplaint.orEmpty(),
                ailments = ailments,
                vitals = vitals,
                consultationAttachments = consultation?.attachments.orEmpty(),
                consultationRecordNo = caseRecord.id,
                visitDateTime = encounter?.startedAt ?: caseRecord.createdAt,
                kernelOutput = kernelOutput,
                evaluateOutput = evaluateOutput,
                evaluateFailureCode = evaluateFailureCode,
                prescription = prescription,
                prescribingDoctor = prescribingDoctor,
            ),
        )
    }
}
