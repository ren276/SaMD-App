package com.example.samdapp.data.kernel

import com.example.samdapp.domain.config.DeviceInfoProvider
import com.example.samdapp.domain.kernel.KernelFallbackSource
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.usecase.GenerateKernelReportUseCase
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

// ──────────────────────────────────────────────────────────────────────────────
// Mock scenario table — dev flavor only (see MockBoundaryModule's dev-flavor binding). Moved out
// of GenerateKernelReportUseCase so this class, and every scenario string in it, is physically
// absent from the staging/prod compilation unit — not just unbound, unreachable.
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
    MockScenario(
        keywords = listOf("pressure", "hypertension", "bp"),
        predictedCondition = "Essential hypertension",
        icdCode = "I10",
        differentials = listOf("White coat hypertension", "Secondary hypertension"),
        reasoningSummary = "Elevated blood pressure without acute target organ damage symptoms is " +
            "consistent with essential hypertension. Follow-up monitoring is recommended.",
        evidenceFor = listOf("Elevated BP readings", "Chief complaint of high pressure"),
        evidenceAgainst = listOf("No acute chest pain or neurological deficits"),
        confidenceRange = 0.70..0.95,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("sugar", "diabetes", "thirst", "urination"),
        predictedCondition = "Type 2 diabetes mellitus",
        icdCode = "E11",
        differentials = listOf("Metabolic syndrome", "Prediabetes"),
        reasoningSummary = "Symptoms of polyuria and polydipsia combined with elevated random blood sugar " +
            "strongly suggest Type 2 diabetes. Fasting confirmation is recommended.",
        evidenceFor = listOf("High random blood glucose", "Classic diabetic symptoms"),
        evidenceAgainst = listOf("No signs of acute ketoacidosis"),
        confidenceRange = 0.75..0.95,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("knee", "joint", "bone", "pain"),
        predictedCondition = "Osteoarthritis (knee)",
        icdCode = "M17",
        differentials = listOf("Rheumatoid arthritis", "Gout", "Traumatic injury"),
        reasoningSummary = "Chronic joint pain without systemic inflammatory signs or acute trauma " +
            "is highly indicative of osteoarthritis in this demographic.",
        evidenceFor = listOf("Localized joint pain", "Age factor"),
        evidenceAgainst = listOf("No fever", "No acute severe swelling"),
        confidenceRange = 0.60..0.85,
        riskCategory = RiskCategory.LOW,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("urine", "burn", "uti", "pelvic"),
        predictedCondition = "Urinary tract infection",
        icdCode = "N39.0",
        differentials = listOf("Vaginitis", "Kidney stones", "Interstitial cystitis"),
        reasoningSummary = "Dysuria and pelvic discomfort without significant systemic illness " +
            "points to an uncomplicated lower urinary tract infection.",
        evidenceFor = listOf("Dysuria reported in complaint"),
        evidenceAgainst = listOf("No high fever or flank pain suggesting pyelonephritis"),
        confidenceRange = 0.70..0.92,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.ROUTINE,
    ),
    MockScenario(
        keywords = listOf("typhoid", "enteric", "prolonged fever"),
        predictedCondition = "Typhoid fever",
        icdCode = "A01.0",
        differentials = listOf("Malaria", "Dengue fever", "Brucellosis"),
        reasoningSummary = "Prolonged fever with gastrointestinal symptoms in this demographic is highly suspicious for enteric fever.",
        evidenceFor = listOf("Prolonged fever", "GI symptoms"),
        evidenceAgainst = listOf("No severe bleeding"),
        confidenceRange = 0.75..0.92,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.URGENT,
    ),
    MockScenario(
        keywords = listOf("dengue", "bone pain", "breakbone", "rash"),
        predictedCondition = "Dengue fever",
        icdCode = "A90",
        differentials = listOf("Chikungunya", "Malaria", "Viral fever"),
        reasoningSummary = "Acute febrile illness with severe myalgia/arthralgia and rash is classically associated with Dengue.",
        evidenceFor = listOf("High fever", "Severe joint/muscle pain"),
        evidenceAgainst = listOf("No active bleeding signs"),
        confidenceRange = 0.70..0.90,
        riskCategory = RiskCategory.MODERATE,
        urgencyLevel = UrgencyLevel.URGENT,
    ),
    MockScenario(
        keywords = listOf("weakness", "pale", "fatigue", "anemia", "anaemia"),
        predictedCondition = "Iron-deficiency anaemia",
        icdCode = "D50",
        differentials = listOf("Thalassemia trait", "B12 deficiency"),
        reasoningSummary = "Generalized fatigue and pallor without acute bleeding is most consistent with nutritional anemia.",
        evidenceFor = listOf("Fatigue", "Pallor"),
        evidenceAgainst = listOf("No acute blood loss"),
        confidenceRange = 0.80..0.95,
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

/**
 * The dev-flavor-only [KernelFallbackSource] binding (see `MockBoundaryModule` in
 * `src/dev/java/.../di/`) — demo-credible keyword-matched mock inference, used when the real
 * `/api/v1/assess` call fails. Never compiled into staging/prod; those flavors bind
 * [NoFallbackKernelSource] instead, which always returns null.
 */
class MockKernelFallbackSource @Inject constructor(
    private val deviceInfoProvider: DeviceInfoProvider,
) : KernelFallbackSource {

    /** Artificial delay to keep the Sending screen's progress animation visible, same as before
     *  this was split out of [GenerateKernelReportUseCase]. */
    override suspend fun fallback(
        caseRecordId: String,
        payload: KernelPayload,
        inferenceStartedAt: Instant,
        dataQualityScore: Double,
    ): KernelReportOutput {
        delay(Random.nextLong(800L, 1600L))
        val inferenceEndedAt = Instant.now()

        val complaint = payload.chiefComplaint.lowercase()
        val scenario = SCENARIOS
            .mapNotNull { s -> s.keywords.filter { complaint.contains(it) }.maxByOrNull { it.length }?.let { it.length to s } }
            .maxByOrNull { (matchLength, _) -> matchLength }
            ?.second
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
            modelVersion = GenerateKernelReportUseCase.MOCK_MODEL_VERSION,
            icdCode = scenario.icdCode,
            deviceId = deviceInfoProvider.deviceId(),
            softwareVersion = deviceInfoProvider.softwareVersion(),
            dataQualityScore = dataQualityScore,
            uncertaintyScore = 1.0 - confidence,
            riskCategory = scenario.riskCategory,
            urgencyLevel = scenario.urgencyLevel,
            inferenceStartedAt = inferenceStartedAt,
            inferenceEndedAt = inferenceEndedAt,
            requiredHumanVerification = confidence < GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
            inferenceSource = InferenceSource.MOCK_FALLBACK,
        )
    }
}
