package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PrescriptionDao {
    @Insert
    suspend fun insertPrescription(prescription: PrescriptionEntity)

    @Insert
    suspend fun insertMedicationLines(lines: List<MedicationLineEntity>)

    @Query("SELECT * FROM prescriptions WHERE caseRecordId = :caseRecordId")
    fun observeForCase(caseRecordId: String): Flow<PrescriptionEntity?>

    @Query("SELECT * FROM medication_lines WHERE prescriptionId = :prescriptionId ORDER BY position ASC")
    fun observeLines(prescriptionId: String): Flow<List<MedicationLineEntity>>

    /** Phase 6b outbox — see PatientDao.getPendingForSync's KDoc. Two tables, one DAO, matching
     *  this file's existing convention. */
    @Query("SELECT * FROM prescriptions WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingPrescriptionsForSync(): List<PrescriptionEntity>

    @Query(
        "UPDATE prescriptions SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applyPrescriptionSyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM prescriptions WHERE syncState = 'FAILED'")
    fun observePrescriptionFailedSyncCount(): Flow<Int>

    @Query("SELECT * FROM medication_lines WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
    suspend fun getPendingMedicationLinesForSync(): List<MedicationLineEntity>

    @Query(
        "UPDATE medication_lines SET syncState = :syncState, " +
            "serverVersion = COALESCE(:serverVersion, serverVersion), " +
            "syncErrorCode = :syncErrorCode, lastSyncAttemptAt = :attemptAt " +
            "WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt",
    )
    suspend fun applyMedicationLineSyncResult(id: String, syncState: SyncState, serverVersion: Int?, syncErrorCode: String?, attemptAt: Instant, sentLocalModifiedAt: Instant)

    @Query("SELECT COUNT(*) FROM medication_lines WHERE syncState = 'FAILED'")
    fun observeMedicationLineFailedSyncCount(): Flow<Int>
}
