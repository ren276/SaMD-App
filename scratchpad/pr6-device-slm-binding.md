# PR-6: the device-side SLM engine binding

Branch `feat/slm-backend-proxy`, on top of PR-4 at `f43a3cf`. Two commits, neither pushed. The four
pre-existing local changes (`.idea/deploymentTargetSelector.xml`, `backend/README.md`,
`backend/docker-compose.yml`, `tools/dev-connect.sh`) were left dirty and untouched throughout.

Every claim below is marked MEASURED or INFERRED.

---

## 0. One thing the brief asked for that could not be done as specified

STEP 0 item 1 says to add `POST /v1/generate` "using the paragraph quoted verbatim in the GPU
machine's `pr5-slm-service-note.md` section 3.1". **That file is not on this machine.** MEASURED: a
search of the whole tree for `pr5`, `PR-5` and `pr5-slm-service-note` returns no such file, and
`/tmp/claude-1000/` (the GPU session's scratchpad root, named in
`scratchpad/gpu-control-token-handoff.md`) does not exist here either. The only PR-5 material
carried back to this machine is `scratchpad/gpu-control-token-handoff.md`, which covers the
tokenizer derivation and not the service note.

So the paragraph is **not** a verbatim quote and is not presented as one. It was written from what
is measurable in this tree instead, and it names its sources so the substitution is visible:
`backend/core/app/adapters/slm/client.py` defines `GENERATE_PATH = "/v1/generate"` and
`backend/core/tests/test_slm.py` scripts `SERVICE_PATH = "/v1/generate"`. Both MEASURED. The path is
therefore right; the wording is mine. If the GPU note's paragraph says anything the contract now
does not, it has to be reconciled by whoever has both files.

Everything else in STEP 0 was applied as specified.

---

## 1. Contract corrections applied

Commit 1, `docs/backend/slm-service-contract.md` only, 165 insertions, 15 deletions.

| Item | What was wrong | What it says now |
|---|---|---|
| 1 | §2 named no generation path | `POST /v1/generate` in a §2 lead paragraph. Not a new subsection: §2.2, §2.3 and §3.1 are cited from source comments and the numbering is not renumberable |
| 2 | §3.1's example `model_sha256` was the tokenizer's digest | Replaced with a marked placeholder; §3.2 now states the digest is over the weight set |
| 3 | No code, no outcome row for a `401` on the service hop | `SAMD-SLM-8008` ratified; §4.2 row added; §4.2.2 records the missing outcome; §4.4 now names the mechanism |
| 4 | `SAMD-SLM-8004` is unreachable | Ratified as reserved and unreachable, kept in the registry, and the contract now forbids faking it in a test |
| new | Nothing required chat control-token wrapping | §2.7, as a safety property with PR-5's measurement |

**On item 2, the digest.** MEASURED and unambiguous: the value in §3.1 was
`cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f`, and that is byte for byte the
SHA-256 `scratchpad/gpu-control-token-handoff.md` reports for `tokenizer.json` at revision
`3e22461f65e89153144f8adb70e3b8c2cc9845a7`. It is also what `SlmStreamSanitizer.kt` carries as
`SANITIZER_TOKENIZER_SHA256`, where it is correctly labelled. Two fine-tunes of one base model share
a tokenizer byte for byte, so the field an operator uses to confirm which **weights** are resident
was illustrated with a file that cannot distinguish them. **No digest of any weight set has been
measured anywhere in this project**, so the replacement is a placeholder and says so rather than
inventing a second measurement-shaped constant.

**On item 3, why the code was ratified rather than re-allocated.** MEASURED: `backend/core/app/errors.py`
uses `SAMD-SLM-8004` through `8006` and then jumps to `8010`, so 8007 is the service's wall-clock
code and 8008 and 8009 are free. Spending a different number to avoid a collision that does not
exist would have been the change with no reason behind it.

**On item 3, the gap it surfaced, which is the part worth reading.** MEASURED in
`backend/core/app/services/slm.py`: there is one branch for the whole `4xx` range, so `422`, `409`,
`413` **and `401`** all become `SlmCallOutcome.PAYLOAD_REJECTED` and `ErrorCode.SLM_PAYLOAD_REJECTED`.
A deployment whose `SLM_SERVICE_TOKEN` does not match the service's therefore writes a log row
saying the **device's** request was bad, when the truth is that the **backend's** credential is
wrong and no request from any device will succeed. Those two operator responses are aimed at
different machines. That is exactly the collapse §4.2's own totality paragraph forbids, and adding
the code without the outcome would have left the collapse in place while making the contract look
complete.

