package com.example.samdapp.domain.slm

/**
 * Measured metadata about what the sanitizer removed from one generation
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §6.1, "Not silent").
 *
 * The reason these counts exist is detectability, not display. The control-token set below is
 * taken from **this** export's vocabulary; a model version bump can change the channel's token
 * identities or its behaviour, and a sanitizer that silently stops matching anything looks exactly
 * like a model that stopped emitting a reasoning channel. The counts are what separates those two.
 *
 * Everything here is a count. There is no field carrying suppressed text, and there must never be
 * one: §9.4 restricts the audit payload to measured metadata, and the suppressed span is
 * unvalidated model prose about a patient.
 *
 * @property spans thought spans entered. An unterminated span counts, because it was still a span.
 * @property characters characters removed from the visible stream, delimiters included.
 * @property droppedTokens standalone control tokens dropped outside a span: unrecognized reserved
 *   tokens, a close delimiter with no open, and a truncated control token at end of stream.
 */
data class SlmSuppression(
    val spans: Int = 0,
    val characters: Int = 0,
    val droppedTokens: Int = 0,
) {
    companion object {
        /** Nothing was suppressed. The expected result for a clean generation. */
        val NONE = SlmSuppression()
    }
}

/**
 * The reasoning channel's delimiters, as harness finding F6 observed them leaking into visible
 * output: `<unused94>thought ... <unused95>`.
 *
 * **Verified (stage 3b task 0).** Both are present in `google/medgemma-1.5-4b-it`'s
 * `tokenizer.json`, at ids 100 and 101. There is no `<start_of_thought>`-style token anywhere in
 * that vocabulary, so the word "thought" the harness saw is ordinary text emitted inside the span
 * rather than a token of its own, and stripping between these two delimiters is what handles it.
 * A rule keyed on the tokenizer's `special` flag would have missed the channel entirely: only
 * `<pad>`, `<eos>`, `<bos>`, `<unk>`, `<start_of_turn>` and `<end_of_turn>` carry that flag, and
 * the whole `<unused*>` range does not.
 *
 * **The residual, which this stage does not discharge.** That verification read the upstream
 * PyTorch repo, not the shipped `litert-community/MedGemma-1.5-4B-IT` `.litertlm` export, which is
 * not on the build machine. Under the H-15 artifact-versus-family standard that is family
 * evidence, one step closer than stage 3a's assumption but still not evidence about the shipped
 * build. **An export-level re-verification of this vocabulary remains a precondition of the flag
 * flip and belongs to stage 3b-1.** Until then [SlmSuppression] is the detector: a generation
 * whose spans count stays zero while the model is known to emit a channel is the signal that this
 * set is wrong for the shipped artifact.
 */
internal const val THOUGHT_OPEN = "<unused94>"
internal const val THOUGHT_CLOSE = "<unused95>"

/**
 * The reserved `<unused*>` range, **verified in full against `tokenizer.json` (stage 3b task 0)**.
 *
 * It is `<unused0>` through `<unused6241>`, and it lives in two disjoint id blocks: a low block at
 * ids 6..104 (`<unused0>`..`<unused98>`) and a high block at ids 256001..262143
 * (`<unused99>`..`<unused6241>`). The two blocks are contiguous in *name* and far apart in *id*,
 * which is exactly how stage 3a got it wrong: it hardcoded `(0..98)` from the low block alone and
 * left 6143 reserved tokens, 98 percent of the range, matching nothing and rendering as visible
 * text. That is a fail-open defect against §6.1's "fail-closed on an unknown control token".
 *
 * Ids are deliberately not modelled here. This class matches on the token's **text**, because
 * text is what arrives in a decoded chunk; the id blocks are recorded above so a future reader can
 * re-verify the range against a tokenizer dump without re-deriving which ids to look at.
 */
private val RESERVED_UNUSED: List<String> = (0..6241).map { "<unused$it>" }

/**
 * The named reserved tokens of the same vocabulary, at the head of the id space: `<pad>` 0,
 * `<eos>` 1, `<bos>` 2, `<unk>` 3, `<mask>` 4, `[multimodal]` 5, then the low `<unused*>` block,
 * then `<start_of_turn>` 105 and `<end_of_turn>` 106. `<start_of_image>`, `<end_of_image>` and
 * `<image_soft_token>` are the multimodal set, the last at 262144.
 *
 * **`[multimodal]` is the one that does not look like the others.** It is a genuine reserved token
 * at id 5, sitting between `<mask>` and `<unused0>`, and it uses square brackets. Stage 3a's
 * structural guard required every matched literal to be `<...>`, so it would have rejected this
 * token, and the sanitizer would have rendered a reserved token as visible text rather than
 * dropping it. The guard is re-expressed rather than the token omitted: see [CONTROL_TOKENS].
 */
private val NAMED_RESERVED: List<String> = listOf(
    "<pad>", "<bos>", "<eos>", "<unk>", "<mask>", "[multimodal]",
    "<start_of_turn>", "<end_of_turn>",
    "<start_of_image>", "<end_of_image>", "<image_soft_token>",
)

