# PR-7: the flag and the read-back UI. The SLM wiring is closed.

Branch `feat/slm-backend-proxy`, on top of PR-6 at `06f6fd8`. One commit, not pushed. The four
pre-existing local changes (`.idea/deploymentTargetSelector.xml`, `backend/README.md`,
`backend/docker-compose.yml`, `tools/dev-connect.sh`) were left dirty and untouched throughout.

Every claim below is marked MEASURED or INFERRED.

**No backend test was run, and none is claimed.** MEASURED: `git status` shows no change under
`backend/`, so there was nothing on that side to verify. The `backend/README.md` and
`backend/docker-compose.yml` entries in the working tree are two of the four pre-existing local
changes and are not this PR's.

---

## 1. The flag

`FeatureFlags.SLM_READBACK_ENABLED`, `false`, in every flavor.

**MEASURED, how the existing flags in that file are wired.** There are two mechanisms and only two.
`SCREEN_SECURITY_ENABLED` is a `val` read from `BuildConfig`, set per flavor in
`app/build.gradle.kts` (`false` for dev so demo recordings work, `true` for staging and prod). Every
other flag in the file, nine of them, is a `const val` literal with the same value in every flavor:
`IDLE_LOCK_ENABLED`, `PATIENT_AUDIT_ENABLED`, `RESUME_DRAFT_ENABLED`,
`DEVICE_RESOURCE_CHECK_ENABLED`, the five `VOICE_*` flags and `PRESCRIPTION_APPROVAL_GATE_ENABLED`.

This one follows the `const val` majority, which is also the mechanism every "not yet safe to reach"
flag uses. A `BuildConfig` field would be a per-flavor knob with no correct second position: there
is no build in which the read-back should be on today, so the flavor axis has nothing to say about
it. No second mechanism was invented.

### 1.1 The one thing that is not like the other flags

The flag is **injected** rather than read inline, through a new `di/FeatureFlagModule` and a
`@SlmReadbackEnabled` qualifier. It is the only flag in the app that is, and the reason is the
brief's own requirement that the flag-off property be **proved rather than asserted**.

A `const val` cannot be moved at runtime. A guard that reads it inline can therefore only ever be
exercised on the shipped `false`, and a test written against that passes identically whether the
guard checks the flag, checks something unrelated, or is `if (false)`. It reports the property
satisfied while proving nothing about the thing it names, which is the eleven-instance standing
rule's exact shape.

That is not a theoretical worry here. MEASURED: the first version of this guard covered only
`onOpenReadback`, and left `onAskReadback` ungated, so the use case had a reachable caller in a
build where the feature is off. The test that caught it could only catch it because the flag was
injectable by then. See §6.

Deliberately not generalised to the other nine flags. They have no such test pressure today, and a
module providing ten booleans would be scaffolding for a need nobody has.

### 1.2 What the flag gates, and what it does not

It gates the **entry point**, not the binding. `RemoteSlmEngine` is bound in every flavor (PR-6) and
stays bound. With the flag off there are three guards between a worker and a generation, and they
are in different places on purpose:

| Guard | Where | What it stops |
|---|---|---|
| `canOpenReadback` | `ReportUiState`, read by the screen | The button is not drawn. Hidden, not disabled |
| `canOpenReadback` | `onOpenReadback` | The sheet cannot be opened by any caller |
| `canOpenReadback` + `showReadbackSheet` | `onAskReadback` | A generation cannot be started |

The third row is the one that matters and it is the one that was missing. The sheet check in it is
not redundancy with the flag check: it is the stronger statement and it still holds the day the flag
is turned on, because a generation started behind a closed surface is one the worker never sees and
can never stop, and it costs a full generation on a single-worker service either way.

---

## 2. The copy, written before the code

Eighteen refusals, eighteen first-class states, no generic fallback. Memo §5.6 requires it and H-14
is in the register because an absence that carries no signal is its own defect.

