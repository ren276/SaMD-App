# Phase 4 scope, read-only pass (2026-09-18)

Branch `fix/navstack-process-death`, HEAD `60e1ed3`. No code or tests written for this document.
Companion to `scratchpad/navstack-phase3-scope.md`, which scoped phase 3 and folded two test items
into this phase.

Evidence marked **VERIFIED** (read the file or ran the command in this pass) or **INFERRED**.

---

## Two corrections to phase 3's scoping, made here

**C1. `scripts/process_death_check.sh` ALREADY EXISTS.** Phase 3's memo said it was "absent from
the tree" (VERIFIED wrong). It is 107 lines, committed in `b88bd88` with the capture fix.
I had searched the repository root rather than `scripts/`. That changes the item from "write a
script" to "add a mode to an existing one", and it surfaces a precondition conflict, see item 3.

**C2. The emergency-reason test does not need Robolectric.** Phase 3 carried it forward as a
"Robolectric test", from the memo's wording. Robolectric is not a dependency of this project at all
(VERIFIED: no match in `app/build.gradle.kts` or `gradle/libs.versions.toml`), and it is not
needed: `EmergencyOverrideViewModel` has exactly one `android.` reference, the
`dagger.hilt.android.lifecycle.HiltViewModel` annotation (VERIFIED, line 10). Everything else is
`kotlinx.coroutines` and the repository interface. It is a plain JVM test, the same shape as
`NavBackStackPolicyTest`. Adding a test framework for it would have been unnecessary.

**Also settled here, so it stops being INFERRED anywhere:** back DOES pop a single entry.
navigation3-ui 1.1.4's `NavDisplay` default is

```kotlin
onBack: () -> Unit = {
    if (backStack is MutableList<T>) {
        backStack.removeLastOrNull()
    }
}
```

(VERIFIED `NavDisplay.kt:255-259` in the sources jar), and this app passes its own equivalent
explicitly, `onBack = { backStack.removeLastOrNull() }` (VERIFIED `AppNavHost.kt:166`). The
one-live-`Compounder` invariant's last leg is now read, not reasoned. The caveat recorded in the
restore memo's section 8.2.1 is struck in the same commit that carries this document.

---

## Item table

| # | Item | Clinical state? | Size | Model |
|---|---|---|---|---|
| 1 | `DoctorAssignmentConfirmRoute` status replace | **reads**, rewrites stack, writes nothing | small | Opus |
| 2 | Restore-time `StopDeviceAcquisitionUseCase`, dev only | no row written, external side effect | **HELD**, see below | Opus |
| 3 | `process_death_check.sh` deep-stack mode | none, script only | medium | Sonnet |
| 4 | Emergency-reason re-derivation test | none, test only | small | Sonnet |
| 5 | `SendingRoute` idempotency pin + byte-budget test | none, tests only | small | Sonnet |
| 6 | `AsrEgressTest` `GrantPermissionRule` | none, test setup only | 2 lines | Sonnet, **separate commit** |

---

## 1. DoctorAssignmentConfirmRoute status replace. Opus

Much cheaper than the phase 2 deferral implied, because the pieces already exist.

**Where the status read lives: the ViewModel, and it is already reading the row.**
`DoctorAssignmentConfirmViewModel.init` already does
`caseRecordRepository.observeCaseRecord(caseRecordId).first()?.encounterId` (VERIFIED line 78).
The status is another field on the row it already fetches (VERIFIED `CaseRecord.status`,
`CaseRecord.kt`). So this is not a new query, a new repository method or a new DAO. It is a second
field read off an existing fetch.

**How the replace happens, and why the Compounder content-key hazard does not apply here.** The
`Compounder` rewrite was delicate because it mutated a live entry's `NavKey` in place, changing
`contentKey` and tearing down a ViewModel mid-screen. Nothing like that is needed: the existing
`onDone` for this route is already `{ backStack.clear(); backStack.add(Home) }` (VERIFIED
`AppNavHost.kt:421`). "Replace the restored entry with Home" IS that behaviour. The entry is
destroyed rather than re-keyed, so its ViewModel is torn down, which is correct and not a
fabricated-audit-row risk the way the Compounder recreation was.

