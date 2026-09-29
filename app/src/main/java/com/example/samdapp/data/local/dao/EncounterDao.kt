package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: EncounterEntity)

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM encounters WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<EncounterEntity>

    @Query(
        "UPDATE encounters SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

    /** The FAILED to PENDING transition, the only way out of a terminal state.
     *  Driven by an explicit human action (S-3 owns its UI): a worker who has fixed
     *  whatever the server objected to, or who knows the parent has since landed,
     *  asks for this row to be tried again.
     *
     *  Resets the attempt budget and clears both the code and the message, so the
     *  next failure is reported on its own terms rather than under the last one's.
     *  Guarded on FAILED so it cannot disturb a row that is mid-flight, and
     *  idempotent: pressing retry twice is one requeue. */
    @Query(
        "UPDATE encounters SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM encounters WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'encounters' AS tableName, id AS recordId, patientId AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM encounters " +
        "WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>

    @Query("SELECT * FROM encounters WHERE id = :encounterId")
    fun observeById(encounterId: String): Flow<EncounterEntity?>

    /** One patient's own visit history — bounded by that patient's actual encounter count, not
     *  an "all patients" query (no data-minimization concern, see [PatientDao]'s KDoc for the
     *  distinction this DAO deliberately preserves). */
    @Query(
        "SELECT e.id AS encounterId, e.startedAt AS startedAt, c.chiefComplaint AS chiefComplaint, " +
        "cr.id AS caseRecordId, cr.status AS status, e.followUpOfEncounterId AS followUpOfEncounterId, " +
        "d.name AS doctorName, d.specialty AS doctorSpecialty " +
        "FROM encounters e " +
        "LEFT JOIN consultations c ON c.encounterId = e.id " +
        "LEFT JOIN case_records cr ON cr.encounterId = e.id " +
        "LEFT JOIN doctors d ON d.id = cr.assignedDoctorId " +
        "WHERE e.patientId = :patientId " +
        "ORDER BY e.startedAt DESC",
    )
    fun observeHistoryForPatient(patientId: String): Flow<List<EncounterHistoryRow>>
}
