package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface DiagnosisFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feedback: DiagnosisFeedbackEntity)

    @Query("SELECT * FROM diagnosis_feedback WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<DiagnosisFeedbackEntity?>

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [DiagnosisFeedbackEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-saved feedback silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM diagnosis_feedback WHERE id = :id")
    suspend fun getServerVersion(id: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. A doctor's clinical REJECT
     *  (`PhysicianDecision.REJECT`) is a normal row here that must sync successfully like any
     *  other — "sync-rejected" (the outbox's `FAILED` state below) is a transport concept and
     *  never means the clinical decision itself; do not conflate the two. */
    @Query("SELECT * FROM diagnosis_feedback WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<DiagnosisFeedbackEntity>

    @Query(
        "UPDATE diagnosis_feedback SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM diagnosis_feedback WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}