The route has a ViewModel whose identity does NOT matter for this, for the same reason: it is being
navigated away from, not re-keyed.

**Shape:** in `init`, after the row is read, if `status` is `SENT_TO_DOCTOR` or later, emit an
effect instead of populating the screen. `DoctorAssignmentConfirmEffect` currently has a single
`Done` (VERIFIED lines 44-46). Either reuse `Done`, or add a distinct effect so the screen can tell
"you finished this" from "this was already assigned". I would add the distinct one: reusing `Done`
makes an already-assigned case indistinguishable from a just-confirmed one in any future logging.

"`SENT_TO_DOCTOR` or later" needs stating explicitly, because `CaseStatus` is a plain enum whose
declaration order is `DRAFT, SAVED_LOCALLY, PENDING_SYNC, SENT_TO_DOCTOR, PRESCRIPTION_RECEIVED,
ABANDONED` (VERIFIED). `ordinal` comparison would sweep `ABANDONED` in as "later", which is wrong:
an abandoned case is not an assigned one. Use an explicit set, `SENT_TO_DOCTOR` and
`PRESCRIPTION_RECEIVED`, not `>=`.

**Tests:** a `SENT_TO_DOCTOR` case emits the replace effect and never populates a doctor; a
`DRAFT`/`SAVED_LOCALLY`/`PENDING_SYNC` case restores normally and proposes a doctor; an `ABANDONED`
case is asserted explicitly so the ordinal trap is pinned rather than commented.

**Why Opus:** it reads clinical state and conditionally changes where the worker lands. The enum
trap above is exactly the kind of thing that passes review and is wrong.

## 2. Restore-time StopDeviceAcquisitionUseCase, dev flavor only. Opus

**Harm model, as far as this repo can establish it.**

- VERIFIED: `stopDeviceAcquisitionUseCase()` is called only from `onStopAcquisition`
  (`CompounderViewModel.kt:603`) and `onCleared`, guarded by `hadSession` (`:609-621`). Process
  death runs neither, so a session open at death is never closed by the device.
- VERIFIED: staging and prod bind `UnavailableVitalsSource`, which overrides only `readVitals` and
  inherits the interface defaults `Rejected(NOT_SUPPORTED)` / no-op `stopAcquisition`. No session
  can exist there. This is a dev-flavor concern only, `PiGatewayVitalsSource` living in
  `app/src/dev/`.
- VERIFIED: the leak window is narrow. `reading_count: 1` "is what makes the gateway emit one
  reading and complete the session, so no timer is left running on the far side"
  (`PiGatewayApi.kt:20-21`). The session self-completes once a reading is emitted, so only death
  DURING an acquisition, before the reading arrives, can leave one open.
- **INFERRED, and NOT verifiable here:** that the gateway rejects a second `startSession` while one
  is open. The gateway server is not in this repository. What can be said from this side is only
  that such a rejection would arrive as an `HttpException` and map to `RejectReason.MALFORMED`
  (VERIFIED `PiGatewayVitalsSource.kt:95-101`), which would tell the worker "malformed" for what is
  really "busy". Whether that path is ever taken is a question for the gateway, not for this repo.
- VERIFIED, and this is the finding that decides the item: `stopSession()` takes no session id
  (`PiGatewayApi.kt:30-31`). See the blast radius below.

**THE BLAST RADIUS, which rules out the unconditional version.** `stopSession()` takes no session
id, so it closes whatever session the gateway currently holds, not specifically the one this device
leaked. Firing it unconditionally on every restore therefore does not mean "release my leftover
session"; it means "close whatever is open on that gateway right now". After a death and relaunch,
that could be a session another acquisition legitimately started. On a shared PHC gateway, a blind
restore-time stop can terminate another worker's live reading, which is a materially worse outcome
than the leak it cleans up (one misleading `MALFORMED` on the next start, dev flavor only).

