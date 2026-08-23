package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.config.DeviceInfoProvider
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Generates a [KernelReportOutput] for a case — REQ-HAN-07.
 *
 * **Primary path**: calls [remoteKernelSource] (backed by Retrofit + FastAPI, `POST /api/v1/assess`).
 * Maps the domain [com.example.samdapp.domain.kernel.KernelAssessmentResult] to
 * [KernelReportOutput] using the specified field bindings:
 * - `predictedCondition` (from `condition_tier` top differential) → [KernelReportOutput.predictedCondition]
 * - `confidenceScore`    (from `probability`)                     → [KernelReportOutput.confidenceScore]
 * - `evidenceFor`        (from `evidence_for`)                    → [KernelReportOutput.evidenceFor]
 * - `evidenceAgainst`    (from `evidence_against`)                → [KernelReportOutput.evidenceAgainst]
 * - `triageUrgency`      (from `triage_urgency`)                  → prepended to [KernelReportOutput.reasoningSummary]
 *
 * **Empty-differential path**: a 200 carrying an empty `differential_diagnosis` is NOT a success.
 * The model ran and produced no usable assessment, which is operationally identical to not having
 * run, so [tryRealApi] routes it straight to [buildUnavailableOutput] and records an
 * [AuditAction.KERNEL_EMPTY_DIFFERENTIAL] breadcrumb. It deliberately does not consult
 * [kernelFallbackSource] first, so even a dev build cannot substitute a mock scenario for it.
 *
 * **Fallback path**: if the real call fails for ANY reason (IOException, HttpException, timeout,
 * server offline), the exception is caught and logged, and [kernelFallbackSource] is asked for a
 * fallback. What that returns depends entirely on the build flavor — dev binds a mock scenario
 * source, staging/prod bind a source that always returns null (see `MockBoundaryModule` and its
 * flavor-specific overrides). A null fallback becomes an honest
 * [InferenceSource.UNAVAILABLE] result via [buildUnavailableOutput] — never a fabricated
 * diagnosis outside dev. The app never crashes because the ML server is unreachable; in
 * staging/prod it now tells the truth about that instead of hiding it.
 *
 * [remoteKernelSource] and [kernelFallbackSource] are domain interfaces — the use case never
 * imports Retrofit types (Clean Architecture boundary maintained, same pattern as [com.example.samdapp.domain.vitalssource.VitalsSource]
 * / [com.example.samdapp.domain.transcription.TranscriptionService]).
 *
 * [patientAge] and [patientSex] are clinical signals (not PII) required by the XGBoost
 * classifier. They are passed as separate optional parameters, NOT added to [KernelPayload]
 * (which enforces the pseudonymization boundary in [SendToKernelUseCase]).
 */
