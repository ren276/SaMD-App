# PR 3 STEP 1 design memo: the voice confirmation gate for `impactOnDailyActivities`

Read-only design pass. No production code written, no migration, no controlled doc touched.
Design of record for the track is `scratchpad/asr-field-audit-memo.md` Part B (B.2 provenance,
B.3 gate state model, B.4 breadcrumbs), with the evidence base in
`scratchpad/asr-usecase-research-memo.md` TASK 1 (automation bias) and TASK 2 (formatting
boundary). This memo turns B.3 into a concrete state model against the code as it actually is.

## 0. Ground truth, and one thing that does not match the task's assumption

- Branch `feat/asr-voice-audit-actions`, HEAD `b8e682c`. Working tree clean (only the
  pre-existing untracked `dashboard.html` and `scratchpad/`).
- **PR 1 is merged** to master (`bacb6f6`). `FieldProvenance` and the
  `impactOnDailyActivitiesProvenance` column are on master.
- **PR 2 is NOT merged.** It is committed on this branch (`d3570ab`, `b8e682c`) and open as
  GitHub PR #28. The four `VOICE_FIELD_*` actions and their backend mirror entries are present
  in this working tree, so the design below is grounded in real code, but PR 3 has a merge
  dependency on #28 landing first. Reported rather than treated as merged.
- No contradiction found between the memos and the code. Two places where the memo's wording
  does not survive contact with this repo are called out explicitly in Part A (process death)
  and Part C (`slot.name` implies an enum that does not exist yet).

Code read for this design, all paths real and current:
`presentation/consultation/ConsultationScreen.kt`,
`presentation/consultation/ConsultationViewModel.kt`,
`domain/model/FieldProvenance.kt`, `domain/model/InferenceSource.kt`,
`domain/audit/AuditLogger.kt`, `domain/usecase/ConsultationUseCases.kt`,
`domain/usecase/TranscribeAudioUseCase.kt`,
`data/repository/ConsultationRepositoryImpl.kt`, `data/repository/ResultCatching.kt`,
`domain/DataError.kt`, `domain/repository/ConsultationRepository.kt`,
`domain/transcription/TranscriptionService.kt`,
`data/transcription/AndroidSpeechRecognizerService.kt`,
`presentation/common/PermissionAction.kt`, `config/FeatureFlags.kt`.

---

## PART A: the state model

### A.0 What the screen already is, so the gate can fit inside it

`ConsultationUiState` (`ConsultationViewModel.kt:29`) is a flat data class of primitives with two
derived properties (`canSend` at `:44`, `hasAudioAttachment` at `:45`). `ConsultationActions`
(`:59`) is a `@Stable` interface the ViewModel implements. The composable takes
`(uiState, actions)` (`ConsultationScreen.kt:80`). The impact field today is one plain
`OutlinedTextField` at `ConsultationScreen.kt:188` bound to `actions::onImpactChange`
(`ConsultationViewModel.kt:112`, a one-line `copy`). The send button is gated on
`uiState.canSend` at `ConsultationScreen.kt:228`, and there is a second, screen-level review
dialog (`ConsultationReviewDialog`, `:242`, the H-08 control) that displays the impact value at
`:258`.

The gate must live inside that shape and add as little as possible to it.

### A.1 The four states, and the honest observation that only three are stored

B.3 names four states. Three of them are states the UI sits in; **Rejected is a transition, not a
stored state**. B.3's own wording says Rejected is "terminal for that capture. Emits an audit
breadcrumb, then returns to Idle." Modelling it as a stored flag would create a state the UI
never renders and that nothing can observe. So the stored model is three fields, and Rejected is
the name of an edge.

Proposed additions to `ConsultationUiState`, three flat fields matching the existing flat style
(`isVoiceMode`, `isRecordingVoice` are already flat booleans on this class):

- `impactVoiceSuggestion: String?` (default null). The outstanding suggestion. **Never the field
  value.**
- `isCapturingImpactVoice: Boolean` (default false). Mic live for this field.
- `impactProvenance: FieldProvenance?` (default null). The provenance the committed value will
  carry at save.

