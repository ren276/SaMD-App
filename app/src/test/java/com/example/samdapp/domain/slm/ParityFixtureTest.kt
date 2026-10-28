package com.example.samdapp.domain.slm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin and Python agree (on-device SLM memo 11.5): `tools/slm-parity/compare.py` re-implements the
 * grounding gate, the control-token check and the stream sanitizer. This fixture holds shared inputs
 * with the verdicts of the REAL Kotlin functions, committed under `src/test/resources/slm/`.
 *
 * - This test recomputes every verdict with Kotlin and asserts the committed fixture still says the
 *   same, so a change to the Kotlin rules fails here until the fixture is regenerated.
 * - `tools/slm-parity/test_compare.py` asserts the Python port gives the same verdicts, so a drifted
 *   port fails there. Drift fails both ways.
 * - Regenerate with `SAMD_PARITY_FIXTURE_DIR=<dir> ./gradlew :app:testDevDebugUnitTest --rerun
 *   --tests '*ParityFixtureTest*'` and copy the file over the committed one.
 */
class ParityFixtureTest {

    private class Case(
        val id: String,
        val medicationLines: List<String>,
        val diagnosis: String?,
        val decision: String,
        val output: String,
    )

    private val amoxicillin = "Amoxicillin (Mox) - 500 mg | oral | three times a day | 5 days | 15"
    private val paracetamol = "Paracetamol - 500 mg | oral | every 8 hours | 3 days | 9"

    private val cases = listOf(
        Case("grounded", listOf(amoxicillin), "Acute viral pharyngitis", "AGREE", "The doctor approved Amoxicillin 500 mg, three times a day, for 5 days, 15 in total."),
        Case("drug-not-in-record", listOf(amoxicillin), null, "AGREE", "Take ibuprofen with the amoxicillin."),
        Case("dose-not-in-record", listOf(amoxicillin), null, "AGREE", "Take 250 mg of amoxicillin."),
        Case("numeral-from-diagnosis", listOf(amoxicillin), "Fever for 3 days", "MODIFY", "Fever for 3 days, take amoxicillin."),
        Case("drug-only-in-diagnosis", listOf(amoxicillin), "Allergic to penicillin", "MODIFY", "Avoid penicillin."),
        Case("reject-no-medicines", emptyList(), "Viral fever", "REJECT", "The doctor did not prescribe paracetamol."),
        Case("decimal-dose", listOf("Albendazole - 400 mg | oral | once a day | 3 days | 3"), null, "AGREE", "Give albendazole 0.5 tablet."),
        Case("suffix-drug", listOf(paracetamol), null, "AGREE", "Azithromycin is not on the record."),
        Case("hydrocortisone-suffix", listOf(paracetamol), null, "AGREE", "Hydrocortisone cream."),
        Case("exact-word-ors", listOf("Oral rehydration salts - 1 sachet | oral | after each loose stool | 3 days | 6"), null, "AGREE", "Give ORS after each loose stool, 1 sachet."),
        Case("ors-not-in-record", listOf(paracetamol), null, "AGREE", "Give ORS."),
        Case("upper-case", listOf(amoxicillin), null, "AGREE", "AMOXICILLIN 500 MG THREE TIMES A DAY."),
        Case("devanagari-only", listOf(amoxicillin), null, "AGREE", "डॉक्टर ने दवा लिखी है।"),
        Case("devanagari-digits", listOf(amoxicillin), null, "AGREE", "दवा ५ दिन लें।"),
        Case("glued-unit", listOf(amoxicillin), null, "AGREE", "Take 500mg and 5days."),
        Case("channel-leak", listOf(amoxicillin), null, "AGREE", "<|channel>thought\nthe worker may want ibuprofen<channel|>Take amoxicillin 500 mg."),
        Case("unused-token", listOf(amoxicillin), null, "AGREE", "Take amoxicillin <unused12> now."),
        Case("multimodal-token", listOf(amoxicillin), null, "AGREE", "Take [multimodal] amoxicillin."),
        Case("bp-less-than", listOf(amoxicillin), null, "AGREE", "BP <120/80 and amoxicillin."),
        Case("egfr-range", listOf(amoxicillin), null, "AGREE", "eGFR <60 and >90 with amoxicillin."),
        Case("footnote", listOf(amoxicillin), null, "AGREE", "See note [2] on amoxicillin."),
        Case("unknown-angle", listOf(amoxicillin), null, "AGREE", "Take <pending> amoxicillin."),
        Case("trailing-partial-token", listOf(amoxicillin), null, "AGREE", "Take amoxicillin. <|chan"),
        Case("unterminated-channel", listOf(amoxicillin), null, "AGREE", "Take amoxicillin. <|channel>thought: still thinking"),
        Case("close-without-open", listOf(amoxicillin), null, "AGREE", "Take amoxicillin<channel|> now."),
    )

