package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The receiving half of the doctor's out-of-app review (REQ-RX-01/03) — the PHC worker calls this
 * to check whether the doctor's separate channel has a response yet. Returns `null` (success, not
 * failure) when nothing has arrived — that's the normal async wait state, not an error. On arrival,
 * persists via [PrescriptionRepository] (already read by [AssembleReportUseCase]/[com.example.
 * samdapp.domain.report.ReportFormatter], so the SAME report object gains its final section with
 * no further wiring) and flips the case to [com.example.samdapp.domain.model.CaseStatus.PRESCRIPTION_RECEIVED].
 */
class ReceiveDoctorPrescriptionUseCase @Inject constructor(
    private val inbox: DoctorPrescriptionInbox,
    private val caseRecordRepository: CaseRecordRepository,
    private val prescriptionRepository: PrescriptionRepository,
) {
    suspend operator fun invoke(caseRecordId: String, patientId: String, encounterId: String): Result<Prescription?> {
        val incoming = inbox.fetchPrescription(caseRecordId).getOrElse { return Result.failure(it) }
            ?: return Result.success(null)

        val prescription = Prescription(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            caseRecordId = caseRecordId,
            doctorId = incoming.doctorId,
            diagnosis = incoming.diagnosis,
            medications = incoming.medications,
            kernelDecision = incoming.kernelDecision,
            createdAt = Instant.now(),
        )
        prescriptionRepository.save(prescription).getOrElse { return Result.failure(it) }
        caseRecordRepository.markPrescriptionReceived(caseRecordId)
        return Result.success(prescription)
    }
}
