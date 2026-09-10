# SLM guardrail and service-contract design memo (MedGemma 1.5 4B, on-device)

**Status: PROPOSED. NOT APPROVED. Operator review required.**
Drafted 2026-09-09. Read-only design work: no code was written, no file outside this memo was
modified, no risk-file row was written. Hazard rows in section 12 are drafted verbatim here for a
later, separate docs commit.

Authoring model: Opus. Scope of this memo: define the SLM feature for the main SaMDApp Android
app, its input contract, its two-tier guardrail, its output handling, and the service contract the
rest of the app builds against. This memo does not design a fine-tune (section 13), does not
design the Docker path (out of scope by operator decision), and does not authorize a build.

---

## 1. Fixed inputs (operator decisions, not re-litigated here)

| # | Decision | Consequence for this memo |
|---|---|---|
| D1 | Model is MedGemma 1.5 4B, int4 `.litertlm`, `litert-community/MedGemma-1.5-4B-IT`. HAI-DEF terms accepted for demo; production distribution gated behind a separate legal review. | The memo designs around the measured behaviour of this artifact, not the model family's paper behaviour. Same posture as H-15's artifact-versus-family distinction for ASR. |
| D2 | Deployment is on-device via LiteRT-LM. No Docker service. | No network seam, no server-side guardrail. Every control described here is device-resident, and the egress claim must be proven, not asserted (section 10.3). |
| D3 | Output streams token by token. | Section 8. Also constrains the sanitizer: control-token stripping has to work on a token stream, not on a finished string (section 6.1). |
| D4 | Two guardrail tiers keyed to `CadreTier`. WORKER tier: readback and explain of a finalized, physician-approved record only. DOCTOR tier (`PHYSICIAN`): open querying permitted. | Section 5. Where `LICENSED_CLINICAL` sits was left to this memo; section 5.1 decides it and gives the reason. |
| D5 | The doctor tier's openness is bounded by H-06. | Stated plainly in section 5.5 and carried into hazard H-24. |

## 2. Empirical basis: what the isolation harness actually established

The harness ran on the GPU machine against the shipped int4 export. Raw outputs live in the sample
repo's `scratchpad/results_cpu.json`. Findings, labelled for reference below:

- **F1. It runs, on CPU only.** About 12 tokens per second, about 3.35 GiB resident. GPU delegation
  is unsupported for this int4 text export. That is a laptop-class CPU number, not a field-phone
  number, so 12 tok/s is an optimistic ceiling for the deployment device, not an estimate for it.
- **F2. Readback works with no fine-tuning.** Given a finalized prescription, it restates it in
  plain language correctly. This is the finding that makes the feature viable and the finding that
  makes a fine-tune unnecessary (section 13).
- **F3. It hedges on its own.** It volunteers "consult your doctor" style caveats without being
  asked. This is desirable output and section 6.2 forbids removing it.
- **F4. It refuses nothing.** Out-of-scope prompts are answered, not declined. There is no
  model-side refusal behaviour to build on.
- **F5. Empty input is not handled.** An empty prompt produced hallucinated content and ran to
  about 2000 tokens. Compare H-20, where an empty symptom input produced a confident `E66` at
  74.95 percent from the classifier's training prior. Same failure class, different model: an
  empty input does not produce an abstention, it produces confident fabrication.
- **F6. The reasoning channel leaks.** Internal `<unused94>thought ... <unused95>` control-token
  spans appear in visible output.
- **F7. It answers free-form clinical questions.** A bare "is ibuprofen safe with lisinopril", with
  no record attached, produced a full interaction answer.

F4, F5, F6 and F7 are the reason every control in this memo sits outside the model.

## 3. What this feature is, and what it is not

**Is:** a plain-language readback of a record a physician has already approved, rendered on device,
to the person holding the phone, in service of the worker or the patient understanding what the
physician decided.

**Is not:** a diagnostic aid, a second opinion, a drug-interaction checker, a triage tool, or a
source of any clinical content that is not already in the approved record. It adds no new clinical
claim. It restates an existing one.

That boundary is the whole safety argument. The moment the model answers a clinical question not
answerable from the record in front of it, the feature has silently become a diagnostic device
function with no validation behind it, and it lands squarely on H-02 (automation bias on
non-autonomous AI output) and H-17 (a worker acting on an AI drug recommendation no physician
reviewed). Section 5 exists to make that transition impossible by construction rather than by
prompt.

---

## 4. Input contract

### 4.1 The only permitted input

The SLM's only permitted input is a **finalized, physician-approved record for one case**, plus one
bounded user question about that record.

In the current app the approval condition already exists and is already load-bearing. It is the
same condition `ReportFormatter` uses for the prescription visibility gate:

- `ReportFormatter.format` computes `decisionCommitted = prescription?.kernelDecision != null`
  (`app/src/main/java/com/example/samdapp/domain/report/ReportFormatter.kt:83`). A committed
  `KernelDecision` (AGREE, MODIFY or REJECT) is the app's existing, single definition of "a
  physician has passed the gate on this case".
- The decision is written by `SubmitDoctorDecisionUseCase`
  (`app/src/main/java/com/example/samdapp/domain/usecase/SubmitDoctorDecisionUseCase.kt:47`), which
  writes the `Prescription` with its `kernelDecision`, writes the `DiagnosisFeedback` row, and
  emits `DIAGNOSIS_FEEDBACK_RECORDED` plus `PRESCRIPTION_APPROVED`.
