package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.PatientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Insert
    suspend fun insert(patient: PatientEntity)

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
