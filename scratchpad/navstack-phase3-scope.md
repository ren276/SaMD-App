# Phase 3 scope, read-only pass (2026-09-17)

> **SUPERSEDED IN PART by the phase 4 scoping pass (`navstack-phase4-scope.md`, 2026-09-18).**
> Two statements below were wrong and are corrected there:
>
> 1. **"no `process_death_check.sh` (VERIFIED, absent from the tree)" is wrong.**
>    `scripts/process_death_check.sh` exists and has since `b88bd88`. This pass searched the
>    repository root rather than `scripts/`. The phase 4 item is "add a mode to an existing script",
>    and the existing script asserts the opposite precondition (that `am kill` is refused), so the
>    new mode is not a flag on the current flow.
> 2. **The emergency-reason test does not need Robolectric.** This pass carried the restore memo's
>    "Robolectric" wording forward without checking. Robolectric is not a dependency of this project,
>    and `EmergencyOverrideViewModel`'s only `android.` reference is the `@HiltViewModel` annotation,
>    so it is a plain JVM test.
>
> Everything else in this document still holds, including the inert-`resumeSuppressedFor` finding,
> which was acted on in `60e1ed3`.

Branch `fix/navstack-process-death`, HEAD `e3e2dfb`. No code or tests written for this document.

`git status --short` at the start of this pass:

```
 M .idea/deploymentTargetSelector.xml
 M backend/README.md
 M backend/docker-compose.yml
?? tools/dev-connect.sh
```

Four pre-existing working-tree items, none of them mine, none staged, nothing committed during
this pass. `backend/docker-compose.yml` is still the `ABDM_MODE: stub -> live` flip.

Evidence is marked **VERIFIED** (read the file or ran the command in this pass) or **INFERRED**
(reasoned from what was read, not directly observed).

---

## 1. Per-route status against the memo's section 2 table

| Route | Memo verdict | Status now | Evidence |
|---|---|---|---|
| `Home`, `Patients`, `Referrals`, `Profile`, `OpenSourceLicensesRoute`, `AbhaEntry` | SAFE | **done**, nothing to do | no args, VERIFIED `Routes.kt:22-46` |
| `AbhaSignUp`, `AbhaLogin` | SAFE-IF form loss acceptable | **phase 5** (D4 notice) | VERIFIED argless, `Routes.kt:44-49` |
| `AbhaOtpRoute` | SAFE-IF no auto-resend | **done**, dropped at save | VERIFIED `NavBackStackSaver.kt` drop branch |
| `AbhaAadhaarEntry` | SAFE | **done** | VERIFIED `Routes.kt:52-53` |
| `AbhaCreateOtpRoute` | UNSAFE always | **done**, drops to `AbhaAadhaarEntry` | VERIFIED transform + `NavBackStackPhiFreeTest` green |
| `Register` | SAFE-IF worker told form cleared | **split**: identifier handled (replaced with `AbhaEntry`), notice is **phase 5** | VERIFIED transform; D4 deferred |
| `MedicalBackground`, `PatientSummary` | SAFE-IF patient row exists | **satisfied by construction**, see section 3 | VERIFIED no delete path |
| `AbhaProfileRoute` | SAFE | **done**, dropped at save | VERIFIED transform |
| `ConsentRoute` | SAFE | **done** | VERIFIED, records nothing on entry |
| `Compounder` | SAFE-IF resume args present | **done**, in-place rewrite + pinned content key | VERIFIED `AppNavHost.kt:316-360`, `CompounderContentKeyTest` both arms green |
| `EmergencyOverrideRoute` | UNSAFE as declared | **done** phase 1, ids only, deliberately not transformed | VERIFIED `Routes.kt:128` |
| `ConsultationRoute` | UNSAFE as declared | **done**, replaced by `Compounder` resume shape synthesized from its own args | VERIFIED transform |
| `SendingRoute` | SAFE, "**a test must pin it**" | **OPEN, phase 3.** Idempotent by `ExistingWorkPolicy.KEEP`, and nothing pins that | VERIFIED `WorkManagerAssessmentScheduler.kt:44`; VERIFIED no test references it |
| `KernelAssessmentRoute` | SAFE | **done** | VERIFIED observes only |
| `TranscriptionRoute` | UNSAFE always | **done**, replaced by `AcknowledgementRoute` | VERIFIED transform |
| `AcknowledgementRoute`, `ReportRoute`, `ConsultationChainRoute` | SAFE / SAFE-IF rows exist | **satisfied by construction**, see section 3 | VERIFIED no delete path |
| `DoctorListRoute`, `PatientAuditRoute`, `DocumentViewerRoute` | SAFE | **done** | VERIFIED |
| `DoctorAssignmentConfirmRoute` | conditionally UNSAFE | **phase 4**, still only owed | VERIFIED: no status check anywhere in `AppNavHost` or the screen |
| Pi acquisition sub-state (memo 2.0) | not restorable | **PARTIALLY OPEN**, see section 4 item B | VERIFIED |

