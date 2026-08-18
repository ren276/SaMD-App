package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ObservationDao {
    @Insert
    suspend fun insertAll(observations: List<ObservationEntity>)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM observations WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<ObservationEntity>

    @Query(
        "UPDATE observations SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM observations WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    @Query("SELECT * FROM observations WHERE encounterId = :encounterId ORDER BY recordedAt ASC")
    fun observeForEncounter(encounterId: String): Flow<List<ObservationEntity>>
}
