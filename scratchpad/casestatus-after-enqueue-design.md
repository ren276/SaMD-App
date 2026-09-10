# STEP 1 design memo: how a sent-but-unassessed case should be represented

Carried ticket from PR #23. Design only, no code in this pass.

---

## Recommendation

**Option B.** The "sent" fact is already durably in the DB as an insert-only
`audit_log` row (`consultation_saved`) in the table `observeResumableDraftForUser`
already joins, so the exclusion is a predicate on an existing join, while Option A
would put a transport/pipeline fact into a clinical enum that two prior memos and the
backend state machine deliberately keep clinical, at the cost of a Postgres CHECK
migration, a transition-table edit, and a coordinated device+backend release.

Second, decisive point: Option A cannot even migrate itself without Option B's signal.
The only way to tell which existing mid-flight `DRAFT` rows should be backfilled to a
new `SUBMITTED` value is the `consultation_saved` audit row. If that signal is good
enough to drive A's backfill, it is good enough to be the fix.

---

## 1. Every reader of CaseStatus

Enum: `app/src/main/java/com/example/samdapp/domain/model/CaseRecord.kt:7`
`DRAFT, SAVED_LOCALLY, PENDING_SYNC, SENT_TO_DOCTOR, PRESCRIPTION_RECEIVED, ABANDONED`

Mirrored on the backend: `backend/core/app/models/enums.py:111` (docstring points at the
Kotlin file, values must match exactly, no mapping table).

### 1a. Compile-enforced readers (exhaustive `when`, safe under Option A)

| Site | What it does | Option A requires |
|---|---|---|
| `presentation/common/CaseStatusDisplay.kt:8-13` `doctorTrackerLabel()` | maps status to tracker label; today `DRAFT`/`SAVED_LOCALLY`/`PENDING_SYNC` all collapse to `"Sent"` | new branch, compiler forces it. Label decision needed: `"Sent"` is already the right word, so this one is nearly free |
| `presentation/common/CaseStatusDisplay.kt:18-25` `historyLabel()` | encounter-history label; `DRAFT` reads `"In progress"` | new branch, compiler forces it. Needs real clinical wording ("Submitted, awaiting assessment") |

Two sites. Both safe: Kotlin refuses to compile a non-exhaustive `when` on an enum.

### 1b. Silent equality readers (no compile error, each must be audited)

| Site | Check | Option A risk |
|---|---|---|
| `presentation/patientsummary/PatientSummaryViewModel.kt:90` | `caseStatus == SENT_TO_DOCTOR` gates `canOpenDoctorReview` | new value falls through to false. Correct by luck, still must be read |
| `presentation/patientsummary/PatientSummaryScreen.kt:166` | `== SAVED_LOCALLY` gates the send-to-doctor affordance | a `SUBMITTED` case shows no affordance at all. This is the screen a worker would use to pick the case back up, so this one is a real behaviour question, not a fall-through |
| `presentation/patientsummary/PatientSummaryScreen.kt:181` | `== PENDING_SYNC` banner | falls through, fine |
| `presentation/patientsummary/PatientSummaryScreen.kt:199,202,209` | `== SENT_TO_DOCTOR` / `== PRESCRIPTION_RECEIVED` review block | falls through, fine |
| `presentation/doctorlist/DoctorListScreen.kt:61` | `== PRESCRIPTION_RECEIVED` -> `isReviewed` | falls through, fine |

### 1c. SQL string-literal readers (no compile check at all, highest Option A risk)

All in `data/local/dao/CaseRecordDao.kt`:

| Site | Query | Option A risk |
|---|---|---|
| `:56-59` `abandonDraftsForPatient` | `UPDATE ... SET status='ABANDONED' WHERE patientId=:patientId AND status='DRAFT'` | **live hazard, see section 3b.** Under A a `SUBMITTED` case stops being caught here, which is arguably the desired outcome but is a silent behaviour change |
| `:65-68` `sendAllPendingSync` | `WHERE status='PENDING_SYNC'` | unaffected |
| `:71` `observePendingSyncCount` | `WHERE status='PENDING_SYNC'` | unaffected, but this count feeds `SyncStatusImpl.state.pendingCount` and the Home sync chip. Decide whether a submitted-unassessed case belongs in a "pending" count shown to the worker |
| `:113-118` `observeResumableDraftForUser` | `AND cr.status='DRAFT'` | this is the query the ticket is about |
| `:122` `observeOpenCaseCount` | `WHERE assignedDoctorId=:doctorId AND status='SENT_TO_DOCTOR'` | unaffected (no doctor assigned yet at this stage) |
| `:130-140` doctor-tracker rows | `WHERE cr.status IN ('SENT_TO_DOCTOR','PRESCRIPTION_RECEIVED')` | excluded, correct: not yet sent to a doctor |