It is recorded as §4.2.2 rather than fixed, in the same shape §4.2.1 used for `QUEUE_FULL`, because
the fix is not documentation: `slm_call_log.outcome` is a `String(20)` under an `enum_check` CHECK
constraint, so a new value is an Alembic migration; the calling side needs a separate device-facing
code, because `SAMD-SLM-8008` is a service-to-backend code and reusing it outward would make one
number mean two things on two hops; and `services/slm.py` needs a `401` branch above its generic
`4xx` one. Carried forward in §8.

**On item 4, why the code stays.** §5.2 loads the model once in a lifespan hook, before the service
accepts anything, so a load failure is a startup failure and there is no state in which the process
serves while its weights are not resident. §5.1's load state can report `starting` or `serving` and
can never report the third thing `SAMD-SLM-8004` describes. It is kept because §9.1's registry is
append-only and deleting a code is the first step toward reusing the number, and because
`services/slm.py` already maps it to `SlmCallOutcome.NOT_LOADED`, which is a value in a shipped
CHECK constraint. The contract now says in terms that **no test may fake it**: a test scripting a
`503` with that code proves the mapping compiles while reporting itself as coverage of a state the
deployment model forbids, which is this project's characteristic bug wearing a green tick.

**On the new §2.7, and why it is a safety property rather than a formatting note.** PR-5 MEASURED
that the same record sent raw, without the artifact's turn grammar, generates `Patient.` repeated to
the token ceiling. Trace it through every control this project built:

- the loop ends at EOS, so `finish_reason` is honestly `stop`. §3.4 is working correctly. Nothing
  was truncated, so the truncation gate is not the control that catches this;
- it carries no control token, so `SlmStreamSanitizer` passes it untouched with every suppression
  counter at zero;
- it carries no drug name and no dosing numeral absent from the record, because it carries almost no
  tokens at all, so `outputIsGrounded` returns true **by construction**, exactly as it does for a
  truncated generation;
- the envelope is correct, so the identity gate passes too.

Four independent controls, each calibrated against a real measured failure mode, and a correct
one-word answer repeated five hundred times walks through all four onto a worker's screen. The only
thing that would catch it is a person reading the text.

That also forced a correction rather than an addition. §2.2 said the service performs "**no**
chat-template application, no role wrapping, and no system-prompt injection", which is now false in
its first clause. It now says the service injects **no clinical text** (unchanged in substance: no
system prompt, no preamble, no instruction lines) and that the artifact's chat control tokens are
required. The two are not in tension: wrapping adds no word a reader sees, and omitting it changes
what the model does.

**What §2.7 admits it cannot do.** Nothing on the response envelope distinguishes a wrapped
generation from an unwrapped one, and no field would: a service that wraps wrongly would report that
it wrapped. It is a property the service holds and the caller cannot verify, which is why it is
written as a requirement with its measurement attached. The contract now says the first integration
test against a real service must include a record whose expected readback is known, and a reviewer
must read the text.

---

## 2. The binding

Commit 2. Four new production files, three changed, three test files.

```
new  app/src/main/java/com/example/samdapp/data/remote/RemoteSlmEngine.kt
new  app/src/main/java/com/example/samdapp/data/remote/api/SlmApiService.kt
new  app/src/main/java/com/example/samdapp/data/remote/dto/SlmReadbackDto.kt
new  app/src/main/java/com/example/samdapp/di/SlmNetworkModule.kt
mod  app/src/main/java/com/example/samdapp/domain/slm/SlmEngine.kt
mod  app/src/main/java/com/example/samdapp/domain/slm/SlmReadbackUseCase.kt
new  app/src/test/java/com/example/samdapp/data/remote/RemoteSlmEngineTest.kt
new  app/src/test/java/com/example/samdapp/di/SlmHttpStackTest.kt
mod  app/src/test/java/com/example/samdapp/config/SlmEngineIsUnreachableFromPresentationTest.kt
mod  app/src/test/java/com/example/samdapp/domain/slm/SlmReadbackUseCaseTest.kt
```

