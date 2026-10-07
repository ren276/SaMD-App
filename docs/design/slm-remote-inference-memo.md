# SLM remote-inference design memo

**Status:** DESIGN MEMO. Read-only pass. No code, no build, no model load, no file changed in
either repository except this memo. `docs/quality/risk-management-file.md` was not touched.

**Date:** 2026-09-18
**Repo under design:** `/media/sandesh/extra-ssd/AndroidWork/SaMDApp` @ `master` `a219da9`
**Serving repo:** `/media/acps/twoTBDrive/finetuninggemma4`, on the GPU machine, not on this machine.

**Inputs, both read in full before anything was written:**

1. `/home/sandesh/Downloads/SLM_SAMPLE_APP_AUDIT.md`, the read-only audit of the serving repo at
   `main` @ `be6cc04`, dated 2026-09-09. Ground truth for the serving side. Its findings are not
   re-derived here.
2. `docs/design/perf-audit-2026-09-18.md`, closed and amended. Ground truth for the device and
   `backend/core` side. F6B-01, F6B-02, F6B-03, F6C-01, F6C-03, F6C-05 all bear on this memo.

**Method.** Static read of SaMDApp source, docs and the risk register, plus the two input reports.
Every claim below is tagged **(MEASURED)** when it is read directly out of a file or a report, or
**(INFERRED)** when it is reasoning over those reads. Where something does not exist, this memo
says "does not exist". No credential, `.env`, `local.properties`, keystore, docker secret or
token file was opened, and none is named by value anywhere below.

---

## P1. Prerequisite status: NOT RUN, and it cannot be run from this session

**(MEASURED)** The serving repo is not reachable from this machine. `/media/acps` does not exist
here; `/media/` contains `lokesh`, `pi`, `saiyam`, `sandesh` only. The SLM runs on a separate,
more capable GPU machine. What is present on this machine is the audit report, copied to
`~/Downloads/`.

Consequence, stated plainly rather than softened: **every serving-side claim in this memo is
as-of-audit, not as-of-today.** They are sourced from `be6cc04` on 2026-09-09. The three checks
the brief asked for, `git rev-parse HEAD`, `git status --short`, and whether
`models/gemma-4-E2B-pharma-merged/` still does not exist, remain open. They are cheap and belong
in the first build session on the GPU box, before PR-1 is opened, not before this memo is read.

If HEAD has moved, the findings most likely to have gone stale are F1 (the zero adapter), the
merged-directory absence, and everything in the audit's section 4.1 and 4.2 about the request and
response schema, because those are the files a well-intentioned fix would touch first.

---

## Section 0: what is actually being integrated

### 0.1 The served model is stock `google/gemma-4-E2B-it`

**(MEASURED, audit F1 and section 3.1.)** The adapter that the serving repo would load is a
mathematical identity for text:

- All 232 `lora_B` matrices in `models/gemma-4-E2B-pharma-v2/adapter_model.safetensors` have
  `max(abs(value)) == 0.0` exactly. LoRA computes `delta_W = B @ A`, so `B @ A = 0` for every
  adapted module and `W + delta_W = W`.
- All 464 adapter tensors resolve to `audio_tower.*` (240) and `vision_tower.*` (224). **Zero
  tensors target the text decoder.** A text-only request never routes through those towers, so the
  adapter is not even on the compute path.
- `trainer_state.json` records `grad_norm: 0.0` at every one of the 12 logged steps. `eval_loss`
  is identical at both evaluations, 5.30464506149292. Loss drifts upward from 5.080 to 5.363.
- Cause, at `training/train_gemma4_pharma_v2.py:91`: `target_modules=["linear"]`. In this
  architecture only the vision and audio towers have submodules literally named `linear`.

**There is no text-decoder fine-tune.** Loading `gemma-4-E2B-pharma-v2` over
`google/gemma-4-E2B-it` produces text generation indistinguishable from the unmodified base
instruction-tuned model. Any domain competence, format adherence or safety property attributed to
"the fine-tune" is a property of stock Gemma 4 E2B IT.

Two consequences the audit already drew and this memo carries forward **(MEASURED)**: the
`[CLINICAL ASSESSMENT]` section grammar the sample README documents was never learned, and
`models/gemma-4-E2B-pharma-v2/README.md:117` publishes "Final Training Loss: 5.248" without noting
that loss never decreased.

### 0.2 What SaMDApp's own documents claim, quoted with file:line

The brief asked for every SaMDApp claim that a fine-tuned, domain-trained or pharma-specific SLM
exists or will be served. The search was `grep -rniE "fine-tun|finetun|pharma-specific|
domain-trained|domain-adapted|LoRA|gemma|E2B|remote inference|slm server|slm service|served model"`
over `docs/` and `PROGRESS.md`.

**Result, stated plainly: no SaMDApp document claims a fine-tuned or pharma-specific SLM exists or
will be served. Those claims do not exist in this repo.** **(MEASURED)**

What the search did surface is a different and independent inaccuracy, and it needs correcting for
the same reason. Two lines assert that an on-device SLM feature is **built and shipped**, when the
engine interface has no implementation anywhere.

**Claim 1.** `docs/product-and-development-report.md:125`, quoted verbatim:

> "**On-Device Small Language Model (SLM) Inference Feature:** Incorporates an on-device Google
> MedGemma 1.5-4B-IT model running via Google LiteRT. Features a custom C++/Kotlin Stream Sanitizer
> that strips internal reasoning tokens and control tokens (`<unused0>..<unused6241>`,
> `[multimodal]`), ensuring clean clinical consultation summaries and approved record readbacks."

**Claim 2.** `docs/product-and-development-report.md:303`, quoted verbatim:

> "- [x] **On-Device Small Language Model (SLM):** LiteRT MedGemma 1.5-4B-IT edge inference engine
> with C++ control-token stream sanitization."

**Why both are inaccurate today (MEASURED):**

- `SlmEngine` (`app/src/main/java/com/example/samdapp/domain/slm/SlmEngine.kt:106`) is an
  interface. `grep -rn "SlmEngine" app/src` returns matches only in
  `app/src/test/.../SlmReadbackUseCaseTest.kt` and in that file's own KDoc. **No implementation
  exists in `app/src/main`. No Hilt module binds it.** The interface KDoc says so itself at
  `SlmEngine.kt:79-83`.
- `grep -rniE "litert|mediapipe|genai" gradle/libs.versions.toml app/build.gradle.kts` returns
  **zero matches.** There is no LiteRT dependency in the build.
- The sanitizer is not C++. `SlmStreamSanitizer.kt` is 319 lines of Kotlin, in
  `app/src/main/java/com/example/samdapp/domain/slm/`. There is no native source for it.
- `SLM_READBACK_ENABLED` does not exist. `grep -n "SLM" app/src/main/java/com/example/samdapp/
  config/FeatureFlags.kt` returns nothing. The flag named in the guardrail memo section 8 has not
  been added.
- No readback UI exists. `grep -rli "readback" app/src/main/java/com/example/samdapp/presentation/`
  returns nothing.

**What is accurate and should stay.** `PROGRESS.md:4602` lists "the MedGemma / Gemma-3-4B-IT
on-device SLM assistant workstream" under **Parked** work that is "not scheduled and not
authorized" **(MEASURED)**. `docs/quality/risk-management-file.md:50`, H-22's mitigation column,
says "**Design-stage only, no code exists.**" **(MEASURED)**. Both are correct. The
product-and-development report is the only document out of step, and it is out of step in the
direction that overstates.

**Correction list, for a controlled-docs pass that is not this memo's to make:**

| # | File:line | Claim | Correction needed |
|---|---|---|---|
| C-1 | `docs/product-and-development-report.md:125` | on-device MedGemma running via LiteRT, with a C++ sanitizer | No engine is bound, no LiteRT dependency is in the build, the sanitizer is Kotlin. Restate as designed-and-gated, not shipped. |
| C-2 | `docs/product-and-development-report.md:303` | checkbox marked `[x]` for a LiteRT MedGemma edge inference engine | The box is not earned. Same correction as C-1. |

### 0.3 The model-identity problem this memo actually has to solve

This is the finding of section 0, and it is larger than the zero adapter.

**(MEASURED)** Three different model families are named across the two repositories, and the
guardrail work already built is keyed to exactly one of them:

| Where | Model string | Source |
|---|---|---|
| Serving repo, everywhere | `google/gemma-4-E2B-it` | audit section 1.1 |
| SaMDApp sanitizer, verified against its tokenizer | `google/medgemma-1.5-4b-it` | `PROGRESS.md:5010`, `SlmStreamSanitizer.kt:36` |
| SaMDApp parked-work list | "MedGemma / Gemma-3-4B-IT" | `PROGRESS.md:4602` |

**Why this is load-bearing and not bookkeeping (MEASURED, then INFERRED).** `SlmStreamSanitizer` is
a fixed literal set, not a pattern matcher. Stage 3b-0 verified that set against
`google/medgemma-1.5-4b-it`'s `tokenizer.json`: the `<unused*>` range is `<unused0>..<unused6241>`
in two disjoint id blocks (6 to 104, and 256001 to 262143), reserved id 5 is `[multimodal]`, and
the reasoning channel's delimiters are `<unused94>` (id 100) and `<unused95>` (id 101)
(`PROGRESS.md:5013-5040`).

The audit records a completely different token grammar for Gemma 4 **(MEASURED, audit section
2.2)**: `<bos>`, `<|turn>ROLE\n`, `<turn|>`, the thinking channel `<|channel>thought ...
<channel|>`, tool blocks `<|tool>` and siblings, media placeholders `<|image|>`, and 24 added
special tokens. `<start_of_turn>` and `<end_of_turn>` are recorded as **not present in that
tokenizer's vocabulary at all**.

**(INFERRED, and I am confident in it.)** Serving `gemma-4-E2B-it` while shipping the current
sanitizer means the sanitizer's entire literal set matches nothing the model emits, and the
model's actual reasoning channel, `<|channel>thought ... <channel|>`, passes straight through to a
worker's screen next to an approved prescription. That is precisely the fail-open condition
stage 3b-0 was opened to close, reintroduced by a model swap rather than by a code change. The
sanitizer's 23 tests would stay green throughout, because they test the MedGemma vocabulary.

This is an F6C-01-shaped defect in advance. F6C-01 found five model-version strings with no two the
same and a runtime serving `toy-v0.6-observed-glucose-4tier` while
`docs/quality/risk-management-file.md:46` performs hazard analysis against
`v0.1-xgb-venn-abers` **(MEASURED, perf audit F6C-01)**. Two model services with the same defect is
a pattern, not an accident, and this memo's section 2 treats it as one.

