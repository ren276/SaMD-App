# Async submission queue: design memo (STEP 1, design only)

Date: 2026-08-28. Branch: master @ 600e0e2. Read-only. No code, no branch, no commit.
Scope: build-sequence item 2, replace the blocking SendingScreen with a WorkManager job.
No ABDM. Companion to `scratchpad/kernel-reports-upsert-investigation.md`.

---

## Recommendations up top

**Decision 1: Option A, and narrower than Option A as written.** Add no token column,
no token table, no new field on the wire. The per-submission token is the WorkManager
unique-work name `assess_<caseRecordId>`. The backend already provides record-level
idempotency keyed on the client-generated row id, so a retry is already a no-op there.
Option B buys nothing today because there is no schema to keep stable: caseRecordId is
already the correlation token on the kernel wire (`SendingViewModel.kt:78`,
`caseToken = caseRecordId`), and anything ABDM-facing later attaches to the case or
encounter, both of which this design already carries end to end.

**Decision 2: the queue runs the per-case assessment, and the @Transaction fix stays
carried (OUT of scope), on one named precondition.** The either/or in the brief does not
map onto this repo, see "Where the repo contradicted the brief" below. The precondition
is that the assessment job is unique work keyed on caseRecordId with
`ExistingWorkPolicy.KEEP`, and that `RetryKernelAssessmentUseCase` is routed through the
same enqueue rather than calling the use case inline. With that, two saves for one case
cannot be concurrent, so the race never opens. Without it, the fix comes back in.

---

## The outbox-keying finding (this is the fact that decides the race fix)

**There is no outbox table. The outbox is virtual.** A row is its own outbox entry by
carrying `syncState = PENDING`. Nothing is enqueued, so no id is captured at enqueue
time, so there is no stale id to strand.

Evidence:

- `RoomSyncOutboxRepository.collectPendingRecords()` at
  `app/src/main/java/com/example/samdapp/data/sync/RoomSyncOutboxRepository.kt:54-78`
  reads the twenty syncable tables directly, live, at drain time. For kernel and evaluate
  that is `:70-71`, hitting `KernelReportDao.getPendingForSync()`
  (`KernelReportDao.kt:37-38`, `SELECT * FROM kernel_reports WHERE syncState = 'PENDING'`).
- `SyncOutboxDrainer.drainLocked()` calls `collectPendingRecords()` inside its loop
  (`SyncOutboxDrainer.kt:53-54`), so every pass re-resolves the current rows.
- Acks are applied by `(table, id)` where id is the row's current PK
  (`RoomSyncOutboxRepository.kt:80-106`), and the DAO guards the write with
  `WHERE id = :id AND localModifiedAt = :sentLocalModifiedAt`
  (`KernelReportDao.kt:40-46`), so a stale ack for a superseded revision applies to
  nothing rather than clobbering a newer local edit.

The one place a PK is held across time is `InFlightBatchStore`, between send and ack:
`SyncOutboxDrainer.kt:116-123` persists `(table, id, localModifiedAt)` per member before
sending. Crash-resume re-collects PENDING rows and intersects them with the saved member
set (`SyncOutboxDrainer.kt:93-94`). A superseded PK simply fails that intersection and is
dropped; if nothing intersects, the batch is cleared (`:96-101`). The row under the new PK
is still PENDING and is collected on the next pass.

**Verdict: the "stranded outbox entry" consequence named in the prior investigation memo
does not exist.** That memo's residual overstated it. The window is one in-flight batch
wide and it self-heals on the next drain. The `@Transaction` fix is therefore **OUT of
scope** for this work, on the precondition in Decision 2.

### What the race would still cost, if it ever opened

Worth recording because it is not zero, and it is why the precondition matters rather
than being cosmetic. If two saves for one case do race, the surviving local row's PK is
the race loser's. Suppose the other PK had already been pushed and applied. The device
then pushes the surviving row under a different id, and the backend accepts it as a new
record, because:

- `_apply_generic` looks up strictly by primary key:
  `backend/core/app/services/sync.py:285-287`,
  `select(model).where(pk_column == record_id)`.
- The backend's `case_record_id` index on both report tables is **not unique**:
  `backend/core/app/models/kernel.py:42` (`Index("ix_kernel_reports_case_record_id", ...)`)
  and `:90` for evaluate_reports. The device-side one-current-assessment-per-case
  invariant from MIGRATION_15_16 is **not mirrored server-side**.