The strings live in `res/values/strings.xml` with their rules in comments, following S-4's
`kernel_failure_*` and S-3's `failed_sync_*`. `SlmRefusalCopy.kt` maps a refusal to a `@StringRes`
pair plus a retry offer, and is total over the enum with no `else`, so a new refusal value is a
compile error rather than a silent fall-through.

**The convention fork is now three features to the rest, and it is still undecided.** MEASURED:
before this PR `strings.xml` held 51 strings across exactly two features; it now holds 100 across
three. Everything else user-facing in the app is still an inline constant. This is the third time in
a row a feature has recorded the fork rather than resolving it, which is itself the finding: three
consecutive authors have each judged their own feature too small a mandate to settle it. It is not
getting decided by accident, and it needs an owner.

### 2.1 The rules this block adds

Beyond S-4's five, four more, because this feature refuses more often than it answers by design:

1. **A refusal is not a fault.** Most of these are a gate doing its job. Nothing may read as "the
   app is broken" unless it is (`assembly_failed` and `rejected_input` are the only two that do).
2. **Never imply the record is wrong.** The report above the sheet is the clinical document and it
   is complete. Several strings send the worker back to it, because reading the report to the
   patient is always available and always correct, and it is the remedy for every refusal with no
   button.
3. **Never show or promise partial text.** A withheld answer is withheld whole.
4. **No button the decode cannot honour.** See §2.3.

### 2.2 The table

| `SlmRefusal` | Title | Offer | Why that offer |
|---|---|---|---|
| `EMPTY_QUESTION` | Type a question first | Ask again | The question is the thing to change |
| `QUESTION_TOO_LONG` | That question is too long | Ask again | Shorten it |
| `RECORD_TOO_LARGE` | This visit is too long to read back | None | Nothing about the question fixes it; the report is the remedy |
| `NOT_APPROVED` | The doctor has not approved this visit yet | None | Wait for a decision. Not an error |
| `CASE_UNRESOLVABLE` | This visit could not be opened | None | Re-open from the patient's record |
| `ASSEMBLY_FAILED` | Something went wrong in this app | None | A real defect, named as one, escalated |
| `OUT_OF_SCOPE_PHRASING` | This can only read back what is written | Ask again | Says what it can do, not that the worker erred |
| `OUT_OF_SCOPE_INTENT` | Ask about this visit | Ask again | Offers three examples |
| `OUT_OF_SCOPE_UNTETHERED` | That is not on this prescription | Ask again | Points at the report |
| `OUTPUT_NOT_GROUNDED` | That cannot be answered from this record | Ask again | See §2.3 |
| `ENGINE_UNREACHABLE` | This phone is not connected | None | Pressing does not create signal. The report still works |
| `SECURE_CONNECTION_FAILED` | The connection is not secure | **None, and says do not try again** | See §2.3 |
| `ENGINE_TIMEOUT` | The read-back took too long | **Try again** | Nothing was generated; a second attempt is a real re-roll |
| `ENGINE_UNAVAILABLE` | The read-back is not available right now | None | Four states, all "wait, then try". A button invites skipping the wait |
| `ENGINE_REJECTED_INPUT` | The read-back could not be sent | None | Unretryable unchanged; a build emitting these is misconfigured |
| `ENGINE_FAILED` | The read-back did not work | **Try again** | Cause unknown to the device, so it may have been transient |
| `OUTPUT_TRUNCATED` | The read-back was cut short | Ask again | See §2.3 |
| `SERVED_MODEL_MISMATCH` | The read-back cannot be trusted right now | **None, and says do not try again** | A safety stop, not fixable from the handset |

### 2.3 The three that needed care, and a finding that came out of them

**`OUTPUT_NOT_GROUNDED` is the gate working.** The copy had to do three things at once: not imply the
patient's record is wrong (it is not, and it is what the worker is being sent back to), not imply the
app broke (it did not, the control fired), and not hint that a real answer exists behind the refusal
(the generation is suppressed whole, never edited into compliance). It says what the limit is and
sends the worker to a question the record can answer.

