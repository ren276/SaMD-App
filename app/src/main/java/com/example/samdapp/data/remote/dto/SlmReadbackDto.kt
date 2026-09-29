package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * `POST /api/v1/slm/readback` request body (api-contract.md §11.3).
 *
 * **Six fields, and the three that are absent are the interesting ones.** `temperature`,
 * `do_sample` and `stop` are not here because the backend supplies `0`, `false` and `[]` and the
 * request model rejects unknown fields. They are the determinism guarantee, and a guarantee the
 * caller can vary is not one. Adding any of them here would be rejected with a `422` by
 * `StrictModel`, which is the point: the field cannot be sent because there is nothing to send it
 * on, the same construction the single-turn rule uses at every layer of this path.
 *
 * There is no `history` and no `messages` field either, for that same reason.
 */
data class SlmReadbackRequestDto(
    /**
     * The real `case_record_id`. Resolved and facility-scoped server side and **never forwarded to
     * the generation service in any form**: the service's request schema has no identifier field
     * and rejects unknown ones, so the id is absent from that wire rather than pseudonymised on it.
     * The backend still needs it here to scope the call and to correlate the log row with a kernel
     * row for the same case.
     */
    @SerializedName("case_token") val caseToken: String,
    /** The whole assembled prompt, built by the guardrail seam. Bounded at 6000 characters here,
     *  on the backend and in tokens at the service; each bound strictly tighter than the one
     *  outside it, so the common case refuses locally. */
    val prompt: String,
    /** The artifact this build's sanitizer and identity gate are pinned to. The claim is the
     *  device's to make, so that a service serving something else answers `409` rather than
     *  returning text the device refuses one gate later. */
    @SerializedName("model_id") val modelId: String,
    @SerializedName("prompt_template_version") val promptTemplateVersion: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    /** Fixed, never random. See `SLM_REQUEST_SEED`. */
    val seed: Int,
)

/**
 * The generation service's response envelope, relayed verbatim by the backend inside
 * [ApiEnvelopeDto] (`slm-service-contract.md` §3.1).
 *
 * **Every field here is nullable and every field is required on a `200`.** That is not a
 * contradiction, it is the whole design: the contract requires the service to send them, and this
 * type refuses to assume the service did. Gson fills an absent field with `null` whatever the
 * declared type says, so declaring these non-null would not enforce presence, it would produce a
 * `null` sitting inside a type that claims it cannot be and an NPE at an unrelated line later. The
 * absences are read and turned into refusals by the engine binding; see its classification of
 * identity and finish reason.
 *
 * `decode` and `usage` are declared and deliberately unread on the device. They are the service's
 * assertion about how it decoded and what it counted, they are recorded by the backend on the
 * `slm_call_log` row, and a second consumer of them here would be a second place to keep in step
 * with the contract for no gain. They are named rather than omitted so that a reader comparing
 * this type against §3.1 can see the whole envelope.
 */
data class SlmReadbackResponseDto(
    @SerializedName("generation_id") val generationId: String? = null,
    /** The generated text. May legitimately be an empty string; absent is a malformed envelope. */
    val text: String? = null,
    /** **Derived from the loaded artifact by the service, never echoed from the request.** The
     *  device's identity gate compares it against a pin this build holds. */
    @SerializedName("model_id") val modelId: String? = null,
    /** Digest of the resident weight set (§3.2). Half an identity is not an identity: the binding
     *  reports no served model at all when this is absent or blank. */
    @SerializedName("model_sha256") val modelSha256: String? = null,
    @SerializedName("prompt_template_version") val promptTemplateVersion: String? = null,
    /** `stop`, `length`, `stop_sequence` or `error`, **derived from the generation**. The one
     *  field in the whole system that can tell a cut-off readback from a complete one. */
    @SerializedName("finish_reason") val finishReason: String? = null,
    val decode: Map<String, Any?>? = null,
    val usage: Map<String, Any?>? = null,
)
