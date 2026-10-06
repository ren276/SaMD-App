package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE consultationId = :consultationId ORDER BY createdAt ASC")
    fun observeForConsultation(consultationId: String): Flow<List<AttachmentEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM attachments WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_ATTACHMENTS + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<AttachmentEntity>

    @Query(
        "UPDATE attachments SET " +
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
        "UPDATE attachments SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_ATTACHMENTS + " AS held, COUNT(*) AS rowCount FROM attachments WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_ATTACHMENTS + " AS held, COUNT(*) AS rowCount FROM attachments WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'attachments' AS tableName, attachments.syncState AS syncState, attachments.id AS " +
        "recordId, consultations.patientId AS patientId, attachments.localModifiedAt AS recordedAt, " +
        "attachments.syncErrorCode AS syncErrorCode, attachments.syncErrorMessage AS syncErrorMessage, " +
        "attachments.serverVersion AS serverVersion, consultations.encounterId AS encounterId, CAST(NULL " +
        "AS TEXT) AS caseRecordId, attachments.consultationId AS parentId FROM attachments LEFT JOIN " +
        "consultations ON consultations.id = attachments.consultationId WHERE attachments.syncState IN " +
        "('FAILED', 'CONFLICT') OR (attachments.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_ATTACHMENTS + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
