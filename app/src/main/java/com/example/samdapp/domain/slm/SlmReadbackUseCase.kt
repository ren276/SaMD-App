package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.toCadreTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * One invocation of the SLM: exactly one approved-record snapshot and exactly one question
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §7).
 *
 * **The single-turn rule is enforced here, by the absence of a field.** There is no history field
 * and no list-of-messages field, so a caller cannot pass a transcript - not because passing one is
 * discouraged, but because there is no parameter it could arrive on. §7's third reason is the one
 * that matters: every control in §5 evaluates one question against one snapshot, and a history
 * buffer lets turn 1 establish context that turn 3's question leans on while itself looking
 * tethered. Removing history removes that class of bypass rather than defending against it.
 *
 * `SlmReadbackUseCaseTest` asserts this type's exact field list and that two sequential
 * invocations with the same snapshot and question produce byte-identical prompts.
 */
internal data class SlmInvocation(
    val snapshot: ApprovedRecordSnapshot,
    val question: String,
)

/**
 * Why a readback was refused. Every one is a typed refusal rather than a truncated or empty
 * prompt (§4.4) and every one is a first-class UI state rather than an error toast (§5.6).
 *
 * The reason code is what a later stage's audit row carries. The question text and the generated
 * text never are (§9.4).
 */
enum class SlmRefusal {
    /** Empty or whitespace-only question. Harness F5: an empty input to this model produced
     *  hallucinated content running to about 2000 tokens, not an abstention. */
    EMPTY_QUESTION,

    /** Question over [MAX_QUESTION_CHARS]: prompt-injection surface and latency blowup. */
    QUESTION_TOO_LONG,

    /** The assembled prompt is over [MAX_PROMPT_CHARS]. Refused rather than truncated: a record
     *  silently cut inside the prompt is one the model answers about incompletely with no signal. */
    RECORD_TOO_LARGE,

    /** No committed `KernelDecision` for the case. Not physician-approved, so readback is not
     *  permitted at all (§4.1). Delegated to [ApprovedRecordReader]. */
    NOT_APPROVED,

    /** No such case record. Nothing to read back, and not a reason to generate. */
    CASE_UNRESOLVABLE,

    /** The case resolved but the report could not be assembled. */
    ASSEMBLY_FAILED,

    /** Input scope gate: the question asks for a drug or disease property rather than a readback. */
    OUT_OF_SCOPE_PHRASING,

    /** Input scope gate: the question does not match any readback-shaped intent. */
    OUT_OF_SCOPE_INTENT,

    /** Input scope gate: the question names a drug or dose that is not in the approved record. */
    OUT_OF_SCOPE_UNTETHERED,

    /** Output scope gate: the generation introduced clinical content absent from the record. The
     *  entire output is suppressed and never edited into compliance (§5.4, §6.2). */
    OUTPUT_NOT_GROUNDED,

    /**
     * The service could not be reached: no route, DNS failure, connection refused.
     *
     * **This is the normal case, not an error case**, and the vocabulary exists to say so. The
     * deployment target is an offline-first device in a rural PHC, so "no network right now" is the
     * expected daily condition of the readback feature, and presenting it in the same words as a
     * failed model is how a worker learns to distrust both. Retryable, and retryable soon.
     */
    ENGINE_UNREACHABLE,

    /**
     * The service was reached and did not answer inside the budget.
     *
     * Distinct from [ENGINE_UNREACHABLE] because the remedies differ: unreachable is about the link,
     * a timeout is about load or a generation that ran long. Retryable, and worth telling the worker
     * that the service is up, because "try again in a moment" is true here and not there.
     *
     * A cancelled readback is NOT this. Cancellation produces no result at all.
     */
    ENGINE_TIMEOUT,

    /**
     * The service is up and is not serving: the backend's circuit is open, or the service reports
     * its model is not loaded.
     *
     * Retryable, but not immediately, and that is the whole reason it is separate from
     * [ENGINE_TIMEOUT]. An open circuit means the backend has already decided that hammering the
     * service makes things worse, and a UI that invites an instant retry works against it.
     */
    ENGINE_UNAVAILABLE,