So the fix is only safe if it is gated on this app believing it left a session open.

**PRECONDITION CHECK: is there any locally-persisted in-flight-acquisition signal? NO.** VERIFIED:

- `activeSessionId` exists only as a `@Volatile` in-memory field on `PiGatewayVitalsSource`
  (`:36`, set `:59`, cleared `:87`). It dies with the process.
- `CompounderViewModel.onStartAcquisition` (`:543-590`) writes nothing durable before the call. It
  updates `_uiState` and launches the job. The only durable traces are the audit rows written
  AFTER a result arrives, `VITALS_DEVICE_READING_RECEIVED` or `VITALS_DEVICE_READING_FAILED`, so a
  death mid-acquisition writes neither.
- `VitalsSnapshot` carries `deviceId`, `source` and `captureMethod`, but a snapshot is written only
  when a reading is recorded. An acquisition that never produced one leaves no row.
- Absence of a completion audit row is not evidence of a leaked session either: it is also exactly
  what a worker who never started an acquisition looks like.

**Therefore item 2 cannot be built safely as specified, and is HELD.**

**The safe version exists, and is deliberately not recommended.** The repo already has the right
pattern for "what was in flight when the process died": `InFlightBatchStore`
(`data/sync/InFlightBatchStore.kt`) persists a sync batch to DataStore BEFORE the network call and
clears it only after the ack. An `InFlightAcquisitionStore` of the same shape, written before
`startAcquisition` and cleared on stop or completion, would gate the restore-time stop correctly.

Note that this would NOT violate D5. D5 forbids persisting device-sourced field VALUES and
`fieldProvenance`, because a restored `DEVICE` mark that no acquisition in this process produced is
a fabricated-provenance claim. A marker recording only that a session was opened carries no reading
and asserts no provenance, so the letter and the purpose of D5 both survive it.

It is still not worth building: a DataStore file, a store class, DI wiring and its own crash-safety
tests, to prevent one misleading rejection reason, in the dev flavor only, in the window between
starting an acquisition and the first reading arriving. Recorded as owed-pending-a-safe-signal so
the reasoning is not redone from scratch if the gateway contract ever changes (for example if
`stopSession` gains a session id, which would remove the blast radius and make the unconditional
version safe on its own).

## 3. process_death_check.sh deep-stack mode. Sonnet

**The precondition conflict, which is the interesting part.** The existing script asserts the
OPPOSITE of what a nav-restore check needs. Its stated purpose is that with the CameraX viewfinder
open "this process is never a low-memory-killer candidate", and it asserts that `oom_score_adj`
stays 0 and that "an `am kill` attempt mid-window is refused rather than tearing the process down"
(VERIFIED, header lines 1-11). A nav restore check needs the app **backgrounded** so that
`onSaveInstanceState` has run, and then needs the kill to **succeed** so the saver's restore path
executes on relaunch.

So this is not a flag on the existing flow. It is a second mode with inverted preconditions
(background first, expect the kill to work), sharing the script's device plumbing and its
`cleanup`/`trap EXIT` discipline, which is worth reusing: it resets `always_finish_activities`
on every exit path including Ctrl-C (VERIFIED lines 38-45).

Also inherited: the script explicitly does not drive the app to the screen under test, because
reaching it needs a signed-in session and the backend (VERIFIED lines 13-16). The deep-stack mode
has the same limitation and should state it rather than pretend otherwise.

**What it proves that no test here can:** that `onSaveInstanceState` actually routes through
`rememberSaveable`'s saver under a real `am kill`. Every existing test drives the `Saver` directly.

## 4. Emergency-reason re-derivation test. Sonnet

Plain JVM test, no Robolectric (see C2). `app/src/test/.../emergency/` does not exist yet
(VERIFIED). Asserts that `EmergencyOverrideViewModel`, given a persisted `VitalsSnapshot`, produces
reason text identical to what the forward path showed, which is the claim phase 1 made by argument
from `CheckEmergencyThresholdsUseCase` being pure over three fields and `CompounderViewModel`
recording before checking. Same standard as `CompounderContentKeyTest`: turn the argument into an
observation.

