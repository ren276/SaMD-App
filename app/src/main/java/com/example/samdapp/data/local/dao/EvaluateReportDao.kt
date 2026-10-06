package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface EvaluateReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: EvaluateReportEntity)

    @Query("SELECT * FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<EvaluateReportEntity?>

    /** H-14: read-only lookup for [com.example.samdapp.domain.repository.EvaluateReportRepository.getFailureCodeForCase],
     *  a distinct read path from [observeForCase] so a failure row's placeholder [EvaluateReportEntity.payloadJson]
     *  is never handed to a caller expecting a real report. */
    @Query("SELECT failureCode FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getFailureCode(caseRecordId: String): String?

    /** The repository's `save`/`saveFailure` resolve this first so a retry [upsert]s the SAME
     *  row this case already has (by primary key), rather than inserting a second row that
     *  [observeForCase] would then have to arbitrate between — the "one row per case, replacing
     *  wholesale on retry" this table's own KDoc already promises. */
    @Query("SELECT id FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getIdForCase(caseRecordId: String): String?

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [EvaluateReportEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved report silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM evaluate_reports WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `failureCode IS NULL` is the
     *  H-14 safety property: a persisted evaluate-failure marker must never be pushable to the
     *  backend as a real report, enforced here rather than relying on every caller to check. */
    @Query(
        "SELECT * FROM evaluate_reports WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " " +
        "AND failureCode IS NULL " +
        "ORDER BY localModifiedAt ASC",
    )
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<EvaluateReportEntity>

    @Query(
        "UPDATE evaluate_reports SET " +
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
        "UPDATE evaluate_reports SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, COUNT(*) AS rowCount FROM evaluate_reports WHERE syncState != 'SYNCED' GROUP BY syncState")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, COUNT(*) AS rowCount FROM evaluate_reports WHERE syncState != 'SYNCED' GROUP BY syncState")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'evaluate_reports' AS tableName, er.syncState AS syncState, er.id AS recordId, cr.patientId AS patientId, " +
        "er.localModifiedAt AS recordedAt, er.syncErrorCode AS syncErrorCode, er.syncErrorMessage AS " +
        "syncErrorMessage FROM evaluate_reports er LEFT JOIN case_records cr ON cr.id = er.caseRecordId " +
        "WHERE er.syncState IN ('FAILED', 'CONFLICT')",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