    /**
     * The service refused the request itself: over the input token limit, an unknown prompt-template
     * version, or a `model_id` it declines to serve. Any 4xx.
     *
     * **Not retryable unchanged, and that is the point of typing it.** This is the class that
     * F6B-02 hop 7 showed being destroyed: a specific, actionable, recoverable defect signal
     * collapsed into a generic outage by one blanket catch, so the worker was told the AI was
     * unavailable when the truth was that something about the request was wrong and would stay
     * wrong. A build emitting these is misconfigured, and somebody needs to see that.
     *
     * Distinct from [SERVED_MODEL_MISMATCH], and the two are easy to confuse. This one is the
     * service **refusing to serve** what was asked for, before generating. That one is the service
     * having **served something else** and said so in the response. Request side and response side.
     */
    ENGINE_REJECTED_INPUT,

    /**
     * The service failed inside itself: a 5xx, an out-of-memory during generation, or a response
     * that could not be parsed into the §2.2 envelope.
     *
     * The catch-all, and deliberately last: anything the seam cannot classify lands here rather
     * than being waved through, so an unrecognised failure is still a refusal. A failure is shown
     * as a failure; there is no substitute output (§9.2). Retryable, cause unknown to the device.
     */
    ENGINE_FAILED,

    /**
     * The generation was cut off: the envelope's `finish_reason` was `length`, or it carried no
     * finish reason at all.
     *
     * **This is the most dangerous failure in the set and it is invisible to every other gate on
     * this path.** A truncated readback passes [SlmStreamSanitizer] untouched, because truncation
     * is not a control token. It passes the output grounding gate **by construction**, because a
     * cut-off restatement of an approved record introduces no drug name and no numeral that was not
     * already in the record, so [outputIsGrounded] returns true on it every time. It arrives over a
     * 200. Nothing downstream of the response envelope can tell it from a complete answer.
     *
     * What reaches the worker if it is not caught is half a dosing instruction read as a whole one:
     * "take one capsule three times a day for" with the duration missing, or a line that stops
     * before the food relation. The `finish_reason` field of the §2.2 envelope is the only place in
     * the entire system where this is detectable, which is why that contract requires the field to
     * be **derived from the generation** rather than asserted, and why the sample serving app's
     * unconditional literal `"stop"` is called out in the memo as a defect that must be impossible
     * in the new contract.
     *
     * A missing finish reason is this refusal too. "The service did not say whether the answer is
     * complete" and "the answer is not complete" are the same thing from the worker's side, and
     * fail-closed is the only reading worth having.
     *
     * Retryable: a second generation may fit, and a persistent recurrence means the output ceiling
     * is too low for the records being read back, which is a tuning signal rather than a fault.
     */
    OUTPUT_TRUNCATED,

    /**
     * The server served an artifact this build was not verified against, or did not say which
     * artifact it served.
     *
     * **This is the durable control PR-1 exists for.** Every other gate on this path is calibrated
     * to one model: [SlmStreamSanitizer]'s literal set is that artifact's tokenizer vocabulary, and
     * its channel grammar is that artifact's channel grammar. Point the server at a different model
     * and the sanitizer keeps returning clean-looking output while the new model's control
     * constructs pass through to a clinician, with every suppression counter reading zero. That is
     * not hypothetical: it is precisely what the previous MedGemma-pinned set would have done
     * against `google/gemma-4-E2B-it`.
     *
     * A missing identity is this refusal too, deliberately. See [servedModelMatchesSanitizer].
     */
    SERVED_MODEL_MISMATCH,
}

/** Terminal result of [SlmReadbackUseCase]. */
sealed interface SlmReadbackResult {

    /**
     * [text] is exactly what the engine produced, unedited. §6.2 forbids meaning-level filtering,
     * including removal of the hedges this model volunteers on its own (F3), so the seam either
     * passes the whole generation or suppresses the whole generation.
     *
     * [promptTemplateVersion] travels with the answer because §9.2 requires the template version
     * on every invocation, on the H-12 `derivation_rule_version` precedent: an auditor has to be
     * able to separate a template change from a model change.
     *
     * [suppression] is what [SlmStreamSanitizer] removed on the way here (§6.1, "Not silent").
     * It travels with the answer rather than being dropped at the seam because the audit stage
     * has to record it: a model version bump that changes the reasoning channel's token identities
     * shows up as a spans count that quietly falls to zero, and nothing else would show it. Counts
     * only - never the suppressed text (§9.4).
     */
    data class Answer(
        val text: String,
        val promptTemplateVersion: String,
        val suppression: SlmSuppression,
    ) : SlmReadbackResult

    data class Refused(val reason: SlmRefusal) : SlmReadbackResult
}

