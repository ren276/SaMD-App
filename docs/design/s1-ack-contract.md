# S-1: the ack contract and its mirror

Tree at a219da9 plus the four pre-existing uncommitted changes, PR-0 through PR-3, the F6C fixes,
S-5, S-5b and S-4. All left exactly as they were. Nothing committed. No credential file opened,
no ABDM gateway contacted, `docs/quality/risk-management-file.md` untouched. Every backend test
run in this session used `ABDM_MODE=stub` on the command line.

No Room entity and no drain query was edited. Scope held to Group A.

MEASURED = observed from a run in this session. INFERRED = read from source, not executed.

---

## STEP 0: THE ABSENCE VERDICT

The gate, and the thing the owner's migration decision rests on. Measured with a throwaway probe
(`tests/test_zz_absence_probe.py`, five tests, deleted after measuring) driving real rejections
through the real API against real PostgreSQL, then reading the committed rows back on a fresh
session.

### The mechanism, established rather than assumed

`_apply_one` wraps each record in `async with session.begin_nested()` (`sync.py`, the
`begin_nested` at the apply site) and catches `IntegrityError`/`DataError` OUTSIDE that block, so
the savepoint has already been rolled back by the time `_reject` is called. Sixteen of the
seventeen reject sites are `return` statements reached BEFORE any `session.add`, `setattr` or
`flush`, so their savepoint is released holding nothing. The seventeenth is the constraint
handler.

**Nothing in `sync.py` or `app/api/v1/sync.py` calls `session.commit()`.** MEASURED by grep: the
only writes are four `session.flush()` calls, and the module docstring states explicitly that
`write_out_of_band` is not used here. The single commit is `session_scope`'s, after `push()`
returns.

### Every path

| Path | Record afterwards | Mechanism |
|---|---|---|
| 16 pre-write `_reject` sites | **ABSENT**, guaranteed | Return precedes every write; savepoint releases empty |
| `_reject` at the constraint handler, 23502 / 23503 / 23505 / 23514 | **ABSENT**, guaranteed | Savepoint rollback, MEASURED below |
| Savepoint rollback with applied siblings | Rejected record **ABSENT**, siblings **PRESENT** | MEASURED: per-record savepoints, by design |
| Connection drop mid-batch | **ALL ABSENT**, including already-applied siblings | Nothing commits mid-batch; `session_scope` rolls back the whole request |
| Response lost after commit | **PRESENT** | The transaction committed. Re-push under the SAME `batch_id` is a verbatim replay |
| Whole-batch 4xx (device mismatch, oversize, unknown table) | **ALL ABSENT**, and no `sync_batches` row | MEASURED: raised before `batch_row` is added |
| `TypeError` at the unvalidated-table guard, `RuntimeError` on a missing `response_json`, an exception out of `audit_service.append` | **ALL ABSENT** | Propagates past the savepoint and past `session_scope` |
| Oversized record failed locally on the device | **ABSENT** | Never sent. See the second FAILED writer below |

### The load-bearing measurement

An UPDATE that violates 23505 runs `setattr` over every column and `existing.server_version += 1`
BEFORE the flush that raises. The question the brief refused to let me assume: does the ORM object
stay dirty after the savepoint rollback, so that a LATER record's `flush()` in the same request
re-emits it?

Probe: seed patient A with an ABHA, seed patient B with a different one, then one batch that
(1) updates B to take A's ABHA and rename it, and (2) applies a clean patient C, forcing another
flush in the same transaction.

```
PROBE upd result: patients PATBBBB0002 rejected SAMD-SYNC-6003 this record already exists with different data.
PROBE upd result: patients PATCCCC0003 applied
PROBE PATBBBB0002 abha:           55555555555555     <- unchanged
PROBE PATBBBB0002 name:           Other Person       <- unchanged, the rename did not leak
PROBE PATBBBB0002 server_version: 1                  <- the += 1 did not leak
PROBE PATCCCC0003: PRESENT
```

**No leak.** `begin_nested`'s rollback restores the identity-map snapshot, the mutations are gone,
and the next record's flush does not re-emit them. Sibling isolation holds in the same run.