- The assembled record object is `ClinicalReport`
  (`app/src/main/java/com/example/samdapp/domain/report/ClinicalReport.kt:85`), built by
  `AssembleReportUseCase(caseRecordId, audience)`, carrying `prescription`,
  `kernelDecision`, `signature`, `diagnosis` and `isFinal`.

So the record type is **`ClinicalReport` assembled for a case whose `Prescription.kernelDecision`
is non-null**, and the readback source of truth is that object, not the database rows behind it.

### 4.2 Finding: the reader the SLM needs does not exist and must be built

`AssembleReportUseCase` produces a `ClinicalReport` for **rendering** (Compose preview and
`ReportCanvasRenderer` PDF, one layout two surfaces). It is not a serializer, it carries full
patient identity in `ReportPatientBlock` (`fullName`, `guardianName`, `address`, `mobileNumber`,
`abhaNumberFormatted`), and it is not shaped for a model prompt.

**Does not exist, must be built** (scoped here, not built):

1. **A finalized-record snapshot type.** A small, flat, model-facing value type. Call it
   `ApprovedRecordSnapshot` for discussion. It carries only what a readback needs: the committed
   `kernelDecision`, `diagnosis`, the formatted medication lines (`ReportFormatter.formatMedicationLine`
   already produces the full-text, no-OD/BD form the NMC rule requires), the referral suggestion
   flag, and age/sex only if the readback genuinely needs it.
2. **A reader that refuses to produce one unless the record is approved.** It resolves the case,
   requires a non-null `kernelDecision`, and returns a typed refusal otherwise. The refusal is not
   an empty snapshot: an empty snapshot reaching the model is exactly F5.
3. **Structural PHI exclusion, following the H-10 pattern.** H-10's control is that `KernelPayload`
   has no field of type `Patient` and `SendToKernelUseCase`'s signature cannot accept one, so
   identity cannot reach the kernel boundary even by mistake. The snapshot type must be built the
   same way: no field of type `Patient`, no field of type `ReportPatientBlock`, no `fullName`, no
   ABHA number, no mobile number, no address. The reader's signature takes a `caseRecordId`, not a
   `Patient`. This is a construction guarantee, not a convention, and it is the difference between
   a control that holds under future edits and one that does not.

### 4.3 Forbidden inputs (hard, not advisory)

- **Free text as input.** The sample app fed arbitrary user text into the prompt. Forbidden. The
  user's contribution is a question about the record, length-capped, and it is never the clinical
  content.
- **Raw OCR text, unescaped, concatenated into the prompt.** The sample app did this. Forbidden
  twice over: it is untrusted content in a prompt position, and OCR of a clinical document is not
  an approved record.
- **Document bytes or decrypted document content** (`ConsultationDocument`). Forbidden for a
  separate reason, in section 10.2: it would launder the H-18 document-visibility gate.
- **ASR transcripts.** `Consultation.transcription` is the ungated write path registered as
  H-15.C2 and held closed only by `VOICE_AUDIO_ATTACHMENT_ENABLED = false`. It must not become
  reachable through a second door.
- **Conversation history.** Section 7.

### 4.4 Hard rejects before the model is called

Every one of these is checked at the seam, before the engine is touched, and returns a typed
refusal rather than an empty or truncated prompt:

| Reject | Reason | Evidence |
|---|---|---|
| Empty or whitespace-only question | F5: empty input hallucinated and ran about 2000 tokens | harness |
| Question over the character budget | Prompt-injection surface and latency blowup; a readback question is short by nature | design |
| Snapshot over the token budget | An oversized record silently truncated in the prompt is a record the model answers about incompletely, with no signal that it did | design |
| No committed `kernelDecision` for the case | The record is not physician-approved; readback is not permitted at all | section 4.1 |
| Case unresolvable, or report assembly failed | Nothing to read back. Not a reason to generate | design |

The budgets are numbers the build session sets from measurement, not numbers this memo invents.
What this memo fixes is that both bounds exist and are enforced before the call, in one place.

---

## 5. The two-tier guardrail

### 5.1 Tier mapping, and where `LICENSED_CLINICAL` sits

The tier source is the existing `UserRole.toCadreTier()`
(`app/src/main/java/com/example/samdapp/domain/auth/AuthSession.kt`), which is already the keying
function for the H-18 document gate. No new tier concept is introduced.

**Decision: `LICENSED_CLINICAL` sits in the WORKER tier.** Readback and explain only, same as
`COMMUNITY`. Three reasons:

1. **Consistency with the gate that already exists.** `DocumentAccessAuthorizer` puts
   `LICENSED_CLINICAL` and `COMMUNITY` both in `DENIED_TIER` today, with an explicit note that
   splitting METADATA from ABSTRACTED is a deferred refinement. Putting the SLM's tier boundary in
   a different place than the document gate's would mean two different answers to "is a nurse
   trusted with interpretive clinical content" living in one app.
2. **The scope document supports it.** `docs/domain/phc-workforce-scope.md` tier 2 (staff nurse,
   pharmacist, lab technician) holds real licensed clinical authority within a lane that is
   explicitly not diagnostic interpretation. An open clinical Q and A surface is diagnostic
   interpretation support, which is outside that lane.
3. **The failure it prevents is the H-17 failure.** A pharmacist asking the model an open drug
   question and acting on the answer is precisely "a PHC worker acts on an AI-generated drug
   recommendation that no physician has reviewed".

