package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ConsultationDao {
    @Insert
    suspend fun insert(consultation: ConsultationEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM consultations WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<ConsultationEntity>

    @Query(
        "UPDATE consultations SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM consultations WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** Also stamps `localModifiedAt` from the same [updatedAt] value, see MIGRATION_12_13's
     *  KDoc for why the two columns are deliberately redundant on entities that have both, and
     *  resets `syncState` to `PENDING` in the same statement so a re-edited transcription
     *  re-drains even if this row was already `SYNCED` (syncstate-reset session). `serverVersion`
     *  is untouched: the next push sends it as `base_version`, letting the backend's
     *  last-write-wins logic run normally rather than looking like a never-synced row. */
    @Query(
        "UPDATE consultations SET transcription = :transcription, updatedAt = :updatedAt, " +
            "localModifiedAt = :updatedAt, syncState = 'PENDING' WHERE id = :consultationId",
    )
    suspend fun updateTranscription(consultationId: String, transcription: String, updatedAt: Instant)

    @Query("SELECT * FROM consultations WHERE encounterId = :encounterId LIMIT 1")
    fun observeForEncounter(encounterId: String): Flow<ConsultationEntity?>

    /** H-18, Build 3a: the one-shot lookup a consultation-document upload derives `patientId`
     *  from, rather than trusting a caller-supplied value. */
    @Query("SELECT * FROM consultations WHERE id = :consultationId")
    suspend fun getById(consultationId: String): ConsultationEntity?
}
