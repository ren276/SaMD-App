package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AilmentEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AilmentDao {
    @Insert
    suspend fun insert(ailment: AilmentEntity)

    /** All non-deleted ailments for an encounter. Private-vs-public filtering for the worker UI is
     *  applied above this layer (Phase 2) — the kernel path reads all of them regardless. */
    @Query("SELECT * FROM ailments WHERE encounterId = :encounterId AND deletedAt IS NULL ORDER BY capturedAtOffline ASC")
    fun observeForEncounter(encounterId: String): Flow<List<AilmentEntity>>

    /** Soft delete (private-entry delete button). Sets [AilmentEntity.deletedAt]; the row is
     *  retained for the audit trail rather than physically removed. Also stamps
     *  [AilmentEntity.localModifiedAt] from the same [deletedAt] value, see MIGRATION_12_13's
     *  KDoc. */
    @Query("UPDATE ailments SET deletedAt = :deletedAt, localModifiedAt = :deletedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant)
}