### 1d. Write sites and plumbing

| Site | Note |
|---|---|
| `data/repository/CaseRecordRepositoryImpl.kt:30` | creates at `DRAFT` |
| `:40` | `updateStatus(..., SAVED_LOCALLY)` at Acknowledgement |
| `:44` | `SENT_TO_DOCTOR` or `PENDING_SYNC` on doctor assignment, by connectivity |
| `:55` | `PRESCRIPTION_RECEIVED` |
| `data/local/Converters.kt:53-54` | `value.name` / `CaseStatus.valueOf(value)`. Additive forward, but `valueOf` throws on an unknown name, so an APK rollback after any row is written with a new value crashes on read. Real, unmitigated cost of A |
| `data/local/entity/CaseRecordEntity.kt:15`, `domain/model/CaseRecord.kt:13`, `domain/model/DoctorTrackerEntry.kt:11`, `data/local/dao/DoctorTrackerRow.kt:13`, `data/local/dao/EncounterHistoryRow.kt:13`, `domain/model/ConsultationHistoryEntry.kt:16` | typed carriers, no branching, no work |
| `data/remote/dto/SyncPayloadDto.kt:167` | KDoc states `status` is the clinical `CaseStatus` and is on the wire |
| `data/sync/SyncStatusImpl.kt:38-43` | KDoc: sync drains `syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt` and **never** `status` |

### 1e. Backend readers (Option A reopens the backend, which the queue memo closed)

| Site | Option A requires |
|---|---|
| `backend/core/app/models/enums.py:111-124` | new enum member |
| `backend/core/app/services/encounter.py:27-36` `ALLOWED_TRANSITIONS` | new key plus new edges. Minimum: `DRAFT -> SUBMITTED`, `SUBMITTED -> {SAVED_LOCALLY, ABANDONED}` |
| `backend/core/app/models/clinical.py:129` | `enum_check("status", CaseStatus, "ck_case_records_status")` is a real Postgres CHECK constraint. Widening it is a DROP CONSTRAINT / ADD CONSTRAINT migration |
| `backend/core/alembic/versions/0002_clinical_tables.py:622` | the constraint's origin; a new `0007_*` revision is required |
| `backend/core/app/schemas/encounter.py:30-42` `CaseStatusUpdate` | typed on the enum, picks up the value automatically |
| `backend/core/app/services/encounter.py:258-267` | transition enforcement, `409 SAMD-ENC-4003` on anything outside the table |
| `docs/backend/api-contract.md:700-716` | the published allowed-values list and the transition table, both enumerated by hand |
| `backend/core/tests/test_encounters.py:135-230` | six transition tests, including `test_unknown_status_value_is_rejected` |

### 1f. Test-side readers (Option A)

`app/src/test/.../testutil/Fakes.kt` (10 refs), `androidTest/.../SyncStateResetTest.kt` (10),
`test/.../data/sync/SyncStatusImplTest.kt` (7),
`test/.../ResolveDoctorAssignmentUseCaseTest.kt` (6),
`test/.../PatientSummaryViewModelTest.kt` (5),
`test/.../ReceiveDoctorPrescriptionUseCaseTest.kt` (4),
`test/.../HomeViewModelTest.kt` (2), plus one each in `AssessmentRunnerTest.kt`,
`ConsultationChainTest.kt`, `MockDoctorPrescriptionInboxTest.kt`,
`androidTest/.../CaseRecordDayOrdinalDaoTest.kt`.

**Count for Option A:** 2 compile-safe, 7 silent Kotlin equality sites, 6 SQL literal
queries, 1 converter with a rollback hazard, 7 backend sites including a Postgres CHECK
migration and a hand-maintained contract doc, plus ~11 test files.

---

## 2. What already marks a case as "sent"

Three candidates, checked for existence, durability, and Room-queryability.

### Candidate 1: WorkInfo for `assess_<caseRecordId>` — exists, NOT usable

