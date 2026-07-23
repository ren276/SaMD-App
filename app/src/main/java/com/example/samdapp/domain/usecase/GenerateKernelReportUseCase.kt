package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.config.DeviceInfoProvider
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import javax.inject.Inject
import kotlin.random.Random

// ──────────────────────────────────────────────────────────────────────────────
// Mock scenario table (unchanged from Phase 4 — used as fallback only)
// ──────────────────────────────────────────────────────────────────────────────

/** One curated (predictedCondition, differentials, reasoning, evidenceFor, evidenceAgainst)
 *  scenario, matched against [KernelPayload.chiefComplaint] by keyword — not real inference, but
 *  gives the mock demo-credible variety instead of static/random text (REQ-HAN-07).
 *  [icdCode]/[riskCategory]/[urgencyLevel] are the Part A report-capture addendum: plausible,
 *  per-scenario values, not randomized — these feed the exported report only, never a
 *  doctor-facing UI in this app. */
private data class MockScenario(
    val keywords: List<String>,
    val predictedCondition: String,
    val icdCode: String?,
    val differentials: List<String>,
    val reasoningSummary: String,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val confidenceRange: ClosedFloatingPointRange<Double>,
    val riskCategory: RiskCategory,
    val urgencyLevel: UrgencyLevel,
)