### 0.4 The prior, argued: proceed on the stock base model, or block on a retrain

**The prior as stated in the brief:** proceed with the stock base model rather than blocking on a
retrain, because SaMDApp's output grounding gate refuses ungrounded text whole, so model domain
competence is not the controlling safety property for a readback of a finalized,
physician-approved record.

**Verdict: the prior is right for the worker tier and wrong for the physician tier, and the
difference is one `if`.** Detail follows.

**Where the prior holds (MEASURED).** For a worker-tier viewer the pipeline is: the record is a
whitelist snapshot with five fields and no identity (`ApprovedRecordSnapshot.kt:34-40`), the input
scope gate refuses before the engine is called (`SlmReadbackUseCase.kt:178-180`), and the output
grounding gate suppresses the whole generation when it introduces any drug-shaped or
numeral-shaped token absent from the record (`SlmScopeGate.kt:155-161`, called at
`SlmReadbackUseCase.kt:203`). A model with no domain knowledge that hallucinates "amoxicillin
500 mg" into a readback of a paracetamol prescription is caught by token containment, not by the
model knowing anything. On that path, domain competence genuinely is not the controlling property.
The prior is sound, and it is sound for a structural reason rather than a hopeful one.

**Where the prior fails, and this is the sharp edge (MEASURED).** `SlmReadbackUseCase.kt:178` and
`:203` both read `if (!openTier)`. A `PHYSICIAN`-tier viewer bypasses **both** gates. The KDoc at
`:127-135` says this is deliberate and explains why: grounding a physician's open query against a
record they did not ask about "would refuse every open query, cancelling D4 by the back door". For
that tier the only controls that run are the sanitizer, the no-editing rule and single-turn.

**(INFERRED.)** For the physician tier, therefore, **model domain competence is the controlling
safety property**, because nothing else is checking the clinical content. Serving a model with no
medical fine-tune, whose own harness finding F4 is that it refuses nothing and whose F7 is that it
answered a bare drug-interaction question in full, into a tier with no output gate, is a different
proposition from serving it into the worker tier. H-22's own text calls the untethered case "the
H-17 harm arriving through a new door" **(MEASURED, risk file line 50)**.

**A second, narrower limit on the prior (MEASURED, then INFERRED).** `outputIsGrounded` checks
token containment only: drug-shaped tokens against the medication lines, numerals against the whole
record text (`SlmScopeGate.kt:155-161`). It does not and cannot catch an ungrounded **clinical
assertion** built entirely from words already in the record. "You do not need to come back" and
"stop taking this if you feel better" contain no drug name and no numeral. Both would pass. That is
not an argument against the gate, which is doing what it was scoped to do, but it is an argument
that grounding is a containment control and not a correctness control, and the memo should not be
read as claiming more.

**Recommendation, therefore, and it is a narrowing rather than a rejection:**

1. **Proceed on the stock base model.** Do not block on a retrain. A retrain of this artifact is
   a multi-week exercise with no validated dataset behind it: the serving repo's training data is
   584 synthetic records generated from hand-authored Python dicts with zero citations, against
   documented claims of 2,164 **(MEASURED, audit section 3.1)**. Blocking on that retrain buys
   nothing that the gate does not already buy for the worker tier.
2. **Scope the first build to the WORKER tier only.** The physician-tier open-query path is a
   separate decision with a separate evidence requirement, and it should not ride in on the back of
   a readback PR. This is a change to the guardrail memo's section 5.3 posture in sequencing only,
   not in substance.
3. **Decide the served artifact identity before PR-1, not during it.** Whichever model is served,
   the sanitizer vocabulary must be re-verified against **that artifact's own tokenizer**, and the
   artifact id has to be on the wire (section 2). Serving `gemma-4-E2B-it` behind a sanitizer built
   for `medgemma-1.5-4b-it` is a silent fail-open, and it is the single most likely way this
   integration goes wrong.

**What would change the answer to "block".** Any one of: opening the physician tier in the same
release; widening the snapshot beyond the five whitelisted fields so that grounding has more
surface than it can check; or a decision to let the SLM answer anything other than a readback. Each
of those moves domain competence back into the controlling position, and at that point a stock
general-purpose model with no medical evaluation artifacts of any kind is not defensible.

---

## Section 1: the PHI boundary. The blocker.

### 1.1 The existing classifier forwarding path, end to end

This is the precedent to extend. Every hop, with anchors.

**Hop 1, device to backend (MEASURED).**
`app/src/main/java/com/example/samdapp/data/remote/api/KernelApiService.kt:22-23`:

```kotlin
@POST("api/v1/assess")
suspend fun assess(@Body request: KernelAssessmentRequestDto): ApiEnvelopeDto<KernelAssessmentResponseDto>
```

It rides the shared backend OkHttp client, `di/NetworkModule.kt:96-104`: connect 10 s, read 30 s,
write 30 s, dev-host interceptors, `BearerInterceptor`, `TokenAuthenticator`, logging interceptor.
**No `callTimeout`** (F6B-03: zero `callTimeout` hits across all of `app/src`).

**Hop 2, the backend route (MEASURED).** `backend/core/app/api/v1/kernel.py:32-54`. Auth is
`ActiveWorkerDep` (`app/deps.py:99`), a bearer-authenticated active worker. The route is thin: it
sets `request.state.audit_action = AuditAction.REQUEST_COMPLETED.value` at `:43` to suppress the
middleware's generic audit row, because the service writes its own specific row on every path, then
delegates. The module docstring at `:3-6` records the deliberate absence of a role guard: all four
roles may submit, because the output is non-autonomous and gated by the doctor AGREE/MODIFY/REJECT
step.

**Hop 3, the service contract (MEASURED).** `backend/core/app/services/kernel.py`, docstring
`:3-16`, eight steps:

1. Resolve and facility-scope the case (`_resolve_case_record`, `:86-94`). A cross-facility case is
   a 404, not a 403, because confirming existence would leak.
2. PHI denylist guard (`app/adapters/kernel/phi_guard.py:64`, `assert_no_identity_fields`, called
   at `services/kernel.py:206`). 28 denied key names at `phi_guard.py:32-61`, including
   `full_name`, `abha_number`, `mobile_number`, `date_of_birth`, `pincode`, `village`, `block`.
   Deliberately redundant with the Pydantic `extra="forbid"` models, and the docstring at
   `phi_guard.py:14-18` explains why: `extra="forbid"` cannot catch a future edit that adds a
   denied name **as a declared field**.
3. HMAC pseudonym substitution (`app/adapters/kernel/pseudonym.py:27-34`):
   `case_token = HMAC-SHA256(case_record_id, key)[:16]`. Its own docstring at `:28-30` is careful
   about what it is: "a wire-boundary control that keeps the raw case primary key off the kernel
   network hop. NOT de-identification".
4. Circuit breaker check (`services/kernel.py:214-220`). Open circuit fails fast with no network
   call.
5. Forward, no retry (`app/adapters/kernel/client.py:47-56`). The module docstring at `:15-17` is
   explicit: "A retried inference call is a second inference call, and kernel_call_log must not
   show one clinical event as two."
6. Restore the real `case_record_id` into the response before returning.
7. Exactly one `kernel_call_log` row and one `audit_events` row, whatever happened.
8. On success only, one `kernel_assessments` row holding the response verbatim.

**Hop 4, timeout policy (MEASURED).** `client.py:32-44` builds one `httpx.AsyncClient` for the app
lifetime, from the FastAPI lifespan (`app/main.py:70-72`), with
`httpx.Timeout(connect=..., read=..., write=read, pool=connect)`. The values come from settings and
were not read, per the credential rule. One client, created once; `client.py:12-13` records why a
per-request client is wrong.

**Hop 5, failure classification (MEASURED).** `services/kernel.py:222-281`, eight distinct
outcomes, each mapped to a `KernelCallOutcome` and an `ErrorCode`:

| Condition | Outcome | Breaker | Anchor |
|---|---|---|---|
| Denied key present | `PHI_REJECTED` | not counted | `:206-212` |
| Breaker open | `CIRCUIT_OPEN` | not counted | `:214-220` |
| `ConnectTimeout` / `ConnectError` | `UNREACHABLE` | `record_failure()` | `:224-231` |
| `ReadTimeout` | `TIMEOUT` | `record_failure()` | `:232-239` |
| other `HTTPError` | `UNREACHABLE` | `record_failure()` | `:240-247` |
| 5xx | `KERNEL_ERROR` | `record_failure()` | `:249-256` |
| 4xx | `PAYLOAD_REJECTED` | **not counted, deliberately** | `:258-267` |
| unparseable body | `MALFORMED_RESPONSE` | `record_failure()` | `:269-281` |

The 4xx comment at `:259-261` is the reasoning worth carrying: the kernel answered correctly that
one payload is bad, and counting that toward the breaker "would open the circuit and punish every
subsequent caller for one bad request."

**Hop 6, where the audit rows are written (MEASURED).** `KERNEL_CALL_FAILED` is written inside
`_fail` (`services/kernel.py:101-161`): a `KernelCallLog` row at `:124-141` carrying
`request_id`, `case_record_id`, `case_token`, `worker_id`, `facility_id`, `endpoint`,
`kernel_base_url`, `input_sha256`, `outcome`, `error_code`, `http_status` and timings, then
`audit_service.append(..., action=AuditAction.KERNEL_CALL_FAILED.value, ...)` at `:142-157` with a
payload of `{endpoint, outcome, error_code}` only. `KERNEL_CALL_FORWARDED` is written in
`_record_success` at `:380-395` with a payload of `{endpoint, outcome: "SUCCESS"}`. Both enum
values live at `backend/core/app/models/enums.py:81-82`.

**Hop 7, and this is the part that matters most to copy (MEASURED).** Both writes go through
`write_out_of_band` (`:159`, `:397`), in their own session, committed immediately. The module
docstring at `:29-40` gives the reason: `session_scope` rolls back the whole request transaction on
any exception, so a `kernel_call_log` row for a FAILED call "cannot be left in the request session
(the SamdError about to propagate would take it with it)". This is the same trap the project's
`CLAUDE.md` records as having caught the `_fail()` rollback bug in the Phase 5 ABDM adapter, with
three prior instances.

**Hop 8, what the device does with a failure (MEASURED, perf audit F6B-02 hop 7).**
`GenerateKernelReportUseCase.kt:250-255` catches `Exception` blanket and returns null, collapsing
every failure class onto `InferenceSource.UNAVAILABLE`. F6B-02 names that as the finding: a
recoverable, specific, actionable error converted into a misleading infrastructure error, in one
`catch` block. **The SLM path must not reproduce it.** Section 3 is where that is addressed.