### Two more things the probe established

- A rejected record still gets a `sync_log` row carrying both `code` AND `message`. MEASURED. So
  the reason is durable server side even though the device currently discards it.
- A whole-batch 4xx leaves **zero** `sync_batches` rows, so the `batch_id` is not burned and a
  corrected resend under the same id is not mistaken for a replay.

### VERDICT

**No path is indeterminate. Every record type can safely be re-pushed once.**

More precisely, and this is the form the migration decision needs:

1. **A row the device marked FAILED is guaranteed absent server side.** There are exactly two
   writers of `SyncState.FAILED`. One is a `rejected` ack, which can only arrive in a response the
   device actually received, which means the transaction committed, which means the rejection is
   durable and (measured) the record is absent. The other is
   `SyncOutboxDrainer.failOversizedRecordsLocally`, which synthesizes a `rejected` ack for a record
   that was never sent at all. Both are absent. This directly answers the question S-5 raised and
   the answer is the safe one, unlike in the ABDM adapter.
2. **Re-push is safe even for the PRESENT case.** Every clinical table is an upsert with
   last-write-wins, so a re-push of an already-applied row acks `stale` or bumps the version;
   `audit_log` is guarded by the partial unique index on `sync_log(table_name, record_id)` and acks
   `duplicate` without growing the chain. The append-only chain cannot be double-written.
3. **The `batch_id` is stable across a crash.** `InFlightBatchStore` persists it before the network
   call and `SyncOutboxDrainer.resumeInFlightBatch` reuses it; an unreadable store returns
   `Result.failure` and the drainer aborts the whole drain rather than minting a fresh id
   (MEASURED by reading both: the store's KDoc states the rule and the drainer's `getOrElse`
   honours it).

Not stopping. Re-push is safe generally.

---

## STEP 1: the ack contract

### The vocabulary

`SyncRetryClass` in `backend/core/app/models/enums.py`, immediately above `SlmCallOutcome`, named
after `KernelCallOutcome`, `SlmCallOutcome` and S-4's device-side `KernelFailure`. A separate enum
for the reason `SlmCallOutcome` is separate from `KernelCallOutcome`: those answer "what happened
to a call", per hop, for an operator. This answers "what should the outbox do with this row", for
a device.

| Value | Meaning |
|---|---|
| `RETRYABLE` | Resending identical bytes later can succeed. The cause is outside this record and can change. |
| `TERMINAL` | Resending identical bytes fails identically, forever. Only a device-side change helps. |
| `CONFLICT` | Another present record claims what this one claims. A person must decide which is right. |

Three, because only three outbox behaviours are distinguishable. `CONFLICT` is not a retry
distinction; it is a screen distinction, and it exists to resolve the 23505 question below.

### All seventeen sites

Sixteen are uniformly `TERMINAL`. The seventeenth fans out by sqlstate.

| Site | Condition | Class |
|---|---|---|
| `_apply_generic` | forbidden field (`ailments.audio_local_uri`) | TERMINAL |
| `_apply_generic` | `sending_phc_id` is not the caller's facility | TERMINAL |
| `_apply_generic` | unexpected field | TERMINAL |
| `_apply_generic` | id belongs to another facility | TERMINAL |
| `_apply_generic` | `payload_json` invalid JSON | TERMINAL |
| `_apply_audit_log` | unexpected field | TERMINAL |
| `_apply_audit_log` | **unknown audit action** | **RETRYABLE** |
| `_apply_audit_log` | invalid timestamp | TERMINAL |
| `_apply_audit_log` | `user_id` required | TERMINAL |
| `_apply_audit_log` | `payload` not a string | TERMINAL |
| `_apply_audit_log` | `patient_id`/`case_record_id` not a string | TERMINAL |
| `_apply_one` | `id` required | TERMINAL |
| `_apply_one` | unsupported `op` | TERMINAL |
| `_apply_one` | `data` required | TERMINAL |
| `_apply_one` | `base_version` not an integer | TERMINAL |
| `_apply_one` | invalid `client_updated_at` | TERMINAL |
| `_apply_one` | constraint violation | **by sqlstate** |

