package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AilmentDao {
    @Insert
    suspend fun insert(ailment: AilmentEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `audioLocalUri` is excluded
     *  from the wire payload (SyncRecordMappers.kt / SAMD-SYNC-6006), not from this query: a
     *  soft-deleted row still syncs its `deletedAt`. */
    @Query("SELECT * FROM ailments WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<AilmentEntity>

    @Query(
        "UPDATE ailments SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM ailments WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** All non-deleted ailments for an encounter. Private-vs-public filtering for the worker UI is
     *  applied above this layer (Phase 2) — the kernel path reads all of them regardless. */
    @Query("SELECT * FROM ailments WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY capturedAtOffline ASC")
    fun observeForEncounter(encounterId: String): Flow<List<AilmentEntity>>

    /** Soft delete (private-entry delete button). Sets [AilmentEntity.deletedAt]; the row is
     *  retained for the audit trail rather than physically removed. Also stamps
     *  [AilmentEntity.localModifiedAt] from the same [deletedAt] value, see MIGRATION_12_13's
     *  KDoc, and resets `syncState` to `PENDING` in the same statement (syncstate-reset session):
     *  `deletedAt` is part of the synced payload (this DAO's own KDoc above), so a soft delete on
     *  an already-`SYNCED` row must re-drain like any other clinical edit. */
    @Query("UPDATE ailments SET deletedAt = :deletedAt, localModifiedAt = :deletedAt, syncState = 'PENDING' WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant)
}