**`OUTPUT_TRUNCATED` must not read as a system error and must not offer the partial text.** Nothing
failed: the service answered, correctly, and honestly reported that the answer was incomplete, which
is the only signal in the entire system that can catch this. There is no partial text to offer and
the copy must not suggest there is, which is structural rather than a rule the UI keeps:
`SlmReadbackResult.Refused` carries a reason and nothing else, `ReadbackState.Refused` mirrors it
with one field, and a test asserts both by reflection.

**`SECURE_CONNECTION_FAILED` must not invite a retry, and its copy says so in those words.** It is
the one string in the block that tells a worker not to press again. PR-6 added the class precisely
because `ENGINE_UNREACHABLE` and `ENGINE_FAILED` both carry "keep tapping", and one cause of a TLS
failure is interception, where tapping again means sending a physician's free-text diagnosis and a
full prescription line set once more to whoever is reading them.

**The finding: "Try again" is wrong for more refusals than it first looks, and the reason is
determinism.** Writing the table surfaced it. `slm-service-contract.md` §2.1 fixes `temperature` at
0 and `do_sample` at false, and PR-6's binding sends a constant seed, so **the same question against
the same record produces the same answer byte for byte.** Every refusal caused by the *content* or
*length* of a completed generation therefore cannot be cleared by pressing again; it can only be
cleared by asking something else.

That contradicts `SlmRefusal.OUTPUT_TRUNCATED`'s own KDoc, which said "Retryable: a second
generation may fit". That sentence was written in PR-2, before the decode parameters were settled
and before any transport existed to send them. **The KDoc is corrected in this PR**, and the copy
offers "Ask something else" rather than a button that is provably incapable of working. A worker who
presses a dead button three times with a patient waiting learns not to believe the screen, which is
the cost the whole copy block exists to avoid.

Pinned as a general rule rather than two special cases: a test asserts that **only**
`ENGINE_TIMEOUT` and `ENGINE_FAILED` offer an immediate retry, so a future third cannot be added
without that failing.

---

## 3. The surface

### 3.1 Where it is entered from, MEASURED

`presentation/report/ReportScreen`, below the report and below the export and referral buttons.

The decision is not a judgement call, it is the same call the domain already made.
`ReportViewModel` calls `assembleReportUseCase(caseRecordId, ReportAudience.WORKER)`, and
`ApprovedRecordReader`, which is what the read-back seam builds its snapshot from, calls **the same
use case with the same audience**. MEASURED by reading both: they are the only two callers of
`AssembleReportUseCase` in the app. The report screen is therefore not merely a place an approved
record is viewed, it is the place that already holds the exact object the read-back restates, and
it already holds the `kernelDecision` that decides whether a read-back is permitted at all.

`PatientSummaryScreen` was the other candidate and is the wrong one: it is a summary that
`flatMapLatest`es over six repositories and does not assemble a report.

**No new navigation idiom.** A `ModalBottomSheet` over the report, driven by a boolean in the
ViewModel's state, which is exactly the shape the screen's own `ReferralSheet` already has. A route
of its own would have needed a new `NavKey`, an entry in `SECURED_ROUTE_TYPES`, a row in the
`NavBackStackPhiFreeTest` fixture and a decision about what it restores to. A sheet needs none of
those.

### 3.2 The generating state

Entered **before the request, not on the first byte**, and it is a state rather than a spinner the
transport toggles. There is no token trickle to wait for: the grounding gate withholds the entire
generation until it completes, so the first thing that ever arrives is the whole answer or a
refusal. A state entered on the first byte would be a state that never appeared.

It is `SamdLoadingIndicator`, the app's existing indeterminate three-dot indicator, and there is no
bar and no percentage. Nothing on this device knows how far along a generation is, and a progress
bar that is invented is a lie a worker calibrates against. The copy tells them roughly what to
expect instead: PR-5 MEASURED p50 at 4.36 s and a 512-token answer at 10.8 s.

