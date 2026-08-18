package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AbhaProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AbhaProfileEntity)

    @Query("SELECT * FROM abha_profiles WHERE abhaId = :abhaId")
    suspend fun getByAbhaId(abhaId: String): AbhaProfileEntity?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM abha_profiles WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<AbhaProfileEntity>

    @Query(
        "UPDATE abha_profiles SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE abhaId = :abhaId AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(abhaId: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM abha_profiles WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}