`WorkManagerAssessmentScheduler.kt:57` defines `uniqueWorkName = "assess_$caseRecordId"`;
`:47-55` exposes `observeWorkState` mapping to `QUEUED`/`RUNNING`/`NONE`.

Disqualified, plainly: **WorkManager keeps its own database, not `AppDatabase`. There is
no `case_records` join to WorkManager state and never can be, so this signal cannot
appear inside a Room `@Query` at all.** It is only readable in Kotlin, as a separate Flow
combined after the fact.

Second disqualifier even in Kotlin: the state is not durable in the sense needed here.
`observeWorkState` returns `NONE` both **before** an enqueue and **after** the work
finishes and WorkManager prunes it. `NONE` cannot distinguish "never sent" from
"sent and completed", which is exactly the distinction the resume prompt needs.

### Candidate 2: a `kernel_reports` row for the case — exists, but marks the wrong moment

`KernelReportDao.kt:17` `SELECT * FROM kernel_reports WHERE caseRecordId = :caseRecordId`;
the table is in the same Room DB, so it *is* joinable.

Disqualified on semantics, not mechanics. The row is written by `AssessmentRunner.run`
at the **end** of the assessment (including the `recordUnavailable` path, `AssessmentRunner.kt:66-70`).
The window this ticket is about is precisely "enqueued, no row yet". Report-row existence
answers "has this case been assessed", not "has this case been sent". Using it would leave
the entire enqueued-and-running window still offered as resumable, which is the bug.

### Candidate 3: an audit row — exists, durable, DB-queryable. **This is the signal.**

`ConsultationViewModel.onSend()` (`:179-220`) does, in order:
1. `saveConsultationUseCase(...)` (`:184`)
2. per-attachment `attachment_added` rows (`:194`)
3. **`auditLogger.log(action = AuditAction.CONSULTATION_SAVED, caseRecordId = caseRecordId, ...)` (`:208-213`)** — suspending, awaited
4. `_effects.send(ConsultationEffect.Sent(...))` (`:216`) which routes to `SendingRoute`
   (`AppNavHost.kt:293`), whose `SendingViewModel` enqueues (`SendingViewModel.kt:80`).

Properties:
- **Single writer.** `CONSULTATION_SAVED` is logged in exactly one place in the whole
  app: `ConsultationViewModel.kt:210`. Confirmed by grep; the only other references are
  the enum declaration (`AuditLogger.kt:36`) and the patient-facing label map
  (`PatientFacingAudit.kt:31`).
- **Durable across restart.** `audit_log` is a Room table. `AuditLogDao` has `@Insert`
  and status-column updates only, **no `@Delete` and no delete query** — insert-only per
  REQ-AUD-02.
- **Room-queryable, in a table the target query already joins.**
- **Indexed.** `AuditLogEntity.kt:9`: `indices = [Index("patientId"), Index("caseRecordId")]`,
  so a `caseRecordId`-keyed subquery is index-backed.
- **Written before the enqueue, and unconditionally.** Every case that reaches the
  enqueue has this row. No false negatives.

There is no `assessment_enqueued`-style action today. `AuditAction`
(`AuditLogger.kt:24-80`) has `CASE_SENT_TO_DOCTOR` and `CASE_QUEUED_FOR_SYNC`, but both
are written much later, at `DoctorAssignmentConfirmViewModel.kt:111`, after the
acknowledgement step. They are not the marker for this window.

Adding a new `assessment_enqueued` action is possible but is not free either: the
`AuditAction` KDoc (`AuditLogger.kt:20-23`) states the list is the wire contract, and the
backend mirrors it in `backend/core/app/domain/audit_actions_device.py` (`consultation_saved`
at `:38`). A new action means a backend accepted-set change, which is the same
"reopen the backend" cost that argues against Option A. Not recommended.

---

## 3. `observeResumableDraftForUser`, end to end

### 3a. The trace

`HomeViewModel.kt:61-81`
-> `CaseRecordRepository.observeResumableDraftForUser(userId)` (`CaseRecordRepository.kt:44`)
-> `CaseRecordRepositoryImpl.kt:84-85` (pure `.map { it?.toDomain() }`, no logic)
-> `CaseRecordDao.kt:113-118`:

```sql
SELECT cr.* FROM case_records cr
JOIN audit_log al ON al.caseRecordId = cr.id
WHERE al.userId = :userId AND al.action = 'encounter_started' AND cr.status = 'DRAFT'
ORDER BY cr.updatedAt DESC LIMIT 1
```

