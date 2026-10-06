package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PrescriptionDao {
    @Insert
    suspend fun insertPrescription(prescription: PrescriptionEntity)

    @Insert
    suspend fun insertMedicationLines(lines: List<MedicationLineEntity>)

    @Query("SELECT * FROM prescriptions WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<PrescriptionEntity?>

    @Query("SELECT * FROM medication_lines WHERE prescriptionId = :prescriptionId ORDER BY position ASC")
    fun observeLines(prescriptionId: String): Flow<List<MedicationLineEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. Two tables, one DAO, matching
     *  this file's existing convention. */
    @Query("SELECT * FROM prescriptions WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_PRESCRIPTIONS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingPrescriptionsForSync(retryEligibleBefore: Instant): List<PrescriptionEntity>

    @Query(
        "UPDATE prescriptions SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applyPrescriptionSyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

    /** The FAILED to PENDING transition, the only way out of a terminal state.
     *  Driven by an explicit human action (S-3 owns its UI): a worker who has fixed
     *  whatever the server objected to, or who knows the parent has since landed,
     *  asks for this row to be tried again.
     *
     *  Resets the attempt budget and clears both the code and the message, so the
     *  next failure is reported on its own terms rather than under the last one's.
     *  Guarded on FAILED so it cannot disturb a row that is mid-flight, and
     *  idempotent: pressing retry twice is one requeue. */
    @Query(
        "UPDATE prescriptions SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailedPrescription(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_PRESCRIPTIONS + " AS held, COUNT(*) AS rowCount FROM prescriptions WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observePrescriptionSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_PRESCRIPTIONS + " AS held, COUNT(*) AS rowCount FROM prescriptions WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getPrescriptionSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'prescriptions' AS tableName, prescriptions.syncState AS syncState, prescriptions.id AS " +
        "recordId, prescriptions.patientId AS patientId, prescriptions.localModifiedAt AS recordedAt, " +
        "prescriptions.syncErrorCode AS syncErrorCode, prescriptions.syncErrorMessage AS " +
        "syncErrorMessage, prescriptions.serverVersion AS serverVersion, prescriptions.encounterId AS " +
        "encounterId, prescriptions.caseRecordId AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM " +
        "prescriptions WHERE prescriptions.syncState IN ('FAILED', 'CONFLICT') OR " +
        "(prescriptions.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_PRESCRIPTIONS + ")",
    )
    suspend fun getFailedPrescriptionsForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM medication_lines WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_MEDICATION_LINES + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingMedicationLinesForSync(retryEligibleBefore: Instant): List<MedicationLineEntity>

    @Query(
        "UPDATE medication_lines SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applyMedicationLineSyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

    /** The FAILED to PENDING transition, the only way out of a terminal state.
     *  Driven by an explicit human action (S-3 owns its UI): a worker who has fixed
     *  whatever the server objected to, or who knows the parent has since landed,
     *  asks for this row to be tried again.
     *
     *  Resets the attempt budget and clears both the code and the message, so the
     *  next failure is reported on its own terms rather than under the last one's.
     *  Guarded on FAILED so it cannot disturb a row that is mid-flight, and
     *  idempotent: pressing retry twice is one requeue. */
    @Query(
        "UPDATE medication_lines SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailedMedicationLine(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICATION_LINES + " AS held, COUNT(*) AS rowCount FROM medication_lines WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeMedicationLineSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICATION_LINES + " AS held, COUNT(*) AS rowCount FROM medication_lines WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getMedicationLineSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medication_lines' AS tableName, medication_lines.syncState AS syncState, " +
        "medication_lines.id AS recordId, prescriptions.patientId AS patientId, " +
        "medication_lines.localModifiedAt AS recordedAt, medication_lines.syncErrorCode AS syncErrorCode, " +
        "medication_lines.syncErrorMessage AS syncErrorMessage, medication_lines.serverVersion AS " +
        "serverVersion, prescriptions.encounterId AS encounterId, prescriptions.caseRecordId AS " +
        "caseRecordId, medication_lines.prescriptionId AS parentId FROM medication_lines LEFT JOIN " +
        "prescriptions ON prescriptions.id = medication_lines.prescriptionId WHERE " +
        "medication_lines.syncState IN ('FAILED', 'CONFLICT') OR (medication_lines.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_MEDICATION_LINES + ")",
    )
    suspend fun getFailedMedicationLinesForReview(): List<FailedSyncRow>
}
