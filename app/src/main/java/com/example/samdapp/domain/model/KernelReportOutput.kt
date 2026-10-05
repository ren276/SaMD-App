package com.example.samdapp.domain.model

import com.example.samdapp.domain.kernel.KernelFailure
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
    /** The classifier's own version string, or null when the response carried none. Null is the
     *  only honest value there: the earlier "remote-kernel" and "unavailable" placeholders looked
     *  like versions and were not. */
    val modelVersion: String?,
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
    /** Which path produced this record — REQ-HAN-08 (audit-traceability addendum). Stamped once
     *  in [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase] at the real-vs-mock
     *  branch point; never inferred after the fact. */
    val inferenceSource: InferenceSource,
    /** Why no assessment exists, when [inferenceSource] is [InferenceSource.UNAVAILABLE] and a
     *  cause is known. Null on every successful row, and also null on the two UNAVAILABLE rows
     *  where no [KernelFailure] is true: a kernel that answered 200 with an empty differential
     *  (reached, answered, nothing to say) and a case whose payload could not be built at all
     *  (nothing was sent).
     *
     *  Device-local. Deliberately absent from `KernelReportSyncPayloadDto`, matching
     *  `EvaluateReportEntity.failureCode`, which the backend also never receives: this is a
     *  remedy for the worker holding the phone, not a clinical fact about the patient. */
    val failureCode: KernelFailure?,
    /** The backend's `X-Request-ID` for the call that produced this report (lowercase UUID4), the
     *  link to the stored model output. Stored on every [inferenceSource] when the response
     *  carried a valid one, because the backend holds the output whichever way the device then
     *  labelled it. Null when there was no response, or the header was missing or malformed. */
    val requestId: String? = null,
    /** `model_metadata.calibrated`, when the classifier said. Null on every non-REAL row. */
    val modelCalibrated: Boolean? = null,
    /** [com.example.samdapp.domain.kernel.KernelTriageRules.DERIVATION_RULE_VERSION] when the
     *  device derived urgency, risk and verification from a real response; null otherwise. */
    val derivationRuleVersion: String? = null,
)
