package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.CaseRecord
import kotlinx.coroutines.flow.Flow

interface CaseRecordRepository {
    suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord>
    suspend fun markSavedLocally(caseRecordId: String): Result<Unit>
    suspend fun assignDoctor(caseRecordId: String, doctorId: String): Result<Unit>

    /** REQ-RX-01/03: flips status once the (mocked) doctor intake has written a prescription. */
    suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit>

    fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?>

    /** Most recent case record for [patientId] — used by PatientSummary (reached only via the
     *  day-scoped roster, so this doesn't reopen the "no all-patients query" boundary, REQ-ROS-02). */
    fun observeLatestForPatient(patientId: String): Flow<CaseRecord?>
}
