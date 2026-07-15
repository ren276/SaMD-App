package com.example.samdapp.domain.model

/**
 * The exact shape sent across the clinical-kernel boundary. Structurally decoupled from patient
 * identity: no parameter here is of type [Patient], so a Patient object cannot reach the kernel
 * even by mistake — enforced by [SendToKernelUseCase][com.example.samdapp.domain.usecase.SendToKernelUseCase]'s
 * own signature, which only accepts [VitalsReading] + [Consultation] + a token. See
 * docs/quality/risk-management-file.md H-10 and docs/requirements/software-requirements.md REQ-HAN-06.
 *
 * [caseToken] is [CaseRecord.id] reused as the correlation token — see the use case's KDoc for
 * why, and for the future-real-kernel caveat.
 *
 * Deliberately excludes: [Patient.fullName], [Patient.aadhaarNumber], [Patient.abhaNumber],
 * [Patient.mobileNumber], [Patient.guardianOrSpouseName], and every address field. Also excludes
 * [Consultation.onset]/[Consultation.aggravatingFactors]/[Consultation.relievingFactors]/
 * [Consultation.impactOnDailyActivities] — not because they're identifying, but because they
 * weren't in the whitelisted field set; add them deliberately if the kernel needs them, don't
 * default to "all of Consultation."
 *
 * Attachments pass through unmodified (image/video/audio/affected-area photo) — they're
 * clinically necessary and are not text fields carrying patient identity, so no
 * blurring/stripping is applied.
 */
data class KernelPayload(
    val caseToken: String,
    val vitals: VitalsReading,
    val chiefComplaint: String,
    val durationBucket: String?,
    val severityScore: Int?,
    val relevantHistory: String?,
    val transcription: String?,
    val attachments: List<Attachment>,
)