The four phase-4 deferrals were each checked and are **still only owed, not half-built**:
no `CaseRecord.status` read exists at the `DoctorAssignmentConfirmRoute` entry (VERIFIED), there is
no Robolectric emergency test (VERIFIED, `app/src/test/.../emergency/` does not exist), there is no
`process_death_check.sh` (VERIFIED, absent from the tree), and `AsrEgressTest` still has no
`GrantPermissionRule` (VERIFIED).

**Phase 3 is not empty, but it is small**, and nothing in it is a route policy. Every remaining
route-level item is done or belongs to phase 4/5. What is left is one inert seam, one unbuilt
restore-time side effect, and two missing tests.

---

## 2. Home resume gating: wired correctly, and structurally inert

Both halves are present exactly as specified:

- `dismissedResumeId` is `rememberSaveable` (VERIFIED `HomeScreen.kt:109`).
- `resumeSuppressedFor` is wired to `backStackContainsCase(backStack, caseRecordId)` at the call
  site (VERIFIED `AppNavHost.kt:186-190`), and gates the dialog alongside the dismissal
  (VERIFIED `HomeScreen.kt:116`).

**But the suppression can never fire.** `Home` only ever exists at index 0:

- the stack is seeded `NavBackStack<NavKey>(Home)` (VERIFIED `AppNavHost.kt:103`);
- `switchTab` does `backStack.clear()` then adds the tab root (VERIFIED `AppNavHost.kt:126-136`);
- both `backStack.add(Home)` sites are `backStack.clear(); backStack.add(Home)` on one line
  (VERIFIED `AppNavHost.kt:360`, `:421`);
- there is no other insertion of `Home` (VERIFIED, grep over `AppNavHost.kt`).

`NavDisplay` draws the last entry, so `HomeScreen` composes only when `Home` IS the last entry,
which given index 0 means the stack is exactly `[Home]`. `backStackContainsCase([Home], anything)`
is false by inspection. So `resumeSuppressedFor` returns false on every call it will ever receive.

**Verdict: the gating is complete as an implementation and inert as a control.** It is not wrong
and it is not harmful; it is a guard against a stack shape this navigation model cannot produce.

**Does the invariant still hold?** Yes, but for a different reason than phase 2 recorded. At most
one live path to a `caseRecordId` holds because only one `Compounder` can be in the stack at all:
the two push sites are Home's resume prompt (reachable only at `[Home]`) and `ConsentRoute`
(VERIFIED `AppNavHost.kt:184`, `:299`), and reaching either again requires a tab switch (clears) or
popping back past the existing `Compounder`. **INFERRED** for the pop half: I did not read
`NavDisplay`'s back handler, so "back pops entries" is standard behaviour I am assuming, not
something I observed in this pass. Worth confirming before anyone relies on it.

**Memo-versus-code discrepancy, flagged per the hard rules.** The coupling note I wrote into
`scratchpad/navstack-restore-memo.md` section 8.2.1 (committed in `e3e2dfb`) says the pinned
content key "is unique only while ... which is exactly what Home's resume gating prevents". That
overstates the gate. The real guarantee is Home-at-index-0 plus clear-on-tab-switch; the resume
gate contributes nothing today because it cannot fire. The note is not false about the
consequences of a collision, but it names the wrong mechanism as the thing holding it off. Phase 3
should correct that note, and it is the same defect class as a comment describing a mechanism the
code does not use.

**No path found that could create a second live path to one `caseRecordId`.** Restore cannot: the
transform emits at most one `Compounder` per case (VERIFIED, `NavBackStackPolicyTest` and the
`eachTransformedEntryLandsOnItsSafeAncestor` assertion). The prompt cannot: it draws only at
`[Home]`. Forward navigation cannot: it requires clearing or popping first.

---

## 3. SAFE-IF conditions with no enforcement

| SAFE-IF | Enforced? | Owner |
|---|---|---|
| `MedicalBackground` / `PatientSummary`: patient row exists, else pop to `Patients` | **Not enforced, and cannot currently be violated.** No patient delete path exists: no `@Delete`, no `deletePatient`, no `DELETE FROM patients` anywhere in `data/local` (VERIFIED, grep). | none today; becomes phase 4+ the day a delete or a sync-driven purge lands. Name it as a standing dependency. |
| `ReportRoute` / `ConsultationChainRoute`: case, report and encounter rows exist | Same. Same absence of a delete path (VERIFIED). | same |
| `AbhaSignUp` / `AbhaLogin` / `Register` / `MedicalBackground`: worker is told the form was cleared | **Not enforced.** No restored-after-restart notice anywhere (VERIFIED, grep for a restore notice returns nothing). | **phase 5**, D4 with RR-03, as already decided |
| `SendingRoute`: enqueue re-entry is idempotent | **True but unpinned.** `ExistingWorkPolicy.KEEP` on `enqueueUniqueWork(uniqueWorkName(caseRecordId), ...)` (VERIFIED `WorkManagerAssessmentScheduler.kt:44`) and the memo says outright "this one is safe because of an existing deliberate design choice, so a test must pin it". No test references `SendingViewModel` or the scheduler's policy (VERIFIED, grep across both test source sets). | **phase 3** |
| `AbhaOtpRoute`: entering does not auto-resend an OTP | Not re-checked in this pass. **INFERRED** safe because the entry is dropped at save time, so it is never restored into. | none |