Sqlstate fan-out: `23502` TERMINAL, **`23503` RETRYABLE**, **`23505` CONFLICT**, `23514` TERMINAL,
anything else TERMINAL.

### 23503 confirmed

Confirmed as the diagnosis found it, and it is the row that matters. `push()` sorts a batch by
table rank, so a parent in the SAME batch always applies first; a 23503 therefore means the parent
genuinely is not on the server, which is a fact about the world that changes, not a fact about
this record. Today it is terminal and the child is destroyed permanently.

### 23505 resolved to CONFLICT, and the argument

The diagnosis said "sometimes retryable". Resolving it required knowing what can actually raise it.
MEASURED by grepping every `unique=True` and `UniqueConstraint` in `app/models/`: exactly **two**
unique constraints are reachable from a client-pushed row.

- `ix_patients_abha_number`. Another patient already holds this ABHA. This is the duplicate-ABHA
  case, the whole reason this sequence exists. Resending unchanged never succeeds and the remedy
  is a human decision about which patient's ABHA is wrong. Not retryable.
- `uq_medication_lines_position` on `(prescription_id, position)`. This one **is** theoretically
  transient: two lines swapping positions inside one prescription, in one batch, where within-table
  order is the array order the device sent. So the "sometimes" was real and this is where it lived.

Every other unique index is on a server-generated column (`audit_events.sequence`) or is the
`sync_log` dedup index, which `_apply_audit_log` handles internally and which never reaches this
site.

**Resolved to CONFLICT uniformly.** A sqlstate cannot tell the two apart; the first is the case
that matters and is definitively not retryable; and the second is no worse off than today, where
it is already terminal. The upgrade, if the second ever matters, is per-CONSTRAINT-NAME
classification at that one site, not a fourth retry class. Written into the code comment so the
next reader does not redo this.

### The correction: a second misclassification among the other sixteen

The brief said to check the sixteen again. One is misclassified, and the diagnosis did not name it.

**An unknown `audit_log` action is RETRYABLE, not terminal.** `DEVICE_AUDIT_ACTIONS` is the
backend's copy of a vocabulary the DEVICE owns and which is explicitly expected to grow (that is
why `AuditActionBackendMirrorTest` exists). A device rolled out ahead of the backend sends an
action this build has not learned. Terminal rejection there destroys an append-only audit row
permanently and leaves a hole in the chain, and the remedy (upgrade the backend) needs nothing
from the device: the identical bytes then apply. Same integrity class S-5 found in the ABDM
adapter, and the audit-action mirror's own test already documents this exact chain as "silent
permanent loss".

### A limitation accepted rather than fixed, for the owner to overrule

The same "device ahead of backend" argument applies to the two **unexpected field** sites. A device
shipping a new column before the backend migration would have every row of that table destroyed
rather than waiting. Left TERMINAL, because an unexpected field is far more likely a device defect
than a rollout skew, and retrying every row of a table forever is a worse failure mode than losing
them visibly. Unlike the audit vocabulary, there is no expectation that the device leads here.
Recorded because it is a judgement, not a fact.

### Not done: `sync_log.retry_class`

The reason is durable in `sync_log` as `code` + `message`, but the class is not stored. Adding it
is a backend migration and buys an operator a column they can already derive from `code` plus the
sqlstate. Skipped deliberately.

### Contract

`docs/backend/api-contract.md` section 6.1 updated in this change set: the example response, a
`retry_class` table with the client rule for each value, the explicit "MUST NOT infer it from
`code`", the "unknown or absent means TERMINAL" rule, the current classification by cause, and a
rewritten Android handling rule. The old rule said flatly "mark it as permanently failed and stop
retrying on `rejected`, since a malformed row will stay malformed forever"; that sentence was the
contract-level statement of the defect.

---

## STEP 2: the device side

