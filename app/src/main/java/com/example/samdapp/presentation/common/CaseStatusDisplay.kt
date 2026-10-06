package com.example.samdapp.presentation.common

import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.audit.PatientFacingMessage
import com.example.samdapp.domain.model.CaseStatus

/**
 * Case status as the worker reads it. SENT_TO_DOCTOR is set on this phone, with no network
 * involved, when a doctor is assigned or Sync now runs; the case reaches the doctor only once the
 * outbox has pushed it. So wherever a case would read as with the doctor, [caseOnServer]
 * (`isServerPresent`, the same rule the assess gate uses) decides between "with the doctor" and
 * "Queued for doctor, not yet on the server". PRESCRIPTION_RECEIVED came back from the doctor and
 * is shown as it is.
 *
 * The `when`s stay exhaustive rather than defaulting, so a future status cannot silently fall
 * through.
 */
@StringRes
fun CaseStatus.doctorTrackerLabelRes(caseOnServer: Boolean): Int = when (this) {
    // The tracker queries only SENT_TO_DOCTOR and PRESCRIPTION_RECEIVED today; these read
    // "Queued", never "Sent", so a query change cannot ship a false label.
    CaseStatus.DRAFT, CaseStatus.SAVED_LOCALLY, CaseStatus.PENDING_SYNC -> R.string.case_status_queued
    CaseStatus.SENT_TO_DOCTOR ->
        if (caseOnServer) R.string.case_status_awaiting_review else R.string.case_status_queued_for_doctor_not_on_server
    CaseStatus.PRESCRIPTION_RECEIVED -> R.string.case_status_reviewed
    CaseStatus.ABANDONED -> R.string.case_status_abandoned
}

/** Consultation-history label: same enum, a different audience than [doctorTrackerLabelRes]. A
 *  history row can legitimately be DRAFT or SAVED_LOCALLY (an encounter that never reached a
 *  doctor), which the tracker never shows. */
@StringRes
fun CaseStatus.historyLabelRes(caseOnServer: Boolean): Int = when (this) {
    CaseStatus.DRAFT -> R.string.case_history_in_progress
    CaseStatus.SAVED_LOCALLY -> R.string.case_history_saved_locally
    CaseStatus.PENDING_SYNC -> R.string.case_history_queued_will_send
    CaseStatus.SENT_TO_DOCTOR ->
        if (caseOnServer) R.string.case_history_awaiting_doctors_review else R.string.case_status_queued_for_doctor_not_on_server
    CaseStatus.PRESCRIPTION_RECEIVED -> R.string.case_history_doctors_response_received
    CaseStatus.ABANDONED -> R.string.case_history_abandoned_restarted
}

/** The doctor-review heading on the patient summary, shown for SENT_TO_DOCTOR and
 *  PRESCRIPTION_RECEIVED only. */
@StringRes
fun doctorReviewHeaderRes(status: CaseStatus, caseOnServer: Boolean): Int =
    if (status == CaseStatus.PRESCRIPTION_RECEIVED) {
        R.string.case_review_header_received
    } else {
        status.historyLabelRes(caseOnServer)
    }

/** The words for a patient-facing audit line whose copy lives in strings.xml. */
@get:StringRes
val PatientFacingMessage.textRes: Int
    get() = when (this) {
        PatientFacingMessage.QUEUED_FOR_DOCTOR_REVIEW -> R.string.patient_audit_queued_for_doctor_review
    }
