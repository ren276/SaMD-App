package com.example.samdapp.domain.model

import java.time.Instant

/**
 * One row of a patient's consultation history (Part C) — an [Encounter] joined with its
 * [chiefComplaint] (from `consultations`) and [caseStatus] (from `case_records`). Both are
 * nullable: a worker can abandon an encounter before either exists. [caseRecordId] is null in
 * that same situation — a row with a null [caseRecordId] has no report to view.
 */
data class ConsultationHistoryEntry(
    val encounterId: String,
    val visitDate: Instant,
    val chiefComplaint: String?,
    val caseRecordId: String?,
    val caseStatus: CaseStatus?,
    /** The prior encounter this visit was logged as a follow-up to, or null for a first/standalone
     *  visit. Used to group a patient's visits into follow-up chains (see PatientSummary). */
    val followUpOfEncounterId: String?,
    /** Assigned doctor + their department/specialty; both null until the case is sent to a doctor. */
    val doctorName: String?,
    val doctorSpecialty: String?,
)
