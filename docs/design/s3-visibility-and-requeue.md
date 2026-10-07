# S-3: visibility and the human requeue

Tree at a219da9 plus the four pre-existing uncommitted changes, PR-0 through PR-3, the F6C fixes,
S-5, S-5b, S-4, S-1 and S-2. All left as they were. Nothing committed. No credential file opened,
`docs/quality/risk-management-file.md` untouched.

**No backend test was run in this session.** S-3 is entirely Android-side and changes no Python.
`ABDM_MODE=stub` was therefore never needed and is not claimed. The one backend file this change
set touches at all is `backend/core/app/services/sync.py`, and only as a file READ AS TEXT by a
new mirror test; it was temporarily edited during mutation M13 and restored byte-identical.

MEASURED = observed from a run in this session. INFERRED = read from source, not executed.

---

## STEP 1: what the worker sees

### The RETRYABLE-in-the-count decision: no, and not separately either

**FAILED only. A `RETRYABLE` row appears in neither the count nor the list.** Three reasons, in
order of weight:

1. **A worker has no action for one.** The card's entire contract is "these need you". A row the
   device is still working on needs nobody. Putting it in the same number as a record needing a
   person trains a worker to read the number as noise, and then the number is worth nothing on
   the day it matters. That is the brief's own lean and it holds.
2. **S-2 made the wait bounded, which removes the only argument for early surfacing.** Every
   RETRYABLE row ends within [MAX_SYNC_ATTEMPTS] acked attempts as SYNCED or as FAILED carrying
   `SAMD-SYNC-RETRY-EXHAUSTED`. Against a fifteen-minute periodic drain that is the same working
   day. So every RETRYABLE row that genuinely needs a human arrives in this count on its own,
   soon, already labelled with the right cause. Surfacing it earlier buys a worker nothing they
   could act on, because there is nothing to act on until the budget is gone.
3. **"Separately and quietly" was considered and rejected.** A second number a worker must learn
   to tell apart from the first is worse than no second number. If it is ever wanted, the place
   for it is the existing sync card's caption ("3 pending"), not a second card. MEASURED: a
   RETRYABLE row is today in no count at all, exactly like a PENDING one, and that is the honest
   statement, because both are in flight.

Pinned by `FailedSyncReviewQueryTest.patientsDao_returnsOnlyFailedRows_...` (a RETRYABLE row is
inserted and must not come back) and by `SyncDaoSqlContractTest.no review query widens beyond
FAILED` (mutation M5).

### The copy table

Five causes, derived from `(syncErrorCode, syncErrorMessage)`, in
`domain/model/SyncFailureReason.kt`. Strings in `strings.xml` beside S-4's `kernel_failure_*`.

| Cause | Detected from | Title | Action |
|---|---|---|---|
| `DUPLICATE_RECORD` | `SAMD-SYNC-6003` + "this record already exists with different data." | Another record already uses these details | none |
| `RECORD_REJECTED` | any other `6003`, or `SAMD-SYNC-6006` | The server would not accept this record | none |
| `RETRIES_EXHAUSTED` | `SAMD-SYNC-RETRY-EXHAUSTED` | This record is waiting on another record | **Send again** |
| `RECORD_TOO_LARGE` | `SAMD-SYNC-RECORD-TOO-LARGE` | This record is too big to send | none |
| `UNRECOGNISED` | anything else, including a null code | This record did not reach the server | **Send again** |

Bodies are in `strings.xml`; each names what to do, and the three with no button say "write down
the patient's name and tell your supervisor today" rather than leaving a worker with a screen and
no next step.

**The one place the code alone is not enough.** The backend rejects every constraint violation as
`SAMD-SYNC-6003`, so a duplicate ABHA and a missing required field arrive under one code and
differ only in the message. The duplicate is the terminus this whole sequence started from, so it
has to be separable, which means matching the message text. Four backend strings are therefore
mirrored in `BackendConstraintMessages` and guarded by `SyncFailureMessageMirrorTest`, which reads
`backend/core/app/services/sync.py` as text. Same crude-guard idiom as `SyncDaoSqlContractTest`;
same stated limit (it cannot prove the backend still SENDS them).

