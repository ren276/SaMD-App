package com.example.samdapp.domain.slm

import kotlinx.coroutines.flow.Flow

/**
 * The on-device generation engine, as the guardrail seam sees it
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §9.1/§9.2).
 *
 * **Deliberately thin, and deliberately ignorant of clinical scope.** It takes a prompt the seam
 * built and returns what the model produced. It performs no gating of its own, because every
 * control in the memo lives outside the model (harness finding F4: this artifact refuses nothing
 * on its own, so a control expressed as a system prompt is not a control).
 *
 * **Unbound at this stage, on purpose.** Nothing implements this interface and no Hilt module
 * binds it. Stage 3 binds the LiteRT-LM implementation in the data layer, per §9.3's
 * `DevClinicalMockModule` posture (dev-flavor stub only, staging/prod bind an honest unavailable
 * implementation). Until then [SlmReadbackUseCase] is written against the interface and every
 * gate around it is tested with a recording fake that counts calls.
 *
 * **It must be impossible to reach from a ViewModel** (§9.1). There is exactly one legitimate
 * consumer, [SlmReadbackUseCase], and
 * `com.example.samdapp.config.SlmEngineIsUnreachableFromPresentationTest` fails the build if this
 * type is named anywhere under `presentation/`. Same architectural argument
 * `com.example.samdapp.domain.document.DocumentAccessAuthorizer`'s KDoc makes for having exactly
 * one caller: a check duplicated across a ViewModel and a composable is a check that will diverge.
 *
 * Decoding is greedy and deterministic by contract (§9.2, temperature 0, no sampling): two
 * readbacks of the same approved record must produce the same text, or the output cannot be
 * reviewed, reproduced in an incident investigation, or regression-tested. The parameter set here
 * carries no sampling knob because there is nothing to tune.
 *
 * **Streaming, since stage 3a.** §8 requires tokens to be surfaced as they are produced and
 * §6.1 requires the thought-channel stripper to run on the stream rather than on a finished
 * string, so [generate] returns a cold flow of chunks. Chunk boundaries are the engine's business
 * and are explicitly not assumed to fall on token or word boundaries: [SlmStreamSanitizer] holds
 * back a window precisely because a control token may straddle two chunks. The terminal result the
 * memo names is [SlmReadbackResult], produced by the seam; a failure arrives here as an exception
 * from the flow and the seam turns it into [SlmRefusal.ENGINE_FAILED], because §9.2 requires that
 * a failure is shown as a failure with no substitute output.
 */
interface SlmEngine {

    /**
     * @param prompt built by the seam from an [ApprovedRecordSnapshot] and one bounded question.
     *   Prompt construction never happens in the UI (§9.2).
     * @param maxOutputTokens hard bound, enforced by the engine. §9.2 also requires a wall-clock
     *   timeout and cancellability; both belong to the binding, not to this signature.
     * @return a cold flow: collecting it starts a generation, and cancelling the collection
     *   cancels that generation (§8, cancellation is real and releases the engine). Nothing is
     *   generated until it is collected, which is what §8's "nothing generates speculatively"
     *   requires of this signature.
     */
    fun generate(prompt: String, maxOutputTokens: Int): Flow<String>
}