| State | Condition | Field shows | `impactProvenance` | `canSend` |
|---|---|---|---|---|
| **Idle** | `!isCapturingImpactVoice && impactVoiceSuggestion == null` | the committed value, editable | `null` (typed or empty), or `VOICE_CONFIRMED` / `VOICE_EDITED` after a completed gate | unaffected |
| **Capturing** | `isCapturingImpactVoice` | the committed value, **unchanged**, label swaps to "Listening..." | unchanged | **false** |
| **Suggested** | `impactVoiceSuggestion != null` | the committed value, **unchanged**; suggestion rendered in an adjacent surface | unchanged until an action is taken | **false** |
| **Rejected** | transition only, not stored | committed value unchanged | unchanged | returns to Idle's value |

`VOICE_UNCONFIRMED` appears in exactly one place in this whole design: the `provenance` field of
the `VOICE_FIELD_SUGGESTED` audit payload (Part C). It is never assigned to
`impactProvenance`, never passed to the use case, never written to a column. That is what makes
the repository refusal (Part B) a backstop rather than the primary mechanism: the primary
mechanism is that no code path constructs it.

### A.2 The load-bearing properties, made concrete

**1. The suggestion sits beside the committed value, never in it.**
`impactVoiceSuggestion` is a separate field from `impactOnDailyActivities`. Nothing reads
`impactVoiceSuggestion` when building the `Consultation`: `onSend`
(`ConsultationViewModel.kt:193`) passes `current.impactOnDailyActivities` only, exactly as
today. A reviewer can check this property by grepping for `impactVoiceSuggestion` and confirming
it appears only in the three gate handlers and the one composable that renders it. Reuses the
existing pattern (one more flat field); adds nothing new.

**2. No timeout, no auto-accept.** There is no `LaunchedEffect` with a delay anywhere in this
design, and no code path clears `impactVoiceSuggestion` except the three explicit user actions
and the failure edges. Stated as a review-checkable property: **if a future diff introduces a
timer that touches `impactVoiceSuggestion`, it is wrong.**

**3. Recomposition and process death.** This is where B.3's literal wording does not survive
contact with the repo, and the honest answer is to deviate and say so.

B.3 asks for the suggestion to survive process death via `rememberSaveable` or
`SavedStateHandle`. **Recommendation: neither, in PR 3.** `ConsultationUiState` lives only in the
ViewModel's `MutableStateFlow` (`ConsultationViewModel.kt:99`). Nothing on this screen survives
process death today: `chiefComplaint`, `onset`, severity, every other field is lost. Persisting
the suggestion alone would resurrect a suggestion attached to a blank form, which is incoherent
and arguably worse than losing it. And losing it **fails safe**: the suggestion is not the field
value, nothing was persisted, no provenance was stamped, so process death leaves exactly the
state the gate is designed to guarantee.

What B.3 actually needs is that the suggestion cannot be dismissed by an accidental
recomposition or a rotation, and ViewModel-held state already gives that: the ViewModel survives
configuration change, so the suggestion survives rotation and every recomposition.

So PR 3 holds the suggestion in `ConsultationUiState`, adds no persistence layer, and documents
the behaviour: **process death discards the suggestion along with the whole draft, by design.**
If true draft survival is wanted it is a separate change covering the entire form, not the
suggestion alone, and it is out of PR 3's scope. Flagged at the DECISION GATE because it is a
deliberate deviation from the design of record.

**4. `canSend` is false while a suggestion is outstanding.** One clause added to the existing
derived property at `ConsultationViewModel.kt:44`:

> current: `chiefComplaint.isNotBlank() && !isSaving`
> proposed: `chiefComplaint.isNotBlank() && !isSaving && impactVoiceSuggestion == null && !isCapturingImpactVoice`

This is the whole guard. It reuses the existing derived-property pattern with no new mechanism,
and because `ConsultationScreen.kt:228` already binds the send button to `canSend`, the worker
cannot even open the H-08 review dialog while a suggestion is outstanding. Blocking at `canSend`
rather than inside the dialog matters: the dialog is a different control at a different
boundary, and stacking the voice gate inside it would train dismissal, which is the failure mode
B.3 warns about and H-02 already records for AGREE.

### A.3 The three actions from Suggested

| Action | Effect on the field | Resulting `impactProvenance` | Suggestion | Breadcrumb |
|---|---|---|---|---|
| **Use it** | committed value := suggestion | `VOICE_CONFIRMED` | cleared | `VOICE_FIELD_CONFIRMED` |
| **Edit** | committed value := suggestion, field focused for editing | `VOICE_EDITED` (pending) | cleared, but the gate is **not** complete | none yet |
| **Discard** | untouched | unchanged | cleared | `VOICE_FIELD_REJECTED` |

