package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface EvaluateReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: EvaluateReportEntity)

    @Query("SELECT * FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<EvaluateReportEntity?>

    /** H-14: read-only lookup for [com.example.samdapp.domain.repository.EvaluateReportRepository.getFailureCodeForCase],
     *  a distinct read path from [observeForCase] so a failure row's placeholder [EvaluateReportEntity.payloadJson]
     *  is never handed to a caller expecting a real report. */
    @Query("SELECT failureCode FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getFailureCode(caseRecordId: String): String?

    /** The repository's `save`/`saveFailure` resolve this first so a retry [upsert]s the SAME
     *  row this case already has (by primary key), rather than inserting a second row that
     *  [observeForCase] would then have to arbitrate between — the "one row per case, replacing
     *  wholesale on retry" this table's own KDoc already promises. */
    @Query("SELECT id FROM evaluate_reports WHERE caseRecordId = :caseRecordId")
    suspend fun getIdForCase(caseRecordId: String): String?

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [EvaluateReportEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved report silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM evaluate_reports WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `failureCode IS NULL` is the
     *  H-14 safety property: a persisted evaluate-failure marker must never be pushable to the
     *  backend as a real report, enforced here rather than relying on every caller to check. */
    @Query(
        "SELECT * FROM evaluate_reports WHERE syncState = 'PENDING' AND failureCode IS NULL " +
            "ORDER BY localModifiedAt ASC",
    )
    suspend fun getPendingForSync(): List<EvaluateReportEntity>

    @Query(
        "UPDATE evaluate_reports SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM evaluate_reports WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}
