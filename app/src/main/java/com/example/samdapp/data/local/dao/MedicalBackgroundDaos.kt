package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface MedicalHistoryItemDao {
    @Insert
    suspend fun insert(item: MedicalHistoryItemEntity)

    @Query("SELECT * FROM medical_history_items WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<MedicalHistoryItemEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM medical_history_items WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<MedicalHistoryItemEntity>

    @Query(
        "UPDATE medical_history_items SET " +
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
        "UPDATE medical_history_items SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM medical_history_items WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medical_history_items' AS tableName, id AS recordId, patientId AS patientId, " +
        "localModifiedAt AS recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS " +
        "syncErrorMessage FROM medical_history_items WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}

@Dao
interface MedicationEntryDao {
    @Insert
    suspend fun insert(entry: MedicationEntryEntity)

    @Query("SELECT * FROM medication_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<MedicationEntryEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM medication_entries WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<MedicationEntryEntity>

    @Query(
        "UPDATE medication_entries SET " +
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
        "UPDATE medication_entries SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM medication_entries WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medication_entries' AS tableName, id AS recordId, patientId AS patientId, " +
        "localModifiedAt AS recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS " +
        "syncErrorMessage FROM medication_entries WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}

@Dao
interface AllergyDao {
    @Insert
    suspend fun insert(allergy: AllergyEntity)

    @Query("SELECT * FROM allergies WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<AllergyEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM allergies WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<AllergyEntity>

    @Query(
        "UPDATE allergies SET " +
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
        "UPDATE allergies SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM allergies WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'allergies' AS tableName, id AS recordId, patientId AS patientId, localModifiedAt AS " +
        "recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS syncErrorMessage FROM allergies " +
        "WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}

@Dao
interface FamilyHistoryEntryDao {
    @Insert
    suspend fun insert(entry: FamilyHistoryEntryEntity)

    @Query("SELECT * FROM family_history_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<FamilyHistoryEntryEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM family_history_entries WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<FamilyHistoryEntryEntity>

    @Query(
        "UPDATE family_history_entries SET " +
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
        "UPDATE family_history_entries SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE id = :id AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(id: String)

    @Query("SELECT COUNT(*) FROM family_history_entries WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'family_history_entries' AS tableName, id AS recordId, patientId AS patientId, " +
        "localModifiedAt AS recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS " +
        "syncErrorMessage FROM family_history_entries WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}

@Dao
interface SocialHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(socialHistory: SocialHistoryEntity)

    @Query("SELECT * FROM social_histories WHERE patientId = :patientId")
    fun observeForPatient(patientId: String): Flow<SocialHistoryEntity?>

    /** `syncstate-reset` session: [upsert] is `REPLACE`, which overwrites the whole row including
     *  `serverVersion` with whatever the caller's fresh [SocialHistoryEntity] carries (default
     *  `null`). The caller must read this first and thread it through the replacement entity, or
     *  a re-edit silently makes an already-synced row look never-synced. */
    @Query("SELECT serverVersion FROM social_histories WHERE patientId = :patientId")
    suspend fun getServerVersion(patientId: String): Int?

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. `patientId` IS this table's
     *  primary key (one row per patient), so it doubles as the sync record id. */
    @Query("SELECT * FROM social_histories WHERE syncState IN ('PENDING', 'RETRYABLE') "
            + "AND (lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore) "
            + "ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(retryEligibleBefore: Instant): List<SocialHistoryEntity>

    @Query(
        "UPDATE social_histories SET " +
        "syncState = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END, " +
        "serverVersion = COALESCE(:serverVersion, serverVersion), " +
        "syncErrorCode = CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END, " +
        "syncErrorMessage = :syncErrorMessage, " +
        "syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END, " +
        "lastSyncAttemptAt = :attemptAt " +
        "WHERE patientId = :patientId AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(patientId: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, syncErrorMessage: String?, attemptAt: Instant, sentLocalModifiedAt: Instant, maxAttempts: Int, retryExhaustedCode: String)

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
        "UPDATE social_histories SET syncState = 'PENDING', syncAttemptCount = 0, " +
        "syncErrorCode = NULL, syncErrorMessage = NULL " +
        "WHERE patientId = :patientId AND syncState = 'FAILED'",
    )
    suspend fun requeueFailed(patientId: String)

    @Query("SELECT COUNT(*) FROM social_histories WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>

    /** The FAILED rows of this table, projected for the worker-facing review list (S-3).
     *  Selects exactly the rows this table's FAILED counter counts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'social_histories' AS tableName, patientId AS recordId, patientId AS patientId, " +
        "localModifiedAt AS recordedAt, syncErrorCode AS syncErrorCode, syncErrorMessage AS " +
        "syncErrorMessage FROM social_histories WHERE syncState = 'FAILED'",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
