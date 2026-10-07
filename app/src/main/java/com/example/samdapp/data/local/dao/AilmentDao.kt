package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AilmentDao {
    @Insert
    suspend fun insert(ailment: AilmentEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `audioLocalUri` is excluded
     *  from the wire payload (SyncRecordMappers.kt / SAMD-SYNC-6006), not from this query: a
     *  soft-deleted row still syncs its `deletedAt`. */
    @Query("SELECT * FROM ailments WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_AILMENTS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<AilmentEntity>

    @Query(
        "UPDATE ailments SET " +
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
        "UPDATE ailments SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_AILMENTS + " AS held, COUNT(*) AS rowCount FROM ailments WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_AILMENTS + " AS held, COUNT(*) AS rowCount FROM ailments WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'ailments' AS tableName, ailments.syncState AS syncState, ailments.id AS recordId, " +
        "ailments.patientId AS patientId, ailments.localModifiedAt AS recordedAt, ailments.syncErrorCode " +
        "AS syncErrorCode, ailments.syncErrorMessage AS syncErrorMessage, ailments.serverVersion AS " +
        "serverVersion, ailments.encounterId AS encounterId, CAST(NULL AS TEXT) AS caseRecordId, " +
        "CAST(NULL AS TEXT) AS parentId FROM ailments WHERE ailments.syncState IN ('FAILED', 'CONFLICT') " +
        "OR (ailments.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_AILMENTS + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    /** All non-deleted ailments for an encounter. Private-vs-public filtering for the worker UI is
     *  applied above this layer (Phase 2) — the kernel path reads all of them regardless. */
    @Query("SELECT * FROM ailments WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY capturedAtOffline ASC")
    fun observeForEncounter(encounterId: String): Flow<List<AilmentEntity>>

    /** Soft delete (private-entry delete button). Sets [AilmentEntity.deletedAt]; the row is
     *  retained for the audit trail rather than physically removed. Also stamps
     *  [AilmentEntity.localModifiedAt] from the same [deletedAt] value, see MIGRATION_12_13's
     *  KDoc, and resets `syncState` to `PENDING` in the same statement (syncstate-reset session):
     *  `deletedAt` is part of the synced payload (this DAO's own KDoc above), so a soft delete on
     *  an already-`SYNCED` row must re-drain like any other clinical edit. */
    @Query("UPDATE ailments SET deletedAt = :deletedAt, localModifiedAt = :deletedAt, syncState = 'PENDING' WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant)
}