**CHO note.** `docs/domain/phc-workforce-scope.md` places CHO/MLHP in tier 1, and `toCadreTier`
carries a commented insertion point mapping a future `UserRole.CHO` to `CadreTier.PHYSICIAN`. If
CHO is ever added, that one-line mapping would silently move CHO into the **open** SLM tier. That
is a larger consequence than it was for the document gate. **Recommendation: adding `UserRole.CHO`
requires its own operator decision that names the SLM tier explicitly, not just the document
gate.** Flagged, not decided here.

### 5.2 WORKER tier: allowed and refused

**Allowed:** questions answerable from the approved record in front of the user.

- "Read this prescription back to me in plain language."
- "What does the doctor want the patient to take, and when?"
- "Explain what 'twice daily after food' means for this medicine."
- "Did the doctor agree with the AI's assessment on this case?"

**Refused:** any clinical question not tethered to that record. Interactions, contraindications,
alternatives, dosing that is not in the record, prognosis, "what else could this be", "is this drug
safe with that drug", and anything about a different patient or a hypothetical one.

**Worked refusal (the harness case, F7).** A COMMUNITY-tier or LICENSED_CLINICAL-tier user types
"is ibuprofen safe with lisinopril". In the harness this produced a full drug-interaction answer.
In this design it is refused at the input scope gate, before the model is loaded, because the
question references a drug entity that is not in the approved record's medication lines and asks
for a property (interaction safety) that is not a readback of anything in the record. The model is
never called. Nothing is generated. There is no answer to leak.

Note what this costs and why it is accepted: a nurse with a genuine interaction question gets no
answer from this feature. That is correct. The feature is not an interaction checker, was never
validated as one, and the sample app's willingness to behave like one is exactly the behaviour
being designed out.

### 5.3 DOCTOR tier: allowed and refused

**Allowed:** open querying. A `PHYSICIAN`-tier user may ask free-form clinical questions, with or
without a record attached.

**Still refused, at both tiers:**

- Requests to generate a prescription, a dose, or a drug choice as an actionable output that could
  be committed to a record. The model's output is never a write path. Nothing generated by the SLM
  is ever persisted into `Prescription`, `DiagnosisFeedback`, `Consultation`, or any clinical
  column. Read-only, display-only, always.
- Anything that would put generated text on the exported clinical report or the PDF. The report is
  the physician's document and the model has no authorship of it.
- Input that violates section 4.4 (empty, oversized).

**The doctor tier still gets the output-side controls.** Thought-channel stripping (6.1), the
disclaimer-preservation prohibition (6.2), single-turn (7), audit (9.4). Openness is about scope of
question, not about exemption from the seam.

### 5.4 Enforcement placement: the gate is code, never the prompt

**Scope enforcement is a hard gate at the app seam, evaluated twice: before the model on the input,
and after the model on the output. It is never delegated to the model, and never expressed only as
a system prompt.**

Justification is F4, measured, not assumed: this artifact refuses nothing on its own. A system
prompt saying "only answer questions about the record" is a request to a model that has been
observed not to honour that class of request. It may be included for output quality. It may not be
counted as a control. This memo counts exactly zero controls that live inside the model.

**Input scope gate (pre-model).** Runs after section 4.4's hard rejects. For the WORKER tier it
decides tethered versus untethered. The deterministic checks available without a second model are:
entity containment (does the question reference a drug, dose or condition string that appears in
the snapshot), question-shape classification against an allowlist of readback intents, and a
denylist of interaction/alternative/prognosis phrasings. This is a coarse instrument and it will
have both false refusals and false passes. It is chosen anyway because the alternative, a model
judging its own scope, has been measured not to work (F4), and because the failure direction of a
coarse deterministic gate is refusal, which is the safe direction here.

**Output scope gate (post-model).** Runs on the completed generation, and on the stream as it
accumulates so a violating generation is cancelled rather than displayed and then retracted. It
checks that the answer is grounded in the snapshot: for the WORKER tier, that it introduces no drug
name absent from the record's medication lines and no dosing numeral absent from the record.

**On a post-model scope violation the entire output is suppressed and the refusal surface is shown
instead. The output is never edited into compliance.** Editing generated clinical text into
compliance is meaning-level rewriting, which section 6.2 forbids for good reason. Suppress the
whole thing or show the whole thing.

### 5.5 The tier gate is an accountability control, not a security boundary (H-06)

Stated plainly, because "the doctor tier is open" must not be read as "the doctor tier is verified".

What is true today, precisely:

- Since Phase 6a the device session role is not simply typed and trusted. `BackendAuthSession.signIn`
  derives a `workerId` from name plus role, authenticates against the backend with a PIN, and the
  resulting `UserSession.role` is taken from the server's account record (`login.worker.role`),
  not from the picker.
- What is still absent: any verification that the human holding the phone is a licensed physician.
  There is no NMC registration check, no credential upload, no professional-registry lookup. The
  account's role is whatever the operator seeded, the credential is a PIN on a shared field device,
  and H-06 remains **Open** in `docs/quality/risk-management-file.md` with RBAC enforcement listed
  as the open action.
- The existing gates say the same thing about themselves.
  `DocumentAccessAuthorizer`'s KDoc, `RetractConsultationDocumentUseCase`'s gate, and the H-17
  `canOpenDoctorReview` gate all carry the H-06 caveat explicitly.

**Therefore: the SLM tier gate makes it visible and attributable when someone reaches the open
tier. It does not make it hard.** Anyone who can obtain a DOCTOR account's PIN, or who is handed a
device already signed in as one, gets open clinical querying. The control that survives is the
audit trail: a `userId` and role on every SLM invocation row. That is accountability. Registered as
hazard H-24 in section 12.