---

## 4. Written-but-not-wired, or wired-but-not-tested

**A. `resumeSuppressedFor` is wired but inert.** Section 2. Phase 3 decides: keep it as
defence-in-depth with a comment saying it is currently unreachable and why, or remove it and rely
on the structural guarantee. I lean keep-and-document, because the structural guarantee is exactly
the kind of thing a future two-pane or list-detail scene would break silently, but that is a call
for you, not for me.

**B. The restore-time gateway stop was never built.** Memo section 2.0 requirement 1 says: on
restore, call `StopDeviceAcquisitionUseCase` once, unconditionally, so a gateway session the dead
process left open is released. That does not exist. `stopDeviceAcquisitionUseCase()` is called only
from `onStopAcquisition` (VERIFIED `CompounderViewModel.kt:603`) and from `onCleared`, guarded by
`hadSession` (VERIFIED `:609-621`). Process death runs neither.

Scope of the actual harm, which is narrower than the memo implies:

- Staging and prod bind `UnavailableVitalsSource`, which does not override `startAcquisition` or
  `stopAcquisition` and so inherits the interface defaults, `Rejected(NOT_SUPPORTED)` and a no-op
  (VERIFIED `UnavailableVitalsSource.kt`, `VitalsSource.kt`). No session can exist to leak.
- Only the **dev flavor** binds `PiGatewayVitalsSource` (VERIFIED, it lives in `app/src/dev/`).
- Even there, the gateway session self-finishes after one reading: "with `reading_count: 1` the
  session is already finished on the far side once a reading has been emitted" (VERIFIED
  `PiGatewayVitalsSource.kt:78-80`). The leak window is process death **during** an acquisition,
  before a reading arrives.
- A stale far-side session would surface on the next `startAcquisition` as an `HttpException`,
  which maps to `RejectReason.MALFORMED` (VERIFIED `PiGatewayVitalsSource.kt:95-101`). So the
  worker's next acquisition fails with a misleading reason rather than being told the gateway is
  busy. **INFERRED**: that the Pi rejects a second start while one is open. I did not read the
  gateway's own server code and it is not in this repo.

So this is real but dev-only and bounded. Phase 3 or phase 4 both defensible; I would put it in
phase 3 because it is three lines in `CompounderViewModel.init` and the memo already specified it.

**C. `MAX_SAVED_STACK_BYTES` is untested.** The only over-budget test drives 60 entries past the
50-entry cap (VERIFIED `NavBackStackSaverTest.anOverlongStackIsNotPersisted`), so the
`encoded.byteSize() > MAX_SAVED_STACK_BYTES` branch has never executed in a test. Cheap to pin.

**D. `NavStackRestoreReporter` drain is tested at the ViewModel but not end to end.**
`NavRestoreViewModelTest` covers the row; nothing exercises the `LaunchedEffect(Unit)` in
`MainNavHost` that actually calls it (VERIFIED `AppNavHost.kt:108-109`). Low value to close; a real
discard is phase 4's `process_death_check.sh` territory. Listing it so it is not mistaken for
covered.

**E. ProGuard keep rules remain inert** because the optimizer is off. Already documented in
`proguard-rules.pro` itself; not new, not phase 3.

---

## 5. Model call

**Verdict: (a), a pop-free remainder. Sonnet high is sufficient, with one caveat below.**

Phase 3 is not empty. Its contents are:

1. Decide and document (or remove) the inert `resumeSuppressedFor` (item A).
2. Correct the section 8.2.1 coupling note, which names the wrong mechanism (section 2).
3. The restore-time `StopDeviceAcquisitionUseCase` call, dev flavor only (item B).
4. The `SendingRoute` idempotency test the memo explicitly asked for (section 3).
5. The byte-budget test (item C).

What decides it: **nothing here mints, deletes or rewrites clinical state.** No route policy
changes. No encounter or case record is created or resumed by any of it. The one behavioral change,
item 3, is a best-effort network call that closes an instrument session; it writes no row, and it
is already `best effort by design, never throws` at the seam it calls (VERIFIED
`PiGatewayVitalsSource.kt:78-89`). The mint-a-second-encounter hazard, the content-key binding and
the PHI transform are all committed and pinned by tests that are green.

**The caveat.** Item 3 touches the vitals acquisition path, which is a clinical-data-capture
surface even though this particular call writes nothing. If you would rather it not be done at
Sonnet, the clean split is: items 1, 2, 4 and 5 at Sonnet high (documentation, a note correction
and two tests, zero production behaviour outside a comment), and item 3 folded into phase 4 with
the other acquisition-adjacent work, where the emergency Robolectric test and the process-death
script already live. That split is what I would pick: it makes phase 3 genuinely inert, and item 3
is better verified next to a real process-death harness than without one.

If you take that split, phase 3 is **documentation plus two tests**, and the more useful question
becomes whether it is worth a phase at all or should be folded into phase 4's scoping pass.
