package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.DiagnosisFeedback
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.TRAINED_ICD_CANDIDATES
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DiagnosisFeedbackRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.PrescriptionRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The interactive, in-app replacement for the old randomly-mocked [com.example.samdapp.data.doctor.MockDoctorPrescriptionInbox]
 * path: the person reviewing on the doctor's behalf picks AGREE/MODIFY/REJECT right here
 * (mirrors `refine_diagnosis.py`'s `DiagnosisFeedback` schema — see [DiagnosisFeedback] KDoc for
 * what each decision means for a future training-dataset reimport).
 *
 * - AGREE: prescribes exactly what the AI kernel recommended — drug, dosage, and the
 *   Gemini-suggested top India brand already computed by [GenerateEvaluateReportUseCase]
 *   ([com.example.samdapp.domain.model.EvaluateReportOutput.topIndianBrand]) — no re-lookup here.
 * - MODIFY / REJECT: the reviewer's own drug/dosage/brand (brand optionally looked up via
 *   [com.example.samdapp.domain.kernel.BrandLookupSource] at the UI layer) becomes the
 *   prescription. REJECT additionally means the AI's candidate is never eligible for dataset
 *   reimport — there's no reliable ground truth once it's rejected outright.
 *
 * [correctedIcdCandidate] and [clinicalNote] are entirely independent of the prescription fields
 * above — they feed [DiagnosisFeedback], the future-training-reimport record, not [MedicationLine].
 * [correctedIcdCandidate] is only stored (as [DiagnosisFeedback.physicianFinalDiagnosis]) when
 * [decision] is MODIFY AND it's one of [TRAINED_ICD_CANDIDATES]; anything else is silently dropped
 * from that field (never a drug/brand/company name, never free text — see [DiagnosisFeedback] KDoc).
 * [clinicalNote] is captured for audit purposes only and never touches [DiagnosisFeedback
 * .physicianFinalDiagnosis][DiagnosisFeedback] or any training reimport.
 *
 * [rejectReason] (H-17, Build 1) is REJECT-only and becomes [Prescription.diagnosis] — the
 * physician's own reasoning, surfaced verbatim on the worker-facing report rather than the fixed
 * fallback string, so the worker sees why nothing was prescribed instead of just that nothing was.
 */
class SubmitDoctorDecisionUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
    private val prescriptionRepository: PrescriptionRepository,
    private val evaluateReportRepository: EvaluateReportRepository,
    private val diagnosisFeedbackRepository: DiagnosisFeedbackRepository,
    private val auditLogger: AuditLogger,
) {
    companion object {
        private const val FALLBACK_DOCTOR_ID = "doc-gen-001"
    }

    suspend operator fun invoke(
        caseRecordId: String,
        patientId: String,
        encounterId: String,
        decision: PhysicianDecision,
        manualDrugName: String,
        manualDosage: String,
        manualBrandName: String,
        correctedIcdCandidate: String? = null,
        clinicalNote: String? = null,
        /** REJECT-only free-text reasoning, entered on the decision surface — flows verbatim to
         *  the worker-facing report as [Prescription.diagnosis] (H-17: "not blankness," the
         *  worker sees why, not just that nothing was prescribed). Falls back to the prior fixed
         *  string when blank. Ignored for AGREE/MODIFY. */
        rejectReason: String = "",
    ): Result<Prescription> = runCatching {
        val evaluateOutput = evaluateReportRepository.getForCase(caseRecordId)
        val treatment = evaluateOutput?.nlemTreatment
        val caseRecord = caseRecordRepository.observeCaseRecord(caseRecordId).first()

        val diagnosis = when (decision) {
            PhysicianDecision.REJECT -> rejectReason.takeIf { it.isNotBlank() }
                ?: "Clinical assessment pending further evaluation (AI suggestion not clinically supported)"
            else -> evaluateOutput?.diagnosticSummary?.primaryAilmentName
                ?: "Clinical assessment pending further evaluation"
        }

        val medication = when (decision) {
            PhysicianDecision.AGREE -> MedicationLine(
                genericName = treatment?.recommendedDrug ?: "As advised by physician",
                brandName = evaluateOutput?.topIndianBrand?.displayName,
                strength = treatment?.dosageForms?.firstOrNull() ?: "As per NLEM 2022",
                dosage = treatment?.pediatricDose ?: "As per NLEM 2022 guidance",
                frequency = "As advised by physician",
                route = "oral",
                duration = "As advised by physician",
                quantity = "As advised by physician",
                foodRelation = null,
                instructions = treatment?.referralReason,
            )
            PhysicianDecision.MODIFY, PhysicianDecision.REJECT -> MedicationLine(
                genericName = manualDrugName,
                brandName = manualBrandName.takeIf { it.isNotBlank() },
                strength = manualDosage,
                dosage = manualDosage,
                frequency = "As advised by physician",
                route = "oral",
                duration = "As advised by physician",
                quantity = "As advised by physician",
                foodRelation = null,
                instructions = null,
            )
        }

        val kernelDecision = when (decision) {
            PhysicianDecision.AGREE -> KernelDecision.AGREE
            PhysicianDecision.MODIFY -> KernelDecision.MODIFY
            PhysicianDecision.REJECT -> KernelDecision.REJECT
        }

        val prescription = Prescription(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            caseRecordId = caseRecordId,
            doctorId = caseRecord?.assignedDoctorId ?: FALLBACK_DOCTOR_ID,
            diagnosis = diagnosis,
            medications = listOf(medication),
            kernelDecision = kernelDecision,
            createdAt = Instant.now(),
        )
        prescriptionRepository.save(prescription).getOrThrow()
        caseRecordRepository.markPrescriptionReceived(caseRecordId)

        // Only a MODIFY correction that's actually one of the 18 trained classes is reimportable —
        // anything else (including a REJECT, which has no reliable ground truth by design) stays null.
        val finalDiagnosis = if (decision == PhysicianDecision.MODIFY) {
            correctedIcdCandidate?.takeIf { code -> TRAINED_ICD_CANDIDATES.any { it.icdCode == code } }
        } else {
            null
        }

        diagnosisFeedbackRepository.save(
            DiagnosisFeedback(
                id = UUID.randomUUID().toString(),
                caseRecordId = caseRecordId,
                icdCandidate = evaluateOutput?.diagnosticSummary?.primaryIcdCandidate ?: "",
                physicianDecision = decision,
                physicianFinalDiagnosis = finalDiagnosis,
                clinicalNote = clinicalNote?.takeIf { it.isNotBlank() },
                createdAt = Instant.now(),
            ),
        )
        auditLogger.log(
            action = AuditAction.DIAGNOSIS_FEEDBACK_RECORDED,
            patientId = patientId,
            caseRecordId = caseRecordId,
            payload = auditPayload(
                "icdCandidate" to (evaluateOutput?.diagnosticSummary?.primaryIcdCandidate),
                "physicianDecision" to decision.name,
                "physicianFinalDiagnosis" to finalDiagnosis,
                "reimportable" to (finalDiagnosis != null || decision == PhysicianDecision.AGREE).toString(),
                "medicationGenericName" to medication.genericName,
                "medicationBrandName" to medication.brandName,
            ),
        )
        auditLogger.log(
            action = AuditAction.PRESCRIPTION_APPROVED,
            patientId = patientId,
            caseRecordId = caseRecordId,
            payload = auditPayload(
                "kernelDecision" to kernelDecision.name,
                "prescriptionId" to prescription.id,
                "medicationCount" to prescription.medications.size.toString(),
            ),
        )

        prescription
    }
}
