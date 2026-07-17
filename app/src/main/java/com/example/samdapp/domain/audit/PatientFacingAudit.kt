package com.example.samdapp.domain.audit

import java.time.Instant

/** One plain-language line for the patient-facing "who has seen your file" view (DPDP
 *  right-to-access) — never the raw [AuditLogEntry.action]/payload/id. */
data class PatientFacingAuditEntry(val timestamp: Instant, val description: String)

/**
 * Maps the existing audit trail to plain language a patient can actually read — reuses data
 * already captured for [AuditLogEntry]/[AuditAction], no new capture logic. Deliberately excludes
 * technical fields (checksums, entity ids, raw action codes): every branch below returns a full
 * sentence, and the fallback for an unmapped action is generic rather than surfacing [action] text.
 */
fun List<AuditLogEntry>.toPatientFacingEntries(): List<PatientFacingAuditEntry> =
    map { entry -> PatientFacingAuditEntry(timestamp = entry.timestamp, description = entry.action.toPatientFacingDescription()) }

private fun String.toPatientFacingDescription(): String = when (this) {
    "patient_registered" -> "PHC worker created your patient record"
    "medical_history_item_added", "medication_added", "allergy_added",
    "family_history_added", "social_history_saved" -> "PHC worker recorded your medical background"
    AuditAction.CONSENT_RECORDED -> "You gave consent for this visit"
    "encounter_started" -> "PHC worker started a consultation"
    AuditAction.ENCOUNTER_RESUMED -> "PHC worker resumed your consultation"
    AuditAction.AILMENT_CAPTURED -> "PHC worker recorded your symptoms"
    AuditAction.AILMENT_DELETED -> "A symptom entry was removed from your record"
    AuditAction.AILMENT_VISIBILITY_CHANGED -> "A symptom entry's privacy setting was changed"
    "vitals_recorded" -> "PHC worker recorded your vitals"
    AuditAction.EMERGENCY_OVERRIDE -> "Your case was flagged for urgent attention"
    "audio_captured" -> "An audio note was recorded for your consultation"
    "attachment_added" -> "A photo or file was attached to your record"
    "consultation_saved" -> "PHC worker saved your consultation details"
    "kernel_response_received" -> "Kernel AI processed your data"
    AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED -> "PHC worker reviewed the AI assistant's suggestion"
    "transcription_completed" -> "Your audio note was transcribed"
    "consultation_locked" -> "Your consultation was finalized and saved"
    "case_sent_to_doctor" -> "Sent for doctor review"
    "report_exported" -> "Your report was generated"
    AuditAction.REFERRAL_CREATED -> "You were referred to another facility"
    else -> "PHC worker updated your record"
}
