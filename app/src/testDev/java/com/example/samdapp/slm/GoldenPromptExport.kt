package com.example.samdapp.slm

import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.slm.ApprovedRecordSnapshot
import com.example.samdapp.domain.slm.CONTROL_TOKENS
import com.example.samdapp.domain.slm.MAX_PROMPT_CHARS
import com.example.samdapp.domain.slm.PROMPT_TEMPLATE_VERSION
import com.example.samdapp.domain.slm.SlmInvocation
import com.example.samdapp.domain.slm.SlmRefusal
import com.example.samdapp.domain.slm.TOKENIZER_DIVERGENT_MARKUP
import com.example.samdapp.domain.slm.buildPrompt
import com.example.samdapp.domain.slm.firstControlTokenIn
import com.example.samdapp.domain.slm.firstDivergentMarkupIn
import com.example.samdapp.domain.slm.inputScopeRefusal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** One golden readback request, before serialisation (on-device SLM memo 11.1). */
internal data class GoldenRecord(
    val id: String,
    val language: String,
    val tier: CadreTier,
    val question: String,
    val family: Int?,
    val snapshot: ApprovedRecordSnapshot,
    val diagnosisKind: String,
    val medCount: Int,
    val designedOver2048Tokens: Boolean,
    val prompt: String,
    /** What the seam's input scope gate says about [question] at [tier], from the real function. */
    val inputRefusal: SlmRefusal?,
)

/**
 * The synthetic golden set: 120 records, fixed seed, mock-only data (memo 11.1, R8, Y-13, Y-17).
 * Prompts come only from the real [buildPrompt]; refusal flags come only from the real
 * [inputScopeRefusal]. Nothing is hand-written into a prompt, and no real patient data exists here.
 *
 * Findings worth knowing, both produced by running the real gate rather than assumed:
 * - The worker-tier input gate is English-only (`ALLOWED_INTENTS`), so a pure Devanagari question
 *   from a worker-tier user is refused as OUT_OF_SCOPE_INTENT. Devanagari records are therefore at
 *   the physician tier, except three deliberate worker-tier refusals.
 * - A REJECT record read by a worker carries no medication lines (H-17), which is modelled here.
 */
internal object GoldenPromptGenerator {

    const val SEED = 20260918
    const val COUNT = 120

    val LANGUAGES = listOf("en", "hi-Deva", "hi-Latn")

    private val MEDICINES = listOf(
        Triple("Amoxicillin", "500 mg", "oral"), Triple("Paracetamol", "500 mg", "oral"),
        Triple("Azithromycin", "250 mg", "oral"), Triple("Metformin", "500 mg", "oral"),
        Triple("Amlodipine", "5 mg", "oral"), Triple("Cetirizine", "10 mg", "oral"),
        Triple("Albendazole", "400 mg", "oral"), Triple("Pantoprazole", "40 mg", "oral"),
        Triple("Doxycycline", "100 mg", "oral"), Triple("Atenolol", "50 mg", "oral"),
        Triple("Oral rehydration salts", "1 sachet", "oral"), Triple("Salbutamol", "100 mcg", "inhaled"),
    )
    private val FREQUENCIES = listOf("once a day", "twice a day", "three times a day", "every 8 hours", "at night")
    private val DURATIONS = listOf("3 days", "5 days", "7 days", "10 days", "2 weeks", "1 month")
    private val FOOD = listOf("after food", "before food", "with food", null)

    private val SHORT_DIAGNOSES = listOf(
        "Acute viral pharyngitis", "Uncomplicated urinary tract infection", "Iron deficiency anaemia",
        "Acute gastroenteritis with mild dehydration", "Allergic rhinitis", "Tension headache",
        "तीव्र ग्रसनीशोथ", "मौसमी बुखार", "पेट में संक्रमण",
        "Tez bukhar aur gale mein dard", "Pet mein infection", "Khoon ki kami",
    )
    private val NUMERAL_DIAGNOSES = listOf(
        "Type 2 diabetes mellitus, HbA1c 8.2, fasting glucose 164",
        "Stage 1 hypertension, second reading 146 over 94",
        "Pulmonary tuberculosis, category 1, week 8 of 24",
        "Iron deficiency anaemia, haemoglobin 8.4",
    )
    private val UNITS_DIAGNOSES = listOf(
        "Hypertension, BP 148/92 mmHg, pulse 88 per minute, SpO2 96%",
        "Febrile illness, temperature 38.4 C, pulse 102 per minute",
        "Mild dehydration, weight 54 kg, urine output reduced",
        "Hypoglycaemia, random glucose 58 mg/dL, treated with oral glucose",
    )
    private const val PROSE_SENTENCE =
        "The patient reports a cough and a sore throat that have continued for several days and are worse at " +
            "night, with a poor appetite and mild tiredness, no breathlessness, and the family has been giving " +
            "warm fluids at home. "

