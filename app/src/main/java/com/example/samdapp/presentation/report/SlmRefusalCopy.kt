package com.example.samdapp.presentation.report

import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.slm.SlmRefusal

/**
 * What a PHC worker should be offered to do next after a refusal.
 *
 * **The whole reason this is a type rather than a boolean.** S-4's rule is that "Retry" appears
 * only where pressing again can actually work, and on this path there are two different ways a
 * button can be useless. One is the ordinary one: the failure is permanent until a person changes
 * something, so nothing to press. The other is specific to this feature and is easy to get wrong:
 * **the decode is deterministic.** `slm-service-contract.md` §2.1 fixes `temperature` at 0 and
 * `do_sample` at false, and the binding sends a constant seed, so re-running the same question
 * produces the same answer byte for byte. Every refusal caused by the *content* or *length* of the
 * answer therefore cannot be cleared by pressing "Try again"; it can only be cleared by asking
 * something else. A button that provably cannot work teaches the wrong remedy, and a worker who
 * presses it three times with a patient waiting learns not to believe the screen.
 */
enum class SlmRetryOffer {
    /**
     * Back to the question box, cleared. The question, or what the record can answer about it, is
     * what has to change. Not a follow-up: the previous answer and the previous question are both
     * gone, so there is one turn on screen at any time and no transcript.
     */
    ASK_AGAIN,

    /**
     * Re-run the same question now. Only for transport failures that are genuinely transient and
     * where the generation never completed, so determinism does not apply.
     */
    RETRY_NOW,

    /** Nothing to press. The remedy is elsewhere, or there is none, and the copy says which. */
    NONE,
}

/**
 * The one place an [SlmRefusal] becomes something a PHC worker reads.
 *
 * Kept out of the domain layer for [com.example.samdapp.presentation.kernelassessment.kernelFailureCopy]'s
 * reason, unchanged: [SlmRefusal] is pure Kotlin with no `androidx` import, and a domain enum
 * carrying `@StringRes` ids would drag the resource system into the layer that is meant to be
 * testable without one. Resource ids, not strings, so the copy is translatable and so a test can
 * assert WHICH message a refusal selects without asserting the English in it.
 *
 * **Nineteen refusals, nineteen states, and that is the requirement rather than thoroughness for
 * its own sake.** Guardrail memo §5.6 makes every refusal a first-class UI state, and H-14 is in
 * the hazard register because an absence that carries no signal is its own defect: a worker who
 * taps and gets a blank sheet, or a toast that has already gone, learns that the feature is
 * unreliable and stops distinguishing the case where it refused on purpose from the case where it
 * broke. On this path that distinction is most of the feature, because it refuses more often than
 * it answers by design.
 *
 * Third feature on the `strings.xml` side of the convention fork, after S-4's `kernel_failure_*`
 * and S-3's `failed_sync_*`. Everything else in this app still uses inline constants. Recorded
 * again rather than resolved: the fork is now three to the rest and is still nobody's decision.
 */
data class SlmRefusalCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val retry: SlmRetryOffer,
)

/**
 * Total over [SlmRefusal] with no `else`, so a new refusal value is a compile error here rather
 * than a value that silently falls through to a generic message. That is the same construction
 * `engineRefusalFor` uses on the domain side and it is deliberate in both places: the generic
 * bucket is what this whole taxonomy exists to avoid.
 */
