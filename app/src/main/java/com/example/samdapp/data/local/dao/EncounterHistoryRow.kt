package com.example.samdapp.data.local.dao

import com.example.samdapp.domain.model.CaseStatus
import java.time.Instant

/** Projection for [EncounterDao.observeHistoryForPatient] — one encounter joined with its
 *  consultation's chief complaint and its case record's status (both nullable). */
data class EncounterHistoryRow(
    val encounterId: String,
    val startedAt: Instant,
    val chiefComplaint: String?,
    val caseRecordId: String?,
    val status: CaseStatus?,
    val followUpOfEncounterId: String?,
    /** Assigned doctor (null until the case is sent) — name + specialty for the "which doctor /
     *  which dept is in the loop" line on history rows. */
    val doctorName: String?,
    val doctorSpecialty: String?,
)