    /** Question pools: [language][family][variant]. Eight families cover every ALLOWED_INTENTS phrase. */
    private val QUESTIONS: Map<String, List<List<String>>> = mapOf(
        "en" to listOf(
            listOf("Please read back the prescription for me.", "Can you read this record out to the patient?", "Read it back slowly so I can note it down."),
            listOf("Can you explain what the doctor decided?", "What does the doctor's decision on this assessment mean?", "What did the doctor change in this case?"),
            listOf("What is the diagnosis written by the doctor?", "What is this illness according to the record?", "What has the doctor written as the problem?"),
            listOf("When should the patient take the medicine?", "How should the tablets be taken, before or after food?", "When do I give the first dose?"),
            listOf("How many times a day is the dose?", "How much of the medicine is given each time?", "How often should this be taken?"),
            listOf("Please tell me what this record says in simple words.", "Summarise the record in plain language.", "Say it in simple words for the family."),
            listOf("Does the doctor want the patient referred to a hospital?", "Is there a referral in this record?", "Did the doctor say to refer the patient?"),
            listOf("Which medicine was prescribed for the patient?", "Explain the prescription in plain language.", "What medication has the doctor written?"),
        ),
        "hi-Latn" to listOf(
            listOf("Doctor ne jo prescription likha hai use read back kijiye.", "Is record ko read out kar dijiye, mareez ke saamne.", "Isse dheere dheere read out kijiye."),
            listOf("Doctor ne kya decide kiya, explain kijiye.", "Doctor ke decision ka kya matlab hai, what does it mean?", "Doctor ne is case mein kya badla, what did the doctor change?"),
            listOf("Is mareez ka diagnosis kya hai, what is the diagnosis?", "Record ke hisaab se what is this problem?", "Doctor ne kya likha hai, what has the doctor written?"),
            listOf("Dawai kab leni hai, when to take it?", "Yeh dawai after food leni hai ya before food?", "Pehli dose kab deni hai, when do I start?"),
            listOf("Din mein kitni baar leni hai, dose kya hai?", "Ek baar mein how much dawai deni hai?", "Yeh how often leni hai?"),
            listOf("Mujhe simple words mein samjhaiye ki record mein kya likha hai.", "Pura record plain language mein bataiye.", "Parivaar ko in simple tarike se batana hai, tell me what is written."),
            listOf("Kya doctor ne referral likha hai?", "Mareez ko hospital refer karna hai kya?", "Did the doctor kisi bade hospital bheja hai?"),
            listOf("Yeh tablet kis liye hai, medicine ka naam bataiye.", "Prescription mein kaun si medication likhi hai?", "Prescribed dawai ki list bataiye."),
        ),
        "hi-Deva" to listOf(
            listOf("डॉक्टर ने जो पर्ची लिखी है उसे पढ़कर सुनाइए।", "इस रिकॉर्ड को मरीज़ के सामने पढ़कर बताइए।", "पर्ची की बातें धीरे-धीरे दोहराइए।"),
            listOf("डॉक्टर ने क्या तय किया है, समझाइए।", "डॉक्टर के फ़ैसले का क्या मतलब है?", "डॉक्टर ने इस मामले में क्या बदला?"),
            listOf("मरीज़ को क्या बीमारी बताई गई है?", "रिकॉर्ड के अनुसार निदान क्या है?", "डॉक्टर ने समस्या के रूप में क्या लिखा है?"),
            listOf("दवा कब लेनी चाहिए?", "दवा खाने से पहले लेनी है या बाद में?", "पहली खुराक कब देनी है?"),
            listOf("दिन में कितनी बार दवा लेनी है?", "हर बार कितनी दवा देनी है?", "यह दवा कितनी देर में दोबारा लेनी है?"),
            listOf("इस रिकॉर्ड में क्या लिखा है, आसान शब्दों में बताइए।", "पूरा रिकॉर्ड सरल भाषा में समझाइए।", "परिवार को समझाने के लिए सरल शब्दों में बताइए।"),
            listOf("क्या डॉक्टर ने मरीज़ को अस्पताल रेफर किया है?", "क्या इस रिकॉर्ड में रेफ़रल है?", "क्या डॉक्टर ने बड़े अस्पताल भेजने को कहा है?"),
            listOf("कौन सी दवा लिखी गई है?", "दवाओं की सूची बताइए और उन्हें कैसे लेना है।", "लिखी हुई दवा किस लिए है?"),
        ),
    )

