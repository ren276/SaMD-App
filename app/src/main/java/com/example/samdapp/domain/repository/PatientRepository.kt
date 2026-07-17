package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Patient
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
     * Today + the [days] before it (inclusive), by encounter start — a bounded window, not the
     * full patient table. Same data-minimization constraint as [observeTodaysPatients]
     * (agent_docs/hardening.md): this just widens the day-scoped query the DAO already exposes,
     * it does not add an "all patients" query. Backs the Patients tab's "today's + recent"
     * roster.
     */
    fun observeRecentPatients(days: Int = 7): Flow<List<Patient>>
}