### 5.6 How a refusal is presented and redirected

A refusal is a first-class UI state, not an error toast and not a model-authored sentence.

- **It says what was refused and why, in one line.** For example: this feature can explain the
  doctor's approved prescription for this patient, and cannot answer general medicine questions.
- **It does not scold, and it does not imply the question was improper.** The nurse asking about an
  interaction is doing their job. The tool is the thing with the limit.
- **It redirects to the escalation path the app already has:** the referral flow (REQ-REF-01) and
  the out-of-app doctor channel that already carries the AGREE/MODIFY/REJECT loop. The redirect is
  "ask the physician", routed through a surface that exists, not left as advice.
- **It never partially answers.** No hedged half-answer, no "I cannot advise, but generally".
  A refusal that leaks the answer is not a refusal, and F3 tells us this model volunteers hedged
  clinical content readily.
- **It is audited** as a refusal with its reason code, never with the question text (section 9.4).

---

## 6. Output handling at the seam: two opposite requirements

These two requirements point in opposite directions and are stated as such deliberately. One
removes text from the stream. The other forbids removing text from the stream. The line between
them is the whole of this section.

### 6.1 REQUIRED: deterministic thought-channel stripping

**Everything between the `<unused94>` / `thought` and `<unused95>` control tokens is removed and
never rendered.** F6 measured this channel leaking into visible output.

Properties this control must have:

- **Deterministic.** It matches fixed, documented control-token strings from the model's own
  tokenizer vocabulary. No heuristics, no regex over clinical prose, no judgement about content.
- **Stream-safe.** Because output streams token by token (D3), a control token can straddle a chunk
  boundary. The sanitizer holds back a trailing window at least as long as the longest control
  token before emitting, so a partially-arrived `<unused94>` is never rendered as visible text and
  then retracted. Retraction on screen is a leak: the clinician already read it.
- **Fail-closed on an unterminated span.** If a generation ends while inside a thought span, the
  span's content is discarded, not flushed. An unterminated reasoning channel is not an answer.
- **Fail-closed on an unknown control token.** Any other unrecognized special token from the
  reserved range is dropped from the visible stream rather than rendered.
- **Not silent.** Suppression counts (how many spans, how many characters) are audited as measured
  metadata, so a change in the model's channel behaviour after a version bump is detectable rather
  than invisible.

This is control-token removal on a known, structural channel. It carries no clinical meaning by
construction: the channel is defined by the model export, not by what happens to be inside it.

### 6.2 PROHIBITED: meaning-level filtering of the model's own hedges

**MedGemma's own hedges, caveats and disclaimers must be preserved verbatim and must never be
stripped, shortened, softened, or pattern-matched away.** F3 established that this model volunteers
"consult your doctor" style caveats unprompted. That is correct output for a device whose entire
safety argument (H-02) rests on the human in the loop. Removing it makes the output read more
authoritative than the model was willing to be.

**The named anti-pattern: the sample app's `filterDisclaimers()`. It is forbidden. Nothing in this
feature may reimplement it under any name, in the sanitizer, in the prompt, in the UI layer, or as
a post-processing "cleanup" step.**

**The line, stated so a future reviewer can apply it without re-deriving it:**

| Permitted | Forbidden |
|---|---|
| Removing a structurally delimited control-token channel defined by the model export | Pattern-matching clinical prose and deleting what matches |
| A rule that can be stated as a token-vocabulary fact | A rule that requires reading the sentence to decide |
| Deterministic, content-blind | Content-dependent, meaning-level |
| Removes text the model did not intend as an answer | Removes text the model did intend as an answer |

A useful test for any future filter proposal: **if the rule cannot be written without referring to
what the words mean, it is on the forbidden side.** `<unused94>` is a token identity.
"consult your doctor" is a meaning.

Section 5.4's output scope gate sits on the permitted side of this line only because it never
edits: it either passes the whole generation through untouched or suppresses all of it. A filter
that removed the offending sentence and displayed the rest would be on the forbidden side.

---

## 7. Single-turn only

**No conversation history is ever replayed to the model. Each invocation carries exactly one
record snapshot and one question.**

Reasons, in order of weight:

1. **The safe use case is single-turn by nature.** A readback of a fixed record is a function of
   that record. There is no dialogue state that needs carrying.
2. **The artifact is prompt-sensitive and not multi-turn-tuned.** Its behaviour across turns is
   unmeasured, and the harness measured nothing about turn 2.
3. **Multi-turn is a scope-gate bypass.** Every control in section 5 evaluates one question against
   one snapshot. A history buffer lets turn 1 establish context that turn 3's question leans on
   while itself looking tethered. Refusing history removes that class entirely rather than
   defending against it.

**Enforcement, in the type system and in the engine lifecycle, not in a convention:**

- The invocation type has no history field and no list-of-messages field. It carries one snapshot
  and one question string. A caller cannot pass a transcript because there is no parameter for one.
- The engine session is created per invocation and closed after it. No KV cache, no session object,
  and no partial state survives across calls. Loaded model **weights** may be cached (that is a
  cost optimization with no state semantics); conversation state may not.
- The UI keeps no growing thread. Prior answers may remain visible on screen for the user to read,
  but visible history is not input, and nothing on screen is re-sent.
- The check that proves it: a test asserting that two sequential invocations with the same snapshot
  and the same question produce identical prompts, byte for byte. If history were leaking in, they
  would not.

---

