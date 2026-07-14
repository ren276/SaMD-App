package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.CaseRecord
import kotlinx.coroutines.flow.Flow

interface CaseRecordRepository {
    suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord>
    suspend fun markSavedLocally(caseRecordId: String): Result<Unit>
    suspend fun assignDoctor(caseRecordId: String, doctorId: String): Result<Unit>
    fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?>
}
