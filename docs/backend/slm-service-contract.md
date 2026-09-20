# SLM Service Contract (v1)

> **PROPOSED, AWAITING OPERATOR SIGN-OFF.** Not an approved controlled document. Specification
> only: **no implementation exists**, on either side of this contract.

**What this document is.** The wire contract of the SLM generation service: the request it accepts,
the response envelope it returns, its error vocabulary, the timeout budget it sits inside, and the
operational properties a caller is entitled to rely on. It is the document PR-4 (the calling side)
and PR-5 (the service) are both built against.

**What this document is not.** It does not say where the schema is enforced, which component calls
the service, or how that call is routed. That is topology and it is settled separately. It does not
specify an endpoint in `api-contract.md`'s sense: that file is the **device to backend** contract,
and this one sits one hop further in, the way `api-contract.md` §5.1 describes the kernel path
mapping without republishing the kernel's own schema.

**Conventions.** §0 of `api-contract.md` applies unchanged: content type, timestamp format, field
naming, the success envelope (§0.5) and the RFC 9457 error envelope (§0.6), including its rule that
`detail` never contains PHI. Error codes follow §9.1's registry discipline: **permanent, never
reused for a different meaning, never renumbered**, because a caller branches on the string.

**Relationship to the sample serving application.** There is none. The `/v1/chat/completions`
surface in the sample repository is not a starting point to be modified, extended or migrated from.
This contract replaces it in full, and nothing in this project calls that shape.

---

## 1. Why this exists, and the one property everything else serves

The service generates a plain-language readback of a record a physician has already approved. It
adds no clinical content, and the device's guardrail seam is built to guarantee that structurally.
Three of that seam's controls are calibrated against **one specific model artifact**: the stream
sanitizer's literal set is that artifact's tokenizer vocabulary, its channel grammar is that
artifact's channel grammar, and the output grounding gate is tuned to what a readback of a
five-field snapshot can legitimately contain.

**So the single property this contract exists to provide is: the caller can always tell what it is
actually talking to, and whether what came back is complete.** Every required field below serves
that. A service that answers correctly but describes itself inaccurately is more dangerous here than
one that fails, because a failure is visible and a wrong self-description is not.

---

## 2. Request

### 2.1 Shape

