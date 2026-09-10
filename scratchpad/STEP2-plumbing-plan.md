# STEP 2 PLUMBING PLAN — Option 4 (Patients-tab `LEFT JOIN`)

- **Date:** 2026-08-28
- **Branch:** `master` (read-only; no code, no branch, no commits)
- **Predecessors:** `AUDIT-patient-registration-sync-gap.md` (diagnosis D) → `AUDIT2-encounter-semantics-and-fix-scope.md` (Option 4 approved)
- **Purpose:** pin the three implementation decisions so Sonnet builds one thing, once.

---

## (a) DAO query pattern — **PICK: single `LEFT JOIN` + `GROUP BY` + `HAVING COALESCE`, projecting an explicit nullable `lastSeenAt`**

### The shape

One new query on `PatientDao`, alongside (never replacing) `observePatientsWithEncounterBetween`. Returning a small projection row — **not** `PatientEntity` — because the UI must distinguish "seen" from "registered, not yet seen":

```
SELECT p.*, MAX(e.startedAt) AS lastSeenAt
FROM patients p
LEFT JOIN encounters e ON e.patientId = p.id
GROUP BY p.id
HAVING COALESCE(MAX(e.startedAt), p.createdAt) >= :startMillis
   AND COALESCE(MAX(e.startedAt), p.createdAt) <  :endMillis
ORDER BY COALESCE(MAX(e.startedAt), p.createdAt) DESC
```

`lastSeenAt` comes back **null** exactly for the registered-never-seen case. That null is the signal the roster row renders on, and it is the same null the window falls back through. One source of truth for both.

### Why not the other two

**Two-query merge in the ViewModel — reject.** The "registered, no encounter" half is an *anti*-join (`NOT EXISTS (SELECT 1 FROM encounters …)`), which is a harder query than the `LEFT JOIN` it is trying to avoid. It then duplicates the 7-day window logic in Kotlin, does dedupe and ordering in memory across two `Flow`s, and issues two DB round trips per emission. Two windows that must agree is a bug waiting to happen the first time someone changes one. It also forecloses pagination — an in-memory merge cannot be cursored.

**Room `@DatabaseView` / CTE — reject *for now*.** A view's payoff is reuse, and there is exactly **one** consumer: the Patients tab. Home deliberately keeps its own untouched `INNER JOIN` query (that separation is the whole point of Option 4). So a view is an abstraction with a single implementation, and it costs a Room version bump (schema identity hash changes) plus a migration, for zero reuse today. **Revisit if and when a second reader appears** — at two consumers the view becomes correct.

**The chosen shape needs no migration, no schema object, and no change to any existing query.**

### The trap Sonnet must not fall into — state this in the PR

`LEFT JOIN` + a `WHERE` clause on the right-hand table's column **silently degrades back into an `INNER JOIN`.** Writing:

```
LEFT JOIN encounters e … WHERE e.startedAt >= :startMillis
```

drops every null-extended row, and the query behaves *identically to today's* — the fix compiles, runs, passes a careless test, and fixes nothing. The window predicate **must** live in `HAVING`, over the `COALESCE`. This is the single highest-risk line in the change.

### Two secondary notes

- **Order stability.** `ORDER BY COALESCE(...) DESC` alone is not a total order when two rows share a timestamp. Today's query has the same latent issue; the backend roster already solved it (`order_by(last_encounter.desc(), Patient.id.asc())` in `services/patient.py`). Add `, p.id ASC` as the tiebreaker and match the server.
- **Index ceiling.** `patients.createdAt` is unindexed on the device; `encounters` has `Index("patientId")`. The `HAVING` forces a scan of `patients`. At single-PHC device volumes (hundreds to low thousands of rows) that is not worth an index. Worth one line in the KDoc naming the ceiling, not worth an index now.

### Also required at the query site (carried from AUDIT2)

KDoc in the house style of `CaseRecordDao.observeDoctorTrackerRows` — naming the purpose-scoped exception **and** the precondition that expires:

> safe while sync is push-only (`RemoteMediator` unbuilt, `docs/sync-design.md:89`), because every `patients` row on this device was authored on this device; when pull lands, this must additionally filter to device-authored rows.

---

## (b) Discard-cycle model — **PICK: no-discard. And the `registration_state` substate is not needed either.**

Candidate 3, minus its column.

### No-discard — settled, not re-litigated

`docs/data-retention.md:26` (`patients` = *"Mutable, no-delete"*), no `@Delete` on `PatientDao`, 13 × `ON DELETE RESTRICT` server-side, and the repudiation argument from AUDIT2 §0(b). **Candidates 1 and 2 both hand a worker an erase primitive over a hash-chained clinical record. Both rejected.** Soft delete (candidate 2) is the less-bad of the two and remains the right *shape* if a supervisor-gated void is ever wanted — but it is not wanted by this bug, and building it now is a schema change on both sides plus an error-contract amendment for a workflow nobody has asked for.

