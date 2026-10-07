package com.example.samdapp.domain.slm

/**
 * Measured metadata about what the sanitizer removed from one generation
 * (`docs/design/slm-guardrail-service-contract-memo.md` §6.1, "Not silent").
 *
 * The reason these counts exist is detectability, not display. The control-token set below is
 * taken from **one specific artifact's** vocabulary; a model swap can change the channel's token
 * identities or its behaviour, and a sanitizer that silently stops matching anything looks exactly
 * like a model that stopped emitting a reasoning channel. The counts are what separates those two.
 *
 * **PR-1 lesson, recorded here because it is the reason [unknownTokens] exists.** Counts alone were
 * not enough. Against `google/gemma-4-E2B-it` the previous set's delimiters (`<unused94>`,
 * `<unused95>`) still *existed* in the vocabulary, as ordinary reserved tokens with no channel
 * meaning, so membership succeeded, nothing failed closed, and every counter stayed at zero while
 * the live channel (`<|channel>` ... `<channel|>`) was absent from the set and leaked whole. A zero
 * suppression count was therefore ambiguous between "nothing to strip" and "wrong vocabulary". It
 * no longer is: an unknown bracketed construct is suppressed and counted separately, and the
 * artifact identity itself is checked at the seam ([servedModelMatchesSanitizer]).
 *
 * Everything here is a count. There is no field carrying suppressed text, and there must never be
 * one: §9.4 restricts the audit payload to measured metadata, and the suppressed span is
 * unvalidated model prose about a patient.
 *
 * @property spans channel spans entered. An unterminated span counts, because it was still a span.
 * @property characters characters removed from the visible stream, delimiters included.
 * @property droppedTokens standalone **known** control tokens dropped outside a span: a reserved
 *   token, a close delimiter with no open, and a truncated control token at end of stream.
 * @property unknownTokens bracketed constructs that satisfy [isControlTokenShaped] but are **not**
 *   in [CONTROL_TOKENS], suppressed fail-closed rather than rendered. Counted apart from
 *   [droppedTokens] on purpose. **A non-zero value here means the served model emitted a construct
 *   this sanitizer does not know about.** That is a signal, not noise: it is what a vocabulary
 *   drift, a model swap, or an export whose control grammar differs from the pinned tokenizer looks
 *   like from inside the stream. It is recorded rather than swallowed so the audit row carries it.
 */
data class SlmSuppression(
    val spans: Int = 0,
    val characters: Int = 0,
    val droppedTokens: Int = 0,
    val unknownTokens: Int = 0,
) {
    companion object {
        /** Nothing was suppressed. The expected result for a clean generation. */
        val NONE = SlmSuppression()
    }
}

/**
 * **The artifact this sanitizer's vocabulary was verified against.** PR-1, 2026-09-18.
 *
 * Verified by reading `tokenizer.json` from the Hugging Face cache snapshot at revision
 * `3e22461f65e89153144f8adb70e3b8c2cc9845a7`, SHA-256
 * `cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f`, on the serving machine. Same
 * standard stage 3b-0 applied when it verified the previous set against `google/medgemma-1.5-4b-it`.
 *
 * These constants are not decoration. They are the input to [servedModelMatchesSanitizer], which is
 * the durable half of PR-1: re-pinning a literal set fixes today's model, and comparing the pin
 * against what the server actually served is what makes the *next* swap loud instead of silent.
 */
internal const val SANITIZER_TARGET_MODEL_ID = "google/gemma-4-E2B-it"

/** Tokenizer revision the vocabulary below was read from. See [SANITIZER_TARGET_MODEL_ID]. */
internal const val SANITIZER_TOKENIZER_REVISION = "3e22461f65e89153144f8adb70e3b8c2cc9845a7"

/** SHA-256 of that `tokenizer.json`. See [SANITIZER_TARGET_MODEL_ID]. */
internal const val SANITIZER_TOKENIZER_SHA256 =
    "cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f"