So the cost of the race is two server rows for one case with nothing rejecting the
second. Not a crash, not a strand, but a traceability defect. Recorded here as a carried
item, not scoped into this work.

---

## Decision 1 in full: where a retry is already idempotent

The brief asks where an idempotency key would be checked so a retry is a no-op. Traced.
There are three independent layers, all already built, none needing a new key:

**Layer 1, WorkManager, prevents the duplicate run.** Unique work by name. A second
enqueue for a case already enqueued is dropped under `ExistingWorkPolicy.KEEP`. This is
the layer the new queue adds and the only new thing in the whole idempotency story.

**Layer 2, the device DB, makes a re-run overwrite rather than accumulate.**
`KernelReportRepositoryImpl.kt:29` resolves the row by caseRecordId
(`getIdForCase(report.caseRecordId) ?: report.id`) and upserts REPLACE, so the fresh UUID
minted per attempt at `GenerateKernelReportUseCase.kt:204` and `:251` is discarded on a
re-run. Same shape in `EvaluateReportRepositoryImpl.kt:49-52`. Enforced by the unique
index from MIGRATION_15_16 (`Migrations.kt:430`, DB version 16, `AppDatabase.kt:72`).

**Layer 3, the backend, makes a re-push a no-op.** Two nested guarantees:

- Batch replay: `sync_batches.batch_id` is the PK and `response_json` is returned verbatim
  on a replay within 24h (`backend/core/app/models/sync.py:33,47` and the docstring at
  `:20-25`). `push()` does this lookup before touching anything
  (`backend/core/app/services/sync.py:528-531,547`).
- Record replay: `_apply_generic` selects the existing row by client-generated PK
  (`sync.py:285-287`), and short-circuits to `status: "stale"` when
  `client_updated_at <= stored_ts` (`sync.py:302-309`). A resent identical record is
  answered `stale`, which `SyncAckMapping.kt:12` maps to `SyncState.SYNCED`, meaning
  "stop pushing it". Each record applies inside its own `begin_nested()` savepoint
  (`sync.py:488`), so one bad record cannot roll back the batch, and an `IntegrityError`
  becomes a per-record `rejected` (`sync.py:510-511`).

**So: the backend HAS a place to honor an idempotency key today, and the key is the
client-generated row id.** No new column, no new constraint, no migration. The
one thing this design must not do is churn that id, which is exactly what
`getIdForCase` already prevents and what the unique work name protects.

---

## The handoff point: where the blocking wait dies

Today, synchronously, inside `SendingViewModel`'s `init` block
(`app/src/main/java/com/example/samdapp/presentation/sending/SendingViewModel.kt:62-134`),
one `viewModelScope.launch` does all of:

1. read vitals, consultation, encounter, patient (`:66-76`)
2. build the pseudonymized payload (`:78`)
3. `generateKernelReportUseCase(...)` blocking on `/v1/assess` (`:82-87`)
4. `generateEvaluateReportUseCase(...)` blocking on `/v1/evaluate` (`:95-101`)
5. audit-log both outcomes (`:109-132`)
6. `_effects.send(SendingEffect.Done(...))` (`:133`)

The UI waits on exactly one thing: `SendingEffect.Done`. `SendingScreen.kt:33-41` collects
`viewModel.effects` and calls `onDone`, which `AppNavHost.kt:303-305` turns into
`backStack.add(KernelAssessmentRoute(...))`. Until then the screen is a spinner with
"Sending case to processing kernel…" (`SendingScreen.kt:48-53`).

**Exact handoff point: `SendingViewModel.kt:133`.** Steps 3 through 5 move into the worker.
`_effects.send(Done)` fires the instant the enqueue returns, not when the inference
returns. Steps 1 and 2 also move into the worker, because they are cheap local reads that
the worker can redo from `caseRecordId` alone. `RetryKernelAssessmentUseCase.kt:31-45`
already proves this is possible: it rebuilds the identical payload from `caseRecordId`
via `CaseRecordRepository.observeCaseRecord` for the encounter id. That existing use case
is the shape the worker's payload-rebuild should reuse, not a second copy of it.

Consequence: the worker (the human) lands on `KernelAssessmentRoute` with no report yet
and immediately backs out to the next patient, or the nav destination changes. That is a
UX decision for Sandesh, not settled here. What IS settled: the enqueue must happen
before `_effects.send`, and its failure must be surfaced rather than swallowed, because a
failed enqueue means no assessment will ever run for that case.

