# Sync re-sync fix: Phase 0 recon (read-only)

Drafted 2026-10-01 on branch `fix/sync-resync-timestamp` (created off master `b44d082`, no code).
Every claim is MEASURED (file:line or a command that was run) unless marked INFERRED. Paths are
relative to the repo root; backend paths are under `backend/core/`.

**Summary.**
- **The bug:** re-syncing an existing row of four tables raises `AttributeError` on master. It
  escapes the per-record savepoint and returns **HTTP 500 for the whole batch**.
- **The poison:** the device resends that same batch under the same `batch_id` first on every
  drain, and stops the drain when it fails. **One re-synced kernel report therefore blocks every
  later sync from that device, indefinitely, with nothing on screen saying so.**
- **The trigger:** the realistic one is re-assessment of a case whose first report already synced.
- **The comparator is wrong on all 19 tables**, not just the four that crash. The correct one
  needs a stored `client_updated_at`, so a migration.

## 1. Timestamp and version columns per synced model

Measured from the SQLAlchemy mappers of `TABLE_REGISTRY` (`app/services/sync.py:182-...`) on
master. `_timestamp_attr` is the column master compares against (`app/services/sync.py:242-252`):
`updated_at` if the model has one, else `created_at`.

| table | rank | updated_at | created_at | client_updated_at | server_version | `_timestamp_attr` | exists? |
|---|---|---|---|---|---|---|---|
| patients | 1 | Y | Y | - | Y | updated_at | OK |
| encounters | 2 | Y | Y | - | Y | updated_at | OK |
| consultations | 3 | Y | Y | - | Y | updated_at | OK |
| attachments | 4 | - | Y | - | Y | created_at | OK |
| observations | 5 | - | Y | - | Y | created_at | OK |
| ailments | 6 | - | Y | - | Y | created_at | OK |
| medical_history_items | 7 | - | Y | - | Y | created_at | OK |
| allergies | 8 | - | Y | - | Y | created_at | OK |
| family_history_entries | 9 | - | Y | - | Y | created_at | OK |
| social_histories | 10 | Y | - | - | Y | updated_at | OK |
| medication_entries | 11 | - | Y | - | Y | created_at | OK |
| case_records | 12 | Y | Y | - | Y | updated_at | OK |
| **kernel_reports** | 13 | - | - | - | Y | created_at | **MISSING** |
| **evaluate_reports** | 14 | - | - | - | Y | created_at | **MISSING** |
| diagnosis_feedback | 15 | - | Y | - | Y | created_at | OK |
| prescriptions | 16 | - | Y | - | Y | created_at | OK |
| **medication_lines** | 17 | - | - | - | Y | created_at | **MISSING** |
| **referrals** | 18 | - | - | - | Y | created_at | **MISSING** |
| abha_profiles | 19 | - | Y | - | Y | created_at | OK |

- **`client_updated_at`:** no synced model stores it.
- **`server_version`:** every synced model has it, from `SyncMixin` (`app/models/mixins.py`).
- **`sync_log`:** `SyncLogEntry` does not store it either. Its columns are `id, batch_id,
  table_name, record_id, status, code, message, server_version, applied_at`.
- **`sync_batches`:** keeps the whole response (`response_json`), but not the per-record
  timestamp.
- **`audit_log`:** goes through its own path (`_apply_audit_log`), not `_apply_generic`, and is
  out of scope.

## 2. The correct comparator, and why it needs a migration

**What the design says.**
- **`docs/sync-design.md:90-92`:** "last-write-wins keyed on `localModifiedAt` (mapped to
  `client_updated_at` on the wire) + `serverVersion`".
- **The device:** sends `clientUpdatedAt = localModifiedAt, baseVersion = serverVersion` for
  every table (`app/src/main/java/com/example/samdapp/data/sync/SyncRecordMappers.kt:52`, `:66`,
  `:75`, and the rest).
- **The contract (`docs/backend/api-contract.md` section 6.1, around lines 1070-1078)** says
  "`client_updated_at` newer than the stored `updated_at`". That is what master implements. It
  compares a sync revision against a device data column with different meaning, or against
  `created_at` when there is no `updated_at`.