**Auth on the backend-to-kernel hop: does not exist (MEASURED).** `build_kernel_client`
(`client.py:32-44`) sets `base_url` and `timeout`. No headers, no auth parameter, no token.
`call_kernel` (`:47-56`) posts a JSON body and nothing else. The kernel is reached over plain
`KERNEL_BASE_URL` on the LAN. `backend/docker-compose.yml:3-4` states the topology deliberately:

> "The XGBoost kernel is deliberately NOT a service here. It is an existing separate process on
> the LAN and stays that way in dev; the backend reaches it through KERNEL_BASE_URL in .env."

This matters for section 1.4: the precedent that would be extended carries **no authentication on
the model hop today**, and extending it unchanged would carry that gap onto a hop that transports
clinical narrative rather than eight numeric features.

### 1.2 Every field of the case snapshot that reaches the model as text

This is the memo's most important paragraph, so it is a list and not a summary. Source:
`buildPrompt` at `app/src/main/java/com/example/samdapp/domain/slm/SlmReadbackUseCase.kt:236-258`,
reading an `ApprovedRecordSnapshot` built at `ApprovedRecordReader.kt:60-68`. **(MEASURED
throughout.)**

The prompt is exactly this, in this order:

1. **Instruction line 1**, constant: `"You are restating a record a doctor has already approved. Use plain language."` (`:245`)
2. **Instruction line 2**, constant: `"Do not add any medical information that is not written below."` (`:246`)
3. **Literal header** `"APPROVED RECORD"` (`:248`)
4. **`snapshot.kernelDecision.name`**, on the line `"Doctor's decision on the assessment: "` (`:249`).
   One of three values: `AGREE`, `MODIFY`, `REJECT` (`domain/model/Prescription.kt:33`). This is the
   physician's verdict on the classifier output. Not PHI on its own; clinical and attributable.
5. **`snapshot.diagnosis`**, on the line `"Diagnosis: "`, or the literal `"(not recorded)"` when
   null (`:250`). This is **free physician-authored text**. Its origin is
   `ReportFormatter.kt:124-128`: it is `prescription?.diagnosis` verbatim, except that under an
   active prescription gate with no committed decision it is replaced by the literal
   `"Awaiting physician review"`. **Free text is the field with the widest content range in this
   prompt.** A physician can type anything into it, including a name, a village or a phone number.
   Nothing strips or validates it, at any layer, on this path.
6. **`snapshot.medicationLines`**, each rendered as `"- $it"` under the header `"Medicines:"`
   (`:251-252`), or the literal `"(no medicines are listed on this approved record)"` when the list
   is empty (`:238-242`). Each line is produced by `ReportFormatter.formatMedicationLine`
   (`ReportFormatter.kt:217-224`) and has exactly this composition:
   - `line.genericName`
   - `line.brandName` in parentheses, when non-blank
   - `line.strength`
   - `line.route`
   - `line.frequency` (written out in full; `BANNED_FREQUENCY_TOKENS` Latin abbreviations are a
     `require` failure, REQ-RX-02)
   - `line.duration`
   - `line.quantity`
7. **`snapshot.suggestsReferral`**, rendered as `yes` or `no` on the line
   `"Referral suggested: "` (`:253`). Derived at `ReportFormatter.kt:145` from
   `severityTriggered || doctorRejectedKernel`. A boolean, and it leaks one bit about severity.
8. **Literal header** `"QUESTION"` (`:255`)
9. **`invocation.question`**, the worker's own typed text, trimmed, bounded at 500 characters
   (`MAX_QUESTION_CHARS`, `SlmScopeGate.kt:30`). **Free text, typed by a human, in a prompt
   position.** Bounded in length, filtered for phrasing and intent for the worker tier, unfiltered
   for the physician tier.

**What does NOT reach the model, and why that is structural rather than lucky (MEASURED).**

- `snapshot.caseRecordId` is a field of the snapshot and is **not interpolated into the prompt**.
  Read `buildPrompt` again: it is never appended. The correlation token stays on the device side of
  the seam.
- `ApprovedRecordSnapshot` has no field of type `Patient` and no field of type
  `ReportPatientBlock`. Its KDoc at `ApprovedRecordSnapshot.kt:14-21` enumerates the exclusions:
  `fullName`, `guardianName`, `address`, `mobileNumber`, `abhaNumber`, `abhaAddress`, chief
  complaint, ailment lines, vitals, raw kernel and evaluate output, every attachment, and age/sex.
  Same construction guarantee as `KernelPayload` for H-10.
- `ApprovedRecordReader`'s signature takes a `caseRecordId: String`, never a `Patient`
  (`ApprovedRecordReader.kt:39`), so identity cannot cross by mistake.
- The `ReportAudience` is derived at the reader from the live session and is **not a parameter**
  (`ApprovedRecordReader.kt:43`, `:81-82`). A worker-tier viewer's snapshot is assembled from the
  WORKER-audience report, so PRIVATE ailments are already redacted before the snapshot exists.

**The honest summary of what would cross a network hop (INFERRED from the above):** a physician's
free-text working diagnosis, a full prescription line set with generic names, brand names,
strengths, routes, frequencies, durations and quantities, a three-valued decision code, one
referral bit, and a worker's free-text question, up to a 6000-character total prompt budget
(`MAX_PROMPT_CHARS`, `SlmScopeGate.kt:39`). **That is clinical PHI under DPDP.** It is not
identified PHI, and the distinction is real and worth keeping, but a diagnosis plus a prescription
is health data about a data principal, and the guardrail memo's section 10.1 already frames the
regime correctly: DPDP purpose limitation binds what the record may be **used for**, not only who
may see it.

### 1.3 The claim this breaks, and it must be said before the options

**(MEASURED.)** The existing design's localisation posture is stated at guardrail memo section
10.1: "**Localisation, satisfied by construction.** On-device inference means the record does not
leave the device at all. That is the strongest possible localisation posture."

**(MEASURED.)** Section 10.3 makes it a flag-flip precondition, on the ASR precedent: a bytecode
scan for a network-capable path, `txDelta` and `rxDelta` of 0 bytes across a full generation, a
StrictMode run recording no network call on the inference path, and an airplane-mode witness on
real arm64 hardware. `PROGRESS.md:5084` lists that egress proof as one of three open preconditions
of the `SLM_READBACK_ENABLED` flip.

**(INFERRED, and unavoidable.)** Remote inference does not weaken that claim, it **deletes** it.
`txDelta = 0` becomes structurally impossible. Section 10.3's evidence set cannot be produced for a
remote engine, in principle, not merely in practice. Any remote-inference decision is therefore
also a decision to retire section 10.3 and replace it with a different proof obligation: not "no
bytes left the device" but "these bytes left the device, to this host, over this transport, and
here is the audit row proving it". That replacement is a risk-file change, and section 1.6 drafts
it.

This is the single largest consequence of the brief's question and it is not a detail. Do not let
it pass unstated in a build PR.

### 1.4 Option A versus Option B

**(A) device to `backend/core` to SLM container.**
**(B) device to SLM container directly.**

| Axis | (A) via backend/core | (B) direct |
|---|---|---|
| **Auth surface** | Reuses `ActiveWorkerDep` bearer auth with `TokenAuthenticator` refresh, already shipped and already tested. Zero new credential to distribute to devices. The device holds one backend credential, as today. **(MEASURED)** | Needs a **new** auth mechanism on a service that today has none. Either a second credential on every device, or the backend token validated by a service that cannot validate it without reaching the backend anyway. **(INFERRED)** |
| **TLS on a LAN** | The device-to-backend hop already has a TLS posture and a `REQUIRE_HTTPS` setting (`backend/docker-compose.yml:35` sets it `false` for dev only). The backend-to-SLM hop is one LAN hop under operator control, and can be pinned to a private network in compose. **(MEASURED)** | The device-to-SLM hop is the LAN hop, over a PHC network, to a container currently binding `0.0.0.0:8000` with `usesCleartextTraffic="true"` on the client side. **(MEASURED, audit section 4.3)** |
| **Audit coverage** | `kernel_call_log` + hash-chained `audit_events` + `write_out_of_band` already exist and already work for exactly this shape of call. Adding SLM outcomes is adding enum values and a table, not inventing a mechanism. **(MEASURED)** | The SLM service has **no audit log of any kind** (audit section 4.3: "No request or response is logged anywhere; there is no audit trail of what was asked or answered"). Audit would have to be built from nothing, on the serving side, outside the hash chain. **(MEASURED)** |
| **Timeout policy** | Two bounded hops with independent budgets, and the backend already owns a per-endpoint circuit breaker. **(MEASURED)** | One hop, and the device stack has no `callTimeout` on any client (F6B-03). A dribbling response is unbounded. **(MEASURED)** |
| **PHC network behaviour** | The device already reaches the backend and already fails honestly when it cannot. One reachability model, not two. **(INFERRED)** | Two independent reachability models on a rural link. The device can reach the backend but not the SLM host, or the reverse, and the UI must express four states instead of two. **(INFERRED)** |
| **What a CDSCO reviewer sees** | One egress point for clinical narrative, auditable, facility-scoped, with a PHI guard and an outcome vocabulary already in the register. The answer to "where does patient narrative travel" is one paragraph and one table. **(INFERRED)** | Two egress points, one of which has no auth, no audit and no PHI guard. **(INFERRED)** |

**Recommendation: (A), and it is not close.**

**(A) is not infeasible.** The precedent is complete: `backend/core` already proxies a model
service over the LAN, with facility scoping, a PHI denylist, a pseudonym substitution, a circuit
breaker, an eight-value outcome vocabulary, out-of-band audit writes and a no-retry rule. Every one
of those is directly reusable. The work is a second proxy alongside the kernel proxy, not a new
architecture. **(INFERRED, from the kernel path read in 1.1.)**

The one real cost of (A) is latency: two hops instead of one, on a link that F6B-03 already shows
is thin. **(INFERRED.)** Section 4 argues that this cost is close to irrelevant here, because the
grounding gate already withholds every token until generation completes, so a streaming-latency
argument for (B) does not exist.

**One honest caveat on (A) (MEASURED).** The backend-to-kernel hop has no auth today, and copying
that shape unchanged would give the SLM hop no auth either. (A) is the right topology; it does not
excuse inheriting that particular gap onto a hop carrying free-text diagnosis and prescription
lines. Section 1.5 makes it a requirement rather than an inheritance.

### 1.5 The sample service's defects under (A): which become requirements, which stop mattering

The sample service today, all **(MEASURED, audit section 4.3)**: no auth, no rate limit, no audit
log, `allow_origins=["*"]` with `allow_credentials=True`, binds `0.0.0.0:8000` published on all
interfaces, and the sample app sets `android:usesCleartextTraffic="true"`.