---

## WorkManager constraints and retry policy: mirror, do not diverge

Mirror `WorkManagerSyncOutboxScheduler` exactly. No stated reason to diverge.

- Constraint: `NetworkType.CONNECTED` only (`WorkManagerSyncOutboxScheduler.kt:29-31`).
  No battery or idle constraint. A PHC assessment must not wait for charging.
- Backoff: `BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS`
  (`WorkManagerSyncOutboxScheduler.kt:46`). That is 10 seconds, doubling.
- Work kind: `OneTimeWorkRequest` per case. Not periodic. The existing periodic job
  (15 min, `:83`) is the outbox drain and stays untouched.
- Uniqueness: `enqueueUniqueWork("assess_$caseRecordId", ExistingWorkPolicy.KEEP, request)`.
  This is the one deliberate divergence: the outbox uses `REPLACE` for its single
  `sync_push_now` name (`:48`) because a later drain subsumes an earlier one. Assessment
  work is per case and must not be replaced by a second enqueue for the same case, so
  KEEP, and that is also the whole race precondition from Decision 2.
- Worker shape: mirror `SyncPushWorker` (`SyncPushWorker.kt:18-33`). Thin `@HiltWorker`
  `CoroutineWorker` that adapts a suspend orchestrator to WorkManager's `Result`. All
  logic in a plain injectable class with no WorkManager dependency, so it is
  JVM-testable the way `SyncOutboxDrainer` is.
- `Result.retry()` vs `Result.failure()`: this is the one place NOT to copy
  `SyncPushWorker` blindly. `SyncPushWorker.kt:32` returns `retry()` on every failure
  because a permanently-bad row is already handled inside the drain as a FAILED state.
  The assessment job has no equivalent internal FAILED state today, so see the failure
  section below.

**No `runNowAndAwait` equivalent.** That method exists so "sync now" can give a prompt
answer (`WorkManagerSyncOutboxScheduler.kt:43-78`). The entire point of this queue is that
nobody waits. Do not port it, and do not port its `withTimeoutOrNull` / `sawRunning`
machinery.

---

## Completion notification: recommend the badge, not a system notification

**Recommend: Room Flow driven in-app state. No system notifications in this work.**

Grounding. The app has **no notification infrastructure at all**: no
`NotificationManager`, no `NotificationCompat`, no channel creation anywhere in
`app/src/main`, and no `POST_NOTIFICATIONS` in `AndroidManifest.xml` (permissions are
RECORD_AUDIO, CAMERA, ACCESS_NETWORK_STATE, INTERNET, lines 5-8). Adding system
notifications means a manifest permission, a channel, an API 33+ runtime permission
request and its denial path, and a foreground-service consideration if the job is ever
expedited. That is a feature of its own, not a detail of this one.

What the app already uses, and what this should use:

- Per-case report state is already a Room Flow: `KernelReportDao.observeForCase`
  (`KernelReportDao.kt:17-18`). The completion signal is simply "a row now exists for this
  case", which the DB already broadcasts.
- Aggregate sync state is already a combined Flow surfaced through `SyncStatusImpl.state`
  (`SyncStatusImpl.kt:71-78`), including `failedCount` from
  `RoomSyncOutboxRepository.observeFailedCount()` (`:108-121`). A pending-assessment count
  belongs in the same shape.
- In-progress and failed state, without any new schema, comes from
  `WorkManager.getWorkInfosForUniqueWorkFlow("assess_$caseRecordId")`. ENQUEUED, RUNNING,
  and FAILED are all readable from WorkInfo. No new column, no new `CaseStatus` value.
  `CaseStatus` (`CaseRecord.kt:7`) deliberately stays untouched: it is the clinical status,
  and the existing design keeps clinical status and transport state on disjoint columns
  (`SyncStatusImpl.kt:38-43`).

**One real gap this creates.** `KernelAssessmentViewModel` reads the report **one-shot**,
not as a Flow: `getForCase(caseRecordId)` at
`app/src/main/java/com/example/samdapp/presentation/kernelassessment/KernelAssessmentViewModel.kt:143-144`,
which routes to `KernelReportRepositoryImpl.getForCase` and its
`observeForCase(...).first()` (`KernelReportRepositoryImpl.kt:36-37`). Today that is
correct, because the blocking SendingScreen guarantees the row exists before the screen
opens. The instant the queue is async, that guarantee is gone and the screen will render
an empty state forever. **Converting that read to a collected Flow is mandatory, not
optional, and is part of this scope.**