**Per the operator rule, the comparator is the incoming `client_updated_at` against the STORED
`client_updated_at`, plus `server_version`.** Comparing against `inference_ended_at`,
`created_at`, `updated_at` or any other column is rejected. Consequences on master today, all
INFERRED from `_apply_generic` (`app/services/sync.py`, the stale check after the facility check):
- **The four MISSING tables** crash on any second write of an existing id. See section 3.
- **The eleven `created_at` tables** compare a revision against creation time. Any resend with
  `client_updated_at > created_at`, which includes a plain replay under a new batch, is
  re-applied and bumps `server_version` instead of being acknowledged `stale`. A delayed older
  edit can overwrite a newer one. **No stale detection exists for them at all.**
- **The four `updated_at` tables** compare against a clinical field the device sets, not the sync
  revision. That is correct only when the two happen to be equal.

**A migration is needed.** No column holds the stored revision (section 1). The fix therefore
takes **Alembic 0009**, and **PR 4's migration renumbers to 0010**.

**What PR 4 must change on rebase** (MEASURED with `git grep -n 0009 feat/pr4-model-identity-consumers`):
1. `alembic/versions/0009_model_identity_and_derivation_checks.py` is renamed to
   `0010_model_identity_and_derivation_checks.py`.
   - `revision = "0010"`, `down_revision = "0009"` (was `"0008"`).
   - Docstring: `Revision ID: 0010`, `Revises: 0009`.
   - The refusal text `0009 downgrade refused` becomes `0010 downgrade refused`.
2. `tests/test_alembic_0009.py` is renamed to `tests/test_alembic_0010.py`.
   - `_SCHEMA` becomes `"mig_0010_scratch"`.
   - Every `_upgrade_to(scratch, "0008")` becomes `"0009"`.
   - Every `_upgrade_to(scratch, "0009")` and `m.revision == "0009"` becomes `"0010"`.
   - The `pytest.raises(..., match="0009 downgrade refused...")` pattern becomes `"0010 ..."`.
   - The test name `test_a_row_written_before_0009_...` becomes `..._before_0010_...`.
   - Docstrings follow.
3. Text references move to "alembic 0010":
   - `app/domain/kernel_identity.py:5`
   - `app/models/sync.py:277` (the `KernelDerivationCheck` docstring)
   - `tests/conftest.py:136`
   - `tests/test_kernel_identity_schema.py:1`
4. **Commit message:** commit 3's subject `feat(backend): alembic 0009 and models` becomes `alembic 0010`.
5. **Expected textual conflicts:**
   - **`app/services/sync.py`.** PR 4 adds `TableSpec.formats` and the format-check loop beside
     the forbidden-field loop. The fix edits `_SYNC_MIXIN_OWNED`, removes `_timestamp_attr`, and
     changes the stale check and `_apply_one`'s except clauses. These are different hunks, but
     close enough that a rebase may stop on them.
   - **`tests/test_sync_retry_class_mirror.py`.** Both branches raise `_EXPECTED_REJECT_SITES`:
     PR 4 from 17 to 18, the fix from 17 to 18. **The rebased value is 19.**
6. **Parked stash `stash@{0}`:** its PROGRESS.md draft says "Alembic 0009" three times, and those
   become 0010. Its `kernel_derivation_check.py` does not name the revision.
7. **Not renumbered, the PR 4 tests that build a scratch schema.** They run every migration in
   order, so the fix's 0009 runs before PR 4's 0010, and the autogenerate parity test then also
   sees `client_updated_at` on the kernel tables. That is correct because the models carry it too.

## 3. Poison batch: yes, proven on a clean master worktree

**Test** (in a throwaway worktree of master, test database only, then removed). It sends one
batch that applies `kernel_reports` `kr-1`. It then sends a second batch holding `kr-1` again,
with a later `client_updated_at`, plus a brand-new valid observation `obs-new`. The batch goes
through an HTTP client with `raise_app_exceptions=False`, so the result is what a device would
receive:

```
STATUS 500 SAMD-SYS-9005 | replay 500
PERSISTED observation obs-new: 0 | sync_batches row for the batch: 0
1 passed
```

**Why it escapes (MEASURED).**
- `_apply_one` wraps each record in `session.begin_nested()` and catches only
  `(IntegrityError, DataError)` (`app/services/sync.py:663-692`).
- The `AttributeError` from `getattr(existing, _timestamp_attr(spec))` is neither of those, so it
  leaves `push()`.
- The request-scoped transaction rolls back, including the `sync_batches` idempotency row and the
  unrelated observation.
- `handle_unexpected` (`app/main.py:237-247`) returns 500 `SAMD-SYS-9005`.
- Because the idempotency row was rolled back, **a replay of the same `batch_id` re-applies and
  fails the same way** (the `replay 500` above).

**Device side (MEASURED).**
- **No classification.** `RetrofitSyncPushService` maps any non-2xx to
  `SyncPushResult.Failure` (`data/remote/RetrofitSyncPushService.kt:33-43`). There is no
  RETRYABLE or TERMINAL distinction at batch level, because retry classes are per-record acks
  only (`data/remote/SyncPushResult.kt:1-6`).
- **The batch stays in flight.** `SyncOutboxDrainer.sendAndApply` deliberately does not clear the
  in-flight batch on `Failure` (`data/sync/SyncOutboxDrainer.kt:198-204`).
- **The poison batch always goes first.** The next `drain()` runs `resumeInFlightBatch()` first
  and resends the same rows under the same `batch_id` (`:140-160`). If that fails it returns
  immediately (`:89-92`), **before any other PENDING row is collected**.
- **Retried for ever.** `SyncPushWorker` maps a failed drain to `Result.retry()`
  (`data/sync/SyncPushWorker.kt:32`), with exponential backoff
  (`data/sync/WorkManagerSyncOutboxScheduler.kt:36`, `:46`), and the periodic work also keeps
  running. Nothing bounds this: the attempt cap (`MAX_SYNC_ATTEMPTS`) is applied only when a
  per-record ack is applied, and a 500 produces no acks.

**F6B-01 does not surface it (MEASURED).**
- **No failed count.** The rows stay PENDING, never FAILED, so `failedCount` stays 0
  (`domain/sync/SyncStatus.kt`, the `failedCount` KDoc says it excludes rows still being worked
  on).
- **No "pending" either.** Home's caption reads `caseRecordRepository.observePendingSyncCount()`,
  the doctor-assignment queue (`data/sync/SyncStatusImpl.kt:73`), not the outbox. Home therefore
  says "Up to date" (`presentation/home/HomeScreen.kt:270-274`).
- **"Sync now" swallows the error.** `HomeViewModel.onSyncNow` discards `syncNow()`'s `Result`
  (`presentation/home/HomeViewModel.kt:96-97`).
- **The only signal** is "Last synced ..." no longer advancing (`HomeScreen.kt:257-261`,
  `SyncStatusImpl.kt:99`).

**Worst case, one sentence:** one kernel or evaluate report re-saved after its first copy synced
makes that device's sync 500 on every attempt, permanently. Every later patient, encounter,
consultation and report from that device then stays on the phone and never reaches the server or
a physician, while Home says "Up to date".

## 4. Which device paths re-push an existing `kernel_reports` id

