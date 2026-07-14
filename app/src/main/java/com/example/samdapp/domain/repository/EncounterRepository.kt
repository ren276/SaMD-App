package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Encounter
import kotlinx.coroutines.flow.Flow

interface EncounterRepository {
    suspend fun startEncounter(patientId: String): Result<Encounter>
    fun observeEncounter(encounterId: String): Flow<Encounter?>
}
