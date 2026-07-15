package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Streams the patients seen during the current local day — the roster a PHC worker
 * resumes from. The device holds only this day-scoped slice, never a full mirror of a
 * growing patient database (agent_docs/hardening.md data-minimization).
 */
class GetTodaysPatientsUseCase @Inject constructor(
    private val patientRepository: PatientRepository,
) {
    operator fun invoke(): Flow<List<Patient>> = patientRepository.observeTodaysPatients()
}