| Path | Re-pushes an existing id? | Evidence |
|---|---|---|
| (a) Lost ack | **No**, while the idempotency row exists. The drainer resends under the **same** `batch_id` (`SyncOutboxDrainer.kt:25-32`, `:140-160`, `InFlightBatchStore.kt:29-36`), and `push()` answers a known `batch_id` from `sync_batches.response_json` without re-applying. No TTL is enforced in code: `grep -n "timedelta\|24" app/services/sync.py` finds no expiry, although the contract text says 24 hours. If the in-flight store is unreadable, the drain fails rather than minting a new id (`SyncOutboxDrainer.kt:141-146`). | MEASURED |
| (b) Re-assessment of the same case | **Yes.** The id is stable: `KernelReportRepositoryImpl.save` reuses `kernelReportDao.getIdForCase(caseRecordId) ?: report.id` (`data/repository/KernelReportRepositoryImpl.kt:31`) and writes it with `@Insert(REPLACE)` (`KernelReportDao.kt:14`, called at `:33`), resetting the row to PENDING with the old `serverVersion` threaded through as `base_version`. `onRetry` re-enqueues the assessment (`KernelAssessmentViewModel.kt:300`), and the runner saves through the same path (`GenerateKernelReportUseCase.kt:124`, `:145`). `base_version` matches the server, so the conflict check passes and the stale check crashes. The same holds for `evaluate_reports` (`EvaluateReportRepositoryImpl.kt:51-54`, `:69-73`). The realistic sequence: the kernel was down, an UNAVAILABLE row synced, the worker tapped Retry, and the new real result re-pushes the same id. | MEASURED (sequence INFERRED) |
| (c) "Send again" | **Only if the server already holds that id.** `requeueFailed` sets a FAILED row back to PENDING, keeping its id (`KernelReportDao.kt:60-66`, `RoomSyncOutboxRepository.kt:146-165`). A row that FAILED on its first insert is absent server side, so that resend takes the insert path and does not crash. A row that was applied earlier and FAILED on a later write does crash. | MEASURED (code); INFERRED (which rows reach it) |

`medication_lines` and `referrals` are also synced from the device and re-pushable
(`RoomSyncOutboxRepository.kt:123-124`, `:164-165`). Whether their ids are re-saved in normal use
is not traced here.

## 5. Has it fired on the running backend

Read-only `docker logs --timestamps backend-api-1`. Counts and timestamps only, no content.

| Pattern | Count | First |
|---|---|---|
| `AttributeError` | 0 | none |
| `has no attribute 'created_at'` | 0 | none |
| `SAMD-SYS-9005` | 0 | none |
| `sync/push` | 0 | none |

- **Scope of the logs:** the container has been up about 42 hours, and its whole log is **7
  lines**, the earliest at `2026-09-29T11:13:52Z`. Classified by level and event only, they are
  all startup lines: alembic context, Uvicorn start, and the app `startup` event.
- **What a firing would leave:** an unhandled exception is logged as `unhandled_exception` at
  ERROR level (`app/main.py:242`), so a firing in this container would have left a line.
- **Conclusion:** it has **not fired in the current container's lifetime**. Nothing can be said
  about earlier containers, whose logs are gone. The log also shows no request lines at all, so it
  cannot show whether any sync traffic arrived.

## 6. Fix design

**6.1 Comparator (all 19 generic tables), in `_apply_generic`**
1. **Conflict:** `base_version` present and not equal to the stored `server_version` gives
   `conflict`. This is unchanged.
2. **Stale:** if the stored `client_updated_at` is not NULL and the incoming
   `client_updated_at <= stored`, the result is `stale`. No write, and `server_version` is
   unchanged. An equal value is a replay.
3. **Applied:** otherwise apply, set `client_updated_at = incoming`, and increment
   `server_version`.
4. **Insert:** a new row is stored with `client_updated_at = incoming`.
5. **Legacy rows:** a stored NULL is treated as older than any incoming value, so the first write
   after the migration applies and sets it. It is **not** back-filled from `updated_at` or
   `created_at`, which are semantically different columns. The window this leaves (an old delayed
   write can overwrite a newer one on a legacy row) is closed by the pre-pilot data reset (D3).
6. **`_timestamp_attr` is deleted.**
7. **`client_updated_at` is server-owned:** it is added to `_SYNC_MIXIN_OWNED`. It is set only from
   the record envelope, and a `data.client_updated_at` sent by a device stays an unknown field
   (TERMINAL).

**6.2 Migration, Alembic 0009 `0009_sync_client_updated_at`.** Add
`client_updated_at TIMESTAMPTZ NULL` to the 19 tables, through `SyncMixin`. No index, because the
lookup is by primary key. No backfill. The downgrade drops the 19 columns.