/**
 * **The model-identity gate.** True only when the model the server says it served is the artifact
 * this sanitizer's vocabulary was verified against.
 *
 * The comparison is against the `model_id` field of the response envelope specified in
 * `docs/design/slm-remote-inference-memo.md` §2.2, which requires that field to be **derived from
 * the loaded artifact** rather than written as a literal. The sample serving app hardcodes its
 * `model` string and ignores the request's, which is precisely the defect this gate assumes will
 * recur.
 *
 * **A null or blank identity is a MISMATCH, not a free pass**, and that is the whole point of the
 * signature taking a nullable. "Nothing to compare, carry on" would defeat the control on day one,
 * because an envelope that has not implemented the field yet is the likeliest early-integration
 * state, and it is indistinguishable from a server that has silently been pointed at a different
 * artifact. Fail-closed is the only reading of an absent identity that is worth anything.
 *
 * Comparison is exact. Not a prefix, not a contains, not case-insensitive: a vocabulary is a
 * property of one artifact at one revision, and `gemma-4-E2B-it` is not `gemma-4-E2B-it-something`.
 */
internal fun servedModelMatchesSanitizer(servedModelId: String?): Boolean =
    servedModelId != null && servedModelId.isNotBlank() && servedModelId == SANITIZER_TARGET_MODEL_ID

/**
 * The reasoning channel's delimiters in `google/gemma-4-E2B-it`, ids 100 and 101.
 *
 * **This is a grammar, not a pair of strings, and that distinction is what PR-1 turned on.** The
 * construct is:
 *
 * ```
 * <|channel>thought\n  ...reasoning prose...  <channel|>
 * ```
 *
 * The channel is **named**, and the name arrives as plain text immediately after the open
 * delimiter. It is not a token. So a fix that only swapped the two delimiter literals would have
 * stripped `<|channel>` and `<channel|>` and left `thought` and everything after it visible next to
 * an approved prescription: the same leak through a smaller hole. The span rule below therefore
 * suppresses **everything between the delimiters, inclusive**, which covers the plain-text channel
 * name without needing to know what names exist.
 *
 * **Residual, stated rather than discovered later.** Suppressing every named channel assumes the
 * model's answer is not itself carried inside a channel. That holds for this artifact: the chat
 * template emits the answer as plain content of the model turn and uses the channel only for
 * thought. If a future export moved the answer into a named channel, this rule would suppress it,
 * and the detector for that is [SlmSuppression.spans] being non-zero while the visible output is
 * empty. Recorded as an open item in `docs/design/pr1-sanitizer-gemma4-repin.md`.
 *
 * **What is deliberately NOT a delimiter.** `<|think|>` (id 98) is an unpaired enable flag, not an
 * opener. It has no partner and delimits nothing. It is carried as an ordinary literal in
 * [NAMED_RESERVED] and is dropped like any other standalone control token; treating it as an opener
 * would start a span that never closes and swallow the rest of the generation.
 */
internal const val CHANNEL_OPEN = "<|channel>"
internal const val CHANNEL_CLOSE = "<channel|>"

/**
 * The reserved `<unused*>` range of `google/gemma-4-E2B-it`, **verified in full against that
 * artifact's `tokenizer.json`** (PR-1, pin above).
 *
 * It is `<unused0>` through `<unused6226>`, 6,227 tokens, contiguous by name and scattered across
 * six disjoint id blocks: 6-45, 53-97, 99, 102-104, 256001-258879, 258885-262143. None of them
 * carries the tokenizer's `special` flag, which is why a rule keyed on that flag would miss the
 * whole range.
 *
 * **Changed in PR-1, and the direction matters.** The previous set ran to `<unused6241>`, which was
 * correct for `google/medgemma-1.5-4b-it` and is 15 tokens too many here. `<unused6227>` through
 * `<unused6241>` are removed rather than left in as harmless no-matches: a literal that cannot
 * match implies a coverage this set does not have, and the next reader would take the range as
 * evidence about the served artifact.
 *
 * Ids are deliberately not modelled here. This class matches on the token's **text**, because text
 * is what arrives in a decoded chunk; the id blocks are recorded above so a future reader can
 * re-verify the range against a tokenizer dump without re-deriving which ids to look at.
 */
private val RESERVED_UNUSED: List<String> = (0..6226).map { "<unused$it>" }

