package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PatientDao {
    @Insert
    suspend fun insert(patient: PatientEntity)

    /** Phase 6b outbox: rows this device has never pushed, or has locally re-modified since its
     *  last push. Never `CONFLICT`/`FAILED`/`SYNCED` — see SyncOutboxRepository's KDoc. */
    @Query("SELECT * FROM patients WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<PatientEntity>

    /** [serverVersion] is `COALESCE`d against the existing value so a `conflict`/`rejected` ack
     *  (which carries no fresh version) never wipes the version from a prior successful sync. */
    @Query(
        "UPDATE patients SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM patients WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    @Query("SELECT * FROM patients WHERE id = :patientId")
    fun observeById(patientId: String): Flow<PatientEntity?>

    /**
     * Patients with at least one encounter whose startedAt falls in [startMillis, endMillis).
     * Deliberately no "all patients" query exists on this DAO — the only list surface is
     * date-bounded, so no code path can pull the full patient table onto the device
     * (data-minimization, see agent_docs/hardening.md). Bounds are epoch-millis Longs
     * compared directly against the stored INTEGER column; the caller decides what window
     * "today" means.
     */
    @Query(
        "SELECT p.* FROM patients p " +
            "INNER JOIN encounters e ON e.patientId = p.id " +
            "WHERE e.startedAt >= :startMillis AND e.startedAt < :endMillis " +
            "GROUP BY p.id " +
            "ORDER BY MAX(e.startedAt) DESC",
    )
    fun observePatientsWithEncounterBetween(startMillis: Long, endMillis: Long): Flow<List<PatientEntity>>
}