/**
 * The guardrail seam (§9.1). The single domain entry point for everything the model touches: one
 * use case, one place to reason about the gate, one place to test it. Presentation calls this and
 * nothing else on the SLM path - it has no access to [SlmEngine] (see that interface's KDoc and
 * `com.example.samdapp.config.SlmEngineIsUnreachableFromPresentationTest`).
 *
 * The pipeline, in §9.1's order. Stages marked *(later stage)* are named seams with nothing behind
 * them yet, so the shape of the pipeline is fixed now and a later stage fills a slot rather than
 * re-cutting the flow:
 *
 * 1. Input hard rejects (§4.4) - empty question, oversized question.
 * 2. Snapshot via [ApprovedRecordReader] (§4.2) - approval, resolvability and PHI exclusion are
 *    that reader's guarantees, restated here as typed refusals rather than re-implemented.
 * 3. Prompt budget (§4.4).
 * 4. Tier resolution from the live `UserSession` (§5.1), fail-closed.
 * 5. Input scope gate (§5.4), WORKER tier only. **On a refusal the engine is never called.**
 * 6. [SlmEngine.generate] - *unbound interface at this stage*; stage 3b binds it.
 * 7. [SlmStreamSanitizer] (§6.1) - control-token stripping on every chunk, before anything is
 *    displayed and before the output gate sees the text.
 * 7b. Model identity (PR-1) - [servedModelMatchesSanitizer] against [SlmEngine.servedModelId].
 *    Tier-blind, and before the grounding gate, because grounding cannot compensate for a gate
 *    calibrated to the wrong artifact.
 * 7c. Completeness (PR-2) - [SlmEngine.finishReason] against [COMPLETE_FINISH_REASONS]. Tier-blind.
 *    The grounding gate cannot catch truncation, so the envelope is the only place it is visible.
 * 8. Output scope gate (§5.4), WORKER tier. Suppress whole or pass whole, never edit.
 * 9. Audit (§9.4) - *later stage*; needs new `AuditAction` values and the backend enum mirror in
 *    the same commit, so it is deliberately not started here.
 *
 * **What a `PHYSICIAN`-tier viewer gets.** The input scope gate is bypassed: §5.3 grants open
 * querying, and D4 fixed that. The output *grounding* gate is bypassed too, and that is not an
 * oversight - §5.4 scopes grounding to the WORKER tier, and grounding a physician's free-form
 * answer against a record they did not ask about would refuse every open query, cancelling D4 by
 * the back door. The output-side controls §5.3 does give the doctor tier are the sanitizer (§6.1),
 * the hedge-preservation prohibition (§6.2), single-turn (§7) and audit (§9.4). Of those, only
 * single-turn, the no-editing rule and (since stage 3a) the sanitizer exist at this stage; all
 * three apply to every tier here. Audit arrives with its stage. The sanitizer being tier-blind is
 * deliberate: the reasoning channel is not an answer for a physician either.
 *
 * **H-06 caveat, same as every other tier gate in this app.** This makes reaching the open tier
 * visible and attributable. It does not make it hard: the role comes from the backend account
 * record, but nothing verifies that the person holding the phone is a licensed physician (§5.5).
 */
