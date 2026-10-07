# PR 4 design addendum: model identity, derivation version and the sync cross-check

Drafted 2026-10-01 against master `b44d082`. Read-only design, no code. Builds on
`scratchpad/backend-truthfulness-memo.md` (sections 1, 2, 4 and 10) and the Phase 0 recon, under
operator rulings R1 to R6, which are final. Every repo claim is MEASURED (file:line or command)
unless marked INFERRED. Where the repo and a ruling disagree, it is listed in section 0 and not
built around silently.

## 0. Repo facts this addendum relies on, and two conflicts

| Fact | Evidence |
|---|---|
| Room is at v21; `22.json` does not exist; no remote branch sets `version = 22` | `AppDatabase.kt:75`; `app/schemas/.../21.json` is the newest; grep over every `origin/*` branch |
| Alembic head is `0008`; no branch has a `0009` | `alembic/versions/0008_slm_call_log.py:33`; `git ls-tree` over every `origin/*` branch |
| Device `kernel_reports` has no foreign keys, nothing references it, no views; one unique index `index_kernel_reports_caseRecordId` | `21.json` (22 entities scanned) |
| Backend rejects an unknown sync field as TERMINAL `SYNC_RECORD_INVALID` | `services/sync.py:347-360` |
| A missing NOT NULL column (23502) and a CHECK violation (23514) are TERMINAL | `services/sync.py:123`, `:149` |
| Device Gson does not serialize nulls, so a null field is omitted from the payload | `SyncGsonAdapters.kt:54-57` (no `serializeNulls()`) |
| `request_id` is `String(36)` on `kernel_call_log` and `kernel_assessments`, both indexed | `models/sync.py:130`, `:134`, `:197`, `:204` |
| The proxy mints or adopts a UUID4 and echoes it as `X-Request-ID` on every response | `middleware/request_id.py:3`, `:37`, `:45-47` |
| The device kernel call returns `Response<...>`, so headers are reachable | `data/remote/api/KernelApiService.kt:19-27` |
| `derive_assess` returns `urgency_level`, `risk_category`, `requires_human_verification`, `derivation_rule_version`, `unrecognised_fields` | `domain/kernel_derivation.py:106-124` |
| Rule version history: `HAN-07/08-v1` (`544ae27`), then `HAN-07/08-v2` (`56e05fd`, current) | `git log -L74,74:app/domain/kernel_derivation.py` |
| Insert-only precedent: `audit_events_reject_mutation()` with no-update and no-delete triggers | `alembic/versions/0001_initial_auth_audit_abha.py:32`, `:185-194`, downgrade `:246-248` |
| SQLCipher migration tests already exist on the `MigrationTestHelper` plus `SupportOpenHelperFactory` pattern | `MigrationTest20To21.kt:5`, `:30-33` |
| F6B-01 (FAILED rows surfaced, bounded retry, manual requeue) **has landed** | `ae4fe90` (2026-09-20) on master: `HomeScreen.kt`, `HomeViewModel.kt:116` `onSendFailedRecordAgain`; requeue SQL `KernelReportDao.kt:60-66` |

**Conflict 1 (R5 premise).** R5 says to record that the sync-taxonomy work moves to v23 and 0010.
The sync-taxonomy work the memo's D4 referred to has already landed and took **Room v21**
(`ae4fe90` and `19b1ee3`, both 2026-09-20, with `MigrationTest20To21`). No pending branch claims
v22 or 0009. PR 4 takes v22 and 0009 either way; what R5 should say about "the sync-taxonomy
work" needs the operator (open question Q1). Recorded here as: any **further** sync-taxonomy
schema work takes Room v23 and Alembic 0010.

**Conflict 2 (A-08).** No item labelled A-08 exists in the repo (grep over `scratchpad/`, `docs/`,
`backend/`, `app/`). The lesson is applied as the brief states it: no device-supplied free string
is stored verbatim. The nearest recorded instance is AUDIT4 section 1.4 (`PatientEntity.abhaNumber`,
a nullable free string). Open question Q2.

## Scope reminder (Phase 0 items 1 to 11, under the rulings)

R1 device rule-version constant plus shared fixture value; R2 no `version` alias; R3 null both
sentinels; R4 `kernel_reports` stays device-owned and the cross-check gets its own server-owned,
insert-only table; R5 Room v22 and Alembic 0009; R6 merge after the demo, backend deploys first.
Out of scope: `/evaluate` identity (memo PR 5), pin file and capture script (memo PR 6), the doc
rewrites of D-9 and D-10 (memo PR 8), the minimum-app-version gate (D1c, design note only, section I).