## 8. Streaming, and the field-phone reality

**Contract:** tokens are surfaced to the UI as they are produced, after passing through the
sanitizer's hold-back window (6.1). The UI shows a visible generating state from the moment the
call starts, not from the first token.

**The latency reality, from F1.** About 12 tokens per second on a desktop CPU with about 3.35 GiB
resident. A 250-token readback is roughly 21 seconds at that rate; a 500-token one is roughly 42
seconds. The deployment device is a field phone, and the arm64 device this project actually tests
on is the iQOO I2302 used for the ASR egress witness. On that class of hardware the desktop number
is a ceiling, not an estimate, so 20 to 60 seconds and worse is the planning range.

What follows from that, as design requirements rather than observations:

- **Short outputs are a control, not a preference.** A hard `maxOutputTokens` cap, set low, sized to
  a readback. It also bounds F5's runaway (about 2000 tokens on empty input) even if some future
  path reaches the model with thin input.
- **The generating state is visible and honest.** It appears immediately, it does not fake progress,
  and it says the work is happening on the device.
- **Cancellation is always available and is real.** Cancel stops generation and releases the engine,
  it does not merely hide the surface while the CPU keeps burning.
- **Nothing generates in the background or speculatively.** No pre-warming a readback for a case the
  user has not asked about. It costs battery and it generates clinical text nobody requested.
- **Device capability gate.** About 3.35 GiB resident is a serious ask on a low-end field phone; on
  a device that cannot hold it the honest outcome is that the feature is unavailable, presented as
  unavailable. It must never degrade into a shorter or faster substitute output, which is the
  fabricated-fallback failure this project has already closed twice (H-09, H-13).
- **Feature flag, default off.** Consistent with `FeatureFlags` practice on this project: one flag
  gating exactly one reachable affordance, defaulting `false`, flipped by its own decision with its
  own evidence. Proposed name `SLM_READBACK_ENABLED`.

---

## 9. The contract the main app builds against

### 9.1 Where the seam sits

Presentation calls a domain use case. The use case is the guardrail seam. The engine sits behind
it, in the data layer, bound by Hilt.

```
UI (readback surface)
  -> SlmReadbackUseCase                  [domain]
       -> ApprovedRecordSnapshotReader   [section 4.2, must be built]
       -> input hard rejects             [section 4.4]
       -> tier resolution                [CadreTier from the live UserSession]
       -> input scope gate               [section 5.4]
       -> SlmEngine.generate(...)        [data layer, LiteRT-LM]
       -> stream sanitizer               [section 6.1, on every chunk]
       -> output scope gate              [section 5.4, on the accumulating stream]
       -> audit                          [section 9.4]
  -> UI renders sanitized stream, or the refusal surface
```

**Everything the model touches routes through that seam.** One use case, one entry point, one place
to reason about and test the gate. Same architectural argument `DocumentAccessAuthorizer`'s KDoc
makes for having exactly one caller: a check duplicated across a ViewModel and a composable is a
check that will diverge.

The engine interface is deliberately thin and knows nothing about clinical scope. It takes a
prompt and decode parameters and returns a token stream. It must be impossible to reach it from a
ViewModel: no injection of the engine into presentation, ever.

### 9.2 Invocation shape

- **In:** a prompt built by the seam from the approved-record snapshot plus one bounded question.
  The message or prompt construction lives in the seam, never in the UI, and the snapshot is
  inserted into a fixed template with a versioned template id.
- **Out:** a stream of tokens, plus a terminal result (completed, cancelled, refused, engine
  failure). A failure is a failure and is shown as one. There is no substitute output.
- **Decoding: greedy and deterministic.** Temperature 0, no sampling, no top-k or top-p variation.
  Two readbacks of the same approved record produce the same text. A clinical readback that varies
  run to run cannot be reviewed, cannot be reproduced in an incident investigation, and cannot be
  regression-tested.
- **Bounded:** hard `maxOutputTokens`, hard wall-clock timeout, cancellable.
- **Versioned:** the model artifact id, the model version and the prompt-template version are
  carried on every invocation and recorded in the audit payload. Precedent: H-12's
  `derivation_rule_version` exists so an auditor can separate a rule change from a model change.
  The same requirement applies here, and it is what makes a future model bump assessable under the
  CDSCO Algorithm Change Protocol instead of invisible.

### 9.3 Build-flavor posture

No mock SLM in the shared compilation unit. If a stub is wanted for dev, it follows the
`DevClinicalMockModule` pattern exactly: dev-flavor source set only, staging and prod bind an
unavailable implementation that returns an honest unavailable state. H-09 and H-13 are both in the
register because a plausible fabricated value reachable in a non-dev build is this project's
recurring failure mode, and a fabricated **readback of a prescription** would be a worse instance
of it than either.

### 9.4 Audit

New `AuditAction` values are needed. At minimum: an invocation row, a refusal row (input scope), a
suppression row (output scope), and a sanitizer-activity row.

Two constraints carried from existing practice:

- **Payload is measured metadata only.** Case id, tier, model id and version, template version,
  token counts, latency, refusal reason code, suppression counts. **Never the question text, never
  the generated text, never a drug name.** This is the same rule the `VOICE_FIELD_*` breadcrumbs
  follow (measured metadata, never the transcript) and the same rule
  `PRESCRIPTION_APPROVED` follows (never the drug name).
- **The backend enum mirror is updated in the same commit.** `test_audit_actions_device.py` asserts
  set agreement between the device enum and the backend mirror. A device action the mirror does not
  accept is a silent, permanent sync rejection of every row carrying it. This trap is documented in
  `AuditAction`'s own KDoc and has bitten this project before.

