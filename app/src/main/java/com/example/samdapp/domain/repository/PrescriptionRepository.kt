package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.Prescription

/**
 * Read + write for the doctor's prescription (Phase 5 writes; Phase 3 report assembly reads).
 * [getForCase] returns null until a doctor has prescribed — the preliminary report relies on that.
 */
interface PrescriptionRepository {
    suspend fun save(prescription: Prescription): Result<Unit>
    suspend fun getForCase(caseRecordId: String): Prescription?
}
