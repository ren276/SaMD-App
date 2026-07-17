package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DoctorRepository
import com.example.samdapp.domain.repository.EncounterRepository
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
        val leastBusy = activeDoctors
            .sortedBy { it.name }
            .minByOrNull { caseRecordRepository.observeOpenCaseCount(it.id).first() }
            ?: return Result.failure(IllegalStateException("No active doctors available for auto-assignment"))
        return Result.success(DoctorAssignmentProposal(leastBusy, isContinuity = false))
    }

    /** The narrow "switch doctor" choice on the continuity confirm screen — same specialty as
     *  the proposed doctor only, not the full roster (keeps an informed choice, not a re-opened
     *  blind pick). */
    suspend fun sameSpecialtyAlternatives(specialty: String, excludingDoctorId: String): Result<List<Doctor>> =
        doctorRepository.getDoctors().map { doctors ->
            doctors.filter { it.specialty == specialty && it.available && it.id != excludingDoctorId }
        }
}