class SlmReadbackUseCase @Inject constructor(
    private val authSession: AuthSession,
    private val approvedRecordReader: ApprovedRecordReader,
    private val engine: SlmEngine,
) {

    suspend operator fun invoke(caseRecordId: String, question: String): SlmReadbackResult {
        // 1. Hard rejects that need no record. Cheapest first, and before anything is assembled.
        if (question.isBlank()) return SlmReadbackResult.Refused(SlmRefusal.EMPTY_QUESTION)
        if (question.length > MAX_QUESTION_CHARS) {
            return SlmReadbackResult.Refused(SlmRefusal.QUESTION_TOO_LONG)
        }

        // 2. The record. Approval, resolvability and PHI exclusion are the reader's guarantees.
        val snapshot = when (val record = approvedRecordReader(caseRecordId)) {
            is ApprovedRecordResult.Available -> record.snapshot
            is ApprovedRecordResult.Refused -> return SlmReadbackResult.Refused(
                when (record.reason) {
                    SnapshotRefusal.NOT_APPROVED -> SlmRefusal.NOT_APPROVED
                    SnapshotRefusal.CASE_UNRESOLVABLE -> SlmRefusal.CASE_UNRESOLVABLE
                    SnapshotRefusal.ASSEMBLY_FAILED -> SlmRefusal.ASSEMBLY_FAILED
                },
            )
        }

        val invocation = SlmInvocation(snapshot = snapshot, question = question.trim())
        val prompt = buildPrompt(invocation)

        // 3. Prompt budget: refuse rather than truncate.
        if (prompt.length > MAX_PROMPT_CHARS) {
            return SlmReadbackResult.Refused(SlmRefusal.RECORD_TOO_LARGE)
        }

        // 4. Tier, from the live session. Fail-closed: no session is a worker.
        val openTier = authSession.currentUser().first().isOpenSlmTier()

        // 5. Input scope gate. Returning here means the engine is never touched.
        if (!openTier) {
            inputScopeRefusal(invocation)?.let { return SlmReadbackResult.Refused(it) }
        }

        // 6 and 7. The engine, still an unbound interface, collected through the sanitizer. The
        //    two are one step because §6.1 requires the stripping to happen on the stream: there
        //    is no point between them where an unsanitized chunk exists as displayable text.
        //    A mid-stream engine failure discards what was already sanitized - a partial readback
        //    presented as an answer is the substitute output §9.2 forbids.
        val sanitizer = SlmStreamSanitizer()
        val visible = StringBuilder()
        try {
            engine.generate(prompt, MAX_OUTPUT_TOKENS).collect { chunk ->
                visible.append(sanitizer.accept(chunk))
            }
            visible.append(sanitizer.finish())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return SlmReadbackResult.Refused(engineRefusalFor(t))
        }
        val generated = visible.toString()

        // 7b. Model identity. The sanitizer's vocabulary and channel grammar belong to exactly one
        //     artifact, so a generation from a different one has passed through a gate calibrated
        //     for something else. Checked before the grounding gate because grounding cannot
        //     compensate: an unrecognized control construct is not a drug name and not a numeral,
        //     so it is "grounded" by construction and would be displayed. Tier-blind on purpose,
        //     for the same reason the sanitizer is: a foreign control grammar is not an answer for
        //     a physician either.
        if (!servedModelMatchesSanitizer(engine.servedModelId())) {
            return SlmReadbackResult.Refused(SlmRefusal.SERVED_MODEL_MISMATCH)
        }

        // 7c. Completeness. Checked here for the same reason as 7b and with more urgency: a
        //     truncated readback passes the sanitizer and passes the grounding gate by
        //     construction, because cutting a restatement short introduces no new drug and no new
        //     numeral. The envelope is the only place it is visible. Tier-blind: half a dosing
        //     instruction is not an answer for a physician either.
        if (engine.finishReason() !in COMPLETE_FINISH_REASONS) {
            return SlmReadbackResult.Refused(SlmRefusal.OUTPUT_TRUNCATED)
        }

        // 8. Output scope gate, over the sanitized text: it must judge what will be displayed,
        //    not what the model emitted. Whole or nothing; the text below is never rewritten.
        if (!openTier && !outputIsGrounded(generated, snapshot)) {
            return SlmReadbackResult.Refused(SlmRefusal.OUTPUT_NOT_GROUNDED)
        }

        // 9. Audit seam (§9.4, later stage): an invocation row, an input-refusal row, an
        //    output-suppression row and a sanitizer row, each with measured metadata only, and the
        //    backend enum mirror updated in the same commit. The sanitizer row's payload is
        //    already computed and carried on the answer below; only the recording is missing.

        return SlmReadbackResult.Answer(
            text = generated,
            promptTemplateVersion = PROMPT_TEMPLATE_VERSION,
            suppression = sanitizer.suppression,
        )
    }
}

/**
 * Prompt template version (§9.2). Bump it whenever the template text below changes, for the same
 * reason H-12 carries `derivation_rule_version`: an auditor must be able to tell a template change
 * from a model change.
 */
internal const val PROMPT_TEMPLATE_VERSION = "slm-readback-v1"

/**
 * Builds the prompt in the seam, never in the UI (§9.2). Pure function of the invocation: the same
 * snapshot and question always produce the same bytes, which is what makes the §7 single-turn test
 * meaningful and what makes a deterministic decode reproducible end to end.
 *
 * The instruction lines are for output quality only. §5.4 is explicit that they are not counted as
 * a control, because F4 measured this artifact ignoring exactly this kind of instruction. The
 * controls are [inputScopeRefusal] and [outputIsGrounded].
 */