---

## Failure surfacing: no silent red badge

Reuse the existing vocabulary. `SyncAckMapping.kt:11-15` already defines the terminal
outcomes: `rejected` maps to `SyncState.FAILED`, meaning "malformed, stop retrying
forever", and `conflict` maps to `CONFLICT`, meaning "surfaced for review, do not retry
blindly". Do not invent a parallel vocabulary.

Two distinct failure classes, and they need different treatment:

**1. The assessment itself could not reach a result.** This is already a solved,
non-silent case and needs nothing new. `GenerateKernelReportUseCase` never fails: it
falls through to `buildUnavailableOutput` (`:251`), writing a real row tagged
`InferenceSource.UNAVAILABLE` whose `reasoningSummary` literally says "Tap Retry to run
the assessment again" (`GenerateKernelReportUseCase.kt:122-123`). `KernelAssessmentScreen`
already renders that distinguishable unavailable state
(`KernelAssessmentViewModel.kt:80,86`) and already has a Retry affordance backed by
`RetryKernelAssessmentUseCase`. **The remedy path exists.** The queue just has to let the
row be written and let the screen observe it, which the Flow conversion above covers.

**2. The job never ran to completion.** New, and the one genuinely new failure mode.
The job was enqueued but the process died, the job was cancelled, or the worker threw
before any row was written. There is no row, so class 1's remedy never appears, and the
case sits with no assessment and nothing on screen saying so.

Design for class 2:

- The worker must be written so that reaching `buildUnavailableOutput` is the normal
  terminal path, never `Result.failure()`. Any exception the worker itself throws
  (payload rebuild failed, case record missing) should be caught and turned into the same
  UNAVAILABLE row, collapsing class 2 into class 1 wherever possible. This is the
  cheapest correct answer and it is why `RetryKernelAssessmentUseCase`'s failure returns
  (`:33,37,39,45`) matter: those are precisely the cases that must not become a silent
  nothing.
- For what is genuinely left, a case whose work is FAILED or CANCELLED with no report
  row, the surface is the case list: a case with no kernel report and no live WorkInfo is
  a stalled case, and it gets the same Retry affordance. This is derivable from data that
  already exists. It needs no new column.
- Retry cap: WorkManager has no built-in cap, as `WorkManagerSyncOutboxScheduler.kt:51-53`
  already documents. If the worker only ever returns `success()`, that is moot, which is
  another reason to prefer the collapse-into-UNAVAILABLE approach over `Result.retry()`.

---

## Scope list for STEP 2

| # | Item | Classification |
|---|---|---|
| 1 | `AssessmentQueueScheduler` interface plus `WorkManagerAssessmentScheduler`, mirroring `SyncOutboxScheduler` / `WorkManagerSyncOutboxScheduler`. Unique work `assess_<caseRecordId>`, `ExistingWorkPolicy.KEEP`, `NetworkType.CONNECTED`, `EXPONENTIAL` / `MIN_BACKOFF_MILLIS`. No `runNowAndAwait`. | Sonnet-mechanical |
| 2 | `AssessmentRunner`, the plain injectable orchestrator holding what `SendingViewModel.kt:62-132` does today (payload rebuild, kernel, evaluate, audit). Reuse `RetryKernelAssessmentUseCase`'s rebuild-from-caseRecordId shape, do not duplicate it. No WorkManager dependency, JVM-testable like `SyncOutboxDrainer`. | **Opus-design-needed** (the reuse-vs-duplicate call between this and `RetryKernelAssessmentUseCase` is a real seam decision, and its exception policy is the class-2 failure story) |
| 3 | `AssessmentWorker`, thin `@HiltWorker` `CoroutineWorker` adapting item 2. Mirror `SyncPushWorker.kt:18-33`. Terminal-path policy per the failure section, not a blind copy of `retry()`. | Sonnet-mechanical |
| 4 | `SendingViewModel` reduced to enqueue-then-`Done`. Handoff at `SendingViewModel.kt:133`. Enqueue failure must not be swallowed. | Sonnet-mechanical |
| 5 | `SendingScreen` copy no longer says "Sending case to processing kernel…" while nothing is being sent. Likely the screen disappears from the nav graph entirely and `ConsultationScreen`'s `onSent` (`AppNavHost.kt:293`) enqueues directly. | **Opus-design-needed** (nav-graph shape and where the worker lands next is a UX decision, see the handoff section) |
| 6 | `KernelAssessmentViewModel` one-shot `getForCase` (`:143-144`) converted to a collected Flow. **Mandatory**, see the completion section. | Sonnet-mechanical |
| 7 | Pending-assessment and stalled-case surfacing in the case list, derived from `observeForCase` plus `getWorkInfosForUniqueWorkFlow`. No new schema, no new `CaseStatus`. | **Opus-design-needed** (which surface, and what a stalled case looks like to a PHC worker) |
| 8 | `RetryKernelAssessmentUseCase` call sites routed through item 1's enqueue instead of running inline. **This is the Decision 2 precondition, not a nice-to-have.** | Sonnet-mechanical, but **must not be dropped** |
| 9 | Tests: enqueue idempotency under KEEP, worker terminal paths, the class-2 collapse into UNAVAILABLE, and the Flow conversion in item 6. Mirror `SyncOutboxDrainerTest`'s fake-driven JVM shape. | Sonnet-mechanical |