/**
 * **Every string this sanitizer matches on.** All of them are bracketed control-token literals
 * from the model's tokenizer vocabulary, and `SlmStreamSanitizerTest` asserts that property of the
 * set itself, so a phrase from clinical prose cannot be added here without turning the suite red.
 *
 * That assertion is the §6.2 line made structural. The memo's test for any filter proposal is
 * "if the rule cannot be written without referring to what the words mean, it is on the forbidden
 * side": `<unused94>` is a token identity, "consult your doctor" is a meaning.
 *
 * **The shape the guard enforces**, widened in stage 3b-0 to admit `[multimodal]` without letting
 * prose in: a literal opens with `<` or `[`, closes with the matching `>` or `]`, and its interior
 * is non-empty and contains no whitespace and no bracket character. A phrase cannot satisfy that,
 * because a phrase has spaces and no brackets, so `filterDisclaimers()` is still un-addable as
 * data and would have to arrive as a visibly new mechanism. Admitting a second bracket pair
 * widens *which vocabulary literals* are expressible; it does not widen the guard toward meaning.
 */
internal val CONTROL_TOKENS: Set<String> = (RESERVED_UNUSED + NAMED_RESERVED).toSet()

/** Length of the hold-back window: no control token is longer than this. */
internal val MAX_CONTROL_TOKEN_LENGTH: Int = CONTROL_TOKENS.maxOf { it.length }

/** Shortest control token. Nothing below this length can be one, so matching starts here. */
private val MIN_CONTROL_TOKEN_LENGTH: Int = CONTROL_TOKENS.minOf { it.length }

/**
 * The characters a control token can start with. Matching only probes the set at a position
 * beginning with one of these, which is what keeps a 6253-literal set cheap enough for a
 * per-chunk streaming path.
 */
private fun isTokenOpener(c: Char): Boolean = c == '<' || c == '['

/**
 * The §6.2 shape rule, as a function, so the guard test and a future reader apply the same rule.
 * True when [literal] is shaped like a control token from the vocabulary and not like prose.
 */
internal fun isControlTokenShaped(literal: String): Boolean {
    val closer = when (literal.firstOrNull()) {
        '<' -> '>'
        '[' -> ']'
        else -> return false
    }
    if (literal.length < 3 || literal.last() != closer) return false
    val interior = literal.substring(1, literal.length - 1)
    return interior.none { it.isWhitespace() || it == '<' || it == '>' || it == '[' || it == ']' }
}

/**
 * Deterministic control-token stripping on a token stream (§6.1). Sits at §9.1 pipeline stage 7,
 * between the engine's chunks and the output scope gate, and nothing generated may be displayed
 * without passing through it: harness F6 measured the model's reasoning channel arriving in
 * visible output.
 *
 * (The engine interface is deliberately not named anywhere in this file. The source scan in
 * `com.example.samdapp.config` asserts that only the interface itself and the seam name it, and
 * this class needs no more than a `String` per chunk to do its job.)
 *
 * **The only operation this class performs is control-token-delimited removal.** It never inspects
 * what a word means, never matches a phrase, never shortens a sentence and never rewrites one.
 * The model's own hedges - "consult your doctor" and the rest of what F3 measured it volunteering
 * - pass through byte for byte, and §6.2 requires exactly that: removing them makes the output
 * read more authoritative than the model was willing to be, in a device whose whole safety
 * argument (H-02) is the human in the loop. **The sample app's `filterDisclaimers()` is forbidden
 * here under any name.**
 *
 * There is deliberately no hook to add one. The constructor takes no arguments, no predicate, no
 * pattern and no lambda; the only data consulted is [CONTROL_TOKENS], a set of exact literals that
 * a test constrains to bracketed control tokens. Adding meaning-level filtering would mean adding
 * a new mechanism in a visible diff, not passing a different argument.
 *
 * **Stateful and single-use per generation.** One instance per invocation, fed [accept] in chunk
 * order, then [finish] exactly once. §7's single-turn rule means no state may outlive an
 * invocation, and this object holds a partial control token across chunks, so it must not be
 * shared or reused.
 *
 * Not thread-safe, and does not need to be: a generation is collected on one coroutine.
 */
class SlmStreamSanitizer {

    private val buffer = StringBuilder()
    private var inThought = false
    private var spans = 0
    private var characters = 0
    private var droppedTokens = 0

    /** Counts so far. Read after [finish] for the whole generation (§6.1, "Not silent"). */
    val suppression: SlmSuppression
        get() = SlmSuppression(spans = spans, characters = characters, droppedTokens = droppedTokens)

    /**
     * Takes the next chunk and returns the text that is safe to display **now**.
     *
     * The returned text is final: this class never asks a caller to un-display something. That is
     * the point of the hold-back window. Because a control token can straddle a chunk boundary
     * (D3), a trailing window of [MAX_CONTROL_TOKEN_LENGTH] characters is withheld from every
     * emission, so a half-arrived `<unused94>` is held rather than printed and then retracted.
     * Retraction on screen is a leak: the clinician already read it.
     */
    fun accept(chunk: String): String {
        buffer.append(chunk)
        val visible = StringBuilder()
        drain(visible)

        if (!inThought) {
            val emit = (buffer.length - MAX_CONTROL_TOKEN_LENGTH).coerceAtLeast(0)
            if (emit > 0) {
                visible.append(buffer, 0, emit)
                buffer.delete(0, emit)
            }
        }
        return visible.toString()
    }

