package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.SlmApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ProblemDetailDto
import com.example.samdapp.data.remote.dto.SlmReadbackRequestDto
import com.example.samdapp.data.remote.dto.SlmReadbackResponseDto
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import com.example.samdapp.domain.slm.PROMPT_TEMPLATE_VERSION
import com.example.samdapp.domain.slm.SANITIZER_TARGET_MODEL_ID
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * The seed sent on every readback request.
 *
 * **Fixed, and never random.** Under `temperature: 0` and `do_sample: false` the decode is greedy
 * and the seed changes nothing about the output, so this value is not a tuning knob; it is a claim
 * the response is checked against. The envelope echoes the decode parameters actually used
 * (`slm-service-contract.md` §3.3), so a response reporting a seed this build did not send, or
 * omitting one, is evidence that the service is not decoding the way it says it is. A value drawn
 * per call would make that check meaningless and would, the day sampling is ever enabled, produce
 * clinical text that varies run to run with no reproducibility handle.
 *
 * The literal is the contract's own example value, kept so the two cannot drift apart silently.
 */
internal const val SLM_REQUEST_SEED = 20260918

/**
 * The device half of the readback path: `POST /api/v1/slm/readback` on `backend/core`
 * (api-contract.md §11.3), behind the [SlmEngine] interface the guardrail seam is written against.
 *
 * **It calls the backend, never the generation service.** Topology (A), permanently. The device
 * holds one backend credential, has one reachability model, and every crossing lands in the
 * existing hash-chained audit log with a circuit breaker in front of it. A device calling the
 * service directly would need a second credential distributed to every handset, a second
 * reachability story on a rural link, and an audit trail built from nothing on the serving side.
 *
 * **Non-streaming, one chunk.** [generate] performs one call, holds the envelope, and emits its
 * whole `text` as a single chunk. The interface stays a `Flow<String>` because the sanitizer runs
 * on the stream and because a future streaming transport should not be an interface change, and
 * [com.example.samdapp.domain.slm.SlmStreamSanitizer] handles a single chunk correctly by
 * construction: it holds back a window for a control token that might straddle a boundary, and
 * `finish()` flushes that window. Streaming would buy nothing today anyway, because the output
 * grounding gate withholds every token until the generation completes, so nothing reaches a screen
 * earlier; it would cost a streaming timeout policy on a stack that has none.
 *
 * **Everything here is classification, and none of it is judgement.** This class decides which
 * [SlmEngineError] a failure is and nothing else. It does not retry, does not substitute, does not
 * shorten, and never returns text on a path that failed. A retried generation is a second
 * generation, which on a single-worker service queues behind the first one and shows one clinical
 * event as two rows.
 */