### 2.1 Two interface changes the brief did not ask for, and why each was forced

**`generate` takes a `caseRecordId`.** Not a widening of what the engine knows; a transport
requirement. MEASURED: `SlmReadbackRequest` in `backend/core/app/schemas/slm.py` declares
`case_token: str = Field(min_length=1, ...)`, so a binding without it produces a `422` on every
call, which classifies as `ENGINE_REJECTED_INPUT` and reads to a worker as a misconfigured build.
The backend needs it to resolve the case, scope it to the caller's facility, and name it on the
`slm_call_log` and audit rows. The seam is the only layer that has it, since `invoke` already takes
it. It goes no further than the backend: the service's own schema has no identifier field and
rejects unknown ones, so the id is **absent** from that wire rather than pseudonymised on it.

Nothing in the test suite pinned this, so `SlmReadbackUseCaseTest` gained an assertion that the
engine actually receives it. Mutation M13 confirms it (§4).

**`SlmEngineError.SECURE_CONNECTION_FAILED` and `SlmRefusal.SECURE_CONNECTION_FAILED` were added.**
This is the one vocabulary addition PR-6 makes, and the brief's own words are the reason: "SSLException
extends IOException, so a naive classification lands it in an offline bucket and tells a worker to
keep sending patient data over a possibly-intercepted connection."

With the taxonomy as PR-2 left it there was no honest target. `UNREACHABLE` is the offline bucket
and carries "try again when you have signal". `ENGINE_ERROR` maps to `SlmRefusal.ENGINE_FAILED`,
documented as "Retryable, cause unknown to the device", which is the same wrong advice in different
words. Both tell a worker to keep tapping. The sibling path already settled this argument:
`KernelFailure.SECURE_CONNECTION_FAILED` exists, carries `KernelRetryAdvice.NEEDS_ACTION` rather
than a retry advice, and `classifyKernelFailure` orders its `SSLException` branch above the
catch-all for exactly this reason. That hop carries eight numeric features under a pseudonym; this
one carries a physician's free-text diagnosis and a full prescription line set. PR-2 built the
vocabulary before a transport existed to produce the failure, so nothing was there to notice.

Cost: one value in each of two enums and one line in `engineRefusalFor`. No UI exists yet (PR-7), so
nothing downstream needed changing.

### 2.2 The client

MEASURED before writing: `NetworkModule` provides the unqualified backend client with
`BearerInterceptor`, `TokenAuthenticator`, the dev-host interceptors and the logging interceptor;
`@AbhaHttpStack` and `@Gemini` are the two qualified siblings. F6B-03's four clients are those three
plus the Pi gateway's.

`SlmNetworkModule.provideSlmOkHttpClient` takes the **unqualified** `OkHttpClient` and calls
`newBuilder()`, overriding the timeout budget and nothing else. The parameter carrying no qualifier
is what makes Hilt resolve it to the authenticated backend client, and `SlmHttpStackTest` asserts
that by reflection, because adding `@AbhaHttpStack` there reads as a one-word change.

Budget, contract §4.3: connect 10 s, read 55 s, write 55 s, `callTimeout` 60 s. **First `callTimeout`
anywhere in `app/src`** (F6B-03 MEASURED zero). It matters here and did not before because
`readTimeout` bounds the wait between bytes: a service dribbling one byte inside every 55 s window
keeps the call alive indefinitely, and redirects, connection retries and the body read each get a
fresh budget. `callTimeout` is the only bound over the whole exchange.

