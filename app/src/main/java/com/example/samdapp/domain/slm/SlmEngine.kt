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
 * **Bound since PR-6, to a remote binding, in every flavor.** The implementation is
 * `com.example.samdapp.data.remote.RemoteSlmEngine`, which calls
 * `POST /api/v1/slm/readback` on `backend/core`. There is no on-device engine and there is no mock
 * or stub engine in any source set, which is deliberate and is the opposite of §9.3's
 * `DevClinicalMockModule` posture: that posture exists to keep fabricated **clinical values** out
 * of a non-dev build, and a stub engine would be exactly that, a plausible readback with no model
 * behind it, reachable wherever the stub was bound. H-09 and H-13 are both in the register because
 * a plausible fabricated value reachable in a non-dev build is this project's recurring failure
 * mode. One binding for all three flavors means there is no build in which a different thing
 * answers, and nothing is reachable at all until PR-7 adds the flag and the surface.
 *
 * [SlmReadbackUseCase] is still written against the interface, and every gate around it is still
 * tested with a recording fake that counts calls.
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
 * memo names is [SlmReadbackResult], produced by the seam.
 *
 * **Failures are typed, since PR-2.** A failure still arrives as an exception from the flow, but it
 * must be an [SlmEngineException] carrying an [SlmEngineError], because the seam has to tell the
 * classes apart: on an offline-first device for rural PHCs, unreachable is the normal case and not
 * an error case, and collapsing it into a generic engine failure is the defect the perf audit
 * recorded as F6B-02 hop 7, where one blanket `catch (e: Exception)` turned a specific, recoverable
 * data-integrity error into a misleading infrastructure error. An implementation that throws an
 * untyped `Throwable` is still safe, because the seam refuses on anything it cannot classify, but it
 * gives the worker the least useful of the available answers. §9.2's rule is unchanged underneath
 * all of this: a failure is shown as a failure, and there is no substitute output, ever.
 */
/**
 * Why a generation failed, as the seam needs to tell them apart.
 *
 * **Named after `KernelCallOutcome` rather than invented.** That backend enum
 * (`backend/core/app/models/enums.py`) already answers this exact question for the classifier hop,
 * and its own docstring argues for the granularity: folding unreachable and erroring into one
 * bucket "would throw that distinction away at the one place it is cheap to keep". One vocabulary
 * across both model hops means an operator reading a `kernel_call_log` row and an SLM row is
 * reading the same words, and it is the reason this enum has no value that enum does not.
 *
 * There is no `SUCCESS`: this type exists only on the failure path. There is no `PHI_REJECTED`
 * either, and since PR-4 that is a decision rather than a gap: the outbound PHI guard lives in
 * `backend/core`, it trips before any call leaves that backend, and it reaches the device as a
 * `422` carrying `SAMD-SLM-8014`. The device classifies it as [PAYLOAD_REJECTED], which is the
 * honest answer from here: the request was refused before generation and will be refused again
 * unchanged. A separate value would name a server-side control the device cannot act on
 * differently.
 */
enum class SlmEngineError {

    /** No route, DNS failure, connection refused. **The normal case on an offline-first device.** */
    UNREACHABLE,

    /**
     * A TLS failure: `SSLException` and everything under it, including a handshake that failed and
     * a peer that could not be verified.
     *
     * **Added in PR-6, and it is the value the taxonomy was missing.** `SSLException` extends
     * `IOException`, so any classification that tests for `IOException` before testing for this
     * lands a TLS failure in [UNREACHABLE], which on this device means "you are offline, it will
     * work when you have signal". That answer tells a worker to keep pressing a button that keeps
     * pushing a physician's free-text diagnosis and a full prescription line set at a connection
     * that may be a captive portal, a clock skew, or an interception. The last of those is a
     * reason to stop sending patient data, not a reason to retry.
     *
     * This is not a new argument on this project. `KernelFailure.SECURE_CONNECTION_FAILED` exists
     * for exactly this, carries `KernelRetryAdvice.NEEDS_ACTION` rather than a retry advice, and
     * `classifyKernelFailure` orders its `SSLException` branch above the catch-all for the same
     * reason. This path is carrying strictly more sensitive bytes than that one and had no such
     * value; PR-2 built the vocabulary before a transport existed to produce the failure.
     */
    SECURE_CONNECTION_FAILED,

    /** The service was reached and did not finish in time: connect succeeded, the budget expired. */
    TIMEOUT,

    /** The service answered that it is not serving: circuit open, or the model is not loaded. */
    UNAVAILABLE,

    /** A 4xx. The request itself was refused: over the token limit, unknown prompt-template
     *  version, or a `model_id` the service declines to serve. Not retryable unchanged. */
    PAYLOAD_REJECTED,

    /** A 5xx, an out-of-memory inside generation, or any failure the service attributes to itself. */
    ENGINE_ERROR,

    /** The response arrived and could not be parsed into the §2.2 envelope. */
    MALFORMED_RESPONSE,
}

/**
 * A typed generation failure. Thrown from the flow returned by [SlmEngine.generate].
 *
 * The seam classifies on [error] alone and never on [message], which exists for logging. A message
 * from a remote service is untrusted text and may carry anything the service put in it, so nothing
 * on the refusal path reads it and no audit payload carries it (§9.4).
 */