@Singleton
class RemoteSlmEngine @Inject constructor(
    private val api: SlmApiService,
) : SlmEngine {

    private val gson = Gson()

    /**
     * One generation at a time, device side.
     *
     * **[Mutex.tryLock], never `withLock`.** A mutex that queues would turn a second tap into a
     * second generation that runs a minute later, which is the duplicate this exists to prevent:
     * the worker sees nothing happen, taps again, and pays for two generations on a single-worker
     * service while the second answer arrives for a screen nobody is looking at any more. Refusing
     * immediately is the honest answer, and [SlmEngineError.UNAVAILABLE] is the right class for it
     * because the worker action is the one that value already means, wait and retry, not
     * immediately.
     *
     * **It also closes a data race, and that is the part that is a safety property rather than a
     * politeness.** [servedModelId] and [finishReason] are per-generation state on a `@Singleton`,
     * read by the seam after the flow completes. Two concurrent generations would let the seam
     * check generation A's text against generation B's `finish_reason`, and the dangerous direction
     * of that is real: A truncated, B complete, A reads `stop` and half a dosing instruction is
     * displayed as a whole one. That is precisely the failure
     * [com.example.samdapp.domain.slm.SlmRefusal.OUTPUT_TRUNCATED] exists to catch and the only
     * place in the system it is catchable.
     *
     * The residual window, between the flow completing and the seam's two reads, is closed in the
     * safe direction rather than argued away: [generate] clears [lastResponse] before it calls, so
     * a caller that loses the race reads `null`, and `null` is a refusal on both gates.
     */
    private val inFlight = Mutex()

    /**
     * The envelope of the generation that is running or that ran last. `@Volatile` because it is
     * written on the coroutine that generated and read by whichever thread the seam resumed on.
     */
    @Volatile
    private var lastResponse: SlmReadbackResponseDto? = null

    override fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String> =
        flow {
            // Cold: nothing above this line runs until the flow is collected, which is what
            // "nothing generates speculatively" requires of this signature.
            if (!inFlight.tryLock()) {
                throw SlmEngineException(
                    SlmEngineError.UNAVAILABLE,
                    "A readback is already in flight on this device.",
                )
            }
            try {
                // Before the call, not after it. A generation that fails, or that is cancelled,
                // must not leave the previous generation's identity and finish reason readable:
                // the seam would then check this text against the last answer's envelope.
                lastResponse = null

                val response = post(caseRecordId, prompt, maxOutputTokens)
                lastResponse = response
                // Exactly one chunk. An absent `text` is a malformed envelope and was already
                // refused by [readEnvelope]; an empty one is legitimate and emits as empty.
                emit(response.text.orEmpty())
            } finally {
                inFlight.unlock()
            }
        }

    /**
     * Null when the envelope carried no usable identity, which the seam treats as a mismatch.
     *
     * **Identity here is `model_id` and `model_sha256` together, and a half identity is none.** The
     * seam's gate compares only the id, so a service that derives its id correctly while leaving
     * the digest blank would pass it. That is not a hypothetical shape: `slm-service-contract.md`
     * §3.2 records three measured instances of a self-describing field being asserted rather than
     * derived, and an envelope with one identity field implemented and one not is exactly what a
     * partly-built service looks like. Withholding the id in that case is not a substitution and
     * does not violate this method's contract, which forbids answering with **this build's own
     * expectation**: it reports that the response carried no identity worth comparing, and the
     * gate then fails closed, which is what an absent identity is defined to mean.
     */
    override fun servedModelId(): String? {
        val response = lastResponse ?: return null
        val modelId = response.modelId
        val digest = response.modelSha256
        if (modelId.isNullOrBlank() || digest.isNullOrBlank()) return null
        return modelId
    }

    /** Null when the envelope carried no finish reason, or one this contract does not define.
     *  Both are [com.example.samdapp.domain.slm.SlmRefusal.OUTPUT_TRUNCATED] at the seam, because
     *  "the service did not say whether the answer is complete" and "the answer is not complete"
     *  are the same thing from a worker's side. Never synthesised from the `200`. */
    override fun finishReason(): SlmFinishReason? = finishReasonOf(lastResponse?.finishReason)

    private suspend fun post(
        caseRecordId: String,
        prompt: String,
        maxOutputTokens: Int,
    ): SlmReadbackResponseDto {
        val request = SlmReadbackRequestDto(
            caseToken = caseRecordId,
            prompt = prompt,
            // The pin this build's sanitizer and identity gate are calibrated to, stated outward so
            // a service holding something else answers 409 rather than returning text that this
            // device refuses a gate later. Not read back from the response, ever.
            // Read straight from the constants the gates are calibrated against, not from a
            // configuration value and not from a constructor parameter. The point of stating the
            // pin outward is that it is THIS BUILD'S pin; a configurable one is the
            // written-once-at-authoring-time field that slm-service-contract.md section 3.2
            // records three measured instances of, moved to the caller's side of the wire.
            modelId = SANITIZER_TARGET_MODEL_ID,
            promptTemplateVersion = PROMPT_TEMPLATE_VERSION,
            maxTokens = maxOutputTokens,
            seed = SLM_REQUEST_SEED,
        )

        val response = try {
            api.readback(request)
        } catch (e: CancellationException) {
            // Before the IOException and RuntimeException branches, which it would otherwise match
            // (kotlinx's CancellationException is a RuntimeException, and OkHttp surfaces a
            // cancelled call as an IOException on some paths). Same ordering and same reason as
            // RetrofitAbhaSource and RetrofitKernelSource: a cancelled readback has no result at
            // all, not a refusal, and the seam rethrows it rather than classifying it.
            throw e
        } catch (e: IOException) {
            throw SlmEngineException(
                transportErrorFor(e),
                // The class name, never the message: an IOException's message can quote the host
                // and port, and nothing on the refusal path reads it anyway.
                "Readback transport failure (${e.javaClass.simpleName})",
                e,
            )
        } catch (e: RuntimeException) {
            // The HTTP exchange succeeded and Gson then failed to build the DTO: a body that is not
            // the declared shape. Not unreachable: a server that answered unintelligibly is not a
            // server that was absent. The exception message is deliberately not interpolated, only
            // its class name, because a parser message can quote the body it choked on.
            throw SlmEngineException(
                SlmEngineError.MALFORMED_RESPONSE,
                "Malformed readback response body (${e.javaClass.simpleName})",
                e,
            )
        }

        return readEnvelope(response)
    }

    private fun readEnvelope(
        response: Response<ApiEnvelopeDto<SlmReadbackResponseDto>>,
    ): SlmReadbackResponseDto {
        if (!response.isSuccessful) {
            // The house block, fourth instance. Identical to RetrofitAuthService.call,
            // RetrofitAbhaSource.call and RetrofitKernelSource: read the error body once, try to
            // read it as a problem document, and accept null rather than throwing on it.
            val problem = response.errorBody()?.charStream()?.use { reader ->
                runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
            }
            throw SlmEngineException(
                httpErrorFor(problem?.code, response.code()),
                // The code and the status, never `detail`: a message relayed from a remote service
                // is untrusted text and may carry anything the service put in it, including the
                // prompt a verbose service echoed back. Nothing on the refusal path reads this.
                "Readback refused (HTTP ${response.code()}, code ${problem?.code ?: "none"})",
            )
        }

        val envelope = response.body()
            ?: throw SlmEngineException(
                SlmEngineError.MALFORMED_RESPONSE,
                "Empty readback response body.",
            )
        val data = envelope.data
        // `text` is required on a 200 and may legitimately be an empty string, so absence is the
        // check and blankness is not. The backend already refuses envelopes missing a required
        // field, and this does not rely on that: a gate that is only correct because the layer
        // below it is correct stops being a gate the day that layer changes.
        if (data.text == null) {
            throw SlmEngineException(
                SlmEngineError.MALFORMED_RESPONSE,
                "Readback envelope carried no text field.",
            )
        }
        return data
    }
}