The ordering is pinned by a test that asserts the state is `Generating` **without** advancing the
test dispatcher, so a coroutine that has been launched but not dispatched cannot satisfy it.

### 3.3 Cancellation, and what it does not do

Two ways to abandon: a **Stop** button in the generating state, and dismissing the sheet, which is
the "taps away" case. Both cancel the job. Stop returns to the question box with the question
intact, so a worker who stopped because it was slow need not retype; dismiss clears everything.

**Device-side, exactly:** the coroutine stops, so no chunk is delivered, nothing is displayed and
the seam produces no result; the binding's `finally` releases its mutex, so the next read-back is
not locked out; the binding cleared its envelope before it called, so no stale identity or finish
reason is left readable; and Retrofit cancels the OkHttp call, which closes the socket.

**It does not stop the GPU.** The generation on the service runs on to its token ceiling, holding
VRAM, and on a single-worker service it blocks the next request. This layer cannot fix that and it
is not a gap in this PR: `SlmEngine`'s own KDoc says so, and making cancellation real is the
service's obligation through client-disconnect detection, which is PR-5's. Until that exists, a
cancelled read-back costs a full generation's GPU time and an immediate retry has two resident.

### 3.4 Single turn, enforced by the type

`ReadbackState` is a sealed interface with four states. There is **one** question in the state and
**one** answer slot, they are different states of the same surface, and there is no list of
anything. A conversation view is not discouraged, it is unrepresentable, because there is nothing to
append a turn to. That is the third instance of the same construction on this path: the seam
enforces single turn by having no history field, the wire contract enforces it the same way, and
this is the layer where a transcript would actually be built.

It follows that the question input and an answer can never be on screen together, which a pure
predicate asserts. "Ask something else" returns to the input with **both** the previous question and
the previous answer cleared, so nothing from the last turn is on screen and nothing travels with the
next one.

### 3.5 Process death

Checked against PR #57's saveable pattern, and the answer is that the read-back state is
**deliberately not saveable**.

A generation cannot survive process death: the HTTP call is gone, the binding's device-side mutex is
gone, and the envelope the identity and completeness gates read is gone with the binding's
per-generation state. Restoring `Generating` would restore a spinner with nothing behind it, which
is the broken state that track exists to prevent rather than an example of surviving one. So the
state dies with the process and a restore lands on a closed sheet over a re-assembled report, which
is a correct screen.

Nothing in the nav layer changed. `ReportRoute(caseRecordId)` is already ids-only, is already in
`SECURED_ROUTE_TYPES`, and this PR adds no `NavKey`, so the saver, the content key and the PHI-free
fixture are all untouched.

---

## 4. Composition cost, MEASURED

The entry point is not Home, so the F2A-01 fan-out is not this PR's context. The measurement for
the screen that is:

| | `ReportViewModel` before | after | `HomeViewModel`, for contrast |
|---|---|---|---|
| Flow collectors (`collect` / `stateIn` / `combine` / `flatMapLatest`) | **0** | **0** | 6 |
| `viewModelScope.launch` | 3 | 4 | 7 |

**PR-7 adds zero collectors.** The one launch it adds is tap-triggered and runs once per read-back,
not a subscription held for the screen's lifetime. `ReportViewModel` had no collectors before and
has none now: its `init` is a single one-shot `assembleReportUseCase(...).fold` plus one
`authSession.currentUser().first()`, which is a take-one and not a subscription.

On the composable side, `ReportScreen` already holds five `produceState` blocks, all pre-existing
and all in `ReportContent` for the report page bitmaps. PR-7 adds one `OutlinedButton` inside the
existing `Column`, guarded by `canOpenReadback`, and one `ModalBottomSheet` guarded by
`showReadbackSheet`. **In every shipped build today both guards are false**, so the composition cost
of this PR in a released APK is one boolean read per recomposition of `ReportContent`.

