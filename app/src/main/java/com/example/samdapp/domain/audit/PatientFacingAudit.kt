package com.example.samdapp.domain.audit

import java.time.Instant

/** One plain-language line for the patient-facing "who has seen your file" view (DPDP
 *  right-to-access), never the raw [AuditLogEntry.action]/payload/id. Exactly one of
 *  [description] and [message] is set: [message] for a line whose words live in strings.xml,
 *  resolved by the screen (this layer has no Android resources). */
data class PatientFacingAuditEntry(
    val timestamp: Instant,
    val description: String?,
    val message: PatientFacingMessage? = null,
)

/** Patient-facing lines whose copy lives in strings.xml. */
enum class PatientFacingMessage {
    /** CASE_SENT_TO_DOCTOR: logged when a doctor is assigned on this phone, a local status change
     *  made before anything reaches the server or the doctor. "Queued", because that is what
     *  happened; the event and its meaning are unchanged, only the words. */
    QUEUED_FOR_DOCTOR_REVIEW,
}

/**
 * Maps the existing audit trail to plain language a patient can actually read — reuses data
 * already captured for [AuditLogEntry]/[AuditAction], no new capture logic. Deliberately excludes
 * technical fields (checksums, entity ids, raw action codes): every branch below returns a full
 * sentence, and the fallback for an unmapped action is generic rather than surfacing [action] text.
 */
fun List<AuditLogEntry>.toPatientFacingEntries(): List<PatientFacingAuditEntry> =
    map { entry ->
        when (entry.action) {
            AuditAction.CASE_SENT_TO_DOCTOR.value ->
                PatientFacingAuditEntry(entry.timestamp, description = null, message = PatientFacingMessage.QUEUED_FOR_DOCTOR_REVIEW)
            else -> PatientFacingAuditEntry(timestamp = entry.timestamp, description = entry.action.toPatientFacingDescription())
        }
    }

private fun String.toPatientFacingDescription(): String = when (this) {
    AuditAction.PATIENT_REGISTERED.value -> "PHC worker created your patient record"
    AuditAction.MEDICAL_HISTORY_ITEM_ADDED.value, AuditAction.MEDICATION_ADDED.value, AuditAction.ALLERGY_ADDED.value,
    AuditAction.FAMILY_HISTORY_ADDED.value, AuditAction.SOCIAL_HISTORY_SAVED.value -> "PHC worker recorded your medical background"
    AuditAction.CONSENT_RECORDED.value -> "You gave consent for this visit"
    AuditAction.ENCOUNTER_STARTED.value -> "PHC worker started a consultation"
    AuditAction.ENCOUNTER_RESUMED.value -> "PHC worker resumed your consultation"
    AuditAction.AILMENT_CAPTURED.value -> "PHC worker recorded your symptoms"
    AuditAction.AILMENT_DELETED.value -> "A symptom entry was removed from your record"
    AuditAction.VITALS_RECORDED.value -> "PHC worker recorded your vitals"
    AuditAction.EMERGENCY_OVERRIDE.value -> "Your case was flagged for urgent attention"
    AuditAction.AUDIO_CAPTURED.value -> "An audio note was recorded for your consultation"
    AuditAction.ATTACHMENT_ADDED.value -> "A photo or file was attached to your record"
    AuditAction.CONSULTATION_SAVED.value -> "PHC worker saved your consultation details"
    AuditAction.KERNEL_RESPONSE_RECEIVED.value -> "Kernel AI processed your data"
    AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED.value -> "PHC worker reviewed the AI assistant's suggestion"
    // Mapped explicitly rather than left to the generic fallback, which would read "PHC worker
    // updated your record" and attribute an AI-side non-result to a person.
    AuditAction.KERNEL_EMPTY_DIFFERENTIAL.value -> "Kernel AI did not produce a result for your case"
    AuditAction.KERNEL_UNRECOGNISED_OUTPUT.value -> "Kernel AI returned a result that was flagged for physician review"
    AuditAction.TRANSCRIPTION_COMPLETED.value -> "Your audio note was transcribed"
    AuditAction.CONSULTATION_LOCKED.value -> "Your consultation was finalized and saved"
    AuditAction.REPORT_EXPORTED.value -> "Your report was generated"
    AuditAction.REFERRAL_CREATED.value -> "You were referred to another facility"
    else -> "PHC worker updated your record"
}