## A. The cross-check table: `kernel_derivation_checks`

Server-owned, insert-only, one row per accepted write of a `kernel_reports` record. Never synced,
never written to `kernel_reports` (R4).

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BigInteger`, `Identity(always=False)` | no | PK. Same shape as `kernel_call_log.id`; gives a deterministic tie-break |
| `kernel_report_id` | `String(36)` | no | FK `kernel_reports.id` `ON DELETE RESTRICT` |
| `facility_id` | `String` (as `SyncMixin.facility_id`) | no | FK `facilities.id` `ON DELETE RESTRICT`; copied from the report row, for scoped queries |
| `request_id` | `String(36)` | yes | The link the check used, exactly as stored on the report. NULL when the report carried none |
| `kernel_assessment_id` | `String(36)` | yes | FK `kernel_assessments.id` `ON DELETE RESTRICT`; the row the derivation was run against |
| `rederive_status` | `String(30)` | no | `MATCH`, `MISMATCH`, `NOT_CHECKED_NO_LINK`, `NOT_CHECKED_ERROR` (`enum_check`, constraint `ck_kernel_derivation_checks_rederive_status`) |
| `rule_version_used` | `String(40)` | no | The backend `DERIVATION_RULE_VERSION` at check time. Written on every row, including the NOT_CHECKED ones, so a reader always knows which rules were in force |
| `device_rule_version` | `String(40)` | yes | Snapshot of the report's `derivation_rule_version` at check time. The report is device-owned and can be re-synced; the snapshot keeps each check interpretable on its own |
| `mismatch_fields` | `ARRAY(Text)` | no | Server default `'{}'`. Field names only, from a closed set (below) |
| `created_at` | `DateTime(timezone=True)` | no | Server default `now()` |

**Indexes.**
- `ix_kernel_derivation_checks_report_created` on `(kernel_report_id, created_at, id)`: serves "latest check for this report" on the DOCTOR view.
- `ix_kernel_derivation_checks_facility_status` on `(facility_id, rederive_status)`: serves "how many mismatches does this facility have".

**CHECK constraints.**
- `ck_kernel_derivation_checks_mismatch_vocab`: `mismatch_fields <@ ARRAY['urgency_level','risk_category','required_human_verification']::text[]`.
- `ck_kernel_derivation_checks_mismatch_consistent`:
  `(rederive_status = 'MISMATCH') = (cardinality(mismatch_fields) > 0)`.
  A MISMATCH must name a field; nothing else may name one.
- `ck_kernel_derivation_checks_link_consistent`:
  `(rederive_status IN ('MATCH','MISMATCH')) = (kernel_assessment_id IS NOT NULL)`.
  A verdict exists only when there was a row to derive from.
- `ck_kernel_derivation_checks_request_id_format`: the same UUID4 pattern as section C, or NULL.

**Immutability.** Function `kernel_derivation_checks_reject_mutation()` plus triggers
`trg_kernel_derivation_checks_no_update` (BEFORE UPDATE) and `trg_kernel_derivation_checks_no_delete`
(BEFORE DELETE), copied from the `audit_events` precedent. The pre-pilot reset drops the database
(or uses `TRUNCATE`, which fires no row triggers), so the triggers do not obstruct D3.

**When a row is written.** After a `kernel_reports` record is accepted by the sync apply path
(insert or update), in the same request:

1. `request_id` on the report is NULL: write `NOT_CHECKED_NO_LINK`, `request_id` NULL. Never
   skipped silently.
2. `request_id` present, but no `kernel_assessments` row with that `request_id`,
   `endpoint = 'assess'` and the same `facility_id`: write `NOT_CHECKED_NO_LINK`, with
   `request_id` set. Reading the row distinguishes "no link sent" (request_id NULL) from "link
   sent, nothing to join" (request_id set, assessment NULL). A different facility's row is never
   joined.
3. Assessment found: run `derive_assess(raw_response)` and compare three pairs:
   report `urgency_level` vs derived `urgency_level`; report `risk_category` vs derived
   `risk_category`; report `required_human_verification` vs derived
   `requires_human_verification`. A derived `None` compared with a stored value is a mismatch on
   that field. All equal: `MATCH`. Otherwise `MISMATCH` with the differing names, sorted.
4. Any exception while deriving or comparing: `NOT_CHECKED_ERROR`. The exception is logged as a
   structured line carrying `kernel_report_id`, `request_id` and the exception class name only,
   never the message (a message can echo response content).

**The cross-check never blocks the clinical row.** The check row is written inside its own
`SAVEPOINT` (`session.begin_nested()`), separate from the record's own apply. If the check insert
itself fails, the savepoint rolls back, the report stays accepted, and a structured warning is
logged. Per the CLAUDE.md backend convention, the test for this asserts the persisted
`kernel_reports` row, not the HTTP ack.

**Idempotent replay.** A replayed batch is answered from the idempotency store
(`services/sync.py`, 24 hour store) without re-applying, so it writes no second check. A genuine
re-sync of the same report (new `server_version`) does write a second row. The table is history,
and the DOCTOR view reads the latest.

**`mismatch_fields`: names only, no values.** Justification:
- **No-PHI-in-logs rule.** Urgency, risk and verification are clinical classifications of an
  identified patient's case. Joined to `request_id`, they are patient-linked clinical data. This
  table is the one most likely to be queried for operations and dashboards and copied into logs,
  so it holds a closed, non-clinical vocabulary of field names and nothing else. The CHECK
  constraint makes that structural, not a convention.
- **Nothing is lost.** The device's values are on the `kernel_reports` row. The derived values
  are reproducible from `kernel_assessments.raw_response` under `rule_version_used`, which is a
  tagged version in git. Copying them here would duplicate clinical data under weaker access
  for no gain.

## B. DOCTOR API: superseded flag and the check result

Added to the `kernel_report` object of `GET /api/v1/encounters/{id}` (`api/v1/encounters.py`,
`_KERNEL_FIELDS` and `_serialise_bundle`). The `kernel_report: null` case is unchanged.

```json
"kernel_report": {
  "...existing fields...": "...",
  "derivation_rule_version": "HAN-07/08-v2",
  "derivation_rule_status": "CURRENT",
  "derivation_check": {
    "status": "MATCH",
    "rule_version_used": "HAN-07/08-v2",
    "mismatch_fields": [],
    "checked_at": "2026-10-01T09:30:00Z"
  },
  "derivation_ok": true
}
```

- **`derivation_rule_version`** (string or null): the stored value, as synced.
- **`derivation_rule_status`** (string, never null), computed on read:
  - `CURRENT`: equal to the backend `DERIVATION_RULE_VERSION`.
  - `SUPERSEDED`: a member of a new backend constant
    `SUPERSEDED_DERIVATION_RULE_VERSIONS = frozenset({"HAN-07/08-v1"})` in `kernel_derivation.py`.
    The next rule bump moves the old value into this set in the same commit.
  - `UNKNOWN`: NULL (every legacy row and every non-REAL row), or any value in neither set (for
    example a device ahead of the backend). **Null is never CURRENT.**
- **`derivation_check`** (object or null): the latest row for this report, ordered
  `created_at DESC, id DESC`. Null means no check exists, which is true of rows synced before
  0009. Null is never read as a match.
- **`derivation_ok`** (boolean, never null), fail closed:
  `derivation_rule_status == "CURRENT" AND derivation_check is not null AND derivation_check.status == "MATCH"`.
  Every other combination is `false`, including UNKNOWN, a missing check and every NOT_CHECKED
  status. A client that wants one signal reads this field, and the safe default for anything it
  does not understand is "attention needed".

The query adds one indexed lookup per bundle on `ix_kernel_derivation_checks_report_created`,
inside the same deterministic-order helper pattern that PR 2 introduced.

## C. `request_id` validation (and the other device-supplied strings)

The lesson applied: no device-supplied free string is stored verbatim. Every string this PR adds
or keeps on the wire gets a closed format, checked on the device before storing, at backend
ingest, and (for the new columns) by a database CHECK.

| Field | Pattern | Device on a bad value | Backend ingest on a bad value |
|---|---|---|---|
| `request_id` | `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` (UUID4, lowercase) | store NULL, and record `KERNEL_UNRECOGNISED_OUTPUT` with field `request_id` | reject TERMINAL `SYNC_RECORD_INVALID`, message `request_id: malformed.` |
| `derivation_rule_version` | `^HAN-07/08-v[0-9]{1,3}$` | cannot happen: it is the device constant (R1) | reject TERMINAL, `derivation_rule_version: malformed.` |
| `model_version` | `^[A-Za-z0-9._:+-]{1,80}$` | store NULL, and record `KERNEL_UNRECOGNISED_OUTPUT` with field `model_version` (R2) | reject TERMINAL, `model_version: malformed.` |

**Device detail (`RetrofitKernelSource`).**
- It reads `response.headers()["X-Request-ID"]`, matches case-insensitively, and stores the
  **lowercased** canonical form.
- It sets `requestId` only on REAL_INFERENCE results. MOCK_FALLBACK and UNAVAILABLE rows store
  NULL, because no derivation ran for them. See section A rule 1 and open question Q3.
- A missing header on a REAL_INFERENCE result is treated like a malformed one: NULL plus the event.

**Event payloads.** `KERNEL_UNRECOGNISED_OUTPUT` for `request_id` and `model_version` carries the
field name only, never the raw value. The existing event already logs the raw `triage_urgency`
and `condition_tier` tokens. That is a classifier-supplied value stored verbatim, outside this
PR's scope, and is recorded as open question Q4 rather than changed here.

**Backend detail.**
- The format checks run in the per-table rules block of `services/sync.py`, before the generic
  unknown-field check. The messages name the field and never echo the value.
- The backend **rejects** rather than normalizes, because it does not rewrite device-owned data
  (D-9 and D-10). An uppercase `request_id` is therefore rejected: the device always lowercases,
  so an uppercase value indicates a defect, not a variant.
- The database CHECKs on `kernel_reports.request_id`, `kernel_reports.derivation_rule_version`
  and the new `model_sha256` columns are defence in depth. Their 23514 already maps to TERMINAL
  (`services/sync.py:149`).
- `model_version` gets no database CHECK, only the ingest check, because existing pre-pilot rows
  are not guaranteed to comply and a migration must not fail on them (see E).

**Consequence of a TERMINAL reject.** The row goes FAILED on the device and appears in the F6B-01
failed-records list (section D). It is never silently dropped.

## D. Compatibility matrix

| | Old backend (0008) | New backend (0009) |
|---|---|---|
| **Old device (Room 21)** | Today's behaviour. Rows sync; `model_version` holds a classifier string or a sentinel. **No FAILED.** | Accepted. The three new fields are absent (nullable, so not required), `model_version` is a string, which is still accepted. Each row gets a check row with `NOT_CHECKED_NO_LINK` (no `request_id`); the DOCTOR view shows `derivation_rule_status: UNKNOWN`, `derivation_ok: false`. **No FAILED.** |
| **New device (Room 22)** | **Every new `kernel_reports` row is rejected TERMINAL**: `derivation_rule_version` (always present on REAL rows) and `request_id` are unknown fields (`services/sync.py:347-360`); a null `model_version` is omitted by Gson, so it is also a missing required column (23502). Rows go **FAILED**. MOCK and UNAVAILABLE rows with all three fields null are rejected only when `model_version` is null, which after R3 is always. So **all** kernel_reports rows fail. | Target state. **No FAILED.** |

**Is the failure visible today? Yes.** F6B-01 has landed (`ae4fe90`): Home shows the failed count
and a failed-records list, and "Send again" requeues a FAILED kernel report
(`HomeViewModel.kt:116`, `KernelReportDao.kt:60-66`, guarded on `syncState = 'FAILED'`). The
local row is kept; nothing is lost.

**Exact consequence of the bad cell (new device, old backend).**
- The physician sees no AI assessment for those cases on the DOCTOR view until each row is resent.
- The worker sees failed records on Home.
- After the backend reaches 0009, "Send again" on each row succeeds.
- The rest of the case (patient, encounter, case record, observations) syncs normally, because
  `kernel_reports` has no dependants.

**Mitigation.**
1. R6: the backend deploys first. Release checklist gate: `alembic current` on the target backend
   reports `0009` before any APK built from PR 4 is distributed.
2. The TERMINAL comment at `services/sync.py:349-353` already names this exact skew. It is
   recorded, not changed. The operator ruled TERMINAL for unknown fields, and F6B-01 makes it
   recoverable.
3. Not proposed: a capability handshake. It would be new contract surface for a skew the deploy
   order already prevents.

**A related skew, device versus classifier.** If the classifier is rolled back to
`samd-classifier:pre-5e1ca00`, which emits only `version`, every new row gets `model_version`
NULL plus one `KERNEL_UNRECOGNISED_OUTPUT` per call (R2). That is honest and expected, not a
failure.

## E. Legacy `"remote-kernel"` and `"unavailable"` from old devices

**Decision: accept and store verbatim. No ingest rewrite, no migration, on either side.**

- **Accept**, because both values pass the `model_version` format check, and rejecting them would
  turn every old-device kernel report into a FAILED row for a value the device was built to send.
- **Verbatim**, because `kernel_reports` is device-owned (R4, D-9, D-10). The server does not
  rewrite device data, and the idempotency store answers replays with what was applied.
- **No cleansing**, because pre-pilot data is declared non-clinical and is reset before first
  deployment (D3), and after R6 no old device survives into the pilot.
- **No special-casing on read.** The DOCTOR view shows the stored string. No client renders
  `model_version` today (Phase 0, item 4c), and these rows are already flagged
  `derivation_ok: false` (UNKNOWN rule status, NO_LINK check).
- **Room `MIGRATION_21_22` copies the sentinel values unchanged** (section F). New rows written
  after the upgrade carry NULL (R3).

## F. `MIGRATION_21_22` (device, Room 21 to 22)

**Schema change, `kernel_reports` only.**
- `modelVersion` goes from `TEXT NOT NULL` to `TEXT` (nullable).
- New nullable columns: `requestId TEXT`, `modelCalibrated INTEGER` (Boolean), `derivationRuleVersion TEXT`.
- The `KernelReportEntity` fields become `String?`, `String?`, `Boolean?` and `String?`.

**Why a rebuild.** SQLite cannot relax NOT NULL in place. The three new columns could be added
with `ALTER TABLE ADD COLUMN`, but the rebuild is needed anyway, so all four changes go through it.

**Steps.** These run inside Room's migration transaction. There is no `PRAGMA foreign_keys` dance,
because nothing references `kernel_reports` and it references nothing (section 0).
1. `CREATE TABLE kernel_reports_new (...)`, using the exported `22.json` `createSql` with the table
   name substituted. Copying Room's own DDL avoids hand-typed drift that `runMigrationsAndValidate`
   would reject.
2. `INSERT INTO kernel_reports_new (<all 28 v21 columns>) SELECT <same 28> FROM kernel_reports`.
   The 3 new columns take NULL. No value is rewritten: the sentinels stay as they are (E).
3. `DROP TABLE kernel_reports`.
4. `ALTER TABLE kernel_reports_new RENAME TO kernel_reports`.
5. `CREATE UNIQUE INDEX IF NOT EXISTS index_kernel_reports_caseRecordId ON kernel_reports (caseRecordId)`.

`AppDatabase.kt:75` becomes `version = 22`, `MIGRATION_21_22` is registered, and `22.json` is
exported and committed.

**Migration test `MigrationTest21To22`** (instrumented, SQLCipher, on the `MigrationTest20To21`
pattern).
- **Affected table:** `kernel_reports` is the only table rebuilt. It is seeded with five v21 rows:
  1. REAL_INFERENCE with a real classifier version.
  2. REAL_INFERENCE with `"remote-kernel"`.
  3. UNAVAILABLE with `"unavailable"` and a `failureCode`.
  4. MOCK_FALLBACK.
  5. A `syncState = 'FAILED'` row with `syncErrorCode`, `syncErrorMessage`, `syncAttemptCount > 0`
     and `lastSyncAttemptAt` set.
  The nullable columns are a mix of NULL and set values.
- **Control table:** one `case_records` row is seeded, to prove the rebuild touches nothing else.
- **Assertions:**
  - `runMigrationsAndValidate(22, MIGRATION_21_22)` passes.
  - Every one of the 28 old columns is value-equal per row, including both sentinels verbatim.
  - The 3 new columns are NULL.
  - A NULL `modelVersion` insert now succeeds.
  - A second row with an existing `caseRecordId` fails, so the unique index survived.
  - `PRAGMA integrity_check` returns `ok`.
  - The `case_records` control row is unchanged.
- **Full chain:** `MigrationTest.kt` (the all-migrations chain) is extended to v22.

**Must run on the physical iQOO I2302**, not the emulator: `MigrationTest21To22` against a
SQLCipher-encrypted database. The shipped ABI is `arm64-v8a` and the emulator's is `x86_64`, so the
native SQLCipher library exercised differs. Per the session device rule this runs with install
plus `am instrument`, **never** a Gradle connected task on the phone, which would uninstall the app
and its data. `MigrationTestHelper` uses its own test database name, never `samd_app.db`:

```
./gradlew :app:installDevDebug :app:installDevDebugAndroidTest
adb -s 10BE3A09C700046 shell am instrument -w \
  -e class com.example.samdapp.data.local.MigrationTest21To22 \
  com.example.samdapp.dev.test/androidx.test.runner.AndroidJUnitRunner