---

## Explicitly OUT of scope

- **The `@Transaction` fix on `KernelReportRepositoryImpl` / `EvaluateReportRepositoryImpl`.**
  Carried, not dropped. Re-opens only if item 8 is skipped or if the work name ever stops
  being keyed on caseRecordId.
- **Any new idempotency key, token column, token table, or wire field.** Decision 1.
- **Any change to `SyncOutboxDrainer`, `RoomSyncOutboxRepository`, `InFlightBatchStore`,
  `SyncBatchPacker`, `SyncAckMapping`, or the periodic `sync_push_periodic` job.** The
  new queue produces rows; the existing outbox pushes them. Two separate jobs, no overlap.
- **System notifications, notification channels, `POST_NOTIFICATIONS`, foreground
  service, expedited work.** Completion section.
- **A unique constraint on `case_record_id` in the backend `kernel_reports` /
  `evaluate_reports` tables.** Real gap (`backend/core/app/models/kernel.py:42,90`),
  needs an Alembic migration and a decision about existing duplicate rows in any deployed
  environment. Separate piece of work. Filed as carried.
- **Any ABDM M2/M3 shaping.** Per the brief.
- **New `CaseStatus` values.** `CaseRecord.kt:7` is clinical status and stays clinical.
- **Backend changes of any kind.** This queue is local drain only.

---

## Where the repo contradicted the brief

**Decision 2's either/or does not exist as framed.** The brief offers "drains ONLY
patient/encounter submissions" versus "drains kernel/evaluate reports too". Neither
describes this repo, for two reasons:

1. **Pushing reports to the backend is already done, by a different job.**
   `RoomSyncOutboxRepository.kt:70-71` already collects PENDING kernel and evaluate rows
   into the existing drain. Nothing about this work adds or removes that.
2. **The new queue's entire payload IS the kernel and evaluate run.** Everything
   `SendingViewModel` does synchronously today is the assess plus evaluate calls and their
   audit entries (`SendingViewModel.kt:78-132`). There is no patient-or-encounter
   submission in that screen to queue separately. A version of this queue that leaves
   kernel and evaluate alone would have nothing left to do.

So kernel and evaluate writes are unavoidably inside this work, and the brief's stated
consequence ("then the @Transaction fix for BOTH repositories is IN scope") would follow
automatically. It does not follow, because the outbox-keying finding removes the reason
the fix was proposed, and per-case unique work removes the concurrency that would trigger
it. That is why Decision 2 lands where it does rather than on either offered branch.

**Second, smaller.** The brief describes the token as needed so a retry "does not create a
second server-side record". The backend already prevents that, keyed on the client row id
(`backend/core/app/services/sync.py:285-287,302-309`), plus batch replay
(`app/models/sync.py:20-25,33,47`). The gap the brief anticipated is not open.

**Third, a correction to my own prior memo.** `scratchpad/kernel-reports-upsert-investigation.md`
listed "stranding any outbox entry holding the first id" as the consequence of the race.
That was written before reading `RoomSyncOutboxRepository`. There are no outbox entries;
the window is one in-flight batch and it self-heals. The real cost of the race is the
duplicate server row described above, which is smaller and differently shaped.
