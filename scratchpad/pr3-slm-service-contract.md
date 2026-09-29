# PR-3: SLM service contract

**Status:** documentation only, uncommitted. **No code was written or changed in this PR.** The four
pre-existing uncommitted changes, PR-0, PR-1 and PR-2 are all present and untouched.
`docs/quality/risk-management-file.md` was not edited: H-11.C1, H-27 and the drafted H-28 remain
PROPOSED.

**Date:** 2026-09-18
**Tree:** SaMDApp @ `a219da9`, main machine, authoritative.
**Deliverable:** `docs/backend/slm-service-contract.md`, 460 lines.

---

## 1. Where it was put, and why not in `api-contract.md`

**(MEASURED)** `docs/backend/api-contract.md` is titled "SaMD Backend API Contract (v1)" and
documents the **device to backend** surface. Its kernel section (§5) does not republish the kernel's
own schema; it documents the path mapping, the PHI guarantee and the backend endpoints that proxy to
it. The kernel's own wire contract is not in that file at all.

**(INFERRED)** The SLM service contract is one hop further in, the same position the kernel occupies.
Writing it as an `api-contract.md` §11 would have meant specifying a `/api/v1/...` endpoint, and the
brief forbids specifying where the schema is enforced. So it is a sibling document in the same
folder, matching §0's conventions, heading style, table shape and level of detail exactly, and
deferring to `api-contract.md` for the envelopes and the error-registry discipline rather than
restating them.

**No request or response data classes were written.** The brief allowed them "if the project's
conventions call for it". They do not, yet: a Kotlin DTO lives under `data/remote/dto/` and belongs
to a client that does not exist; a Pydantic model belongs to whichever side enforces the schema, and
naming that side is exactly what this PR is forbidden to do. Types written now would have no caller
and would pre-judge the enforcement point. The contract's tables are the specification; the types
are the calling side's and the service's to write against them.

---

## 2. What the contract closes

Each row is a measured defect in the sample serving application or in this system, now specified out
of existence.

| # | Defect | Closed by |
|---|---|---|
| 1 | `model` field on the request ignored entirely; response `model` a hardcoded literal | `model_id` required and validated (409 on mismatch); response `model_id` **derived at load** |
| 2 | No artifact digest anywhere, so a caller cannot verify what answered | `model_sha256` computed at load, echoed every response |
| 3 | `finish_reason` is the literal `"stop"` unconditionally | honest `finish_reason`, derived, four values, absence treated as truncation |
| 4 | No input length limit at any layer; tokenizer will not self-truncate; ends in CUDA OOM | enforced token limit, `413`, **rejected never truncated** |
| 5 | `max_tokens` with no upper bound | required from caller, served as `min(requested, ceiling)` |
| 6 | No stop sequences in the schema; termination by inherited EOS ids | `stop` required, may be empty, echoed |
| 7 | No seed anywhere; sampling on at temperature 0.1 | `seed` required and echoed; `temperature` must be 0, `do_sample` must be false, else 422 |
| 8 | `top_k` silently inherited as 64 from the base generation config | `decode` block reports parameters **actually used**, `null` meaning not applied |
| 9 | Streaming completion counter counts chunks, not tokens | `usage` specified in tokens |
| 10 | Client-held 16-turn history replayed on every request, windowed by message count | `prompt` is a string; **single turn enforced by the absence of a history field** |
| 11 | No auth on the model hop | §4.4 makes authentication a requirement on the backend-to-service hop, mechanism deferred |
| 12 | `auto` device selection with no CUDA base image, silently serving on CPU | GPU pinned, **must fail to start without one** |
| 13 | Unbounded work, no queue, no rate limit | single worker, in-process bounded queue, `503` + `Retry-After` |
| 14 | Cancellation not deliverable by the client | server-side disconnect detection made the service's obligation |

---

## 3. The error mapping table

Ten `SlmCallOutcome` values, ten conditions, exhaustive both ways. Five carried by a service
response, five observable only at the call site.

| Condition | Source | Outcome |
|---|---|---|
| `200`, finish `stop` or `stop_sequence` | service | `SUCCESS` |
| `200`, finish `length` or `error`, or absent | service | `TRUNCATED` |
| `422` 8001, `409` 8002, `413` 8003 | service | `PAYLOAD_REJECTED` |
| `503` 8004 | service | `NOT_LOADED` |
| `503` 8005 | service | **`QUEUE_FULL`, does not exist yet** |
| `500` 8006, `504` 8007 | service | `ENGINE_ERROR` |
| `200` body does not parse | call site | `MALFORMED_RESPONSE` |
| budget expired, no response | call site | `TIMEOUT` |
| connect failure, DNS, no route | call site | `UNREACHABLE` |
| breaker open, no call made | call site | `CIRCUIT_OPEN` |
| identity guard tripped, no call made | call site | `PHI_REJECTED` |

New codes, in the previously unused `SAMD-SLM-8xxx` block: 8001 validation (422), 8002 model
mismatch (**409**, not 422, because a conflict is an operational problem that should page
differently from a bad field), 8003 prompt too long (413), 8004 not loaded (503), 8005 queue full
(503 + `Retry-After`), 8006 generation failed (500), 8007 service wall-clock cap (504).

---

## 4. What writing it found

This has been the most useful section of each record, so it is the longest one here.

### 4.1 `SlmCallOutcome` has no value for a saturated service. PR-2's enum is one short.

Building the mapping table surfaced it. `NOT_LOADED` means the weights are not resident.
`CIRCUIT_OPEN` means the caller declined to call. **Neither describes a service that is loaded,
healthy and busy**, which is the ordinary steady-state consequence of the single worker plus bounded
queue that §5.2 requires.

