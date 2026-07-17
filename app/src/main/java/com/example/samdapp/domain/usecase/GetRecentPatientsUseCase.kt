package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Streams the last 7 days' roster (bounded window, not a full patient mirror — see
 * [PatientRepository.observeRecentPatients]) for the Patients tab's searchable list.
 */
class GetRecentPatientsUseCase @Inject constructor(
    private val patientRepository: PatientRepository,
) {
    operator fun invoke(): Flow<List<Patient>> = patientRepository.observeRecentPatients()
}