---

## 5. Tests and mutation checks

| Suite | Tests | Failures | Pre-existing failures |
|---|---|---|---|
| `:app:testDevDebugUnitTest` | 713 | 0 | 0 |
| `:app:testStagingDebugUnitTest` | 680 | 0 | 0 |
| `:app:testProdDebugUnitTest` | 680 | 0 | 0 |
| backend `pytest` | **not run** | | |

PR-6 left all three flavors green at 688 / 655 / 655, so there are no pre-existing failures to
separate out; this PR adds 25 tests to each. `assembleDevDebug`, `assembleStagingDebug` and
`assembleProdDebug` all succeed, which is the Hilt graph check and is what proves the new
`@SlmReadbackEnabled Boolean` binding and the `dagger.Lazy<ReportPdfExporter>` resolve in every
flavor.

**The backend suite was not run and no stub run is claimed.** Nothing under `backend/` changed.

### 5.1 The harness, changed because of what PR-6 found

PR-6's harness deleted the JUnit results directory before every run, which makes the test task out
of date and forces a re-run. That is how it masked a real Gradle input hole: a mutation went red for
a reason that had nothing to do with whether Gradle knew the source had changed.

**This harness does not delete outputs.** The mutation itself has to be what invalidates the task. A
run that produces no fresh XML is scored `RED-STALE`, which is counted as a **failure of the harness
or the build inputs**, never as a caught defect. MEASURED: that classifier fired once, on an
unmutated baseline re-run where `UP-TO-DATE` was the correct answer, and that case is now handled by
reading the existing results for an unmutated tree rather than by weakening the rule. No mutation
scored `RED-STALE`, which is independent evidence that PR-6's `src` input fix is holding.

### 5.2 Results: 18 applied, 17 RED, 1 GREEN and explained

Restore is by content snapshot with a SHA-256 comparison, never `git checkout`.

| # | Property broken | Verdict | Caught by |
|---|---|---|---|
| M1 | the flag ships off in every flavor | RED | `the flag as shipped is off in every flavor` |
| M2 | the flag alone can close the entry point | RED | the predicate test + the reachability test |
| M3 | the flag check on `onAskReadback` | **GREEN** | nothing. See §5.3 |
| M3b | **both** guards on `onAskReadback` | RED | `a generation cannot be started behind a closed sheet` + 1 |
| M4 | a generation cannot start behind a closed sheet | RED | `a generation cannot be started behind a closed sheet` |
| M5 | the generating state precedes the request | RED | 3 tests, incl. the dispatcher-not-advanced one |
| M6 | no input on screen beside an answer | RED | `the question input and an answer are never on screen together` |
| M7 | "ask something else" clears the question | RED | `asking something else clears...` |
| M8 | Stop actually cancels | RED | `stopping a generation leaves no partial state...` |
| M9 | tapping away cancels and clears | RED | `tapping away cancels the generation and clears everything` |
| M10 | a TLS failure never invites a retry | RED | 3 tests |
| M11 | a truncated answer offers a different question | RED | 3 tests |
| M12 | an unverifiable model never invites a retry | RED | 2 tests |
| M13 | no two refusals share a copy pair | RED | 2 tests |
| M14 | a refusal has no field text could travel in | RED | `the readback state has no slot a conversation could be built in` |
| M15 | a refusal never arrives as an answer | RED | 2 tests |
| M16 | an over-long question cannot be asked | RED | `an over-long question is refused before the engine is reached` |
| M17 | a blank question cannot be asked | RED | `a blank question cannot be asked` |

### 5.3 Why M3 is green, reported rather than hidden

Removing the flag check from `onAskReadback` and leaving the sheet check fails nothing. That is not
a missing test, it is a true statement about the code: the sheet cannot be opened with the flag off,
so the flag check on that method is unreachable-redundant **given** the sheet check. No test can
distinguish it, because the state it guards against, flag off with the sheet open, does not exist.

