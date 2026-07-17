package com.example.samdapp.domain.model

import java.time.Instant

/**
 * The clinical kernel's assessment for a case — the AI-produced section appended to the report
 * (Phase 4). This is the kernel's RESPONSE; distinct from [KernelPayload], which is the
 * pseudonymized outbound request. Net-new in this overhaul (no prior AiKernelResponse existed).
 *
 * [confidenceScore] is 0.0..1.0. [requiredHumanVerification] is driven by the confidence threshold
 * (< 0.90 per the app's existing convention) and gates the doctor's mandatory review — the kernel
 * is never presented as autonomous or validated while mocked (REQ-HAN-05).
 *
 * [differentials], [evidenceFor], [evidenceAgainst] persist as JSON string lists (Room converter).
 */
data class KernelReportOutput(
    val id: String,
    val caseRecordId: String,
    val predictedCondition: String,
    val confidenceScore: Double,
    val differentials: List<String>,
    val reasoningSummary: String,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val modelVersion: String,
    /** Mock kernel's structured ICD-10 suggestion — null when the complaint didn't match a
     *  well-characterized scenario (the default/unmatched fallback deliberately doesn't code
     *  one; the doctor's own diagnosis, not this app, is the source of a real ICD code). */
    val icdCode: String?,
    val deviceId: String,
    val softwareVersion: String,
    /** Simple heuristic: proportion of the optional [KernelPayload] fields that were populated. */
    val dataQualityScore: Double?,
    /** Mock complement of [confidenceScore] (`1 - confidenceScore`) — a placeholder for a real
     *  kernel's own uncertainty estimate, not a second independent signal in this mock. */
    val uncertaintyScore: Double?,
    val riskCategory: RiskCategory,
    val urgencyLevel: UrgencyLevel,
    val inferenceStartedAt: Instant,
    val inferenceEndedAt: Instant,
    val requiredHumanVerification: Boolean,
)