/**
 * The finish reasons `slm-service-contract.md` §3.4 defines, and nothing else.
 *
 * Anything unrecognised maps to `null` rather than to a nearest neighbour, and `null` refuses at
 * the seam. A service that invents a fifth value has drifted from the contract, and guessing that
 * its new word probably means "complete" is how a truncated readback reaches a worker.
 */
internal fun finishReasonOf(raw: String?): SlmFinishReason? = when (raw) {
    "stop" -> SlmFinishReason.STOP
    "length" -> SlmFinishReason.LENGTH
    "stop_sequence" -> SlmFinishReason.STOP_SEQUENCE
    "error" -> SlmFinishReason.ERROR
    else -> null
}

/**
 * Transport failures: the bytes did not come back, so there is no status and no code to read.
 *
 * Pure, total and Android-free, so it is unit testable without a device and without a Retrofit
 * stub, which is the same construction `classifyKernelFailure` uses and for the same reason.
 *
 * **Classified by exception TYPE, never by message.** An `IOException`'s message is OEM- and
 * OS-dependent, is not a contract, and can quote the host and port.
 *
 * **The branch order is the safety property.** Every type below extends `IOException`, so an
 * `else -> UNREACHABLE` placed above any of them silently absorbs it. [SSLException] is the one
 * that matters: it is an `IOException`, and a classification that reaches the catch-all first
 * reports a TLS failure as "you are offline". That is the wrong answer in the one direction that
 * costs something. A captive portal, a skewed clock and an interception all produce it, and the
 * advice "keep tapping, it will go through when you have signal" means keep pushing a physician's
 * free-text diagnosis and a full prescription line set at a connection that may be reading them.
 * `KernelFailure.SECURE_CONNECTION_FAILED` exists on the sibling path for exactly this reason and
 * that path carries eight numeric features under a pseudonym.
 */