    /** Worker-tier questions the real gate must refuse: 4 + 3 + 3 = 10. */
    val REFUSED: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "Is ibuprofen safe with lisinopril?",
            "Could this be something else instead of what the doctor wrote?",
            "What is the weather like today?",
            "Explain how many ibuprofen tablets to give.",
        ),
        "hi-Latn" to listOf(
            "Yeh dawai doosri dawai ke saath safe hai kya, koi interaction to nahi?",
            "Is dawai ka alternative bataiye.",
            "Aaj mausam kaisa hai?",
        ),
        "hi-Deva" to listOf(
            "क्या यह दवा किसी और दवा के साथ सुरक्षित है?",
            "मुझे कोई दूसरी दवा बताइए।",
            "आज मौसम कैसा है?",
        ),
    )

    private val FIRST_REFUSED_K = mapOf("en" to 30, "hi-Latn" to 30, "hi-Deva" to 30)

    fun generate(): List<GoldenRecord> {
        val formatter = ReportFormatter()
        val decisions = balanced(KernelDecision.entries.toList(), 1)
        val referral = balanced(listOf(true, false), 2)
        val meds = balanced(listOf(0, 1, 5, 12), 3)
        val kinds = shuffled(
            List(20) { "null" } + List(40) { "short" } + List(24) { "numerals" } + List(24) { "units" } +
                List(6) { "long-prose" } + List(6) { "long-digits" },
            4,
        )
        check(kinds.size == COUNT)

        return List(COUNT) { i ->
            val language = LANGUAGES[i % 3]
            val k = i / 3
            val refusedAt = k - FIRST_REFUSED_K.getValue(language)
            val refusedQuestions = REFUSED.getValue(language)
            val isRefused = refusedAt in refusedQuestions.indices

            val tier = when {
                isRefused -> if (k % 2 == 0) CadreTier.COMMUNITY else CadreTier.LICENSED_CLINICAL
                language == "hi-Deva" -> CadreTier.PHYSICIAN
                else -> listOf(CadreTier.COMMUNITY, CadreTier.LICENSED_CLINICAL, CadreTier.PHYSICIAN)[k % 3]
            }
            val family: Int? = if (isRefused) null else if (k < 8) k else k % 8
            val question = when {
                isRefused -> refusedQuestions[refusedAt]
                k < 8 -> QUESTIONS.getValue(language)[family!!][0]
                else -> QUESTIONS.getValue(language)[family!!][1 + (k / 8) % 2]
            }

            val decision = decisions[i]
            // H-17: a REJECT record carries no medication lines for a worker-tier reader.
            val medCount = if (decision == KernelDecision.REJECT && tier != CadreTier.PHYSICIAN) 0 else meds[i]
            val medicationLines = List(medCount) { m ->
                val (name, strength, route) = MEDICINES[(i + m * 5) % MEDICINES.size]
                formatter.formatMedicationLine(
                    MedicationLine(
                        genericName = name, brandName = null, strength = strength, dosage = "1",
                        frequency = FREQUENCIES[(i + m) % FREQUENCIES.size], route = route,
                        duration = DURATIONS[(i + 2 * m) % DURATIONS.size], quantity = "${5 + (i + m) % 20}",
                        foodRelation = FOOD[(i + m) % FOOD.size], instructions = null,
                    ),
                )
            }

            val kind = kinds[i]
            val base = ApprovedRecordSnapshot(
                caseRecordId = "golden-%03d".format(i),
                kernelDecision = decision,
                diagnosis = when (kind) {
                    "null" -> null
                    "short" -> SHORT_DIAGNOSES[(i / 3) % SHORT_DIAGNOSES.size]
                    "numerals" -> NUMERAL_DIAGNOSES[(i / 3) % NUMERAL_DIAGNOSES.size]
                    "units" -> UNITS_DIAGNOSES[(i / 3) % UNITS_DIAGNOSES.size]
                    else -> "" // filled below, once the rest of the prompt has a length
                },
                medicationLines = medicationLines,
                suggestsReferral = referral[i],
            )
            val snapshot = if (kind.startsWith("long")) padToBound(base, question, kind, i) else base
            val prompt = buildPrompt(SlmInvocation(snapshot, question))
            val refusal = if (tier == CadreTier.PHYSICIAN) null else inputScopeRefusal(SlmInvocation(snapshot, question))

            GoldenRecord(
                id = "g-%03d".format(i), language = language, tier = tier, question = question, family = family,
                snapshot = snapshot, diagnosisKind = kind, medCount = medCount,
                designedOver2048Tokens = kind == "long-digits", prompt = prompt, inputRefusal = refusal,
            )
        }
    }

    /**
     * A diagnosis long enough that the whole prompt lands near the 6000-character bound. The
     * digits kind is a run of single digits separated by spaces: every digit word is at least one
     * token whether or not the tokenizer splits digits, so about 2,500 of them exceed 2048 tokens
     * (designed expected refusals, C-1; the real count comes from `token_parity.py`). The prose
     * kind stays well under.
     */
    private fun padToBound(base: ApprovedRecordSnapshot, question: String, kind: String, i: Int): ApprovedRecordSnapshot {
        val core = if (kind == "long-digits") "Serial monitoring readings recorded over the stay:" else "Presenting history:"
        val filler = if (kind == "long-digits") {
            (0 until 3000).joinToString(" ") { n -> ((n * 7 + i) % 10).toString() }
        } else {
            PROSE_SENTENCE.repeat(40)
        }
        val without = buildPrompt(SlmInvocation(base.copy(diagnosis = ""), question)).length
        val room = TARGET_PROMPT_CHARS - without
        val diagnosis = "$core $filler".take(room).trimEnd()
        return base.copy(diagnosis = diagnosis)
    }

    private const val TARGET_PROMPT_CHARS = 5950

    private fun <T> shuffled(values: List<T>, salt: Int): List<T> = values.shuffled(Random(SEED + salt))

    private fun <T> balanced(values: List<T>, salt: Int): List<T> =
        shuffled(List(COUNT) { values[it % values.size] }, salt)
}

