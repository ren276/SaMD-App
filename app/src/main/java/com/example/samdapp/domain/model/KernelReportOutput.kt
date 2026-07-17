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
    val inferenceTimestamp: Instant,
    val requiredHumanVerification: Boolean,
)