**Recorded gap, not counted as mitigation:** the same operational gap H-15 records applies here.
Emitting audit rows creates a detection capability. Nobody reviews them today. There is no
dashboard, no threshold and no procedure, and until there is, the audit trail is accountability
after an incident, not detection during one.

---

## 10. DPDP and CDSCO constraints

### 10.1 This is DPDP, not HIPAA

The governing regime is India's **Digital Personal Data Protection Act 2023**, as recorded in
`docs/regulatory-foundation.md` section 2.4. Patient health data is sensitive personal data;
obligations are lawful consent, **purpose limitation**, **data minimisation**, security safeguards,
breach reporting and data-localisation considerations. HIPAA does not apply and no HIPAA-shaped
reasoning (covered entities, business associates, the US minimum-necessary standard) should be
imported into this design. The distinction matters practically: DPDP purpose limitation binds what
the record may be **used for**, not only who may see it, and generating a new derived artifact from
an approved record is a use.

Consequences:

- **Purpose limitation.** The approved record was collected to deliver care for that case. A plain
  language readback to the person delivering that care is within purpose. Feeding the same record
  into a model to answer an unrelated clinical question is not, which is a second and independent
  reason for the section 5.2 refusal.
- **Data minimisation.** The snapshot carries the minimum needed for a readback and structurally
  excludes identity (4.2 item 3). A readback does not need the patient's name to explain a dose.
- **Localisation, satisfied by construction.** On-device inference means the record does not leave
  the device at all. That is the strongest possible localisation posture, and it is why D2 matters
  beyond cost.

### 10.2 Showing a record through the SLM must not bypass the visibility gates that already exist

This is the sharpest DPDP-adjacent constraint and it is easy to get wrong.

- **The snapshot must be built from the WORKER-audience report for a worker-tier viewer.**
  `ReportAudience.WORKER` redacts PRIVATE ailments (REQ-AIL-02): a redacted line carries no clinical
  text at all. `ReportAudience.PHYSICIAN` shows everything. If the SLM snapshot were assembled with
  the PHYSICIAN audience and then read back to an ASHA worker, the model would speak aloud exactly
  the content the redaction exists to withhold. **The audience passed to assembly must be derived
  from the live viewer's `CadreTier`, at the seam, and must never be a caller-supplied parameter
  the UI can set.**
- **Document content stays out.** `DocumentAccessAuthorizer` denies raw decrypted document content
  to `LICENSED_CLINICAL` and `COMMUNITY` (H-18, Build 3c). Passing document content into the model
  for such a viewer would launder that gate: the bytes would reach them as generated prose instead
  of as a rendered file. Section 4.3 forbids document input outright, and this is the reason it is
  a hard rule rather than a scope preference.
- **The prescription visibility gate is upstream and stays upstream.** The SLM reads a record only
  after `kernelDecision` is committed (4.1), which is the same condition
  `PRESCRIPTION_APPROVAL_GATE_ENABLED` enforces on the report. The SLM must never be the surface
  where an unapproved prescription becomes legible.

### 10.3 The egress claim must be proven, not asserted

On-device is a claim about behaviour and this project has an established standard for proving it,
set by the ASR track: a reflection or bytecode scan for a network-capable path, a measured
`txDelta` and `rxDelta` of 0 bytes across a full generation, a StrictMode run recording no network
call on the inference path, and an airplane-mode witness on the shipped arm64 ABI on real hardware.
The same evidence set is a precondition for any flag flip here. Family or vendor documentation that
LiteRT-LM runs locally is not evidence about this build.

### 10.4 CDSCO framing

- The app's Class B/C determination is genuinely unresolved and this memo does not touch it. What it
  does assert is scoped: **a readback that restates a physician-approved record makes no new
  clinical claim**, and is a presentation transformation of an already-reviewed output. That is the
  narrow claim the feature is designed to keep true.
- **An untethered clinical answer is a new clinical claim**, from an unvalidated generative model,
  shown inside a device whose Class C argument (H-02) is centred on human-in-the-loop review of
  non-autonomous output. Section 5's gate is therefore load-bearing for the classification
  argument, not only for user safety. It should be treated with the seriousness H-02's row assigns
  to the doctor-review step.
- **Algorithm Change Protocol.** Model artifact id, model version and prompt-template version are
  recorded per invocation (9.2) so that a behaviour change can be attributed to a model swap or to
  a template edit. Without that, a future incident is unattributable.
- **HAI-DEF terms.** Accepted for demo. Production distribution of the weights is gated behind a
  separate legal review, which is a **precondition of distribution, not of a dev-flavor build**.
  Same shape as the CC-BY-4.0 attribution item on the ASR track.

---

## 11. Explicit non-carryovers from the sample app

Each of these existed in the sample app and each is forbidden here. Listed so that a build session
can check them off, and so a reviewer can grep for them.

