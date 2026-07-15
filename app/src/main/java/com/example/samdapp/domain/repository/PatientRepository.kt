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
}
