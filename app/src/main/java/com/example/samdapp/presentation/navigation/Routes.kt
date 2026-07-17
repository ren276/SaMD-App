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
 *  capture begins). */
data class ConsentRoute(val patientId: String)
data class Compounder(val patientId: String)

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
 *  Acknowledgement screen once the case is saved locally. */
data class ReportRoute(val caseRecordId: String)
data class DoctorListRoute(val caseRecordId: String)

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
        }
    }
    return null
}
