package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

/** One curated (predictedCondition, differentials, reasoning, evidenceFor, evidenceAgainst)
 *  scenario, matched against [KernelPayload.chiefComplaint] by keyword — not real inference, but
 *  gives the mock demo-credible variety instead of static/random text (REQ-HAN-07). */
private data class MockScenario(
    val keywords: List<String>,
    val predictedCondition: String,
    val differentials: List<String>,
    val reasoningSummary: String,
    val evidenceFor: List<String>,
    val evidenceAgainst: List<String>,
    val confidenceRange: ClosedFloatingPointRange<Double>,
)

private val SCENARIOS = listOf(
    MockScenario(
        keywords = listOf("fever", "chills", "temperature"),
        predictedCondition = "Viral fever",
        differentials = listOf("Dengue", "Typhoid", "Malaria"),
        reasoningSummary = "Elevated temperature with reported chills and no localized findings is " +
            "most consistent with a self-limiting viral illness; vector-borne and enteric causes " +
            "remain on the differential given the regional prevalence.",
        evidenceFor = listOf("Documented fever/chills in chief complaint", "No focal infection reported"),
        evidenceAgainst = listOf("No rash or bleeding tendency reported", "No sustained high-grade pattern documented"),
        confidenceRange = 0.72..0.94,
    ),
    MockScenario(
        keywords = listOf("cough", "cold", "throat", "breath"),
        predictedCondition = "Upper respiratory tract infection",
        differentials = listOf("Bronchitis", "Pneumonia", "Allergic rhinitis"),
        reasoningSummary = "Cough with upper-airway symptoms and no reported respiratory distress " +
            "points to a common URTI; lower-respiratory and allergic causes are kept as differentials.",
        evidenceFor = listOf("Cough/throat symptoms in chief complaint"),
        evidenceAgainst = listOf("No reported breathlessness or chest pain"),
        confidenceRange = 0.68..0.90,
    ),
    MockScenario(
        keywords = listOf("stomach", "abdomen", "vomit", "diarrh"),
        predictedCondition = "Acute gastroenteritis",
        differentials = listOf("Food poisoning", "Peptic ulcer disease", "Appendicitis"),
        reasoningSummary = "Abdominal symptoms without a documented localized/rebound pattern favor " +
            "a self-limiting gastroenteritis; a surgical abdomen is kept as a differential given the " +
            "limited on-device exam data available to the kernel.",
        evidenceFor = listOf("Abdominal/GI symptoms in chief complaint"),
        evidenceAgainst = listOf("No documented localized rigidity or rebound tenderness"),
        confidenceRange = 0.60..0.88,
    ),
    MockScenario(
        keywords = listOf("head", "migraine", "dizz"),
        predictedCondition = "Tension-type headache",
        differentials = listOf("Migraine", "Sinusitis", "Hypertension-related headache"),
        reasoningSummary = "Headache without reported neurological deficit or photophobia is most " +
            "consistent with a tension-type pattern; vascular and sinus causes remain differentials.",
        evidenceFor = listOf("Headache reported as chief complaint"),
        evidenceAgainst = listOf("No neurological deficit or visual disturbance reported"),
        confidenceRange = 0.65..0.91,
    ),
)

private val DEFAULT_SCENARIO = MockScenario(
    keywords = emptyList(),
    predictedCondition = "Non-specific presentation",
    differentials = listOf("Viral syndrome", "Early localized infection", "Stress/somatic presentation"),
    reasoningSummary = "The chief complaint does not match a well-characterized presentation pattern " +
        "available to this mock kernel; a broader, lower-confidence differential is returned.",
    evidenceFor = listOf("Chief complaint recorded", "Vitals within the payload considered"),
    evidenceAgainst = listOf("Insufficient distinguishing detail in the whitelisted payload"),
    confidenceRange = 0.45..0.75,
)

/**
 * Extends the mocked kernel handoff ([SendToKernelUseCase]) with a fuller [KernelReportOutput] —
 * REQ-HAN-07. Net-new: no prior kernel-response object existed. [requiredHumanVerification] is
 * driven by the existing <90% confidence convention (REQ-HAN-05) — never presented as autonomous
 * or validated while mocked.
 */
class GenerateKernelReportUseCase @Inject constructor(
    private val kernelReportRepository: KernelReportRepository,
) {
    companion object {
        const val HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD = 0.90
        const val MODEL_VERSION = "mock-kernel-v0.1"
    }

    suspend operator fun invoke(caseRecordId: String, payload: KernelPayload): Result<KernelReportOutput> {
        delay(Random.nextLong(800L, 1600L))

        val complaint = payload.chiefComplaint.lowercase()
        val scenario = SCENARIOS.firstOrNull { s -> s.keywords.any { complaint.contains(it) } } ?: DEFAULT_SCENARIO
        val confidence = Random.nextDouble(scenario.confidenceRange.start, scenario.confidenceRange.endInclusive)

        val output = KernelReportOutput(
            id = UUID.randomUUID().toString(),
            caseRecordId = caseRecordId,
            predictedCondition = scenario.predictedCondition,
            confidenceScore = confidence,
            differentials = scenario.differentials,
            reasoningSummary = scenario.reasoningSummary,
            evidenceFor = scenario.evidenceFor,
            evidenceAgainst = scenario.evidenceAgainst,
            modelVersion = MODEL_VERSION,
            inferenceTimestamp = Instant.now(),
            requiredHumanVerification = confidence < HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
        )
        return kernelReportRepository.save(output).map { output }
    }
}
