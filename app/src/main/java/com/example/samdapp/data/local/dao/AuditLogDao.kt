package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Insert-only by design — the audit trail must never be editable after the fact.
 * Do not add @Update or @Delete methods here.
 */
@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE patientId = :patientId ORDER BY timestamp DESC")
    fun observeByPatientId(patientId: String): Flow<List<AuditLogEntity>>

    /** Recent actions by one worker (Profile tab's audit summary) — bounded by [limit], not the
     *  full log. */
    @Query("SELECT * FROM audit_log WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    fun observeByUserId(userId: String, limit: Int): Flow<List<AuditLogEntity>>
}