**Edit still requires a confirm, and here is the concrete mechanism.** B.3 says Edit "still
requires a confirm". The cheapest honest reading in this screen: after Edit, the text is in the
field and provenance is `VOICE_EDITED`, and the confirm the worker performs is the existing
screen-level one (Review and send). The value is already visible in the field they are editing
and again in the review dialog at `ConsultationScreen.kt:258`, so there is a real second look.
The alternative, a per-field "confirm my edit" tap, adds a fourth state and a second tap for the
same field, which is precisely the "make the tap look more official" move that research memo 1.5
warns does not buy safety. Recommendation: **Edit commits into the field with `VOICE_EDITED` and
relies on the existing review-and-send confirm**, and the `VOICE_FIELD_EDITED` breadcrumb is
emitted at save time (Part C), not at edit time, so it records a confirmed edit rather than an
abandoned one. Flagged as an interpretation of B.3, not a quote from it.

**Provenance transitions for keyboard edits after the gate**, stated so there is no ambiguity:

- Typing into an empty field with `impactProvenance == null`: stays null. `SaveConsultationUseCase`
  stamps `TYPED` at save when the text is non-blank, which is exactly PR 1's current behaviour
  (`ConsultationUseCases.kt:45`, unchanged).
- Typing into a value that is `VOICE_CONFIRMED`: becomes `VOICE_EDITED`. This is B.2's own
  definition of `VOICE_EDITED`, "voice-seeded, then hand-corrected".
- Clearing the field to empty: resets `impactProvenance` to null. An empty field has no
  provenance, which matches the PR 1 review fix that only stamps `TYPED` when a value exists.

### A.4 What the UI adds, and the anti-rubber-stamp constraints on it

New composable: one suggestion surface rendered directly under the impact `OutlinedTextField`
(`ConsultationScreen.kt:188`) when `impactVoiceSuggestion != null`, plus one mic button above it
when the new flag is on. Both are new UI but they reuse existing primitives (`OutlinedButton`,
`Text`, `heightIn(min = 56.dp)`); no new design system pattern.

Constraints, each traceable to research memo TASK 1:

- The suggestion text renders at `bodyLarge` or larger. B.3 sets this floor for `chiefComplaint`;
  applying it here costs nothing.
- Touch targets `heightIn(min = 56.dp)`, matching every other button on this screen.
- **The three actions are visually equal weight.** Do not make "Use it" a filled primary button
  with "Discard" as a small text button. A visual hierarchy that pushes toward accept is a
  rubber-stamp affordance, and section 1.3's evidence (71 percent of ED notes containing an
  error under exactly this control) is about how easily this tap gets performed without reading.
- The mic never mutates the field. The existing screen already gets this wrong for
  `chiefComplaint` in principle and PR 0 fixed it defensively; the new field must not
  reintroduce it.
- Permission via the existing `rememberPermissionAction(Manifest.permission.RECORD_AUDIO, ...)`
  (`presentation/common/PermissionAction.kt:14`), the same call shape as
  `ConsultationScreen.kt:110`. No new permission mechanism.

**Reused vs new, summarised.** Reused: the `*UiState` + `@Stable *Actions` pattern, flat state
fields, the derived-guard pattern, `rememberPermissionAction`, `TranscriptionService` behind its
existing interface, `auditPayload`, the repository `Result` + `DataError` convention. New: three
state fields, three action methods, one suggestion composable, one feature flag, one `DataError`
member, one Levenshtein helper. No new architectural pattern, no new module, no sealed state
hierarchy.

---

## PART B: the write-refusal

### B.1 Where it lives

**`ConsultationRepositoryImpl.saveConsultation`, `data/repository/ConsultationRepositoryImpl.kt:22`.**
Today it is a single line: `asDataResult { consultationDao.insert(consultation.toEntity()) }`.
The refusal goes inside that function, before any DAO call.

This is B.2's explicit instruction, quoted: "the check belongs in the repository implementation
(for the first slice, `data/repository/ConsultationRepositoryImpl.kt`), not in the ViewModel, so
every caller routes through it."