### Why the substate column is also unnecessary

Both states in question are **already representable today**:

| State | How it is already represented | Surfaced by |
|---|---|---|
| "Registered, not yet seen" | patient row with no `encounters` row | `lastSeenAt IS NULL` from (a) — no new column |
| "Rejected by server as duplicate ABHA" | `patients.syncState = FAILED` + `patients.syncErrorCode` | already written by `PatientDao.applySyncResult` |

Adding `registration_state` would encode, in a new synced column, information the existing rows already carry. That is the same mistake as the `Encounter.kind` column AUDIT2 rejected: **the model is right, the read path was wrong.** Option 4 fixes the read path; the state needs no name because it is the absence of a row, and absence is already queryable.

### What actually happens on ABHA conflict — corrected

The premise "409 or similar" does not hold on the path the device uses. Traced end to end:

1. **The device never calls the REST endpoint.** `ErrorCode.PAT_DUPLICATE_ABHA` = `SAMD-PAT-3004` → HTTP **409** (`errors.py:54`, `:123`) — but that is raised by `_assert_abha_unclaimed`, which is called only from `create_patient` (`services/patient.py:145`) and the PATCH path (`:174-177`). The device syncs; it does not POST patients.
2. **`_apply_generic` does not call `_assert_abha_unclaimed` at all.** Verified by reading it end to end (`sync.py:252-345`): forbidden-field check, unknown-field check, facility check, version/staleness checks, attribute coercion, `session.add`, `apply_blind_indexes`, `flush`. No ABHA guard anywhere on the sync path.
3. So the duplicate reaches `flush()` and trips the **`ix_patients_abha_number` UNIQUE index** → `IntegrityError`, sqlstate **23505**.
4. `_apply_one` catches it: `except (IntegrityError, DataError) as exc: return _reject(table, safe_id, ErrorCode.SYNC_RECORD_INVALID, _constraint_message(exc))` (`sync.py:509-510`).
5. The device therefore receives, **inside an HTTP 200 batch response**, a per-record:
   `{"status": "rejected", "code": "SAMD-SYNC-6003", "message": "this record already exists with different data."}`
   — `SYNC_RECORD_INVALID` (`errors.py:80`), *not* `SAMD-PAT-3004`. **There is no 409 on this path, and no HTTP error at all: the batch succeeds.**
6. `SyncAckMapping.kt` maps `"rejected"` → `SyncState.FAILED`, and its KDoc states the locked Phase 6b decision: *`rejected` means "malformed, stop retrying forever"*.

**The consequence, stated plainly:** a duplicate-ABHA re-registration lands as a permanently-`FAILED`, never-retried local row, tagged with a generic malformed-record code the device cannot distinguish from a genuine schema violation. The worker sees the red `observeFailedSyncCount()` badge and nothing else. No remedy exists on the device, and none can, because the blocking condition lives on the server.

**One thing that is *not* broken, and is worth recording as verified:** each record applies under its own `session.begin_nested()` savepoint (`sync.py:487`), so the `IntegrityError` rolls back only that record. The other 11 records in the batch, the `sync_batches` row, and every `sync_log` row still commit. `push()`'s docstring states this as load-bearing, and AUDIT 1's live data confirms it. **This is the CLAUDE.md `_fail()`-rollback trap being correctly avoided, not another instance of it.**

### What follows for STEP 2

**Nothing.** Option 4 removes the *motivation* for the discard cycle — a worker who can find the patient does not re-register them. Since no discard path is being built, no re-registration cycle is being introduced, and this whole failure mode stays hypothetical.

Two prevention items are worth filing, neither in STEP 2's scope (see Follow-ups 2 and 3): a device-side duplicate-ABHA check before insert (small, device-only, removes the collision at source), and making the rejection distinguishable server-side (backend change, out of the approved device-only scope).

---

## (c) Aggregate / `INNER JOIN` survey across the DAO layer — **survey ran; the premise does not hold**

Ran the full sweep over `app/src/main/java/com/example/samdapp` rather than assuming the count.

**There is exactly ONE `INNER JOIN` in the entire Android codebase** — `PatientDao.kt:47`, the roster query already under discussion. Not 25. One.

**Zero occurrences of `MIN(`** anywhere in the DAO layer.

Full aggregate inventory, and why none of the others can carry the nullability trap:

| Pattern | Count | Nullability trap possible? |
|---|---|---|
| `MAX(e.startedAt)` in `PatientDao.kt:50` (with `GROUP BY p.id`) | 1 | Yes — **this is the one**, and it is the query being changed |
| `COUNT(*) … WHERE syncState = 'FAILED'` (one per syncable table) | ~20 | **No.** Single-table, no join, no outer side |
| `COUNT(*)` on `case_records` by status (`:71`, `:102`) | 2 | **No.** Single-table |
| `COALESCE(:serverVersion, serverVersion)` in every `applySyncResult` | ~20 | **No.** Scalar coalesce in an `UPDATE … SET`, no join, no aggregate |
| `MIN(` | 0 | — |

Every other encounter access in the DAO layer is one of two safe shapes:

- **Scalar equality filter** — `WHERE encounterId = :encounterId` (`AilmentDao:35`, `ObservationDao:31`, `CaseRecordDao:83`, `ConsultationDao:43`). Single-encounter scope; the encounter id is already known, so no join and no outer side exists to be null.
- **Already `LEFT JOIN`** — `CaseRecordDao:116` (`LEFT JOIN consultations`), `EncounterDao:42-43` (`LEFT JOIN consultations`, `LEFT JOIN case_records`). These already handle the nullable side deliberately.

**Conclusion: no sibling sites share the trap, because no sibling sites exist.** There is no remediation work hiding behind this survey, and nothing to defer into a fix branch.

---

## Follow-up tickets (filed, deferred — none in STEP 2)

**1. Guard against a second `INNER JOIN encounters` appearing.** *(This is the encounter-join survey ticket, filed as requested — reframed to match what the survey actually found.)* The sweep found one `INNER JOIN` in the codebase, `PatientDao.kt:47`, and zero `MIN(` aggregates, so there is no backlog of sibling sites to remediate. The residual risk is prospective rather than existing: the day-scoping rule is currently upheld by convention plus one instrumented test, and nothing mechanically stops a future DAO from adding a second encounter-joined patient query that quietly re-narrows or re-widens the roster boundary. The cheap guard is a lint or CI grep asserting that `INNER JOIN encounters` appears exactly once in `data/local/dao/`, with the allowed site named — turning REQ-ROS-02 from a documented intention into a mechanically enforced one. Small, deferred, and worth doing before the next roster-touching feature rather than after it.

**2. Device-side duplicate-ABHA check before registration.** The register flow can query the local `patients` table by `abhaNumber` before insert and route the worker to the existing patient instead of creating a colliding row. Device-only, small, and it removes the collision at source rather than reporting it afterwards. Deliberately kept out of STEP 2 to hold that PR to the approved scope; a natural fast-follow.

**3. Make the duplicate-ABHA sync rejection distinguishable.** Today it arrives as generic `SAMD-SYNC-6003` "this record already exists with different data." Either call `_assert_abha_unclaimed` from `_apply_generic` so the sync path returns `SAMD-PAT-3004` like the REST path does, or map sqlstate 23505 on `patients` to a specific code. **Backend change — outside the approved device-only scope for STEP 2.** Worth doing before anyone builds worker-facing sync-error messaging, since a generic code cannot be turned into a useful message.

**4. Carried from AUDIT2, still open:** `docs/db-schema-cheatsheet.md`; delivery metrics defined off `CaseStatus` rather than `encounters`; server-side invisibility of encounter-less patients to be named on the sync-roadmap Phase 3 (pull) ticket.

---

## STEP 2 scope, final

1. New `LEFT JOIN` query on `PatientDao` per (a), projecting nullable `lastSeenAt`, window predicate in `HAVING COALESCE`, tiebreak `p.id ASC`, KDoc carrying the push-only precondition.
2. `PatientRepository` + `GetRecentPatientsUseCase` + `PatientsViewModel` wired to it; search filter unchanged.
3. Patients-tab empty-state copy corrected; roster row visually marks `lastSeenAt == null` as "registered, not yet seen".
4. One new instrumented DAO test: encounter-less patient inside the window appears; outside the window does not; **and a patient with an encounter outside the window but registered inside it resolves through the `COALESCE`, not the `WHERE`** (this is the test that catches the degrade-to-`INNER-JOIN` trap).
5. `observePatientsWithEncounterBetween` and `TodaysPatientsDaoTest` **untouched and still green**.
6. REQ-ROS-02 clarifying note + one traceability-matrix row.

**Not in scope:** any discard/delete/void path; any new state column on `Patient` or `Encounter`; any backend change; any Room migration or `@DatabaseView`; the four follow-up tickets above.

---

## Discipline note

No code written. No branch created. No commits. No file touched outside this memo. `.env`, `docker-compose.yml`, and credentials files untouched. No PHI read.