**Non-negotiable under (A), because backend/core cannot compensate for them:**

| # | Requirement | Why backend/core cannot cover it |
|---|---|---|
| R-1 | **Bind to a private network, not `0.0.0.0` on all interfaces.** In compose, an internal network with no `ports:` publication. | Edge ownership is about who can reach the port. A published port is reachable by anything on the PHC LAN regardless of what the backend does. |
| R-2 | **Auth on the backend-to-SLM hop.** A shared secret header at minimum, checked by the service. | The backend is the only legitimate caller, but nothing enforces that without a check on the callee. This is the one place where copying the kernel precedent exactly would be wrong. |
| R-3 | **Per-request audit on the serving side, at least a generation id, model id, template version, token counts and finish reason.** | The backend's audit row records that a call happened and what it returned. It cannot record what the engine did internally, and an incident investigation needs both halves. |
| R-4 | **A rate limit or a concurrency bound on the service.** | The backend can bound its own concurrency but cannot stop a second caller. R-1 reduces the population of possible callers; it does not bound the one caller's request rate. |
| R-5 | **Model load at startup, with a health endpoint that reports load state honestly.** | The backend's circuit breaker can only react to failures. A service that reports healthy while unloaded turns a fast failure into a slow one. |

**Stop mattering under (A), because backend/core owns the edge:**

| # | Sample defect | Why it stops mattering |
|---|---|---|
| S-1 | `allow_origins=["*"]` with `allow_credentials=True` | CORS is a browser control. The only caller is a server-side `httpx` client, which does not enforce CORS and does not send an `Origin`. Under (A) with R-1 applied, this is dead configuration. **Remove it anyway**, because dead permissive configuration is what becomes live permissive configuration when someone later publishes the port. |
| S-2 | No TLS to the device | There is no device-to-SLM hop under (A). The device-to-backend hop has its own TLS posture. |
| S-3 | `usesCleartextTraffic="true"` in the sample app's manifest | The sample Android app is not integrated. SaMDApp's own manifest posture is untouched by this design. |
| S-4 | OpenAI-shaped `/v1/chat/completions` surface and its two convenience wrappers | The contract in section 2 replaces it. Nothing in SaMDApp calls that shape. |

### 1.6 Hazard row, drafted here. NOT written to the risk file.

The register's last id is **H-27** **(MEASURED)**. `H-11.C1` and `H-27` are PROPOSED and awaiting
operator sign-off; neither is touched by this memo. The next free id is **H-28**. Column order
matches the existing table: `| ID | Hazard / hazardous situation | Potential harm | Sev* | Prob* |
Risk controls implemented | Residual / open work |`.

**DRAFT, not written. Proposed row H-28:**

> | **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-18, SLM remote-inference design memo (`docs/design/slm-remote-inference-memo.md`) section 1** H-28 | Approved-record clinical narrative leaves the device to a remote generation service | The SLM readback design's localisation posture (guardrail memo section 10.1, "on-device inference means the record does not leave the device at all") is retired by remote inference. What crosses the hop is the physician's free-text working diagnosis, the full prescription line set (generic name, brand name, strength, route, frequency, duration, quantity), the three-valued `kernelDecision`, the referral bit and the worker's free-text question, up to a 6000-character prompt. This is clinical health data about a data principal under DPDP 2023 even though identity fields are structurally excluded (`ApprovedRecordSnapshot` carries no `Patient`-typed field and the `caseRecordId` is not interpolated into the prompt). The harm is a DPDP purpose-limitation and security-safeguard exposure on a hop that the existing design's evidence set (section 10.3: bytecode scan, `txDelta`/`rxDelta` of 0, StrictMode, airplane-mode witness) was written to prove could not exist. | High | Med | **Nothing is built. This row is a design-stage entry.** Proposed controls, all from this memo's section 1: topology (A), device to `backend/core` to SLM container, so there is exactly one egress point for clinical narrative and it is the one already carrying `/api/v1/assess`; reuse of the shipped `ActiveWorkerDep` bearer auth on the device hop; an authenticated backend-to-SLM hop (R-2), which the existing kernel hop does **not** have; the SLM container bound to a private compose network with no published port (R-1); the existing `assert_no_identity_fields` denylist applied to the outbound body, extended for this payload shape; per-call `audit_events` rows through `write_out_of_band` on the `KERNEL_CALL_FORWARDED`/`KERNEL_CALL_FAILED` precedent, with measured metadata only and never the question text, the diagnosis text or the generated text; and the `ApprovedRecordSnapshot` whitelist left exactly as it is, so the field list crossing the hop stays five fields and cannot grow by accident. | **Open, and this row's premise is a decision the operator has not made.** Guardrail memo section 10.3 must be retired and replaced: the egress-proof evidence set is unproducible for a remote engine in principle, and what replaces it is a positive proof (these bytes, to this host, with this audit row) rather than a negative one. `docs/product-and-development-report.md:125` and `:303` still assert an on-device LiteRT engine that does not exist and would become doubly wrong under this design. No measurement exists of the payload size distribution on a real PHC link. The physician-tier path bypasses both scope gates (`SlmReadbackUseCase.kt:178`, `:203`), so for that tier the remote hop carries an unfiltered free-text question with no output grounding; this memo recommends the first build be WORKER-tier only, and that recommendation is not itself a control. |

---

## Section 2: what the new service must be

The sample service is a reference for **shape**, not a thing to integrate with. It is a demo, its
adapter is a no-op, its `finish_reason` lies, and it has no auth. The production service is a new
build. This section specifies it.

### 2.1 Request schema, closing the audit's 4.1 gaps

The audit's section 4.1 recorded the sample schema as six fields with no bounds on any of them
**(MEASURED)**: `model` (ignored entirely), `messages`, `temperature` (no range validation),
`top_p` (no range validation), `max_tokens` (no upper bound), `stream`.

Required schema, field by field, with the defect each field closes:

| Field | Type | Required | Bound | Closes |
|---|---|---|---|---|
| `prompt` | string | yes | **enforced input token limit**, rejected with an explicit error when exceeded, never silently truncated | Audit 5.2: no length limit exists at any layer, the tokenizer's `model_max_length` is the unset sentinel so it will not self-truncate, and the observable outcome is a CUDA OOM inside `generate()` |
| `max_tokens` | int | yes | **enforced server-side ceiling**, min of requested and ceiling | Audit 4.1: `max_tokens` has no upper bound |
| `stop` | list of strings | yes, may be empty | explicit, echoed back | Audit 2.2: "Explicit stop sequences do not exist" |
| `seed` | int | yes | echoed back | Audit 2.4: "No random seed is set anywhere" |
| `temperature` | float | yes | **must be exactly 0**, rejected otherwise | Audit 2.4: effective temperature 0.1 with `do_sample=True`, so identical input produces different clinical text with no reproducibility handle |
| `do_sample` | bool | yes | **must be false**, rejected otherwise | Same. Guardrail memo section 9.2: "Two readbacks of the same approved record produce the same text." |
| `prompt_template_version` | string | yes | rejected when absent | Guardrail memo section 9.2 and the H-12 `derivation_rule_version` precedent |
| `model_id` | string | yes | **validated against the loaded artifact**, 409 on mismatch | Audit 4.1: the sample's `model` field is "**Ignored.** Never read after validation" |

**On truncation behaviour, explicitly.** The device side already refuses rather than truncates:
`MAX_PROMPT_CHARS = 6000` and a `RECORD_TOO_LARGE` refusal at `SlmReadbackUseCase.kt:170-172`,
whose enum KDoc gives the reason at `:46-47`: "Refused rather than truncated: a record silently cut
inside the prompt is one the model answers about incompletely with no signal." **(MEASURED.)** The
service must take the same position, in token units rather than characters, and return an explicit
error code rather than a truncated generation. A character budget standing in for a token budget is
recorded on the device side as a deliberate placeholder (`SlmScopeGate.kt:33-38`); the service is
where the real tokenizer lives and where the real bound belongs.

**On single-turn, carried across the wire.** `SlmInvocation` has no history field and no
list-of-messages field, and the KDoc at `SlmReadbackUseCase.kt:16-21` says the single-turn rule is
"enforced here, by the absence of a field" **(MEASURED)**. The request schema must preserve that:
a `prompt` string, not a `messages` array. A `messages` array on the wire reintroduces the
parameter that the device-side type deliberately does not have, and the audit's section 4.3 records
what the sample did with one: 16 turns of client-held history replayed on every call, windowed by
message count rather than tokens, each turn potentially carrying a whole OCR document.

### 2.2 Response envelope

| Field | Requirement | Closes |
|---|---|---|
| `model_id` | **derived from the loaded artifact at load time**, never a literal | Audit 4.2: the sample hardcodes `model: "gemma-4-e2b-pharma"` at `edge_server.py:340` and ignores the request's `model` field entirely |
| `model_sha256` or an equivalent artifact digest | computed at load, echoed on every response | SOUP requirement S-2's server-side analogue, section 5 |
| `prompt_template_version` | echoed from the request | H-12 precedent: separate a template change from a model change |
| `decode` | the parameters **actually used**: temperature, do_sample, seed, top_p, top_k, max_new_tokens, stop | Audit 2.2: `top_k` is unset in the sample and silently falls back to `64` from the base `generation_config.json`. A parameter nobody sent and nobody sees is still shaping clinical text. |
| `generation_id` | unique per generation | No correlation handle exists in the sample beyond `chatcmpl-<epoch_ms>` |
| `finish_reason` | **honest**: one of `stop`, `length`, `stop_sequence`, `error`, and it must be derivable from the generation, not asserted | Audit 4.2, quoted verbatim: "`finish_reason` is the **literal string `"stop"` unconditionally**. It does **not** reflect truncation: a completion cut off at `max_new_tokens` still reports `"stop"`. A consumer cannot distinguish a complete answer from a truncated one." |
| `usage` | prompt tokens and completion tokens counted **in tokens** | Audit 4.2: in the sample's streaming path `completion_tokens` counts streamer text chunks, not tokens, so the reported `tokens_per_second` is not a token rate |

**`finish_reason` is the one that cannot be negotiated.** **(INFERRED, from two measured facts.)**
A truncated readback of a prescription that is indistinguishable from a complete one is a
half-instruction presented as a whole instruction. The device seam already refuses to present a
partial generation as an answer: `SlmReadbackUseCase.kt:184-186` discards everything already
sanitized on a mid-stream engine failure, because "a partial readback presented as an answer is the
substitute output section 9.2 forbids". A dishonest `finish_reason` defeats that rule from the
server side, silently, on the success path, where no exception is raised for the seam to catch.