Grounding that claim in the current call graph: `saveConsultation` has exactly one production
caller, `SaveConsultationUseCase` (`domain/usecase/ConsultationUseCases.kt:52`), which itself has
exactly one caller, `ConsultationViewModel.onSend` (`:198`). So today a ViewModel-level check
would be equally effective, and that is precisely why it is the wrong place: the refusal must
survive the second caller that does not exist yet. The repository is the last point every future
path must cross.

Second write path, checked: `ConsultationRepositoryImpl.updateTranscription` (`:30`) writes to
the same table but touches only `transcription` and `updatedAt`, never provenance, so it cannot
carry `VOICE_UNCONFIRMED`. No refusal needed there. Worth a one-line comment so a future reader
does not assume it was missed.

### B.2 Refusal semantics: return a failure, do not throw, never drop

**Return `Result.failure(...)`.** Reasons, in order of weight:

- **Not a silent drop.** A drop returns success while nothing persisted. That is the exact shape
  CLAUDE.md's persisted-row rule exists to catch, and the `_fail()` rollback bug it cites.
- **Not a throw.** `asDataResult` (`data/repository/ResultCatching.kt:9`) catches every
  `Exception` and wraps it as `DataError.Local`, whose message is "Local storage error". A
  deliberate policy refusal reported to the user as a storage error is a lie in the error
  channel. Returning an explicit failure keeps the refusal distinguishable.
- The repo's whole data-layer contract is `Result` plus the sealed `DataError`
  (`domain/DataError.kt`), so a returned failure is the idiomatic shape.

**New `DataError` member.** `DataError` is currently `sealed class` with `Local(cause)` and
`NotFound(what)`. Add a third, for example
`class Refused(reason: String) : DataError("Refused: $reason")`. One line, fits the existing
sealed shape, and gives the ViewModel something it can distinguish if it ever needs to.

**The rule, precisely.** If `consultation.impactOnDailyActivitiesProvenance ==
FieldProvenance.VOICE_UNCONFIRMED`, return the failure and perform no DAO call at all. Checked
before the insert so there is no partial write to roll back.

Stated for the reviewer: the refusal is a **backstop**, not the primary control. The primary
control is that no UI path constructs `VOICE_UNCONFIRMED` (Part A.1). The backstop exists so that
a future screen that forgets the gate cannot get an unconfirmed value into the database, which is
B.2's own justification.

### B.3 Honest-failure behaviour: three failure edges, one rule

The rule: **a failed or empty capture leaves the field exactly as the worker left it.** Never
commit an empty string, never commit an unconfirmed value, never fabricate.

