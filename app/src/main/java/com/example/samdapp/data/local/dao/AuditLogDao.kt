package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Insert-only for the audit content itself (`timestamp`/`userId`/`action`/`payload`/etc) — the
 * trail must never be editable after the fact. [applySyncResult] is the one exception, and it is
 * not really one: it only ever touches the four Phase 6b transport-bookkeeping columns
 * (`syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt`), never the audit content, so
 * the "never editable" guarantee this docstring names is unbroken.
 */
@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `op` is `"insert"` for this
     *  table (SyncRecordMappers.kt), matching its append-only nature server side too. */
    @Query("SELECT * FROM audit_log WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<AuditLogEntity>

    @Query(
        "UPDATE audit_log SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM audit_log WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE patientId = :patientId ORDER BY timestamp DESC")
    fun observeByPatientId(patientId: String): Flow<List<AuditLogEntity>>

    /** Recent actions by one worker (Profile tab's audit summary) — bounded by [limit], not the
     *  full log. */
    @Query("SELECT * FROM audit_log WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    fun observeByUserId(userId: String, limit: Int): Flow<List<AuditLogEntity>>
}