```json
{
  "prompt": "You are restating a record a doctor has already approved. Use plain language.\n...",
  "model_id": "google/gemma-4-E2B-it",
  "prompt_template_version": "slm-readback-v1",
  "max_tokens": 512,
  "temperature": 0,
  "do_sample": false,
  "seed": 20260918,
  "stop": []
}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `prompt` | string | yes | The whole prompt, already assembled. **Not a messages array.** See §2.2. |
| `model_id` | string | yes | Validated against the loaded artifact. Mismatch is `409`, not a substitution. |
| `prompt_template_version` | string | yes | Opaque to the service. Rejected when absent or blank. Echoed. |
| `max_tokens` | integer | yes | Served as `min(requested, ceiling)`. See §2.4. |
| `temperature` | number | yes | **Must be exactly 0.** Any other value is `422`. |
| `do_sample` | boolean | yes | **Must be `false`.** Any other value is `422`. |
| `seed` | integer | yes | Echoed. Present even under greedy decoding. See §2.5. |
| `stop` | array of strings | yes | May be empty. Echoed. See §2.6. |

Unknown fields are rejected, not ignored. A caller sending a field this contract does not define is
either on a newer contract version or is confused, and both cases are better as a `422` than as a
silently dropped parameter.

### 2.2 `prompt` is a string, and the absence of a history field is the control

**Single turn is enforced by there being no parameter a transcript could arrive on.** That is the
same construction the device's own invocation type uses: it carries a snapshot and one question,
with no history field and no list-of-messages field, so a caller cannot pass a transcript, not
because passing one is discouraged but because there is nothing to pass it on.

A `messages` array on the wire would reintroduce exactly the parameter the device type deliberately
lacks, and would do so at the layer furthest from the gates. The failure it enables is specific: a
history buffer lets turn 1 establish context that turn 3's question leans on while turn 3 itself
still looks tethered to the record, which is a bypass of the input scope gate rather than a
violation of it. Removing the field removes that class rather than defending against it.

This also settles a second question before it is asked. The service performs **no** chat-template
application, no role wrapping, and no system-prompt injection. The prompt is assembled by the
device's seam from a versioned template and arrives complete. A service-side preamble would be
clinical text that no template version accounts for and that no audit row could attribute.

### 2.3 Input length: rejected, never truncated

**An over-length prompt is `413` / `SAMD-SLM-8003`. It is never truncated and then generated from.**

This mirrors the device's own position, and the reasoning is the device's KDoc verbatim: a record
silently cut inside the prompt is "one the model answers about incompletely with no signal that it
did", which is why the device refuses at `MAX_PROMPT_CHARS` rather than trimming.

The service is where the real tokenizer lives, so **the bound here is in tokens**, and it is the
authoritative one. The device's 6000-character budget is documented in its own source as a
conservative stand-in "until a tokenizer exists to measure with". The two bounds are therefore not
redundant and neither replaces the other: the device's is a cheap early refusal that costs no
network call, the service's is the real limit. The device's must stay strictly tighter in practice,
so that the common case refuses locally and a `413` means something has drifted.

The rejection names the limit and the measured length. It **does not echo any part of the prompt**,
which carries a physician's free-text diagnosis and a full prescription line set.

### 2.4 `max_tokens` and its ceiling

Required from the caller, and bounded by the service: the served value is `min(requested, ceiling)`.
The ceiling is configuration, reported on the health endpoint (§5.1), and it exists because
`max_tokens` with no upper bound is how a single request becomes an out-of-memory.

**The ceiling is not the interesting number. `max_tokens` is.** See §4.3: this budget is sized so
that truncation is **rare**, not merely detectable. `OUTPUT_TRUNCATED` is a backstop, and a backstop
that fires routinely is a broken feature, not a working safety net.

### 2.5 `seed`, and why it is required under greedy decoding

`temperature: 0` with `do_sample: false` is greedy decoding, which is already deterministic, so a
seed changes nothing about this request's output. It is required anyway, for two reasons.

First, it is a **claim the response can be checked against**. The envelope echoes the decode
parameters actually used (§3.3), so a response reporting a seed the caller did not send, or omitting
one, is evidence that the service is not decoding the way it says it is. A field that is inert in
the happy path is still a live assertion about the service's honesty.

Second, it removes a silent failure mode at the moment it would matter most. If a future change ever
enables sampling, a contract with no seed produces clinical text that varies run to run with no
reproducibility handle, which is precisely the state the sample service is in: the audit found no
seed set anywhere in it, in the server, the training scripts or the app.

### 2.6 `stop`, and what "explicit" buys

Required, and may be an empty array. The point is that **the caller states its stop sequences rather
than inheriting whatever the model's generation config happens to carry**.

This is the same class of defect as an unsent decode parameter still shaping output (§3.3). In the
sample service no `stop` parameter exists in the request schema and none is passed to generation, so
termination is entirely by the base model's EOS ids. That is not wrong; it is undeclared, and an
undeclared behaviour cannot be changed deliberately or reasoned about during an incident.

An empty array means "terminate on model EOS only", stated rather than assumed.

---

## 3. Response envelope

### 3.1 Shape

```json
{
  "generation_id": "gen-01J8Z2Q7K3",
  "text": "The doctor has approved amoxicillin...",
  "model_id": "google/gemma-4-E2B-it",
  "model_sha256": "cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f",
  "prompt_template_version": "slm-readback-v1",
  "finish_reason": "stop",
  "decode": {
    "temperature": 0,
    "do_sample": false,
    "seed": 20260918,
    "top_p": null,
    "top_k": null,
    "max_new_tokens": 512,
    "stop": []
  },
  "usage": {
    "prompt_tokens": 214,
    "completion_tokens": 96,
    "total_tokens": 310
  }
}
```

Every field is required on a `200`. None is nullable except the individual entries inside `decode`,
where `null` carries meaning: it states that the parameter was **not applied**, which is different
from it being absent from the response.

### 3.2 `model_id` and `model_sha256`: derive, never assert

**Both are computed from the loaded artifact at load time. Neither may be a literal, a configuration
value, or an echo of what the request asked for.**

This is stated as a rule rather than a preference because **three instances of the same defect are
already measured in this system**:

1. The sample serving application returns a hardcoded `model` string on every response and ignores
   the request's `model` field entirely, so the field it advertises has no relationship to what is
   loaded.
2. The classifier service answers `/health` with `model_version: toy-v0.6-observed-glucose-4tier`
   while four separate places in this repository document four different strings, one of which is
   the risk file, which performs its hazard analysis against a model that is not the one serving.
3. The same classifier hardcodes `"calibrated": true` on a clinical inference response while its own
   metadata records `calibration_used_for_evaluation: False`. A field named `calibrated` on a
   clinical response is a statement about confidence quality, and it is being asserted rather than
   read.

The common shape is a self-describing field written once at authoring time and then surviving every
subsequent artifact change. The fix is structural and cheap: **every self-describing field on a model
response is computed at load time from the artifact, or it is not on the response.**

`model_sha256` is the field the caller's identity gate compares against a pin it holds. Its absence
or blankness is therefore **representable and meaningful**: a caller treats a missing or empty
identity as a **mismatch**, never as a free pass, because an envelope that has not implemented the
field yet is indistinguishable from a service quietly pointed at a different artifact, and the first
is the likeliest early-integration state.

### 3.3 `decode`: the parameters actually used

Not the parameters requested. **The parameters applied**, including any the caller never sent.

The concrete case this closes is measured: in the sample service `top_k` is not in the request
schema, is never passed explicitly, and therefore silently falls back to `64` from the base model's
generation config. **A parameter nobody sent and nobody sees is still shaping clinical text.** Any
sampling parameter not applied under greedy decoding is reported as `null`, which is an assertion
that it was inert, not an admission that it is unknown.

### 3.4 `finish_reason`: honest, and non-negotiable

One of `stop`, `length`, `stop_sequence`, `error`, **derived from the generation**, never asserted.

- `stop` model emitted an end-of-sequence token
- `length` the output-token ceiling was reached; **the text is cut off**
- `stop_sequence` a sequence from the request's `stop` array ended the generation
- `error` the service abandoned the generation part way through

**Why this field is the one that cannot be negotiated away.** A truncated readback is invisible to
every downstream check the device has:

- It passes the stream sanitizer untouched. Truncation is not a control token.
- It passes the output grounding gate **by construction**. A cut-off restatement of an approved
  record introduces no drug name and no dosing numeral the record did not already contain, so the
  containment check returns true on it every time.
- It arrives over a `200`, with a well-formed body.

There is no other signal anywhere in the system. What reaches a health worker if this field lies is
half a dosing instruction read as a whole one: a line that stops before the duration, or before the
food relation. The sample service returns the literal string `"stop"` unconditionally, so a
completion cut off at the token ceiling reports exactly what a complete one reports, and the audit's
own words are that "a consumer cannot distinguish a complete answer from a truncated one". **That
must be impossible here.**

A caller treats an absent finish reason as truncation, for the same reason it treats an absent
`model_sha256` as a mismatch.

### 3.5 `usage`: tokens, counted as tokens

`prompt_tokens`, `completion_tokens` and `total_tokens` are token counts from the tokenizer.

Stated explicitly because the sample service's streaming path increments its completion counter once
per emitted text chunk rather than per token, so the throughput figure it publishes is not a token
rate. These counts feed the audit row's measured metadata, and a count that is not what it claims to
be is worse than no count.

`usage` carries no latency and no tokens-per-second. Those are the caller's to measure, from the
call it made, and a service self-reporting its own performance has the same trust problem as a
service self-asserting its own identity.

---

## 4. Errors, outcomes and the timeout budget

### 4.1 Error code registry

Following `api-contract.md` §9.1's discipline. The `SAMD-SLM-8xxx` block is previously unused.

| Code | HTTP | Meaning |
|---|---|---|
| `SAMD-SLM-8001` | 422 | Request validation failed (`errors[]` present): `temperature` not 0, `do_sample` not false, missing `prompt_template_version`, unknown field |
| `SAMD-SLM-8002` | 409 | `model_id` does not match the loaded artifact |
| `SAMD-SLM-8003` | 413 | Prompt exceeds the input token limit |
| `SAMD-SLM-8004` | 503 | Model not loaded |
| `SAMD-SLM-8005` | 503 | Request queue full (carries `Retry-After`) |
| `SAMD-SLM-8006` | 500 | Generation failed inside the service, including out of memory |
| `SAMD-SLM-8007` | 504 | The service's own wall-clock cap expired and it abandoned the generation |

`SAMD-SLM-8002` is deliberately `409` and not `422`. A conflict says the request was well formed and
the **service** is not the one it was meant for, which is a different operational problem from a bad
field and should page differently.

### 4.2 Mapping to `SlmCallOutcome`, and its totality

Every failure maps to exactly one outcome, and every outcome is reachable. Five are carried by a
service response; five are observable only at the call site, because no response exists to carry
them.

| Condition | Source | `SlmCallOutcome` |
|---|---|---|
| `200`, `finish_reason` `stop` or `stop_sequence` | service | `SUCCESS` |
| `200`, `finish_reason` `length` or `error`, or absent | service | `TRUNCATED` |
| `422` `SAMD-SLM-8001`, `409` `SAMD-SLM-8002`, `413` `SAMD-SLM-8003` | service | `PAYLOAD_REJECTED` |
| `503` `SAMD-SLM-8004` | service | `NOT_LOADED` |
| `503` `SAMD-SLM-8005` | service | **`QUEUE_FULL`, which does not yet exist. See §4.2.1** |
| `500` `SAMD-SLM-8006`, `504` `SAMD-SLM-8007` | service | `ENGINE_ERROR` |
| `200` whose body does not parse into §3.1 | call site | `MALFORMED_RESPONSE` |
| read or total budget expired with no response | call site | `TIMEOUT` |
| connect failure, DNS, no route | call site | `UNREACHABLE` |
| breaker open, no call attempted | call site | `CIRCUIT_OPEN` |
| outbound identity guard tripped, no call attempted | call site | `PHI_REJECTED` |

**Totality, asserted in prose because it is the property that matters.** The table is exhaustive in
both directions. Every status this contract permits the service to return appears in a row, and
every value of the outcome vocabulary appears as a target, with the one exception named below. There
is no "other" row and there must never be one: an unmapped failure is exactly how a specific,
recoverable, actionable error gets collapsed into a generic bucket, which is the defect the perf
audit traced through six hops and named at a single `catch` block. If a future failure has no row,
the correct response is to add a row, not to widen `ENGINE_ERROR`.

#### 4.2.1 A gap this document found in the outcome vocabulary

**`QUEUE_FULL` does not exist and is required.** Writing the mapping surfaced it.

`SlmCallOutcome` currently has `NOT_LOADED` for "up but not serving because the weights are not
resident" and `CIRCUIT_OPEN` for "the caller declined to call". Neither describes a service that is
loaded, healthy and **saturated**, which is the ordinary consequence of a single worker with a
bounded queue (§5.2). Mapping a queue-full `503` onto `NOT_LOADED` would make the log state
something untrue, and the two have different operator responses: one is a deployment fault, the
other is normal backpressure that a `Retry-After` already tells the caller how to handle.

The value belongs in `SlmCallOutcome` alongside the others when the log table that consumes it
lands. **Recorded here rather than added, because the enum shipped in the previous change and this
one is specification only.** On the device side no new refusal value is needed: saturation and
"model not loaded" are both "retryable, but not immediately", which the existing
`ENGINE_UNAVAILABLE` already means. That value's own documentation currently says "circuit open, or
the service reports its model is not loaded" and should be widened to name saturation when the
calling side is built.

### 4.3 Timeout budget

Each layer strictly inside the next, so that a timeout is always classified at the innermost layer
that can still see the cause. If an outer layer fires first, every failure looks the same from
outside and the §4.2 vocabulary stops earning its keep.

| Layer | connect | read | whole call |
|---|---|---|---|
| Device to backend, SLM endpoint | 10 s | **55 s** | 60 s |
| Backend to SLM service | 5 s | 50 s | 50 s |
| Service internal wall clock | n/a | n/a | **40 s hard cap** |

At 40 s the service abandons its own generation and frees VRAM **deliberately**, returning `504` /
`SAMD-SLM-8007`. That is the difference between a bounded failure and an abandoned generation that
keeps a single-worker service blocked while the caller has already given up.

**Why 40 s and not more.** A readback is an optional convenience offered mid-consultation, with a
patient in the room. Past roughly 45 seconds it has stopped being useful, and a worker will rightly
stop using a feature that makes them wait. If generations do not fit inside 40 s, **that is a signal
about `max_tokens` and prompt brevity, not a reason to widen the window.** `max_tokens` should be
sized so that truncation is rare rather than merely detectable: `OUTPUT_TRUNCATED` is a backstop,
and a backstop that fires often is a broken feature wearing a safety net.

**One correction to the budget as briefed.** The device's read and whole-call bounds were specified
as 60 s and 60 s. Equal values violate this section's own rule, and not only in theory: with a
connect budget of 10 s, a call that spends any time connecting can have its whole-call bound expire
while the read bound still has headroom, so the timeout is classified at the outermost layer, which
is the one that knows least. The read bound is therefore **55 s**, strictly inside the 60 s
whole-call bound. Everything else is as briefed.

**Arithmetic worth recording, because it is unvalidated.** The device's output ceiling is 512
tokens. A 40 s service cap means the service must sustain at least **12.8 tokens per second** for a
maximum-length generation to complete. **No latency or throughput measurement for this stack exists**
(the audit searched and found none: no benchmark artifact, no eval output, empty logs). On GPU this
is very likely comfortable for a model of this size, but that is **INFERRED and not measured**, and
the first measurement taken when the service exists must confirm it. If it does not hold, §4.3's own
rule applies: reduce `max_tokens`, do not widen the cap.

### 4.4 Authentication

**The backend-to-service hop MUST be authenticated. The mechanism is not chosen here**; it belongs
with the service that has to enforce it.

Stated as a requirement rather than left implicit because of a measured precedent that must not be
inherited. The existing kernel hop has **no authentication at all**: its client is constructed with
a base URL and a timeout and no headers, and the kernel is reached over a plain configured URL on
the LAN. That is a tolerable posture for a hop carrying eight numeric features under a pseudonymous
case token. **It is not tolerable for a hop carrying a physician's free-text working diagnosis and a
full prescription line set**, which is what a readback prompt is.

Copying the kernel hop's shape is the right call for the topology and the wrong call for this
specific gap. The gap must be closed on this hop, deliberately, in the change that builds it.

---

## 5. Operational properties a caller may rely on

### 5.1 Health endpoint

`GET /health`. **Never a static `healthy` literal.** Reports:

| Field | Why a caller needs it |
|---|---|
| load state | Distinguishes "starting" from "serving"; a service reporting healthy while unloaded turns a fast failure into a slow one |
| `model_id`, derived | The same value §3.2 requires on a generation, checkable without generating |
| `model_sha256` | Lets an operator confirm the artifact without a clinical request |
| device in use (`cuda` or `cpu`) | See §5.3 |
| VRAM in use | Capacity and leak visibility |
| queue depth | The number behind §4.2.1's backpressure, so the caller's breaker and an operator read the same figure |
| decode ceiling in force | The `max_tokens` bound §2.4 will actually apply |

This shape is what made the classifier's five-way version mismatch findable at all: the running
service published its model version on `/health`, and that is how the discrepancy with four
documented strings was caught.

### 5.2 One worker, one resident model, a bounded queue

**A single worker process.** The reason is arithmetic, and it is different from the classifier's.
The classifier holds roughly 2 GB resident for a 4.7 MB model because its import graph pulls torch,
so adding workers multiplies an import graph. Here the multiplied quantity is **the weight set
itself**: the served artifact is a bfloat16 checkpoint of roughly 9.5 GiB, so two workers is
roughly 19 GiB of VRAM before any KV cache. Concurrency does not come from process count.

It comes from an **in-process bounded queue**. When the queue is full the service answers `503` /
`SAMD-SLM-8005` with a `Retry-After`, immediately. An unbounded queue converts a load spike into a
wave of timeouts, and a timeout is indistinguishable at the far end from the service being down, so
an honest refusal now is strictly better information than a slow failure later.

The model loads **once, at startup**, in a lifespan hook rather than at module import, so that a
load failure is a startup failure with a log line and the health endpoint can report load state
rather than the process dying during import.

### 5.3 GPU, and the silent CPU fallback that must not exist

The service requires a GPU and **must fail to start without one**. Device selection is pinned, not
auto-detected.

The failure mode this forecloses is measured in the sample repository's own files: its container is
built from a slim Python base with no CUDA runtime, its compose definition declares no GPU
reservation, and its device setting is `auto`, resolving to CPU when CUDA is unavailable. Those
three compose into a service that **starts successfully and serves**. On a model of this size the
observable result is not an error, it is a generation that takes minutes, which under §4.3 is a
`504` on every request with no indication of why. `auto` is the wrong default for a service whose
reason to exist is a GPU.

### 5.4 Client disconnect

**The service detects client disconnect and abandons the generation.**

This is not an optimisation. The caller cannot deliver cancellation on its own: cancelling an HTTP
call closes a socket, while the generation continues to the token ceiling, holding VRAM, and on a
single-worker service blocking the next request. Without disconnect detection a cancelled readback
costs a full generation and an immediate retry has two of them resident. The device-side interface
already carries this correction in its own documentation, naming the service as the owner of the
obligation.

---

## 6. Deliberately not specified here

| Item | Where it belongs |
|---|---|
| Which component calls the service, and how the call is routed | topology, settled separately |
| Where the request schema is enforced | the calling side's change |
| The authentication mechanism on the backend-to-service hop | the service's change (§4.4 fixes the requirement, not the scheme) |
| Streaming | not in v1. The output grounding gate withholds all text until generation completes, so streaming buys no perceived latency for the tier this is first built for, while costing a streaming timeout policy |
| Batch or multi-prompt requests | out of scope. Batch size is one, always |
| Any `slm_call_log` table shape | the calling side's change; this document fixes only the outcome vocabulary it must record |