/**
 * Trim probes (G-1' trim rule, memo 5.1): questions carrying one whitespace-like character at the
 * start, the end, or both. The seam trims the question with Kotlin's `trim()` before it builds the
 * prompt, and the server's template trims the whole prompt with Python's `strip()`; the two differ
 * on some characters (U+0085 and U+001F are the interesting ones). These prompts are built the way
 * the seam builds them, so a GPU-machine render shows the real difference, if any.
 */
internal object TrimProbeGenerator {

    val PROBE_CHARS: List<Pair<String, String>> = listOf(
        "ascii-space" to " ", "tab" to "\t", "crlf" to "\r\n", "u00a0" to "\u00A0", "u2003" to "\u2003",
        "u200b" to "\u200B", "u0085" to "\u0085", "u001f" to "\u001F", "ufeff" to "\uFEFF",
    )
    const val BASE_QUESTION = "Explain the prescription in plain language"

    class Probe(val id: String, val label: String, val position: String, val rawQuestion: String, val prompt: String, val snapshot: ApprovedRecordSnapshot)

    fun generate(): List<Probe> {
        val snapshot = ApprovedRecordSnapshot(
            caseRecordId = "trim-probe",
            kernelDecision = KernelDecision.AGREE,
            diagnosis = "Acute viral pharyngitis",
            medicationLines = listOf("Paracetamol - 500 mg | oral | three times a day | 3 days | 9"),
            suggestsReferral = false,
        )
        val cases = buildList {
            add(Triple("none", "none", BASE_QUESTION))
            PROBE_CHARS.forEach { (label, c) ->
                add(Triple(label, "leading", c + BASE_QUESTION))
                add(Triple(label, "trailing", BASE_QUESTION + c))
                add(Triple(label, "both", c + BASE_QUESTION + c))
            }
        }
        return cases.mapIndexed { i, (label, position, raw) ->
            // What the seam does with the question before it builds the prompt (SlmReadbackUseCase).
            val prompt = buildPrompt(SlmInvocation(snapshot, raw.trim()))
            Probe("t-%02d".format(i), label, position, raw, prompt, snapshot)
        }
    }
}