/**
 * The named control tokens of the same vocabulary.
 *
 * `<pad>`, `<eos>`, `<bos>`, `<unk>` and `<mask>` occupy ids 0 through 4 (the exact assignment
 * within that block was not re-derived in PR-1 and nothing here depends on it). `[multimodal]` is
 * id 5, present in the vocabulary but **not** in `added_tokens`, so it carries no `special` flag,
 * and it is square-bracketed rather than angle-bracketed: it is the entry that forced the shape
 * guard to admit `[` in stage 3b-0 and it survives the move to Gemma 4 unchanged.
 *
 * The rest is Gemma 4's own control grammar: the named reasoning channel (`<|channel>` 100,
 * `<channel|>` 101) and its unpaired enable flag (`<|think|>` 98), the turn grammar (`<|turn>` 105,
 * `<turn|>` 106), the tool blocks, the escape token `<|"|>`, and the media placeholders
 * (`<|image|>` 258880, `<|audio|>` 258881, `<|video|>` 258884).
 *
 * **Known incompleteness, and why it is safe.** The artifact declares 24 added special tokens. Four
 * of them are the image and audio boundary markers (begin/end of image, begin/end of audio); their
 * exact spellings were **not** carried in the PR-1 handoff and are therefore **not guessed here**.
 * Writing a plausible-looking literal into a safety control's match set on the strength of a naming
 * convention is the failure this whole PR exists to prevent. Those four are covered anyway, by the
 * fail-closed catch-all in [SlmStreamSanitizer]: a bracketed construct that satisfies
 * [isControlTokenShaped] and is absent from this set is suppressed and counted in
 * [SlmSuppression.unknownTokens]. Completing the set is a one-line change once the spellings are
 * read out of `tokenizer.json`, and until then a non-zero `unknownTokens` on a multimodal
 * generation is the expected, visible consequence.
 *
 * Five literals from the previous set are **removed**, not retained: `<start_of_turn>`,
 * `<end_of_turn>`, `<start_of_image>`, `<end_of_image>` and `<image_soft_token>` do not exist in
 * this artifact's vocabulary. Same reasoning as the `<unused*>` trim above.
 */
private val NAMED_RESERVED: List<String> = listOf(
    "<pad>", "<bos>", "<eos>", "<unk>", "<mask>", "[multimodal]",
    "<|think|>", CHANNEL_OPEN, CHANNEL_CLOSE,
    "<|turn>", "<turn|>",
    "<|tool>", "<tool|>", "<|tool_call>", "<tool_call|>", "<|tool_response>", "<tool_response|>",
    "<|\"|>",
    "<|image|>", "<|audio|>", "<|video|>",
)

/**
 * **Every string this sanitizer matches on by name.** All of them are bracketed control-token
 * literals from the pinned artifact's tokenizer vocabulary, and `SlmStreamSanitizerTest` asserts
 * that property of the set itself, so a phrase from clinical prose cannot be added here without
 * turning the suite red.
 *
 * That assertion is the §6.2 line made structural. The memo's test for any filter proposal is
 * "if the rule cannot be written without referring to what the words mean, it is on the forbidden
 * side": `<|channel>` is a token identity, "consult your doctor" is a meaning.
 *
 * **The shape the guard enforces**, widened in stage 3b-0 to admit `[multimodal]` without letting
 * prose in: a literal opens with `<` or `[`, closes with the matching `>` or `]`, and its interior
 * is non-empty and contains no whitespace and no bracket character. A phrase cannot satisfy that,
 * because a phrase has spaces and no brackets, so `filterDisclaimers()` is still un-addable as
 * data and would have to arrive as a visibly new mechanism. Admitting a second bracket pair
 * widens *which vocabulary literals* are expressible; it does not widen the guard toward meaning.
 *
 * **Membership is no longer the whole rule (PR-1).** This set is the *known* vocabulary. Anything
 * shaped like a control token and absent from it is suppressed too, fail-closed, and counted
 * separately. See [SlmSuppression.unknownTokens].
 */
internal val CONTROL_TOKENS: Set<String> = (RESERVED_UNUSED + NAMED_RESERVED).toSet()

/** Length of the hold-back window: no known control token is longer than this. */
internal val MAX_CONTROL_TOKEN_LENGTH: Int = CONTROL_TOKENS.maxOf { it.length }