### 2.3 Why this matters beyond tidiness

**(MEASURED, perf audit F6C-01.)** `backend/core` is already serving a classifier whose runtime
`model_version` is `toy-v0.6-observed-glucose-4tier`, against four documented strings, none of
which match, one of which is in the risk file: `docs/quality/risk-management-file.md:46` performs
hazard analysis against `v0.1-xgb-venn-abers`. Five strings, no two the same. F6C-01 calls it a
traceability finding under IEC 62304, not a performance one, and notes that the backend itself
already takes this class of problem seriously: `services/kernel.py:19-25` explains that the proxy
stopped attributing backend arithmetic to a named model version "because it breaks IEC 62304
traceability".

**(MEASURED, audit section 4.2.)** The sample SLM service hardcodes its `model` string and ignores
the request's.

**(MEASURED, perf audit F6C-05.)** The same classifier hardcodes `"calibrated": True` on a clinical
inference response while its own `model_meta.json` records
`calibration_used_for_evaluation: False`.

**(INFERRED.)** Three independent instances of the same defect shape: a model service asserting a
property about itself as a literal rather than reading it from the artifact. Two model services
with the same defect is a pattern, not an accident, and the pattern is that the literal is written
once, at authoring time, and then survives every subsequent artifact change. The fix is
structural and cheap: **derive, never assert.** Every self-describing field on a model response is
computed at load time from the artifact, or it is not on the response.

Section 0.3's sanitizer-vocabulary problem is the same defect wearing different clothes: a literal
token set authored against one model, surviving a swap to another.

### 2.4 Runtime, workers, health, GPU reservation

**Model load at startup, not per request.** **(MEASURED, perf audit F6C-03.)** The classifier
already does this correctly, at module scope in `SaMDClassifier/src/app.py:32-39`, and F6C-03
measured it: ten sequential `POST /v1/assess`, p50 11.97 ms, first call 25.3 ms, a delta of roughly
13 ms, which is connection setup and not a model load. **(INFERRED.)** The SLM service must do the
same, in a FastAPI lifespan rather than at module scope, so that load failure is a startup failure
with a log line rather than an import-time traceback, and so the health endpoint can report load
state.

**Worker count and its VRAM multiplication.** **(MEASURED, perf audit F6C-04.)** The classifier
holds 2.048 GiB resident for a 4.7 MB model, because a `pipeline_glue` import pulls
sentence-transformers and therefore torch. It runs one worker because `SaMDClassifier/Dockerfile:42`
has no `--workers` flag, so there is no duplication today. F6C-04's finding is the exposure:
adding `--workers N` multiplies the full 2.05 GB, not the 4.7 MB of model.

**(INFERRED, and the arithmetic is worse here.)** For an SLM the multiplied quantity is the weight
set itself. The audit measured the base checkpoint at 10,246,621,918 bytes in bfloat16, about 9.54
GiB, served at `dtype=torch.bfloat16` with no quantization anywhere in the serving path (audit
section 1.3: "Actual serving precision: `bfloat16`"). `--workers 2` is roughly 19 GiB of VRAM
before any KV cache. **Requirement: one worker, one model, and concurrency handled by a queue
inside the process rather than by process count.** Section 4.4 covers the queue.

**Health endpoint shape.** The sample has `GET /health` (`edge_server.py:186`) and one explicit
error, `503 "Model engine not loaded"` (`:214-215`) **(MEASURED)**. What the production health
endpoint must report, so that the backend's circuit breaker and an operator see the same truth:
load state, the derived `model_id` and artifact digest, the device actually in use (`cuda` or
`cpu`), VRAM in use, current queue depth, and the decode ceiling in force. Not a static `healthy`
literal. The classifier's health endpoint is the precedent to follow and to improve on: F6C-01 read
its `model_version` out of `/health` and that is exactly how the five-string mismatch was caught.

**GPU reservation: does not exist.** **(MEASURED, audit section 1.3.)** `Dockerfile:2` is
`FROM python:3.11-slim`, with no CUDA base image, and `docker-compose.yml` declares no
`deploy.resources.reservations.devices` and no `runtime: nvidia`. `docker-compose.yml:19` sets
`DEVICE=auto`, and `edge_server.py:53-59` resolves `auto` to `cuda` when `torch.cuda.is_available()`
else `cpu`.

**(INFERRED, and this is a failure mode worth naming.)** Those three facts compose into a silent
CPU fallback. A container with no CUDA runtime reports `torch.cuda.is_available() == False`, the
device resolves to `cpu`, and the service starts successfully and serves. On a 5.12B-parameter
model the observable result is not an error, it is a generation that takes minutes. The guardrail
memo's section 8 already planned for 20 to 60 seconds on-device; a silent CPU fallback on the
server is worse than the on-device case it was meant to replace. **Requirement: a CUDA base image,
an explicit GPU reservation, and `DEVICE` pinned to `cuda` with startup failure when CUDA is
unavailable.** `auto` is the wrong default for a service whose whole reason to exist is a GPU.

---

## Section 3: failure taxonomy, and the anti-pattern to name

### 3.1 `SlmRefusal` does not distinguish unreachable from engine failure

**(MEASURED.)** `SlmRefusal` has eleven values (`SlmReadbackUseCase.kt:38-75`). Exactly one covers
everything the engine can do wrong: `ENGINE_FAILED` at `:74`, whose KDoc reads "The engine failed.
A failure is shown as a failure; there is no substitute output (section 9.2)." It is produced by a
single blanket catch at `:196-198`:

```kotlin
} catch (t: Throwable) {
    return SlmReadbackResult.Refused(SlmRefusal.ENGINE_FAILED)
}
```

That was correct when the engine was on-device and unbound, because an on-device engine has no
unreachable state. **(INFERRED.)** On an offline-first device for rural PHCs with a remote engine,
**unreachable is the normal case, not an error case.** Collapsing it into `ENGINE_FAILED` is the
same shape as F6B-02 hop 7, where one `catch (e: Exception)` in
`GenerateKernelReportUseCase.kt:250-255` converts a specific, recoverable, actionable error into a
misleading infrastructure error. That finding is closed as a finding and open as a defect; the SLM
path must not add a second instance of it on the day it is built.

**Proposed new values, device side.** Replace `ENGINE_FAILED` with a set that mirrors the backend's
`KernelCallOutcome` vocabulary rather than inventing a second one:

| New `SlmRefusal` value | Fires when | Worker-facing meaning |
|---|---|---|
| `ENGINE_UNREACHABLE` | connect failure, DNS failure, no route | The service could not be reached. Normal offline case. Retryable, and the UI should say so. |
| `ENGINE_TIMEOUT` | read or call timeout with bytes flowing or not | The service was reached and did not finish in time. Retryable. |
| `ENGINE_UNAVAILABLE` | backend returns circuit-open, or the service reports itself unloaded | The service is up but not serving. Retryable later, not immediately. |
| `ENGINE_REJECTED_INPUT` | 4xx from the service: over token limit, bad template version, model id mismatch | Not retryable without a change. A real defect signal, and the one F6B-02 shows gets destroyed if it is not typed. |
| `ENGINE_FAILED` | 5xx, OOM, malformed envelope, anything else | The service failed internally. Retryable, cause unknown to the device. |
| `OUTPUT_TRUNCATED` | `finish_reason == "length"` on an otherwise successful response | A complete-looking answer that is not complete. **This is a refusal, not an answer.** See 3.3. |

**Proposed backend mirror.** A `SlmCallOutcome` enum in `backend/core/app/models/enums.py`
alongside `KernelCallOutcome` (`:270-284`), with the same eight-plus-one shape: `SUCCESS`,
`TIMEOUT`, `UNREACHABLE`, `ENGINE_ERROR`, `PAYLOAD_REJECTED`, `MALFORMED_RESPONSE`, `CIRCUIT_OPEN`,
`PHI_REJECTED`, plus `TRUNCATED`. `KernelCallOutcome`'s own docstring at `:271-275` argues for
exactly this granularity: folding unreachable and erroring into one bucket "would throw that
distinction away at the one place it is cheap to keep."

**Every file that must change in the same commit.** **(MEASURED, file list; INFERRED, the grouping.)**

| File | Change |
|---|---|
| `app/src/main/java/com/example/samdapp/domain/slm/SlmReadbackUseCase.kt` | The `SlmRefusal` enum (`:38-75`) and the catch at `:189-198`, which must classify rather than blanket-catch |
| `app/src/main/java/com/example/samdapp/domain/slm/SlmEngine.kt` | The failure contract in the KDoc at `:100-104`, which currently says a failure "arrives here as an exception from the flow"; it now needs typed failures, not a `Throwable` |
| `app/src/test/java/com/example/samdapp/domain/slm/SlmReadbackUseCaseTest.kt` | `RecordingSlmEngine` (`:60-65`) must be able to produce each failure class; the existing `ENGINE_FAILED` test at `:370` splits |
| `app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt` | The device `AuditAction` enum, new SLM values |
| `backend/core/app/models/enums.py` | `AuditAction` mirror (`:81-82` is where the kernel pair sits) and the new `SlmCallOutcome` |
| `backend/core/tests/test_audit_actions_device.py` | Asserts set agreement between the device enum and the backend mirror. **This is the trap.** The guardrail memo's section 9.4 records it: "A device action the mirror does not accept is a silent, permanent sync rejection of every row carrying it." |
| The readback UI, when it exists | Each refusal is a first-class UI state, not an error toast (guardrail memo section 5.6). Six new values is six new states. |

**The `test_audit_actions_device.py` coupling is the reason "same commit" is not a style
preference.** **(MEASURED.)** A device audit action the backend mirror does not accept causes every
row carrying it to be rejected at sync, permanently, and F6B-01 establishes what happens next:
`rejected` maps to `SyncState.FAILED` (`SyncAckMapping.kt:14`), no drain ever re-collects a FAILED
row, no requeue path exists anywhere in `app/src/main`, and `SyncState.failedCount` is never
rendered (zero `failedCount` hits across `presentation/`). A split commit here produces silent
permanent loss of exactly the audit rows that exist to prove the egress in section 1.

### 3.2 The full failure set, and what the worker sees

**H** = honest failure shown as a failure. **S** = substituted output. The target is H in every row.

