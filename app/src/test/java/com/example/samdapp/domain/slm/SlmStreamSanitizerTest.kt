package com.example.samdapp.domain.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3a of the SLM build: the stream sanitizer
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §6.1 and §6.2).
 *
 * The two sections point in opposite directions on purpose. §6.1 requires text to be removed from
 * the stream; §6.2 forbids text being removed from the stream. Both sets of tests are here, next
 * to each other, because the line between them is the whole safety property and a reader checking
 * one should be looking at the other.
 *
 * **Synthetic streams, deliberately.** No engine is bound at this stage, so every stream here is
 * hand-built. That is not a weakness of these tests, it is the point of doing this stage now: a
 * real engine cannot be asked to split a control token across a chunk boundary on demand, and the
 * straddling case is the one the hold-back window exists for.
 *
 * Independent sourcing, per CLAUDE.md: expected suppression counts are recomputed from the span
 * strings the test itself fed in (`span.length`), never read back off the object under test.
 */
class SlmStreamSanitizerTest {

    /** Feeds chunks in order and returns everything the sanitizer ever emitted, in order. */
    private fun run(vararg chunks: String): Pair<String, SlmStreamSanitizer> {
        val sanitizer = SlmStreamSanitizer()
        val emitted = StringBuilder()
        chunks.forEach { emitted.append(sanitizer.accept(it)) }
        emitted.append(sanitizer.finish())
        return emitted.toString() to sanitizer
    }

    // ------------------------------------------------------------- §6.1 control-token stripping

    /**
     * The channel harness finding F6 measured: `<unused94>thought ... <unused95>` in visible
     * output. The span goes, everything around it stays.
     */
    @Test
    fun `a delimited thought span is removed and the surrounding content survives`() {
        val (visible, _) = run(
            "Take one capsule. $THOUGHT_OPEN" +
                "thought: the user may be asking about the dose. Consider mentioning amoxicillin." +
                "$THOUGHT_CLOSE Take it after food.",
        )

        assertEquals("Take one capsule.  Take it after food.", visible)
        assertFalse("the reasoning channel reached the visible stream", visible.contains("thought"))
        assertFalse(visible.contains(THOUGHT_OPEN))
        assertFalse(visible.contains(THOUGHT_CLOSE))
    }

    @Test
    fun `several spans in one generation are all removed`() {
        val (visible, sanitizer) = run(
            "A${THOUGHT_OPEN}first$THOUGHT_CLOSE B${THOUGHT_OPEN}second$THOUGHT_CLOSE C",
        )

        assertEquals("A B C", visible)
        assertEquals(2, sanitizer.suppression.spans)
    }

    /**
     * The hold-back window (§6.1, "Stream-safe"). The open delimiter is split across three chunks
     * so that no single chunk contains it.
     *
     * **What is asserted is the absence of a retraction, not just the final text.** Every prefix
     * of the emitted stream is checked as it is produced: no partial control token is ever visible,
     * at any point, so there is nothing on screen to take back. A retraction is a leak - §6.1's
     * reason is that the clinician already read it - and a test that only inspected the final
     * string would pass on an implementation that printed `<unu` and then erased it.
     */
    @Test
    fun `a control token straddling chunk boundaries is never emitted as visible text`() {
        val chunks = listOf("Dose is one. <un", "used9", "4>thought: hidden reasoning", "$THOUGHT_CLOSE Done.")
        val sanitizer = SlmStreamSanitizer()
        val emitted = StringBuilder()
        val prefixes = mutableListOf<String>()

        chunks.forEach {
            emitted.append(sanitizer.accept(it))
            prefixes += emitted.toString()
        }
        emitted.append(sanitizer.finish())
        prefixes += emitted.toString()

        assertEquals("Dose is one.  Done.", emitted.toString())
        prefixes.forEach { prefix ->
            assertFalse("a partial control token was displayed and would have to be retracted: '$prefix'", prefix.contains("<un"))
            assertFalse("reasoning text was displayed and would have to be retracted: '$prefix'", prefix.contains("hidden"))
        }
        // Emitted output only ever grows: nothing already shown was withdrawn.
        prefixes.zipWithNext { earlier, later ->
            assertTrue("the emitted stream shrank, so something on screen was retracted", later.startsWith(earlier))
        }
    }

