package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.domain.model.ReferralStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ReferralDao {
    @Insert
    suspend fun insert(referral: ReferralEntity)

    @Query("SELECT * FROM referrals WHERE caseRecordId = :caseRecordId ORDER BY timestamp DESC")
    fun observeForCase(caseRecordId: String): Flow<List<ReferralEntity>>

    /** This device's own sent-referral outbox (Referrals tab) — small, PHC-worker-generated, not
     *  a full-table pull of anyone else's data. */
    @Query("SELECT * FROM referrals ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ReferralEntity>>

    /** Unreachable in this build, no caller sets a referral's status once created (see
     *  MIGRATION_12_13's PROGRESS.md note: status-transition history predates any timestamp
     *  column and is unrecoverable for existing rows). Signature updated for when a caller
     *  exists, so [ReferralEntity.localModifiedAt] is never left stale by a status change. */
    @Query("UPDATE referrals SET status = :status, localModifiedAt = :localModifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: ReferralStatus, localModifiedAt: Instant)
}