Include the empty case: no vitals row means an empty reason list, and the screen is still a correct
emergency screen, which is the behaviour the ViewModel's KDoc already claims.

## 5. Test hardening folded from phase 3. Sonnet

- `SendingRoute` idempotency: pin `ExistingWorkPolicy.KEEP` on
  `enqueueUniqueWork(uniqueWorkName(caseRecordId), ...)` (VERIFIED
  `WorkManagerAssessmentScheduler.kt:44`). The memo asked for this explicitly and nothing references
  it (VERIFIED, no test mentions `SendingViewModel` or the scheduler policy).
- `MAX_SAVED_STACK_BYTES`: the branch has never executed, because the only over-budget test trips
  the 50-entry cap first (VERIFIED `NavBackStackSaverTest.anOverlongStackIsNotPersisted`).

## 6. AsrEgressTest RECORD_AUDIO. Sonnet, separate commit

`AsrEgressTest` has no `GrantPermissionRule` (VERIFIED), while its own KDoc assumes the emulator mic
yields silence and asserts capture returns success-carrying-nothing. Without the grant it gets
`Microphone unavailable`, which is the failure seen in the full instrumented run. The fix is the
rule. **Not** a relaxed assertion: relaxing it would blind the test to a real egress regression,
which is the one thing it exists to catch.

Unrelated to navigation. It should ship as its own commit so it can be reviewed, reverted or
cherry-picked without touching the nav track.

---

## Proposed build order and commit split

Order is driven by one dependency: item 2 must be verified by item 3, so the script comes first.

1. **Commit A, Sonnet: test hardening.** Items 4, 5. Pure additions, no production code, nothing
   depends on them. Doing them first means the suite that guards everything else is stronger before
   any behaviour changes.
2. **Commit B, Sonnet: the script.** Item 3, deep-stack mode. No app code. Lands before item 2 so
   item 2 has something real to be verified against.
3. **Commit C, Opus: the status replace.** Item 1, with its tests including the `ABANDONED` case.
   Self-contained, touches only the doctor-assignment screen and its ViewModel.
4. **Commit D, Opus: the acquisition stop. HELD, likely does not exist.** The precondition check
   in item 2 failed: there is no locally-persisted in-flight-acquisition signal to gate on, and the
   ungated version can close another worker's live session. Unless you elect to build the
   `InFlightAcquisitionStore`, this commit does not happen and item 2 is recorded as owed.
5. **Commit E, Sonnet, independent of all the above:** item 6, `AsrEgressTest`. Can land at any
   time, including before commit A.

Five commits, not one. Items 1 and 2 are unrelated to each other and to the tests; folding them
together would produce a commit whose message has to explain four unrelated things, and would make
the acquisition change, the one resting on an unconfirmed failure mode, hard to revert alone.

**Model split:** commits A, B and E at Sonnet high. Commits C and D at Opus high. The split is
clean because the Sonnet items touch no production code at all except the script, and the Opus items
are the two that read clinical state or act on a clinical-capture path.

**The open question, restated after the precondition check.** My earlier lean toward shipping item
2 was formed before the `stopSession()`-takes-no-session-id finding. That finding moves it to
hold-unless-gated: the cleanup is not harmless if the failure mode is imaginary, because the blind
version has its own failure mode that is worse than the leak. Three options, in the order I would
take them:

1. **Hold** (recommended). Item 2 is owed-pending-a-safe-signal. Commit B still stands on its own
   as the phase's process-death proof.
2. **Build the gated version** with an `InFlightAcquisitionStore` modelled on `InFlightBatchStore`.
   D5-compatible, correct, and out of proportion to the harm.
3. **Ship the blind stop.** Not recommended and I would want that instruction in writing: it can
   terminate an unrelated in-flight reading on a shared gateway.