class SlmEngineException(
    val error: SlmEngineError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * How the service says the generation ended, from the `finish_reason` of the §2.2 response
 * envelope. **Required to be honest**: the memo makes this non-negotiable because the sample
 * serving app returns the literal string `"stop"` unconditionally, so a completion cut off at
 * `max_new_tokens` is indistinguishable from a complete one by any consumer.
 */
enum class SlmFinishReason {

    /** The model emitted an end-of-sequence token. The generation is complete. */
    STOP,

    /** The generation hit the output-token ceiling. **The answer is cut off.** */
    LENGTH,

    /** The generation hit a configured stop sequence. Complete, by the contract's own definition. */
    STOP_SEQUENCE,

    /** The service abandoned the generation part way through and says so. */
    ERROR,
}

interface SlmEngine {

    /**
     * @param caseRecordId the case the prompt was assembled from.
     *
     *   **Added in PR-6, and it is a transport requirement rather than a widening of what the
     *   engine knows.** The device does not reach the generation service; it reaches
     *   `POST /api/v1/slm/readback`, which resolves the case, scopes it to the caller's facility,
     *   and writes an `slm_call_log` row and an audit row that name it. That endpoint's request
     *   model requires the field, so a binding without it produces a `422` on every call.
     *
     *   It goes no further than the backend. The generation service's own request schema has no
     *   identifier field and rejects unknown fields, so the id is **absent** from that wire rather
     *   than pseudonymised on it, which is the stronger property and the one the backend's PHI
     *   guard enforces rather than trusts.
     *
     * @param prompt built by the seam from an [ApprovedRecordSnapshot] and one bounded question.
     *   Prompt construction never happens in the UI (§9.2).
     * @param maxOutputTokens hard bound, enforced by the engine. §9.2 also requires a wall-clock
     *   timeout and cancellability; both belong to the binding, not to this signature.
     * @return a cold flow: collecting it starts a generation. Nothing is generated until it is
     *   collected, which is what §8's "nothing generates speculatively" requires of this signature.
     *
     *   **Cancellation, corrected in PR-2.** This KDoc previously said that cancelling the
     *   collection cancels the generation, and §8 says cancellation "does not merely hide the
     *   surface while the CPU keeps burning". That promise was written for an on-device engine,
     *   where the collector and the compute share a process, and **a remote binding cannot keep
     *   it.** Cancelling an OkHttp call closes a socket; the generation on the GPU runs on to
     *   `max_new_tokens`, holding VRAM, and on a single-worker service it blocks the next request.
     *   What cancellation guarantees here is narrower and worth stating exactly: no further chunk
     *   is delivered, nothing more is displayed, and the seam produces no result. It does not
     *   guarantee that the work stopped.
     *
     *   Making it true again is the **service's** obligation, not this interface's: the service
     *   detects client disconnect and abandons its own generation. That lands with the service
     *   (PR-5). Until it does, a cancelled readback costs a full generation's GPU time and an
     *   immediate retry has two of them resident.
     */
    fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String>

    /**
     * The artifact identity the server reported for the generation just collected, or null when the
     * response carried none.
     *
     * **Read after the flow completes, never before.** The identity is a field of the *response*
     * envelope (`scratchpad/slm-remote-inference-memo.md` §2.2, `model_id`, required to be derived
     * from the loaded artifact rather than written as a literal), so it does not exist until the
     * server has answered. Under §4.1's non-streaming transport a binding performs one call, holds
     * the envelope, and emits its body as a single chunk, so the value is available the moment
     * collection ends. There is no second round trip.
     *
     * **Null is a refusal, not a default.** The seam treats a null or blank identity as a mismatch
     * ([servedModelMatchesSanitizer]). An implementation must therefore return what the envelope
     * actually carried and must never substitute [SANITIZER_TARGET_MODEL_ID], a configured value,
     * or the id that was requested: the whole purpose of the comparison is to catch the case where
     * the server is serving something other than what this build was verified against, and a
     * binding that answers with its own expectation would report agreement with itself.
     *
     * Per-generation state, like the rest of this interface: one engine use per invocation, and the
     * value is undefined before the first collection.
     *
     * **What "one engine use per invocation" means once the engine is a singleton (PR-6).** The
     * binding is `@Singleton`, so this state is shared, and the thing that makes reading it after
     * the flow safe is the binding's own mutex: a second generation cannot start while one is in
     * flight, so it cannot overwrite the envelope the seam is about to read. The residual window,
     * between the flow completing and the seam reading these two, is closed in the safe direction
     * rather than left open: a generation clears the state before it calls, so a caller that loses
     * the race reads `null` and refuses, instead of reading someone else's answer.
     */
    fun servedModelId(): String?

    /**
     * How the service said the generation ended, from the envelope's `finish_reason`, or null when
     * the response carried none. Read after the flow completes, like [servedModelId].
     *
     * **Null is a refusal, not a default**, for the same reason and with more force: the sample
     * serving app hardcodes `"stop"`, so "the field is missing" and "the field is untrustworthy"
     * are the same state from here. An implementation must return what the envelope carried and
     * must never synthesise [SlmFinishReason.STOP] because the HTTP status was 200. A truncated
     * generation returns 200.
     */
    fun finishReason(): SlmFinishReason?
}