Mapping a queue-full `503` onto `NOT_LOADED` would write something untrue into the log, and the two
have opposite operator responses: one is a deployment fault worth waking someone for, the other is
normal backpressure that the `Retry-After` header already tells the caller how to handle.

**Recorded, not added.** The enum shipped in PR-2 and this PR is specification only. `QUEUE_FULL`
belongs in `SlmCallOutcome` when the log table that consumes it lands. On the device side nothing new
is needed: saturation and "not loaded" are both "retryable, but not immediately", which is what
`ENGINE_UNAVAILABLE` already means; only that value's own documentation needs widening, since it
currently enumerates "circuit open, or the service reports its model is not loaded".

### 4.2 The briefed device budget violated the brief's own rule

The brief specified device read 60 s and `callTimeout` 60 s, under the stated principle that each
layer sits **strictly** inside the next. Equal values are not strictly inside.

It is not only a wording point. `callTimeout` bounds the whole call including connect, and the
connect budget is 10 s. A call that spends any measurable time connecting can have its whole-call
bound expire while the read bound still has headroom, so the timeout gets classified at the
**outermost** layer, which is the one that knows least about the cause. That is precisely the
failure the strict-nesting rule exists to prevent.

The contract sets device read to **55 s**, inside the 60 s whole-call bound. Everything else is as
briefed: backend 5 s / 50 s / 50 s, service hard cap 40 s.

### 4.3 The memo's 90 s and 120 s budget was wrong, and the override is right, but not for the reason given

**I agree with 60/50/40 and would not argue for the memo's numbers. The brief asked me to say so with
a reason rather than comply silently, so here is the reason, which is stronger than the product
argument alone.**

**(MEASURED)** The memo sized its 90 s read budget from the guardrail memo's §8 latency figure: about
12 tokens per second at about 3.35 GiB resident, giving a 20 to 60 second planning range. **That
figure is a desktop CPU measurement of MedGemma 1.5 4B.** It is a CPU number, for a different model,
taken for an on-device deployment that no longer exists in this design.

**(INFERRED)** Carrying it into a GPU service budget was an error of provenance, not of arithmetic. A
budget inherited from a measurement of a different artifact on different hardware is not a
conservative budget, it is an unrelated one, and it happened to be large, which reads as safe and is
not. The product argument in the brief reaches the same conclusion by a better route: a readback is
an optional convenience offered with a patient in the room, and past roughly 45 seconds a worker will
stop using it, so a budget that permits two minutes is specifying a feature nobody will use.

**The arithmetic that actually binds, recorded because it is unvalidated.** The device ceiling is 512
output tokens and the service cap is 40 s, so the service must sustain at least **12.8 tokens per
second** for a maximum-length generation to finish. **(MEASURED)** No latency or throughput
measurement for this stack exists; the audit searched for one and found no benchmark artifact, no
eval output and empty logs. **(INFERRED)** On a GPU this is very likely comfortable for a model of
this size, but it is not measured, and the first measurement taken when the service exists must
confirm it. If it does not hold, the correct lever is `max_tokens`, not the window, which is the
brief's own rule and is written into §4.3.

### 4.4 The memo's §2.2 envelope was missing the field PR-1's gate actually compares

**(MEASURED)** The memo's response-envelope list names "served model identifier derived from the
artifact", decode parameters, a generation id and an honest finish reason. It does **not** name an
artifact digest.

PR-1 then built the identity gate with two pinned constants, the model id **and** the tokenizer
SHA-256, because a model id is a name and a name can be reused across artifact revisions while the
tokenizer vocabulary the sanitizer is calibrated against changes underneath it. The envelope as the
memo specified it cannot satisfy the gate that was built against it.

The contract adds `model_sha256` as required on every response. Worth noting as a sequencing lesson
rather than a mistake: the gate was built before the envelope was specified, and the gate was right.

### 4.5 The device and service length bounds are not redundant, and the direction matters

Both exist and neither replaces the other. The device refuses at 6000 characters, documented in its
own source as a conservative stand-in "until a tokenizer exists to measure with". The service refuses
in tokens, and is authoritative because that is where the tokenizer is.

The invariant worth stating, and now stated in §2.3: **the device's bound must stay strictly tighter
in practice**, so the common case refuses locally with no network call and a `413` always means
something has drifted. If they ever cross, every over-length prompt becomes a round trip carrying a
physician's diagnosis and a full prescription line set to be rejected at the far end, which is the
worst of both.

---

## 5. Open items

1. **`QUEUE_FULL`** (§4.1 above), for whichever change adds `slm_call_log`.
2. **`ENGINE_UNAVAILABLE`'s KDoc** should name saturation alongside circuit-open and not-loaded.
3. **The 12.8 tokens per second floor is unvalidated** and must be measured when the service exists.
4. **The input token limit has no number yet.** §2.3 fixes the behaviour, rejection rather than
   truncation, and leaves the value to the service, which is the only component that can measure a
   real prompt against a real tokenizer. It must be set above the device's 6000-character bound in
   token terms, per §4.5.
5. **`api-contract.md` is untouched.** When the calling side exists it needs the usual entries: an
   endpoint section, rows in §9.1's registry, §9.2's consumer map and §9.3's authorization matrix.
   Those describe a route, so they were out of scope here.
6. **Nothing in this contract is testable yet**, by construction. The first executable assertion
   against it is the service's own validation suite.

---

## 6. What is not in PR-3

No endpoint, no OkHttp client, no Retrofit interface, no backend route, no `SlmEngine`
implementation, no flag, no UI, no data classes, no migration, no risk-file edit. No code of any kind
was written or changed: the diff is one new documentation file and this record.
