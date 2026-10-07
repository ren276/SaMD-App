package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ObservationDao {
    @Insert
    suspend fun insertAll(observations: List<ObservationEntity>)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM observations WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_OBSERVATIONS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<ObservationEntity>

    @Query(
        "UPDATE observations SET " +
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
        "UPDATE observations SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_OBSERVATIONS + " AS held, COUNT(*) AS rowCount FROM observations WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_OBSERVATIONS + " AS held, COUNT(*) AS rowCount FROM observations WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'observations' AS tableName, observations.syncState AS syncState, observations.id AS " +
        "recordId, observations.patientId AS patientId, observations.localModifiedAt AS recordedAt, " +
        "observations.syncErrorCode AS syncErrorCode, observations.syncErrorMessage AS syncErrorMessage, " +
        "observations.serverVersion AS serverVersion, observations.encounterId AS encounterId, CAST(NULL " +
        "AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM observations WHERE " +
        "observations.syncState IN ('FAILED', 'CONFLICT') OR (observations.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_OBSERVATIONS + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM observations WHERE encounterId = :encounterId ORDER BY recordedAt ASC")
    fun observeForEncounter(encounterId: String): Flow<List<ObservationEntity>>
}
