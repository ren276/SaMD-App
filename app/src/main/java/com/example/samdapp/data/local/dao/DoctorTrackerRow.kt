package com.example.samdapp.data.local.dao

import com.example.samdapp.domain.model.CaseStatus
import java.time.Instant

/** Projection for [CaseRecordDao.observeDoctorTrackerRows] — the flat, cross-patient status list
 *  Part B's DoctorList renders. [chiefComplaint] is nullable only if the consultation row is
 *  somehow missing (shouldn't happen for a case that reached SENT_TO_DOCTOR, but the join is
 *  LEFT to avoid silently dropping a row over it). */
data class DoctorTrackerRow(
    val caseRecordId: String,
    val patientId: String,
    val status: CaseStatus,
    val updatedAt: Instant,
    val patientFullName: String,
    val chiefComplaint: String?,
    val doctorName: String?,
    val doctorSpecialty: String?,
)