**The backend message is never rendered.** It is PHI-safe by contract but written for an operator
reading a log ("a referenced record does not exist yet"). It is a discriminator and nothing else.

**Duplicate copy says "such as the same ABHA number", not "the ABHA number is duplicated".**
sqlstate 23505 proves a unique constraint was hit; the ack does not name which. MEASURED from
`_SQLSTATE_RETRY_CLASSES`' own comment: exactly two unique constraints are client-reachable and
one of them is `ix_patients_abha_number`. Asserting a cause we have not established is how a
worker stops believing the screen, which is S-4's `case_not_on_server` rule applied again.

### Row label: seven nouns for twenty tables

`presentation/sync/SyncFailureCopy.kt`. A worker does not distinguish an allergy row from a
family-history row; both are "patient history". Twenty labels would be twenty strings saying five
things. Same collapse S-4 made for the five `SAMD-KERN-*` codes with one identical action, and
design for the same reason: the distinction is kept where it is useful (the table name is still
what `requeueFailed` dispatches on) and dropped where it is not.

Row header is `"%1$s — %2$s"` (a verbatim quote of `failed_sync_row_header`, the one em
dash in this record), rendering as patient name then noun, falling back to the noun alone when there is no patient
(`abha_profiles`, or a parent row this device no longer holds). Second line is the record's
`localModifiedAt`, not `lastSyncAttemptAt`: a worker recognises the day the visit happened, not
the day the phone last tried.

---

## STEP 2: the surfaces

### The finding that reshaped the PR: there is no edit path

The brief asked whether the worker edits the record and then sends, or the action navigates them
to the record, and expected the second. **Neither is available.** MEASURED:
`PatientRepository` exposes `register`, `observePatient`, `observeTodaysPatients` and
`observeRegisteredOrSeenRecently` and no update of any kind, and no other repository exposes a
corrective edit of a synced record either. `PatientSummary` is the only patient destination and
it is a read surface.

S-2's state table is still correct that any local edit resets a row to PENDING with a fresh
budget. What does not exist is a flow in which a worker deliberately corrects a record the server
rejected. Edits happen as a side effect of normal clinical flows re-saving a row, not as a repair.

So a third action ("open and fix this") would have been a button with nowhere to go, which is the
exact defect S-4 exists to stop. The action set is two:

- **`SEND_AGAIN`** calls `SyncOutboxRepository.requeueFailed`, S-2's FAILED to PENDING
  transition. No edit. Offered only where the record is not believed to be wrong:
  `RETRIES_EXHAUSTED` (the parent may have landed since) and `UNRECOGNISED`.
- **`TELL_SUPERVISOR`** is no button at all, and copy that names the escalation.

Operationally, "edited" for the requeue path means nothing: the row goes back to PENDING unchanged
and is redrained. That is honest for the two causes it is offered for and dishonest for the other
three, which is why they do not get it.

**Carry forward:** a corrective-edit flow for a rejected patient record is the missing half of
this, and it is its own PR (a screen, a repository method, and a decision about which fields a
worker may change after registration). Until it exists, a duplicate ABHA reaches a supervisor and
not a fix.

### Where the count and the list come from

- **Count.** `SyncState.failedCount`, unchanged, already inside `SyncStatusImpl.state`'s
  `combine` and already collected by `HomeViewModel`. Rendering it adds nothing.
- **List.** A new `SyncOutboxRepository.failedRecords()`, twenty per-DAO `getFailedForReview()`
  queries plus one batched patient-name lookup, **suspend and not a Flow**, fetched when the
  worker opens the list.
- **Home card.** `FailedRecordsCard`, the same `Card` + text column + trailing `OutlinedButton`
  idiom as `SyncStatusRow` directly above it, differing only in the error container colour.
  Rendered only when `failedCount > 0`.
- **List.** An `AlertDialog`, which is Home's existing dialog idiom (the resume prompt and the
  low-resource warning are both one). Not a destination, per the brief's settled point.
- **Requeue.** Row button to `HomeViewModel.onSendFailedRecordAgain` to
  `SyncStatus.sendFailedRecordAgain` to `requeueFailed(table, id)`, then the list is re-read.