    /**
     * Fail-closed on an unterminated span (§6.1). The stream stops mid-thought, which is exactly
     * where the channel is most likely to hold a candidate statement the model never committed to.
     */
    @Test
    fun `an unterminated thought span is discarded rather than flushed at end of stream`() {
        val (visible, sanitizer) = run("Here is the plan. ${THOUGHT_OPEN}maybe suggest ibuprofen instead")

        assertEquals("Here is the plan. ", visible)
        assertFalse(visible.contains("ibuprofen"))
        assertEquals(1, sanitizer.suppression.spans)
    }

    /** The same, when the span opens in one chunk and the stream simply stops in the next. */
    @Test
    fun `an unterminated span split across chunks is still discarded`() {
        val (visible, _) = run("Answer. $THOUGHT_OPEN", "considering an alternative drug", " and another")

        assertEquals("Answer. ", visible)
    }

    /** Fail-closed on an unknown reserved token (§6.1): dropped from the visible stream. */
    @Test
    fun `an unrecognized reserved token is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it <unused7>after<end_of_turn> food.")

        assertEquals("Take it after food.", visible)
        assertEquals(2, sanitizer.suppression.droppedTokens)
        assertEquals(0, sanitizer.suppression.spans)
    }

    /** A close delimiter with no open is not a structure the sanitizer recognizes, so it goes. */
    @Test
    fun `a close delimiter with no open is dropped`() {
        val (visible, sanitizer) = run("Take it${THOUGHT_CLOSE} after food.")

        assertEquals("Take it after food.", visible)
        assertEquals(1, sanitizer.suppression.droppedTokens)
    }

    /** A control token truncated by end of stream can never complete, so it is not rendered. */
    @Test
    fun `a control token truncated at end of stream is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it after food. <unus")

