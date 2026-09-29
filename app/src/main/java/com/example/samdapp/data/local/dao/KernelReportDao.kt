package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface KernelReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: KernelReportEntity)

    @Query("SELECT * FROM kernel_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<KernelReportEntity?>

    /** [com.example.samdapp.data.repository.KernelReportRepositoryImpl.save] resolves this first so
     *  a re-assessment [upsert]s the SAME row this case already has (by primary key), rather than
     *  inserting a second row — `GenerateKernelReportUseCase` mints a fresh `id` on every attempt,
     *  so without this the REPLACE has nothing to replace. Mirrors
     *  [EvaluateReportDao.getIdForCase]; MIGRATION_15_16's unique index on `caseRecordId` is the
     *  enforcement that makes the single result here structural rather than conventional. */
    @Query("SELECT id FROM kernel_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getIdForCase(caseRecordId: String): String?

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [KernelReportEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved report silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM kernel_reports WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM kernel_reports WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<KernelReportEntity>

    @Query(
        "UPDATE kernel_reports SET " +
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
        "UPDATE kernel_reports SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM kernel_reports WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'kernel_reports' AS tableName, kr.id AS recordId, cr.patientId AS patientId, " +
        "kr.localModifiedAt AS recordedAt, kr.syncErrorCode AS syncErrorCode, kr.syncErrorMessage AS " +
        "syncErrorMessage FROM kernel_reports kr LEFT JOIN case_records cr ON cr.id = kr.caseRecordId " +
        "WHERE kr.syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
