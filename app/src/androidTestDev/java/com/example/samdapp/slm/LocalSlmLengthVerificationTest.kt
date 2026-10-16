package com.example.samdapp.slm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.data.slm.LocalSlmEngine
import com.example.samdapp.di.localSlmEngine
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.slm.ApprovedRecordSnapshot
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import com.example.samdapp.domain.slm.SlmInvocation
import com.example.samdapp.domain.slm.buildPrompt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The over-length and cap cases of memo 5.5 and 14 slice S3 (Y-7', Y-20, R9-a), at the engine
 * level so the seam's 6000-character bound does not hide them, on the product accelerator order
 * (GPU first). If any case below produces visible output the test fails: STOP and report, that
 * needs a new ruling. Results go to the status stream as counts only, never text.
 */
@RunWith(AndroidJUnit4::class)
class LocalSlmLengthVerificationTest {

    private fun status(key: String, value: Any?) = InstrumentationRegistry.getInstrumentation().sendStatus(
        0, Bundle().apply { putString("slm_$key", value.toString()) },
    )

    /** Returns (emitted chunks, thrown engine exception or null). */
    private fun attempt(prompt: String, maxOutputTokens: Int = 16): Pair<List<String>, SlmEngineException?> = runBlocking {
        val emitted = mutableListOf<String>()
        try {
            engine.generate("length-verification", prompt, maxOutputTokens).collect { emitted += it }
            emitted to null
        } catch (e: SlmEngineException) {
            emitted to e
        }
    }

    @Test
    fun aPromptInThe2049To2559BandIsRefusedByTheBackstopAndEmitsNothing() {
        // Tokens per digit word are measured, not assumed: each attempt reports its prefill count
        // and the next digit count is corrected from it until the count lands in the band.
        var digits = 1100
        var chars = 0
        var prefill = -1
        var final: Pair<List<String>, SlmEngineException?>? = null
        for (round in 1..8) {
            val prompt = bandPrompt(digits)
            chars = prompt.length
            val result = attempt(prompt)
            prefill = engine.lastCounts()?.prefillTokens ?: -1
            status("band_round_$round", "digits=$digits chars=$chars prefill=$prefill error=${result.second?.message}")
            if (prefill in 2100..2500) {
                final = result
                break
            }
            digits = if (prefill > 0) digits + (2300 - prefill) / 2 else digits * 4 / 5
        }
        status("band_final", "chars=$chars prefill=$prefill")

        assertTrue("prefill count $prefill is not in the 2049..2559 band", prefill in 2049..2559)
        val (emitted, error) = final!!
        assertEquals("the backstop must emit nothing", emptyList<String>(), emitted)
        assertEquals(SlmEngineError.PAYLOAD_REJECTED, error?.error)
        assertEquals("prefill_over_limit_or_unreadable", error?.message)
        assertNull(engine.servedModelId())
        assertNull(engine.finishReason())
    }

    @Test
    fun theOverContextGoldenPromptIsRefusedByTheRuntimeErrorAndEmitsNothing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val row = File(context.filesDir, "slm-parity/prompts-v1.jsonl").readLines(Charsets.UTF_8)
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "g-006" }
        val prompt = row.getValue("prompt").jsonPrimitive.content

        val (emitted, error) = attempt(prompt)
        status("over_context", "chars=${prompt.length} error=${error?.error} reason=${error?.message}")

        assertEquals("an over-context prompt must emit nothing", emptyList<String>(), emitted)
        assertEquals(SlmEngineError.PAYLOAD_REJECTED, error?.error)
        // R9-a: this is the runtime's own error, matched on the pinned text; a changed text fails here.
        assertEquals("input_too_long", error?.message)
    }

    @Test
    fun anEnumerationCappedAtSixteenOutputTokensFinishesLength() {
        val (emitted, error) = attempt("Count from one to fifty, writing each number on its own line.", maxOutputTokens = 16)
        val counts = engine.lastCounts()
        status("capped", "decode=${counts?.decodeTokens} prefill=${counts?.prefillTokens} finish=${engine.finishReason()}")

        assertNull(error)
        assertEquals(SlmFinishReason.LENGTH, engine.finishReason())
        assertTrue("decode count ${counts?.decodeTokens}", (counts?.decodeTokens ?: 0) >= 16)
        assertEquals("one chunk after the post-run checks", 1, emitted.size)
    }

    private fun bandPrompt(digits: Int): String = buildPrompt(
        SlmInvocation(
            ApprovedRecordSnapshot(
                caseRecordId = "band-probe",
                kernelDecision = KernelDecision.AGREE,
                diagnosis = "Serial readings: " + (0 until digits).joinToString(" ") { ((it * 7) % 10).toString() },
                medicationLines = listOf("Amoxicillin - 500 mg | oral | three times a day | 5 days | 15"),
                suggestsReferral = false,
            ),
            "Explain the prescription in plain language",
        ),
    )

    companion object {
        private lateinit var engine: LocalSlmEngine

        @BeforeClass
        @JvmStatic
        fun startEngine() {
            engine = localSlmEngine(InstrumentationRegistry.getInstrumentation().targetContext)
        }

        @AfterClass
        @JvmStatic
        fun releaseEngine() = runBlocking { engine.release() }
    }
}