**One thing the budget does not achieve, MEASURED by a test that initially failed.** Connect plus
read is 65 s against a 60 s whole-call bound, so a call spending more than 5 s connecting can still
have `callTimeout` expire while the read bound has headroom, which is the outcome §4.3 raised the
read bound to 55 s to avoid. 55 is strictly inside 60, which is what §4.3 states as the rule, but
the rationale it gives ("a call that spends any time connecting can have its whole-call bound
expire") is not fully satisfied by it. The numbers are the brief's and are unchanged; the residual
5 s window is pinned by an assertion so widening it is a failing test rather than a quiet change.

### 2.3 The mutex

`Mutex.tryLock()`, never `withLock`. A mutex that queued would turn a second tap into a second
generation running a minute later: the worker sees nothing happen, taps again, pays for two
generations on a single-worker service, and the second answer arrives for a screen nobody is
looking at. A refusal is the honest answer and `ENGINE_UNAVAILABLE` already means the action
("wait, then retry"), so its KDoc was widened from three states to four rather than a fifth refusal
value being added for an identical worker action.

**It is also a data race fix, and that is the part that is a safety property.** `servedModelId()`
and `finishReason()` are per-generation state on a `@Singleton`, read by the seam *after* the flow
completes. Two concurrent generations would let the seam check generation A's text against
generation B's envelope, and the dangerous direction is real: A truncated, B complete, A reads
`stop`, and half a dosing instruction is displayed as a whole one. That is precisely what
`OUTPUT_TRUNCATED` exists to catch and the only place in the system it is catchable.

The residual window, between the flow completing and the seam's two reads, is closed in the safe
direction rather than argued away: `generate` sets `lastResponse = null` **before** it calls, so a
caller that loses the race reads `null`, and `null` refuses on both gates. Mutation M7 confirms the
clearing is load-bearing.

### 2.4 Identity: half an identity is none

The brief requires an absent or blank `model_id` **or digest** to be a MISMATCH. The seam's gate
(`servedModelMatchesSanitizer`) compares only the id, so the binding is where the digest is
enforced: `servedModelId()` returns `null` unless `model_id` and `model_sha256` are both present and
non-blank. This is not a substitution and does not violate that method's contract, which forbids
answering with **this build's own expectation**; it reports that the response carried no identity
worth comparing, which is defined to mean fail closed.

The envelope it defends against is the likely one, not a contrived one: the id implemented and the
digest not yet, which is what a partly-built service looks like on integration day.

---

## 3. The failure mapping, and its totality

Everything `POST /api/v1/slm/readback` can produce, onto exactly one `SlmEngineError`. The two
classifying functions are pure, total and Android-free, on `classifyKernelFailure`'s construction.

### 3.1 Transport (no response, so no status and no code)

| Condition | `SlmEngineError` |
|---|---|
| `SSLException` and every subclass | `SECURE_CONNECTION_FAILED` |
| `SocketTimeoutException` | `TIMEOUT` |
| `EOFException` | `MALFORMED_RESPONSE` |
| `InterruptedIOException` (bare: OkHttp's `callTimeout`) | `TIMEOUT` |
| `UnknownHostException`, `ConnectException`, any other `IOException` | `UNREACHABLE` |
| `RuntimeException` from the converter | `MALFORMED_RESPONSE` |
| `CancellationException` | rethrown, never classified |

**Branch order is the safety property, not a style choice.** Every type above extends `IOException`,
so an `else -> UNREACHABLE` placed above any of them silently absorbs it.

### 3.2 HTTP, by `code` (api-contract.md §11.3, every row)

| Code | Status | `SlmEngineError` |
|---|---|---|
| `SAMD-SLM-8020` not configured | 503 | `UNAVAILABLE` |
| `SAMD-ENC-4002` case not found | 404 | `PAYLOAD_REJECTED` |
| `SAMD-SLM-8014` PHI blocked | 422 | `PAYLOAD_REJECTED` |
| `SAMD-SLM-8015` circuit open | 503 | `UNAVAILABLE` |
| `SAMD-SLM-8010` backend cannot reach service | 502 | `UNAVAILABLE` |
| `SAMD-SLM-8011` backend read budget expired | 504 | `TIMEOUT` |
| `SAMD-SLM-8012` service rejected the payload | 422 | `PAYLOAD_REJECTED` |
| `SAMD-SLM-8004` model not loaded | 503 | `UNAVAILABLE` |
| `SAMD-SLM-8005` queue full | 503 | `UNAVAILABLE` |
| `SAMD-SLM-8006` service failed in generation | 502 | `ENGINE_ERROR` |
| `SAMD-SLM-8013` malformed envelope | 502 | `MALFORMED_RESPONSE` |
| `SAMD-SYS-9005` defect in the proxy | 500 | `ENGINE_ERROR` |

Fallback for no code or an unknown one: 401/403 and any other 4xx to `PAYLOAD_REJECTED`, 504 to
`TIMEOUT`, 503 to `UNAVAILABLE`, any other 5xx to `ENGINE_ERROR`, anything outside those to
`ENGINE_ERROR`. No "other" bucket that swallows a class.

### 3.3 200, at the envelope

| Condition | Result |
|---|---|
| `finish_reason` `stop` or `stop_sequence`, identity complete | the text, one chunk |
| `finish_reason` `length` or `error` | the text is relayed; the seam refuses `OUTPUT_TRUNCATED` |
| `finish_reason` absent or unrecognised | `finishReason()` null; the seam refuses `OUTPUT_TRUNCATED` |
| `model_id` or `model_sha256` absent or blank | `servedModelId()` null; the seam refuses `SERVED_MODEL_MISMATCH` |
| `text` absent | `MALFORMED_RESPONSE` |
| `text` empty string | legitimate, emitted as empty |
| body absent or not the declared shape | `MALFORMED_RESPONSE` |

### 3.4 Three mappings that are not the obvious one

**`SAMD-SLM-8010` is `UNAVAILABLE`, not `UNREACHABLE`.** It says the *backend* could not reach the
*service*. The device demonstrably reached the backend, because the backend is what answered.
`UNREACHABLE` on this device means "you have no signal", which is a wrong answer to a worker holding
a connected phone and invites exactly the wrong retry. Pinned by its own test and by M11.

**`EOFException` is `MALFORMED_RESPONSE`, not `UNREACHABLE`, and a test found this.** MEASURED: a
`200` with an empty body made Gson's reader throw `EOFException`, which Retrofit propagates as the
checked `IOException` it is, so it reached the catch-all and classified as `UNREACHABLE`. That would
have told a worker holding a connected phone they were offline, about a request that reached the
backend, was authenticated, ran, and left an `slm_call_log` row. The rule now encoded is that
`UNREACHABLE` means **no response at all**; once any of one has arrived, an unusable answer is
`MALFORMED_RESPONSE`. That is also the honest answer for the other producer of this exception, a
body truncated mid-flight. **Reading the branch order did not catch this. Running it did.**

**`SAMD-ENC-4002` and a bare `401` are both `PAYLOAD_REJECTED`, and both lose a distinction.** A
`404` means the case has not synced, which will fail identically forever until the sync path is
fixed; the kernel side has a dedicated `CASE_NOT_ON_SERVER` for it that drives distinct copy. A
`401` reaching the classifier has already been through `TokenAuthenticator`, so the refresh was
attempted and failed, and the worker's action is "sign in again" rather than "fix a build". This
vocabulary can express neither. `PAYLOAD_REJECTED` is true of both (unretryable unchanged, a person
must act) and is not the whole truth of either. Carried forward in §8; PR-7 is the change that would
feel it, because it writes the copy.

---

## 4. The flavor binding, MEASURED

**Before this PR:** no flavor bound an `SlmEngine`, because no implementation existed. MEASURED by
grep for `SlmEngine` across all of `app/src`: five files, of which the only production ones were
`SlmEngine.kt` (the declaration), `SlmReadbackUseCase.kt` (the holder) and `SlmScopeGate.kt` (which
matches on `SlmReadback`, not on the engine). No `@Binds`, no `@Provides`, no implementation, no
stub, in `src/main`, `src/dev`, `src/staging` or `src/prod`.

**After this PR:** `SlmNetworkModule` lives in `src/main/`, so dev, staging and prod all bind
`RemoteSlmEngine` against `POST /api/v1/slm/readback`. **No flavor source set contains any
`SlmEngine` implementation or binding.** MEASURED two ways: by a new source-scan assertion in
`SlmEngineIsUnreachableFromPresentationTest`, with its own non-vacuity guards and a positive
control, and by `assembleDevDebug`, `assembleStagingDebug` and `assembleProdDebug` all succeeding,
which means the Hilt graph resolves `SlmEngine` in each.

**Why no dev stub, stated as a decision.** This is deliberately the opposite of `MockBoundaryModule`'s
posture for `VitalsSource` and `KernelFallbackSource`. Those are flavored precisely so fabricated
clinical *values* cannot be reached outside dev. A stub readback engine would be the same hazard in
the same clothes, and worse: what it fabricates is clinical narrative that reads exactly like a real
answer, and it would satisfy the identity gate by construction by answering with whatever pin it was
compiled against, with the sanitizer's suppression counters honestly at zero. H-09 and H-13 are both
in the register because a plausible fabricated value reachable in a non-dev build is this project's
recurring failure mode. One binding for all three flavors means there is no build in which a
different thing answers. M16 pins it: adding a stub to `src/staging/` turns the suite red.

Nothing is reachable through any of this yet. There is no `SLM_READBACK_ENABLED` flag and no UI
(PR-7). The binding exists and is unreached.

---

## 5. Tests and mutation checks

**Test totals, all MEASURED at the final state of the branch.**

| Suite | Tests | Failures | Pre-existing failures |
|---|---|---|---|
| `:app:testDevDebugUnitTest` | 688 | 0 | 0 |
| `:app:testStagingDebugUnitTest` | 655 | 0 | 0 |
| `:app:testProdDebugUnitTest` | 655 | 0 | 0 |
| backend `pytest` (`ABDM_MODE=stub`) | 330 | 0 | 0 |

A baseline run of `:app:testDevDebugUnitTest` before any PR-6 edit exited 0, so there are no
pre-existing failures to separate out. The backend suite was run as
`ABDM_MODE=stub ./.venv/bin/python -m pytest -q` from `backend/core`, with `ABDM_MODE=stub` on the
command line, per CLAUDE.md and the S-5 finding that the local `.env` otherwise points the ABHA
tests at the live `dev.abdm.gov.in` gateway. No backend *code* was touched by this PR; the run is
insurance over the contract edit, not a check of a change.

`assembleDevDebug`, `assembleStagingDebug` and `assembleProdDebug` all succeed, which is the Hilt
graph check. One caveat worth recording: running all three `assemble` tasks in a single gradle
invocation produced a spurious `packageStagingDebug FAILED` that did not reproduce serially or on
retry. INFERRED to be a parallel-output conflict between the three package tasks, not a defect.

**Mutation checks: 18 applied, 18 RED, all restored byte-identical, suite green after.** Restore is
by content snapshot with a SHA-256 comparison, never `git checkout`, which would restore the
index's version and silently discard an uncommitted edit. A run producing no fresh JUnit XML is
scored `RED-NO-COMPILE`, not RED, so a mutation that breaks compilation cannot be counted as a
caught defect; none of the 18 hit that.

| # | Property broken | Verdict | Caught by |
|---|---|---|---|
| M1 | SSLException classified as offline | RED | `anSslFailureIsNotClassifiedAsOffline`, `everyEngineErrorValueIsReachable` |
| M2 | an absent digest passes the identity gate | RED | `anAbsentDigestIsNoIdentity...`, `aBlankDigestIsNoIdentity...` |
| M3b | an absent `model_id` answered with this build's own pin | RED | `anAbsentModelIdIsNoIdentity`, `aBlankModelIdIsNoIdentity` |
| M4 | an unrecognised `finish_reason` treated as `stop` | RED | 4 tests, incl. `anUnrecognisedFinishReasonIsNoFinishReason` |
| M5 | `finish_reason` `length` treated as `stop` | RED | `aTruncatedGenerationArrivesOverA200AndSaysSo` + 2 |
| M6b | the mutex queues instead of refusing | RED | `aSecondGenerationWhileOneIsInFlightIsRefusedAndNeverReachesTheNetwork` |
| M7 | the envelope is not cleared before a call | RED | `aFailedGenerationClearsThePreviousGenerationsEnvelope` |
| M8 | a fresh client instead of `newBuilder()` | RED | both derivation tests in `SlmHttpStackTest` |
| M9 | `callTimeout` not set | RED | `aCallBuiltFromThisClientCarriesTheWholeCallBudget` + 1 |
| M10 | an empty `200` body classified as offline | RED | `anEmptyBodyOnA200IsMalformed`, `everyTransportExceptionMapsToOneError` |
| M11 | `SAMD-SLM-8010` classified as `UNREACHABLE` | RED | `theBackendBeingUnableToReachTheServiceIsNotReportedAsTheDeviceBeingOffline` + 1 |
| M12 | the request states a model id that is not this build's pin | RED | `theRequestCarriesThisBuildsPinAndTemplateAndOmitsTheDeterminismFields` |
| M13 | the case id is not passed to the engine | RED | `the case id reaches the engine...` |
| M14 | `SECURE_CONNECTION_FAILED` collapsed into `ENGINE_UNREACHABLE` | RED | `each engine failure class refuses with its own reason...` |
| M15 | more than one chunk emitted | RED | `aSuccessfulGenerationEmitsExactlyOneChunk...` + 3 |
| M16 | a stub engine added to `src/staging/` | RED | `noFlavorSourceSetImplementsOrBindsTheEngine` |

Two of the first sixteen were blunter than their labels and were re-run precisely. **M3** as first
written mutated the `lastResponse == null` guard rather than the absent-`model_id` path, and went
red through the wrong test; M3b is the labelled property and is the row above. **M6** as first
written replaced the guard with `if (false)`, which left an `unlock()` on an unheld mutex and turned
24 tests red rather than the one; M6b queues instead of refusing, which is the actual defect being
defended against, and turns exactly one test red. The two original runs are not counted in the 18.

---

## 6. What a test caught that reasoning did not

Three things, all found by running rather than by reading, and one of them is a real defect.

1. **The `EOFException` classification.** A `200` with an empty body was classified `UNREACHABLE`,
   which would tell a connected worker they were offline about a request that reached the backend
   and left a log row. Documented at §3.4. This is the finding; the other two are corrections to my
   own assertions.
2. **The timeout budget's nesting is not total.** `read + connect` is 65 s against a 60 s call bound,
   so an assertion I wrote expecting the three to nest failed. The numbers are the brief's and are
   right; my claim about them was wrong. The residual 5 s window is now pinned and documented rather
   than smoothed over. §2.2.
3. **The seam's own taxonomy table caught the new enum value.** `SlmReadbackUseCaseTest` already
   asserts `SlmEngineError.entries.toSet() == cases.keys`, so adding `SECURE_CONNECTION_FAILED`
   turned it red until a row was added. That guard was written by PR-2 and worked exactly as
   intended on the first change that touched it, which is worth recording as a positive result: it
   is the same shape as the guards this project keeps finding *missing* elsewhere.

---

## 7. Deliberately not done

- **No `SLM_READBACK_ENABLED` flag, no UI, no ViewModel.** PR-7. `SlmEngineIsUnreachableFromPresentationTest`
  still passes, and its allowlist grew from two names to four deliberately, in this commit.
- **No audit rows on the device.** Step 9 of the seam is still a named seam with nothing behind it;
  it needs new `AuditAction` values and the backend enum mirror in one commit.
- **`docs/quality/risk-management-file.md` untouched.** Six hazard items pending sign-off.
- **No backend code changed.** The `401` branch §4.2.2 calls for is carried forward, not written.
- **Nothing pushed, nothing merged.**

---

## 8. Carry forward, not acted on

New from this PR:

- **`SlmCallOutcome` has no value for a rejected service credential**, so a wrong `SLM_SERVICE_TOKEN`
  is logged as the device's request being bad. Contract §4.2.2 states it in full. Needs an Alembic
  migration against the `enum_check` CHECK constraint, a device-facing error code distinct from
  `SAMD-SLM-8008`, and a `401` branch ahead of the generic `4xx` one in `services/slm.py`.
- **The device vocabulary cannot express "the case never synced" or "sign in again."** Both land in
  `ENGINE_REJECTED_INPUT`. The kernel side has `CASE_NOT_ON_SERVER` and `NOT_AUTHORIZED` for exactly
  these. PR-7 writes the copy and is the change that will feel the gap.
- **Contract §4.3's read bound does not fully achieve its own stated rationale.** 55 s is strictly
  inside 60 s, but connect plus read is 65 s, so more than 5 s spent connecting still lets the
  outermost layer classify the timeout. Pinned by a test; the numbers are settled and unchanged.
- **Nothing can verify §2.7.** Control-token wrapping is invisible on the envelope, so the first
  integration test against a real service needs a record whose expected readback is known and a
  reviewer who reads the text.

Carried from before, unchanged:

- Two Room migrations unrun for want of a device, and they are a merge gate.
- No corrective edit path for a rejected record.
- Six hazard items pending sign-off.
- The kernel proxy's `404` audit-row gap, standalone.
- The middleware fallback audit row that can never fire on ANY route, found in PR-4 and not swept.
- The `strings.xml` fork.
- The one-time re-push of pre-existing FAILED rows, owner-gated and unblocked.
- The GPU box's Docker filesystem at 100 percent, so the container path, GPU reservation and
  loopback publish are configured but unproven.
- Gemma 4 E2B measured at 9.551 GiB resident, which makes the on-device endgame conditional on
  quantisation and on fixing ASR residency first.