internal fun transportErrorFor(e: IOException): SlmEngineError = when (e) {
    // Above SocketTimeoutException and above the catch-all. SSLException extends IOException and
    // nothing else here is a supertype of it, so this is the only ordering that reaches it.
    is SSLException -> SlmEngineError.SECURE_CONNECTION_FAILED
    // The connection was established and the answer did not arrive in time.
    is SocketTimeoutException -> SlmEngineError.TIMEOUT
    // **A response that started arriving and ended early is not an absent server.** Gson's reader
    // raises EOFException on an empty or truncated body, and Retrofit propagates it as the checked
    // IOException it is, so without this branch it reaches the catch-all below. MEASURED: a 200
    // with an empty body classified as UNREACHABLE, which would tell a worker holding a connected
    // phone that they are offline, about a request that reached the backend, was authenticated,
    // ran, and left an slm_call_log row. The rule this encodes is that UNREACHABLE means no
    // response at all; once any of one has arrived, an unusable answer is MALFORMED_RESPONSE. That
    // also covers the other producer of this exception, a body truncated mid-flight, and it is the
    // honest answer for that too: the server answered and the answer could not be read.
    // A test found this. Reading the branch order did not.
    is java.io.EOFException -> SlmEngineError.MALFORMED_RESPONSE
    // OkHttp raises a bare InterruptedIOException, not a SocketTimeoutException, when `callTimeout`
    // expires. Below SocketTimeoutException, which is a subclass of it. Without this branch the
    // one bound that covers the whole call, including redirects, retries and the body read, would
    // be reported as "no route", which is the F6B-02 collapse in miniature: the most specific
    // failure on the path, produced by the budget this build set deliberately, landing in the
    // vaguest bucket available.
    is InterruptedIOException -> SlmEngineError.TIMEOUT
    // UnknownHostException, ConnectException, and every other IOException that means the bytes did
    // not get there. The normal case on an offline-first device, and not TIMEOUT: only a
    // SocketTimeoutException proves a connection was established first.
    else -> SlmEngineError.UNREACHABLE
}

/**
 * Non-2xx responses from `backend/core`, mapped from the RFC 9457 `code` where there is one and
 * from the status class where there is not.
 *
 * Pure and total, for [transportErrorFor]'s reasons. Classification prefers the `SAMD-*` code over
 * the status because the code is the contract; `ProblemDetailDto`'s own KDoc states that Android
 * branches on `code`, never on `title` or `detail`. The status is the fallback for a body that was
 * absent or was not a problem document, which is the case the three sibling sources already handle
 * by passing `code = null`.
 *
 * **Every row of api-contract.md §11.3's failure table appears below, and two of them are not the
 * obvious mapping.**
 *
 * `SAMD-SLM-8010` says the **backend** could not reach the **service**. It is not
 * [SlmEngineError.UNREACHABLE], which on this device means the handset could not reach the backend
 * and is the normal offline condition. The device demonstrably did reach the backend, because the
 * backend is what answered, and telling a worker holding a connected phone that they are offline is
 * a wrong answer that also invites exactly the wrong retry. It is [SlmEngineError.UNAVAILABLE]:
 * something one hop further in is down, retry but not immediately.
 *
 * `SAMD-ENC-4002` is a `404` for a case this backend does not have, which happens when the case
 * record has not synced or its create failed upstream. It is [SlmEngineError.PAYLOAD_REJECTED]
 * rather than a transient failure because it will fail identically forever: the fix is on the sync
 * path, not on this button. The sibling kernel path has a dedicated `CASE_NOT_ON_SERVER` value for
 * this and it drives distinct copy; this vocabulary has none, so the distinction is recorded here
 * and in `scratchpad/pr6-device-slm-binding.md` rather than silently lost.
 *
 * A `401` or `403` reaching this function has already been through `TokenAuthenticator`, so the
 * refresh was attempted and failed. It is `PAYLOAD_REJECTED` for the same reason: unretryable
 * unchanged, and a person has to act. That the person's action is "sign in again" rather than "fix
 * a build" is a distinction this vocabulary cannot carry either, and it is recorded in the same
 * two places.
 */