**Why twenty small queries and not one `UNION ALL`.** One artifact is nicer to count, but this
environment has no Robolectric and no device, so neither shape can be EXECUTED by a runnable
test. Given that, twenty statements that each mirror the `observeFailedSyncCount` directly above
them are reviewable one at a time, and a seventy-line UNION with correlated sub-selects is not.
`SyncDaoSqlContractTest` pins the count at twenty either way. `consultation_documents` gets none,
consistent with it having no counter and never being drained: it can never hold a FAILED row.

**No new column and no backend change.** `syncErrorCode` has been persisted since the outbox
landed, S-1 added `retry_class`, S-2 added `syncErrorMessage` and `syncAttemptCount`. This PR only
reads them.

### `toLocalSyncState`'s unknown-status branch: recorded and skipped

**Decided: return null, and have `applyAck` record it in `skippedAcks` and skip, exactly as it
already does for an unknown TABLE.**

The argument is symmetry with a fix S-1 already made one layer down in the same function. A throw
here escapes `applyAck`, so every ack AFTER it in the batch is never applied either, the valid
ones included, and `InFlightBatchStore` is never cleared. One unrecognised word from the backend
became a whole-outbox outage, and a silent one, because nobody watches a WorkManager failure.

S-1 was right to leave it throwing at the time: skipping leaves the row PENDING, and before the
drain loop was bounded a permanently unackable row could be re-collected inside one drain without
end. S-2 bounded it twice (the in-drain `attempted` set, and `RETRY_MIN_INTERVAL` in every drain
predicate), so the skip now costs one record's bytes per fifteen-minute drain, which is what any
PENDING row costs.

**What an unknown status from a future backend does:** recorded, logged, dropped. The row keeps
its state (PENDING) and is resent next drain; S-1's absence analysis establishes resending any
record type is safe. The rest of the batch applies normally. It never reaches a worker, and
deliberately: an unknown ack status is a device/backend mirror break, a bug for an engineer, not
a task for a health worker with a patient in front of them.

`SyncAckMappingTest`'s assertion was inverted rather than deleted, so the decision stays visible
where the old one was. Two new drainer tests prove the batch survives and that the row is resent
on the NEXT drain but not inside this one.

### strings.xml

Twenty-six strings and one `<plurals>` added, following S-4's precedent. **The convention fork is
still undecided and now has two features on one side**: `kernel_failure_*` (S-4, eleven strings)
and `failed_sync_*` / `sync_record_type_*` (S-3). Everything else in the app still uses inline
constants, including the Home composables these sit between, so `HomeScreen` now reads from both
conventions within one screen. Recorded, not resolved.

---

## STEP 3: what this costs HomeViewModel

**Nothing on the launch path. No fourth collector, no widened flow.** MEASURED by reading the
wiring and pinned by a test:

- `SyncStatusImpl.state` already combines four sources, one of which is
  `syncOutboxRepository.observeFailedCount()`, itself a `combine` of twenty Room `Flow<Int>`
  counters. `HomeViewModel`'s existing second collector already collects it. Those twenty
  observable queries are subscribed at app start **today**, with nothing rendering the result.
  S-3 renders it. Delta: zero queries, zero collectors, zero flows.
- The list is a `suspend` one-shot behind a worker's tap on a card that only exists when the
  count is above zero. On the overwhelmingly common launch (count zero, card absent) it never
  runs at all.
- The requeue is a `suspend` one-shot plus a re-read of the list. The count updates itself
  through the flow that was already there.

**Nothing to record against F2A-01.** The pool-contention fix does not need to know about S-3,
because S-3 adds no work to the second of serialised Room IO the audit measured. The honest
footnote is the reverse: twenty of the queries F2A-01 is about are `observeFailedSyncCount`, and
until this PR they fed a field nothing read.

Guarded by `HomeFailedRecordsTest.the list is not fetched until a worker opens it`, which asserts
`failedRecordsCalls == 0` after construction. Adding a collector in `init` makes it fail
(mutation M8).

---

## STEP 4: tests and the mutation checks

