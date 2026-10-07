# S-2: states, schema, drain, bounded requeue

Tree at a219da9 plus the four pre-existing uncommitted changes, PR-0 through PR-3, the F6C fixes,
S-5, S-5b, S-4 and S-1. All left as they were. Nothing committed. No credential file opened,
`docs/quality/risk-management-file.md` untouched. The backend suite was run with `ABDM_MODE=stub`
on the command line; S-2 changes no backend code, so that run is a regression check only.

MEASURED = observed from a run in this session. INFERRED = read from source, not executed.

---

## STEP 1: the state machine

Written first, implemented against it.

| From | To | Trigger | Terminal? |
|---|---|---|---|
| (new row) | `PENDING` | Any local clinical write. Entity default. | no |
| `PENDING` | `SYNCED` | Ack `applied` / `stale` / `duplicate` | yes, until edited |
| `PENDING` | `CONFLICT` | Ack `conflict` | yes, until edited |
| `PENDING` | `RETRYABLE` | Ack `rejected` + `retry_class = RETRYABLE` | **no** |
| `PENDING` | `FAILED` | Ack `rejected` + `TERMINAL` or `CONFLICT`; or a locally-failed oversize record | yes |
| `RETRYABLE` | `SYNCED` | A later drain succeeds | yes, until edited |
| `RETRYABLE` | `CONFLICT` | A later drain acks `conflict` | yes, until edited |
| `RETRYABLE` | `RETRYABLE` | A later drain acks `RETRYABLE` again, budget remaining | no |
| `RETRYABLE` | `FAILED` (`SAMD-SYNC-RETRY-EXHAUSTED`) | The ack that makes `syncAttemptCount + 1 >= MAX_SYNC_ATTEMPTS` | yes |
| `RETRYABLE` | `FAILED` (server code) | A later drain acks `TERMINAL`/`CONFLICT` | yes |
| `FAILED` | `PENDING` | `requeueFailed`, explicit human action only (S-3's UI) | no |
| `SYNCED` / `CONFLICT` / `FAILED` | `PENDING` | Any local edit: the repository rewrites the row and the entity defaults reset state, count, code and message | no |

Only `PENDING` and `RETRYABLE` are collected by a drain, and only when
`lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= now - RETRY_MIN_INTERVAL`.

### The attempt-count reset decision

**Reset on success AND on edit. Never on a failure of any kind.**

- **On success**, in the same statement that applies the ack:
  `syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END`.
- **On edit**, automatically. A clinical edit goes through the repository, which builds a fresh
  entity and upserts it; `syncAttemptCount` takes its declared default of 0, exactly as
  `syncState` already took `PENDING`. No extra code. The seven explicit
  `SET ... syncState = 'PENDING'` DAO statements are reset-in-place writers on top of that.
- **On requeue**, explicitly: `requeueFailed` sets the count to 0 with the state.

**The brief's question: a row that succeeds after three failures and later fails again.** It gets
a full five attempts the second time, not two. The second failure is a different event, very
likely a different cause, and charging it the first episode's attempts would abandon it early for
history it has nothing to do with. The opposite rule (never reset) makes a row's retry budget a
lifetime allowance, so a record that has synced happily for months would be abandoned on its
first bad day. That is the wrong shape for clinical data.

**What is NOT reset: a failure.** A `RETRYABLE` ack always increments. That is what makes the cap
bind at all.

### SETTLED items, implemented as specified

`RETRYABLE` is not terminal; the cap produces a FAILED with a distinct stored reason;
`syncAttemptCount` lands in this migration; an edited FAILED row returns to PENDING by explicit
human action, and the transition (`SyncOutboxRepository.requeueFailed`, 21 DAO methods) ships
here while its UI stays with S-3.

---

## STEP 2: the migration

`MIGRATION_20_21`, two columns, `syncErrorMessage TEXT` and
`syncAttemptCount INTEGER NOT NULL DEFAULT 0`, on **21 tables**. Built on `MIGRATION_12_13`'s
local-helper shape rather than 42 hand-written statements.

- **No backfill on either column.** 0 and NULL are the true values for rows written before either
  existed. A fabricated count would give a row fewer real attempts than the policy promises.
- **Existing FAILED rows stay FAILED.** The one-time re-push is owner-gated and does not ship
  here, even though S-1's absence verdict cleared it. The migration test asserts this explicitly,
  because a migration that quietly moved them would make the owner's decision for them and resend
  clinical records nobody authorised.

### Does any table need different treatment? One, and it is a schema-only case

**`consultation_documents`.** It is deliberately unwired from the outbox: its `getPendingForSync`
is never called, it is absent from `collectPendingRecords` and from `applyAck`'s `when`. It is
still a Room entity carrying the same sync columns, so its schema must match its entity or Room's
own validation fails when the database opens after the upgrade. It therefore gets the columns,
the `requeueFailed` method (so the state machine has no table-shaped hole) and **not** the widened
drain predicate. `SyncDaoSqlContractTest` asserts both halves of that, so wiring it in by accident
fails the build.

The other twenty differ only in primary key name, which these columns do not touch.

**`MigrationTest20To21` is written and compiled. It did NOT run: no device or emulator is
attached.** Same status as `MigrationTest19To20` from S-4. Neither has ever executed.

---

## STEP 3: drain and requeue

### The query count, re-derived rather than trusted

The diagnosis said "28 queries across 16 DAO files against 20 pushed tables". MEASURED by grep,
the 28 reconciles but it is two different kinds of statement added together:

| Kind | Count |
|---|---|
| `SELECT ... WHERE syncState = 'PENDING'` drain queries | 21 (20 wired + `consultation_documents`) |
| `UPDATE ... SET syncState = 'PENDING'` reset-in-place writers | 7 |
| **Diagnosis total** | **28** |
| `applySyncResult` UPDATEs | 21 |
| `observeFailedSyncCount` counters | 20 (none for the unwired table) |

**The number that matters for "every drain query selects PENDING or RETRYABLE" is 20, not 28.**
The 7 resets already write PENDING and needed no change. All 20 were widened; the 21st was left
alone. Verified by count in `SyncDaoSqlContractTest`, and by grep: exactly one bare
`WHERE syncState = 'PENDING'` select remains, in `ConsultationDocumentDao`.

### Backoff and cap, with the numbers

- **`MAX_SYNC_ATTEMPTS = 5`.** Against a 15-minute periodic drain
  (`WorkManagerSyncOutboxScheduler.PERIODIC_INTERVAL_MINUTES`, MEASURED), five acked attempts
  carry a row for at least an hour and normally a working session. The dominant retryable cause is
  a `23503` whose parent is in the same outbox and lands on the very next drain, so one retry
  usually suffices; a row that burns all five has a parent that is not coming and should reach a
  human the same day.
- **`RETRY_MIN_INTERVAL = 5 minutes`**, flat, deliberately BELOW the periodic interval. It is not
  the backoff. The periodic schedule is the backoff and WorkManager already layers exponential
  retry on top. This only stops a row being retried inside one drain or by a worker tapping sync
  repeatedly, and staying under 15 minutes means it can never delay a scheduled drain that would
  have carried the row.
- **Exponential per-row backoff was considered and rejected.** It buys nothing for the dominant
  case, where a longer wait is strictly worse, and it would have needed a `nextAttemptAt` column
  on all 21 tables to express. Recorded so it is not re-proposed as an oversight.
- **Cap exit:** `FAILED` with `syncErrorCode = SAMD-SYNC-RETRY-EXHAUSTED`, a device-local code in
  no backend registry, following the precedent `failOversizedRecordsLocally` set with
  `SAMD-SYNC-RECORD-TOO-LARGE`. Separable from a server refusal, which is the whole justification
  for allowing a cap.

### Where the attempt is charged, and why that point is safe

**On the ack, inside each DAO's own `applySyncResult` statement.** Not before the push.

The brief asked for a durable increment that survives a crash. A pre-push increment does survive a
crash, and it is wrong, for a reason that only shows up when you look at what else runs the drain:

> A PHC whose Wi-Fi is up but whose backend is unreachable passes WorkManager's connectivity
> constraint and `syncNow`'s `isOnline` check (MEASURED, `SyncStatusImpl.syncNow` guards on
> connectivity, not on reachability). A pre-push increment would charge every pending row an
> attempt on every such drain, and after five drains the entire outbox would be FAILED without a
> single byte ever reaching the server.

