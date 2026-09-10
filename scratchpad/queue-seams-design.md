# Queue seams: design memo (STEP 1, design only)

Date: 2026-08-28. Branch: `design/queue-seams` (cut from master @ 600e0e2).
Read-only except this memo. Builds on `scratchpad/async-queue-design.md` and does not
contradict it. Settles the two items that memo marked Opus-design-needed so STEP 2
Sonnet never makes a design call in flight.

---

## Seam 1: the orchestrator reuse-vs-duplicate decision

### Recommendation: neither A nor B. Delete `RetryKernelAssessmentUseCase`.

Retry becomes an enqueue of the same unique work `assess_<caseRecordId>`, which async-queue
scope item 8 already requires. Once that item lands, the retry path and the queue path are
the same code by construction. There is nothing left for `RetryKernelAssessmentUseCase` to
do that `AssessmentRunner` plus the scheduler does not already do.

That collapses Seam 1 rather than deciding it. Option A (runner calls Retry) inverts the
dependency the wrong way and, as shown below, is not even behavior-preserving. Option B
(shared inner core) is the right shape only if two consumers survive, and after item 8 they
do not: retry stops being a code path and becomes a button that enqueues.

Deletion over addition. No new abstraction, one orchestrator, no divergence to manage
because there are no longer two things to diverge.

### What each currently does, and where they overlap

