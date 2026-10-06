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
    @Query("SELECT * FROM prescriptions WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " "
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

    @Query("SELECT COUNT(*) FROM prescriptions WHERE syncState IN ('FAILED', 'CONFLICT')")
    fun observePrescriptionFailedSyncCount(): Flow<Int>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the rows this table's failed counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'prescriptions' AS tableName, syncState AS syncState, id AS recordId, patientId AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM " +
        "prescriptions WHERE syncState IN ('FAILED', 'CONFLICT')",
    )
    suspend fun getFailedPrescriptionsForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM medication_lines WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " "
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

    @Query("SELECT COUNT(*) FROM medication_lines WHERE syncState IN ('FAILED', 'CONFLICT')")
    fun observeMedicationLineFailedSyncCount(): Flow<Int>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the rows this table's failed counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medication_lines' AS tableName, ml.syncState AS syncState, ml.id AS recordId, p.patientId AS patientId, " +
        "ml.localModifiedAt AS recordedAt, ml.syncErrorCode AS syncErrorCode, ml.syncErrorMessage AS " +
        "syncErrorMessage FROM medication_lines ml LEFT JOIN prescriptions p ON p.id = ml.prescriptionId " +
        "WHERE ml.syncState IN ('FAILED', 'CONFLICT')",
    )
    suspend fun getFailedMedicationLinesForReview(): List<FailedSyncRow>
}
