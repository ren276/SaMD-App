# PR #62 post-merge review

Reviewed 2026-09-29 against master `d5fceb8` (merge of #62, `feat/slm-backend-proxy`). #62 merged
with no automated review: CodeRabbit skipped it ("Too many files", 163 over the 100-file limit)
and Copilot hit its quota. Human review was the only control, so this record exists.

**Area A (SLM egress hop) is on record from 2026-09-22 and is not repeated here.** The one item
from that review closed today is A-NET-2, below.

Every claim is labelled MEASURED (run, or read at file:line) or INFERRED.

## Scope

`git diff --numstat d5fceb8^1 d5fceb8`: 162 files (the PR page said 163; `test_kernel.py` had
already landed on master through #58). Grouped:

| Group | Files | Added | Deleted |
|---|---|---|---|
| docs / scratchpad | 23 | 10,224 | 7 |
| tests (app unit, androidTest, backend) | 35 | 6,280 | 110 |
| app source | 83 | 5,375 | 412 |
| generated Room schema (`20.json`, `21.json`) | 2 | 5,079 | 0 |
| backend source | 17 | 1,470 | 38 |
| Alembic migration (`0008_slm_call_log.py`) | 1 | 115 | 0 |
| other (`app/build.gradle.kts`) | 1 | 43 | 2 |

The real review surface is **137 files, about 13,300 added lines** (app source, backend source,
tests, the migration, `build.gradle.kts`). That is still over CodeRabbit's 100-file limit, so
excluding docs alone would not have brought #62 under it.

## A-NET-2: closed

The service enforces its token. `POST /v1/generate` with body `{}` returned **401
`SAMD-SLM-8008`** ("missing or invalid X-SLM-Service-Token") both with no token and with a wrong
token (MEASURED, 2026-09-29, via the loopback tunnel). Auth is checked before the body is
validated, so no generation was possible. The tunnel itself binds `127.0.0.1` and `::1` only; it is
not reachable from the LAN, but it is reachable from any container under Docker Desktop through
`host.docker.internal` (MEASURED from `samd-classifier`).

## Area B: ABDM adapter (`abdm_adapter/service.py`, diff-scoped)

| Hunk | Verdict |
|---|---|
| `service.py:289-298` `submit_identity` guards the `txnId` read | FIXES the S-5 `_fail`-bypass trap. NEUTRAL for F6B-01, F6B-02, 23505, `AbhaApiResult` |
| `service.py:364-398` `verify_otp` reads the whole enrol body before the first state change | FIXES the S-5 audit-integrity trap (malformed 200 now ends FAILED with `ABHA_SESSION_FAILED`) |
| `tests/conftest.py` `_stub_abdm_gateway` forces `ABDM_MODE=stub` | FIXES the S-5 section 0 leak of a live mode into the suite |
| `tests/test_abha.py` four malformed-enrol cases, missing `txnId`, retry-class unit test | Covers the fix; see B-04 |

Checks: `AbhaApiResult` and the device side are not in the diff (MEASURED). No new path to live
mode: `abdm_mode` still defaults to `stub` (MEASURED `config.py:102`). The new tests read the row
and audit rows back from the database, use path-aware mock transports, and (verify_otp only) assert
the call was reached. No route calls two ABHA service functions in one request (MEASURED
`router.py:56,78,101,134`), so the widened deadlock rule cannot trigger across calls.

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| B-01 | P2 | A present-but-null `ABHANumber`, `token` or `txnId` passes the new guard: `str(None)` is `"None"` and gets persisted (row reaches ENROLLED with `abha_number = "None"`) | MEASURED mechanism, impact INFERRED |
| B-02 | P3 | A malformed 200 from `enrol/byAadhaar` is classed RETRYABLE, though the OTP may already be consumed and the ABHA created | INFERRED (ABDM semantics) |
| B-03 | P3 | Guard checks presence, not length/shape: `abha_number` is `String(14)`, `abha_status`/`abha_type` `String(20)`; an oversize value fails at the ENROLLED flush after the audit, as a 503 "database unavailable" (F6B-02-shaped) | MEASURED columns, failure INFERRED |
| B-04 | P3 | `test_submit_identity_missing_txn_id…` has no call-reached flag and does not assert `last_error_detail` | MEASURED |

## Area C: sync (`services/sync.py`, `RoomSyncOutboxRepository.kt`, DAO/entity retry fields)

Mapping against F6B-01 (FAILED rows never re-collected or surfaced), F6B-02 (blanket catch) and
the 23505 duplicate-ABHA chain:

- FIXES F6B-01: RETRYABLE rows re-collected; 23503 children wait for their parent
  (`sync.py:151-156`); `requeueFailed`; `failedRecords()`; bounded attempts with a persisted reason.
- Partly FIXES the 23505 chain: a duplicate ABHA is now classified CONFLICT and shown to the worker
  as DUPLICATE_RECORD; its children are C-03.
- NEUTRAL for F6B-02: the constraint catch is still narrow. On the /assess hop, the 404 is now a
  typed `KernelFailure.CASE_NOT_ON_SERVER` (MEASURED `KernelFailure.kt:73-81`).
- `sync.py` makes no upstream call and runs on the request session by design, so
  `write_out_of_band` after an upstream call does not apply (MEASURED `sync.py:686-695`). A sync
  retry cannot re-send an ABHA enrolment (it pushes rows to `/sync`, database only).
- No retry storm: 5-minute minimum interval, 5 attempts, 15-minute periodic drain (MEASURED
  `SyncRetryPolicy.kt:26,41`, `WorkManagerSyncOutboxScheduler.kt:83`); backoff is fixed.

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| C-02 | P2 | The 5-minute `lastSyncAttemptAt` gate also applies to PENDING rows. Edits that set PENDING without clearing it (`assignDoctor`, `updateStatus`, `sendAllPendingSync`, referral `updateStatus`) are delayed 3 to 18 minutes after a recent sync. Creating a referral (including EMERGENCY) is not delayed: a new row has `lastSyncAttemptAt = null` | MEASURED queries and UPDATEs, delay INFERRED |
| C-01 | P2 | Unknown audit action is RETRYABLE (`sync.py:483-495`), but the device gives up in about an hour, before a backend rollout can land; the audit chain then has a gap, and the copy blames a missing parent | MEASURED constants, timing INFERRED |
| C-03 | P2 | Children of a duplicate-ABHA patient hit 23503, exhaust retries, and show "parent may have landed" copy though the parent never will | MEASURED classification, outcome INFERRED |
| C-04 | P3 | An ack with an unknown status or table is skipped without touching the attempt count or state: the row is resent every drain, never reaches FAILED, never surfaces | MEASURED |
| C-05 | P3 | `abha_profiles` review row has `patientId` NULL, so ABHA failures show no patient name (`AbhaProfileDao.kt:63`) | MEASURED |
| C-06 | P3 | The entity KDoc says an edit resets `syncAttemptCount`; the UPDATE-based edits do not | MEASURED |

## Area D: Room 20 to 21 and migrations

- `MIGRATION_20_21` adds `syncErrorMessage TEXT` and `syncAttemptCount INTEGER NOT NULL DEFAULT 0`
  to 21 tables, no backfill, FAILED rows stay FAILED (MEASURED `Migrations.kt:575-610`).
- `21.json` matches the migration: a structural diff of `20.json` and `21.json` shows the same
  table set, identical indices, foreign keys and views, exactly the two new fields on all 21
  tables carrying `syncState`, and every `createSql` identical to version 20 once those two
  columns are removed. `19.json` to `20.json` differs only by `kernel_reports.failureCode`
  (MEASURED).
- No `fallbackToDestructiveMigration` variant anywhere in `app/src`; both migrations are
  registered in `addMigrations`; database version 21, `exportSchema = true` (MEASURED).
- No unique-index or "one current X per case" rule was added or removed by #62's migrations
  (Room indices identical 20 to 21; Alembic `0008` creates only `slm_call_log`). The existing
  one-report-per-case rule for kernel reports is enforced by a unique index (MEASURED
  `KernelReportEntity.kt`).

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| D-01 | P2 | CI never runs any androidTest: `android-ci.yml:39,42` runs `assemble<Flavor>Debug` and `test<Flavor>DebugUnitTest`; `android-release.yml:36,44` likewise; no `connectedAndroidTest` or emulator step | MEASURED |
| D-02 | P3 | `syncAttemptCount` has `DEFAULT 0` on upgraded installs only; entities and `21.json` declare no default. `runMigrationsAndValidate` cannot see it | MEASURED, Room comparison INFERRED |
| D-03 | P3 | `MigrationTest20To21` seeds rows in `patients` only; `freshInstallAtV21…` asserts nothing | MEASURED |
| D-04 | P3 | `sync_log` does not record the `retry_class` sent to the device | MEASURED |
| D-05 | P3 | Alembic `0008` `downgrade()` drops `slm_call_log` with all rows | MEASURED |
| D-06 | P3 | `FailedSyncReviewQueryTest.kernelReportsDao_reachesThePatientThroughItsCaseRecord` fails; classified **TEST_BUG**, see below | MEASURED |

## Step 0: the previously unexecuted evidence, now run

`:app:connectedDevDebugAndroidTest` on `emulator-5554` (AVD Pixel_9_Pro_3, Android 17), 2026-09-29,
result XML timestamp `2026-09-29T11:15:52Z`. **26 run, 25 passed, 1 failed, 0 skipped.**

| Class | Run | Passed | Failed |
|---|---|---|---|
| `MigrationTest19To20` | 2 | 2 | 0 |
| `MigrationTest20To21` | 3 | 3 | 0 |
| `SyncStateResetTest` | 14 | 14 | 0 |
| `EvaluateReportFailureSyncSafetyTest` | 2 | 2 | 0 |
| `FailedSyncReviewQueryTest` | 5 | 4 | 1 |

Both migration classes are green on a real SQLCipher database, including row survival and "an
existing FAILED row stays FAILED". These are the first recorded runs of these classes.

### D-06: TEST_BUG, no production reach

The failing test seeds `kr-1` (FAILED) and `kr-2` (PENDING) both with `caseRecordId = "case-1"`.
`kernel_reports` has a **unique** index on `caseRecordId` and `KernelReportDao.upsert` is
`OnConflictStrategy.REPLACE`, so inserting `kr-2` deletes `kr-1`. A temporary raw
`SELECT id, caseRecordId, syncState FROM kernel_reports` after seeding returned only
`kr-2|case-1|PENDING` (MEASURED on the emulator; the diagnostic was reverted, byte-identical by
`cmp`). The query (`LEFT JOIN … WHERE kr.syncState = 'FAILED'`) is correct, and no state was
rewritten; the FAILED row simply never existed. Production cannot reach it: the repository reuses
the existing report id for a case (`KernelReportRepositoryImpl.kt:22-33`). Fix is test-only: seed
the PENDING report under a second case record.

## Device rule for instrumented tests

Every connected test run sets `ANDROID_SERIAL=emulator-5554`. Never run instrumented tests on the
physical phone (serial `10BE3A09C700046`). Incident that prompted the rule: with a phone attached,
an unpinned `connectedDevDebugAndroidTest` also targeted it; the phone reported "Unable to find
instrumentation target package" and a failed uninstall of `com.example.samdapp.dev`
(`DELETE_FAILED_INTERNAL_ERROR`). The phone had no samdapp package afterwards, so nothing was
removed (MEASURED), but the run should never have reached it.

## Where each finding goes

| Finding | Destination |
|---|---|
| **C-02** | **Fix now**: branch `fix/sync-pending-eligibility` |
| D-06 | Fix now with C-02 (test only, its own commit) |
| C-01, C-03, C-04, C-05, C-06, D-04 | Sync failure taxonomy work |
| B-01, B-02, B-03, B-04 | ABHA state memo |
| D-01, D-02, D-03, D-05 | Housekeeping (CI job for androidTest, schema default, migration test breadth, downgrade guard) |
| A-NET-2 | Closed, this document |
| Area A findings | Already on record from 2026-09-22 (SLM hardening) |