`SendingViewModel.kt:62-134` (the queue path's future content):

| Step | Line |
|---|---|
| read latest vitals for encounter, default to `VitalsReading()` if absent | `:66-67` |
| read consultation for encounter | `:68` |
| read encounter, then patient, for age and sex | `:73-76` |
| build pseudonymized payload via `SendToKernelUseCase` | `:78` |
| kernel call | `:82-87` |
| evaluate call | `:95-101` |
| audit `EVALUATE_RESPONSE_RECEIVED` / `EVALUATE_RESPONSE_FAILED` | `:109-123` |
| audit `KERNEL_RESPONSE_RECEIVED` | `:125-132` |

`RetryKernelAssessmentUseCase.kt:31-53`:

| Step | Line |
|---|---|
| resolve case record, derive `encounterId` from it | `:32-34` |
| read latest vitals, **fail** if absent | `:36-37` |
| read consultation, **fail** if absent | `:38-39` |
| read encounter, then patient, for age and sex | `:40-41` |
| build payload via `SendToKernelUseCase`, **fail** if null | `:43-45` |
| kernel call | `:47-52` |
| evaluate call | absent |
| audit | absent |

Genuine shared substance: both call `SendToKernelUseCase` with `caseToken = caseRecordId`
(`SendingViewModel.kt:78`, `RetryKernelAssessmentUseCase.kt:43`), and both do the same four
reads (vitals, consultation, encounter, patient) against the same repositories. That is the
whole overlap and it is real.

**Four differences, and they are not cosmetic:**

1. **encounterId source.** `SendingViewModel` receives it as an assisted constructor param
   (`:37`); Retry derives it from `CaseRecordRepository.observeCaseRecord` (`:32-34`).
   Retry's derivation is the one the worker needs, because a background job is handed only
   a `caseRecordId`. The runner takes Retry's shape here.
2. **Evaluate.** The queue path runs it (`SendingViewModel.kt:95-101`). Retry does not.
3. **Audit.** The queue path writes three audit actions (`:109-132`). Retry writes none.
4. **Missing-data policy, and this one is decisive.** `SendingViewModel.kt:66-67` defaults
   absent vitals to an empty `VitalsReading()` and assesses anyway. Retry returns
   `Result.failure` when vitals or consultation is missing (`:37`, `:39`).

Difference 4 is why **Option A is rejected outright**: routing the first assessment through
`RetryKernelAssessmentUseCase` would silently change a case with no vitals from "assessed on
empty vitals" to "no assessment and no row at all". That is a behavior regression smuggled in
as a refactor, exactly the silent drift the brief's bar warns about.

### Consequences of the recommendation, to be accepted explicitly

**C1. Retry now re-runs evaluate too.** Today it does not (difference 2). This is an
improvement, not a regression: the evaluate save is idempotent per case
(`EvaluateReportRepositoryImpl.kt:49-52`) and a successful re-run clears the H-14 failure
marker back to null (`:61`). A worker retrying a case that failed both calls currently only
gets the kernel half back. Accept.

**C2. Retry now writes audit entries.** Today it writes none (difference 3). Strictly better
for traceability. Accept.

**C3. The missing-data policy converges on STRICT, not lenient.** The runner should adopt
Retry's strictness (missing vitals or consultation means the assessment cannot run) and NOT
`SendingViewModel`'s `?: VitalsReading()` default. Reason: assessing on a silently-empty
vitals set produces a confident-looking report built on nothing, which is the fabrication the
`buildUnavailableOutput` design exists to prevent (`GenerateKernelReportUseCase.kt:100-107`).
Under this design a strict failure is not a dead end, because it collapses into a visible,
retryable UNAVAILABLE row per the catch below. **This is a behavior change to the
first-assessment path and is flagged for Sandesh to accept before Sonnet builds it.** It
must not be introduced silently.

### The class-2 collapse: exactly where the catch lives

`AssessmentRunner.run(caseRecordId): Unit` (returns nothing; the row is the output). Four
stages, with the catch wrapping the first two:

```
stage 1  resolve   case record, vitals, consultation, encounter, patient
stage 2  build     payload via SendToKernelUseCase
         <-- ONE try/catch wraps stages 1 and 2, and also converts their
             Result.failure / null returns into the same branch -->
stage 3  kernel    GenerateKernelReportUseCase(caseRecordId, payload, age, sex)
stage 4  evaluate  GenerateEvaluateReportUseCase(...) then audit both
```

**The catch lives in `AssessmentRunner`, around stages 1 and 2 only.** Stages 3 and 4 do not
need it and must not be wrapped in one that swallows:

- Stage 3 already never throws. `GenerateKernelReportUseCase` catches everything internally
  (`:238-243` in `tryRealApi`, rethrowing only `CancellationException` at `:236`) and always
  produces an output, falling through to `buildUnavailableOutput` (`:251`). Its only
  `Result.failure` is a failed DB save (`:104`, `save(output).map { output }`).
- Stage 4 already writes its own honest failure marker before returning failure
  (`GenerateEvaluateReportUseCase.kt:66`, `saveFailure(...)`), and the report screen omits
  the section when absent. Its failure must be audited (`SendingViewModel.kt:116-123`
  already does this) and must not fail the job.

On the stage-1/2 branch, the runner must write the same UNAVAILABLE row the kernel use case
would have written. It cannot call `buildUnavailableOutput` directly: that method is private
(`GenerateKernelReportUseCase.kt:246`). **Add one public entry point on
`GenerateKernelReportUseCase`, something like `recordUnavailable(caseRecordId: String)`, that
reuses the same private builder.** That keeps the honest-unavailable vocabulary in the single
place that already owns it, rather than a second copy of "Assessment unavailable" text in the
runner.

One detail Sonnet must not improvise: `buildUnavailableOutput` takes a `payload` and uses it
for exactly one thing, `dataQualityScore(payload)` at `:268`. With no payload there is no
data quality to score, so `recordUnavailable` passes `0.0`. That is honest and needs no
payload parameter.

**The one genuinely unrecoverable case, stated so it is not mistaken for a gap.** If stage 3
returns `Result.failure` because the DB save itself failed, the collapse cannot write an
UNAVAILABLE row either, since that needs the same failing save. There is no row and no
remedy inside the runner. That case is covered by async-queue scope item 7, the stalled-case
surface: a case with no kernel report row and no live `WorkInfo` is stalled and offers Retry.
The runner should log and return normally rather than pretend.

**Worker result policy, unchanged from the async-queue memo:** because every reachable
failure ends as a written UNAVAILABLE row, `AssessmentWorker` returns `Result.success()` on
every terminal path and never `Result.retry()`. WorkManager has no retry cap
(`WorkManagerSyncOutboxScheduler.kt:51-53` documents this), so an unbounded retry loop is a
real hazard and this policy avoids it.

### Seam 1 decisions, marked for Sonnet

| ID | Decision | Status |
|---|---|---|
| S1-1 | `AssessmentRunner` is the single orchestrator. Signature `run(caseRecordId: String)`. No WorkManager dependency, JVM-testable like `SyncOutboxDrainer`. | Sonnet-mechanical |
| S1-2 | `RetryKernelAssessmentUseCase` is **deleted**. Its call sites enqueue `assess_<caseRecordId>` instead. Its encounterId-from-caseRecordId derivation (`:32-34`) moves into the runner verbatim. | Sonnet-mechanical |
| S1-3 | New public `recordUnavailable(caseRecordId)` on `GenerateKernelReportUseCase`, delegating to the existing private `buildUnavailableOutput` with `dataQualityScore = 0.0`. | Sonnet-mechanical |
| S1-4 | Catch wraps stages 1 and 2 only, in `AssessmentRunner`, and routes to S1-3. Stages 3 and 4 are not wrapped in a swallowing catch. | Sonnet-mechanical |
| S1-5 | Missing-data policy converges on STRICT (C3). | **Opus-reviewed, needs Sandesh sign-off before build** |
| S1-6 | Retry now also runs evaluate (C1) and writes audit (C2). | **Opus-reviewed, needs Sandesh sign-off before build** |

---

## Seam 2: the friendly counter

### Recommendation: derive at read time. Zero schema. No migration.

`case_records.createdAt` already carries everything needed. Room stores `Instant` as epoch
millis (`Converters.kt:28-29`), so a millis-bounded `COUNT` works exactly as
`PatientDao.observePatientsWithEncounterBetween` already does (`PatientDao.kt:45-52`).

**The counter is the case's ordinal position in its own day's intake:**

```
position(case) = COUNT(*) FROM case_records
                 WHERE createdAt >= dayStartMillis(case.createdAt)
                   AND createdAt <  dayEndMillis(case.createdAt)
                   AND (createdAt, id) <= (case.createdAt, case.id)
```

Stable, monotonic, restart-proof, and zero migration. Stable because `case_records` rows are
only ever inserted, never deleted: abandonment is a status flip to `ABANDONED`
(`CaseRecordDao.kt:56-60`), not a delete. So once a case exists, the count of cases at or
before it inside its day can never change. The `(createdAt, id)` tiebreak mirrors the
deterministic ordering `PatientDao.kt:57` already uses (`... DESC, p.id ASC`).

### Stable day ordinal, not live queue depth

This is a design call the brief's phrasing ("case queued, position 4") leaves open, and it
matters. Two different numbers could be shown:

- **Day ordinal.** "Case 4 of today." Fixed the moment the case is created. A receipt.
- **Live queue depth.** "3 cases ahead of you." Changes as work drains, and counts down.

**Recommend the day ordinal.** The brief states the counter is a receipt and display-only.
A receipt whose number changes after it is handed over is not a receipt. Live depth also
goes stale the instant the worker walks away from the screen, which is precisely what this
queue exists to let them do.

If Sandesh also wants a live depth indicator, it is a different number with a different
label, derived separately as a count of today's cases with no kernel report row yet. It must
not be presented as the same "position". Out of scope here unless asked.

### The day boundary, concretely

**Local device midnight, in `ZoneId.systemDefault()`, computed from the case's own
`createdAt`, not from `now`.**

This is not a new convention. `PatientRepositoryImpl.kt:26-31` already defines "today" this
exact way for the Home roster:

```kotlin
val zone = ZoneId.systemDefault()
val today = LocalDate.now(zone)
val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
```

Reuse it. Do not invent a facility-day field, a day-start marker row, or a rolling window.
The one deliberate change: the day is taken from the case being displayed, not from the
clock at read time. `LocalDate.ofInstant(case.createdAt, zone)` rather than
`LocalDate.now(zone)`.

**Across midnight.** A case created at 23:59 keeps its number forever, because its window is
derived from its own `createdAt`. Computing the window from `now` instead would put that case
outside the current day's window and return 0 or a nonsense value at 00:01. This is the whole
reason for the "from the case, not from now" rule, and Sonnet must not simplify it back to
`LocalDate.now()`. The next case created at 00:01 is case 1 of the new day. No reset job, no
marker, nothing runs at midnight.

**On app restart mid-day.** Nothing happens. The number lives in `case_records`, which is on
disk. This is the failure mode the brief called out as worse than no counter, and derivation
rules it out structurally rather than by remembering to persist something.

**On a device clock jump.** The number is still stable for every already-created case, since
each is judged in its own day. A clock jump can make two cases land in different days that a
human would call the same day, and the counter would restart. That is acceptable for a
display-only receipt and needs no clock-sync machinery. Explicitly not worth solving here.

### Facility scoping

Device-local scope IS facility scope in this app, and no facility column is needed.

`UserSession` is `(userId, name, role)` (`AuthSession.kt:23`) and carries no `facilityId`.
The facility is stored at login into DataStore (`AuthTokenStore.saveLogin(..., facilityId,
facilityName)`, `AuthTokenStore.kt:36-46`) but is not exposed on the domain session. It does
not need to be: sync is push-only and every row on the device was authored on this device,
as `PatientDao.kt:80-83` already states for the roster query. One device serves one facility,
so a device-local count is already facility-scoped.

**Carried caveat:** when pull sync lands, that stops being true and the counter would start
counting server-sourced cases from other devices at the same facility. The same note already
exists on the roster query (`PatientDao.kt:80-83`), so this counter inherits an existing,
already-tracked constraint rather than adding a new one.

### Vocabulary guard: this is not an ABDM token

Recorded as instructed. The display counter and the ABDM M2 linking token are unrelated
concepts and must never share a name, a type, a column, or a helper:

- The **display counter** is a derived integer, device-local, day-scoped, display-only. It
  authorizes nothing, identifies nothing durable, and is never used to look a case up.
- The **ABDM M2 linking token** is a consent-authorization artifact, backend-only, with its
  own lifecycle and audit requirements.

Naming rule for Sonnet: call it a queue position or day ordinal. Do not use the word "token"
anywhere in this feature, and do not put it in a package or class whose name contains `abdm`
or `abha`. `caseRecordId` remains the sole durable case key, as the async-queue memo settled
in its Decision 1.

### Seam 2 decisions, marked for Sonnet

| ID | Decision | Status |
|---|---|---|
| S2-1 | Counter is derived at read time from `case_records.createdAt`. **No new table, no new column, no migration.** | Sonnet-mechanical |
| S2-2 | New `CaseRecordDao` query: `COUNT(*)` bounded by day-start and day-end millis with a `(createdAt, id)` tiebreak. Mirrors `PatientDao.kt:45-52`'s millis-bounds shape. | Sonnet-mechanical |
| S2-3 | Day window computed from the case's own `createdAt`, not `LocalDate.now()`. Reuse `PatientRepositoryImpl.kt:26-31`'s `ZoneId.systemDefault()` / `atStartOfDay` convention. | Sonnet-mechanical, **must not be simplified to `now()`** |
| S2-4 | Display is a stable day ordinal, not a live queue depth. | **Opus-reviewed, needs Sandesh sign-off if live depth was actually wanted** |
| S2-5 | No facility column. Device-local scope is facility scope while sync is push-only. Carried caveat inherited from `PatientDao.kt:80-83`. | Sonnet-mechanical |
| S2-6 | Naming guard: no "token" vocabulary, no `abdm`/`abha` package or class placement. | Sonnet-mechanical |

---

## Fixed display-surface constraints (not decided here, recorded as binding)

These are stated as fixed by the brief and are written here so the STEP 2 build treats them
as constraints, not choices:

- **ABHA number does NOT appear on the queue or any multi-case dashboard surface.** It is
  sensitive identity data and stays on the gated patient detail view. A shared facility
  dashboard listing multiple cases must never render ABHA.
- **Patient UID and ABHA are PATIENT identity, not CASE identity**, and never become the
  queue token. The queue's visible token is the display counter; case identity is
  `caseRecordId`.
- **`CaseStatus` (`CaseRecord.kt:7`) stays clinical and is NOT extended** for queue or
  transport state. Pipeline tags (Queued, Processing, Ready, stalled) derive from `WorkInfo`
  plus report-row existence, per the async-queue memo. No new `CaseStatus` value.

The last one is consistent with how this codebase already separates the two concerns:
`SyncStatusImpl.kt:38-43` documents that draining `case_records` touches only
`syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt` and never `status`.

---

## Repo contradictions with this brief

**One, in Seam 1.** The brief frames the choice as A or B, both of which keep
`RetryKernelAssessmentUseCase` alive as a second consumer. Async-queue scope item 8 already
routes retry through the queue enqueue, which removes that second consumer. So the shared-core
abstraction in Option B would be built for two consumers that do not both survive the same
change set. The recommendation is therefore outside the two offered options. If Sandesh
rejects item 8, Option B becomes correct again and this decision must be revisited.

**One, smaller, in Seam 1.** The brief lists the `RetryKernelAssessmentUseCase` failure
returns as being at `:33,37,39,45`. Confirmed accurate, and there is a fourth consideration
the brief did not name: those strict failures conflict with `SendingViewModel.kt:66-67`'s
lenient default for the same missing data. Unifying the paths forces a choice between them,
which is decision S1-5 above. This is not a contradiction of the brief so much as a decision
it did not know it was asking for.

**Seam 2: no contradiction.** The brief's preference for derive-at-read-time with zero schema
is achievable exactly as hoped. The brief's rejection of in-memory storage is correct and the
derivation makes it moot. The only refinement is that the day window must come from the case,
not from the clock, which the brief's "what happens across midnight" question anticipated.