fun slmRefusalCopy(refusal: SlmRefusal): SlmRefusalCopy = when (refusal) {

    // ---- the question ---------------------------------------------------------------

    SlmRefusal.EMPTY_QUESTION -> SlmRefusalCopy(
        R.string.readback_refusal_empty_question_title,
        R.string.readback_refusal_empty_question_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    SlmRefusal.QUESTION_TOO_LONG -> SlmRefusalCopy(
        R.string.readback_refusal_question_too_long_title,
        R.string.readback_refusal_question_too_long_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    // ---- the record -----------------------------------------------------------------

    // Refused rather than trimmed: a record cut inside the prompt is one the model answers about
    // incompletely with no signal that it did. Nothing about the question would fix it, so the
    // remedy is the report itself, which is complete.
    SlmRefusal.RECORD_TOO_LARGE -> SlmRefusalCopy(
        R.string.readback_refusal_record_too_large_title,
        R.string.readback_refusal_record_too_large_body,
        SlmRetryOffer.NONE,
    )

    /*
     * The injection guard, PR-8.
     *
     * NONE, not ASK_AGAIN, and the reason is that the device cannot tell the worker which half of
     * the prompt carried the token. The question is theirs to change; the approved record is not,
     * and if the diagnosis is what carries it then "ask something else" is advice that cannot
     * work. A refusal that names both possibilities and offers no button is the honest shape.
     *
     * Never the code, per S-4. "SAMD-SLM-8009" tells a health worker nothing and reads as a fault
     * in the app rather than as a control that fired.
     */
    SlmRefusal.PROMPT_CONTROL_TOKENS -> SlmRefusalCopy(
        R.string.readback_refusal_prompt_control_tokens_title,
        R.string.readback_refusal_prompt_control_tokens_body,
        SlmRetryOffer.NONE,
    )

    // The gate the entire feature rests on. Not an error, and must not read as one: a doctor has
    // simply not decided yet, which is the ordinary state of a case for most of its life.
    SlmRefusal.NOT_APPROVED -> SlmRefusalCopy(
        R.string.readback_refusal_not_approved_title,
        R.string.readback_refusal_not_approved_body,
        SlmRetryOffer.NONE,
    )

    SlmRefusal.CASE_UNRESOLVABLE -> SlmRefusalCopy(
        R.string.readback_refusal_case_unresolvable_title,
        R.string.readback_refusal_case_unresolvable_body,
        SlmRetryOffer.NONE,
    )

    // An app defect, and named as one. Never blamed on the network or on the record.
    SlmRefusal.ASSEMBLY_FAILED -> SlmRefusalCopy(
        R.string.readback_refusal_assembly_failed_title,
        R.string.readback_refusal_assembly_failed_body,
        SlmRetryOffer.NONE,
    )

    // ---- the input scope gate, WORKER tier ------------------------------------------

    SlmRefusal.OUT_OF_SCOPE_PHRASING -> SlmRefusalCopy(
        R.string.readback_refusal_out_of_scope_phrasing_title,
        R.string.readback_refusal_out_of_scope_phrasing_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    SlmRefusal.OUT_OF_SCOPE_INTENT -> SlmRefusalCopy(
        R.string.readback_refusal_out_of_scope_intent_title,
        R.string.readback_refusal_out_of_scope_intent_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    SlmRefusal.OUT_OF_SCOPE_UNTETHERED -> SlmRefusalCopy(
        R.string.readback_refusal_out_of_scope_untethered_title,
        R.string.readback_refusal_out_of_scope_untethered_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    // ---- the output grounding gate, WORKER tier -------------------------------------

    /*
     * The gate working, not a fault, and the hardest of the nineteen to word. Three things it must
     * not do: imply the patient's record is wrong (it is not, and it is the thing the worker is
     * being sent back to), imply the app broke (it did not, this is the control firing), or hint
     * that a real answer exists behind the refusal (the generation is suppressed whole, never
     * edited into compliance).
     *
     * ASK_AGAIN and never RETRY_NOW. The generation completed and its content is what failed the
     * check, so under a deterministic decode the identical question produces the identical
     * ungrounded text and the identical refusal, forever.
     */
    SlmRefusal.OUTPUT_NOT_GROUNDED -> SlmRefusalCopy(
        R.string.readback_refusal_output_not_grounded_title,
        R.string.readback_refusal_output_not_grounded_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    // ---- transport --------------------------------------------------------------------

    // The expected daily condition of a rural PHC, said plainly, with the report offered as the
    // thing that still works. No button: nothing about pressing it changes whether there is signal.
    SlmRefusal.ENGINE_UNREACHABLE -> SlmRefusalCopy(
        R.string.readback_refusal_engine_unreachable_title,
        R.string.readback_refusal_engine_unreachable_body,
        SlmRetryOffer.NONE,
    )

    /*
     * The one refusal whose copy tells the worker not to try again in those words, and the reason
     * PR-6 added the class at all. A TLS failure can be a captive portal, a clock skew, or an
     * interception. Under the last of those, "try again" means sending a physician's free-text
     * diagnosis and a full prescription line set once more to whoever is reading them. Escalation,
     * never retry, and never folded back into the offline bucket that would carry the opposite
     * advice.
     */
    SlmRefusal.SECURE_CONNECTION_FAILED -> SlmRefusalCopy(
        R.string.readback_refusal_secure_connection_failed_title,
        R.string.readback_refusal_secure_connection_failed_body,
        SlmRetryOffer.NONE,
    )

    // Connected, no answer inside the budget. Nothing was generated, so determinism does not
    // apply and a second attempt is a genuinely different roll of load and latency.
    SlmRefusal.ENGINE_TIMEOUT -> SlmRefusalCopy(
        R.string.readback_refusal_engine_timeout_title,
        R.string.readback_refusal_engine_timeout_body,
        SlmRetryOffer.RETRY_NOW,
    )

    /*
     * Four states, one worker action: the read-back is not configured for this deployment, the
     * backend's circuit is open, the service is loaded and saturated, or a read-back is already
     * running on this phone and the binding's mutex refused a second. All four mean "wait, then
     * try", which is exactly what a button invites someone to skip.
     */
    SlmRefusal.ENGINE_UNAVAILABLE -> SlmRefusalCopy(
        R.string.readback_refusal_engine_unavailable_title,
        R.string.readback_refusal_engine_unavailable_body,
        SlmRetryOffer.NONE,
    )

    // Not retryable unchanged. A build emitting these is misconfigured and somebody needs to see
    // that, which is the whole point of typing it separately from a generic failure.
    SlmRefusal.ENGINE_REJECTED_INPUT -> SlmRefusalCopy(
        R.string.readback_refusal_engine_rejected_input_title,
        R.string.readback_refusal_engine_rejected_input_body,
        SlmRetryOffer.NONE,
    )

    // The catch-all. One press, then the report. Retryable because the cause is unknown to the
    // device, which means it may equally have been transient.
    SlmRefusal.ENGINE_FAILED -> SlmRefusalCopy(
        R.string.readback_refusal_engine_failed_title,
        R.string.readback_refusal_engine_failed_body,
        SlmRetryOffer.RETRY_NOW,
    )

    /*
     * The answer hit the output ceiling and was withheld whole. It must not read as a system
     * error, because nothing failed: the service answered and honestly reported that the answer
     * was incomplete, which is the one signal in the entire system that can catch this.
     *
     * **ASK_AGAIN, deliberately against this value's own KDoc**, which says "Retryable: a second
     * generation may fit". That sentence was written in PR-2, before the decode parameters were
     * settled and before any transport existed. Under greedy decoding at temperature 0 with a
     * constant seed, the same question produces the same cut-off answer every time, so "Try again"
     * would be a button that is provably incapable of working. A shorter question is the only
     * action that can change the outcome. The KDoc is corrected in place; this comment records
     * that the correction was made here first.
     *
     * There is no partial text to offer and the copy must not imply there is: the seam returns
     * `Refused`, which carries a reason and nothing else, so the generated string never leaves the
     * use case on this path.
     */
    SlmRefusal.OUTPUT_TRUNCATED -> SlmRefusalCopy(
        R.string.readback_refusal_output_truncated_title,
        R.string.readback_refusal_output_truncated_body,
        SlmRetryOffer.ASK_AGAIN,
    )

    /*
     * A safety stop. The server served an artifact this build was not verified against, or would
     * not say which it served, which means the sanitizer's vocabulary and channel grammar were
     * calibrated for a different model and every suppression counter reading zero proves nothing.
     * Not the worker's doing and not fixable from the handset, so no button and an escalation.
     */
    SlmRefusal.SERVED_MODEL_MISMATCH -> SlmRefusalCopy(
        R.string.readback_refusal_served_model_mismatch_title,
        R.string.readback_refusal_served_model_mismatch_body,
        SlmRetryOffer.NONE,
    )
}
