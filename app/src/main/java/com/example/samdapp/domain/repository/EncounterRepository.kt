package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.Encounter
import kotlinx.coroutines.flow.Flow

interface EncounterRepository {
    suspend fun startEncounter(patientId: String, followUpOfEncounterId: String? = null): Result<Encounter>
    fun observeEncounter(encounterId: String): Flow<Encounter?>

    /** Every encounter for [patientId], most recent first, joined with its chief complaint and
     *  case status (both nullable — an abandoned encounter may have neither). Backs the
     *  Consultation History section on PatientSummary and the "mark as follow-up" picker. */
    fun observeHistoryForPatient(patientId: String): Flow<List<ConsultationHistoryEntry>>
}