M3b is what closes the evidence: removing **both** lines turns the suite red, so the pair is
load-bearing and the redundancy is defence in depth rather than decoration. The line is kept, and
the reason it is kept is written next to it: it holds if a future caller ever opens the sheet by
another route.

---

## 6. What a test caught that reasoning did not

**The flag guard had a hole, and it is the one thing in this PR that would have shipped wrong.**
`onOpenReadback` was gated on the flag and `onAskReadback` was not. Reasoning said that was fine,
and the reasoning was even correct as far as it went: the sheet is the only caller, the sheet cannot
open with the flag off, so nothing reaches the use case. The test that asserted "with the flag off
the use case is never reached" failed anyway, with `expected:<0> but was:<1>`, because it called the
action directly rather than through the sheet.

That is exactly the reasoning `TranscribeAudioUseCase`'s KDoc rejects for its own flag, in this
repository, in writing: the guard has to hold for **any** caller, not only for the one screen that
has one today. The same argument had already been made and written down, and it still did not stop
the same shape being built again. The fix added both the flag check and the stronger sheet check.

Two smaller things, both corrections to my own assertions rather than defects:

- A test asserted the sheet stays open after Stop while the flag was off, so the sheet had never
  opened. The assertion was right about the behaviour and wrong about the setup; fixing it is what
  forced the flag to be injectable, which is what made §1.1 necessary and §6's finding catchable.
- Two question-validity tests initially asserted only that the engine was not reached. With the flag
  off that is true whatever the question says, so they would have passed for the wrong reason. They
  now assert the pure predicate as well, and assert that a well-formed question **is** askable, so
  they cannot pass by refusing everything.

---

## 7. What the read-back now does, end to end

A worker on the report screen for a case a physician has decided taps **Read this back in plain
language**, which is drawn only when `SLM_READBACK_ENABLED` is on and the report carries a committed
`kernelDecision`; a sheet opens with one question box and no history. They type one question and
press Ask, which is refused locally if it is blank or over 500 characters, and the sheet shows an
indeterminate waiting state from the moment the request starts. `SlmReadbackUseCase` then runs the
gates in order: the two input hard rejects; `ApprovedRecordReader`, which resolves the case, derives
the report audience from the live session, and refuses unless a physician decision is committed; the
6000-character prompt budget, refused rather than trimmed; tier resolution from the live session,
fail-closed to WORKER; and the WORKER-tier input scope gate, on whose refusal the engine is never
called at all. `RemoteSlmEngine` then takes a device-side mutex so a second tap cannot queue a
duplicate, clears its envelope, and makes **one** authenticated call to `POST /api/v1/slm/readback`
on `backend/core` with a 60 s whole-call budget, sending the case id, the prompt, this build's
pinned `model_id`, the template version and a constant seed, and never `temperature`, `do_sample` or
`stop`. The backend resolves and facility-scopes the case, runs its PHI guard, checks its circuit
breaker, and forwards once, with no retry, to the generation service, writing exactly one
`slm_call_log` row and one audit row out of band whatever happens. The reply comes back as one
chunk through the stream sanitizer, which strips the artifact's control tokens and counts what it
removed; then the identity gate, which refuses unless the envelope's `model_id` **and** `model_sha256`
are both present and match this build's pin; then the completeness gate, which refuses unless
`finish_reason` is `stop` or `stop_sequence`; then the WORKER-tier output grounding gate, which
suppresses the whole generation rather than editing it if it introduces any drug name or numeral the
approved record did not already contain. What reaches the sheet is either the generation verbatim,
with a line saying the report above wins if the two differ, or one of eighteen typed refusals with
its own title, its own body and an offer that is "ask something else", "try again" or nothing at
all, chosen by whether that action can actually work. A refusal carries a reason and no text, at
every layer, so there is never partial generated content on screen. At any point the worker can
press Stop or tap away, which cancels the call and the coroutine and leaves nothing behind on the
device, though the GPU keeps generating until the service learns to detect a disconnect.

