# AUDIT 2 — Encounter semantics, discard/re-register, and the real fix scope

- **Date:** 2026-08-27
- **Branch:** `master` (read-only; no code, no branch, no commits)
- **Predecessor:** `scratchpad/AUDIT-patient-registration-sync-gap.md` (diagnosis **D**)
- **Purpose:** decide the fix shape *before* STEP 2 is authorised.

---

## Headline

**Option 1 (mint an Encounter at registration) should be rejected. Option 4 (bounded `LEFT JOIN` on the Patients tab only) is the correct fix.**

Three independent findings converge on that:

1. The codebase **already contains an explicit, written rejection of Option 1** — `StartCaseUseCase`'s KDoc: *"this is when a PHC visit actually begins clinically, **not at Register (demographics can be captured without a visit)**."* Option 1 reverses a decision that was made deliberately and documented at the point of the code it governs.
2. `Encounter` has **no status, kind, or lifecycle column**. It is a bare existence marker (`id, patient_id, started_at, follow_up_of_encounter_id`). It carries meaning *only* by existing. Minting one at registration therefore cannot be qualified — it is indistinguishable from a real visit to every reader, on device and on server. That is exactly the implicit-substate pattern the question in §Q3 anticipates.
3. Sandesh's own two constraints (encounter = clinical work actually happened; workers must never delete patients) each independently kill Option 1 and its discard companion. Both constraints are **already codified in the repo** — the second verbatim in `docs/data-retention.md`.

---

## Part 0 — Sandesh's product reasoning, evaluated

Asked for: which parts hold up, judged as a scalability/security question, not a coding one.

### (a) "Encounter means the consultation actually happened / data reached the kernel"

**Verdict: the conclusion is right and decisive. The stated mechanism does not match today's code, and that mismatch is itself a finding.**

What the code does today:

- `StartCaseUseCase.kt:17-25` mints `Encounter` **and** a `CaseRecord(DRAFT)` together, at **`CompounderViewModel.kt:222`** — i.e. the moment the Compounder / Initial Assessment screen *opens*, before a single vital is typed.
- The kernel call happens much later, via `GenerateKernelReportUseCase`, and its outcome is recorded on **`CaseStatus`** (`DRAFT → SAVED_LOCALLY → PENDING_SYNC → SENT_TO_DOCTOR → PRESCRIPTION_RECEIVED`, plus `ABANDONED`) and in `kernel_reports` / `evaluate_reports` — **not** on `Encounter`.

So **"encounter count" today does not mean "data reached the kernel."** It means "a worker opened the vitals screen." A worker who opens Compounder and crashes out before finishing already produces an encounter, and that patient already shows on Home. If encounter counts are (or become) a delivery/reporting metric, they over-count today, in the opposite direction from the bug this audit started on.

This does not weaken the argument — it strengthens it. If the intended semantic is "clinical work happened", then `Encounter` is **already looser** than intended, and Option 1 would loosen it a second time, at a point (registration) where *no clinical work whatsoever* has occurred. Adding registration-encounters to a marker that is already over-inclusive is the wrong direction.

**Scalability angle:** at PHC volumes this is not a row-count problem, it is a *denominator* problem. The moment anyone reports "encounters per facility per month" to a district health officer, Option 1 makes registration events indistinguishable from clinical events in that denominator — permanently, retroactively, and with no column to separate them after the fact. Encounters are the natural join key for every future clinical analytic; contaminating it is a one-way door.

**Actionable spin-off (not part of this fix):** if "encounters" is meant to be the kernel-reached metric, that metric should be defined over `case_records.status` / `kernel_reports`, not `encounters`. Worth a separate ticket — see Follow-ups, item 3.

### (a2) "There must be a way to retry / continue a consultation after a crash — ACID, nothing lost"

**Verdict: correct requirement, and it is already built. Not a gap. Do not scope it into this fix.**

