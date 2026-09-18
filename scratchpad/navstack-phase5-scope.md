# Phase 5 scope, read-only pass (2026-09-18)

Branch `fix/navstack-process-death`, HEAD `3c1b171`. **No edit has been made to
`docs/quality/risk-management-file.md`.** This document is the row skeleton for operator approval.

Working tree at the start of this pass, the four pre-existing items and nothing else:

```
 M .idea/deploymentTargetSelector.xml
 M backend/README.md
 M backend/docker-compose.yml
?? tools/dev-connect.sh
```

The scope-memo record commit landed as **`eab587f`**, `docs(nav): record the phase 3 and phase 4
scoping passes`.

Evidence is **VERIFIED** (read in this pass) or **NOT VERIFIED** (flagged, and listed again in the
closing section so nothing is written into the risk file on top of an unchecked claim).

---

## 1. Id confirmation, against the live file

Read from `docs/quality/risk-management-file.md` itself, not the memo.

| | Memo asserted | Live file | Verdict |
|---|---|---|---|
| Next free hazard id | H-26 | H-01 through H-25 present, no H-26 | **H-26 confirmed free** |
| Next free residual id | RR-03 | RR-01, RR-02 present, no RR-03 | **RR-03 confirmed free** |

VERIFIED by enumerating every `H-NN` and `RR-NN` token in the file. Neither has moved. The last
three commits to touch the file are `f9c3803`, `c1d72fa`, `82c0fca`, none of which added an id
above H-25 or RR-02.

Note that `H-15.C1` through `H-15.C3` exist as sub-rows of H-15 and do not consume a top-level id.

## 2. The file's actual format, so the rows match it byte for byte

VERIFIED. The hazard register is a seven-column table declared at line 22:

```
| ID | Hazard / hazardous situation | Potential harm | Sev* | Prob* | Risk controls implemented | Residual / open work |
```

The PROPOSED banner lives INSIDE the ID cell, ahead of the id, bolded. The most recent examples
(H-22 through H-25) read:

```
| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-09, SLM guardrail and service-contract design memo** H-24 | ...
```

Residuals are not table rows. They live in `### 4.1 Accepted residual risks` as prose entries
opening `**RR-NN — <title>. Status: ACCEPTED.**`, followed by italic sub-headings used loosely:
RR-01 uses *The risk.*, *Why the schema is deliberately unchanged.*, *Mitigation, and its honest
limit.*; RR-02 uses *The risk.*, *What remains, and is accepted.*

**A wording problem to settle before writing.** Section 4.1 is titled "Accepted residual risks" and
its preamble says an entry there means "the decision is to live with what remains". All three
residuals below are **PROPOSED, not accepted**: nobody has signed off on living with them. Writing
them under a heading that says ACCEPTED would assert an operator decision that has not happened,
which is precisely the falsity this phase is meant to avoid. Options, for your call:

- (a) enter them with `Status: PROPOSED, NOT ACCEPTED.` in place of `Status: ACCEPTED.`, inside
  4.1, and let the status line carry the distinction;
- (b) add `### 4.2 Proposed residual risks (not yet accepted)` and keep 4.1 meaning what it says.

I lean (b): 4.1's preamble is explicit about what membership means, and (a) puts an entry under a
heading that contradicts its own status line. But (b) changes the file's section structure, which
in a regulated document is your decision and not mine.

## 3. H-26 row skeleton

Banner cell, matching the house style exactly:

> `**PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-18, navigation back-stack process-death track, phases 1 to 4 (`scratchpad/navstack-restore-memo.md`, `scratchpad/navstack-phase3-scope.md`, `scratchpad/navstack-phase4-scope.md`)** H-26`

**Hazard / hazardous situation.** Restoring a persisted navigation back stack places a worker into
a clinical screen whose context is no longer the context that was saved. Four failure directions,
each one that phases 1 to 4 actually addressed, not the memo's original list:

1. **Re-entry repeats a clinical write.** A restored screen whose ViewModel acts on construction
   performs the action again: `TranscriptionRoute` transcribes and persists in `init`, and a
   restored `Compounder` without resume arguments re-runs `StartCaseUseCase` and mints a second
   encounter and case record for one visit.
