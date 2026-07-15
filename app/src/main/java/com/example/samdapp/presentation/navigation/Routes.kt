package com.example.samdapp.presentation.navigation

data object Home
data object Register
data class MedicalBackground(val patientId: String)
data class PatientSummary(val patientId: String)
data class Compounder(val patientId: String)
data class ConsultationRoute(
    val patientId: String,
    val encounterId: String,
    val caseRecordId: String,
    val chiefComplaint: String,
)
data class SendingRoute(val caseRecordId: String, val consultationId: String, val audioUri: String?)
data class TranscriptionRoute(val consultationId: String, val audioUri: String, val caseRecordId: String)
data class AcknowledgementRoute(val caseRecordId: String)
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
            is Compounder -> return route.patientId
            is ConsultationRoute -> return route.patientId
        }
    }
    return null
}