**6.3 Poison containment in `_apply_one`.** After the existing
`except (IntegrityError, DataError)`:
- **`except SQLAlchemyError: raise`.** A dead connection or other database-level failure must
  still fail the whole batch, so it is retried whole and is not marked per record against a
  broken session.
- **`except Exception`.** The record's savepoint has already rolled back. Log
  `sync_record_unexpected_error` with table, record id and exception class at ERROR. Return
  `_reject(table, safe_id, ErrorCode.SYS_INTERNAL, "server error applying this record.", SyncRetryClass.RETRYABLE)`.
  - **Why RETRYABLE:** a server bug that a deploy can fix. The device retries the row within
    `MAX_SYNC_ATTEMPTS`, then marks it FAILED with `RETRY_EXHAUSTED_CODE`, which F6B-01 shows on
    Home with "Send again".
  - **Why not TERMINAL:** that would condemn recoverable clinical data to a bug.
  - **The code:** `SYS_INTERNAL` already exists, so the device needs no change; its
    `syncFailureReasonFor` maps an unknown code to `UNRECOGNISED`.
  - **Cancellation still propagates:** `CancelledError` is a `BaseException`, so `except Exception`
    does not swallow it.
- **The pinned `_reject` count** in `tests/test_sync_retry_class_mirror.py` goes from 17 to 18.

**6.4 Tests. Each asserts persisted rows, and each has a red-on-master proof (test files copied
into a master worktree).**
1. **Comparator, parametrized over all 19 tables in `TABLE_REGISTRY`**, using the `test_sync.py`
   record builders (adding minimal builders for any table without one). Each table checks:
   - first write stores `client_updated_at`
   - a later write is applied, and `server_version` becomes 2
   - an equal (replay) write is `stale`, with no change
   - an older write is `stale`, with no change
   - a stored NULL then a newer write is applied

   **Red on master:** the four MISSING tables raise `AttributeError`. The `created_at` tables apply
   the replay instead of `stale`. The `client_updated_at` column is absent everywhere.
2. **Lost-ack replay:** the same `batch_id` resent is answered from the store, so the observable
   `server_version` is unchanged. The same records under a **new** `batch_id` are `stale`.
   **Red on master:** the new-batch case re-applies a `created_at` table and crashes a kernel
   report.
3. **Re-assessment:** a `kernel_reports` and an `evaluate_reports` row are synced, then the same id
   is synced with a later `client_updated_at` and `base_version` 1. The second write is applied,
   `server_version` is 2, and the persisted values are the new ones. **Red on master:**
   `AttributeError`, or a 500 through the device-view client.
4. **Poison-batch regression:** a batch with one record whose apply raises a non-database
   exception (monkeypatched `_apply_generic` for that one table) plus one valid record. The batch
   returns 200. The bad record is `rejected`, `SAMD-SYS-9005`, RETRYABLE. The valid record is
   persisted, and the `sync_batches` row is persisted. A second test checks that a raised
   `OperationalError` still fails the whole batch. **Red on master:** 500 and nothing persisted,
   exactly section 3's output.
5. **Server-owned column:** `data.client_updated_at` is rejected TERMINAL as an unexpected field.
6. **Mirror:** `_EXPECTED_REJECT_SITES = 18`, and the new site passes `SyncRetryClass.RETRYABLE`.
7. **Migration:** 0009 is run in the scratch-schema pattern PR 4 introduced (`test_alembic_0009.py`).
   Upgrade adds the column to all 19 tables, and the downgrade removes it. **Red on master:** no
   0009.

**6.5 Out of scope, recorded.**
- **The contract text** (`api-contract.md` section 6.1, "stored `updated_at`") must change to
  "stored `client_updated_at`". This is a PROPOSED docs commit, pending sign-off.
- **The device makes batch-level failure invisible:** an outbox that cannot drain is never shown,
  and "Sync now" swallows its error. That is the reason this bug is silent, and it deserves its
  own item.
- **The idempotency store has no TTL**, despite the contract's "24 hours".