internal fun buildPrompt(invocation: SlmInvocation): String {
    val snapshot = invocation.snapshot
    val medications = if (snapshot.medicationLines.isEmpty()) {
        "(no medicines are listed on this approved record)"
    } else {
        snapshot.medicationLines.joinToString("\n") { "- $it" }
    }

    return buildString {
        appendLine("You are restating a record a doctor has already approved. Use plain language.")
        appendLine("Do not add any medical information that is not written below.")
        appendLine()
        appendLine("APPROVED RECORD")
        appendLine("Doctor's decision on the assessment: ${snapshot.kernelDecision.name}")
        appendLine("Diagnosis: ${snapshot.diagnosis ?: "(not recorded)"}")
        appendLine("Medicines:")
        appendLine(medications)
        appendLine("Referral suggested: ${if (snapshot.suggestsReferral) "yes" else "no"}")
        appendLine()
        appendLine("QUESTION")
        append(invocation.question)
    }
}

/**
 * Tier resolution (§5.1). The tier source is the existing `UserRole.toCadreTier()`, which already
 * keys the H-18 document gate; no new tier concept is introduced.
 *
 * Only `CadreTier.PHYSICIAN` is open. `LICENSED_CLINICAL` sits with `COMMUNITY` in the worker tier
 * by §5.1's decision, for the reason the document gate already puts them together: two different
 * answers to "is a nurse trusted with interpretive clinical content" living in one app is worse
 * than either answer. **Fail-closed:** a null session (signed out, or a read that raced a
 * sign-out) is a worker, so there is no path where "no viewer" gets open querying.
 *
 * `internal` and derived from the live session, never a parameter: the failure this prevents is a
 * later stage passing "physician" for an ASHA, which is the same construction guarantee
 * `UserSession?.toReportAudience()` gives the audience.
 *
 * §5.1's CHO flag applies here and is louder than it was for documents: adding `UserRole.CHO` to
 * `toCadreTier` as `PHYSICIAN` would silently move CHO into the open SLM tier, and that needs its
 * own operator decision naming the SLM tier explicitly.
 */
internal fun UserSession?.isOpenSlmTier(): Boolean = this?.role?.toCadreTier() == CadreTier.PHYSICIAN

/**
 * Finish reasons that mean the generation actually finished.
 *
 * `STOP_SEQUENCE` counts as complete because a configured stop sequence ending the generation is
 * the contract working as designed (§2.2 requires explicit stop sequences), not an interruption.
 * Null is deliberately absent from this set, so an envelope carrying no finish reason refuses:
 * see [SlmRefusal.OUTPUT_TRUNCATED].
 */
internal val COMPLETE_FINISH_REASONS: Set<SlmFinishReason?> =
    setOf(SlmFinishReason.STOP, SlmFinishReason.STOP_SEQUENCE)

/**
 * Maps a thrown generation failure to its refusal (PR-2).
 *
 * **This function is what replaced a blanket catch, and the replacement is the point.** The perf
 * audit's F6B-02 traced a duplicate-ABHA data-integrity error through six defensible hops into one
 * `catch (e: Exception)` that turned it into "Assessment unavailable", and named that single catch
 * block as the finding. The SLM path had the same shape from the day it was written, with one
 * `ENGINE_FAILED` covering everything, and on a remote engine that would have merged the single
 * most common condition in the field (no network) with the rarest and most serious (the service
 * broke).
 *
 * **An unclassifiable throwable still refuses.** The `else` branch is [SlmRefusal.ENGINE_FAILED],
 * never an answer, so a binding that throws something untyped degrades the quality of the refusal
 * and never its existence. Fail-closed on the taxonomy, not just on the outcome.
 *
 * [CancellationException] never reaches here: the seam rethrows it before calling this, because a
 * cancelled readback has no result at all, not a refusal.
 */
internal fun engineRefusalFor(t: Throwable): SlmRefusal = when ((t as? SlmEngineException)?.error) {
    SlmEngineError.UNREACHABLE -> SlmRefusal.ENGINE_UNREACHABLE
    SlmEngineError.TIMEOUT -> SlmRefusal.ENGINE_TIMEOUT
    SlmEngineError.UNAVAILABLE -> SlmRefusal.ENGINE_UNAVAILABLE
    SlmEngineError.PAYLOAD_REJECTED -> SlmRefusal.ENGINE_REJECTED_INPUT
    SlmEngineError.ENGINE_ERROR -> SlmRefusal.ENGINE_FAILED
    SlmEngineError.MALFORMED_RESPONSE -> SlmRefusal.ENGINE_FAILED
    null -> SlmRefusal.ENGINE_FAILED
}