| # | Failure | Where it is detected | Worker sees | H or S |
|---|---|---|---|---|
| 1 | Service unreachable | device transport, or backend `UNREACHABLE` (`services/kernel.py:224-231` precedent) | `ENGINE_UNREACHABLE`. "Could not reach the service." Retry offered. | **H** |
| 2 | Timeout | device `callTimeout`, or backend `ReadTimeout` (`:232-239` precedent) | `ENGINE_TIMEOUT`. Retry offered. | **H** |
| 3 | GPU OOM | serving side, must be caught and returned as 5xx | `ENGINE_FAILED`. **See 3.3: today this is the dangerous one.** | **H**, only if 3.3's requirement is met |
| 4 | Model not loaded | service health, or 503 | `ENGINE_UNAVAILABLE`. | **H** |
| 5 | Input over length | service, before generation, explicit error | `ENGINE_REJECTED_INPUT`. Distinct from a network failure. | **H** |
| 6 | Input refused by scope gate | device, `inputScopeRefusal` (`SlmScopeGate.kt:129-138`), **before the engine is called** | `OUT_OF_SCOPE_PHRASING`, `OUT_OF_SCOPE_INTENT`, or `OUT_OF_SCOPE_UNTETHERED`. Nothing is generated and nothing leaves the device. | **H** |
| 7 | Output refused by grounding gate | device, `outputIsGrounded` (`:155-161`) | `OUTPUT_NOT_GROUNDED`. **Whole output suppressed, never edited** (`:145-149`). | **H** |
| 8 | Sanitizer suppression | device, `SlmStreamSanitizer`, on every chunk | Nothing, by design. This is not a failure: the stripped span is control tokens. The counts travel on the answer as `SlmSuppression(spans, characters, droppedTokens)` (`SlmStreamSanitizer.kt:21-25`) for the audit row. | not a failure |
| 9 | Truncated stream / `finish_reason == "length"` | **service must report it honestly** (section 2.2); device must treat it as a refusal | `OUTPUT_TRUNCATED`. **Not an answer.** | **H**, and impossible today because the sample's `finish_reason` is a literal |
| 10 | Malformed envelope | device, or backend `MALFORMED_RESPONSE` (`:269-281` precedent) | `ENGINE_FAILED`. | **H** |

**Row 9 deserves its own sentence.** **(MEASURED, audit 4.2; INFERRED, the consequence.)** With the
sample's unconditional `"stop"`, row 9 is **not detectable by any consumer**. A readback truncated
mid-prescription arrives as a well-formed, complete-looking answer. The sanitizer passes it, the
grounding gate passes it (a truncated readback introduces no new drug and no new numeral, so it is
grounded by construction), and the worker reads half a dosing instruction as the whole one. **This
is the single most dangerous failure in the table, it is invisible at every existing gate, and the
only place it can be caught is the response envelope.** That is why section 2.2 makes an honest
`finish_reason` non-negotiable rather than a nice-to-have.

### 3.3 The anti-pattern this seam exists to prevent

**The sample app's fallback, with its anchors (MEASURED, audit 5.3).**
`PharmaInferenceRepository.kt:183-195`: when the stream did not succeed, the repository emits
`generateOfflineFallbackResponse(userPrompt)` with `tokensPerSecond = 28.4`, `totalTokens = 190`,
`totalLatencyMs = 620`, `runtimeMode = RuntimeMode.ON_DEVICE_LITERT` and
`modelName = "Gemma 4 LiteRT (On-Device Local)"`.

`generateOfflineFallbackResponse()` at `:198-226` is a four-branch `if/else` over lowercased
substring matches, returning hardcoded clinical text including "reduce Warfarin dose preemptively
by 33-50%", "Vitamin K1 5-10 mg IV", "Atropine 2-5 mg IV stat (Pediatric: 0.05 mg/kg)" and
"Pralidoxime 1-2 g IV".

Three properties, from the audit: the trigger is a substring match on the whole prompt, so the
warfarin branch fires on a prompt merely containing both words including a negation or an OCR
document that lists them; the metrics are hardcoded literals rendered to the user as real
measurements; and it is visually indistinguishable from a real answer, with no badge and no
warning.

**And the compounding mechanism, which is the part worth memorising (MEASURED, audit 5.2).** In the
streaming path the generation thread at `edge_server.py:254` is unjoined and its exceptions are not
propagated. A CUDA OOM inside `generate()` therefore does not become a 500. The client receives
`200 OK` with an SSE stream that simply stops producing content, the Android loop
`while (!source.exhausted())` exits with `streamSuccess == false`, and the fallback fires. The
audit's own sentence, quoted verbatim: "**A server-side OOM is presented to the clinician as a
confident canned clinical answer.**"

**What in SaMDApp's design makes that impossible, and what does not.**

Makes it impossible **(MEASURED)**:

1. **There is no fallback source on the SLM path.** `SlmReadbackUseCase.kt:189-198` has exactly two
   outcomes on a throw: rethrow `CancellationException`, or return `Refused(ENGINE_FAILED)`. There
   is no second source to try. Contrast `GenerateKernelReportUseCase.kt:250-255`, which does have a
   `kernelFallbackSource`, and whose blanket catch F6B-02 named.
2. **A partial generation is discarded, not emitted.** `:184-186`: "A mid-stream engine failure
   discards what was already sanitized, a partial readback presented as an answer is the substitute
   output section 9.2 forbids." The `StringBuilder` at `:188` is dropped on the catch path.
3. **No mock can reach a non-dev build.** Guardrail memo section 9.3 mandates the
   `DevClinicalMockModule` pattern: dev-flavor source set only, staging and prod bind an honest
   unavailable implementation. H-09 and H-13 are both in the register because a plausible
   fabricated value reachable in a non-dev build is this project's recurring failure mode, and H-13
   records the cost: `MockVitalsSource` was moved out of the shared compilation unit into
   `src/dev/` to close it.
4. **`filterDisclaimers()` is structurally un-addable.** Stage 3b-0 re-expressed the sanitizer's
   guard as `isControlTokenShaped`: a literal opens with `<` or `[`, closes with the matching
   bracket, and its interior is non-empty with no whitespace and no bracket character
   (`PROGRESS.md:5025-5030`). A prose phrase has spaces and no brackets, so it cannot enter the
   match set as data. That is NC-2 enforced by shape rather than by discipline.
5. **The output grounding gate suppresses whole or passes whole** (`SlmScopeGate.kt:145-149`), and
   there is deliberately no "clean this up" variant to reach for.

**What does NOT yet make it impossible, stated as absences (MEASURED):**

1. **Row 9 of the failure table.** A truncated generation is currently undetectable by any gate in
   this app, because the only place it is visible is a response field the sample lies about. This
   is not a substituted output in the fabrication sense, but it is a **partial output presented as
   a whole one**, which is the same harm class from the worker's side. Closing it is a service
   requirement, not a device one. It does not exist today.
2. **`OUTPUT_TRUNCATED` does not exist** as a `SlmRefusal` value, so even an honest
   `finish_reason` has nowhere to land on the device.
3. **The refusal surface does not exist.** No readback UI exists at all, so "presented as a
   failure" is currently a design intention with no implementation behind it. A refusal rendered as
   a toast, or as an empty screen, would recreate the H-14 failure mode where the absence itself
   carries no signal.
4. **The honest-unavailable binding does not exist.** `SlmEngine` has no implementation of any
   kind, so the staging and prod binding that section 9.3 mandates is not written. Until it is,
   there is nothing to check.

### 3.4 Shared vocabulary with the sync failure taxonomy (F6B-01, F6B-02)

**(MEASURED.)** The sync side's terminal-failure vocabulary is `SyncState.FAILED`, set at
`SyncAckMapping.kt:14` from the backend's `"rejected"` ack, which is itself produced by
`_SQLSTATE_MESSAGES` at `backend/core/app/services/sync.py:125`. The backend's kernel-call
vocabulary is `KernelCallOutcome` (`enums.py:270-284`). The device's inference vocabulary is
`InferenceSource { REAL_INFERENCE, MOCK_FALLBACK, UNAVAILABLE }`
(`domain/model/InferenceSource.kt:12`).

**Three vocabularies exist. This memo proposes a fourth. That is one too many, and where they must
share is specific, not general (INFERRED):**

1. **Reachability versus rejection is the distinction all four need, and only one of them has.**
   `KernelCallOutcome` separates `UNREACHABLE`, `TIMEOUT`, `KERNEL_ERROR`, `PAYLOAD_REJECTED`,
   `MALFORMED_RESPONSE` and `CIRCUIT_OPEN`. `InferenceSource` collapses all of them into
   `UNAVAILABLE`. `SlmRefusal` collapses all of them into `ENGINE_FAILED`. **The SLM's new values
   must be named after `KernelCallOutcome`'s, not invented**, so that an operator reading a
   `kernel_call_log` row and an SLM call row is reading one vocabulary.
2. **"The server rejected the data" must survive to the worker.** This is F6B-02's hop 7 exactly. A
   `PAYLOAD_REJECTED` from the SLM service (over token limit, template version mismatch, model id
   mismatch) is a defect signal, not an outage, and it is the class of error that F6B-02 shows gets
   destroyed by a blanket catch. `ENGINE_REJECTED_INPUT` exists in 3.1 for this and only this.
3. **Terminal must not mean invisible.** F6B-01's finding is that `FAILED` is terminal by
   construction (no drain selects it) and invisible by omission (`failedCount` is never rendered),
   and that "Neither half is wrong alone. Together they are." The SLM path's equivalent trap is an
   audit row that cannot sync because the backend mirror lacks the enum value, which lands in
   exactly that terminal-and-invisible state. 3.1's same-commit requirement is what prevents it.

**Where the sharing is written down, concretely:** `backend/core/app/models/enums.py` is the one
file that holds `AuditAction` and `KernelCallOutcome` side by side, and it is where
`SlmCallOutcome` belongs. `backend/core/tests/test_audit_actions_device.py` is the test that makes
the device-backend agreement enforced rather than intended. Those two files are the seam.

---

## Section 4: transport

### 4.1 The prior: non-streaming single response, interface stays a `Flow`

**The prior as stated:** non-streaming single response, sanitizer fed the whole body as one chunk,
interface stays a `Flow` so streaming can be added later. Rationale: the grounding gate already
withholds all text until generation completes, so streaming buys no perceived latency while costing
a streaming timeout policy on a stack that F6B-03 found has zero `callTimeout` anywhere.

**Verdict: agree, and the rationale is stronger than stated.** Three supports, then one caveat.

**Support 1, the premise is correct and measurable (MEASURED).** `SlmReadbackUseCase.kt:190-205`:
the flow is fully collected into a `StringBuilder`, `sanitizer.finish()` is appended, and only then
does `outputIsGrounded` run at `:203`. Nothing is returned before the gate. For a worker-tier
viewer, **no token can reach the screen before generation completes**, so streaming buys exactly
zero perceived latency on the tier this memo recommends building first.