/** Script of the dominant letters of [text]: Devanagari block against ASCII Latin letters. */
internal fun dominantScript(text: String): String {
    val deva = text.count { it.code in 0x0900..0x097F && it.isLetter() }
    val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
    return if (deva > latin) "Devanagari" else "Latin"
}

internal fun coverageCells(records: List<GoldenRecord>): Map<String, Int> {
    val cells = linkedMapOf<String, Int>()
    fun hit(cell: String) { cells[cell] = (cells[cell] ?: 0) + 1 }
    records.forEach { r ->
        hit("language:${r.language}")
        hit("decision:${r.snapshot.kernelDecision}")
        hit("tier:${r.tier}")
        hit("diagnosis:${r.diagnosisKind}")
        hit("medications:${r.medCount}")
        hit("referral:${if (r.snapshot.suggestsReferral) "yes" else "no"}")
        r.family?.let { hit("family:$it/${r.language}") }
        if (r.inputRefusal != null) hit("expected_input_refusal:${r.language}")
        if (r.designedOver2048Tokens) hit("designed_over_2048_tokens")
        if (r.diagnosisKind == "long-prose" || r.diagnosisKind == "long-digits") hit("long_near_6000_chars")
    }
    return cells.toSortedMap()
}

/**
 * Golden prompt export for the parity harness (on-device SLM memo 11.1, slice S5a).
 *
 * The export test runs only when `SAMD_GOLDEN_EXPORT_DIR` is set, so CI writes nothing. Gradle does
 * not treat an environment variable as a task input, so run it with `--rerun`:
 * `SAMD_GOLDEN_EXPORT_DIR=/media/sandesh/extra-ssd/samd-slm-parity/golden ./gradlew
 * :app:testDevDebugUnitTest --rerun --tests '*GoldenPromptExport*'`.
 */
class GoldenPromptExport {

    @Test
    fun theGeneratedSetMeetsTheSpecifiedCoverage() {
        val records = GoldenPromptGenerator.generate()

        assertEquals(GoldenPromptGenerator.COUNT, records.size)
        GoldenPromptGenerator.LANGUAGES.forEach { language ->
            assertEquals(language, 40, records.count { it.language == language })
        }
        val cells = coverageCells(records)
        KernelDecision.entries.forEach { assertTrue("decision $it", cells.getValue("decision:$it") >= 3) }
        listOf("null", "short", "numerals", "units", "long-prose", "long-digits").forEach {
            assertTrue("diagnosis $it", cells.getValue("diagnosis:$it") >= 3)
        }
        listOf(0, 1, 5, 12).forEach { assertTrue("medications $it", cells.getValue("medications:$it") >= 3) }
        listOf("yes", "no").forEach { assertTrue("referral $it", cells.getValue("referral:$it") >= 3) }
        (0 until 8).forEach { family ->
            GoldenPromptGenerator.LANGUAGES.forEach { language ->
                assertTrue("family $family in $language", (cells["family:$family/$language"] ?: 0) >= 1)
            }
        }
        assertTrue("designed over 2048 tokens", cells.getValue("designed_over_2048_tokens") >= 3)
        assertTrue("long near the bound", cells.getValue("long_near_6000_chars") >= 3)
    }

