package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface DiagnosisFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feedback: DiagnosisFeedbackEntity)

    @Query("SELECT * FROM diagnosis_feedback WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<DiagnosisFeedbackEntity?>

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [DiagnosisFeedbackEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved feedback silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM diagnosis_feedback WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. A doctor's clinical REJECT
     *  (`PhysicianDecision.REJECT`) is a normal row here that must sync successfully like any
     *  other — "sync-rejected" (the outbox's `FAILED` state below) is a transport concept and
     *  never means the clinical decision itself; do not conflate the two. */
    @Query("SELECT * FROM diagnosis_feedback WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_DIAGNOSIS_FEEDBACK + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<DiagnosisFeedbackEntity>

    @Query(
        "UPDATE diagnosis_feedback SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

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
        "UPDATE diagnosis_feedback SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_DIAGNOSIS_FEEDBACK + " AS held, COUNT(*) AS rowCount FROM diagnosis_feedback WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_DIAGNOSIS_FEEDBACK + " AS held, COUNT(*) AS rowCount FROM diagnosis_feedback WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'diagnosis_feedback' AS tableName, diagnosis_feedback.syncState AS syncState, " +
        "diagnosis_feedback.id AS recordId, case_records.patientId AS patientId, " +
        "diagnosis_feedback.localModifiedAt AS recordedAt, diagnosis_feedback.syncErrorCode AS " +
        "syncErrorCode, diagnosis_feedback.syncErrorMessage AS syncErrorMessage, " +
        "diagnosis_feedback.serverVersion AS serverVersion, case_records.encounterId AS encounterId, " +
        "diagnosis_feedback.caseRecordId AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM " +
        "diagnosis_feedback LEFT JOIN case_records ON case_records.id = diagnosis_feedback.caseRecordId " +
        "WHERE diagnosis_feedback.syncState IN ('FAILED', 'CONFLICT') OR (diagnosis_feedback.syncState IN " +
        "" +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_DIAGNOSIS_FEEDBACK + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
