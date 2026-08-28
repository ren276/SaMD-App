package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.PatientDirectoryEntry
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Streams the last 7 days' directory (bounded window, not a full patient mirror - see
 * [PatientRepository.observeRegisteredOrSeenRecently]) for the Patients tab's searchable list.
 * Unlike Home's roster, includes a registered patient with no encounter yet (nullable
 * [PatientDirectoryEntry.lastSeenAt]).
 */
class GetRecentPatientsUseCase @Inject constructor(
    private val patientRepository: PatientRepository,
) {
    operator fun invoke(): Flow<List<PatientDirectoryEntry>> = patientRepository.observeRegisteredOrSeenRecently()
}
