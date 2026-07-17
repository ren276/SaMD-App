package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.EncounterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: EncounterEntity)

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
