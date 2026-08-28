package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.PatientDirectoryEntry
import kotlinx.coroutines.flow.Flow

interface PatientRepository {
    suspend fun register(patient: Patient): Result<Unit>
    fun observePatient(patientId: String): Flow<Patient?>

    /**
     * Today's roster: patients seen (encounter started) during the current local day.
     * The date window is resolved from the device clock/zone when collection begins.
     */
    fun observeTodaysPatients(): Flow<List<Patient>>

    /**
     * Today + the [days] before it (inclusive): patients registered in the window OR seen
     * (encounter started) in the window, whichever is later. A bounded window, not the full
     * patient table - same data-minimization constraint as [observeTodaysPatients]
     * (agent_docs/hardening.md), just resolved on registration time when there is no encounter
     * yet, instead of requiring one. Backs the Patients tab's directory: unlike
     * [observeTodaysPatients], a registered-but-unseen patient is included here (with a null
     * [PatientDirectoryEntry.lastSeenAt]) so a worker can find a patient they registered and
     * have not yet started a visit for.
     */
    fun observeRegisteredOrSeenRecently(days: Int = 7): Flow<List<PatientDirectoryEntry>>
}