"Resumable" today means exactly three things ANDed: this worker started it
(`audit_log.userId` plus `action='encounter_started'`, because no `workerId` column exists
on `case_records` or `encounters`, per the DAO KDoc at `:105-112`), it is still `DRAFT`,
and it is the most recent such case.

Downstream, `HomeViewModel.kt:66-80` decorates it with the patient name into
`ResumableEncounter`; `HomeScreen.kt:102-108` shows `ResumeEncounterDialog`
("Resume in-progress consultation?", `:246`); confirming calls `onResumeEncounter`
which navigates to **`Compounder(patientId, resumeEncounterId, resumeCaseRecordId)`**
(`AppNavHost.kt:162-164`), and `CompounderViewModel.kt:212-221` writes an
`encounter_resumed` audit row.

**This makes the bug worse than "a confusing prompt."** Resume drops the worker back at
the *top* of the clinical flow, at vitals capture. Accepting it for an already-submitted
case means re-entering vitals and consultation on a case whose assessment is already
enqueued or complete, writing a clinically false `encounter_resumed` row, and pressing
Send a second time. `ExistingWorkPolicy.KEEP` (`WorkManagerAssessmentScheduler.kt:44`)
covers the still-running case, but once the first run has finished, a second enqueue runs
a second assessment and upserts over the existing report. This is an audit-visible
integrity problem, not just UX noise.

### 3b. Where the exclusion lands, and the second site

Option B's predicate lives inside the existing DAO query, keyed on the table the query
already joins. No new table, no new join, no new index. Shape:

```sql
... AND NOT EXISTS (
      SELECT 1 FROM audit_log s
      WHERE s.caseRecordId = cr.id AND s.action = 'consultation_saved')
```

(A second `JOIN` on `audit_log` would multiply rows; `NOT EXISTS` is the correct shape
and uses the `caseRecordId` index. Exact SQL is a STEP 2 decision, not fixed here.)

**Second site, and it is not optional.** `CaseRecordDao.abandonDraftsForPatient`
(`:56-59`) flips **every** `DRAFT` row for a patient to `ABANDONED` whenever a new case
starts for that patient (called from `StartCaseUseCase`). Before the async queue this was
safe, because a case left `DRAFT` only if the worker backed out before Acknowledgement.
After the queue, a submitted-but-unacknowledged case sits at `DRAFT` indefinitely, so a
returning patient the same day silently flips a case with a real, completed kernel
assessment to `ABANDONED`. That is clinical data being mislabelled by a cleanup query.

It needs the same exclusion predicate, and it is the same signal in the same table.
Option A would fix this site implicitly (a `SUBMITTED` row simply is not `DRAFT`), which
is a genuine point in A's favour, but it is one extra `NOT EXISTS` under B against a
CHECK-constraint migration and a coordinated release under A.

These two are the complete set: `grep` for `status = 'DRAFT'` / `status='DRAFT'` across
the app returns exactly `CaseRecordDao.kt:58` and `CaseRecordDao.kt:115`.

---

## 4. Regulatory framing: clinical status or pipeline fact

`CaseStatus` is the **clinical** vocabulary, and this codebase has drawn that line three
times already, in writing:

1. `SyncStatusImpl.kt:38-43`: the outbox drain "touches only the
   `syncState`/`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt` columns, never `status`
   (the clinical `CaseStatus`, flipped only by `caseRecordRepository`'s own methods)."
   Transport state lives on disjoint columns, by design.
2. `scratchpad/async-queue-design.md:211-213`: "No new column, no new `CaseStatus` value.
   `CaseStatus` (`CaseRecord.kt:7`) deliberately stays untouched: it is the clinical
   status, and the existing design keeps clinical status and transport state on disjoint
   columns." Restated as an explicit non-goal at `:303`.
3. `scratchpad/queue-seams-design.md:294-296`: "`CaseStatus` (`CaseRecord.kt:7`) stays
   clinical and is NOT extended for queue or pipeline state ... No new `CaseStatus` value."

And the backend enum's own header (`enums.py:100-108`) calls these "Clinical vocabularies
mirrored from the Android domain models."

**Verdict: "submitted, awaiting assessment" is a pipeline fact, not a clinical status.**
The clinical facts of the encounter do not change between enqueue and completion. Nothing
about the patient, the consultation, the vitals, or the worker's clinical intent differs
at 12:00:01 (enqueued) from 12:00:00 (about to enqueue). What changed is where the work
item sits in a local job queue. That is the same category as `syncState`, which this
codebase deliberately keeps on a separate column.

The queue's own pipeline tags already live off `CaseStatus`, exactly as designed:
`AssessmentWorkState` (`AssessmentQueueScheduler.kt:11`) is a WorkInfo-derived,
explicitly "display-only" enum, and report-row existence carries the completion half.

Option A would put a pipeline state into the enum that the backend's server-enforced
clinical state machine validates (`api-contract.md:703-716`, "Server-enforced
transitions"). The regulatory reading of that state machine is that it describes the
clinical lifecycle of a case; inserting a local-job-queue state into it makes the
regulated transition table partly a description of Android WorkManager. That cuts against
the separation, and it is the strongest argument here.

The counter-argument, stated fairly: `PENDING_SYNC` is already in the enum, and it is
arguably transport state. `enums.py:113-116` and `api-contract.md:714-716` both explain
it as a value the device writes while offline that legitimately reaches the server. So
the line is not perfectly clean today. The difference is that `PENDING_SYNC` describes
the *clinical* handoff to a doctor being deferred (the case is clinically finished and
waiting to move to a human), whereas "awaiting assessment" describes an internal AI job.
That distinction holds, but it should be acknowledged rather than pretended away.

---

## 5. Cost

### Option A (rejected): migration and schema cost

- **Room: no migration required.** `Converters.kt:53-54` stores `value.name` as a plain
  string with no CHECK constraint. `AppDatabase.kt:72` stays at version 16. The
  `CaseRecord.kt:5-6` KDoc already claims this forward-compatibility, and it is accurate.
- **Room rollback hazard, unmitigated.** `CaseStatus.valueOf(value)` (`Converters.kt:54`)
  throws on an unknown name. Once any row carries the new value, an APK downgrade crashes
  on read. No CHECK constraint means no guard either.
- **Backend: a real Alembic migration.** `clinical.py:129` `enum_check("status", CaseStatus,
  "ck_case_records_status")` is a Postgres CHECK; widening it is a new `0007_*` revision
  doing DROP CONSTRAINT / ADD CONSTRAINT on `case_records`, which validates the existing
  rows.
- **Backend transition table.** `encounter.py:27-36` plus the published table at
  `api-contract.md:703-716`, plus `test_encounters.py:135-230`.
- **Coordinated release, not device-only.** `status` is on the sync wire
  (`SyncPayloadDto.kt:167`). A device shipping `SUBMITTED` to a backend that has not
  taken the migration gets a 422 on the value or a `409 SAMD-ENC-4003` on the transition,
  which lands as FAILED outbox rows. The async-queue memo explicitly scoped "backend
  changes of any kind" out (`async-queue-design.md:307`); Option A reopens that.
- **Existing mid-flight DRAFT rows at upgrade.** They must either stay `DRAFT` (in which
  case the bug persists for exactly those rows and the fix is incomplete) or be
  backfilled. And **the only signal available to drive that backfill is the
  `consultation_saved` audit row**, that is, Option B's predicate, run once as a
  migration. Option A therefore contains Option B and adds a schema change on top.

### Option B (recommended): query-only

- **Zero schema.** No Room migration, no `AppDatabase` version bump, no Alembic
  revision, no wire change, no new enum value on either side, no new `AuditAction`.
- **Two DAO queries touched**, both in `CaseRecordDao.kt`: `observeResumableDraftForUser`
  (`:113-118`) and `abandonDraftsForPatient` (`:56-59`).
- **Device-only.** Backend untouched, consistent with the queue memo's scope.
- **Reversible.** A query predicate can be changed in the next release with no data
  consequences. A shipped enum value cannot be withdrawn.

**Guard test** (the one test that must exist, Room instrumented test against the real
DAO, mirroring `CaseRecordDayOrdinalDaoTest`):

`observeResumableDraftForUser` returns null for a `DRAFT` case that has both an
`encounter_started` and a `consultation_saved` audit row for the same user and case, and
still returns the case for a `DRAFT` case that has `encounter_started` only. Both
assertions in one test, driven by real rows, because that pair *is* the correctness bar:
a submitted case must not reappear, and a genuinely in-progress case must not stop being
offered.

Per CLAUDE.md's backend convention, this asserts the DB read, not a ViewModel state. It
is already a DAO test, so that is satisfied by construction.

---

## 6. STEP 2 scope

| # | Item | Classification |
|---|---|---|
| 1 | Add the `NOT EXISTS (consultation_saved)` predicate to `CaseRecordDao.observeResumableDraftForUser` (`:113-118`), with a KDoc line saying why (the async queue leaves submitted cases at `DRAFT`) | mechanical |
| 2 | Add the same predicate to `CaseRecordDao.abandonDraftsForPatient` (`:56-59`) so a returning patient's new visit cannot flip a submitted case to `ABANDONED` | **needs-review** (behaviour change to a cleanup query on clinical data; the KDoc at `:50-55` states the current intent and must be updated to match) |
| 3 | Room DAO test: submitted case not resumable, in-progress DRAFT still resumable (section 5's guard test) | mechanical |
| 4 | Room DAO test for item 2: `abandonDraftsForPatient` skips a submitted case, still abandons a genuinely-backed-out DRAFT | mechanical |
| 5 | `HomeViewModelTest` unchanged in substance; confirm the two existing `CaseStatus` refs still hold | mechanical |
| 6 | Update `CaseRecordDao.kt:105-112` KDoc: "resumable" now means started-by-this-worker AND still DRAFT AND not yet submitted | mechanical |

Nothing here touches the enum, the converters, the sync payload, the backend, or any
migration. If Sandesh takes item 2 out, items 1/3/6 still ship and still close the
ticket, but the `abandonDraftsForPatient` hazard stays open and should be filed as
carried rather than dropped.

### Carried, not in scope

- **`historyLabel()` (`CaseStatusDisplay.kt:19`) shows "In progress" for a submitted
  case.** Cosmetic under Option B and mildly wrong. Fixing it properly needs the same
  signal at the presentation layer, which means plumbing a derived flag through
  `ConsultationHistoryEntry`/`EncounterHistoryRow`. Not worth it for a label; revisit
  with queue-memo item 7.
- **Enqueue-failure orphan.** If `SendingViewModel.enqueueAndProceed` fails
  (`:78-86`) and the worker kills the app instead of using `retryEnqueue`, the case has
  a `consultation_saved` row but no assessment will ever run, and under Option B it is no
  longer offered as resumable. This case belongs to **queue-memo item 7**
  (`async-queue-design.md:281`, pending-assessment and stalled-case surfacing: no report
  row, nothing running), which is where a stalled case is meant to be recovered from.
  Option A would leave such a case at `DRAFT` and therefore still resumable, which is a
  genuine, narrow advantage for A; it does not outweigh the schema cost, and leaning on a
  crash-recovery prompt to catch stalled queue work is the wrong home for it either way.
- **`observePendingSyncCount` (`:71`) and the Home sync chip** do not count
  submitted-unassessed cases. Unchanged by this fix; that surfacing is item 7's job.

---

## 7. Repo contradiction with the brief

**One, and it does not change the analysis.** The brief says "`ConsultationScreen.onSend()`
enqueues the assessment." It does not. `ConsultationViewModel.onSend()`
(`:179-220`) saves the consultation, logs `consultation_saved`, and emits
`ConsultationEffect.Sent`; `AppNavHost.kt:293` then pushes `SendingRoute`, and
`SendingViewModel.kt:80` performs the enqueue. `SendingScreen` was **not** removed from
the nav graph (`AppNavHost.kt:297`), so async-queue memo item 5's "likely the screen
disappears entirely" did not happen.

Repo wins, and this is load-bearing in Option B's favour: because the enqueue is one
navigation step *after* the `consultation_saved` write, that audit row is guaranteed
already committed by the time any assessment work exists, so the exclusion predicate can
never lag the enqueue.

Second, minor: the brief lists "a sending-related AuditAction" as a candidate to check
for existence. There is no assessment-enqueue action. `CONSULTATION_SAVED` is the closest
existing marker and is written on the same code path, one step earlier.

---

## STOP

Design only. No code written, no branch, no commit. Awaiting Sandesh's review of the
recommendation and the section 1 reader list before STEP 2. Recommendation is Option B
(query-only), so per the brief STEP 2 is Sonnet-mechanical, with scope item 2
(`abandonDraftsForPatient`) flagged needs-review because it changes a cleanup query's
behaviour on clinical data.
