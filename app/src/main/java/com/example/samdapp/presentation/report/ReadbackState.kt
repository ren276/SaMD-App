package com.example.samdapp.presentation.report

import androidx.compose.runtime.Immutable
import com.example.samdapp.domain.slm.SlmRefusal

/**
 * What the read-back sheet is showing. Four states, mutually exclusive by construction.
 *
 * **The single-turn rule is enforced by this type's shape, not by the composable's discipline.**
 * `scratchpad/slm-guardrail-service-contract-memo.md` §7 enforces single turn at the seam by there
 * being no history field a transcript could arrive on, and the contract enforces it again on the
 * wire the same way. This is the third instance of the same construction, at the layer where a
 * transcript would actually be built: there is **one** question in [ReportUiState] and **one**
 * answer here, they are different states of the same slot, and there is no list of anything. A
 * conversation view is not discouraged here, it is unrepresentable, because there is nothing to
 * append a turn to.
 *
 * It follows that the question input and an answer can never be on screen together. [Idle] is the
 * only state that carries an input, and reaching it from [Answer] or [Refused] clears the previous
 * question and the previous answer. That is what "ask something else" means and it is why the
 * action is not called "ask a follow-up": nothing from the previous turn survives into the next
 * one, on screen or on the wire.
 */
@Immutable
sealed interface ReadbackState {

    /** The question box. The only state with an input. */
    data object Idle : ReadbackState

    /**
     * The request is out and nothing has come back.
     *
     * **Entered before the call, not on the first byte, and that is the point of it being a state
     * rather than a spinner the transport toggles.** There is no token trickle to wait for: the
     * output grounding gate withholds the entire generation until it completes, so the first thing
     * that ever arrives is the whole answer or a refusal. PR-5 measured p50 at 4.36 s and a
     * full-length 512-token answer at 10.8 s, so a worker with a patient in front of them waits
     * seconds looking at this and nothing else. A state that appeared on the first byte would be a
     * state that never appeared at all.
     */
    data object Generating : ReadbackState

    /**
     * [text] is exactly what the seam returned, unedited. §6.2 forbids meaning-level filtering, so
     * the surface either shows the whole generation or shows none of it, and there is no branch
     * here that trims, summarises or highlights.
     */
    @Immutable
    data class Answer(val text: String) : ReadbackState

    /**
     * A refusal, carrying a reason and **no text**.
     *
     * That is not a simplification of the seam, it is the seam: `SlmReadbackResult.Refused` holds
     * a reason and nothing else, so there is no partial or suppressed generation for this state to
     * accidentally render. The property "a refusal never displays partial generated text" is
     * therefore structural at three layers rather than a rule the UI has to keep.
     */
    @Immutable
    data class Refused(val reason: SlmRefusal) : ReadbackState
}

/**
 * Whether the question input is on screen. Pure, so the single-turn property is assertable without
 * Compose and without a device.
 *
 * True for exactly one state. A change that let an input render beside an answer is what a
 * conversation view looks like on the way in, and it fails here first.
 */
fun ReadbackState.showsQuestionInput(): Boolean = this is ReadbackState.Idle

/** Whether the worker is waiting on a generation, so the sheet can offer Stop and nothing else. */
fun ReadbackState.isGenerating(): Boolean = this is ReadbackState.Generating

/**
 * Whether the read-back entry point exists on the report screen at all.
 *
 * Pure and parameterised rather than reading [com.example.samdapp.config.FeatureFlags] inline, so
 * **both sides of the flag are testable**. `SLM_READBACK_ENABLED` is a `const val false`, so a test
 * that only exercised the shipped value would prove "the button is hidden" without ever proving
 * that the flag is what hides it: every assertion would pass just as well if the gate were
 * `false &&` anything, or if the approval condition were the only thing doing the work. Splitting
 * the two inputs is what makes "flag off makes the entry point unreachable" a proof rather than a
 * restatement, which is what this PR was asked for.
 *
 * Both conditions are required and neither is redundant. The flag is the deployment decision. The
 * physician decision is the feature's own precondition and is the same `kernelDecision != null`
 * that `ApprovedRecordReader` requires before it will build a snapshot; checking it here is the
 * difference between a control that is not offered and one that refuses when pressed. The seam
 * still refuses independently, and `NOT_APPROVED` still has its own copy, for the case where a
 * decision is withdrawn between render and tap.
 */
internal fun canOpenReadback(flagEnabled: Boolean, hasPhysicianDecision: Boolean): Boolean =
    flagEnabled && hasPhysicianDecision

/**
 * Whether the Ask button can fire, judged on the question alone.
 *
 * Deliberately **not** including the flag, even though the action it drives does. Folding the flag
 * in here would make every assertion about question validity pass for the wrong reason while the
 * flag is off, which is the vacuous-check shape this project keeps finding: the test would report
 * "a blank question cannot be asked" when what it had actually proved is "nothing can be asked".
 * The two conditions are separate questions and are answered separately.
 *
 * The blank and over-length checks duplicate two of the seam's own refusals on purpose. They are
 * the cheaper of two independent gates, not the only one: this one costs no round trip, and
 * `EMPTY_QUESTION` and `QUESTION_TOO_LONG` still have their own copy for the case where the seam
 * is reached anyway.
 */
internal fun canAskReadback(question: String, state: ReadbackState, maxQuestionChars: Int): Boolean =
    question.isNotBlank() && question.length <= maxQuestionChars && state is ReadbackState.Idle