So attempts count round trips the backend actually answered. The crash case is bounded separately
and already was: a crash mid-push leaves the in-flight batch persisted, the next drain resumes
under the **same** `batch_id`, and the backend's 24-hour idempotency replay returns the stored
envelope, which produces the ack that charges the attempt. A crash that produces no ack charges
nothing, which is correct, because nothing was learned.

This is atomic with the state write: one statement reads `syncAttemptCount`, decides the cap, and
writes state, code, message and count together, so no second round trip can race it.

### Termination of `drainLocked`, every case

Two independent guards; the loop needs only one:

1. **`RETRY_MIN_INTERVAL`, in SQL.** Every ack stamps `lastSyncAttemptAt`, and every drain query
   excludes a row stamped more recently than the cutoff. An acked RETRYABLE row is ineligible for
   five minutes.
2. **The `attempted` set, in memory.** A row whose ack did NOT apply (the guarded UPDATE matched
   zero rows because a clinical edit changed `localModifiedAt` mid-flight) keeps its old timestamp
   and is still eligible, so guard 1 does not cover it. That case predates S-2 and could already
   spin. A pass that yields no record this drain has not already attempted ends the loop, which
   bounds it at one pass per distinct record id.

Cases: **no rows** returns immediately; **all SYNCED/CONFLICT/FAILED** leave the predicate and the
set shrinks; **RETRYABLE** is excluded by guard 1, and by guard 2 if guard 1 somehow does not
apply; **ack did not apply** is excluded by guard 2; **send fails** returns the failure. Proven by
test, not by this paragraph: `the drain terminates while a RETRYABLE row is still in the queue`,
and mutation M2 below.

