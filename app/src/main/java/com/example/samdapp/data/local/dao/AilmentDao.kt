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
    @Query("SELECT * FROM ailments WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
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

    @Query("SELECT COUNT(*) FROM ailments WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'ailments' AS tableName, id AS recordId, patientId AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM ailments " +
        "WHERE syncState = 'FAILED'",
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
