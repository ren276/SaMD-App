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
    @Query("SELECT * FROM medical_history_items WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_MEDICAL_HISTORY_ITEMS + " "
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

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICAL_HISTORY_ITEMS + " AS held, COUNT(*) AS rowCount FROM medical_history_items WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICAL_HISTORY_ITEMS + " AS held, COUNT(*) AS rowCount FROM medical_history_items WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medical_history_items' AS tableName, medical_history_items.syncState AS syncState, " +
        "medical_history_items.id AS recordId, medical_history_items.patientId AS patientId, " +
        "medical_history_items.localModifiedAt AS recordedAt, medical_history_items.syncErrorCode AS " +
        "syncErrorCode, medical_history_items.syncErrorMessage AS syncErrorMessage, " +
        "medical_history_items.serverVersion AS serverVersion, CAST(NULL AS TEXT) AS encounterId, " +
        "CAST(NULL AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM medical_history_items " +
        "WHERE medical_history_items.syncState IN ('FAILED', 'CONFLICT') OR " +
        "(medical_history_items.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_MEDICAL_HISTORY_ITEMS + ")",
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
    @Query("SELECT * FROM medication_entries WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_MEDICATION_ENTRIES + " "
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

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICATION_ENTRIES + " AS held, COUNT(*) AS rowCount FROM medication_entries WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_MEDICATION_ENTRIES + " AS held, COUNT(*) AS rowCount FROM medication_entries WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'medication_entries' AS tableName, medication_entries.syncState AS syncState, " +
        "medication_entries.id AS recordId, medication_entries.patientId AS patientId, " +
        "medication_entries.localModifiedAt AS recordedAt, medication_entries.syncErrorCode AS " +
        "syncErrorCode, medication_entries.syncErrorMessage AS syncErrorMessage, " +
        "medication_entries.serverVersion AS serverVersion, medication_entries.encounterId AS " +
        "encounterId, CAST(NULL AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM " +
        "medication_entries WHERE medication_entries.syncState IN ('FAILED', 'CONFLICT') OR " +
        "(medication_entries.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_MEDICATION_ENTRIES + ")",
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
    @Query("SELECT * FROM allergies WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_ALLERGIES + " "
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

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_ALLERGIES + " AS held, COUNT(*) AS rowCount FROM allergies WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_ALLERGIES + " AS held, COUNT(*) AS rowCount FROM allergies WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'allergies' AS tableName, allergies.syncState AS syncState, allergies.id AS recordId, " +
        "allergies.patientId AS patientId, allergies.localModifiedAt AS recordedAt, " +
        "allergies.syncErrorCode AS syncErrorCode, allergies.syncErrorMessage AS syncErrorMessage, " +
        "allergies.serverVersion AS serverVersion, CAST(NULL AS TEXT) AS encounterId, CAST(NULL AS TEXT) " +
        "AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM allergies WHERE allergies.syncState IN " +
        "('FAILED', 'CONFLICT') OR (allergies.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_ALLERGIES + ")",
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
    @Query("SELECT * FROM family_history_entries WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_FAMILY_HISTORY_ENTRIES + " "
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

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_FAMILY_HISTORY_ENTRIES + " AS held, COUNT(*) AS rowCount FROM family_history_entries WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_FAMILY_HISTORY_ENTRIES + " AS held, COUNT(*) AS rowCount FROM family_history_entries WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'family_history_entries' AS tableName, family_history_entries.syncState AS syncState, " +
        "family_history_entries.id AS recordId, family_history_entries.patientId AS patientId, " +
        "family_history_entries.localModifiedAt AS recordedAt, family_history_entries.syncErrorCode AS " +
        "syncErrorCode, family_history_entries.syncErrorMessage AS syncErrorMessage, " +
        "family_history_entries.serverVersion AS serverVersion, CAST(NULL AS TEXT) AS encounterId, " +
        "CAST(NULL AS TEXT) AS caseRecordId, CAST(NULL AS TEXT) AS parentId FROM family_history_entries " +
        "WHERE family_history_entries.syncState IN ('FAILED', 'CONFLICT') OR " +
        "(family_history_entries.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_FAMILY_HISTORY_ENTRIES + ")",
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
    @Query("SELECT * FROM social_histories WHERE " + SyncSql.PENDING_ELIGIBILITY_FRAGMENT + " AND NOT " + SyncSql.HELD_SOCIAL_HISTORIES + " "
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

    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_SOCIAL_HISTORIES + " AS held, COUNT(*) AS rowCount FROM social_histories WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    fun observeSyncStateCounts(): Flow<List<SyncStateCount>>

    /** The same counts, read once (for a decision made right after a drain). */
    @Query("SELECT syncState AS syncState, " + SyncSql.HELD_SOCIAL_HISTORIES + " AS held, COUNT(*) AS rowCount FROM social_histories WHERE syncState != 'SYNCED' GROUP BY syncState, held")
    suspend fun getSyncStateCounts(): List<SyncStateCount>

    /** The FAILED and CONFLICT rows of this table, projected for the worker-facing review list.
     *  Selects exactly the FAILED and CONFLICT groups of observeSyncStateCounts, so the number on the Home
     *  card and the length of the list can never disagree. Suspend rather than a Flow:
     *  the list is fetched when a worker opens it, so it costs nothing at launch.
     *  See [FailedSyncRow]. */
    @Query(
        "SELECT 'social_histories' AS tableName, social_histories.syncState AS syncState, " +
        "social_histories.patientId AS recordId, social_histories.patientId AS patientId, " +
        "social_histories.localModifiedAt AS recordedAt, social_histories.syncErrorCode AS syncErrorCode, " +
        "social_histories.syncErrorMessage AS syncErrorMessage, social_histories.serverVersion AS " +
        "serverVersion, CAST(NULL AS TEXT) AS encounterId, CAST(NULL AS TEXT) AS caseRecordId, CAST(NULL " +
        "AS TEXT) AS parentId FROM social_histories WHERE social_histories.syncState IN ('FAILED', " +
        "'CONFLICT') OR (social_histories.syncState IN " +
        SyncSql.UNSENT_STATES + " AND " +
        SyncSql.HELD_SOCIAL_HISTORIES + ")",
    )
    suspend fun getFailedForReview(): List<FailedSyncRow>
}