| # | Sample-app behaviour | Why it is forbidden here |
|---|---|---|
| NC-1 | **The fake on-device fallback: fabricated dosing produced on network failure.** | Fabricated clinical content presented as real output. This is H-09 and H-13 in their purest form, both of which this project already closed at real cost. A failure is shown as a failure. There is no substitute output, ever. |
| NC-2 | **`filterDisclaimers()`.** | Meaning-level deletion of the model's own hedges, which makes the output read more authoritative than the model was. Section 6.2. Forbidden under any name. |
| NC-3 | **Unescaped OCR text concatenated into the prompt.** | Untrusted content in a prompt position, and OCR of a document is not an approved record. Section 4.3. |
| NC-4 | **Free text as model input.** | The clinical content the model sees must come from an approved record, not from whatever a user typed. Section 4.1. |
| NC-5 | **No input validation on empty or oversized input.** | F5: empty input hallucinated and ran to about 2000 tokens. Section 4.4. |
| NC-6 | **Rendering raw model output without control-token stripping.** | F6: the reasoning channel leaks into visible output. Section 6.1. |
| NC-7 | **Relying on the model to decline out-of-scope questions.** | F4 and F7: it declines nothing and answered a bare interaction question in full. Section 5.4. |

---

## 12. Hazards to register, drafted verbatim (PROPOSED, not written to the risk file)

These are drafted for a later, separate docs commit into `docs/quality/risk-management-file.md`
section 2, in that file's existing row format and status convention. **They are not written to that
file by this memo.** IDs continue from the highest currently used, H-21. Severity and probability
follow that file's convention of provisional placeholders pending formal scales and clinical review.

---

| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-09, SLM guardrail and service-contract design memo (`scratchpad/slm-guardrail-service-contract-memo.md`)** H-22 | The on-device SLM refuses nothing on its own, so every scope control is external or absent | The MedGemma 1.5 4B int4 artifact selected for on-device readback was measured in an isolation harness on 2026-09-09 to answer out-of-scope prompts rather than decline them. A bare "is ibuprofen safe with lisinopril", with no patient record attached, produced a full drug-interaction answer. Empty input was not handled: it produced hallucinated content and ran to roughly 2000 tokens rather than abstaining, the same failure shape H-20 records for the classifier's empty-input prior. Consequence: any scope boundary expressed as a system prompt or as an instruction to the model is not a control, and a worker-tier user reaching the model with a free-form clinical question would receive a confident, unvalidated, physician-unreviewed clinical answer, which is the H-17 harm arriving through a new door. | High | Med | **Design-stage only, no code exists.** The memo specifies that scope enforcement is a hard gate at the app seam, evaluated twice (input scope before the model, output scope on the generated stream), never delegated to the model, and that zero controls live inside the model. Input hard rejects (empty, oversized, no committed `kernelDecision`) fire before the engine is loaded. Worker-tier untethered questions are refused before the model is called, so nothing is generated to leak. Output-side violations suppress the entire generation rather than editing it. | **Open, and nothing is built.** The input scope gate is a coarse deterministic instrument (entity containment, intent allowlist, phrasing denylist) and will produce both false refusals and false passes; its failure direction is refusal, which is the safe direction, but its accuracy for real worker phrasing is unmeasured. No measurement exists of how often a tethered readback question is wrongly refused, and none exists of how often an untethered one passes. Both are preconditions of a flag flip, not of the design. |

---

| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-09, SLM guardrail and service-contract design memo** H-23 | The model's internal reasoning channel leaks into text shown to a clinician | The artifact emits `<unused94>` / `thought` / `<unused95>` delimited reasoning spans into its visible output, measured in the same 2026-09-09 harness run. Two distinct harms if rendered. First, the reasoning channel is not an answer and is not intended as one: it contains the model's discarded intermediate content, including candidate statements it did not commit to, and a clinician reading it is reading something no part of this design validated. Second, it is unbounded prose in a clinical surface, so a discarded differential or a speculative drug mention can appear on screen next to an approved prescription with no marking separating the two. | Med | High | **Design-stage only, no code exists.** The memo requires deterministic control-token stripping at the seam on every stream chunk, with a hold-back window at least as long as the longest control token so a straddling token is never rendered and then retracted, fail-closed discard of an unterminated span, fail-closed drop of unrecognized reserved tokens, and audited suppression counts so a channel-behaviour change after a model version bump is detectable rather than silent. The memo separately and explicitly forbids meaning-level filtering (the sample app's `filterDisclaimers()`), so this control is bounded to control-token identity and may not grow into prose filtering. | **Open, and nothing is built.** The control-token set is taken from this specific export's vocabulary; a model version bump can change it, and the suppression-count audit is the only planned detector for that. Nobody reviews audit rows today (the same operational gap H-15 records), so the detector is a capability, not a detection. |

---

| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-09, SLM guardrail and service-contract design memo** H-24 | The open doctor tier of the SLM rests on a role that is not credential-verified (composition of H-06 with the new feature) | The SLM design permits open clinical querying for `CadreTier.PHYSICIAN` and restricts every other tier to readback of an approved record. The gate keys off `UserRole.toCadreTier()`, the same function the H-18 document gate uses. Since Phase 6a the session role is taken from the server account record after PIN authentication rather than from the login picker, which is a real improvement over the self-asserted state H-06 was originally written against. What does not exist at any layer is verification that the human holding the device is a licensed physician: there is no NMC registration check, no credential upload, no professional-registry lookup, the account role is whatever the operator seeded, and the credential is a PIN on a shared field device. H-06 remains Open with RBAC enforcement listed as its open action. Consequence: anyone who obtains a DOCTOR account PIN, or is handed a device already signed in as one, has open, unvalidated clinical querying, and the readback-only boundary that the entire safety argument of this feature depends on is bypassed. A second, quieter path: if `UserRole.CHO` is ever added, the commented `CHO -> PHYSICIAN` insertion point in `toCadreTier` would move a mid-level cadre into the open tier as a one-line change. | High | Med | **Design-stage only, no code exists.** The memo states plainly that the tier gate is an accountability control and not a security boundary, in the same terms `DocumentAccessAuthorizer`, `RetractConsultationDocumentUseCase` and the H-17 `canOpenDoctorReview` gate already use. Every SLM invocation is audited with the acting `userId` and tier, so reaching the open tier is attributable after the fact. The memo also flags that adding `UserRole.CHO` must be its own operator decision naming the SLM tier explicitly, not an incidental mapping change. | **Open.** Attribution is not prevention. This row is a composition, in the same sense H-21 is: H-06 is rated tolerable on the assumption that downstream gates hold, and this feature adds a downstream gate that H-06 undercuts. Neither row tests the pair. Closing this needs REQ-SEC-03 RBAC enforcement, which is H-06's own open action, plus a decision on whether professional-credential verification is in scope for the deployment at all. |

---

| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-09, SLM guardrail and service-contract design memo** H-25 | On-device generation latency and memory on a field phone degrade the readback into an unusable or abandoned surface | The artifact was measured at roughly 12 tokens per second with roughly 3.35 GiB resident, on a desktop CPU, with GPU delegation unsupported for this int4 text export. A 250-token readback is roughly 21 seconds at that rate and a 500-token one roughly 42 seconds; the deployment target is a field phone at a rural PHC, where the desktop figure is a ceiling and not an estimate. Harms are usability harms with clinical consequence, in the IEC 62366 sense rather than the direct-injury sense. A worker who waits 40 seconds learns to skip the readback, so the patient-comprehension benefit the feature exists for is not delivered. A worker who cancels mid-generation may act on a partial readback that stopped before the dosing line. A 3.35 GiB working set on a low-end device risks the app being backgrounded and killed mid-consultation, which touches unsaved case state. | Med | High | **Design-stage only, no code exists.** The memo requires a hard low `maxOutputTokens` cap sized to a readback (which also bounds the roughly 2000-token empty-input runaway), a wall-clock timeout, real cancellation that releases the engine rather than hiding the surface, an immediately visible and honest generating state, no background or speculative generation, and a device capability gate whose failure mode is an honest unavailable state. The memo explicitly forbids degrading to a shorter or substitute output on a constrained device, because a fabricated or silently truncated readback is the H-09 and H-13 failure mode with clinical content in it. The feature sits behind `SLM_READBACK_ENABLED`, default `false`. | **Open, and nothing is built.** No measurement exists on the arm64 deployment device (the iQOO I2302 used for the ASR egress witness), so the tokens-per-second figure, the memory headroom and the background-kill risk are all unquantified for the hardware that matters. An on-device latency and memory measurement is a precondition of any flag flip. The abandonment risk is behavioural and cannot be closed by code alone. |

---

## 13. Explicitly out of scope

- **Fine-tuning.** F2 established that readback works correctly with no fine-tuning. A fine-tune
  would add a training pipeline, a dataset provenance question, an artifact-validation burden and a
  CDSCO algorithm-change surface, in exchange for a gap that has not been measured to exist.
  **Out of scope unless a specific, measured gap later demands it.** If that day comes, the trigger
  is a measurement, not an impression.
- **Docker.** At most a stage-speed accelerator for development. Not a deployment path. Out of scope
  by operator decision (D2).
- **Doctor-tier prompt engineering** beyond what the seam requires. The open tier's answer quality
  is not this memo's subject; its boundary is.
- **The production distribution legal review** of HAI-DEF terms. A precondition of distribution,
  tracked separately, not resolvable here.
- **Any code.** Nothing in this memo is built. Section 4.2's reader, the seam, the sanitizer and the
  gates are all "does not exist, must be built".

## 14. Open questions for the operator

1. **`LICENSED_CLINICAL` placement.** Section 5.1 places it in the WORKER tier with three reasons.
   Confirm or overrule. Overruling it means a nurse or pharmacist gets open clinical querying, which
   changes H-24's exposure and touches H-17.
2. **CHO.** Confirm that adding `UserRole.CHO` would require a separate decision naming the SLM
   tier, rather than inheriting `CHO -> PHYSICIAN` from the document gate's mapping.
3. **Who is the readback for.** This memo assumes the worker reads it, possibly aloud to the
   patient. If the patient is to read or hear it directly, language and literacy become first-class
   requirements and the design needs another pass.
4. **Which model version pin.** 9.2 requires the artifact id and version to be recorded. Confirm the
   exact artifact that ships is the one the harness measured, since every finding in section 2 is
   about that artifact and not about the model family.
5. **Refusal wording.** Section 5.6 fixes the shape and the constraints, not the sentence. The final
   wording should be operator-approved before it reaches a user, in the same way H-15's residual
   wording was.

## 15. Summary of findings that are "does not exist, must be built"

1. An approved-record snapshot type with structural PHI exclusion (4.2).
2. A reader that refuses to produce a snapshot unless `kernelDecision` is committed (4.2).
3. The guardrail seam use case, with input hard rejects, tier resolution, and the two scope gates
   (4.4, 5.4, 9.1).
4. The stream sanitizer, with hold-back window and fail-closed behaviour (6.1).
5. The engine binding, dev-flavor-only stub posture, and the honest unavailable state (9.3).
6. New audit actions plus the backend enum mirror in the same commit (9.4).
7. The `SLM_READBACK_ENABLED` flag and the device capability gate (8).
8. The egress evidence set on arm64 hardware (10.3).
9. The on-device latency and memory measurement on the deployment device (H-25 residual).
