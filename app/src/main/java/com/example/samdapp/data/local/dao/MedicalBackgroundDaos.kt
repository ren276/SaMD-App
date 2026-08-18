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
    @Query("SELECT * FROM medical_history_items WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<MedicalHistoryItemEntity>

    @Query(
        "UPDATE medical_history_items SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM medical_history_items WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}

@Dao
interface MedicationEntryDao {
    @Insert
    suspend fun insert(entry: MedicationEntryEntity)

    @Query("SELECT * FROM medication_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<MedicationEntryEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM medication_entries WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<MedicationEntryEntity>

    @Query(
        "UPDATE medication_entries SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM medication_entries WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}

@Dao
interface AllergyDao {
    @Insert
    suspend fun insert(allergy: AllergyEntity)

    @Query("SELECT * FROM allergies WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<AllergyEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM allergies WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<AllergyEntity>

    @Query(
        "UPDATE allergies SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM allergies WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}

@Dao
interface FamilyHistoryEntryDao {
    @Insert
    suspend fun insert(entry: FamilyHistoryEntryEntity)

    @Query("SELECT * FROM family_history_entries WHERE patientId = :patientId ORDER BY createdAt ASC")
    fun observeForPatient(patientId: String): Flow<List<FamilyHistoryEntryEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. */
    @Query("SELECT * FROM family_history_entries WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<FamilyHistoryEntryEntity>

    @Query(
        "UPDATE family_history_entries SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM family_history_entries WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
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
    @Query("SELECT * FROM social_histories WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingForSync(): List<SocialHistoryEntity>

    @Query(
        "UPDATE social_histories SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE patientId = :patientId AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applySyncResult(patientId: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM social_histories WHERE syncState = 'FAILED'")
    fun observeFailedSyncCount(): Flow<Int>
}