Twelve mutations. Each applied, the whole JVM suite run, then restored from a file snapshot taken
before the pass and verified byte-identical with `diff`. `git checkout --` was not used, per S-2's
own recorded trap. The harness deletes the results directory before each run and treats an absent
one as RED, so a stale XML cannot be read as a pass.

| # | Mutation | Result |
|---|---|---|
| M1 | `toLocalSyncState` throws on an unknown status again | **RED**, 3 tests |
| M2 | The 6003 duplicate branch matches the wrong backend message | **RED**, 2 tests |
| M3 | `DUPLICATE_RECORD` offers a "Send again" button | **RED**, 3 tests |
| M4 | One review query widened to `syncState IN ('FAILED', 'RETRYABLE')` | **RED**, 1 test |
| M5 | One review query misspells a projection alias (`patientId` to `patient_id`) | **RED**, 1 test |
| M6 | One review query's table literal no longer matches `requeueFailed` | **RED**, 1 test |
| M7 | The list is loaded eagerly in `HomeViewModel.init` | **RED**, 2 tests |
| M8 | A requeued row is left in the list | **RED**, 1 test |
| M9 | Two causes share one body string resource | **RED**, 1 test |
| M10 | One table loses its noun and falls to the generic label | **RED**, 1 test |
| M11 | The device's mirrored constraint message is reworded | **RED**, 1 test |
| M12 | The **backend** rewords the constraint message | **RED**, 1 test |

M12 is the one that matters most as a check on the check: it proves the new
`backend/core/app/services/sync.py` entry in `app/build.gradle.kts`'s `inputs.files` actually
works. Without it the test task would have been UP-TO-DATE on a backend-only edit and reported
green, which is the trap S-1 found and this project's characteristic bug.

### What a test or the compiler caught that reasoning did not

1. **Room warns, it does not error, when a projection alias stops matching a NULLABLE field.**
   M5 was expected to be a compile failure. MEASURED: the build SUCCEEDED with
   `w: [ksp] ... FailedSyncRow has some properties [patientId] which are not returned by the
   query`, and every `audit_log` row would have rendered with no patient name, in a screen that
   only appears when something has already gone wrong. The alias assertion is therefore not
   redundant with the Room compiler, which was the assumption before running it.
2. **The table literal in each review SELECT is coupled to `requeueFailed`'s dispatch, and
   nothing was watching it.** Renaming `'patients'` to `'patient'` left the list rendering
   normally and made "Send again" a silent no-op for every patient row: the worker presses the
   button, is told nothing, and the record stays stuck. Invisible to every other test and to
   Room, which validates columns and has no opinion about what a string literal means to Kotlin
   two layers away. `each review query's table literal is one requeueFailed can dispatch on` was
   added mid-pass to close it, and M6 is that mutation re-run against the guard.
3. **A needle wide enough to catch an unrelated query passes when the guarded thing is gone.**
   The first draft counted `AS patientId` across the whole DAO directory and got twenty-one, not
   twenty, because `CaseRecordDao.observeDoctorTrackerRows` projects the same alias for the
   doctor tracker. Caught on the new test's first run. The count is now scoped to the review
   statements themselves, extracted by a helper, and the reason is in that helper's KDoc.

### New tests

- `SyncFailureReasonTest` (6): the copy table as assertions: every `(code, message)` pair, the
  duplicate separated from the generic reject, the fallback for an unknown code and for no code
  at all, and the action split pinned.
- `SyncFailureMessageMirrorTest` (2): the four mirrored backend messages, and that 23505 is
  still classified CONFLICT backend-side (if it became RETRYABLE, a duplicate would arrive as
  `RETRIES_EXHAUSTED` and the worker would be told to press a button that cannot work).
- `SyncDaoSqlContractTest` (+4): twenty review queries and none on the unwired table; the six
  aliases; FAILED-only; table literals match the requeue dispatch.
- `SyncFailureCopyTest` (4): no two causes share a resource, every syncable table has its own
  noun, an unknown table falls back, and the twenty collapse to exactly seven nouns.
- `HomeFailedRecordsTest` (6): card hidden at zero; list not read until opened; requeue removes
  the row from both list and count; closing drops the snapshot; an unknown code arrives as the
  fallback with a button; a duplicate arrives as its own cause with none.