    /**
     * Ends the generation and returns the last of the held-back text. Call exactly once.
     *
     * Two fail-closed rules live here:
     *
     * - **An unterminated span is discarded, not flushed.** If the stream ended inside a thought
     *   span, its content is dropped. An unterminated reasoning channel is not an answer, and a
     *   generation cut short mid-thought is the case where the channel is most likely to hold a
     *   candidate statement the model never committed to.
     * - **A truncated control token is dropped, not rendered.** A trailing fragment that is a
     *   proper prefix of a control token can no longer complete, and §6.1's rule that a partially
     *   arrived control token is never rendered as visible text has no exception for end of
     *   stream. It is counted in [SlmSuppression.droppedTokens] rather than dropped silently,
     *   because a stream that keeps ending mid-token is a channel-behaviour change worth seeing.
     */
    fun finish(): String {
        if (inThought) {
            characters += buffer.length
            buffer.setLength(0)
            return ""
        }

        val fragment = trailingControlTokenPrefixLength()
        if (fragment > 0) {
            characters += fragment
            droppedTokens++
            buffer.setLength(buffer.length - fragment)
        }

        val rest = buffer.toString()
        buffer.setLength(0)
        return rest
    }

    /**
     * Consumes every **complete** control token currently in the buffer, appending the text
     * between them to [visible]. Leaves the buffer holding only text with no complete control
     * token in it, which is what makes the hold-back window in [accept] sufficient.
     */
    private fun drain(visible: StringBuilder) {
        while (true) {
            if (inThought) {
                val close = buffer.indexOf(THOUGHT_CLOSE)
                if (close < 0) {
                    // Discard everything that cannot still be part of a half-arrived close token.
                    val keep = MAX_CONTROL_TOKEN_LENGTH.coerceAtMost(buffer.length)
                    characters += buffer.length - keep
                    buffer.delete(0, buffer.length - keep)
                    return
                }
                characters += close + THOUGHT_CLOSE.length
                buffer.delete(0, close + THOUGHT_CLOSE.length)
                inThought = false
            } else {
                val (at, token) = firstControlToken() ?: return
                visible.append(buffer, 0, at)
                buffer.delete(0, at + token.length)
                characters += token.length
                if (token == THOUGHT_OPEN) {
                    inThought = true
                    spans++
                } else {
                    // Unrecognized reserved token, or a close delimiter with no open. Dropped
                    // rather than rendered (§6.1, fail-closed on an unknown control token).
                    droppedTokens++
                }
            }
        }
    }

    /**
     * Earliest complete control token in the buffer, with its offset, or null. Exact string
     * membership against a fixed set of literals - no regex, and nothing that depends on what the
     * surrounding text says.
     *
     * **Direction of the scan, and why it changed in stage 3b-0.** Stage 3a asked "where is each
     * token", looping the set and calling `indexOf` per entry. That was tolerable over 109
     * literals and is not over 6253: it would be roughly 6253 buffer scans per arriving chunk, on
     * a field phone, on the streaming path. This asks the mirrored question - "at each position
     * that could open a token, is this one" - which is the same match with the same semantics, at
     * one hash lookup per candidate length per bracket character. No control token contains a
     * bracket after its first character, so a position that does not start with one cannot begin
     * a token and is skipped.
     *
     * Lengths are probed longest first. No token in this vocabulary is a proper prefix of another
     * (the closing bracket terminates every one), so longest-first and shortest-first agree here;
     * it is written this way so that a future vocabulary where they disagree still takes the
     * longer match, which is the fail-closed direction.
     */
    private fun firstControlToken(): Pair<Int, String>? {
        for (at in 0 until buffer.length) {
            if (!isTokenOpener(buffer[at])) continue
            val longest = minOf(MAX_CONTROL_TOKEN_LENGTH, buffer.length - at)
            for (length in longest downTo MIN_CONTROL_TOKEN_LENGTH) {
                val candidate = buffer.substring(at, at + length)
                if (candidate in CONTROL_TOKENS) return at to candidate
            }
        }
        return null
    }

    /**
     * Length of the trailing run that is a proper prefix of some control token, or 0.
     *
     * Only the last opener in the hold-back window can begin such a run: a control token contains
     * no bracket after its first character, so an earlier opener whose text is already fixed and
     * followed by more characters cannot still be completing.
     */
    private fun trailingControlTokenPrefixLength(): Int {
        val earliest = maxOf(0, buffer.length - (MAX_CONTROL_TOKEN_LENGTH - 1))
        for (at in buffer.length - 1 downTo earliest) {
            if (!isTokenOpener(buffer[at])) continue
            val tail = buffer.substring(at)
            return if (CONTROL_TOKENS.any { it.length > tail.length && it.startsWith(tail) }) tail.length else 0
        }
        return 0
    }
}
