package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ReferralDao {
    @Insert
    suspend fun insert(referral: ReferralEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM referrals WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<ReferralEntity>

    @Query(
        "UPDATE referrals SET " +
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
        "UPDATE referrals SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM referrals WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'referrals' AS tableName, id AS recordId, patientUid AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM referrals " +
        "WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM referrals WHERE caseRecordId = :caseRecordId ORDER BY timestamp DESC")
    fun observeForCase(caseRecordId: String): Flow<List<ReferralEntity>>

    /** This device's own sent-referral outbox (Referrals tab) — small, PHC-worker-generated, not
     *  a full-table pull of anyone else's data. */
    @Query("SELECT * FROM referrals ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ReferralEntity>>

    /** Unreachable in this build, no caller sets a referral's status once created (see
     *  MIGRATION_12_13's PROGRESS.md note: status-transition history predates any timestamp
     *  column and is unrecoverable for existing rows). Signature updated for when a caller
     *  exists, so [ReferralEntity.localModifiedAt] is never left stale by a status change, and
     *  `syncState` resets to `PENDING` in the same statement (syncstate-reset session) so a
     *  status change on an already-`SYNCED` referral re-drains. */
    @Query("UPDATE referrals SET status = :status, localModifiedAt = :localModifiedAt, syncState = 'PENDING' WHERE id = :id")
    suspend fun updateStatus(id: String, status: ReferralStatus, localModifiedAt: Instant)
}