2. **Re-entry offers a decision the case has already passed.** A restored
   `DoctorAssignmentConfirmRoute` for a case already sent offers a second doctor assignment.
3. **Route arguments carry clinical text and identifiers into unprotected storage.** The saved
   stack lands in the Activity's `onSaveInstanceState` Bundle, which is outside SQLCipher and the
   document key.
4. **A stack outlives the session or the build that wrote it.** A stack saved by one worker
   restored under another carries the first worker's patient context; a stack naming a route class
   a later build renamed cannot be deserialized at all.

**Potential harm.** Duplicate or split clinical record for one visit (the
consult-sent-to-two-doctors family, and a `MIGRATION_15_16` one-current-assessment-per-case
violation); wrong-worker patient context; PHI exposure in unencrypted process state; on the
deserialization path, an unrecoverable launch failure if it were unhandled.

**Sev / Prob.** **NOT VERIFIED, and I am not proposing numbers.** Every other row's rating is an
operator clinical judgement, and inventing one here to make the row look complete is the same
defect as dressing a residual as a control. Candidate framing only: severity is plausibly High on
direction 1 (a split record is the failure family H-03 and RR-02 already treat as High), and
probability depends on field low-memory-kill rates nobody here has measured. Yours to set.

**Risk controls implemented.** Only things the code does, each VERIFIED:

| Control | Evidence |
|---|---|
| Ids-only route shape: every route argument is an opaque local id, with three documented exceptions needed by the forward path | `Routes.kt`, phase 1 |
| Save-time PHI transform: the serialized stack is rewritten before encoding, so clinical free text and direct identifiers never reach the Bundle. `ConsultationRoute` becomes `Compounder` in resume shape, `TranscriptionRoute` becomes `AcknowledgementRoute`, the three ABHA-carrying routes drop or become `AbhaEntry`. The live stack is never mutated | `NavBackStackSaver.transformForSave`, `NavBackStackPhiFreeTest`, `NavBackStackSaverTest.savingDoesNotMutateTheLiveStack` |
| Session-keyed discard: a stack saved under one `userId` is discarded rather than adopted by another | `navBackStackSaver`, `NavBackStackSaverTest.aStackSavedByAnotherWorkerIsDiscarded` |
| Null-on-failure Saver: an undeserializable or over-budget stack degrades to `[Home]` instead of throwing out of composition, so a renamed route class cannot produce a launch loop | `NavBackStackSaver`, two budget tests and the corrupt-class test |
| Compounder in-place entry rewrite: once the encounter and case record exist the entry is rewritten to its resume shape, so a restore resumes rather than starts | `AppNavHost`, `NavBackStackPolicyTest` |
| Compounder content-key pin: prevents the rewrite from tearing down the ViewModel and fabricating an `ENCOUNTER_RESUMED` audit row for a visit that never left | `AppNavHost` `clazzContentKey`, `CompounderContentKeyTest` with a control arm on the default key |
| `DoctorAssignmentConfirmRoute` status replace: a case already `SENT_TO_DOCTOR` or `PRESCRIPTION_RECEIVED` navigates away instead of offering a second assignment. An explicit status set, not an ordinal threshold | `DoctorAssignmentConfirmViewModel`, `DoctorAssignmentConfirmViewModelTest`, mutation-checked |
| `nav_stack_restore_discarded` audit action, device and backend, carrying the discard reason and no route contents | `AuditLogger.kt`, `audit_actions_device.py`, `NavRestoreViewModelTest`, backend persisted-row test |
| R8 keep rules for the route package, because the class name is the persisted wire format | `app/proguard-rules.pro` |

**Two controls that must NOT be overclaimed, and the row must say so in the control cell itself:**

- **Home resume gating is structurally inert today.** `resumeSuppressedFor` /
  `backStackContainsCase` cannot fire: `Home` exists only at index 0 and `NavDisplay` draws the
  last entry, so the gate is consulted only when the stack is exactly `[Home]`. It is retained as
  defence in depth against a future multi-pane scene. What actually prevents two live `Compounder`
  entries, and therefore keeps the content key unique, is the single-`Compounder` structural
  property of `AppNavHost`. VERIFIED, and corrected in code and memo in `60e1ed3`.