- `SyncOutboxRetryBehaviourTest` (+2): an unknown ack status is skipped with the rest of the
  batch still applying (the unknown ack is deliberately FIRST in the batch, which is the whole
  point), and the row is resent on the next drain without spinning inside this one.
- `FailedSyncReviewQueryTest` (5, instrumented): the four structural shapes of the twenty
  queries against real SQLite: direct column, one-hop join (including the LEFT JOIN keeping an
  orphaned report), `CAST(NULL AS TEXT)`, and the aliased primary key round-tripping through
  `requeueFailed`. Five tables and not twenty is argued in its KDoc. **NOT RUN: no device.**

### Totals

```
app:  ./gradlew :app:testDevDebugUnitTest
      647 tests, 0 failures, 0 errors     (623 at the end of S-2)
      compileDevDebugKotlin / StagingDebug / ProdRelease / DevDebugAndroidTest: clean
      No Room schema change: no entity was touched, so no new schemas/*.json

backend: not run. S-3 changes no Python.
```

**No pre-existing failures.** Nothing in the JVM suite was failing before this change set and
nothing is now. No test was caused to fail by S-3 and left failing.

---

## STEP 5: the sync track, closed

The four symptoms in the diagnosis now behave as follows, end to end. A child row whose parent has
not landed is rejected `RETRYABLE` by the backend (S-1 put the class on the wire), lands in a
`RETRYABLE` state that the drain re-collects (S-2), and syncs on the drain after its parent
arrives, with no human ever told; if the parent never comes, five acked attempts exhaust the
budget and it becomes FAILED carrying a device-local code that says "we gave up" rather than "the
server refused you" (S-2), and it appears on a Home card with a count and a plain sentence, with a
button that genuinely can work (S-3). A record the server refuses on its merits is FAILED
immediately, is never retried, and now reaches a worker in words that name the record, name the
cause and name the escalation, with no button offered, because there is nothing on this phone
that can fix it. A kernel call that fails for one of ten reasons is typed at the catch site and
produces one of three worker actions instead of one misleading "Retry" (S-4). And an ack the
device cannot interpret, by table or by status, is recorded and skipped instead of destroying
every later ack in its batch (S-1 for the table, S-3 for the status).

What remains open is the other half of the last case: for the three causes with no button, the
app has no screen that can repair the record, so the worker's only route is a supervisor. That is
a real gap, it is now visible rather than silent, and it is the next PR rather than this one.

---

## Carry forward, not acted on

- **`MigrationTest19To20` (S-4), `MigrationTest20To21` (S-2) and now
  `FailedSyncReviewQueryTest` (S-3) are all written, compiled and unrun**, for want of a device.
  Every SQL semantic claim in this record is therefore INFERRED; only the SQL's TEXT and Room's
  compile-time column validation are MEASURED.
- **The strings.xml convention fork** is still undecided, and now has two features on one side.
  `HomeScreen` reads from both conventions within one screen, which is the first place it is
  visibly awkward.
- **The one-time re-push of pre-existing FAILED rows** remains owner-gated and unblocked by S-1's
  absence verdict. Out of S-3's scope by the brief. Note that those rows are now VISIBLE: an
  install with old FAILED rows will show a card on the next launch, and most of them will read as
  `UNRECOGNISED`, because they predate `syncErrorMessage` being persisted. That is honest and it
  may be a surprise on first release; worth the owner knowing before the re-push decision.
- **`CONFLICT` rows are not on this surface.** `observeFailedCount` counts FAILED only, and a
  `conflict` ack produces `SyncState.CONFLICT`, which is equally terminal-until-edited and equally
  invisible. Deliberately out of scope (the brief says "the failed-records surface"), and it is
  the same shape of gap this PR just closed for FAILED.
- **The 404 audit-row gap** (no `KERNEL_CALL_FAILED` for the `_resolve_case_record` path) remains
  standalone.
- **`sync_log.retry_class` is still not stored** backend-side, from S-1.
- **Robolectric is not in this module.** It is what would let the JVM suite execute Room SQL and
  read string resources, and it would have turned three of this PR's text-scanning guards into
  real tests. Not added here: a new test dependency mid-sequence is its own change with its own
  blast radius.
