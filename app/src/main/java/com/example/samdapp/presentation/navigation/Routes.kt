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