- **The R8 keep rules are inert** while `optimization { enable = false }`. They are pre-positioned,
  not active. VERIFIED.

**Verification note, and the row must not imply more than this.** Restore behaviour is proven by
the saver unit and instrumented tests (541 JVM, 14 navigation instrumented at HEAD) and by the
scripted deep-stack check `scripts/process_death_check.sh navstack`. That check is **written and
has never been run end to end**: it needs a signed-in session, the backend up, and a worker-driven
deep stack. So the restore path is **harness-proven, not field-proven**, and an end-to-end live
process-death run is OWED. VERIFIED (the script exists, its guard paths were exercised, no full run
has happened).

**Residual / open work cell.** Points to RR-03, the two further residuals below, the standing
dependency in section 5, and the owed end-to-end run.

## 4. Residual rows, as residuals

### RR-03, unrecoverable in-screen state after process death

Status: **PROPOSED, NOT ACCEPTED** (see the 4.1-versus-4.2 question above).

*The risk.* The back stack is restored; the contents of the screen it restores into are not. Typed
registration and medical-background forms, an unsaved vitals form, a typed consultation body, and
any in-flight Pi gateway acquisition with its device provenance marks all live in ViewModel memory
and are gone. The worker sees the right screen with an empty form, which can read as "my work was
saved". VERIFIED: nothing persists this state.

*Deliberate, for the acquisition half.* Persisting a `DEVICE` provenance mark that no acquisition
in this process produced would be a fabricated-provenance claim, the H-13 direction. The design
prefers losing the reading to asserting an unearned one. This is memo decision D5 and it is a
trade, not an oversight.

*What is owed.* The D4 cleared-form notice, below.

### RR-04, no safe restore-time acquisition stop exists

Status: **PROPOSED, NOT ACCEPTED.**

*The risk.* A process death during a Pi gateway acquisition leaves a session open on the gateway
that the device never closes: `stopDeviceAcquisitionUseCase()` runs only from `onStopAcquisition`
and `onCleared`, and process death runs neither. VERIFIED. Bounded: dev flavor only (staging and
prod bind `UnavailableVitalsSource`, which cannot open a session), and the gateway self-completes a
session after one reading, so the window is death during acquisition before the reading arrives.

*Why it is not fixed, and this is the part that makes it a residual rather than an open action.*
`stopSession()` carries no session id, so an unconditional restore-time stop closes whatever
session the gateway currently holds, which after a relaunch could be another worker's live
acquisition. Gating it needs a locally persisted in-flight marker, and none exists, because D5
deliberately does not persist acquisition state. VERIFIED on all three points.

*What would reopen it.* A session id on `stopSession` collapses the blast radius and makes the
unconditional fix safe on its own. Alternatively an in-flight marker recording only that a session
was opened, carrying no reading and no provenance, which would not violate D5's purpose; judged out
of proportion to the harm (one misleading `MALFORMED` rejection on the next start).

*NOT VERIFIED:* that the gateway rejects a second `startSession` while one is open. The gateway
server is not in this repository. The harm statement above must be written so it does not assert
that rejection as fact.

### RR-05, egress-gate test reliability

Status: **PROPOSED, NOT ACCEPTED.**

*The risk.* `AsrEgressTest.aCaptureAndDecodeWritesNoFileToAppStorage` is a pre-distribution
zero-egress gate over vendored ASR native code, and it fails in full-suite runs while passing in
isolation. A gate that flakes is a gate people learn to ignore.

*Cause, corrected.* Not the permission. VERIFIED by four checks: `dumpsys` reports RECORD_AUDIO
`granted=true`; the test passes alone under `am instrument`; it passes alone under a single-class
Gradle run; it fails only in the full suite. The earlier theory that it needed a
`GrantPermissionRule` is disproven and must not be carried into this row.

*Leading hypothesis, NOT a diagnosis.* Cross-test microphone contention through the shared
process-wide recognizer, with `AudioRecord` not fully released between the two classes that drive
the real mic. Fits every observation; the release path was not instrumented and no ordering was
forced.