/**
 * The first control token from [CONTROL_TOKENS] that appears as text in [text], or null.
 *
 * **The outbound half of this file's set, added in PR-8, and it reuses the set rather than
 * restating it.** The service refuses a prompt carrying any of the loaded artifact's special or
 * added tokens with `SAMD-SLM-8009` and does not strip it (`slm-service-contract.md` §2.8). The
 * seam asks this question first, so injection-shaped text never leaves the handset. A second list
 * would be the defect [CONTROL_TOKENS] exists to prevent, one artifact repin away from two
 * disagreeing answers about the same vocabulary.
 *
 * **Membership only, deliberately, and NOT [isControlTokenShaped].** The shape rule is fail-closed
 * on the way in from the model, where an unrecognised bracketed construct is not an answer. On the
 * way out it would refuse a physician's `<pending>` in a free-text diagnosis, which the service
 * serves perfectly happily, so the handset would be blocking readbacks the service would answer.
 * Substring membership is also exactly what the service matches on, which is what lets a service
 * `8009` be read as a drift signal rather than as a second opinion.
 *
 * Cost is one pass of the set per readback, not per chunk, which is why this is written as the
 * obvious loop while the streaming path needed [SlmStreamSanitizer.firstControlToken]'s inverted
 * scan.
 */
internal fun firstControlTokenIn(text: String): String? = CONTROL_TOKENS.firstOrNull { it in text }

/** Shortest known control token. Nothing below this length can be one, so matching starts here. */
private val MIN_CONTROL_TOKEN_LENGTH: Int = CONTROL_TOKENS.minOf { it.length }

/**
 * The characters a control token can start with. Matching only probes the set at a position
 * beginning with one of these, which is what keeps a 6248-literal set cheap enough for a
 * per-chunk streaming path.
 */
private fun isTokenOpener(c: Char): Boolean = c == '<' || c == '['

