package com.example.samdapp.domain.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3a of the SLM build: the stream sanitizer
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §6.1 and §6.2), **re-pointed in PR-1 from
 * `google/medgemma-1.5-4b-it` to `google/gemma-4-E2B-it`**
 * (`scratchpad/pr1-sanitizer-gemma4-repin.md`).
 *
 * The two sections point in opposite directions on purpose. §6.1 requires text to be removed from
 * the stream; §6.2 forbids text being removed from the stream. Both sets of tests are here, next
 * to each other, because the line between them is the whole safety property and a reader checking
 * one should be looking at the other.
 *
 * **What PR-1 changed in this file, so a reader can see it rather than reconstruct it.** Every test
 * below existed before and every one is re-pointed rather than deleted; each carries a note naming
 * the MedGemma-era literal it used to drive and why the swap was not cosmetic. The delimiters moved
 * from `<unused94>`/`<unused95>`, which in Gemma 4 still *exist* as ordinary reserved tokens with
 * no channel meaning, to `<|channel>`/`<channel|>`, which Gemma 4 actually emits and the old set
 * did not contain. That is why the old suite would have stayed entirely green while the whole
 * reasoning channel leaked: nothing here fed it Gemma 4 text, and the one test that pinned the
 * vocabulary pinned the wrong vocabulary.
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
     * The channel harness finding F6 measured, re-pointed.
     *
     * *MedGemma-era intent:* `<unused94>thought ... <unused95>` in visible output. The span goes,
     * everything around it stays.
     *
     * *What PR-1 changed:* Gemma 4's construct is `<|channel>thought\n ... <channel|>`, and the
     * channel **name is plain text**, not a token. So this test now also proves the thing a
     * two-string swap would have missed: the word `thought` sitting immediately after the open
     * delimiter is inside the span and goes with it. A fix that stripped only the delimiters would
     * pass the old assertion and fail this one.
     */
    @Test
    fun `a delimited channel span is removed and the surrounding content survives`() {
        val (visible, _) = run(
            "Take one capsule. $CHANNEL_OPEN" +
                "thought\nthe user may be asking about the dose. Consider mentioning amoxicillin." +
                "$CHANNEL_CLOSE Take it after food.",
        )

        assertEquals("Take one capsule.  Take it after food.", visible)
        assertFalse("the reasoning channel reached the visible stream", visible.contains("thought"))
        assertFalse("the plain-text channel name survived the span removal", visible.contains("thought"))
        assertFalse(visible.contains(CHANNEL_OPEN))
        assertFalse(visible.contains(CHANNEL_CLOSE))
    }

    /**
     * The channel name is the part a delimiter-only fix leaks, so it gets its own assertion rather
     * than riding along inside the test above. Nothing of the name, and nothing of the reasoning,
     * reaches the visible stream.
     *
     * *New in PR-1.* There was no equivalent under MedGemma, where the channel was unnamed and the
     * word "thought" was ordinary prose inside the span rather than a structural element following
     * the delimiter.
     */
    @Test
    fun `the plain-text channel name is inside the span and is suppressed with it`() {
        val span = "${CHANNEL_OPEN}thought\nreduce the dose to 250 mg$CHANNEL_CLOSE"

        val (visible, sanitizer) = run("Before. $span After.")

        assertEquals("Before.  After.", visible)
        assertFalse("the channel name leaked", visible.contains("thought"))
        assertFalse("reasoning content leaked", visible.contains("250"))
        assertEquals(1, sanitizer.suppression.spans)
        assertEquals(span.length, sanitizer.suppression.characters)
    }

    /** *MedGemma-era intent unchanged:* several spans in one generation are all removed. */
    @Test
    fun `several spans in one generation are all removed`() {
        val (visible, sanitizer) = run(
            "A${CHANNEL_OPEN}thought\nfirst$CHANNEL_CLOSE B${CHANNEL_OPEN}thought\nsecond$CHANNEL_CLOSE C",
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
     * at any point, so there is nothing on screen to take back. A retraction is a leak, §6.1's
     * reason is that the clinician already read it, and a test that only inspected the final
     * string would pass on an implementation that printed `<|ch` and then erased it.
     *
     * *What PR-1 changed:* the split points moved from inside `<unused94>` to inside `<|channel>`.
     * The window itself also grew, because Gemma 4's longest literal is `<|tool_response>` at 16
     * characters against MedGemma's 12.
     */
    @Test
    fun `a control token straddling chunk boundaries is never emitted as visible text`() {
        val chunks = listOf("Dose is one. <|ch", "ann", "el>thought\nhidden reasoning", "$CHANNEL_CLOSE Done.")
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
            assertFalse("a partial control token was displayed and would have to be retracted: '$prefix'", prefix.contains("<|ch"))
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
     *
     * *MedGemma-era intent unchanged.* Only the delimiter moved.
     */
    @Test
    fun `an unterminated channel span is discarded rather than flushed at end of stream`() {
        val (visible, sanitizer) = run("Here is the plan. ${CHANNEL_OPEN}thought\nmaybe suggest ibuprofen instead")

        assertEquals("Here is the plan. ", visible)
        assertFalse(visible.contains("ibuprofen"))
        assertEquals(1, sanitizer.suppression.spans)
    }

    /** The same, when the span opens in one chunk and the stream simply stops in the next. */
    @Test
    fun `an unterminated span split across chunks is still discarded`() {
        val (visible, _) = run("Answer. $CHANNEL_OPEN", "thought\nconsidering an alternative drug", " and another")

        assertEquals("Answer. ", visible)
    }

    /**
     * Fail-closed on a known reserved token (§6.1): dropped from the visible stream.
     *
     * *What PR-1 changed:* this test used to pair `<unused7>` with `<end_of_turn>`. `<end_of_turn>`
     * does **not** exist in Gemma 4's vocabulary and was removed from the set, so it is no longer a
     * known-token case at all. It reappears below, as an *unknown*-token case, which is the more
     * interesting thing it is now. Here it is replaced by `<|turn>`, a real Gemma 4 literal at id
     * 105.
     */
    @Test
    fun `a known reserved token is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it <unused7>after<|turn> food.")

        assertEquals("Take it after food.", visible)
        assertEquals(2, sanitizer.suppression.droppedTokens)
        assertEquals(0, sanitizer.suppression.spans)
        assertEquals(0, sanitizer.suppression.unknownTokens)
    }

    /**
     * **The stage-3a defect, as a test, re-pointed.**
     *
     * *MedGemma-era intent:* `<unused99>` is the first token of the high id block (256001), which
     * stage 3a's `(0..98)` range did not cover; `<unused6241>` was the last token of that block.
     *
     * *What PR-1 changed:* Gemma 4's range ends at `<unused6226>`, not `<unused6241>`, and it is
     * scattered across six id blocks rather than two. The high-block probe therefore moves to
     * `<unused6226>`. `<unused6241>` is now out of vocabulary, which the removal test below asserts
     * directly.
     */
    @Test
    fun `a reserved token from a high id block is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it <unused99>after<unused6226> food.")

        assertEquals("Take it after food.", visible)
        assertEquals(2, sanitizer.suppression.droppedTokens)
        assertEquals("<unused99>".length + "<unused6226>".length, sanitizer.suppression.characters)
    }

    /** A high-block token straddling a chunk boundary is held back like any other. */
    @Test
    fun `a high-block reserved token straddling chunks is never emitted as visible text`() {
        val sanitizer = SlmStreamSanitizer()
        val emitted = StringBuilder()
        val prefixes = mutableListOf<String>()

        listOf("Take it <unu", "sed6226", "> after food.").forEach {
            emitted.append(sanitizer.accept(it))
            prefixes += emitted.toString()
        }
        emitted.append(sanitizer.finish())

        assertEquals("Take it  after food.", emitted.toString())
        prefixes.forEach { assertFalse("a partial reserved token was displayed: '$it'", it.contains("<unu")) }
    }

    /**
     * `[multimodal]` is a reserved token at id 5 and it uses square brackets, so stage 3a's guard
     * would have refused to carry it and the sanitizer would have rendered it. It is dropped like
     * any other reserved token.
     *
     * *Unchanged by PR-1:* `[multimodal]` sits at id 5 in Gemma 4 too. It is one of the six
     * literals that carried over from the MedGemma set untouched.
     */
    @Test
    fun `the square-bracket reserved token is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it [multimodal]after food.")

        assertEquals("Take it after food.", visible)
        assertEquals(1, sanitizer.suppression.droppedTokens)
    }

    /**
     * The other half of admitting `[`: ordinary square brackets in clinical prose are not tokens
     * and must survive. A bracket is now a position the matcher probes, not a character it eats.
     *
     * *Load-bearing differently since PR-1.* Under MedGemma this guarded the listing rule only.
     * Now that an unknown bracketed run is suppressed on shape alone, this string is a live test of
     * the catch-all's appetite: `[as needed]` and `[2]` both contain a space or survive on their
     * own, and neither may be eaten.
     */
    @Test
    fun `ordinary square brackets in clinical text are untouched`() {
        val text = "Amoxicillin 500 mg [as needed] for 5 days. See note [2] and consult your doctor."

        val (visible, sanitizer) = run(text)

        assertEquals(text, visible)
        assertEquals(SlmSuppression.NONE, sanitizer.suppression)
    }

    /** A close delimiter with no open is not a structure the sanitizer recognizes, so it goes. */
    @Test
    fun `a close delimiter with no open is dropped`() {
        val (visible, sanitizer) = run("Take it${CHANNEL_CLOSE} after food.")

        assertEquals("Take it after food.", visible)
        assertEquals(1, sanitizer.suppression.droppedTokens)
    }

    /**
     * A control token truncated by end of stream can never complete, so it is not rendered.
     *
     * *What PR-1 changed:* `<unus` is still a proper prefix of a Gemma 4 literal, so this test
     * drives the same path with the same string. Kept verbatim on purpose, as the one place the
     * re-pointing is visibly a no-op.
     */
    @Test
    fun `a control token truncated at end of stream is dropped rather than rendered`() {
        val (visible, sanitizer) = run("Take it after food. <unus")

        assertEquals("Take it after food. ", visible)
        assertEquals(1, sanitizer.suppression.droppedTokens)
        assertEquals("<unus".length, sanitizer.suppression.characters)
    }

    // ------------------------------------------- PR-1: the unpaired think flag, and the catch-all

    /**
     * `<|think|>` (id 98) is an **unpaired enable flag**, not a delimiter. It opens nothing.
     *
     * The failure this test exists to catch is treating it as an opener, which is an easy mistake
     * to make from its name: a span that opens on `<|think|>` never finds a partner, so it runs to
     * end of stream and the fail-closed unterminated-span rule swallows the entire answer. The
     * assertion that matters is therefore `spans == 0` and the surviving text, not the drop itself.
     *
     * *New in PR-1.* MedGemma had no equivalent token.
     */
    @Test
    fun `the unpaired think flag is a plain literal and does not open a span`() {
        val (visible, sanitizer) = run("Take one capsule <|think|>after food, twice a day.")

        assertEquals("Take one capsule after food, twice a day.", visible)
        assertEquals("the think flag was treated as a span opener and swallowed the answer", 0, sanitizer.suppression.spans)
        assertEquals(1, sanitizer.suppression.droppedTokens)
        assertEquals(0, sanitizer.suppression.unknownTokens)
    }

    /**
     * **The fail-closed catch-all.** Membership alone is fail-open by construction: a construct the
     * set does not name simply does not match, and is rendered. That is not hypothetical, it is
     * exactly how Gemma 4's `<|channel>` would have reached a clinician under the MedGemma set.
     *
     * `<end_of_turn>` is the ideal probe because it is a real token of a *different* model and is
     * absent from this one, which is the shape of every future vocabulary drift.
     *
     * *New in PR-1.*
     */
    @Test
    fun `a bracketed construct absent from the vocabulary is suppressed, not rendered`() {
        val (visible, sanitizer) = run("Take it <end_of_turn>after food.")

        assertEquals("Take it after food.", visible)
        assertEquals("an unknown construct was rendered to the clinician", 1, sanitizer.suppression.unknownTokens)
        assertEquals("an unknown construct was miscounted as a known reserved token", 0, sanitizer.suppression.droppedTokens)
    }

    /**
     * The two counters mean different things and must not be pooled. A known reserved token is
     * ordinary traffic; an unknown one is a signal that the served artifact is emitting a grammar
     * this build was not verified against. Pooling them would hide the second inside the first.
     *
     * *New in PR-1.*
     */
    @Test
    fun `known and unknown suppressions are counted separately`() {
        val (visible, sanitizer) = run("A<unused7>B<end_of_turn>C<|turn>D<start_of_turn>E")

        assertEquals("ABCDE", visible)
        assertEquals("known reserved tokens miscounted", 2, sanitizer.suppression.droppedTokens)
        assertEquals("unknown constructs miscounted", 2, sanitizer.suppression.unknownTokens)
    }

    /**
     * **The catch-all's ceiling, asserted so that it is a known limit rather than a surprise.**
     *
     * The unknown-construct scan is bounded by [MAX_CONTROL_TOKEN_LENGTH], which is the same number
     * as the hold-back window, because a construct longer than the window can straddle a chunk
     * boundary and be partly displayed before it is recognised. So a foreign construct longer than
     * the longest *known* literal is not caught. `<image_soft_token>` is 18 characters against a
     * window of 16, which makes it the natural probe: it is a real MedGemma token, it is absent
     * from this vocabulary, and it is past the bound.
     *
     * This test documents the gap rather than the guarantee. If the window is ever driven from a
     * declared maximum instead of the longest known literal, this test is the one that should be
     * inverted, deliberately, in that change.
     *
     * *New in PR-1.*
     */
    @Test
    fun `an unknown construct longer than the hold-back window is a known gap in the catch-all`() {
        val tooLong = "<image_soft_token>"
        assertTrue("the probe is no longer past the bound, so this test proves nothing", tooLong.length > MAX_CONTROL_TOKEN_LENGTH)

        val (visible, sanitizer) = run("Take it ${tooLong}after food.")

        assertEquals("Take it ${tooLong}after food.", visible)
        assertEquals("the ceiling moved: the catch-all now reaches past the window", 0, sanitizer.suppression.unknownTokens)
    }

    /**
     * **The cost of the catch-all, bounded and asserted.** Suppressing on shape alone could eat
     * clinical prose, so every shape a vital sign takes is tested. Each survives for a stated
     * structural reason, not by luck:
     *
     * - `BP <120/80` and `sat <95%`: no closing `>` anywhere, so no run is ever completed.
     * - `eGFR <60 and >90`: the run from `<` to the next `>` holds whitespace.
     * - `temp <38.5 and pulse >92`: same.
     * - `HR <60>` would NOT survive, and that is the deliberate fail-closed cost, documented on
     *   [isControlTokenShaped] rather than hidden.
     *
     * *New in PR-1.* Under MedGemma the shape rule governed only what could be listed, so prose
     * could not be eaten by it and no test of this kind was needed.
     */
    @Test
    fun `clinical measurements written with angle brackets survive the catch-all`() {
        listOf(
            "BP <120/80 on arrival, recheck in one week.",
            "eGFR <60 and >90 are both recorded in the notes.",
            "Oxygen sat <95% at rest, review if it falls further.",
            "temp <38.5 and pulse >92, both improving since yesterday.",
            "Give if weight <10 kg, otherwise use the adult dose.",
        ).forEach { text ->
            val (visible, sanitizer) = run(text)

            assertEquals("a clinical measurement was eaten by the catch-all", text, visible)
            assertEquals("a clinical measurement was counted as a control construct: '$text'", SlmSuppression.NONE, sanitizer.suppression)
        }
    }

    // ------------------------------------------------------------------ §6.1 suppression counts

    /**
     * Counts are the §6.1 "Not silent" requirement. Expected values are recomputed here from the
     * spans this test fed in, not read off the sanitizer.
     *
     * *What PR-1 changed:* the counts were the **only** planned detector for a model swap changing
     * the channel's token identities, and PR-1 established that they are not sufficient on their
     * own. Against Gemma 4 the old delimiters still resolved as reserved tokens, so no counter
     * moved and a zero read exactly like a clean generation. The detector is now three things
     * together: these counts, [SlmSuppression.unknownTokens], and the identity gate at the seam.
     */
    @Test
    fun `suppression counts the spans and the characters it removed`() {
        val firstSpan = "${CHANNEL_OPEN}thought\nconsidering the dose$CHANNEL_CLOSE"
        val secondSpan = "${CHANNEL_OPEN}thought\nand the duration$CHANNEL_CLOSE"

        val (visible, sanitizer) = run("Start. $firstSpan middle $secondSpan end.")

        assertEquals("Start.  middle  end.", visible)
        assertEquals(2, sanitizer.suppression.spans)
        assertEquals(firstSpan.length + secondSpan.length, sanitizer.suppression.characters)
        assertEquals(0, sanitizer.suppression.droppedTokens)
        assertEquals(0, sanitizer.suppression.unknownTokens)
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
        val tail = "${CHANNEL_OPEN}thought\nthe stream stopped here"

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
     * name. This test is what fails if someone reintroduces it. Unchanged by PR-1: a re-pinning of
     * the vocabulary must not move this line, and the fact that it did not is part of what makes
     * the change reviewable.
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
    fun `a hedge adjacent to a channel span survives verbatim`() {
        val hedge = "Please consult your doctor before changing the dose."

        val (visible, _) = run("${CHANNEL_OPEN}thought\nshould I hedge here$CHANNEL_CLOSE$hedge")

        assertEquals(hedge, visible)
    }

    /**
     * The structural half of §6.2, and the reason this file is more than a behaviour suite.
     *
     * Every string the sanitizer matches on must be a bracketed control-token literal. A phrase
     * from clinical prose cannot satisfy that shape, so `filterDisclaimers()` cannot be smuggled
     * in by adding an entry to the existing list, it would have to arrive as a visible new
     * mechanism. This is the memo's own test made executable: "if the rule cannot be written
     * without referring to what the words mean, it is on the forbidden side".
     */
    @Test
    fun `every string the sanitizer matches on is a bracketed control-token literal`() {
        assertTrue("the control-token set is empty, so this assertion proves nothing", CONTROL_TOKENS.isNotEmpty())

        val notControlTokens = CONTROL_TOKENS.filterNot { isControlTokenShaped(it) }

        assertEquals(
            "A non-control-token string was added to the sanitizer's match set. Memo section 6.2: " +
                "meaning-level filtering of the model's own hedges is forbidden, and the sanitizer's " +
                "only permitted operation is control-token-delimited removal.",
            emptyList<String>(),
            notControlTokens,
        )
        assertTrue("the delimiters under test are not in the matched set", CHANNEL_OPEN in CONTROL_TOKENS)
        assertTrue("the delimiters under test are not in the matched set", CHANNEL_CLOSE in CONTROL_TOKENS)
    }

    /**
     * The shape rule itself, exercised directly rather than only through the set, because stage
     * 3b-0 widened it and a widening is exactly where a guard quietly stops guarding.
     *
     * Admitting square brackets was forced by a real vocabulary entry: `[multimodal]` is a
     * reserved token at id 5. Stage 3a's guard required `<...>`, so it would have rejected a
     * genuine reserved token and left the sanitizer rendering it.
     *
     * What the widening must not do is admit prose. Every rejection case below is the shape of
     * something `filterDisclaimers()` would need: a phrase, a fragment of one, a bare word, or a
     * bracket wrapped around a phrase.
     *
     * *What PR-1 changed:* the accepted examples moved to Gemma 4 literals. `<image_soft_token>`
     * and `<start_of_turn>` were MedGemma tokens and are gone from this vocabulary; they still
     * satisfy the *shape* rule, which is the point of the catch-all, so they are no longer useful
     * as "this is a real literal" examples and have been replaced by ones that are.
     */
    @Test
    fun `the shape rule admits vocabulary literals and still rejects prose`() {
        listOf("[multimodal]", "<unused94>", "<unused6226>", "<pad>", CHANNEL_OPEN, CHANNEL_CLOSE, "<|think|>", "<|tool_response>")
            .forEach { assertTrue("a real vocabulary literal was rejected: $it", isControlTokenShaped(it)) }

        listOf(
            "consult your doctor",
            "[consult your doctor]",
            "<consult your doctor>",
            "consult",
            "not medical advice",
            "",
            "<>",
            "[]",
            "<a<b>>",
            "<unused94",
            "unused94>",
        ).forEach { assertFalse("prose or a malformed literal was admitted: '$it'", isControlTokenShaped(it)) }
    }

    /**
     * The vocabulary the set claims to cover, asserted against the ids recorded in
     * `tokenizer.json`. This is the test that would have caught the stage-3a defect: it hardcoded
     * `(0..98)` from the low id block and missed the high block.
     *
     * *What PR-1 changed, and this is the one that goes red on a bad re-pin:* Gemma 4's range is
     * `<unused0>..<unused6226>`, 6,227 tokens across six disjoint id blocks (6-45, 53-97, 99,
     * 102-104, 256001-258879, 258885-262143), not MedGemma's `..<unused6241>` across two. The size
     * assertion moved from `6242 + 11` to `6227 + 21` and pins both halves of the set, so adding a
     * literal without reading it out of the tokenizer turns this red.
     */
    @Test
    fun `the reserved range covers the verified vocabulary in full and stops where it stops`() {
        (0..98).forEach { assertTrue("<unused$it> is missing from the matched set", "<unused$it>" in CONTROL_TOKENS) }
        listOf(99, 100, 500, 3000, 6225, 6226)
            .forEach { assertTrue("<unused$it> is missing from the matched set", "<unused$it>" in CONTROL_TOKENS) }

        assertFalse("the range runs past this artifact's vocabulary", "<unused6227>" in CONTROL_TOKENS)
        assertFalse("the MedGemma-era range end is still in the set", "<unused6241>" in CONTROL_TOKENS)
        assertEquals("the matched set is not the verified size", 6227 + 21, CONTROL_TOKENS.size)
    }

    /**
     * **The five MedGemma literals that were removed rather than left as harmless no-matches.**
     *
     * Leaving them in would have cost nothing at runtime and would have been worse documentation:
     * a literal that cannot match implies a coverage this set does not have, and the next reader
     * would take the range as evidence about the served artifact. They are asserted absent so that
     * re-adding one is a visible failure rather than a quiet restoration.
     *
     * *New in PR-1.*
     */
    @Test
    fun `literals that do not exist in the pinned artifact are absent from the set`() {
        listOf("<start_of_turn>", "<end_of_turn>", "<start_of_image>", "<end_of_image>", "<image_soft_token>")
            .forEach { assertFalse("a foreign vocabulary literal is still in the matched set: $it", it in CONTROL_TOKENS) }
    }

    /**
     * The pin itself, asserted so that editing the vocabulary without editing the provenance, or
     * the reverse, is a red test rather than a silent divergence. These constants are what the
     * seam's identity gate compares against, so they are load-bearing rather than documentation.
     *
     * *New in PR-1.*
     */
    @Test
    fun `the sanitizer declares the artifact and tokenizer it was verified against`() {
        assertEquals("google/gemma-4-E2B-it", SANITIZER_TARGET_MODEL_ID)
        assertEquals("3e22461f65e89153144f8adb70e3b8c2cc9845a7", SANITIZER_TOKENIZER_REVISION)
        assertEquals(
            "cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f",
            SANITIZER_TOKENIZER_SHA256,
        )
    }

    /**
     * The identity comparison's own truth table, exercised here rather than only through the seam.
     * The absent cases are the ones that matter: an envelope with no `model_id` is the likeliest
     * early-integration state and must not read as agreement.
     *
     * *New in PR-1.*
     */
    @Test
    fun `the model identity comparison is exact and treats an absent identity as a mismatch`() {
        assertTrue(servedModelMatchesSanitizer(SANITIZER_TARGET_MODEL_ID))

        listOf(null, "", "   ", "google/medgemma-1.5-4b-it", "gemma-4-E2B-it", "google/gemma-4-E2B-it-v2", "GOOGLE/GEMMA-4-E2B-IT")
            .forEach { assertFalse("a non-matching served identity was accepted: ${it ?: "null"}", servedModelMatchesSanitizer(it)) }
    }

    /**
     * No hook to add meaning-level filtering. The sanitizer takes no predicate, no pattern and no
     * lambda anywhere in its API or its state, so there is no argument a caller could pass to make
     * it judge content, a filter would have to be a new mechanism in a visible diff.
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
        assertTrue(MAX_CONTROL_TOKEN_LENGTH >= CHANNEL_OPEN.length)
        assertTrue(MAX_CONTROL_TOKEN_LENGTH >= CHANNEL_CLOSE.length)
    }

    /**
     * Chunking must not change the answer: the same stream split every possible way is identical.
     *
     * *Strengthened in PR-1.* The stream now carries an unknown construct and an angle-bracketed
     * vital alongside the channel span, because the catch-all introduced a second scan whose
     * bound interacts with the hold-back window. A boundary falling inside an unclosed `<` run is
     * exactly where that interaction would show.
     */
    @Test
    fun `the result is independent of where the chunk boundaries fall`() {
        val stream = "BP <120/80. Take one capsule ${CHANNEL_OPEN}thought\nhidden$CHANNEL_CLOSE" +
            "after food<end_of_turn>. Consult your doctor."
        val whole = run(stream).first

        for (split in 1 until stream.length) {
            val (parts, _) = run(stream.substring(0, split), stream.substring(split))
            assertEquals("splitting at $split changed the output", whole, parts)
        }
    }
}
