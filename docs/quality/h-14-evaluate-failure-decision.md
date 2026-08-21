# H-14: persisted evaluate-failure signal, design decision

Status: PROPOSED, awaiting operator approval. No code written.
Date: 2026-08-20
Base: `master` @ 2e63cd7 (PR #12 merged, so H-09's `InferenceSource.UNAVAILABLE` is present)
Branch: `fix/h-14-evaluate-failure-signal`

## Problem restated against the current tree

`GenerateEvaluateReportUseCase.invoke()` catches the failure, logs it, and returns
`Result.failure(e)`. It writes no Room row. `EvaluateReportRepositoryImpl.getForCase()`
therefore returns `null` for three distinct states:

1. evaluate has not run yet
2. evaluate ran and legitimately produced nothing
3. evaluate ran and failed

`AuditAction.EVALUATE_RESPONSE_FAILED` records the truth, but audit is write-only here; no
read-back surface consults it. `ReportCanvasRenderer.kt:168` (`report.evaluateOutput?.let`) and
`PatientSummaryViewModel.kt:162` both silently omit the section on `null`.

## Facts established by investigation

| Fact | Evidence |
|---|---|
| Android DB version is 14; migrations are strictly sequential to `MIGRATION_13_14` | `AppDatabase.kt:72`, `Migrations.kt:357` |
| Next migration is therefore `MIGRATION_14_15`, version 14 to 15 | observed convention, no gaps, no reuse |
| `EvaluateReportEntity` has no clinical status column | `EvaluateReportEntity.kt:13-29` |
| Its only state column is `syncState`, whose four values are all sync-lifecycle, not clinical | `SyncState.kt:7-22` |
| Any written row defaults to `syncState = PENDING` | `EvaluateReportEntity.kt:19` |
| `getPendingForSync()` selects exactly `WHERE syncState = 'PENDING'` | `EvaluateReportDao.kt:28` |
| The outbox pushes every such row unconditionally as `op = "upsert"` | `RoomSyncOutboxRepository.kt:71`, `SyncRecordMappers.kt:186-193` |
| `observeFailedSyncCount()` counts `syncState = 'FAILED'` and feeds a UI badge | `EvaluateReportDao.kt:39`, `RoomSyncOutboxRepository.kt:116` |
| Backend derives writable columns from the SQLAlchemy model; unknown wire fields are rejected as SAMD-SYNC-6003 | `services/sync.py:278-280`, `_attr_map` at `:186-191` |
| Backend `evaluate_reports.payload_json` is JSONB and `nullable=False`; it accepts any JSON shape | `models/kernel.py:99` |
| Local-only Room tables are an established pattern (`DoctorEntity` is absent from `TABLE_REGISTRY`) | `AppDatabase.kt:67`, `services/sync.py:150-176` |
| Local-only columns are also established (`ailments.audio_local_uri` is explicitly forbidden on the wire) | `services/sync.py:13-15` |

### Blocking sub-finding: H-09 shipped a latent sync defect

This is not H-14, but it is the direct precedent H-14 is asked to mirror, and it is now on
`master`.

`SyncRecordMappers.kt:182` puts `inferenceSource = inferenceSource.name` on the wire for
`kernel_reports`. The backend enum has only two members, `REAL_INFERENCE` and `MOCK_FALLBACK`
(`models/enums.py:207-214`), and alembic `0002_clinical_tables.py:1034` pins a CHECK constraint
`inference_source IN ('REAL_INFERENCE', 'MOCK_FALLBACK')`. No later migration widens it.

Per `services/sync.py:9-12`, a bad enum value surfaces as `IntegrityError` under the record's
savepoint and becomes a generic SAMD-SYNC-6003 rejection. Android maps `rejected` to
`SyncState.FAILED`, which by design stops the outbox retrying that row forever.

Net effect: every kernel report stamped `UNAVAILABLE` is permanently rejected by the backend,
never syncs, and increments the failed-sync badge. The H-09 marker works on-device and is lost
server-side. Recommend fixing this as a sibling task (backend enum member plus an alembic
migration widening the CHECK). It also sharpens the H-14 lesson: putting a new state value on
the wire is what costs a backend change, not adding the column itself.

## Options

### Option 1: no migration, marker inside `payloadJson`

`payloadJson` is an opaque Gson blob in a TEXT column, so adding a nullable `failure` field to
the private `EvaluateReportPayload` data class needs no schema change at all.

- Migration required: N
- Sync-push impact: BAD. The row defaults to `PENDING`, enters the outbox, and is pushed as a
  real `evaluate_reports` upsert. The backend accepts it because `payload_json` is
  shape-agnostic JSONB. A failure is stored server-side as a genuine evaluate report.
- Audit impact: none
- Backend schema impact: none
- Could a failure be pushed as real: YES, by default. Preventing it means filtering the outbox
  on a discriminator buried inside a JSON string, which is unindexable and fragile.

Rejected. The zero-migration saving is paid for with a JSON-sniffing DAO predicate guarding a
patient-safety invariant.

### Option 1b: no migration, park the row in a non-PENDING `syncState`

Write the failure row as `SYNCED` or `FAILED` so `getPendingForSync()` skips it.

- `SYNCED` asserts the server acknowledged a row it never received. That is a false entry in
  the sync ledger and is exactly the traceability corruption this decision exists to avoid.
- `FAILED` means "server rejected this record" and would inflate `observeFailedSyncCount()`,
  showing operators a sync-health alarm for a clinical event.

Rejected outright. A sync-lifecycle column must never carry clinical meaning.

### Option 2: new nullable column on `EvaluateReportEntity` (RECOMMENDED)

Add `failureCode: String?`, null meaning a real report and non-null meaning the evaluate call
failed.

- Migration required: Y, `MIGRATION_14_15`, DB version 14 to 15, one
  `ALTER TABLE evaluate_reports ADD COLUMN failureCode TEXT`. Validated androidTest-only under
  SQLCipher, matching `MigrationTest13To14`.
- Sync-push impact: `getPendingForSync()` becomes
  `WHERE syncState = 'PENDING' AND failureCode IS NULL`. One predicate, indexable, no JSON
  parsing. Failure rows structurally never enter the outbox.
- Backend schema impact: NONE, because `failureCode` is deliberately kept out of
  `EvaluateReportSyncPayloadDto`. It never crosses the wire, so the unknown-field rejection at
  `services/sync.py:278` is never triggered. This is the precise lesson from the H-09 defect
  above.
- Audit impact: none. `EVALUATE_RESPONSE_FAILED` continues unchanged.
- Could a failure be pushed as real: NO. Blocked at the outbox query, one layer below any
  caller.

Two consequential details the implementation must handle:

1. `payloadJson` is NOT NULL. A failure row needs a minimal sentinel (`"{}"`).
   `getForCase()` must branch on `failureCode` *before* Gson-deserializing, so the clinical
   tree is never parsed for a failure row.
2. `EvaluateReportOutput` has non-null `diagnosticSummary`, `nlemTreatment`, and
   `safetyAndTriage`. A failure must never be expressed as an `EvaluateReportOutput` with faked
   sub-objects. Add a distinct read path (a sealed result, or a separate
   `getFailureForCase()`), so the type system keeps a failure from ever being mistaken for a
   clinical result.

Retry needs no extra work: the existing `upsert` is `REPLACE` on the same per-case row, so a
successful retry naturally overwrites the failure row and clears `failureCode`.

### Option 3: separate signal outside the evaluate table

Two sub-variants:

- Column on `CaseRecordEntity`. Worse: `case_records` is itself synced (`TABLE_REGISTRY` rank
  12), so it raises the identical wire question, and it pollutes the case record with
  per-endpoint failure detail.
- New local-only table `evaluate_failures`, absent from `TABLE_REGISTRY` (precedent:
  `DoctorEntity`). This gives the strongest isolation, since being unsyncable is structural
  rather than a query predicate.

- Migration required: Y, `MIGRATION_14_15`, `CREATE TABLE`
- Sync-push impact: none, structurally unpushable
- Backend schema impact: none
- Audit impact: none
- Could a failure be pushed as real: NO

Rejected on cost, not on safety. It adds a table, a DAO, and a second repository read on every
report and summary render, and it splits "this case failed" and "this case succeeded" across two
rows that can disagree. A retry that writes a real report leaves a stale failure row behind
unless every write path explicitly deletes it. That is a new correctness hazard traded for an
isolation guarantee Option 2 already achieves at the DAO layer.

## Recommendation

**Option 2.** It is the smallest change that makes failure distinguishable: one nullable column,
one DAO predicate, zero backend and zero wire impact. It keeps one row per case as the single
source of truth, so retry semantics fall out of the existing `REPLACE` upsert for free, and the
"never pushable" guarantee sits in the outbox query rather than in caller discipline.

Scope once approved (Sonnet 5):

1. `MIGRATION_14_15` plus DB version bump to 15, and `MigrationTest14To15` (androidTest, SQLCipher)
2. `failureCode` on `EvaluateReportEntity`; `getPendingForSync()` predicate updated
3. `GenerateEvaluateReportUseCase` writes a failure row in its existing `catch` block
4. A failure-aware read path so `EvaluateReportOutput` is never fabricated
5. `ReportCanvasRenderer` and `PatientSummaryScreen` render the failure the way
   `assessmentMarkerLabel()` (`ReportCanvasRenderer.kt:47-50`) renders `UNAVAILABLE`
6. A test asserting a failure row is absent from `getPendingForSync()`, per the CLAUDE.md rule
   that persistence claims are asserted against the DB row, not the return value

Open sibling task, recommend scheduling with or before the above: widen the backend
`InferenceSource` enum and its CHECK constraint so H-09's `UNAVAILABLE` rows can sync at all.
