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
    @Query("SELECT * FROM attachments WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
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

    @Query("SELECT COUNT(*) FROM attachments WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'attachments' AS tableName, a.id AS recordId, c.patientId AS patientId, a.localModifiedAt " +
        "AS recordedAt, a.syncErrorCode AS syncErrorCode, a.syncErrorMessage AS syncErrorMessage FROM " +
        "attachments a LEFT JOIN consultations c ON c.id = a.consultationId WHERE a.syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
