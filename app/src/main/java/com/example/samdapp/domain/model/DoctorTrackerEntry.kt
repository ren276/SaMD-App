package com.example.samdapp.domain.model

import java.time.Instant

/** One row of Part B's cross-patient DoctorList tracker — read-only, no clinical action. */
data class DoctorTrackerEntry(
    val caseRecordId: String,
    val patientId: String,
    val patientFullName: String,
    val chiefComplaint: String?,
    val status: CaseStatus,
    val updatedAt: Instant,
    /** The doctor the case is with, and their department/specialty. */
    val doctorName: String?,
    val doctorSpecialty: String?,
)