internal fun httpErrorFor(code: String?, httpStatus: Int): SlmEngineError = when {
    // Backend refused to send: no SLM configured, or the circuit is open. Both are "up, not
    // serving, retry but not immediately", which is what the seam's ENGINE_UNAVAILABLE means.
    code == "SAMD-SLM-8020" -> SlmEngineError.UNAVAILABLE
    code == "SAMD-SLM-8015" -> SlmEngineError.UNAVAILABLE
    // The service is up and not serving: weights not resident, or its bounded queue is full.
    code == "SAMD-SLM-8004" -> SlmEngineError.UNAVAILABLE
    code == "SAMD-SLM-8005" -> SlmEngineError.UNAVAILABLE
    // The backend could not reach the service. See the KDoc: not UNREACHABLE.
    code == "SAMD-SLM-8010" -> SlmEngineError.UNAVAILABLE
    // The backend's own read budget expired waiting on the service.
    code == "SAMD-SLM-8011" -> SlmEngineError.TIMEOUT
    // The service refused the request: 422, 409 or 413 one hop in.
    code == "SAMD-SLM-8012" -> SlmEngineError.PAYLOAD_REJECTED
    // The outbound PHI guard tripped. Refused before generation, and unretryable unchanged.
    code == "SAMD-SLM-8014" -> SlmEngineError.PAYLOAD_REJECTED
    // The case does not resolve on this backend, or belongs to another facility. See the KDoc.
    code == "SAMD-ENC-4002" -> SlmEngineError.PAYLOAD_REJECTED
    // The service failed inside itself: a 500, or a 504 from its own 40 s wall clock.
    code == "SAMD-SLM-8006" -> SlmEngineError.ENGINE_ERROR
    // A body the backend could not parse, or an envelope missing a required field.
    code == "SAMD-SLM-8013" -> SlmEngineError.MALFORMED_RESPONSE
    // A defect in the proxy itself. The backend says so rather than dressing it as an outage.
    code == "SAMD-SYS-9005" -> SlmEngineError.ENGINE_ERROR

    // No code, or one this build does not know. Fall back to the status class, which is still
    // contractual, rather than to a single bucket. An unknown SAMD-* code is a backend newer than
    // this build, and its status still carries the class of the failure.
    httpStatus == 401 || httpStatus == 403 -> SlmEngineError.PAYLOAD_REJECTED
    httpStatus == 504 -> SlmEngineError.TIMEOUT
    httpStatus == 503 -> SlmEngineError.UNAVAILABLE
    httpStatus in 400..499 -> SlmEngineError.PAYLOAD_REJECTED
    httpStatus in 500..599 -> SlmEngineError.ENGINE_ERROR

    // Not reachable through Retrofit, which only routes non-2xx here, and not widened into one of
    // the buckets above. A status outside every class named is a response this contract does not
    // describe, and ENGINE_ERROR is the value whose documentation says exactly that: the catch-all,
    // deliberately last, so an unrecognised failure is still a refusal and never an answer.
    else -> SlmEngineError.ENGINE_ERROR
}
