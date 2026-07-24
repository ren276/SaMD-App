package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** [isContinuity] true = this is the same doctor who handled the prior visit this encounter is a
 *  follow-up to (attribution/empanelment — the patient keeps seeing the doctor who has context on
 *  them); false = a fresh/unrelated case, auto-assigned to whichever active doctor has the fewest
 *  currently-open cases (least-busy). */
data class DoctorAssignmentProposal(val doctor: Doctor, val isContinuity: Boolean)

/**
 * Part B's doctor-assignment logic — replaces the old worker-driven doctor picker. Grounded in
 * the "empanelment/attribution" pattern real EHR/telemedicine systems use: default to the same
 * provider on a follow-up (continuity of care), auto-assign (least-busy) when there's no prior
 * relationship to attribute to. See PROGRESS.md for the research this is based on.
 */
class ResolveDoctorAssignmentUseCase @Inject constructor(
    private val encounterRepository: EncounterRepository,
    private val caseRecordRepository: CaseRecordRepository,
    private val doctorRepository: DoctorRepository,
    private val kernelReportRepository: KernelReportRepository,
    private val evaluateReportRepository: EvaluateReportRepository,
) {
    suspend operator fun invoke(caseRecordId: String, encounterId: String): Result<DoctorAssignmentProposal> {
        val encounter = encounterRepository.observeEncounter(encounterId).first()
            ?: return Result.failure(IllegalStateException("Encounter $encounterId not found"))
        val doctors = doctorRepository.getDoctors().getOrElse { return Result.failure(it) }

        val continuityDoctor = encounter.followUpOfEncounterId?.let { priorEncounterId ->
            val priorDoctorId = caseRecordRepository.observeByEncounterId(priorEncounterId).first()?.assignedDoctorId
            priorDoctorId?.let { id -> doctors.firstOrNull { it.id == id && it.available } }
        }
        if (continuityDoctor != null) {
            return Result.success(DoctorAssignmentProposal(continuityDoctor, isContinuity = true))
        }

        val activeDoctors = doctors.filter { it.available }
        if (activeDoctors.isEmpty()) {
            return Result.failure(IllegalStateException("No active doctors available for auto-assignment"))
        }

        // REQ-DOC-MAP: Try to map AI Kernel prediction to a specialist department
        // When real ML is running, kernelReport.predictedCondition is a risk tier (e.g. "low_risk")
        // so we must read the primaryIcdCandidate from the evaluate report if available.
        val evaluateReport = evaluateReportRepository.getForCase(caseRecordId)
        val kernelReport = kernelReportRepository.getForCase(caseRecordId)
        
        // Prefer the actual name (e.g. "Type 2 diabetes mellitus") so it can be string-matched,
        // rather than the ICD code ("E11").
        val conditionString = evaluateReport?.diagnosticSummary?.primaryAilmentName
            ?: kernelReport?.predictedCondition

        val targetSpecialty = conditionString?.let { mapConditionToSpecialty(it) }

        val specialtyDoctors = if (targetSpecialty != null) {
            activeDoctors.filter { it.specialty == targetSpecialty }
        } else {
            emptyList()
        }

        val pool = specialtyDoctors.ifEmpty { activeDoctors }

        val leastBusy = pool
            .sortedBy { it.name }
            .minByOrNull { caseRecordRepository.observeOpenCaseCount(it.id).first() }
            ?: return Result.failure(IllegalStateException("No active doctors available for auto-assignment"))
        return Result.success(DoctorAssignmentProposal(leastBusy, isContinuity = false))
    }

    private fun mapConditionToSpecialty(condition: String): String? {
        val lower = condition.lowercase()
        return when {
            // High acuity / Emergency
            lower.contains("severe") || lower.contains("haemorrhagic") -> "Critical Care"

            // Cardiology
            lower.contains("chest pain") || lower.contains("tachycardia") || lower.contains("palpitation") -> "Cardiology"

            // Neurology
            lower.contains("stroke") || lower.contains("seizure") || lower.contains("fits") ||
            lower.contains("weakness") || lower.contains("numbness") || lower.contains("fainting") ||
            lower.contains("syncope") || lower.contains("dizziness") || lower.contains("vertigo") ||
            lower.contains("migraine") -> "Neurology"

            // Pulmonology
            lower.contains("asthma") || lower.contains("pneumonia") || lower.contains("tuberculosis") ||
            lower.contains("breathlessness") || lower.contains("shortness of breath") || 
            lower.contains("cough") || lower.contains("respiratory") -> "Pulmonology"

            // Orthopedics
            lower.contains("fracture") || lower.contains("osteoarthritis") || lower.contains("arthritis") ||
            lower.contains("back pain") || lower.contains("neck pain") || lower.contains("joint pain") -> "Orthopedics"

            // Gastroenterology
            lower.contains("jaundice") || lower.contains("constipation") || lower.contains("acidity") ||
            lower.contains("reflux") || lower.contains("heartburn") -> "Gastroenterology"

            // Urology
            lower.contains("kidney stone") || lower.contains("blood in urine") || lower.contains("prostate") -> "Urology"

            // Endocrinology
            lower.contains("diabetes") || lower.contains("thyroid") || lower.contains("obesity") || lower.contains("metabolic") -> "Endocrinology"

            // Psychiatry
            lower.contains("depression") || lower.contains("anxiety") || lower.contains("panic") || lower.contains("insomnia") -> "Psychiatry"

            // Gynecology
            lower.contains("pregnancy") || lower.contains("menstrual") || lower.contains("pcos") || lower.contains("infertility") -> "Gynecology"

            // Dermatology
            lower.contains("skin rash") || lower.contains("eczema") || lower.contains("psoriasis") || lower.contains("acne") -> "Dermatology"

            // ENT
            lower.contains("ear pain") || lower.contains("sore throat") || lower.contains("sinusitis") -> "ENT"

            // Ophthalmology
            lower.contains("eye pain") || lower.contains("blurred vision") -> "Ophthalmology"

            // Internal Medicine
            lower.contains("typhoid") || lower.contains("dengue") || lower.contains("malaria") ||
            lower.contains("chikungunya") || lower.contains("anemia") || lower.contains("anaemia") -> "Internal Medicine"

            // General Physician
            lower.contains("headache") || lower.contains("fever") || lower.contains("viral") || 
            lower.contains("hypertension") || lower.contains("white coat") || lower.contains("abdominal pain") || 
            lower.contains("vomiting") || lower.contains("diarrhea") || lower.contains("diarrhoea") || 
            lower.contains("gastroenteritis") || lower.contains("urinary") || lower.contains("uti") || 
            lower.contains("non-specific") || lower.contains("non specific") -> "General Physician"

            else -> null
        }
    }

    /** The narrow "switch doctor" choice on the continuity confirm screen — same specialty as
     *  the proposed doctor only, not the full roster (keeps an informed choice, not a re-opened
     *  blind pick). */
    suspend fun sameSpecialtyAlternatives(specialty: String, excludingDoctorId: String): Result<List<Doctor>> =
        doctorRepository.getDoctors().map { doctors ->
            doctors.filter { it.specialty == specialty && it.available && it.id != excludingDoctorId }
        }
}