    @Test
    fun exactlyTenWorkerQuestionsAreRefusedByTheRealGateAndNothingElseIs() {
        val records = GoldenPromptGenerator.generate()

        val refused = records.filter { it.inputRefusal != null }
        assertEquals(10, refused.size)
        refused.forEach { assertTrue("${it.id} must be worker tier", it.tier != CadreTier.PHYSICIAN) }
        assertEquals(setOf(SlmRefusal.OUT_OF_SCOPE_PHRASING, SlmRefusal.OUT_OF_SCOPE_INTENT, SlmRefusal.OUT_OF_SCOPE_UNTETHERED), refused.map { it.inputRefusal }.toSet())
        // The ten are exactly the deliberately refused questions: each of those is refused by the
        // real gate, and every other worker-tier question passes it.
        val deliberate = GoldenPromptGenerator.REFUSED.values.flatten().toSet()
        assertEquals(10, deliberate.size)
        assertEquals(deliberate, refused.map { it.question }.toSet())
        records.filter { it.tier != CadreTier.PHYSICIAN && it.question !in deliberate }
            .forEach { assertEquals(it.id, null, it.inputRefusal) }
    }

    @Test
    fun noPromptTripsTheOtherSeamGuardsAndEveryPromptIsTheRealBuildPrompt() {
        val records = GoldenPromptGenerator.generate()

        records.forEach { r ->
            assertEquals(r.id, null, firstControlTokenIn(r.prompt))
            assertEquals(r.id, null, firstDivergentMarkupIn(r.prompt))
            assertTrue("${r.id} is ${r.prompt.length} chars", r.prompt.length <= MAX_PROMPT_CHARS)
            assertEquals(r.id, buildPrompt(SlmInvocation(r.snapshot, r.question)), r.prompt)
            assertFalse("${r.id}: the case id must not be in the prompt", r.prompt.contains(r.snapshot.caseRecordId))
        }
        assertEquals("prompts must be distinct", records.size, records.map { it.prompt }.toSet().size)
    }

    @Test
    fun theLongRecordsSitNearTheBoundAndTheDigitOnesAreDesignedToExceed2048Tokens() {
        val long = GoldenPromptGenerator.generate().filter { it.diagnosisKind.startsWith("long") }

        long.forEach { assertTrue("${it.id}: ${it.prompt.length}", it.prompt.length in 5850..MAX_PROMPT_CHARS) }
        long.filter { it.designedOver2048Tokens }.forEach { r ->
            // One digit word is at least one token, so this many digits cannot fit 2048 tokens.
            assertTrue(r.id, r.prompt.count { it.isDigit() } >= 2300)
        }
        long.filter { !it.designedOver2048Tokens }.forEach { r ->
            assertTrue(r.id, r.prompt.count { it.isDigit() } < 100)
        }
    }

    @Test
    fun theSetIsDeterministic() {
        assertEquals(
            GoldenPromptGenerator.generate().map { it.prompt },
            GoldenPromptGenerator.generate().map { it.prompt },
        )
    }

