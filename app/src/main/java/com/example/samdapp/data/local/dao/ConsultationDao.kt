package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ConsultationDao {
    @Insert
    suspend fun insert(consultation: ConsultationEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM consultations WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_CONSULTATIONS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<ConsultationEntity>

    @Query(
        "UPDATE consultations SET " +
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
        "UPDATE consultations SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_CONSULTATIONS + " AS held, COUNT(*) AS rowCount FROM consultations WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_CONSULTATIONS + " AS held, COUNT(*) AS rowCount FROM consultations WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'consultations' AS tableName, consultations.syncState AS syncState, consultations.id AS " +
        "recordId, consultations.patientId AS patientId, consultations.localModifiedAt AS recordedAt, " +
        "consultations.syncErrorCode AS syncErrorCode, consultations.syncErrorMessage AS " +
        "syncErrorMessage, consultations.serverVersion AS serverVersion, consultations.encounterId AS " +
        "encounterId, CAST(NULL AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM " +
        "consultations WHERE consultations.syncState IN ('FAILED', 'CONFLICT') OR " +
        "(consultations.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_CONSULTATIONS + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    /** Also stamps `localModifiedAt` from the same [updatedAt] value, see MIGRATION_12_13's
     *  KDoc for why the two columns are deliberately redundant on entities that have both, and
     *  resets `syncState` to `PENDING` in the same statement so a re-edited transcription
     *  re-drains even if this row was already `SYNCED` (syncstate-reset session). `serverVersion`
     *  is untouched: the next push sends it as `base_version`, letting the backend's
     *  last-write-wins logic run normally rather than looking like a never-synced row. */
    @Query(
        "UPDATE consultations SET transcription = :transcription, updatedAt = :updatedAt, " +
        "localModifiedAt = :updatedAt, syncState = 'PENDING' WHERE id = :consultationId",
    )
    suspend fun updateTranscription(consultationId: String, transcription: String, updatedAt: Instant)

    @Query("SELECT * FROM consultations WHERE encounterId = :encounterId LIMIT 1")
    fun observeForEncounter(encounterId: String): Flow<ConsultationEntity?>

    /** H-18, Build 3a: the one-shot lookup a consultation-document upload derives `patientId`
     *  from, rather than trusting a caller-supplied value. */
    @Query("SELECT * FROM consultations WHERE id = :consultationId")
    suspend fun getById(consultationId: String): ConsultationEntity?
}