/**
 * The §6.2 shape rule, as a function, so the guard test and a future reader apply the same rule.
 * True when [literal] is shaped like a control token from the vocabulary and not like prose.
 *
 * **Since PR-1 this function decides what gets suppressed, not only what may be listed.** The
 * fail-closed catch-all suppresses any bracketed run that satisfies it. So the prose cases below
 * are no longer only a listing guard, they are a statement about what survives on a clinician's
 * screen, and each is asserted in `SlmStreamSanitizerTest`:
 *
 * - `BP <120/80` survives: there is no closing `>` at all, so nothing is ever matched.
 * - `eGFR <60 and >90` survives: the run from `<` to the next `>` is `<60 and >`, whose interior
 *   holds whitespace, so the shape test rejects it.
 * - `temp >38 <39` survives: the run from `<` reaches end of text with no closer.
 * - `sat <95%` survives: no closer.
 *
 * What does **not** survive is a bracketed single word with no spaces, for example `<pending>`.
 * That is the deliberate cost of fail-closed: an unknown bracketed single token is treated as a
 * control construct and counted in [SlmSuppression.unknownTokens] rather than shown. A readback of
 * an approved record is restating structured fields, so that shape is not expected in it, and the
 * counter makes the choice visible rather than silent.
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
 * The model's own hedges, "consult your doctor" and the rest of what F3 measured it volunteering,
 * pass through byte for byte, and §6.2 requires exactly that: removing them makes the output read
 * more authoritative than the model was willing to be, in a device whose whole safety argument
 * (H-02) is the human in the loop. **The sample app's `filterDisclaimers()` is forbidden here under
 * any name.**
 *
 * There is deliberately no hook to add one. The constructor takes no arguments, no predicate, no
 * pattern and no lambda; the only data consulted is [CONTROL_TOKENS] plus the structural shape rule
 * in [isControlTokenShaped], neither of which can express meaning. Adding meaning-level filtering
 * would mean adding a new mechanism in a visible diff, not passing a different argument.
 *
 * **Two rules, and PR-1 added the second.** Membership handles the vocabulary that was verified;
 * the shape test handles everything else that is bracketed. Membership alone is fail-open by
 * construction, because an unanticipated construct simply does not match and is therefore rendered.
 * That is not hypothetical: it is exactly how Gemma 4's `<|channel>` grammar would have reached a
 * clinician under the previous set.
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
    private var inChannel = false
    private var spans = 0
    private var characters = 0
    private var droppedTokens = 0
    private var unknownTokens = 0

    /** Counts so far. Read after [finish] for the whole generation (§6.1, "Not silent"). */
    val suppression: SlmSuppression
        get() = SlmSuppression(
            spans = spans,
            characters = characters,
            droppedTokens = droppedTokens,
            unknownTokens = unknownTokens,
        )

    /**
     * Takes the next chunk and returns the text that is safe to display **now**.
     *
     * The returned text is final: this class never asks a caller to un-display something. That is
     * the point of the hold-back window. Because a control token can straddle a chunk boundary
     * (D3), a trailing window of [MAX_CONTROL_TOKEN_LENGTH] characters is withheld from every
     * emission, so a half-arrived `<|channel>` is held rather than printed and then retracted.
     * Retraction on screen is a leak: the clinician already read it.
     */
    fun accept(chunk: String): String {
        buffer.append(chunk)
        val visible = StringBuilder()
        drain(visible)

        if (!inChannel) {
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
     * - **An unterminated span is discarded, not flushed.** If the stream ended inside a channel
     *   span, its content is dropped. An unterminated reasoning channel is not an answer, and a
     *   generation cut short mid-thought is the case where the channel is most likely to hold a
     *   candidate statement the model never committed to.
     * - **A truncated control token is dropped, not rendered.** A trailing fragment that is a
     *   proper prefix of a known control token can no longer complete, and §6.1's rule that a
     *   partially arrived control token is never rendered as visible text has no exception for end
     *   of stream. It is counted in [SlmSuppression.droppedTokens] rather than dropped silently,
     *   because a stream that keeps ending mid-token is a channel-behaviour change worth seeing.
     *
     * **Residual, deliberate.** The trailing-fragment rule tests prefixes of *known* literals only.
     * A truncated *unknown* construct cannot be recognised as one: "a bracketed run that has not
     * closed yet" is the same text as `BP <120/80` at the moment the stream stops, and dropping it
     * would eat clinical prose. The fail-open here is one trailing fragment at end of stream, and
     * it is the narrower harm than truncating a vital sign.
     */
    fun finish(): String {
        if (inChannel) {
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
            if (inChannel) {
                val close = buffer.indexOf(CHANNEL_CLOSE)
                if (close < 0) {
                    // Discard everything that cannot still be part of a half-arrived close token.
                    val keep = MAX_CONTROL_TOKEN_LENGTH.coerceAtMost(buffer.length)
                    characters += buffer.length - keep
                    buffer.delete(0, buffer.length - keep)
                    return
                }
                // Everything from the open delimiter to the close inclusive goes, which is what
                // carries the plain-text channel name out with it.
                characters += close + CHANNEL_CLOSE.length
                buffer.delete(0, close + CHANNEL_CLOSE.length)
                inChannel = false
            } else {
                val match = firstControlToken() ?: return
                visible.append(buffer, 0, match.at)
                buffer.delete(0, match.at + match.text.length)
                characters += match.text.length
                when {
                    match.text == CHANNEL_OPEN -> {
                        inChannel = true
                        spans++
                    }
                    match.known -> {
                        // Known reserved token, or a close delimiter with no open. Dropped rather
                        // than rendered (§6.1, fail-closed on an unknown control token).
                        droppedTokens++
                    }
                    else -> {
                        // Shaped like a control token, absent from the verified vocabulary. This is
                        // the PR-1 catch-all: suppressed, and counted apart so that it reads as the
                        // signal it is rather than as ordinary reserved-token traffic.
                        unknownTokens++
                    }
                }
            }
        }
    }

    /** Where a control token was found, what it was, and whether the vocabulary knows it. */
    private data class TokenMatch(val at: Int, val text: String, val known: Boolean)

    /**
     * Earliest complete control token in the buffer, with its offset, or null. Exact string
     * membership against a fixed set of literals first, then the structural shape test. No regex,
     * and nothing that depends on what the surrounding text says.
     *
     * **Direction of the scan, and why it changed in stage 3b-0.** Stage 3a asked "where is each
     * token", looping the set and calling `indexOf` per entry. That was tolerable over 109
     * literals and is not over 6248: it would be roughly 6248 buffer scans per arriving chunk, on
     * a field phone, on the streaming path. This asks the mirrored question, "at each position
     * that could open a token, is this one", which is the same match with the same semantics, at
     * one hash lookup per candidate length per bracket character. No known control token contains a
     * bracket after its first character, so a position that does not start with one cannot begin
     * a token and is skipped.
     *
     * Lengths are probed longest first. No token in this vocabulary is a proper prefix of another
     * (the closing bracket terminates every one), so longest-first and shortest-first agree here;
     * it is written this way so that a future vocabulary where they disagree still takes the
     * longer match, which is the fail-closed direction.
     *
     * **Known before unknown, deliberately.** A position that matches a known literal is reported
     * as known even though it would also satisfy the shape test, so the `unknownTokens` counter
     * means what its KDoc says: constructs this sanitizer was not built for.
     */
    private fun firstControlToken(): TokenMatch? {
        for (at in 0 until buffer.length) {
            if (!isTokenOpener(buffer[at])) continue

            val longest = minOf(MAX_CONTROL_TOKEN_LENGTH, buffer.length - at)
            for (length in longest downTo MIN_CONTROL_TOKEN_LENGTH) {
                val candidate = buffer.substring(at, at + length)
                if (candidate in CONTROL_TOKENS) return TokenMatch(at, candidate, known = true)
            }

            val unknown = unknownShapedTokenAt(at) ?: continue
            return TokenMatch(at, unknown, known = false)
        }
        return null
    }

    /**
     * The complete bracketed run starting at [at] when it is shaped like a control token and is not
     * in the known vocabulary, or null.
     *
     * Bounded by [MAX_CONTROL_TOKEN_LENGTH] for the same reason the hold-back window is: a run
     * longer than the longest thing this vocabulary can express is not a control token, and
     * scanning further would let one stray `<` eat an arbitrary amount of clinical prose.
     *
     * **The ceiling this creates, named rather than left to be discovered.** An unknown construct
     * longer than [MAX_CONTROL_TOKEN_LENGTH] is not caught: its closer falls outside the scan and
     * it is rendered. The bound cannot simply be raised, because it is the same number as the
     * hold-back window in [SlmStreamSanitizer.accept], and a construct longer than that window can
     * straddle a chunk boundary and be partly emitted before it is recognised, which is the
     * retraction leak the window exists to prevent. The two move together or not at all. Today the
     * window is 16, set by `<|tool_response>`, so a 17-character-or-longer foreign construct passes.
     * `SlmStreamSanitizerTest` asserts this limit directly rather than leaving it implicit, and
     * `docs/design/pr1-sanitizer-gemma4-repin.md` carries it as an open item: the upgrade path is to
     * set both the window and this bound from a declared maximum rather than from the longest known
     * literal, which costs a larger hold-back on every chunk and is not worth it until a real
     * vocabulary needs it.
     *
     * The scan stops at the first whitespace or bracket character, which is what keeps `eGFR <60
     * and >90` intact: the run from `<` to the next `>` contains a space, so it is rejected here
     * rather than being handed to [isControlTokenShaped] and rejected there. Same answer, one pass.
     *
     * **Angle brackets only, and this is a deliberate narrowing of the catch-all.** Square-bracket
     * runs are not probed for *unknown* constructs, though `[multimodal]` is still matched by name
     * like any other known literal. The reason is measured rather than aesthetic: `See note [2]`
     * satisfies the shape rule exactly (opener, non-empty interior, no whitespace, closer), so an
     * unrestricted catch-all deletes a footnote reference out of clinical prose. That case is in
     * `SlmStreamSanitizerTest` and it went red when this function probed both brackets. The trade is
     * asymmetric and the asymmetry is in the vocabulary: this artifact has exactly **one**
     * square-bracket control token and it is known by name, while its entire generative control
     * grammar (`<|channel>`, `<|turn>`, `<|tool*>`, the media placeholders) is angle-bracketed. So
     * the unknown-construct risk lives behind `<` and the prose-collision risk lives behind `[`.
     * Suppressing on shape is only defensible where the shape is not also ordinary writing.
     *
     * The residual: an unknown *square-bracket* control token would be rendered. It would be caught
     * by the identity gate instead, which refuses the whole readback when the served artifact is not
     * the pinned one, and that is the layer built for vocabulary drift.
     */
    private fun unknownShapedTokenAt(at: Int): String? {
        // Angle brackets only. See the KDoc above for why square brackets are excluded.
        if (buffer[at] != '<') return null
        val closer = '>'
        val limit = minOf(buffer.length, at + MAX_CONTROL_TOKEN_LENGTH)
        for (end in at + 1 until limit) {
            val c = buffer[end]
            if (c == closer) {
                val candidate = buffer.substring(at, end + 1)
                return if (isControlTokenShaped(candidate)) candidate else null
            }
            if (c.isWhitespace() || c == '<' || c == '>' || c == '[' || c == ']') return null
        }
        return null
    }

    /**
     * Length of the trailing run that is a proper prefix of some known control token, or 0.
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