    @Test
    fun exportsTheGoldenFilesWhenAskedTo() {
        val dir = System.getenv("SAMD_GOLDEN_EXPORT_DIR")
        assumeTrue("SAMD_GOLDEN_EXPORT_DIR is not set; nothing is written", !dir.isNullOrBlank())
        val out = File(dir!!).apply { mkdirs() }
        val records = GoldenPromptGenerator.generate()

        val lines = records.map { r ->
            buildJsonObject {
                put("id", r.id)
                put("template_version", PROMPT_TEMPLATE_VERSION)
                put("prompt", r.prompt)
                put("prompt_sha256", sha256(r.prompt))
                put("question", r.question)
                put("question_script", dominantScript(r.question))
                put("question_language", r.language)
                put("tier", r.tier.name)
                put("expected_input_refusal", r.inputRefusal != null)
                put("expected_input_refusal_reason", r.inputRefusal?.name)
                put("designed_over_2048_tokens", r.designedOver2048Tokens)
                put("snapshot_fields", buildJsonObject {
                    put("case_record_id", r.snapshot.caseRecordId)
                    put("kernel_decision", r.snapshot.kernelDecision.name)
                    put("diagnosis", r.snapshot.diagnosis)
                    put("medication_lines", JsonArray(r.snapshot.medicationLines.map { JsonPrimitive(it) }))
                    put("suggests_referral", r.snapshot.suggestsReferral)
                })
                put("cells", JsonArray(cellsOf(r).map { JsonPrimitive(it) }))
            }.toString()
        }
        File(out, "prompts-v1.jsonl").writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        File(out, "control-tokens.txt").writeText(CONTROL_TOKENS.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        File(out, "html-tag-pieces.txt").writeText(TOKENIZER_DIVERGENT_MARKUP.joinToString("\n"), Charsets.UTF_8)
        File(out, "coverage.txt").writeText(
            coverageCells(records).entries.joinToString("\n", postfix = "\n") { (cell, n) -> "$cell\t$n" },
            Charsets.UTF_8,
        )

        // Read it back: the file on disk, not the list in memory.
        val back = File(out, "prompts-v1.jsonl").readLines(Charsets.UTF_8).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(GoldenPromptGenerator.COUNT, back.size)
        back.forEachIndexed { i, o ->
            assertEquals(records[i].prompt, o.getValue("prompt").jsonPrimitive.content)
            assertEquals(sha256(records[i].prompt), o.getValue("prompt_sha256").jsonPrimitive.content)
            assertEquals(records[i].inputRefusal != null, o.getValue("expected_input_refusal").jsonPrimitive.booleanOrNull)
            assertTrue(o.getValue("snapshot_fields") is JsonObject)
            assertTrue(o.getValue("expected_input_refusal_reason").let { it is JsonNull || it is JsonPrimitive })
        }
        assertEquals(CONTROL_TOKENS.size, File(out, "control-tokens.txt").readLines().size)
        assertEquals(70, File(out, "html-tag-pieces.txt").readText().split('\n').size)
    }

    @Test
    fun theTrimProbesCoverEveryCharacterAtEveryPositionAndAreBuiltLikeTheSeam() {
        val probes = TrimProbeGenerator.generate()

        assertEquals(
            listOf("ascii-space", "tab", "crlf", "u00a0", "u2003", "u200b", "u0085", "u001f", "ufeff"),
            TrimProbeGenerator.PROBE_CHARS.map { it.first },
        )
        assertEquals(1 + 9 * 3, probes.size)
        assertEquals(probes.size, probes.map { it.id }.toSet().size)
        probes.forEach { p ->
            assertEquals(p.id, buildPrompt(SlmInvocation(p.snapshot, p.rawQuestion.trim())), p.prompt)
            assertEquals(p.id, null, firstControlTokenIn(p.prompt))
            assertEquals(p.id, null, firstDivergentMarkupIn(p.prompt))
        }
        // What Kotlin's trim() removes is gone from the prompt; what it keeps is still there. The
        // probes only measure something if both kinds exist.
        val none = probes.single { it.label == "none" }.prompt
        fun trailing(label: String) = probes.single { it.label == label && it.position == "trailing" }.prompt
        listOf("ascii-space", "tab", "crlf", "u00a0", "u2003").forEach { assertEquals(it, none, trailing(it)) }
        listOf("u200b", "ufeff").forEach { assertTrue(it, trailing(it) != none) }
    }

    @Test
    fun exportsTheTrimProbesWhenAskedTo() {
        val dir = System.getenv("SAMD_GOLDEN_EXPORT_DIR")
        assumeTrue("SAMD_GOLDEN_EXPORT_DIR is not set; nothing is written", !dir.isNullOrBlank())
        val out = File(dir!!).apply { mkdirs() }
        val probes = TrimProbeGenerator.generate()

        val lines = probes.map { p ->
            buildJsonObject {
                put("id", p.id)
                put("template_version", PROMPT_TEMPLATE_VERSION)
                put("prompt", p.prompt)
                put("prompt_sha256", sha256(p.prompt))
                put("probe_char", p.label)
                put("probe_position", p.position)
                put("raw_question_code_points", JsonArray(p.rawQuestion.codePoints().toArray().map { JsonPrimitive(it) }))
            }.toString()
        }
        File(out, "trim-probes-v1.jsonl").writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        val back = File(out, "trim-probes-v1.jsonl").readLines(Charsets.UTF_8).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(probes.size, back.size)
        back.forEachIndexed { i, o ->
            assertEquals(probes[i].prompt, o.getValue("prompt").jsonPrimitive.content)
            assertEquals(sha256(probes[i].prompt), o.getValue("prompt_sha256").jsonPrimitive.content)
        }
    }

    private fun cellsOf(r: GoldenRecord): List<String> = coverageCells(listOf(r)).keys.toList()

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