        assertEquals("Take it after food. ", visible)
        assertEquals(1, sanitizer.suppression.droppedTokens)
        assertEquals("<unus".length, sanitizer.suppression.characters)
    }

    // ------------------------------------------------------------------ §6.1 suppression counts

    /**
     * Counts are the §6.1 "Not silent" requirement and the only planned detector for a model
     * version bump changing the channel's token identities. Expected values are recomputed here
     * from the spans this test fed in, not read off the sanitizer.
     */
    @Test
    fun `suppression counts the spans and the characters it removed`() {
        val firstSpan = "${THOUGHT_OPEN}considering the dose$THOUGHT_CLOSE"
        val secondSpan = "${THOUGHT_OPEN}and the duration$THOUGHT_CLOSE"

        val (visible, sanitizer) = run("Start. $firstSpan middle $secondSpan end.")

        assertEquals("Start.  middle  end.", visible)
        assertEquals(2, sanitizer.suppression.spans)
        assertEquals(firstSpan.length + secondSpan.length, sanitizer.suppression.characters)
        assertEquals(0, sanitizer.suppression.droppedTokens)
    }

    @Test
    fun `a clean generation reports no suppression at all`() {
        val (visible, sanitizer) = run("The doctor approved amoxicillin, three times a day, after food.")

        assertEquals("The doctor approved amoxicillin, three times a day, after food.", visible)
        assertEquals(SlmSuppression.NONE, sanitizer.suppression)
    }

    /** An unterminated span still reports what it swallowed, so it is not an invisible loss. */
    @Test
    fun `an unterminated span is counted as a span and as characters`() {
        val tail = "${THOUGHT_OPEN}the stream stopped here"

        val (_, sanitizer) = run("Answer. $tail")

        assertEquals(1, sanitizer.suppression.spans)
        assertEquals(tail.length, sanitizer.suppression.characters)
    }

    // ------------------------------------------------- §6.2 the forbidden line, enforced by test

    /**
     * The §6.2 preservation proof. F3 measured this model volunteering "consult your doctor" style
     * caveats unprompted, and §6.2 requires them preserved verbatim: removing them makes the output
     * read more authoritative than the model was willing to be, in a device whose safety argument
     * (H-02) rests on the human in the loop.
     *
     * The named anti-pattern is the sample app's `filterDisclaimers()`, forbidden here under any
     * name. This test is what fails if someone reintroduces it.
     */
    @Test
    fun `the model's own hedges pass through verbatim`() {
        val hedged = "This is a general explanation and not medical advice. Please consult your doctor " +
            "before changing anything. I am not a substitute for a clinician. Always consult your doctor " +
            "if symptoms get worse."

        val (visible, sanitizer) = run(hedged)

        assertEquals(hedged, visible)
        assertEquals(SlmSuppression.NONE, sanitizer.suppression)
    }

    /** A hedge sitting immediately next to a suppressed span survives the span's removal intact. */
    @Test
    fun `a hedge adjacent to a thought span survives verbatim`() {
        val hedge = "Please consult your doctor before changing the dose."

        val (visible, _) = run("${THOUGHT_OPEN}should I hedge here$THOUGHT_CLOSE$hedge")

        assertEquals(hedge, visible)
    }

    /**
     * The structural half of §6.2, and the reason this file is more than a behaviour suite.
     *
     * Every string the sanitizer matches on must be a bracketed control-token literal. A phrase
     * from clinical prose cannot satisfy that shape, so `filterDisclaimers()` cannot be smuggled
     * in by adding an entry to the existing list - it would have to arrive as a visible new
     * mechanism. This is the memo's own test made executable: "if the rule cannot be written
     * without referring to what the words mean, it is on the forbidden side".
     */
    @Test
    fun `every string the sanitizer matches on is a bracketed control-token literal`() {
        assertTrue("the control-token set is empty, so this assertion proves nothing", CONTROL_TOKENS.isNotEmpty())

        val notControlTokens = CONTROL_TOKENS.filterNot { token ->
            token.length > 2 && token.startsWith("<") && token.endsWith(">") &&
                token.drop(1).dropLast(1).none { it == '<' || it == '>' || it.isWhitespace() }
        }

        assertEquals(
            "A non-control-token string was added to the sanitizer's match set. Memo section 6.2: " +
                "meaning-level filtering of the model's own hedges is forbidden, and the sanitizer's " +
                "only permitted operation is control-token-delimited removal.",
            emptyList<String>(),
            notControlTokens,
        )
        assertTrue("the delimiters under test are not in the matched set", THOUGHT_OPEN in CONTROL_TOKENS)
        assertTrue("the delimiters under test are not in the matched set", THOUGHT_CLOSE in CONTROL_TOKENS)
    }

    /**
     * No hook to add meaning-level filtering. The sanitizer takes no predicate, no pattern and no
     * lambda anywhere in its API or its state, so there is no argument a caller could pass to make
     * it judge content - a filter would have to be a new mechanism in a visible diff.
     */
    @Test
    fun `the sanitizer exposes no predicate or pattern hook`() {
        val java = SlmStreamSanitizer::class.java

        val types = java.declaredFields.map { it.type.name } +
            java.declaredMethods.flatMap { method -> method.parameterTypes.map { it.name } } +
            java.declaredConstructors.flatMap { constructor -> constructor.parameterTypes.map { it.name } }

        val hooks = types.filter {
            it.contains("Regex") || it.contains("Pattern") || it.contains("kotlin.jvm.functions")
        }

        assertEquals("a content-judgement hook was added to the sanitizer (memo section 6.2)", emptyList<String>(), hooks)
        assertEquals("the sanitizer gained a constructor argument", 0, java.declaredConstructors.single().parameterCount)
    }

    // ----------------------------------------------------------------------------- window sanity

    /**
     * Non-vacuity guard for the straddling test: the hold-back window has to be at least as long
     * as the longest control token, or splitting a token across chunks would prove nothing.
     */
    @Test
    fun `the hold-back window is at least as long as the longest control token`() {
        assertEquals(CONTROL_TOKENS.maxOf { it.length }, MAX_CONTROL_TOKEN_LENGTH)
        assertTrue(MAX_CONTROL_TOKEN_LENGTH >= THOUGHT_OPEN.length)
    }

    /** Chunking must not change the answer: the same stream split every possible way is identical. */
    @Test
    fun `the result is independent of where the chunk boundaries fall`() {
        val stream = "Take one capsule ${THOUGHT_OPEN}hidden$THOUGHT_CLOSE after food. Consult your doctor."
        val whole = run(stream).first

        for (split in 1 until stream.length) {
            val (parts, _) = run(stream.substring(0, split), stream.substring(split))
            assertEquals("splitting at $split changed the output", whole, parts)
        }
    }
}
