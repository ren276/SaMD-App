package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface KernelReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: KernelReportEntity)

    @Query("SELECT * FROM kernel_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<KernelReportEntity?>

    /** [com.example.samdapp.data.repository.KernelReportRepositoryImpl.save] resolves this first so
     *  a re-assessment [upsert]s the SAME row this case already has (by primary key), rather than
     *  inserting a second row — `GenerateKernelReportUseCase` mints a fresh `id` on every attempt,
     *  so without this the REPLACE has nothing to replace. Mirrors
     *  [EvaluateReportDao.getIdForCase]; MIGRATION_15_16's unique index on `caseRecordId` is the
     *  enforcement that makes the single result here structural rather than conventional. */
    @Query("SELECT id FROM kernel_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getIdForCase(caseRecordId: String): String?

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [KernelReportEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved report silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM kernel_reports WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM kernel_reports WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<KernelReportEntity>

    @Query(
        "UPDATE kernel_reports SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM kernel_reports WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}