    private fun verdicts(c: Case): JsonObject {
        val snapshot = ApprovedRecordSnapshot(
            caseRecordId = "fixture", kernelDecision = com.example.samdapp.domain.model.KernelDecision.valueOf(c.decision),
            diagnosis = c.diagnosis, medicationLines = c.medicationLines, suggestsReferral = false,
        )
        val sanitizer = SlmStreamSanitizer()
        val sanitized = sanitizer.accept(c.output) + sanitizer.finish()
        return buildJsonObject {
            put("id", c.id)
            put("medication_lines", JsonArray(c.medicationLines.map { JsonPrimitive(it) }))
            put("diagnosis", c.diagnosis)
            put("decision", c.decision)
            put("output", c.output)
            put("grounded", outputIsGrounded(c.output, snapshot))
            put("control_token_hit", firstControlTokenIn(c.output) != null)
            put("sanitized", sanitized)
        }
    }

    private fun fixture(): JsonObject = buildJsonObject {
        put("note", "Verdicts of the real Kotlin outputIsGrounded, firstControlTokenIn and SlmStreamSanitizer (one chunk, then finish). Regenerate with ParityFixtureTest.")
        put("named_control_tokens", JsonArray(CONTROL_TOKENS.filterNot { it.startsWith("<unused") }.sorted().map { JsonPrimitive(it) }))
        put("unused_token_count", CONTROL_TOKENS.count { it.startsWith("<unused") })
        put("cases", JsonArray(cases.map { verdicts(it) }))
    }

    @Test
    fun theCommittedFixtureStillMatchesTheRealKotlinFunctions() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("slm/grounding-fixture-v1.json")) {
            "missing slm/grounding-fixture-v1.json; regenerate it (see this class's KDoc)"
        }
        val committed = Json.parseToJsonElement(String(stream.readBytes(), Charsets.UTF_8)).jsonObject
        val expected = fixture()

        assertEquals("named control tokens", expected.getValue("named_control_tokens"), committed.getValue("named_control_tokens"))
        assertEquals("unused token count", expected.getValue("unused_token_count"), committed.getValue("unused_token_count"))
        val now = expected.getValue("cases").jsonArray
        val then = committed.getValue("cases").jsonArray
        assertEquals("case count", now.size, then.size)
        now.indices.forEach { i ->
            assertEquals("fixture case ${now[i].jsonObject.getValue("id").jsonPrimitive.content}", now[i], then[i])
        }
        // Non-vacuity: the fixture exercises both verdicts.
        val grounded = now.map { it.jsonObject.getValue("grounded").jsonPrimitive.booleanOrNull }
        assertEquals(true, grounded.contains(true) && grounded.contains(false))
    }

    @Test
    fun writesTheFixtureWhenAskedTo() {
        val dir = System.getenv("SAMD_PARITY_FIXTURE_DIR")
        assumeTrue("SAMD_PARITY_FIXTURE_DIR is not set; nothing is written", !dir.isNullOrBlank())
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        File(dir!!).apply { mkdirs() }.resolve("grounding-fixture-v1.json")
            .writeText(pretty.encodeToString(JsonObject.serializer(), fixture()) + "\n", Charsets.UTF_8)
    }
}
