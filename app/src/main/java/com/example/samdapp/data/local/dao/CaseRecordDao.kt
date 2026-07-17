package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.domain.model.CaseStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface CaseRecordDao {
    @Insert
    suspend fun insert(caseRecord: CaseRecordEntity)

    @Query("UPDATE case_records SET status = :status, updatedAt = :updatedAt WHERE id = :caseRecordId")
    suspend fun updateStatus(caseRecordId: String, status: CaseStatus, updatedAt: Instant)

    @Query(
        "UPDATE case_records SET status = :status, assignedDoctorId = :doctorId, updatedAt = :updatedAt " +
            "WHERE id = :caseRecordId",
    )
    suspend fun assignDoctor(caseRecordId: String, doctorId: String, status: CaseStatus, updatedAt: Instant)

    @Query("SELECT * FROM case_records WHERE id = :caseRecordId")
    fun observeById(caseRecordId: String): Flow<CaseRecordEntity?>

    @Query("SELECT * FROM case_records WHERE patientId = :patientId ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestForPatient(patientId: String): Flow<CaseRecordEntity?>

    /** For doctor-continuity resolution (Part B): the case record belonging to a specific PRIOR
     *  encounter, so its [CaseRecordEntity.assignedDoctorId] can become the default for a
     *  follow-up encounter. */
    @Query("SELECT * FROM case_records WHERE encounterId = :encounterId LIMIT 1")
    fun observeByEncounterId(encounterId: String): Flow<CaseRecordEntity?>

    /** Count of this doctor's currently-open (sent, not yet reviewed) cases — the least-busy
     *  signal for auto-assigning a fresh/unrelated case (Part B). */
    @Query("SELECT COUNT(*) FROM case_records WHERE assignedDoctorId = :doctorId AND status = 'SENT_TO_DOCTOR'")
    fun observeOpenCaseCount(doctorId: String): Flow<Int>

    /** Cross-patient doctor-tracker rows (Part B's DoctorList): every case currently sent to or
     *  reviewed by a doctor, joined with the patient's name and the consultation's chief
     *  complaint. Deliberately not scoped to one patient — this IS the cross-patient view the
     *  tracker exists to show, unlike [com.example.samdapp.data.local.dao.PatientDao]'s
     *  deliberately day-scoped roster query. */
    @Query(
        "SELECT cr.id AS caseRecordId, cr.patientId AS patientId, cr.status AS status, " +
            "cr.updatedAt AS updatedAt, p.fullName AS patientFullName, c.chiefComplaint AS chiefComplaint, " +
            "d.name AS doctorName, d.specialty AS doctorSpecialty " +
            "FROM case_records cr " +
            "JOIN patients p ON p.id = cr.patientId " +
            "LEFT JOIN consultations c ON c.encounterId = cr.encounterId " +
            "LEFT JOIN doctors d ON d.id = cr.assignedDoctorId " +
            "WHERE cr.status IN ('SENT_TO_DOCTOR', 'PRESCRIPTION_RECEIVED') " +
            "ORDER BY cr.updatedAt DESC",
    )
    fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerRow>>
}