- `SyncResultDto` gains `@SerializedName("retry_class") val retryClass: String?`.
- `domain/model/SyncRetryClass.kt`: the mirror enum, plus `parseSyncRetryClass` and a named
  `CONSERVATIVE_SYNC_RETRY_CLASS`. Three values and no `UNKNOWN` sentinel: an absent or
  unrecognised wire value is `null`, handled at the single parse point, so the enum stays exactly
  the backend's set and the mirror test stays a plain set comparison rather than one with a
  carve-out.
- `SyncAckMapping` gains `retryClassOrConservative()`, total over every input by construction.

### S-1 changes no state mapping, deliberately, and this is the safety property

`toLocalSyncState` still maps every `rejected` to `FAILED` regardless of `retry_class`. Acting on a
`RETRYABLE` rejection needs a `SyncState` value that does not exist yet (S-2) and a drain that
re-collects it (S-3).

This is not caution for its own sake. MEASURED by reading `SyncOutboxDrainer.drainLocked`: it is a
`while (true)` loop that re-collects PENDING rows until none remain. A row that a `RETRYABLE` ack
left PENDING would be re-collected and resent inside the same drain call, forever, without ever
terminating. **Wiring the behaviour here, before S-2 and S-3, would convert a permanently-lost row
into a hung drain.** So the brief's warning about a partial landing resolves the other way than it
might look: this PR is safe to land alone precisely because it is inert, and `SyncRetryClassMirrorTest`
has a test that pins the inertness so a later change cannot half-enable it.

### `message`: not persisted, and the minimum split

MEASURED: every `applySyncResult` DAO signature is
`(id, syncState, serverVersion, code, attemptAt, sentLocalModifiedAt)`. There is no `message`
parameter anywhere, so the field is genuinely discarded at the seam as the diagnosis found.

Persisting it needs a column on all twenty syncable entities plus a migration. So does persisting
`retry_class`. Both are schema, which this PR's scope excludes. **Not expanded silently.**

Proposed minimum split, for S-2:

> One migration adding two nullable columns, `syncRetryClass TEXT` and `syncErrorMessage TEXT`, to
> the twenty syncable entities; the `applySyncResult` signature gains both; `applyAck` passes
> `retryClassOrConservative().name` and `result.message`. Device-local, absent from every
> `*SyncPayloadDto`, exactly as S-4 did with `kernel_reports.failureCode`. That is the whole of
> the persistence step, and it is separable from S-2's new `SyncState` values.

### The `applyAck` else branch

Was `error("Unknown sync table ...")`. Now a recorded, skipped ack: appended to a `skippedAcks`
list and logged. The throw was worse than it looks. It escaped `applyAck`, so **every ack after it
in the same batch was never applied either**, valid ones included, and `InFlightBatchStore` was
never cleared. Skipping is safe here for two independent reasons: an unknown table means this
device has no DAO and therefore no local row to update, so nothing that exists goes unwritten; and
the rows behind any lost acks stay PENDING and resend, which Step 0 establishes is safe.

`toLocalSyncState`'s unknown-**status** branch is a different question and is left alone: see
"carry forward" below.

### The second FAILED writer, which the diagnosis did not name

`SyncOutboxDrainer.failOversizedRecordsLocally` synthesizes a `rejected` ack for a record too large
to send, with a device-invented code `SAMD-SYNC-RECORD-TOO-LARGE` that is in no backend registry.
It is the only `rejected` the backend never produces. It now sets `retryClass = TERMINAL` explicitly
rather than inheriting the conservative default, so this path's own reasoning lives at this path.
Found by grepping for FAILED writers while building the Step 0 verdict, not by the diagnosis.

---

## STEP 3: the mirror tests, and the one that did not work

Backend: `backend/core/tests/test_sync_retry_class_mirror.py`, 7 tests. It walks `sync.py`'s **AST**
rather than regexing it, counts exactly 17 `_reject` calls, and asserts each one's fifth argument
is either a real `SyncRetryClass` member or the one approved classifier helper. AST rather than
regex specifically because these sites are now heavily commented and several comments name a
`SyncRetryClass` value in prose; `ast.parse` discards comments by construction, which is a stronger
version of the "strip comments first" fix the audit-action mirror needed. `_reject` also takes
`retry_class` as a required parameter with no default, so omission is already a `TypeError` and a
mypy error; the test asserts the thing a signature cannot.