---

## STEP 4: tests and the mutation checks

### Mutation checks: break it, confirm red, restore, confirm byte-identical

| # | Mutation | Result |
|---|---|---|
| M1 | A `RETRYABLE` ack mapped back to `FAILED` | **RED**, 6 tests |
| M2 | The in-drain `attempted` guard deleted | **RED**, 4 tests |
| M3 | Attempt cap neutered in all 21 `applySyncResult` (parameter kept bound, so it still compiles) | **RED**, 1 test |
| M4 | All 20 drain predicates reverted to `syncState = 'PENDING'` | **RED**, 1 test |
| M5 | `requeueFailed` no longer resets the attempt budget | **RED**, 1 test |
| M6 | Attempt count no longer reset on a successful sync | **RED**, 1 test |
| M7 | `consultation_documents` wired into the drain | **RED**, 1 test |
| M8 | `MIGRATION_20_21` omits `consultation_documents` | **RED**, 1 test |
| M9 | `RETRY_MIN_INTERVAL` gate removed from the drain predicate | **RED**, compile (Room rejects the now-unused `:retryEligibleBefore`) |
| M10 | `RETRYABLE` removed from the `SyncState` enum | **RED**, compile (non-exhaustive `when`) |

All ten restored. Verified byte-identical with `diff -rq` against snapshots taken before the
mutation pass: the DAO directory, `Migrations.kt`, `SyncState.kt`, `SyncAckMapping.kt` and
`SyncOutboxDrainer.kt` all report identical, and the suite is green.

### The finding: two mutations went green, and that is why a new test exists

**M3 and M4, on their first run, left the entire JVM suite green.** The behaviour tests run
against `FakeSyncOutboxRepository`, which re-implements the retry policy in Kotlin; the real DAO
SQL is covered only by instrumented tests, which need a device. So between the fake and the device
there was nothing at all, and the fake was agreeing with itself.

