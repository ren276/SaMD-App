package com.example.samdapp.presentation.common

import com.example.samdapp.R
import com.example.samdapp.domain.audit.PatientFacingMessage
import com.example.samdapp.domain.model.CaseStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the worker sees a case as with the doctor, it must be true: SENT_TO_DOCTOR is a local
 * status, set on this phone with no network involved, so until the case is on the server
 * (isServerPresent) the label is "Queued for doctor, not yet on the server". Asserts which string
 * is selected, never the words, per the copy discipline in SlmRefusalCopyTest.
 */
class CaseStatusDisplayTest {

    @Test
    fun `tracker label for a case with the doctor depends on server presence`() {
        assertEquals(R.string.case_status_queued_for_doctor_not_on_server, CaseStatus.SENT_TO_DOCTOR.doctorTrackerLabelRes(caseOnServer = false))
        assertEquals(R.string.case_status_awaiting_review, CaseStatus.SENT_TO_DOCTOR.doctorTrackerLabelRes(caseOnServer = true))
    }

    @Test
    fun `tracker label for a case not yet sent is Queued, never Sent`() {
        listOf(CaseStatus.DRAFT, CaseStatus.SAVED_LOCALLY, CaseStatus.PENDING_SYNC).forEach {
            assertEquals(it.name, R.string.case_status_queued, it.doctorTrackerLabelRes(caseOnServer = false))
        }
    }

    @Test
    fun `history label for a case with the doctor depends on server presence`() {
        assertEquals(R.string.case_status_queued_for_doctor_not_on_server, CaseStatus.SENT_TO_DOCTOR.historyLabelRes(caseOnServer = false))
        assertEquals(R.string.case_history_awaiting_doctors_review, CaseStatus.SENT_TO_DOCTOR.historyLabelRes(caseOnServer = true))
        assertEquals(R.string.case_history_queued_on_phone, CaseStatus.PENDING_SYNC.historyLabelRes(caseOnServer = false))
    }

    @Test
    fun `patient summary header for a case with the doctor depends on server presence`() {
        assertEquals(R.string.case_status_queued_for_doctor_not_on_server, doctorReviewHeaderRes(CaseStatus.SENT_TO_DOCTOR, caseOnServer = false))
        assertEquals(R.string.case_history_awaiting_doctors_review, doctorReviewHeaderRes(CaseStatus.SENT_TO_DOCTOR, caseOnServer = true))
        assertEquals(R.string.case_review_header_received, doctorReviewHeaderRes(CaseStatus.PRESCRIPTION_RECEIVED, caseOnServer = true))
    }

    @Test
    fun `the patient-facing queued message has its own string`() {
        assertEquals(R.string.patient_audit_queued_for_doctor_review, PatientFacingMessage.QUEUED_FOR_DOCTOR_REVIEW.textRes)
    }
}