*Standing constraint.* The fix must never be a relaxed assertion. Relaxing it trades a flake for
undetected egress in code nobody here wrote.

### D4, the cleared-form notice: a RESIDUAL today, not a control

VERIFIED by a fresh read in this pass: no restored-after-restart notice exists anywhere in
`presentation/register/` or `presentation/medicalbackground/`. It was scoped in phase 3 and
deferred; it has not been built. It is therefore recorded as owed work under RR-03, cross-linked,
and **not** listed among H-26's controls.

## 5. Standing dependency: the four SAFE-IF routes

`MedicalBackground`, `PatientSummary`, `ReportRoute` and `ConsultationChainRoute` are safe to
restore into **only because no patient or case delete path and no server-driven purge exists
today**. VERIFIED in phase 3: no `@Delete`, no `deletePatient`, no `DELETE FROM patients` anywhere
in the local data layer.

**Trigger condition.** The day a delete, a retraction that removes rows, or a sync-driven purge
lands, these four become live UNSAFE-on-restore with **no enforcement anywhere**: each would
restore into a screen whose backing row no longer exists, and nothing checks for that.

This is not a control (the code does nothing) and not a residual (there is no gap today). The file
has **no existing standing-dependency concept**: VERIFIED, no match for such a section or phrasing.
Two placements, your call:

- (a) inside H-26's `Residual / open work` cell, explicitly labelled `STANDING DEPENDENCY, not a
  residual`, with the trigger condition. No structural change to the file.
- (b) a new `### 4.2 Standing dependencies` section (or 4.3 if the residual question above takes
  4.2), which names the concept properly but adds structure to a regulated document.

I lean (a) for this one: the dependency is specific to restore safety and belongs with the row it
qualifies, and it keeps the structural change to at most one new section rather than two.

## 6. H-18 cross-links, as pointers only

All three anchors VERIFIED present:

- **ABHA in a durable surface.** H-18 already carries the ABHA-exclusion direction. H-26 points at
  it and states that the save-time transform removes ABHA identifiers from the Bundle by dropping
  or replacing the three routes that carry them. No rewrite of H-18.
- **The `cacheDir` sweep.** H-18 records that the viewer temp file is deleted on clean screen exit
  and swept at every app start (`SaMDApplication.onCreate`, `sweepOrphanedViewerTempFiles`).
  `DocumentViewerRoute` is safe to restore into because re-decrypt is idempotent and the sweep
  runs, so H-26 depends on that property and points at it rather than restating it.
- **Build 3b Option A.** H-18's banner already names it, and `scratchpad/capture-process-death-memo.md`
  exists. `scripts/process_death_check.sh` is shared between that work and this one, now with two
  modes whose expectations about `am kill` are opposite. H-26 points at the shared script.

---

## Everything in this skeleton that is NOT verified against the code

1. **Sev and Prob for H-26.** Deliberately not proposed. Operator clinical judgement.
2. **That the Pi gateway rejects a second `startSession` while one is open** (RR-04). The server is
   not in this repository. The row must not assert it.
3. **The mic-contention hypothesis** (RR-05). Labelled as a hypothesis in the row, because the
   release path was not instrumented and no ordering was forced.
4. **That the deep-stack script passes.** It has never been run end to end. The row says OWED.
5. **Field probability of process death** on the deployment hardware. Nobody here has measured it;
   it feeds item 1 and must not be invented.

Everything else in the skeleton is VERIFIED against code read in this pass or in phases 1 to 4.

## Open questions for the operator, before any prose is written

1. Residual placement: 4.1 with a `PROPOSED, NOT ACCEPTED` status line, or a new 4.2 section.
2. Standing-dependency placement: H-26's residual cell, or its own section.
3. Sev and Prob for H-26.
4. Whether RR-04 and RR-05 should be residuals at all, or open actions on H-26 and on the ASR work
   respectively. RR-05 in particular is a test-reliability item on a different subsystem; it may
   belong to the ASR egress record rather than to this track's risk rows.