Device: `app/src/test/.../SyncRetryClassMirrorTest.kt`, 7 tests, shaped after
`AuditActionBackendMirrorTest`. Reads the Python enum as text, comments stripped, bounded to the
class body so a neighbouring `StrEnum` cannot bleed in.

### Verified negatively, in all four combinations

| Drift | Backend suite | Kotlin suite |
|---|---|---|
| Backend enum gains `DRIFTED` | FAILED | FAILED |
| Device enum gains `DEVICE_ONLY_DRIFT` | FAILED | FAILED |

MEASURED by actually making each edit and running both suites, then restoring. A mirror test that
has never been seen to fail is a mirror test that might be comparing two empty sets.

### What that exercise caught, and reasoning did not

**The device mirror test did not run at all.** On the first negative check, drifting the backend
enum and running the Kotlin suite produced `Task :app:testDevDebugUnitTest UP-TO-DATE` and a stale
green result on disk. Gradle had no idea the test reads `backend/core/app/models/enums.py`, so a
backend-only change never invalidated the task.

This is worse than the problem PR-2 fixed. PR-2's correction was "a guard that only runs under
pytest is not a guard for contributors running the Kotlin suite". This guard was in the Kotlin
suite and still did not run. It would have reported green through exactly the drift it exists to
catch.

Fixed in `app/build.gradle.kts` by declaring both backend mirror files as optional inputs to
`tasks.withType<Test>`, next to the `src/main` input the egress-proof tests already declare. After
the fix, the same drift produces `Task :app:testDevDebugUnitTest FAILED`.

**The pre-existing `AuditActionBackendMirrorTest` has had the identical hole since it was written**,
for the identical reason, and it reads `backend/core/app/domain/audit_actions_device.py`. That file
is listed in the same `inputs.files` call, so both guards are now live. That test was the model for
this one, and copying it faithfully would have copied the hole.

---

## STEP 4: verification

```
backend/core:  ABDM_MODE=stub ./.venv/bin/python -m pytest tests/ -q
               287 passed, 0 failed          (280 before this PR, +7 new)

app:           ./gradlew :app:testDevDebugUnitTest
               605 tests, 0 failures, 0 errors   (598 before this PR, +7 new)

ruff check / ruff format --check   clean on sync.py, enums.py, the new test
mypy app/services/sync.py app/models/enums.py   Success: no issues found
```

**No pre-existing failures.** Every backend test run in this session used `ABDM_MODE=stub` on the
command line. No test contacted the ABDM gateway.

---

## Carry forward, not acted on

**The backend writes no `KERNEL_CALL_FAILED` row for the 404 path.** `_resolve_case_record` raises
before `_forward` is entered, and `KERNEL_CALL_FAILED` is written inside `_fail`, which only
`_forward` calls. Every other kernel failure has an audit row and this one does not, and it is the
terminus of the duplicate-ABHA chain. **Recommendation: standalone, not S-1 scope.** It is an audit
completeness fix in `services/kernel.py` with no dependency on the ack contract, the new
`SyncState` values or the drain, so folding it into S-2 or S-3 would only couple it to a migration
it does not need.

**`toLocalSyncState`'s unknown-status branch still throws.** Left alone deliberately, because none
of the three options is safe until S-3 changes the drain loop: throwing wedges the outbox and drops
valid acks in the same batch (today's behaviour); mapping to FAILED is permanent loss; skipping
leaves the row PENDING and `drainLocked`'s `while (true)` never terminates. The third only becomes
available once the drain has a bounded retry. **S-3 item.**

**`MigrationTest19To20` from S-4 is written and unrun**, no device attached.

**The strings.xml convention fork from S-4 is undecided.**

**`sync_log.retry_class` is not stored**, see Step 1.

**The two unexpected-field sites are TERMINAL by judgement**, see Step 1, owner may overrule.