- `HomeViewModel.kt:61-83` observes `caseRecordRepository.observeResumableDraftForUser(sessionUserId)` and surfaces a `ResumableEncounter` prompt on Home. Its KDoc: *"Crash-recovery resume prompt (item 5, privacy/UX hardening pass): a `DRAFT` case this worker started but never reached Acknowledgement/save for."*
- `CompounderViewModel.kt:212-222` takes `resumeEncounterId` / `resumeCaseRecordId` and, when present, **resumes** rather than minting a second encounter — logging `AuditAction.ENCOUNTER_RESUMED`.
- Stale drafts are closed out with `CaseStatus.ABANDONED` (`CaseRecordDao.kt:54-58`), which is what stopped the earlier "one consult sent to two doctors" bug.

Note the asymmetry this exposes, and it is the crux of the whole issue: **an interrupted *consultation* is recoverable and reachable from Home. An interrupted *registration* is neither.** The crash-resume machinery hangs off `CaseRecord`, and a registered-but-unvisited patient has no `CaseRecord` to hang off. That is precisely the hole, and it argues for fixing the *find* path (Option 4), not for inventing a fake encounter so the existing machinery accidentally applies.

### (a3) "Offline → queue → push later"

**Verdict: correct, and also already built.** `CaseRecordRepository.kt:10-14` — `isOnline` decides `SENT_TO_DOCTOR` vs queued `PENDING_SYNC`; "Sync Up" flips every `PENDING_SYNC` to `SENT_TO_DOCTOR`. The Phase 6b outbox (`RoomSyncOutboxRepository` + `SyncPushWorker`) is the transport. AUDIT 1 proved it works end-to-end: 12/12 applied, 0 rejected. **Not in scope for this fix.**

### (b) "A worker must not be able to discard/delete a patient"

**Verdict: correct, important, and already the checked-in posture. This kills Option 1's companion affordance outright.**

`docs/data-retention.md:26` (verbatim):

> | `patients` | `PatientEntity` | Mutable, no-delete | Registration + updates only. |

