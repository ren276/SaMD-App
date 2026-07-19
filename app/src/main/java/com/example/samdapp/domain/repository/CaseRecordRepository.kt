package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.DoctorTrackerEntry
import kotlinx.coroutines.flow.Flow

interface CaseRecordRepository {
    suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord>
    suspend fun markSavedLocally(caseRecordId: String): Result<Unit>
    /** [isOnline] decides whether this lands as `SENT_TO_DOCTOR` right away or queues as
     *  `PENDING_SYNC` until [sendAllPendingCases] runs — the offline-first send path. */
    suspend fun assignDoctor(caseRecordId: String, doctorId: String, isOnline: Boolean): Result<Unit>

    /** "Sync Up": flips every `PENDING_SYNC` case to `SENT_TO_DOCTOR`. Caller is responsible for
     *  only invoking this while online. */
    suspend fun sendAllPendingCases(): Result<Unit>

    /** Count of cases queued locally, assigned a doctor but not yet sent — drives the "N pending"
     *  caption and the Sync Up button's badge. */
    fun observePendingSyncCount(): Flow<Int>

    /** REQ-RX-01/03: flips status once the (mocked) doctor intake has written a prescription. */
    suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit>

    fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?>

    /** Most recent case record for [patientId] — used by PatientSummary (reached only via the
     *  day-scoped roster, so this doesn't reopen the "no all-patients query" boundary, REQ-ROS-02). */
    fun observeLatestForPatient(patientId: String): Flow<CaseRecord?>

    /** The case record for one specific encounter — doctor-continuity resolution (Part B) walks
     *  a follow-up's prior encounter back to this to find who saw the patient last time. */
    fun observeByEncounterId(encounterId: String): Flow<CaseRecord?>

    /** Crash-recovery resume: [userId]'s own in-progress (still `DRAFT`) visit, if any — see the
     *  DAO KDoc for how "this worker's" is derived without a schema change. */
    fun observeResumableDraftForUser(userId: String): Flow<CaseRecord?>

    /** [doctorId]'s currently-open (SENT_TO_DOCTOR) case count — the least-busy signal for
     *  auto-assigning a fresh/unrelated case. */
    fun observeOpenCaseCount(doctorId: String): Flow<Int>

    /** Every case currently sent to or reviewed by a doctor, across all patients — Part B's
     *  DoctorList tracker. Deliberately cross-patient; see the DAO KDoc for why this one is
     *  exempt from the day-scoping data-minimization rule the patient roster follows. */
    fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerEntry>>
}