private val SCENARIOS = listOf(
    MockScenario(
        keywords = listOf("fever", "chills", "temperature"),
        predictedCondition = "Viral fever",
        icdCode = "R50.9",
        differentials = listOf("Dengue", "Typhoid", "Malaria"),
        reasoningSummary = "Elevated temperature with reported chills and no localized findings is " +
            "most consistent with a self-limiting viral illness; vector-borne and enteric causes " +
            "remain on the differential given the regional prevalence.",
        evidenceFor = listOf("Documented fever/chills in chief complaint", "No focal infection reported"),
        evidenceAgainst = listOf("No rash or bleeding tendency reported", "No sustained high-grade pattern documented"),
        confidenceRange = 0.72..0.94,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("cough", "cold", "throat", "breath"),
        predictedCondition = "Upper respiratory tract infection",
        icdCode = "J06.9",
        differentials = listOf("Bronchitis", "Pneumonia", "Allergic rhinitis"),
        reasoningSummary = "Cough with upper-airway symptoms and no reported respiratory distress " +
            "points to a common URTI; lower-respiratory and allergic causes are kept as differentials.",
        evidenceFor = listOf("Cough/throat symptoms in chief complaint"),
        evidenceAgainst = listOf("No reported breathlessness or chest pain"),
        confidenceRange = 0.68..0.90,
        riskCategory = RiskCategory.LOW,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("stomach", "abdomen", "vomit", "diarrh"),
        predictedCondition = "Acute gastroenteritis",
        icdCode = "A09",
        differentials = listOf("Food poisoning", "Peptic ulcer disease", "Appendicitis"),
        reasoningSummary = "Abdominal symptoms without a documented localized/rebound pattern favor " +
            "a self-limiting gastroenteritis; a surgical abdomen is kept as a differential given the " +
            "limited on-device exam data available to the kernel.",
        evidenceFor = listOf("Abdominal/GI symptoms in chief complaint"),
        evidenceAgainst = listOf("No documented localized rigidity or rebound tenderness"),
        confidenceRange = 0.60..0.88,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.URGENT,
    ),
    MockScenario(
        keywords = listOf("head", "migraine", "dizz"),
        predictedCondition = "Tension-type headache",
        icdCode = "G44.2",
        differentials = listOf("Migraine", "Sinusitis", "Hypertension-related headache"),
        reasoningSummary = "Headache without reported neurological deficit or photophobia is most " +
            "consistent with a tension-type pattern; vascular and sinus causes remain differentials.",
        evidenceFor = listOf("Headache reported as chief complaint"),
        evidenceAgainst = listOf("No neurological deficit or visual disturbance reported"),
        confidenceRange = 0.65..0.91,
        riskCategory = RiskCategory.LOW,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
)

private val DEFAULT_SCENARIO = MockScenario(
    keywords = emptyList(),
    predictedCondition = "Non-specific presentation",
    // No ICD code: the kernel isn't confident enough in a specific presentation to code one —
    // this is the one path where [KernelReportOutput.icdCode] is genuinely null, not lazy.
    icdCode = null,
    differentials = listOf("Viral syndrome", "Early localized infection", "Stress/somatic presentation"),
    reasoningSummary = "The chief complaint does not match a well-characterized presentation pattern " +
        "available to this mock kernel; a broader, lower-confidence differential is returned.",
    evidenceFor = listOf("Chief complaint recorded", "Vitals within the payload considered"),
    evidenceAgainst = listOf("Insufficient distinguishing detail in the whitelisted payload"),
    confidenceRange = 0.45..0.75,
    riskCategory = RiskCategory.MODERATE,
    urgencyLevel = UrgencyLevel.ROUTINE,
)

// ──────────────────────────────────────────────────────────────────────────────
// Use case
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Generates a [KernelReportOutput] for a case — REQ-HAN-07.
 *
 * **Primary path**: calls [remoteKernelSource] (backed by Retrofit + FastAPI at
 * `http://10.203.3.29:8000/v1/assess` in the data layer). Maps the domain
 * [KernelAssessmentResult] to [KernelReportOutput] using the specified field bindings:
 * - `predictedCondition` (from `condition_tier` top differential) → [KernelReportOutput.predictedCondition]
 * - `confidenceScore`    (from `probability`)                     → [KernelReportOutput.confidenceScore]
 * - `evidenceFor`        (from `evidence_for`)                    → [KernelReportOutput.evidenceFor]
 * - `evidenceAgainst`    (from `evidence_against`)                → [KernelReportOutput.evidenceAgainst]
 * - `triageUrgency`      (from `triage_urgency`)                  → prepended to [KernelReportOutput.reasoningSummary]
 *
 * **Fallback path (CRITICAL — REQ-HAN-05)**: if the API call fails for ANY reason
 * (IOException, HttpException, timeout, server offline), the exception is caught, logged, and
 * the app silently falls back to the existing mock inference. The app NEVER crashes in the
 * field because the ML server is unreachable.
 *
 * [remoteKernelSource] is a domain interface — the use case never imports Retrofit types
 * (Clean Architecture boundary maintained, same pattern as [VitalsSource] / [TranscriptionService]).
 *
 * [patientAge] and [patientSex] are clinical signals (not PII) required by the XGBoost
 * classifier. They are passed as separate optional parameters, NOT added to [KernelPayload]
 * (which enforces the pseudonymization boundary in [SendToKernelUseCase]).
 */
class GenerateKernelReportUseCase @Inject constructor(
    private val kernelReportRepository: KernelReportRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val remoteKernelSource: RemoteKernelSource,
) {
    companion object {
        const val HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD = 0.90
        const val MODEL_VERSION = "mock-kernel-v0.1"
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
     * @param patientSex Biological sex string from [Patient.biologicalSex]; null means default "U" (unknown).
     */
    suspend operator fun invoke(
        caseRecordId: String,
        payload: KernelPayload,
        patientAge: Int? = null,
        patientSex: String? = null,
    ): Result<KernelReportOutput> {
        val inferenceStartedAt = Instant.now()

        val output = tryRealApi(caseRecordId, payload, patientAge, patientSex, inferenceStartedAt)
            ?: generateMock(caseRecordId, payload, inferenceStartedAt)

        return kernelReportRepository.save(output).map { output }
    }

    // ── Real API call ──────────────────────────────────────────────────────────

    /**
     * Attempts the remote kernel call via [remoteKernelSource]. Returns null on any failure
     * — the caller falls back to [generateMock] automatically. This method never throws.
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
        } catch (e: Exception) {
            // CRITICAL GRACEFUL FALLBACK: any failure (network down, timeout, HTTP error, parse
            // error, server offline) is logged here and returns null — the caller uses mock
            // inference instead. The app NEVER crashes when the ML server is unreachable.
            // CancellationException is a Throwable (not Exception) in Kotlin so structured
            // concurrency is not broken.
            logger.warning("Kernel API unavailable — falling back to mock inference. Reason: ${e.message}")
            null
        }
    }

    // ── Mock fallback ──────────────────────────────────────────────────────────

    /**
     * The original Phase 4 mock inference — demo-credible scenario matching against
     * [KernelPayload.chiefComplaint]. Used when the real API is unreachable.
     * Has an artificial delay to keep the Sending screen's progress animation visible.
     */
    private suspend fun generateMock(
        caseRecordId: String,
        payload: KernelPayload,
        inferenceStartedAt: Instant,
    ): KernelReportOutput {
        delay(Random.nextLong(800L, 1600L))
        val inferenceEndedAt = Instant.now()

        val complaint = payload.chiefComplaint.lowercase()
        val scenario = SCENARIOS.firstOrNull { s -> s.keywords.any { complaint.contains(it) } }
            ?: DEFAULT_SCENARIO
        val confidence = Random.nextDouble(scenario.confidenceRange.start, scenario.confidenceRange.endInclusive)

        return KernelReportOutput(
            id = UUID.randomUUID().toString(),
            caseRecordId = caseRecordId,
            predictedCondition = scenario.predictedCondition,
            confidenceScore = confidence,
            differentials = scenario.differentials,
            reasoningSummary = scenario.reasoningSummary,
            evidenceFor = scenario.evidenceFor,
            evidenceAgainst = scenario.evidenceAgainst,
            modelVersion = MODEL_VERSION,
            icdCode = scenario.icdCode,
            deviceId = deviceInfoProvider.deviceId(),
            softwareVersion = deviceInfoProvider.softwareVersion(),
            dataQualityScore = dataQualityScore(payload),
            uncertaintyScore = 1.0 - confidence,
            riskCategory = scenario.riskCategory,
            urgencyLevel = scenario.urgencyLevel,
            inferenceStartedAt = inferenceStartedAt,
            inferenceEndedAt = inferenceEndedAt,
            requiredHumanVerification = confidence < HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
            inferenceSource = InferenceSource.MOCK_FALLBACK,
        )
    }
}