**Support 2, the physician tier does not change the answer (MEASURED).** The physician tier
bypasses the grounding gate at `:203`, so in principle streaming would help there. But
`SlmReadbackUseCase` returns `SlmReadbackResult`, a terminal value, not a flow
(`:147`, `:212-216`). There is no streaming surface on the seam's own signature today for either
tier. Adding one is a seam change, not a transport change.

**Support 3, F6B-03's gap makes streaming actively expensive (MEASURED).** Zero `callTimeout`
across all of `app/src`, four independent `OkHttpClient` instances, four connection pools. F6B-03's
own worked case: "A server that dribbles bytes just under the read timeout can hold a request open
indefinitely." An SSE stream from a GPU service is precisely a request that dribbles bytes under
the read timeout by design, for 20 to 60 seconds. Adopting streaming first means adopting a
streaming timeout policy on a stack that has no whole-call timeout policy at all.

**Caveat, and it is a real cost to accept knowingly (INFERRED).** The guardrail memo's section 8
requires that "tokens are surfaced to the UI as they are produced" and that "The UI shows a visible
generating state from the moment the call starts, not from the first token." The first half is not
satisfiable for the worker tier under any transport, because the grounding gate withholds
everything; that tension exists in the current design and is not created here. The second half is
satisfiable and must be built: a visible, honest generating state for the full 20-to-60-second
window, that does not fake progress. Non-streaming makes that surface more important, not less,
because there is no token trickle to signal liveness.

**Recommendation: non-streaming. Keep `SlmEngine.generate` returning `Flow<String>` unchanged**
(`SlmEngine.kt:118`), and have the remote binding emit exactly one chunk. The sanitizer handles a
single chunk correctly by construction: it holds back a window precisely because a control token
may straddle chunks, and `finish()` flushes it (`SlmReadbackUseCase.kt:191-193`). No seam change,
no test change, and streaming remains addable later behind the same interface.

### 4.2 Timeout budget, and what cancellation does to the GPU

**(MEASURED, the existing numbers.)** Device backend client: connect 10 s, read 30 s, write 30 s,
no `callTimeout` (`NetworkModule.kt:96-104`). Backend-to-kernel: `httpx.Timeout` with connect,
read, write and pool from settings (`client.py:36-44`). Guardrail memo section 8's planning range
for a generation: 20 to 60 seconds and worse.

**Proposed budget (INFERRED, sized from those numbers):**

| Hop | connect | read | call (whole) | Note |
|---|---|---|---|---|
| Device to backend, SLM endpoint | 10 s, unchanged | **90 s**, raised from 30 s for this endpoint only | **120 s `callTimeout`** | 30 s read would cut a normal generation. This is the one endpoint that needs a different read budget, which argues for a dedicated client or a per-call override, not a global raise. |
| Backend to SLM service | 5 s | **90 s** | **100 s** | Strictly inside the device's budget, so the device sees a typed backend error rather than its own timeout. |
| Service internal | n/a | n/a | **hard wall-clock cap below 90 s** | The service must abandon its own generation before the backend gives up, so that the abandonment is the service's decision and it can free VRAM deliberately. |

**The ordering rule matters more than the numbers (INFERRED).** Each inner budget strictly inside
its outer one, so that a timeout is always classified at the innermost layer that can see the cause.
If the device times out first, every failure looks like `ENGINE_TIMEOUT` and section 3.1's
vocabulary is wasted. The kernel path already gets this right by accident of having a short model:
at p50 11.97 ms nothing ever reaches a timeout boundary.

**Device-side cancellation and the GPU (MEASURED, then INFERRED).** `SlmEngine.generate`'s KDoc at
`:113-116` promises that "cancelling the collection cancels that generation", on the on-device
assumption where cancellation and the compute are in one process. **Over a network that promise is
not deliverable by the client.** Cancelling an OkHttp call closes a socket. The generation on the
GPU keeps running to `max_new_tokens`, holding VRAM, and on a single-worker service it blocks the
next request.

**Requirement:** the service must detect client disconnect and stop generation. In FastAPI that is
`await request.is_disconnected()` polled from the generation loop, or an equivalent cancellation
token checked per step. Without it, a worker who cancels and immediately retries has two
generations resident and a queue behind them. **(INFERRED.)** And `SlmEngine.kt`'s KDoc at
`:113-116` must be corrected in the same commit that binds a remote engine, because it currently
promises something the binding cannot deliver.

### 4.3 Reuse an existing OkHttp client, or add one

**(MEASURED, F6B-03.)** Four clients today, four `ConnectionPool` instances, four `Dispatcher`
instances, four `ExecutorService` instances, each taking the OkHttp default of 5 idle connections
at 5 minutes keep-alive. None is built from `newBuilder()` off a shared instance.

**Recommendation: reuse `provideOkHttpClient` via `newBuilder()`, with a per-call read timeout
override. Do not add a fifth client. (INFERRED.)**

Reasons:

1. Under topology (A) the SLM endpoint is a `backend/core` endpoint. It needs the same
   `BearerInterceptor` and the same `TokenAuthenticator` that every other backend call needs. A
   fresh client would need both re-added, and a fresh client that forgot them would produce 401s
   that look like service failures.
2. F6B-03's finding is four pools where one would do. Adding a fifth makes a named finding worse in
   the same week it was filed.
3. The one legitimate reason to fork a client on this project is the `PiGatewayNetworkModule`
   reason, quoted from `PiGatewayNetworkModule.kt:39-41`: reusing the backend client "installs
   `BearerInterceptor` and `TokenAuthenticator`, so reusing it would attach the backend access
   token." That reason is about **not** wanting the token. Here the token is wanted.
4. OkHttp's `newBuilder()` shares the pool, dispatcher and executor while allowing per-client
   timeout overrides, so the longer read budget costs nothing structurally.

**And add `callTimeout` while touching it (INFERRED).** F6B-03 found zero across `app/src`. The SLM
call is the first request on this project where the whole-call bound is load-bearing rather than
theoretical, because it is the first one expected to run for a minute. Adding `callTimeout` to the
SLM-specific `newBuilder()` derivative is a two-line change and closes half of F6B-03 for the path
that needs it most. Whether to add it to the other three clients is a separate decision and is not
this memo's to make.

### 4.4 Concurrency: what happens when a request arrives while one is in flight

