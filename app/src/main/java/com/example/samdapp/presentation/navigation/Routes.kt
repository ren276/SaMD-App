package com.example.samdapp.presentation.navigation

data object Home

/** Bottom-nav tab: searchable roster beyond just today (see [Patients] entry in AppNavHost). */
data object Patients

/** Bottom-nav tab: this device's own sent-referral outbox. */
data object Referrals

/** Bottom-nav tab: signed-in worker's session info, audit summary, sync toggle, sign-out. */
data object Profile

data object AbhaEntry
data object AbhaSignUp
data object AbhaLogin
data class AbhaOtpRoute(val abhaId: String)

/** [abhaId] is null for a manual/no-ABHA registration ("Skip" on [AbhaEntry]); non-null when
 *  reached via the mock ABHA sign-up/login flow, so [RegisterViewModel] autofills from it. */
data class Register(val abhaId: String? = null)
data class MedicalBackground(val patientId: String)
data class PatientSummary(val patientId: String)

/** Digital consent checkpoint (REQ-TRS-01) — shown once, before Compounder (where ailment
 *  capture begins). [followUpOfEncounterId] carries the worker's optional "this is a follow-up
 *  to a prior visit" pick from PatientSummary's consultation history (Part C) through to
 *  [Compounder]/[com.example.samdapp.domain.usecase.StartCaseUseCase], which stamps it onto the
 *  new [com.example.samdapp.domain.model.Encounter]. */
data class ConsentRoute(val patientId: String, val followUpOfEncounterId: String? = null)
data class Compounder(val patientId: String, val followUpOfEncounterId: String? = null)

/** Terminal state reached from [Compounder] when [com.example.samdapp.domain.usecase.
 *  CheckEmergencyThresholdsUseCase] trips (REQ-TRS-02) — no patientId of its own; the persistent
 *  patient banner still resolves from the [Compounder] entry beneath it on the back stack. */
data class EmergencyOverrideRoute(val reasons: List<String>)
data class ConsultationRoute(
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val chiefComplaint: String,
)
data class SendingRoute(
    val caseRecordId: String,
    val consultationId: String,
    val audioUri: String?,
    val encounterId: String,
)
/** AI Assessment Panel (REQ-HAN-07) — shown right after the mocked kernel handoff, before the
 *  case proceeds to transcription/save. */
data class KernelAssessmentRoute(
    val caseRecordId: String,
    val consultationId: String,
    val audioUri: String?,
)
data class TranscriptionRoute(val consultationId: String, val audioUri: String, val caseRecordId: String)
data class AcknowledgementRoute(val caseRecordId: String)

/** Preliminary clinical report (Phase 3) — worker-facing preview + PDF export, reached from the
 *  Acknowledgement screen once the case is saved locally. Reused read-only for past-encounter
 *  history (Part C) and the doctor-tracker (Part B) — this screen has no edit affordances on the
 *  report content itself (the referral sheet is a separate, additive action). */
data class ReportRoute(val caseRecordId: String)

/** A single patient's follow-up chain — every visit linked to [rootEncounterId], newest first,
 *  each opening its own single-consult [ReportRoute]. Keeps the Consultation History list clean
 *  (one row per chain) while still letting the worker drill into all of a chain's visits. Reports
 *  are never merged; each visit is its own document. */
data class ConsultationChainRoute(val patientId: String, val rootEncounterId: String)

/** Bottom-nav-adjacent, cross-patient, read-only status tracker (Part B) — every case currently
 *  sent to a doctor, across all patients. No longer the per-case doctor picker (that step is now
 *  automatic — see [DoctorAssignmentConfirmRoute] for the one case where a worker still chooses:
 *  confirming/switching a continuity-of-care doctor on a follow-up visit). */
data object DoctorListRoute

/** Narrow doctor-continuity confirmation (Part B) — shown only when the new case is a follow-up
 *  to a prior visit with a resolved doctor; a fresh/unrelated case is auto-assigned silently and
 *  never reaches this screen. */
data class DoctorAssignmentConfirmRoute(val caseRecordId: String)

/**
 * The patient the current back-stack entry is about, or null if none (Home, Register).
 * Scans from the top down so routes that don't carry a patientId (Sending, Transcription,
 * Acknowledgement, DoctorList) inherit it from the encounter route beneath them — keeping
 * the patient identity banner visible for the whole encounter.
 */
fun currentPatientId(backStack: List<Any>): String? {
    for (route in backStack.asReversed()) {
        when (route) {
            is MedicalBackground -> return route.patientId
            is PatientSummary -> return route.patientId
            is ConsentRoute -> return route.patientId
            is Compounder -> return route.patientId
            is ConsultationRoute -> return route.patientId
            is ConsultationChainRoute -> return route.patientId
        }
    }
    return null
}
