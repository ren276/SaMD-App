package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.toCadreTier
import kotlinx.coroutines.CancellationException
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

    /** The engine failed. A failure is shown as a failure; there is no substitute output (§9.2). */
    ENGINE_FAILED,
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
     */
    data class Answer(val text: String, val promptTemplateVersion: String) : SlmReadbackResult

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
 * 6. [SlmEngine.generate] - *unbound interface at this stage*; stage 3 binds it.
 * 7. Stream sanitizer (§6.1) - *later stage*, and it must run before anything is displayed.
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
 * single-turn and the no-editing rule exist at this stage; both apply to every tier here. The
 * sanitizer and audit arrive with their stages.
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

        // 6. The engine. Unbound at this stage: no implementation and no Hilt binding exists.
        val generated = try {
            engine.generate(prompt, MAX_OUTPUT_TOKENS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return SlmReadbackResult.Refused(SlmRefusal.ENGINE_FAILED)
        }

        // 7. Stream sanitizer seam (§6.1, later stage). Nothing sits here yet, and the gap is
        //    real: the harness saw <unused94>thought spans leak into visible output (F6), so no
        //    generation may be displayed until this slot is filled.

        // 8. Output scope gate. Whole or nothing; the text below is never rewritten.
        if (!openTier && !outputIsGrounded(generated, snapshot)) {
            return SlmReadbackResult.Refused(SlmRefusal.OUTPUT_NOT_GROUNDED)
        }

        // 9. Audit seam (§9.4, later stage): an invocation row, an input-refusal row, an
        //    output-suppression row and a sanitizer row, each with measured metadata only, and the
        //    backend enum mirror updated in the same commit.

        return SlmReadbackResult.Answer(text = generated, promptTemplateVersion = PROMPT_TEMPLATE_VERSION)
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