---

## 8. What remains before the flag could be turned on

Not softened. In rough order of how far each is from being closeable:

1. **The generation service does not exist.** Not "is unfinished": there is no service in this
   repository. The whole of `slm-service-contract.md` is a requirement on a component nobody here
   has written. Everything above it is a device and backend half talking to a specification.
2. **The GPU host cannot currently run one.** Its Docker filesystem is at 100 percent, so the
   container path, the GPU reservation and the loopback publish are configured and **unproven**.
   Nothing has been observed to start.
3. **No end-to-end run has ever happened, on any hardware.** Not a slow one, not a partial one. Zero
   generations have travelled device to backend to service and back. Every latency number in this
   PR's copy and budget comes from PR-5's measurements of the model directly, not of this path.
4. **Control-token wrapping is unverifiable from here.** Contract §2.7 requires the service to wrap
   the prompt in the artifact's chat control tokens, and PR-5 MEASURED that an unwrapped prompt
   generates `Patient.` to the token ceiling, reports `finish_reason: stop` honestly, and passes the
   sanitizer, the grounding gate and the identity gate. Nothing on the envelope can distinguish it.
   The first integration run needs a record whose expected read-back is known and a human reading
   the text.
5. **Six hazard items are pending operator sign-off**, and `docs/quality/risk-management-file.md`
   was not touched by this PR.
6. **The backend has no `401` branch** (contract §4.2.2): one branch covers all `4xx`, so a wrong
   `SLM_SERVICE_TOKEN` logs the device's request as bad. That would be the first thing an operator
   saw on day one of a real deployment, pointing at the wrong machine.
7. **No audit rows exist on the device side.** Step 9 of the seam is a named seam with nothing
   behind it; it needs new `AuditAction` values and the backend enum mirror in one commit.
8. **The two "do not try again" refusals have never been seen by a person.** They are the two that
   most need a worker to read and act on them, and no usability check of any kind has been run on
   this copy.
9. **No Indian-accented or low-literacy review of the copy.** Eighteen strings written by one author
   in one sitting, held only to a written rule set.

Turning the flag on before those is turning on a clinical feature whose answer path nobody has
watched work.

---

## 9. Carry forward, not acted on

New from this PR:

- **The `strings.xml` convention fork is three features to the rest and three authors have now
  declined to settle it.** It needs an owner, not a fourth recording.
- **The read-back entry button's render guard is not pinned by a test.** `canOpenReadback` gating the
  composable is a Compose-level claim and there is no Compose test for this screen; the ViewModel
  guards are what the mutation checks cover. A rendered-but-dead button would be cosmetic rather
  than a safety failure, which is why it was not chased here.
- **`SlmRefusal.ENGINE_UNAVAILABLE` now covers four states behind one string**, including the
  device's own mutex refusal. If the read-back is ever used heavily enough that double-taps are
  common, that string will be read by workers who are not waiting on anything remote.

Carried from before, unchanged:

- Two Room migrations unrun for want of a device, and they are a merge gate.
- No corrective edit path for a rejected record.
- Six hazard items pending sign-off.
- The kernel proxy's `404` audit-row gap, standalone.
- The middleware fallback audit row that can never fire on ANY route, found in PR-4 and not swept.
- Contract §4.2.2: one branch for all `4xx`, so a wrong service token logs the device's request as
  bad; needs an Alembic migration, a device-facing code and a `401` branch.
- The `strings.xml` fork.
- The one-time re-push of pre-existing FAILED rows, owner-gated and unblocked.
- The timeout nesting residual of 5 s, pinned by an assertion.
- The GPU box's Docker filesystem at 100 percent.
- Gemma 4 E2B measured at 9.551 GiB resident, which makes the on-device endgame conditional on
  quantisation and on fixing ASR residency first.
