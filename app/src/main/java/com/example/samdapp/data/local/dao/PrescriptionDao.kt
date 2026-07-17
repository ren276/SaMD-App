package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import kotlinx.coroutines.flow.Flow

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
}
