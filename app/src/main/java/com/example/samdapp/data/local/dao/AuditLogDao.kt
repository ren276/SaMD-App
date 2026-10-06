package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Insert-only for the audit content itself (`timestamp`/`userId`/`action`/`payload`/etc) — the
 * trail must never be editable after the fact. [applySyncResult] is the one exception, and it is
 * not really one: it only ever touches the four Phase 6b transport-bookkeeping columns
 * (`syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt`), never the audit content, so
 * the "never editable" guarantee this docstring names is unbroken.
 */
@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `op` is `"insert"` for this
     *  table (SyncRecordMappers.kt), matching its append-only nature server side too. */
    @Query("SELECT * FROM audit_log WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<AuditLogEntity>

    @Query(
        "UPDATE audit_log SET " +
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
        "UPDATE audit_log SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, COUNT(*) AS rowCount FROM audit_log WHERE syncState != 'SYNCED' GROUP BY syncState")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, COUNT(*) AS rowCount FROM audit_log WHERE syncState != 'SYNCED' GROUP BY syncState")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'audit_log' AS tableName, syncState AS syncState, id AS recordId, patientId AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM audit_log " +
        "WHERE syncState IN ('FAILED', 'CONFLICT')",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE patientId = :patientId ORDER BY timestamp DESC")
    fun observeByPatientId(patientId: String): Flow<List<AuditLogEntity>>

    /** Recent actions by one worker (Profile tab's audit summary) — bounded by [limit], not the
     *  full log. */
    @Query("SELECT * FROM audit_log WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    fun observeByUserId(userId: String, limit: Int): Flow<List<AuditLogEntity>>
}