That is the fifth instance in this sequence of a check reporting satisfied while the thing it
checked had changed underneath it. `SyncDaoSqlContractTest` (8 tests) closes it by reading the DAO
sources as text and asserting the 21 statements say the right thing, with the counts pinned so
adding a table is a deliberate act. It is crude on purpose and its KDoc says what it does not
prove: it cannot show SQLite agrees, only that the SQL was not quietly changed. A crude guard that
runs beats a precise one that does not.

M8 then found the same shape in the migration: omitting one `addRetryColumns` line was invisible
to the JVM suite, and it would make every existing install fail to open the database after the
upgrade. Now guarded by cross-checking the migration's table list against the entities' own
`tableName` annotations.

**That new guard caught a real bug in itself on its first run**, which is the best evidence it
works: the entity regex only matched single-line `@Entity(tableName = "...")`, so
`ConsultationDocumentEntity`, whose annotation wraps across lines, was invisible and reported as a
migration line with no entity behind it.

### What the compiler and tests caught that reasoning did not

1. **The resume path escaped the drain-termination guard.** `the attempt count survives a crash
   mid-push` failed with "expected 1 but was 2". `resumeInFlightBatch` ran before the `attempted`
   set existed, so a row it acked as RETRYABLE was picked straight back up by the first loop pass
   and charged twice in one drain. In production guard 1 would have hidden it, and only the fake
   (which has no clock) exposed it. Fixed by having the resume return its members and seeding the
   set with them, which is also why the guard must not be allowed to lean on the SQL.
2. **A non-exhaustive `when` in `FakeSyncOutboxRepository`** that adding `RETRYABLE` to the enum
   turned into a compile error, forcing the fake to state what a retryable ack does rather than
   silently falling through.
3. **Room rejects an unused bound parameter**, which is why M9 is a compile failure rather than a
   test failure. A free guard nobody wrote.
4. **`git checkout --` is not a restore during a mutation pass.** Restoring the DAO directory that
   way during M3/M4 reverted it to HEAD, discarding every S-2 DAO edit, and the next mutation then
   "passed" against stale result XML from the previous run. Caught by the next compile. The
   remaining mutations were redone with file snapshots and a harness that treats an absent results
   directory as RED rather than as zero failures. Recorded because the measurement harness having
   the same failure mode as the thing it measures is worth one line in a record.

### Totals

```
app:          ./gradlew :app:testDevDebugUnitTest
              623 tests, 0 failures, 0 errors     (615 before the SQL-contract test, 605 before S-2)
              compileDevDebugKotlin / StagingDebug / ProdRelease / DevDebugAndroidTest: clean
              Room exported app/schemas/.../21.json

backend/core: ABDM_MODE=stub ./.venv/bin/python -m pytest tests/ -q
              287 passed, 0 failed     (unchanged; S-2 touches no backend code)
```

**No pre-existing failures.** Nothing in either suite was failing before this change set and
nothing is now.

---

## Carry forward, not acted on

- **`MigrationTest19To20` (S-4) and `MigrationTest20To21` (S-2) are both written, compiled and
  unrun**, for want of a device. Every schema claim in both is therefore INFERRED.
- **The one-time re-push of existing FAILED rows** is owner-gated and now unblocked by S-1's
  absence verdict. It does not ship here and this migration deliberately leaves those rows alone.
  Worth noting that it is now also *smaller* than it was: rows failing from today forward for a
  retryable reason will not become FAILED in the first place.
- **The 404 audit-row gap** (no `KERNEL_CALL_FAILED` for the `_resolve_case_record` path) remains
  standalone, not S-1 or S-2 scope.
- **The strings.xml convention fork** from S-4 is undecided.
- **`sync_log.retry_class` is still not stored** backend-side, from S-1.
- **`toLocalSyncState`'s unknown-status branch still throws.** S-1 deferred it to S-3 because no
  option was safe until the drain loop was bounded. It now is: with the `attempted` guard and the
  attempt cap, skipping an unknown status can no longer spin. **S-3 can now make it a recorded
  skip**, which was not true when S-1 looked at it.