**(MEASURED, the constraint.)** Section 2.4: one worker, one resident model, roughly 9.54 GiB in
bfloat16. **(MEASURED, the sample's answer.)** The sample has no concurrency control on the server
at all; the only guard anywhere is client-side, `PharmaViewModel.kt:149`,
`if (_uiState.value.isGenerating) return`, which the audit correctly notes is not a safety control.

**Recommendation: both, at different layers, for different reasons (INFERRED).**

**Server-side queue, depth 1 plus a small bounded wait, is the correctness control.** A single
worker holding one model cannot serve two generations concurrently without either doubling KV cache
or interleaving badly. A bounded queue with an explicit `503` plus `Retry-After` when full is an
honest answer; an unbounded queue turns a load spike into a timeout storm, and every one of those
timeouts is indistinguishable at the device from the service being down. Queue depth must be on the
health endpoint (section 2.4) so the backend's breaker and an operator see the same number.

**Device-side mutex is the user-experience control, and it is not optional either.** Without it a
worker who taps twice gets two identical generations queued, waits twice as long, and holds a slot
that another PHC worker needs. A single PHC may have several devices against one GPU box.

**Why both, rather than picking one.** The device-side guard cannot bound anything across devices;
the server-side queue cannot stop one device from wasting its own turn. The failure modes they
prevent are disjoint. Note also that the device-side guard cannot live in the ViewModel the way the
sample's does: `SlmReadbackUseCase` is the seam, and the guard belongs there or below it, because
`SlmEngineIsUnreachableFromPresentationTest` exists specifically to keep presentation off this path
**(MEASURED, `SlmEngine.kt:85-90`)**.

---

## Section 5: SOUP and licence

### 5.1 The position from the audit, recorded without interpretation

**(MEASURED, audit section 1.4.)**

- **No `LICENSE`, `COPYING`, `NOTICE` or terms file exists anywhere in the serving repository.** A
  filesystem search for `LICENSE*` and `COPYING*` returned zero results.
- **No license or terms file exists in the cached base model snapshot either.** A search for
  `*licen*`, `*terms*`, `*notice*`, `*policy*` under the cached
  `models--google--gemma-4-E2B-it/` tree returned zero results. The snapshot contains only
  `chat_template.jinja`, `config.json`, `generation_config.json`, `model.safetensors`,
  `tokenizer_config.json` and `tokenizer.json`.
- **Apache-2.0 is asserted on the adapter only**, and only as metadata, not as text: a README
  shield badge, a YAML frontmatter key, a BibTeX field, and a malformed `licence: license` key with
  no value. Four PEFT-generated checkpoint cards carry the unfilled default
  `**License:** [More Information Needed]`.
- **The base model's own terms are not present locally.** They are referenced only by the upstream
  URL `https://huggingface.co/google/gemma-4-E2B-it`. Any "deemed manufacturer", redistribution,
  use-restriction or prohibited-use clause governing the base weights lives at that upstream
  location and is not retrievable from the repo.
- **No file in the repo reconciles the Apache-2.0 assertion against the base model's terms.**
- **Training-data license: does not exist as a document.** No data license, no provenance file, no
  attribution file. The clinical facts inside the generator dicts carry no cited source.
- **Third-party runtime licenses: no aggregated notice file exists.**

### 5.2 What the SaMD SOUP record needs for a server-side model

**(MEASURED, the existing record.)** `docs/quality/soup-validation-record.md` covers on-device ASR:
sherpa-onnx 1.13.7 (Apache-2.0), ONNX Runtime (MIT), Parakeet tdt-0.6b-v2 int8 weights (CC-BY-4.0).
Five requirements, S-1 through S-5, with per-requirement evidence. The companion
`docs/sbom/model-soup-2026-09-02-v1.0.json` carries identity, version, SHA-256 and licence per
component, with `externalReferences` and `samd:` properties recording sourcing and shipped ABI.

**The five requirements, and how each changes for a server-side model (INFERRED throughout, from
the measured text of each requirement):**

| Req | ASR, in-APK | Server-side SLM |
|---|---|---|
| **S-1** "performs no off-device transmission" | Provable, and proved: reflection scan, `txDelta`/`rxDelta` of 0, StrictMode, airplane-mode witness on arm64 hardware | **Inverts.** The requirement becomes "transmission goes only to the declared host, over the declared transport, and every transmission is audited". The evidence is a positive record, not a zero measurement. This is the same replacement section 1.3 identified for guardrail memo 10.3. |
| **S-2** "the bytes that ship are the bytes that were assessed" | `PINNED_ASSET_SHA256` in `SherpaOnnxTranscriptionServiceTest`, red test on mismatch | **Weakens structurally, and this is the hard one.** The device cannot hash an artifact it does not hold. The digest must be computed server-side at load, returned on every response (section 2.2's `model_sha256`), and recorded in the audit row. The device's guarantee degrades from "I verified the bytes" to "I recorded what the server claimed the bytes were". That is a real reduction in assurance and must be written down as one, not glossed. |
| **S-3** "the component set changes only by shipping a new app release" | Holds: compiled into the APK, no download-on-first-use, no model CDN, no remote config, with `TranscriptionPathHasNoNetworkDependencyTest` keeping it that way | **Breaks outright.** A server-side model can be swapped without an app release. That is a post-deployment model update, and it engages exactly the CDSCO Algorithm Change Protocol gap that `qms-overview.md` still lists as TODO and that S-3's own text was written to avoid. **This is the single largest SOUP consequence of remote inference.** The mitigation is procedural, not technical: the served `model_id` and digest on every response, recorded per call, so that a swap is at minimum **detectable after the fact**. Detectability is not change control. |
| **S-4** "failure is honest" | The absent-asset test asserts a visible failure | Unchanged in spirit, wider in surface. Section 3's taxonomy is what discharges it. |
| **S-5** "licence obligations of the shipped artifacts are discharged" | CC BY 4.0 attribution surfaced in-app by `OpenSourceLicensesScreen`, asserted by `OpenSourceLicensesScreenTest` | **Changes shape.** Nothing is shipped in the APK, so no attribution attaches at APK distribution for the model. Obligations attach to the **operator of the service**, which may be the same institute. Whether an in-app attribution is still required is a licence-reading question, not a code question. |

**A new SOUP entry is required regardless, and it is a different kind of entry (INFERRED).** The
existing companion describes files with digests and ABIs. A server-side entry describes a
**service**: base model identity and revision, the exact adapter (or the recorded fact that no
effective adapter exists, per section 0.1), the serving runtime stack (PyTorch, `transformers`,
`peft`, FastAPI, uvicorn, and their versions), the container base image, the host, and the
digest-at-load mechanism. None of that fits the current companion's component shape without a new
property set. **That entry does not exist today.**

### 5.3 Live distribution surfaces, and whether they create an obligation

**(MEASURED, audit section 1.4.)** Two surfaces are already live:

1. A published Hugging Face adapter, `sandeshv12/gemma4pharma`, linked from the serving repo's
   `README.md:5` and named as `adapter_id` in the model card.
2. A 29 MB debug APK committed at the serving repo's root, `Gemma4PharmaCopilot-debug.apk`.

**(INFERRED, stated as a question to close rather than an answer.)** Both are distribution, and
both predate this design. Two things follow that do not require reading anyone's licence text:

- The published adapter is the artifact section 0.1 measured as a mathematical identity, published
  under an Apache-2.0 assertion, with a model card publishing "Final Training Loss: 5.248" and
  documented dataset counts (2,164/270/271) that do not match the files on disk (584/73/73). That
  is a factual-accuracy question about a public artifact, independent of any licence question, and
  it is the kind of thing that is cheap to correct now and expensive to explain later.
- The committed debug APK is the app whose `filterDisclaimers()` strips "I am an AI and not a
  physician" from clinical output before display (audit F4). Distributing that binary is a separate
  matter from distributing the weights.

**Neither is SaMDApp's to fix**, and neither blocks this design. Both belong on the owner's list
because they are live now.

### 5.4 The licence position, named and not interpreted

Per instruction, this memo names which document governs and what it is not. It does not interpret
terms.

- **The document that governs the base weights** is the terms published with
  `google/gemma-4-E2B-it` at its upstream Hugging Face location. **It is not present in the serving
  repo and not present in the local cache** (audit section 1.4). It has not been read in this pass.
- **The Apache-2.0 assertions in the serving repo are not that document.** They are metadata
  attached to the LoRA adapter artifacts.
- **`docs/quality/soup-validation-record.md` is not that document either.** It covers the ASR
  components and, since 2026-09-17, a CameraX inventory.
- **The HAI-DEF Terms of Use are a separate document again**, and `PROGRESS.md:4571-4574` already
  flags them for operator legal review, recording that they "condition clinical use on regulatory
  authorization, forbid use that could cause Google to be deemed a medical-device *manufacturer*,
  and impose an indemnity" **(MEASURED)**. That flag concerns MedASR and the MedGemma family.
  Whether it reaches a Gemma 4 base served server-side is precisely the question this memo does not
  answer.

**This is an owner and institute legal question.** What this memo can say is which document has to
be obtained and read before a production deployment: the upstream terms for whichever artifact is
finally served, and the HAI-DEF terms if the artifact is from that family. Neither is on this
machine.

---

## Section 6: build sequence

PRs in order. Each carries a model tier, meaning the effort and review weight it needs, not a
Claude model name.

| PR | Scope | Tier | Blocked on Section 1? |
|---|---|---|---|
| **PR-0** | **Docs correction only.** C-1 and C-2 from section 0.2: `docs/product-and-development-report.md:125` and `:303` stop claiming a shipped on-device LiteRT engine. Controlled-docs change, no code. | low | **No.** Those two lines are wrong today regardless of which topology is chosen. |
| **PR-1** | **P1 discharge and model-identity decision, recorded.** Run the three GPU-box checks. Decide the served artifact. Re-verify the sanitizer's literal set against **that artifact's own tokenizer**, the way stage 3b-0 did against `medgemma-1.5-4b-it`. Output is a scratchpad record plus, if the artifact is not MedGemma, a red `SlmStreamSanitizerTest`. | medium | **No**, and it must not wait. Section 0.3's fail-open is topology-independent. |
| **PR-2** | **Failure taxonomy, device and backend, one commit.** Section 3.1's six `SlmRefusal` values, the `SlmCallOutcome` backend mirror, the `AuditAction` additions on both sides, and `test_audit_actions_device.py` green. No transport, no engine, no UI. | high | **No.** The taxonomy is correct for any topology, and F6B-01's terminal-and-invisible trap makes the same-commit coupling mandatory whenever it lands. |
| **PR-3** | **Service contract, written as a document and a schema.** Sections 2.1 and 2.2, as an API contract entry alongside `docs/backend/api-contract.md` section 5. No implementation. | medium | **Partially.** The schema is topology-independent; where the schema is *enforced* is not. |
| **PR-4** | **`backend/core` SLM proxy.** The section 1.1 pattern applied: facility scoping, PHI guard over the new payload shape, circuit breaker, no retry, `write_out_of_band` audit rows, the new outcome vocabulary, R-2's authenticated outbound hop. | high | **Yes.** This PR is topology (A). It does not exist under (B). |
| **PR-5** | **The service itself.** Section 2's schema enforced, artifact-derived identity, honest `finish_reason`, lifespan load, single worker, private network, CUDA base image with an explicit GPU reservation, health endpoint, bounded queue, disconnect-driven cancellation. | high | **Yes**, for R-1 and R-2. The rest is topology-independent. |
| **PR-6** | **Remote `SlmEngine` binding.** One-chunk `Flow`, `newBuilder()` off the shared client with a `callTimeout`, device-side mutex, and the `SlmEngine.kt:113-116` cancellation KDoc corrected. Staging and prod bind honest-unavailable per guardrail memo 9.3. | high | **Yes.** |
| **PR-7** | **Readback UI and the refusal surface.** Every `SlmRefusal` value a first-class state. The honest generating state for the full window. Flag `SLM_READBACK_ENABLED`, default false. | high | **Yes.** |
| **PR-8** | **Risk file and SOUP.** H-28 written, guardrail memo 10.3 retired and replaced, S-2 and S-3 positions from section 5.2 recorded, new server-side SOUP entry. | medium | **Yes**, and it is a sign-off gate, not a code change. |

### What must NOT be in the first PR

The first PR is PR-0, a two-line docs correction. Stated explicitly because the temptation runs the
other way:

- **No transport.** No OkHttp client, no Retrofit interface, no endpoint.
- **No `SlmEngine` implementation**, in any flavor, including a dev stub. Guardrail memo 9.3, and
  H-09 and H-13 behind it.
- **No feature flag.** `SLM_READBACK_ENABLED` arrives with the surface it gates, not before.
- **No UI.** No readback screen, no entry point, no navigation route.
- **No risk-file edit.** H-28 is drafted in this memo and stays here until the operator signs it.
- **No change to `ApprovedRecordSnapshot`'s field list.** The five-field whitelist is what bounds
  the section 1.2 payload. Growing it is a separate decision with its own reasoning, per that
  type's own KDoc: "Add a field deliberately, with a reason, or not at all."
- **No change to the scope gates.** `SlmScopeGate.kt` is calibrated for the current snapshot and
  the current tiers. Touching it in a transport PR mixes two review problems.
- **Nothing that widens the physician tier.** Section 0.4's recommendation is worker-tier first, and
  that is a decision to make before the tier logic is touched, not during.

---

## Appendix A: the "does not exist" list for this design

Searched for, and absent. Not undocumented, absent.

| Item | Status |
|---|---|
| Any `SlmEngine` implementation in `app/src/main` | does not exist |
| Any LiteRT, MediaPipe or genai dependency in the SaMDApp build | does not exist |
| `SLM_READBACK_ENABLED` in `FeatureFlags.kt` | does not exist |
| Any readback UI under `presentation/` | does not exist |
| A C++ stream sanitizer (the shipped one is Kotlin) | does not exist |
| Any SaMDApp document claiming a fine-tuned or pharma-specific SLM | does not exist |
| A text-decoder fine-tune in the serving repo | does not exist (audit F1) |
| Auth on the `backend/core` to kernel hop | does not exist |
| An SLM audit log on the serving side | does not exist |
| An honest `finish_reason` on the serving side | does not exist |
| An `OUTPUT_TRUNCATED` refusal value on the device | does not exist |
| A distinction between unreachable and engine-failed on the device | does not exist |
| A `callTimeout` on any OkHttp client in `app/src` | does not exist (F6B-03) |
| A GPU reservation or CUDA base image in the serving container definition | does not exist (audit 1.3) |
| A server-side SOUP entry shape in the model companion | does not exist |
| A licence or terms file for the base weights, in the serving repo or its cache | does not exist (audit 1.4) |
| Latency or throughput measurement for the serving stack | does not exist (audit 4.4) |
| P1's three GPU-box checks, run | not run; the repo is not on this machine |

---

*End of memo. Read-only. No file in either repository was created, modified or deleted other than
this memo. `docs/quality/risk-management-file.md` was not touched: H-28 above is a draft, and
H-11.C1 and H-27 were read by id only.*