```

## G. Alembic `0009_model_identity_and_derivation_checks`

`revision = "0009"`, `down_revision = "0008"`.

**upgrade(), in order**
1. `op.alter_column("kernel_reports", "model_version", existing_type=sa.String(80), nullable=True)`.
2. Add three columns to `kernel_reports`:
   - `sa.Column("model_calibrated", sa.Boolean(), nullable=True)`
   - `sa.Column("request_id", sa.String(36), nullable=True)`
   - `sa.Column("derivation_rule_version", sa.String(40), nullable=True)`
3. Add CHECKs on `kernel_reports`. Both columns are new and empty, so neither can fail on existing rows:
   - `ck_kernel_reports_request_id_format`: `request_id IS NULL OR request_id ~ '<UUID4 pattern>'`
   - `ck_kernel_reports_derivation_rule_version_format`: `derivation_rule_version IS NULL OR derivation_rule_version ~ '^HAN-07/08-v[0-9]{1,3}$'`
4. `op.create_index("ix_kernel_reports_request_id", "kernel_reports", ["request_id"])`.
5. `kernel_call_log`: add `model_sha256 String(64)` nullable, plus
   `ck_kernel_call_log_model_sha256_format`: `model_sha256 IS NULL OR model_sha256 ~ '^[0-9a-f]{64}$'`.
6. `kernel_assessments`: add `model_sha256 String(64)` nullable with
   `ck_kernel_assessments_model_sha256_format` (the same pattern), and `model_calibrated Boolean` nullable.
7. `op.create_table("kernel_derivation_checks", ...)` exactly as section A, with its FKs, CHECKs
   and both indexes.
8. `CREATE OR REPLACE FUNCTION kernel_derivation_checks_reject_mutation()`, then
   `trg_kernel_derivation_checks_no_update` and `trg_kernel_derivation_checks_no_delete`.

**downgrade(), the exact reverse**
1. `DROP TRIGGER IF EXISTS` for both triggers, then `DROP FUNCTION IF EXISTS kernel_derivation_checks_reject_mutation()`.
2. `op.drop_table("kernel_derivation_checks")`. This is destructive for check history, which is
   acceptable pre-pilot and stated here.
3. `kernel_assessments`: drop `model_calibrated`, the CHECK, then `model_sha256`.
4. `kernel_call_log`: drop the CHECK, then `model_sha256`.
5. `kernel_reports`: drop `ix_kernel_reports_request_id`, both CHECKs, then the columns
   `derivation_rule_version`, `request_id` and `model_calibrated`.
6. **Refuse before re-tightening.** If `SELECT count(*) FROM kernel_reports WHERE model_version IS NULL`
   is above 0, raise `RuntimeError("0009 downgrade refused: N kernel_reports rows have model_version NULL")`.
   No sentinel is back-filled, because writing `"unknown"` would reintroduce the fabricated
   attribution this PR removes.
7. `op.alter_column("kernel_reports", "model_version", existing_type=sa.String(80), nullable=False)`.

The ORM models change to match: `models/kernel.py` (`KernelReport`) and `models/sync.py`
(`KernelCallLog`, `KernelAssessment`, new `KernelDerivationCheck`), with the new enum in
`models/enums.py`.

**Demo freeze.** Migration tests run against the existing `samd_test` database only. Nothing
touches the running stack's database, and nothing restarts a container.

## H. Build plan: ordered commits, each with its proof

**How red-on-master is proven for every commit.**
- **Setup:** add a temporary `git worktree` at `master` in the scratchpad, copy only the commit's
  new or changed **test** files into it, run them there, and record the red result. Then run the
  same tests on the branch and record green.
- **Device runs:** `rm -rf app/build/test-results/<task>` before each run, and treat an empty
  results directory as RED, not zero failures (mutation-harness memory).
- **Tests are written to compile against master wherever possible**, so the red is behavioural, not
  a compile error. Where a new symbol makes compile-red unavoidable, that is stated.
- **Backend:** run with `ABDM_MODE=stub` on the invocation.
- **Persisted rows:** every test of a write asserts the persisted row through the ORM or raw SQL,
  never the HTTP response alone (CLAUDE.md convention).

1. **`feat(kernel): shared derivation rule version (R1)`**
   - **Changes:**
     - Add the device constant `KernelTriageRules.DERIVATION_RULE_VERSION = "HAN-07/08-v2"`.
     - Add `"derivation_rule_version": "HAN-07/08-v2"` to both `expectations.json` copies.
   - **Tests:**
     - `KernelEmergencyUrgencyFixtureTest` asserts that the device constant equals the fixture value.
     - `test_kernel_emergency_fixtures.py` asserts that the backend constant equals the fixture value.
     - `ClassifierFixtureMirrorTest` keeps both copies byte-identical.
   - **Red on master:**
     - **Backend:** a `KeyError` on the master fixture. This is behavioural.
     - **Device:** compile-red, because the constant does not exist. This is unavoidable and stated.
   - **Also:** declare the backend fixture as a Gradle task input if it is not already one
     (gradle-mirror-test-inputs memory).
2. **`test(fixtures): recapture classifier fixtures at 5e1ca00`**
   - **Method:** `git archive 5e1ca00` of SaMDClassifier, run in its own `.venv` through FastAPI
     `TestClient` (the #65 method), written into both fixture directories with `_provenance`
     updated. `expectations.json` triage values are expected to be unchanged.
   - **Test:** a new provenance test asserts that every fixture's `classifier_commit` is `5e1ca00`
     and that `model_metadata` carries `model_version`, `model_sha256` and `calibrated`.
   - **Red on master:** the master fixtures are at `63e85af` and have only `version`.
3. **`feat(backend): alembic 0009 and models`**
   - **Tests:**
     - Upgrade on the test DB creates every column, CHECK, index, the table and the triggers.
     - A NULL `model_version` insert succeeds.
     - Malformed `request_id` and `derivation_rule_version` inserts violate their CHECKs.
     - UPDATE and DELETE on `kernel_derivation_checks` raise.
     - Downgrade refuses while a NULL `model_version` exists, and succeeds once there is none.
   - **Red on master:** the columns and table are absent.
4. **`feat(sync): accept model_calibrated, request_id, derivation_rule_version; nullable model_version`**
   - **Accepted payloads:**
     - The new fields are accepted.
     - A null or omitted `model_version` is accepted.
     - An old-device payload is accepted, with `"remote-kernel"` stored verbatim.
   - **Rejected payloads:** each malformed format from section C is rejected TERMINAL, and the
     message does not contain the value.
   - **Red on master:** the new-field payload is rejected as `unexpected field`. This is behavioural.
5. **`feat(kernel-proxy): lift model_sha256 and model_calibrated`**
   - **Change:** the proxy writes these two values from `model_metadata` into `kernel_call_log` and
     `kernel_assessments`.
   - **Test:** drive the proxy with a stubbed classifier returning the recaptured fixture, then
     assert the persisted rows.
   - **Red on master:** the columns are NULL or absent.
6. **`feat(sync): derivation cross-check into kernel_derivation_checks (R4)`**
   - **Tests (every outcome asserts the persisted rows):**
     - MATCH.
     - MISMATCH: names only, and neither the row nor `caplog` contains a clinical value.
     - NO_LINK with a NULL `request_id`.
     - NO_LINK when the `request_id` has no assessment row.
     - NO_LINK when the assessment belongs to another facility.
     - NOT_CHECKED_ERROR, via a monkeypatched `derive_assess` that raises. The report is still persisted.
     - A failed check insert rolls back only its savepoint, and the report row persists.
     - A re-sync appends a second check row.
     - An idempotent replay appends none.
     - `kernel_reports` is byte-unchanged by the check (R4).
   - **Red on master:** the table and the behaviour are absent.
7. **`feat(encounters): derivation_rule_status, derivation_check, derivation_ok on the DOCTOR view`**
   - **Tests:**
     - v2 with MATCH gives CURRENT and `ok: true`.
     - v1 gives SUPERSEDED and `ok: false`.
     - NULL gives UNKNOWN and `ok: false`.
     - `"HAN-07/08-v9"` gives UNKNOWN.
     - No check row gives `derivation_check: null` and `ok: false`.
     - Every NOT_CHECKED status gives `ok: false`.
     - With two checks, the latest is chosen deterministically.
   - **Red on master:** the keys are absent.
8. **`feat(kernel): read calibrated and X-Request-ID on the device`**
   - **Change:** `ModelMetadataDto.calibrated`, plus `KernelAssessmentResult.modelCalibrated` and
     `requestId`.
   - **Tests (`RetrofitKernelSource` with MockWebServer):**
     - A valid header is stored lowercased.
     - An uppercase header is canonicalized.
     - A malformed header gives null.
     - A missing header gives null.
     - A body with only `version` gives `modelVersion` null.
   - **Red on master:** compile-red for the new fields. The `version`-only case is
     behavioural-green on master, since master already reads `model_version`. It is kept as a
     regression guard and labelled as such, not counted as red proof.
9. **`fix(kernel): null the model-version sentinels and persist identity (R2, R3), Room 22`**
   - **Changes:**
     - `GenerateKernelReportUseCase:293` and `:359` write NULL.
     - A missing version records `KERNEL_UNRECOGNISED_OUTPUT` with field `model_version` and no raw value.
     - `derivationRuleVersion` is stamped on REAL_INFERENCE rows only.
     - The domain model, entity, mappers and `SyncPayloadDto` change to match.
     - `MIGRATION_21_22`, `22.json` and `MigrationTest21To22` are added, and `MigrationTest.kt` is
       extended.
   - **JVM tests:**
     - The use case writes NULL for a missing version, with exactly one event that has no raw value.
     - An UNAVAILABLE row is NULL.
     - A REAL row carries the rule version, and a MOCK row does not.
     - The sync mapper JSON has the three new keys and omits a null `model_version`.
     - `SyncDaoSqlContractTest` is updated for the new columns.
   - **Red on master:** the use case returns `"remote-kernel"` and `"unavailable"`. This is
     behavioural.
   - **Instrumented:** `MigrationTest21To22`, on the emulator and on the iQOO I2302 (section F).
10. **`docs(progress): PR 4 entry`**: the `PROGRESS.md` entry, in the same commit set as 9, or as
    its own commit if the operator prefers.
11. **`docs(contract): PROPOSED kernel_reports sync fields, DOCTOR view fields, kernel_derivation_checks`**
    - A separate commit, marked PROPOSED, pending operator sign-off (IEC 62304).
    - Edits `docs/backend/api-contract.md` only: the sync payload fields, the encounter bundle
      fields, and the new table's existence.
    - Changes no D-9 or D-10 text (memo PR 8).

**Deploy order at merge time (R6):** the backend (0009 plus commits 3 to 7) is deployed and
verified with `alembic current` showing `0009`, before any APK carrying commits 8 and 9 is
distributed.

## I. D1c, minimum-app-version gate (recorded option, not built)

**Sketch.**
- A backend setting `MIN_SYNC_SOFTWARE_VERSION`.
- `POST /sync/push` compares the batch's `software_version`. The device already sends it per row,
  and on the batch through `device_id` and `software_version` (INFERRED from
  `KernelReportSyncPayloadDto`).
- Below the minimum, the batch is refused with a new RETRYABLE code, so the rows wait on the
  device rather than going FAILED, and the device shows "update required".

**Use.** It would let a safety fix stop pre-fix devices from writing new clinical rows. It is the
reverse of the D-matrix skew and does not mitigate it.

**Not built** in PR 4. It needs its own contract entry, copy and a decision on whether reads also
stop.

## Open questions for the operator

- **Q1. R5 wording.** The sync-taxonomy work R5 refers to has already landed at Room v21
  (`ae4fe90`, `19b1ee3`). Should R5 be recorded as "further sync-taxonomy schema work takes
  v23/0010", or is there a specific pending piece of work R5 means that I could not find?
- **Q2. A-08.** No item labelled A-08 exists in the repo. Confirm that the lesson as stated (no
  device-supplied free string stored verbatim) is what is meant, or point me to the source.
- **Q3. `request_id` on non-REAL rows.** I store it only on REAL_INFERENCE rows, so MOCK and
  UNAVAILABLE rows record `NOT_CHECKED_NO_LINK`, which is literally true. Storing it on
  UNAVAILABLE rows too would aid forensic joins to `kernel_call_log`. That would need either a
  fifth status or an acceptance that NO_LINK means "nothing to derive". Keep it REAL-only?
- **Q4. Existing verbatim tokens in `KERNEL_UNRECOGNISED_OUTPUT`.** The event already stores raw
  `triage_urgency` and `condition_tier` values from the classifier. Fold a fix into PR 4 (field
  name only), or leave it as a separate item?
