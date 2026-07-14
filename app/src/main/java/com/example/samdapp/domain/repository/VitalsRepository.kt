package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.VitalsSnapshot
import kotlinx.coroutines.flow.Flow

interface VitalsRepository {
    suspend fun saveVitals(snapshot: VitalsSnapshot): Result<Unit>
    fun observeLatestForEncounter(encounterId: String): Flow<VitalsSnapshot?>
}
