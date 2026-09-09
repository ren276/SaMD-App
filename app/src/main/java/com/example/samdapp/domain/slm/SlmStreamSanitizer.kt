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
 * **Provenance, and the part of it that is not yet verified.** `<unused94>` and `<unused95>` are
 * reserved tokens in the Gemma tokenizer vocabulary this export inherits, and the memo records
 * them as the observed delimiters. The exact vocabulary of the shipped `.litertlm` artifact has
 * not been read in this stage, because the artifact is not in this repo and the engine is not
 * bound. **Stage 3b must re-verify this set against the shipped export's tokenizer and correct it
 * if it differs.** Until then [SlmSuppression] is the detector: a generation whose spans count
 * stays zero while the model is known to emit a channel is the signal that this set is wrong.
 */
internal const val THOUGHT_OPEN = "<unused94>"
internal const val THOUGHT_CLOSE = "<unused95>"

/** The reserved range `<unused0>`..`<unused98>` of the Gemma vocabulary. */
private val RESERVED_UNUSED: List<String> = (0..98).map { "<unused$it>" }

/**
 * The named special tokens of the same vocabulary. Same provenance caveat as [THOUGHT_OPEN], and
 * the same stage-3b verification requirement.
 */
private val NAMED_SPECIALS: List<String> = listOf(
    "<pad>", "<bos>", "<eos>", "<unk>", "<mask>",
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
 * side": `<unused94>` is a token identity, "consult your doctor" is a meaning. A set that may
 * only contain `<...>` literals cannot express a meaning.
 */
internal val CONTROL_TOKENS: Set<String> = (RESERVED_UNUSED + NAMED_SPECIALS).toSet()

/** Length of the hold-back window: no control token is longer than this. */
internal val MAX_CONTROL_TOKEN_LENGTH: Int = CONTROL_TOKENS.maxOf { it.length }

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
     * matching against a fixed set - no regex, and nothing that depends on what the surrounding
     * text says.
     */
    private fun firstControlToken(): Pair<Int, String>? {
        var bestAt = -1
        var bestToken: String? = null
        for (token in CONTROL_TOKENS) {
            val at = buffer.indexOf(token)
            if (at < 0) continue
            if (bestAt < 0 || at < bestAt || (at == bestAt && token.length > bestToken!!.length)) {
                bestAt = at
                bestToken = token
            }
        }
        return bestToken?.let { bestAt to it }
    }

    /** Length of the trailing run that is a proper prefix of some control token, or 0. */
    private fun trailingControlTokenPrefixLength(): Int {
        for (length in minOf(MAX_CONTROL_TOKEN_LENGTH - 1, buffer.length) downTo 1) {
            val tail = buffer.substring(buffer.length - length)
            if (CONTROL_TOKENS.any { it.length > length && it.startsWith(tail) }) return length
        }
        return 0
    }
}
