package com.example.samdapp.presentation.common

import com.example.samdapp.domain.model.CaseStatus

/** Doctor-tracker-facing label (Part B) — DRAFT/SAVED_LOCALLY never reach the tracker (it only
 *  queries SENT_TO_DOCTOR/PRESCRIPTION_RECEIVED), but the `when` stays exhaustive rather than
 *  defaulting, so a future status addition can't silently fall through. */
fun CaseStatus.doctorTrackerLabel(): String = when (this) {
    CaseStatus.DRAFT, CaseStatus.SAVED_LOCALLY -> "Sent"
    CaseStatus.SENT_TO_DOCTOR -> "Awaiting Review"
    CaseStatus.PRESCRIPTION_RECEIVED -> "Reviewed"
}

/** Consultation-history-facing label (Part C) — same enum, different audience than
 *  [doctorTrackerLabel]: a history row can legitimately be DRAFT/SAVED_LOCALLY (an encounter that
 *  never reached a doctor), which the tracker never shows. */
fun CaseStatus.historyLabel(): String = when (this) {
    CaseStatus.DRAFT -> "In progress"
    CaseStatus.SAVED_LOCALLY -> "Saved locally"
    CaseStatus.SENT_TO_DOCTOR -> "Awaiting doctor's review"
    CaseStatus.PRESCRIPTION_RECEIVED -> "Doctor's response received"
}