Corroborating: `PatientDao` has **no `@Delete`** and no `DELETE FROM patients` query anywhere in the app. Server-side, every child table FKs `patients.id` with **`ON DELETE RESTRICT`** (13 constraints — see AUDIT 1's `\d patients`), so a hard delete is structurally refused by the database even if someone wrote the code.

**Security angle, and this is the strongest reason to hold the line:** a delete affordance on a patient record in the hands of a field ASHA worker is a *repudiation* primitive. `audit_log` is insert-only and hash-chained precisely so that "this person was registered" cannot be unsaid. A "discard registration" button re-opens that — worst case, a coerced or careless worker erases the record of someone having presented at the PHC, and the audit chain then attests to a patient that no longer exists. Under DPDP, erasure is a *data-principal* right exercised through a defined process, not a worker-side UI button. **AUDIT 1's option-1 recommendation of a "discard affordance" was wrong on this point; Sandesh's objection is correct and the affordance should not be built.**

If a mistaken registration must ever be neutralised, the shaped answer is the `ailments` precedent — a soft-delete/void flag that syncs, keeps the row and the audit trail, and is gated behind a supervisor role (`UserRole.DOCTOR`), not a worker tap. That is a separate, later decision; **nothing in the current bug requires it.**

### Net effect of Sandesh's input on the option set

| Option (from AUDIT 1) | Status after this input |
|---|---|
| 1. Block back-out + discard affordance | **Dead.** Discard violates `data-retention.md:26` and is a repudiation risk. Blocking back-out alone leaves the already-stranded patient stranded and traps a worker mid-flow with no exit. |
| 2. Mint encounter at registration | **Dead.** Contradicts `StartCaseUseCase`'s documented rule, contaminates the encounter denominator, and creates an unnameable substate. |
| 3. Bounded by-registration-date roster query on Home | **Dead.** Puts non-clinical entries in the work queue; breaks REQ-ROS-01 ("those with an encounter today") head-on. |
| 4. `LEFT JOIN` on the Patients tab only | **Survivor.** Detailed in §Q4. |

---

## Q1 — Everywhere `Encounter` currently means "a clinical interaction happened"

### The shape of the type

`backend/core/app/models/encounter.py` and `app/.../entity/EncounterEntity.kt`:

```
id, patient_id, started_at, follow_up_of_encounter_id, created_at, updated_at
(+ sync columns: sync_state/server_version/… on both sides)
```

**There is no `status`, no `kind`, no `type`, no `ended_at`, no `completed`.** The row's existence *is* the assertion. Nothing downstream can ask an encounter what sort of encounter it is.

### Readers, and how each would misread a registration-encounter

| # | Reader | File:line | Reads it as | Misreads under Option 1? |
|---|---|---|---|---|
| 1 | **Device Home roster** | `PatientDao.kt:45-52` via `HomeViewModel.kt:53` | "patients seen today" — the work queue | **Yes — and this is the whole point.** Every registered patient joins the work queue as if awaiting clinical action. Registration-heavy days would bury the actually-pending patients. Directly breaks REQ-ROS-01's wording, *"those with an encounter today"*. |
| 2 | **Device Patients tab** | same query via `GetRecentPatientsUseCase` | "seen in the last 7 days" — empty state literally says **"No patients seen in the last 7 days."** (`PatientsScreen.kt:52-56`) | **Yes**, but benignly — this is the surface where we *want* them listed. Only the copy is wrong, which Option 4 fixes honestly instead of by redefining "seen". |
| 3 | **Backend roster API** `GET /api/v1/patients` | `services/patient.py:_roster_query` + `roster()` (`:228`), `api/v1/patients.py:99-145` | Facility-scoped `INNER JOIN Encounter` over `started_at`, keyset-paginated on `last_encounter_at`, window hard-capped at `MAX_ROSTER_WINDOW` = 31 days | **Yes.** `RosterPage` returns `last_encounter_at` per patient (`schemas/patient.py:130`). Under Option 1 that field would report the *registration* timestamp as a clinical last-seen date — to any future consumer, including a district dashboard. Silent, plausible, wrong. |
| 4 | **Encounter bundle** `GET /api/v1/encounters/{id}` | `services/encounter.py:107-225` | Assembles consultation + observations + ailments + case record for one encounter | **Degrades.** Returns a structurally valid but entirely empty bundle. Not corrupt, but every consumer must now handle "an encounter with nothing in it" as a normal case. |
| 5 | **Follow-up linkage** | `Encounter.follow_up_of_encounter_id`; `ResolveDoctorAssignmentUseCase` ("follow-up to a prior visit defaults to that visit's doctor") | "the prior visit this one continues" | **Yes, clinically.** A registration-encounter becomes a selectable "prior visit" in PatientSummary's history, and can become the anchor a follow-up defaults its doctor from — an empty encounter has no doctor, so the default silently falls through to auto-assign. Wrong-doctor-linkage risk touches **H-03**. |
| 6 | **`consultations`** | `models/encounter.py` — *"One per encounter in practice, not enforced as such"* | 1:1 with encounter | **Yes.** Breaks the stated practice invariant: every registration-encounter is an encounter with zero consultations. |
| 7 | **`case_records`** | `CaseRecordEntity.encounterId`; `StartCaseUseCase` creates them as a pair | every encounter has a case record | **Yes.** Option 1 mints the first encounter in the codebase with **no** `CaseRecord`. `HomeViewModel`'s resumable-draft logic, the doctor tracker, and `CaseStatus` reporting all assume the pairing. This is the largest structural blast radius. |
| 8 | **Kernel path** | `services/kernel.py`, `GenerateKernelReportUseCase`, `KernelPayload.kt` | — | **No.** Verified: `KernelPayload` carries **no** encounter field; the kernel is fed vitals/ailments/history keyed by case. Sandesh's instinct that encounters are kernel-adjacent is right in *spirit* but the coupling is via `CaseRecord`, not `Encounter`. |
| 9 | **Report formatter** | `domain/report/ReportFormatter.kt` | — | **No.** No encounter references; reads ailments/vitals directly. |
| 10 | **Admin dashboard** | `api/admin.py:206` `admin_dashboard()` | users / sync-state / audit / chain panels only | **No.** Confirmed: no encounter panel exists today. **But this is a timing accident, not a safeguard** — the first "activity" panel anyone adds will reach for `encounters`, and by then the contamination is historical and unfilterable. |
| 11 | **Instrumented test** | `TodaysPatientsDaoTest.kt:55-80` | Asserts a patient named `"never"` with **no encounter** is excluded, and that an empty-window query returns `emptyList()` | **Yes — the test breaks or, worse, silently keeps passing while the production meaning has moved underneath it.** `traceability-matrix.md:28` binds this test to REQ-ROS-02 / H-04. |

**Count: 7 of 11 readers misread it. Two of those (5, 7) are clinical-safety-adjacent (H-03), and one (3) leaks the wrong meaning across the network boundary into a paginated public API field.**

The two "safe" readers (8, 9) are safe only because they route through `CaseRecord`. That is the real lesson: **the codebase already has a type that means "clinical work happened" — it is `CaseRecord` + `CaseStatus`.** `Encounter` is the visit envelope. Option 1 asks the envelope to also mean "no visit", which is a contradiction in one column-less type.

---

## Q2 — The discard → re-register cycle

Traced as asked, though note the conclusion: **this path should not be built** (Part 0(b)). Recorded so the decision is evidence-based and so the constraints are on file if a supervisor-gated void is ever considered.

### Constraint inventory

| Layer | Constraint | Enforced where |
|---|---|---|
| Device Room | **None.** `PatientEntity` declares no unique index on `abhaNumber` (`Index("patientId")` exists on `encounters`, nothing equivalent here) | — |
| Backend column | `ix_patients_abha_number` **UNIQUE**, global — *not* facility-scoped | `\d patients` |
| Backend service | `_assert_abha_unclaimed()` → `ErrorCode.PAT_DUPLICATE_ABHA` (SAMD-PAT-3004) | `services/patient.py:77-95`, called at `:145` (create) and `:174-177` (update) |
| Backend FK | 13 × `ON DELETE RESTRICT` referencing `patients.id` | `\d patients` |
| ABHA→patient link | `abha_profiles` is its own table (`pk_attr="abha_id"`, rank 19); `patients.abha_number` is an independent copy | `sync.py` TABLE_REGISTRY |

`_assert_abha_unclaimed`'s docstring is unusually explicit and worth quoting in full, because it pre-answers the question:

> Never auto-merge, never reassign. Two patient records claiming one ABHA number is the
> wrong-patient hazard (H-03), and it is resolved by a human, not by whichever write landed
> second. The UNIQUE index is the backstop; this check exists to return the right error code
> instead of an opaque integrity violation.

### The cycle, step by step

**Local-only discard (patient never synced):**
1. Register → local `patients` row, `syncState = PENDING`.
2. Discard → hard delete would work locally (no FK constraints in Room, by design) **but** would orphan the already-written `audit_log` `PATIENT_REGISTERED` row, which is insert-only and hash-chained and will still sync. Result: the server receives an audit entry referencing a `patient_id` that never arrives. That is a *permanent* chain artefact.
3. Re-register same Aadhaar → new 12-char id, `abha_number` identical. Pushes cleanly. Backend has one patient. **Recovers — but has left an unresolvable audit orphan.**

**Post-sync discard (the realistic case — the outbox drains on a timer):**
1. Register → syncs. Backend now holds `patients(id=X, abha_number=A)` permanently (no delete path, `RESTRICT`).
2. Discard → deletes local row only. **The server copy is unreachable and undeletable.**
3. Re-register same Aadhaar → new id `Y`, same `abha_number = A`.
4. Push → `_assert_abha_unclaimed` fires → **`PAT_DUPLICATE_ABHA` (SAMD-PAT-3004), rejected.**
5. Device marks row `FAILED` (`PatientDao.applySyncResult`). `observeFailedSyncCount()` lights the failure counter on Home. **The row is now permanently unsyncable — every retry rejects identically, because the condition is on the server and no device action can clear it.** The worker sees a patient locally, a red sync count, and no explanation or remedy.

**This is strictly worse than the bug being fixed.** Today's failure is "I can't find the patient." That failure would be "I have created a permanently broken record and a permanently failing sync, and re-registering makes it worse each time."

### On ABDM idempotency

Sandesh is right that ABDM is idempotent for an already-enrolled Aadhaar. `abdm_adapter/transaction.py:73-83` documents the observed real behaviour (xlsx rows CRT_ABHA_108/109): *"same number skips straight to enrollment completion, different number requires an explicit mobile-OTP step."* Re-enrolment returns the **same ABHA number**.

**That idempotency is what makes the collision certain rather than unlikely.** ABDM being well-behaved guarantees step 3 produces the identical `abha_number`, guaranteeing step 4's rejection. Note also `transaction.py:62-64`: *"REQ-AUD-02-style idempotency for ABHA sessions is a Phase 6/later concern, not built here"* — the adapter's own session state machine treats a repeat call at the same state as **out of order**, not as a benign retry. So the re-enrolment leg is itself not currently retry-safe.

### If a void path is ever wanted

Not now. For the record, the shape that would work: follow the `ailments` precedent — a synced soft-delete flag (`voided_at` + reason), `patients` stays `Mutable, no-delete`; `_assert_abha_unclaimed` amended to ignore voided rows so re-registration can succeed; role-gated to `UserRole.DOCTOR`; audit action for the void itself. **That is a schema change on both sides plus a migration plus an error-contract amendment — far more than the present bug justifies.**

---

## Q3 — Does Option 1 add an unnamed `REGISTERED_NO_VISIT` substate?

**Yes. Unambiguously, and worse than the general form of that pattern.**

The usual implicit-substate smell is a state that *could* be distinguished with effort. Here it **cannot be distinguished at all**: `Encounter` has no discriminating column, so a registration-encounter and a real visit are **byte-identical** apart from what other tables happen to reference them. The only way to tell them apart would be a negative join — *"an encounter with no `case_record` and no `consultation`"* — which is:

- **unindexed** (an anti-join across two tables per row);
- **not stable** — an encounter mid-consultation legitimately has no consultation row yet, so the discriminator collides with the genuine in-progress state;
- **not available at all** to the device's `INNER JOIN` roster query without rewriting it — the exact query Option 1 exists to avoid touching.

So Option 1's supposed advantage ("no roster-query change") is illusory: the moment any reader needs to tell the two apart, the roster query has to change **anyway**, and by then the historical rows are already ambiguous.

**Should `Encounter` grow an explicit `kind`/substate column instead?**

**No — that is scope creep, and it is scope creep in service of an option that should be rejected anyway.** A `kind` column would need: a Room migration (v17), a backend Alembic migration, a `TableSpec` field-map change, a wire-contract amendment in `api-contract.md`, backfill semantics for every existing row, and an exhaustiveness sweep of all 11 readers in §Q1. All to make a row that means "no clinical interaction occurred" safe to store in a table whose entire purpose is recording clinical interactions.

The correct reading of that cost is a signal, not an obstacle: **the state being modelled does not belong on `Encounter`.** "Registered, not yet seen" is not a kind of visit. It is the *absence* of a visit — and it is already perfectly represented, today, with zero new columns, by `a patient row with no encounter`. The data model is already right. **Only the read path is wrong**, and that is Q4.

*(Aside, for the ADR: if the intended semantic is Sandesh's "encounter = clinical work completed", the honest way to get there is not a `kind` column on `Encounter` but reporting off `CaseStatus`, which already distinguishes DRAFT / SAVED_LOCALLY / PENDING_SYNC / SENT_TO_DOCTOR / PRESCRIPTION_RECEIVED / ABANDONED. That vocabulary already exists and is already synced.)*

---

## Q4 — The `LEFT JOIN` alternative

**This is the right fix.** It splits the semantics exactly where the requirements already split them:

- **Home = work queue.** `INNER JOIN encounters`, unchanged. REQ-ROS-01 ("those with an encounter today") preserved **verbatim** — no requirement text is amended, no traceability row moves.
- **Patients tab = "who exists here, recently".** Bounded `LEFT JOIN`, so a registered-but-unseen patient is findable.

Shape (illustrative — **no code in this memo**): a *second, separate* DAO query for the Patients tab, joining `LEFT`, bounded on `COALESCE(MAX(e.startedAt), p.createdAt)` within the same 7-day window. Leaving `observePatientsWithEncounterBetween` **untouched** matters: it keeps `TodaysPatientsDaoTest`'s REQ-ROS-02 assertions passing unmodified, so the H-04 control that test guards is demonstrably not regressed. The new query gets its own test asserting its own bound.

### Does the Patients tab have its own DPDP constraint, or is it Home-only?

**Checked. The constraint is device-wide, not Home-only — but the exposure delta here is genuinely nil, for a reason that is specific and must be written down because it expires.**

The governing text:

- `software-requirements.md:62-63` — **REQ-ROS-02** *"Never expose an 'all patients' query — list access is day-scoped only (data minimisation; risk H-04)."*
- `software-requirements.md:72` — **REQ-SEC-02** *"Minimise on-device data to the current day's scope (risk H-04)."*
- `regulatory-foundation.md:155` — *"Our **day-scoped local cache** and **SQLCipher-at-rest** are direct DPDP data-minimisation…"*
- `hardening.md:18-20` — *"roster queries scoped to today (Home) / last 7 days (Patients tab). No all-patients query exists anywhere on the DAO."*

Note `hardening.md` already grants the Patients tab a **different, wider window** than Home (7 days vs 1). The two surfaces are **already** governed separately. The precedent for differentiating them exists.

The decisive point: **all three controls govern what the device *holds* and what a query can *reach*. A `LEFT JOIN` changes neither.**

- The patient row is **already on this device** — the worker registered it here.
- Sync is **push-only**. `docs/sync-design.md:89`: *"`RemoteMediator` (the pull/read side) is still not built — Phase 3 of the sync roadmap."* No pull path exists in the app (verified: no device caller of `GET /api/v1/patients`).
- Therefore **every `patients` row on a device today was authored on that device.** A `LEFT JOIN` cannot surface a patient the holder of this device did not personally register.
- The window stays bounded (7 days). No unbounded query is introduced. REQ-ROS-02's literal prohibition — *"an 'all patients' query"* — is not violated.

**Exposure delta: patients this device registered in the last 7 days who were never seen. Bounded, self-authored, already resident, already encrypted at rest under SQLCipher.** Weigh that against the status quo: a clinical record the worker created, cannot find, cannot act on, and will attempt to re-create — producing the duplicate-ABHA hazard H-03 was written to prevent. **The safety case runs in favour of the change.**

**Precedent already in the codebase:** `CaseRecordDao.observeDoctorTrackerRows()` (`CaseRecordDao.kt:105-121`) is a **cross-patient, non-day-scoped** query that joins `patients` — and its KDoc defends itself explicitly: *"Deliberately not scoped to one patient — this IS the cross-patient view the tracker exists to show, unlike `PatientDao`'s deliberately day-scoped roster query."* Purpose-scoped exceptions to day-scoping are already an accepted pattern here, provided they are named and justified at the query. The new one should be written the same way.

### The condition that expires — this must go in the PR, not just this memo

**The "no new exposure" argument holds only while sync is push-only.** When `RemoteMediator` lands (sync roadmap Phase 3), the device will hold patients it did not author, and a `LEFT JOIN` over `patients` would then surface *server-sourced* patients with no encounter — which **is** a real widening of H-04.

Mitigation, decided now rather than discovered later: bound the new query on **`p.createdAt`** (device-authored registration time) rather than on an unbounded patient set, and add the constraint to the query's own KDoc — *"safe while sync is push-only; when pull lands, this must additionally filter to device-authored rows."* One sentence at the site, and a note on the sync-roadmap Phase 3 ticket. **A control whose validity depends on a condition must state the condition where the next reader will hit it.**

### Cost

| Item | Cost |
|---|---|
| New DAO query + repository method + use case | Small; mirrors existing shapes exactly |
| `PatientsViewModel` | Swap the use case; search filter unchanged |
| `PatientsScreen` empty-state copy | *"No patients seen in the last 7 days."* → wording that covers registered-not-seen |
| Roster row UI | Should visually distinguish "registered, not yet seen" — otherwise the work-queue/directory split is invisible to the worker and the tab silently becomes a second work queue |
| `TodaysPatientsDaoTest` | **Unchanged** — the query it guards is untouched |
| New test | One: registered-with-no-encounter appears in the new query, and an out-of-window registration does not |
| Requirements / traceability | REQ-ROS-01 untouched. REQ-ROS-02 needs a **clarifying note**, not an amendment: list access remains bounded; the Patients tab's bound is `COALESCE(last encounter, registration)` within 7 days. Add a matrix row for the new query. |
| Backend | **None.** Device-only change. |

### The gap this fix does *not* close

The backend roster (`GET /api/v1/patients`) stays `INNER JOIN Encounter`, so a registered-never-seen patient remains invisible **server-side**. Today that is harmless — nothing reads it. It matters at **device replacement / re-install**: with no pull path, a wiped device loses local-only patients regardless, and the encounter-less ones would stay unreachable even after pull lands. **Name it on the Phase 3 pull ticket; do not fix it in this PR.**

---

## Recommendation

**Authorise STEP 2 for Option 4, device-only, scoped as:**

1. New bounded `LEFT JOIN` query on `PatientDao` for the Patients tab; `observePatientsWithEncounterBetween` left **byte-for-byte unchanged**.
2. `PatientRepository` / use case / `PatientsViewModel` wired to it.
3. Patients-tab empty-state copy corrected; roster row visually marks "registered, not yet seen".
4. One new instrumented DAO test; `TodaysPatientsDaoTest` untouched and still green.
5. KDoc at the new query stating the purpose-scoped exception **and** the push-only precondition, in the house style of `observeDoctorTrackerRows`.
6. REQ-ROS-02 clarifying note + one traceability-matrix row.

**Explicitly not in scope:** minting encounters at registration; any `kind`/substate column on `Encounter`; any discard/delete/void path; any backend change; anything about crash-resume or offline queueing (both already built).

---

## Follow-ups to file separately

### 1. "AbhaProfile sync-log gap" — **no gap exists; do not file**

Re-checked against AUDIT 1's own output. `abha_profiles` **is** in `sync_log`, logged through the same generic path as every other table:

```
  6 | f4d8973a-… | abha_profiles | 91756140880001 | applied |  |  | 1 | 2026-08-27 05:56:42.011802+00
```

`table_name = 'abha_profiles'`, `status = applied`, same batch as the patient row, `server_version = 1`. It is `TableSpec(19, AbhaProfile, pk_attr="abha_id", …)` in `TABLE_REGISTRY` and goes through `_apply_generic` like the other nineteen tables — there is no separate `SyncMixin` auto-log path. The `record_id` is the ABHA number itself (`91756140880001`) rather than a UUID, because `pk_attr="abha_id"`; that is probably what made it look absent when scanning for a UUID-shaped id. **Nothing to fix. Ticket withdrawn.**

### 2. `docs/db-schema-cheatsheet.md` — **file it; worth doing**

Agreed, and the ROI case is stronger than "saves guessing". Three separate audit rounds were spent on failed queries, and the failures were not random — they cluster on real traps a reader cannot guess:

- `sync_log` uses **`status`**, not `outcome`; **`applied_at`**, not `created_at`; **`table_name`**, not `table`.
- `sync_batches` PK is **`batch_id`**, not `id`; the counters are `record_count / applied / stale / conflicted / rejected` (there is no `total_entities`).
- `patients` has **no** `worker_id` and no `name` — attribution lives on `sync_batches.worker_id`, and `full_name` / `mobile_number` / `aadhaar_number` / `guardian_or_spouse_name` / `emergency_contact` are **`bytea` ciphertext**, queryable only via the `*_blind_idx` columns.

That last one is the reason this is a **safety** item and not a convenience item: someone who does not know those columns are encrypted will eventually try to `SELECT full_name` in an incident, get bytes, and either reach for the decryption path in a hurry or paste ciphertext somewhere it should not go. One page, one paragraph per table, stating the timestamp column, the PK, the encrypted columns, and the blind-index equivalents. **Small, and it belongs in `docs/` where the retention and sync design notes already live.**

### 3. New: define the delivery metric off `CaseStatus`, not `encounters`

Surfaced by Part 0(a). If "encounters" is intended to mean "consultation completed / data reached the kernel", today's `encounters` table over-counts — it is stamped when the Compounder screen opens, not when the kernel returns. Any facility/district reporting should be defined over `case_records.status` (and `kernel_reports`), which already carries that distinction and already syncs. **Worth naming before any dashboard is built on top of `encounters`, because the contamination would be retroactive and unfilterable.**

---

## Discipline note

No code written. No branch created. No commits. No file touched outside this memo. `.env`, `docker-compose.yml`, and credentials files untouched. No PHI read — the encrypted `patients` columns were deliberately excluded from every query in both memos.
