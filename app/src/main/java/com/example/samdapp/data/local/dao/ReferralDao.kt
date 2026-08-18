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
    @Query("SELECT * FROM referrals WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<ReferralEntity>

    @Query(
        "UPDATE referrals SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM referrals WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

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