class GenerateKernelReportUseCase @Inject constructor(
    private val kernelReportRepository: KernelReportRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val remoteKernelSource: RemoteKernelSource,
    private val kernelFallbackSource: KernelFallbackSource,
    private val auditLogger: AuditLogger,
) {
    companion object {
        const val HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD = 0.90
        /** Model-version tag stamped on a dev-flavor mock fallback result — see
         *  `MockKernelFallbackSource` in `src/dev/`. */
        const val MOCK_MODEL_VERSION = "mock-kernel-v0.1"
        private val logger = Logger.getLogger("KernelUseCase")

        /** Optional [KernelPayload] signals considered for [dataQualityScore] — the whitelisted
         *  fields that may or may not be present, not the always-required ones (chiefComplaint,
         *  vitals, caseToken). */
        private fun dataQualityScore(payload: KernelPayload): Double {
            val signals = listOf(
                payload.durationBucket != null,
                payload.severityScore != null,
                !payload.relevantHistory.isNullOrBlank(),
                !payload.transcription.isNullOrBlank(),
                payload.attachments.isNotEmpty(),
            )
            return signals.count { it }.toDouble() / signals.size
        }
    }

    /**
     * @param caseRecordId The case's primary key (used as the correlation token in the API request).
     * @param payload The pseudonymized kernel payload (vitals + chief complaint etc.).
     * @param patientAge Patient age in years — required by the XGBoost classifier; null means the
     *   API request will use a safe default (30) and the result treated as lower confidence.
     * @param patientSex Biological sex string from [com.example.samdapp.domain.model.Patient.biologicalSex]; null means default "U" (unknown).
     */
    suspend operator fun invoke(
        caseRecordId: String,
        payload: KernelPayload,
        patientAge: Int? = null,
        patientSex: String? = null,
    ): Result<KernelReportOutput> {
        val inferenceStartedAt = Instant.now()

        val output = tryRealApi(caseRecordId, payload, patientAge, patientSex, inferenceStartedAt)
            ?: kernelFallbackSource.fallback(caseRecordId, payload, inferenceStartedAt, dataQualityScore(payload))
            ?: buildUnavailableOutput(caseRecordId, payload, inferenceStartedAt)

        return kernelReportRepository.save(output).map { output }
    }

    // ── Real API call ──────────────────────────────────────────────────────────

    /**
     * Attempts the remote kernel call via [remoteKernelSource]. Returns null on any failure
     * — the caller falls back to [kernelFallbackSource], then [buildUnavailableOutput]. This
     * method never throws.
     *
     * A 200 with an empty differential is the one case that returns a NON-null
     * [InferenceSource.UNAVAILABLE] output rather than null: returning null there would send it
     * through [kernelFallbackSource] first, which on a dev build would answer with a mock
     * scenario and label a reached-but-empty kernel as [InferenceSource.MOCK_FALLBACK].
     */
    private suspend fun tryRealApi(
        caseRecordId: String,
        payload: KernelPayload,
        patientAge: Int?,
        patientSex: String?,
        inferenceStartedAt: Instant,
    ): KernelReportOutput? {
        return try {
            val result = remoteKernelSource.assess(
                payload = payload,
                patientAge = patientAge ?: 30,
                patientSex = patientSex ?: "U",
            )
            logger.info("Kernel API success — case $caseRecordId, triage=${result.triageUrgency}")

            if (result.predictedCondition == null) {
                // 200 with an empty differential_diagnosis: the model produced no usable
                // assessment. Operationally identical to not running. Never fabricate.
                //
                // Logged here rather than at either caller because both SendingViewModel's
                // initial run and RetryKernelAssessmentUseCase's retry pass through this branch,
                // and only here is the empty-200 fact distinguishable from an unreachable kernel
                // (which exits at the catch below, never reaching this line). The user sees the
                // same UNAVAILABLE state either way; the audit trail is what separates
                // "kernel down" from "kernel returning empty differentials" in field analysis.
                //
                // Payload carries server-verbatim values and a measured zero only: no condition
                // string, no confidence, no PHI. caseRecordId is the pseudonymous case token
                // every other action already logs.
                auditLogger.log(
                    action = AuditAction.KERNEL_EMPTY_DIFFERENTIAL,
                    caseRecordId = caseRecordId,
                    payload = auditPayload(
                        "triageUrgency" to result.triageUrgency,
                        "modelVersion" to result.modelVersion,
                        "safetyScreenPassed" to result.safetyScreenPassed.toString(),
                        "differentialCount" to "0",
                    ),
                )
                return buildUnavailableOutput(caseRecordId, payload, inferenceStartedAt)
            }

            val inferenceEndedAt = Instant.now()
            val confidence = result.confidenceScore

            // Map triage_urgency string → our existing UrgencyLevel enum
            val urgency = when (result.triageUrgency.uppercase()) {
                "EMERGENCY", "EMERGENT" -> UrgencyLevel.EMERGENCY
                "URGENT"                -> UrgencyLevel.URGENT
                else                    -> UrgencyLevel.ROUTINE
            }

            // Infer risk category from confidence + urgency
            val risk = when {
                urgency == UrgencyLevel.EMERGENCY  -> RiskCategory.HIGH
                confidence >= 0.85                 -> RiskCategory.LOW
                confidence >= 0.65                 -> RiskCategory.MODERATE
                else                               -> RiskCategory.HIGH
            }

            val reasoningSummary = buildString {
                append("ML risk model triage: ${result.triageUrgency}. ")
                if (!result.safetyScreenPassed) append("⚠ Safety screen did not pass. ")
                if (result.recommendedInvestigations.isNotEmpty()) {
                    append("Recommended investigations: ${result.recommendedInvestigations.joinToString(", ")}. ")
                }
                append("Top differential (${result.predictedCondition}) at ${(confidence * 100).toInt()}% confidence.")
            }

            KernelReportOutput(
                id = UUID.randomUUID().toString(),
                caseRecordId = caseRecordId,
                predictedCondition = result.predictedCondition,
                confidenceScore = confidence,
                differentials = result.differentials,
                reasoningSummary = reasoningSummary,
                evidenceFor = result.evidenceFor,
                evidenceAgainst = result.evidenceAgainst,
                modelVersion = result.modelVersion ?: "remote-kernel",
                icdCode = null, // Real endpoint doesn't return ICD codes in this contract shape
                deviceId = deviceInfoProvider.deviceId(),
                softwareVersion = deviceInfoProvider.softwareVersion(),
                dataQualityScore = dataQualityScore(payload),
                uncertaintyScore = 1.0 - confidence,
                riskCategory = risk,
                urgencyLevel = urgency,
                inferenceStartedAt = inferenceStartedAt,
                inferenceEndedAt = inferenceEndedAt,
                requiredHumanVerification = confidence < HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
                inferenceSource = InferenceSource.REAL_INFERENCE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Any failure (network down, timeout, HTTP error, parse error, server offline) is
            // logged here and returns null — the caller tries kernelFallbackSource next, then
            // buildUnavailableOutput. The app never crashes when the ML server is unreachable.
            logger.warning("Kernel API unavailable — trying fallback source. Reason: ${e.message}")
            null
        }
    }

    // ── Honest unavailable state ───────────────────────────────────────────────

    /**
     * The real call failed AND [kernelFallbackSource] had nothing to offer (always true in
     * staging/prod, since those flavors bind a no-op fallback). No fabricated diagnosis, no
     * confidence score that means anything — every clinical field says plainly that no
     * assessment happened, tagged [InferenceSource.UNAVAILABLE] so every downstream surface
     * (report renderer, doctor review screen) can render it as a distinguishable failure state
     * instead of silently showing nothing.
     */
    private fun buildUnavailableOutput(
        caseRecordId: String,
        payload: KernelPayload,
        inferenceStartedAt: Instant,
    ): KernelReportOutput = KernelReportOutput(
        id = UUID.randomUUID().toString(),
        caseRecordId = caseRecordId,
        predictedCondition = "Assessment unavailable",
        confidenceScore = 0.0,
        differentials = emptyList(),
        // Reach-neutral by design: this same output is produced both when the kernel could not
        // be reached and when it answered 200 with an empty differential. Naming either cause
        // here would be false half the time, and the cause belongs in the audit trail, not on a
        // clinical record. What is true in both cases is that no assessment exists.
        reasoningSummary = "Assessment unavailable. The AI did not produce a result for this " +
            "case and no diagnosis was generated. Tap Retry to run the assessment again.",
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        modelVersion = "unavailable",
        icdCode = null,
        deviceId = deviceInfoProvider.deviceId(),
        softwareVersion = deviceInfoProvider.softwareVersion(),
        dataQualityScore = dataQualityScore(payload),
        uncertaintyScore = 1.0,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = inferenceStartedAt,
        inferenceEndedAt = Instant.now(),
        requiredHumanVerification = true,
        inferenceSource = InferenceSource.UNAVAILABLE,
    )
}