This is the voice-side analogue of the `InferenceSource.UNAVAILABLE` precedent
(`domain/model/InferenceSource.kt`): "`UNAVAILABLE` when the real call failed and no fallback
scenario was produced (staging/prod's honest failure state, no fabricated diagnosis, no mock)."
The same discipline, one layer down: the capture failed, so no value was produced, so the record
says nothing rather than saying something invented.

| Edge | Trigger, grounded in real code | Behaviour |
|---|---|---|
| ASR error | `AndroidSpeechRecognizerService.kt:73` `onError` resumes with an exception, surfaced as `Result.failure` | field untouched, provenance untouched, `isCapturingImpactVoice` false, error message shown, `VOICE_FIELD_REJECTED` |
| **Empty transcript on success** | `AndroidSpeechRecognizerService.kt:65-68` reads the results list and calls `.orEmpty()`, so a **successful** recognition can return `""` | treat as a failed capture. **Never enter Suggested with a blank suggestion.** Field untouched, `VOICE_FIELD_REJECTED` |
| Worker discards | Discard tap | field untouched, `VOICE_FIELD_REJECTED` |

The empty-transcript edge is the one a build would most likely miss, and it is the direct
analogue of the empty-differential-200 bug the repo already fixed once
(`AuditAction.KERNEL_EMPTY_DIFFERENTIAL`, `domain/audit/AuditLogger.kt:91`): a successful
response carrying nothing usable must be routed to the honest-failure state rather than treated
as a real result. Calling that parallel out explicitly so the build does not rediscover it.

### B.4 The tests that prove it

**Test 1, the refusal, androidTest (real Room, asserts the DB).** Per CLAUDE.md's rule that a
write-survived-a-failure-path test must assert the persisted row:

- Build a `Consultation` with `impactOnDailyActivitiesProvenance = VOICE_UNCONFIRMED`, call
  `ConsultationRepositoryImpl.saveConsultation`.
- Assert the returned `Result` is a failure **and** that
  `consultationDao.observeForEncounter(encounterId).first()` is null. The DB assertion is the
  test; the return-value assertion is a bonus.
- **Positive control in the same class:** the same consultation with `VOICE_CONFIRMED` persists
  and reads back with `VOICE_CONFIRMED`. Without this, a repository that fails every write would
  pass the refusal test.
- androidTest because it needs a real Room database. **Must run on device or emulator before
  merge.** PR 1's lesson: the androidTest source set did not even compile at its checkpoint,
  because nothing had built it. Do not repeat that.

**Test 2, honest failure, JVM unit test (`testDevDebugUnitTest`).** With a fake
`TranscriptionService`: an ASR failure and a blank-transcript success each leave
`uiState.impactOnDailyActivities` unchanged, leave `impactProvenance` unchanged, and never reach
`saveConsultationUseCase`. Assert the call count on a fake, not just the resulting state, which
is the shape `FakeTranscriptionService` already established in PR 0.

**Test 3, the guard, JVM unit test.** `canSend` is false while `impactVoiceSuggestion != null`,
and true again after Discard.

---

## PART C: the four breadcrumbs

### C.1 Transition to action

| Transition | `AuditAction` | Emitted at |
|---|---|---|
| Capturing to Suggested, non-blank transcript | `VOICE_FIELD_SUGGESTED` | the moment the suggestion is shown |
| Suggested to Idle via **Use it** | `VOICE_FIELD_CONFIRMED` | the tap |
| Voice-seeded value confirmed after an edit | `VOICE_FIELD_EDITED` | **at save**, not at the Edit tap (Part A.3): it records a confirmed edit, not an abandoned one |
| Discard, ASR error, or blank transcript | `VOICE_FIELD_REJECTED` | the discard tap or the failure edge |

All four exist already, added in PR 2 (`domain/audit/AuditLogger.kt`, `VOICE_FIELD_SUGGESTED`
through `VOICE_FIELD_REJECTED`), with matching backend mirror entries. PR 3 is the first code to
emit them, and needs no further backend change: the accepted-set widening already shipped, which
was the whole point of shipping PR 2 ahead of the caller.

### C.2 Payload, built with the existing helper

Built with `auditPayload(vararg Pair<String, String?>)` (`domain/audit/AuditLogger.kt:95`), the
same helper every existing call site uses. `patientId` and `caseRecordId` travel as
`AuditLogger.log` parameters (`AuditLogger.kt:7`), not inside the payload, matching
`ConsultationViewModel.kt:153`.

| Key | Available in PR 3? | Value |
|---|---|---|
| `slot` | yes | `"IMPACT_ON_DAILY_ACTIVITIES"` |
| `provenance` | yes | `VOICE_UNCONFIRMED` on SUGGESTED, `VOICE_CONFIRMED` / `VOICE_EDITED` on the others, `VOICE_UNCONFIRMED` on REJECTED |
| `asrModelId` | partially | `"android.speech.SpeechRecognizer"`. Honest engine identity, not a fabricated model name |
| `asrModelVersion` | **no** | `null`. The platform recognizer exposes no model version. **Do not invent one.** PR 4's sherpa-onnx fills both fields with real values |
| `charCount` | yes | length of the suggestion (SUGGESTED) or of the committed value (CONFIRMED, EDITED). A measured length, never content |
| `editDistance` | yes | Levenshtein between suggestion and confirmed text, **only on `VOICE_FIELD_EDITED`**. Pure function, no dependency needed |

On `slot`: B.4 writes `"slot" to slot.name`, which implies an enum that does not exist in this
repo. For one field, a `const val` string is the right size and an enum is speculative
structure. Promote to an enum in PR 5 when a second slot exists. Flagged because it is a
deviation from B.4's literal code sketch, not from its intent.

On `asrModelVersion`: emitting a placeholder version string would be exactly the fabrication
pattern H-09 and H-12 exist to prevent. A null is honest and is queryable as "this row predates
the real engine".

### C.3 One proposed addition to B.4, flagged for sign-off

Research memo 1.5, consequence 3, defines the rubber-stamp detector as two signals: "If
confirmations come back with a **median edit distance of zero and a sub-second dwell time**, the
gate is being rubber-stamped and the feature should be pulled for that field. Design the
measurement in from PR 3, not later."

B.4's payload carries `editDistance` but **not dwell time**, so as specified the payload can only
see half the detector. Proposal: add `dwellMs`, the elapsed time from the suggestion being shown
to the action being tapped, on CONFIRMED, EDITED and REJECTED. It is a measured duration, carries
no content and no PHI, and without it the instrumentation the research memo asks for cannot be
built later without a schema change and a backfill gap.

Flagged as an **addition to the design of record**, needing operator sign-off, not slipped in.

### C.4 What must never be in these payloads

The transcript text, the corrected text, any audio URI, any patient name. Stated as a review
check, because this repo already has the anti-pattern in two places and the research memo section
0 calls them out: `ConsultationViewModel.kt:157` and `:183` log `"uri" to captured.uri`, and
`:227` logs the chief complaint text into `CONSULTATION_SAVED`. Those are pre-existing. **PR 3
must not extend the pattern**, and specifically must not add a `uri` key to any of the four new
payloads. Note also that `AndroidSpeechRecognizerService` never writes an audio file at all: the
`uri` it returns is a synthetic `speech-session://<uuid>` key
(`AndroidSpeechRecognizerService.kt:40`) into an in-memory map, so there is no audio artefact to
retain or leak here, which matches research memo 1.4's "do not retain the audio".

---

## PART D: the formatting boundary in code

### D.1 Recommendation: ZERO tidy in PR 3

Show the raw transcript verbatim in the suggestion surface, and store exactly the string the
worker confirmed. Reasons:

- **The confirmed value is the reviewed value.** Any transformation between what is displayed and
  what is stored opens the question "which version did the human actually read", and the entire
  safety argument for this feature is that a human read the thing that got stored. Zero tidy
  makes that identity trivially true.
- Research memo 2.1 requires any tidy to be deterministic, versioned, content-preserving,
  salience-neutral and negation-inert, **and** to be re-derivable against a retained verbatim
  source. Shipping a ruleset means shipping `tidy_rule_version`, a rule table, and a test per
  rule. That is a real amount of surface for a first slice whose purpose is the gate, not the
  text.
- The safest allowed rules (T1 whitespace, T14 Unicode NFC) are invisible to the reader by
  definition, so skipping them costs the worker nothing.
- Nothing downstream needs tidy text. `impactOnDailyActivities` is excluded from every model path
  by `KernelPayload`'s own KDoc, which is precisely why PR 1 chose this field. It is read by a
  human.

Explicitly **not** in PR 3: T2 capitalisation, T3 filler removal, T4/T5 number normalisation, T6
sentence segmentation (NOT SAFE, can rescope a negation), T7 attribution removal, T8
de-duplication, T9 contraction expansion, T11 spell correction, T12 punctuation stripping, T13
truncation. Display segmentation (research memo 2.3's allowed alternative) is also out: the
platform recognizer returns one flat string and exposes no pause boundaries, so there is nothing
to segment on even if it were wanted.

### D.2 If the operator wants the minimum instead

If a minimal tidy is preferred over zero, the only defensible set is **T1-outer (trim leading and
trailing whitespace) plus T14 (Unicode NFC, strip zero-width and control characters)**, nothing
else. Both are SAFE unconditionally in research memo 2.2, and neither can touch a token. It would
ship pinned as `tidy_rule_version = "ASR-TIDY-v1"`, mirroring
`derivation_rule_version = "HAN-07/08-v1"` in `app/domain/kernel_derivation.py`, so a later rule
change is separable from a model change. Do not add a third rule to v1 later without bumping it.

Note the codebase already applies a trim-like normalisation at the save boundary for every field
via `ifBlank { null }` (`ConsultationViewModel.kt:207`), so outer-trim behaviour partially exists
already and is not novel.

### D.3 One validation flagged, not recommended blind

Research memo 2.3 refers to "the numeric-only rejection from the prior memo's A.2" (a narrative
field whose whole confirmed value parses as a number should be rejected). That would be a cheap
one-clause guard on confirm. **I did not read A.2 in this pass**, so I am flagging it as a
candidate rather than asserting what A.2 requires. If it is wanted in PR 3 it should be confirmed
against A.2 first.

---

## PART E: scope and flag re-enable

### E.1 Per-field flag, and the global flag stays false

**Recommendation: add a new, separate flag; do not flip `VOICE_INPUT_ENABLED`.**

`FeatureFlags.VOICE_INPUT_ENABLED` (`config/FeatureFlags.kt:45`) gates **three** affordances at
once, per its own KDoc and the two call sites: the Text/Voice mode toggle and "Record main
concern" (`ConsultationScreen.kt:139-153`) and the "Record audio" attachment button (`:212`).
Flipping it re-enables voice on `chiefComplaint`, which reaches `/api/v1/evaluate` via
`symptomString` and is the **High** severity half of H-15. That is the single thing PR 3 must not
do.

So: `VOICE_INPUT_ENABLED` stays `false`, and PR 3 adds something like
`VOICE_FIELD_IMPACT_ENABLED` (default `false`) gating only the impact-field mic and its
suggestion surface. Two independent flags, each documenting what it holds back.

Confirmed explicitly, no other voice affordance re-enables: `chiefComplaint` voice stays hidden,
the audio attachment button stays hidden, and the Compounder screen's `RECORD_AUDIO` path is not
touched by PR 3 at all.

### E.2 The Item 6 gate: does re-enabling voice reopen the transmission risk? Yes.

Stated plainly, because this is the decision that matters most in this memo.

**The confirmation gate and the off-device transmission risk are orthogonal controls.** The gate
governs what enters the field. It does nothing about where the audio goes. Treating the gate as
mitigating H-15's transmission half would be a serious category error.

The code is unchanged since PR 0 diagnosed it: `AndroidSpeechRecognizerService.kt:57` calls
`SpeechRecognizer.createSpeechRecognizer(context)`, not `createOnDeviceSpeechRecognizer`, and the
intent built at `:58-61` sets `EXTRA_LANGUAGE_MODEL` and `EXTRA_LANGUAGE` but never
`EXTRA_PREFER_OFFLINE`. The app holds `INTERNET`. So the moment any mic affordance is reachable,
patient narrative may again leave the device to a third-party recognizer with no data-processing
agreement and no H-11 coverage. The operator's Item 6 physical-device check, which would tell us
whether it actually does for the deployment locale, is still outstanding per the PR 0 entry in
PROGRESS.md.

Three options:

- **(a) Build the gate dark. Recommended.** PR 3 ships the state model, the refusal, the UI and
  the breadcrumbs with `VOICE_FIELD_IMPACT_ENABLED = false`. Nothing reachable by a user, no
  transmission risk, gate fully built and tested. PR 4 brings the on-device engine and flips the
  flag in the same change that removes the exposure. Consistent with how PR 1 and PR 2 already
  shipped (both added code nothing reaches yet), and it keeps PR 3 purely about the gate.
- **(b) Interim hardening plus flag on.** Switch to `createOnDeviceSpeechRecognizer` where
  available and set `EXTRA_PREFER_OFFLINE`, then enable the impact field only. Weaker than it
  sounds: Android's own documentation says the offline preference "may have no effect" depending
  on the recognizer implementation, so it is best-effort, and it cannot be validated as effective
  until the Item 6 device check lands. It also drags recognizer changes into a PR whose subject
  is the gate.
- **(c) Enable as-is.** Not acceptable. It silently reopens the probable DPDP exposure PR 0
  deliberately closed.

**Recommend (a).** The honest consequence, stated so nobody is surprised at review: PR 3 ships a
feature no user can reach, and H-15's residual can say the gate control *exists*, not that it
*functions*, until PR 4.

---

## PART F: build sub-steps, docs, and model routing

### F.1 Sub-steps, each independently reviewable

| Step | Content | Why separable |
|---|---|---|
| **3a** | `DataError.Refused`, the `VOICE_UNCONFIRMED` refusal in `ConsultationRepositoryImpl.saveConsultation`, the androidTest persisted-row test plus positive control | Highest-value, smallest diff, no UI. Mergeable alone, and it is the risk control the rest merely feeds |
| **3b** | Three `ConsultationUiState` fields, three `ConsultationActions` methods, the ViewModel handlers, the `canSend` clause, the honest-failure edges, JVM tests | The state model. No visible UI yet; testable entirely on the JVM |
| **3c** | The suggestion surface and mic button in `ConsultationScreen.kt`, `VOICE_FIELD_IMPACT_ENABLED` (default false), Compose tests | Pure presentation, gated off |
| **3d** | Breadcrumb emission at the four transitions, payload assembly, Levenshtein helper, `dwellMs` if approved, audit tests | Mechanical once C.2 is settled |

Order matters: 3a before 3b so the backstop exists before anything can produce a suggestion.

### F.2 Docs, per the standing rule

- **Working docs, inline, same commit as each step.** PROGRESS.md entry per step: what landed,
  what does not function yet, the flag state, test counts. Each step states plainly that the
  feature is dark.
- **Controlled docs, PROPOSED, never marked approved by the build.** At 3d, draft the H-15
  residual update in `docs/quality/risk-management-file.md`: the confirmation-gate control now
  **exists in code** (state model, write refusal, four breadcrumbs) and is **disabled by flag**
  pending the on-device engine, so the residual risk is unchanged for users. Mark
  `PROPOSED, AWAITING OPERATOR SIGN-OFF`, matching how PR 2's H-15 note was handled.
- **Flagged for later, not PR 3:** when the flag actually flips (PR 4), H-15's residual and
  `docs/requirements/intended-use-statement.md` §i both need a real update, and that is a
  controlled-doc change requiring sign-off in its own right, not a footnote to a build step.

### F.3 Model routing

- **3a and 3b on Opus.** Both carry design that is the safety argument itself: the refusal
  semantics (fail versus throw versus drop, and the honest-failure edges) and the state model
  invariants. These are where a plausible-looking wrong choice is expensive.
- **3c and 3d on Sonnet.** Presentation wiring and payload assembly against a settled spec.
- Every step keeps the persisted-row test convention and the androidTest-runs-on-device
  requirement regardless of model.

---

## DECISION GATE

Six items. The first five are the ones the task named; the sixth is the addition this memo
proposes to the design of record.

1. **State-model shape.** Three flat fields on `ConsultationUiState`
   (`impactVoiceSuggestion`, `isCapturingImpactVoice`, `impactProvenance`) with Rejected modelled
   as a transition rather than a stored state. *Recommended.* Alternative: a sealed
   `VoiceFieldState` type, which is worth it at two or more voice fields, not at one.

2. **`rememberSaveable` vs `SavedStateHandle` for process death.** *Recommendation: neither in
   PR 3.* ViewModel-held state only; process death discards the suggestion along with the whole
   draft, which fails safe. This is a deliberate deviation from B.3's literal wording and needs
   sign-off. Alternative: `SavedStateHandle`, which would make the suggestion outlive the field
   value it belongs to unless the whole form is persisted, a much larger change.

3. **Per-field vs global flag.** *Recommendation: a new `VOICE_FIELD_IMPACT_ENABLED`, with
   `VOICE_INPUT_ENABLED` left `false`,* so `chiefComplaint` voice and the audio attachment stay
   hidden. Alternative: flipping the global flag, which re-enables the High-severity
   `chiefComplaint` path and should be rejected.

4. **The Item 6 / platform-recognizer transmission decision.** *Recommendation: option (a), build
   the gate dark in PR 3 and re-enable voice only in PR 4 with the on-device engine.* The gate
   does not mitigate the transmission risk; they are orthogonal. Alternative (b), interim
   `EXTRA_PREFER_OFFLINE` plus on-device recognizer with the impact field enabled, is best-effort
   only and cannot be validated until the outstanding Item 6 device check lands.

5. **Zero tidy vs minimal tidy.** *Recommendation: zero tidy in PR 3,* raw transcript displayed
   and stored verbatim, so the confirmed value and the reviewed value are provably identical.
   Alternative: T1-outer plus T14 only, shipped as `tidy_rule_version = "ASR-TIDY-v1"`.

6. **`dwellMs` in the audit payload, an addition to B.4.** Research memo 1.5 defines the
   rubber-stamp detector as edit distance **and** dwell time; B.4's payload carries only the
   former. Adding `dwellMs` now avoids a later schema change and a permanent gap in the
   instrumentation. Not content, not PHI. Needs sign-off as a change to the design of record.

Two smaller interpretations recorded so they are not mistaken for the memo's own wording:
`slot` ships as a string constant rather than B.4's implied enum (Part C.2), and Edit's required
confirm is the existing screen-level review-and-send rather than a new per-field second tap
(Part A.3).
