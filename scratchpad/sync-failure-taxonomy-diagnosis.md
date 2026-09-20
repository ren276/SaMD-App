# Sync failure taxonomy, pass 1 of 2: diagnosis

**Status:** READ-ONLY diagnosis. **No source file was changed.** Nothing committed, no branch, no
stash. The only write is this memo. The four pre-existing uncommitted changes, PR-0 through PR-3 and
the F6C fix pass are all present and untouched. `docs/quality/risk-management-file.md` was not
edited: three hazard items remain pending sign-off there.

**Date:** 2026-09-19
**Tree:** SaMDApp @ `a219da9` plus the uncommitted work above, main machine, authoritative.
**Method:** static read of Kotlin, FastAPI and the ABDM adapter. No ABDM test was run and no gateway
was contacted. No credential file was opened.

**On anchors.** Every figure carried in from a previous audit was re-derived here against the
pattern rather than the line. Two carried figures were wrong, and one symptom turns out not to be the
defect it was filed as.

---

## Symptom 1: the FAILED row

### 1.1 The path, PENDING to FAILED, hop by hop

| # | Hop | Anchor |
|---|---|---|
| 1 | A syncable mutation writes the row `PENDING` | the `syncState` column on each entity |
| 2 | The drain collects it | `RoomSyncOutboxRepository.kt:58-77`, one `getPendingForSync()` per table |
| 3 | The batch is pushed | `SyncPushApiService`, `POST /api/v1/sync/push` |
| 4 | The backend applies each record under its own savepoint | `backend/core/app/services/sync.py` |
| 5 | A per-record failure becomes an ack | `services/sync.py:238-245`, `_reject(...)` sets `"status": "rejected"` |
| 6 | The device maps the ack to a local state | `data/sync/SyncAckMapping.kt:14`, `"rejected" -> SyncState.FAILED` |
| 7 | The state and the error code are written to the row | `RoomSyncOutboxRepository.applyAck():80-106`, one `applySyncResult(...)` per table |
| 8 | No drain ever selects it again | every drain query filters `syncState = 'PENDING'` |

**(MEASURED)** All eight confirmed in the current tree.

### 1.2 Corrections to the carried figures

**"16 DAOs drain PENDING" is right as a file count and wrong as a table count.**
**(MEASURED)** 28 `syncState = 'PENDING'` queries across 16 DAO files. But `collectPending`
(`RoomSyncOutboxRepository.kt:58-77`) collects **20 tables**, and `applyAck`'s `when` has **20
branches**. The mismatch is one file: `ConsultationDocumentDao.kt`.

**`consultation_documents` has the entire outbox apparatus and is wired to nothing, deliberately.**
**(MEASURED)** It has `getPendingForSync` (`:47`), `applySyncResult` (`:56`) and the sync columns,
and it is absent from `collectPending`, absent from `applyAck`'s `when`, absent from
`observeFailedCount`, and unknown to the backend (`grep consultation_documents
backend/core/app/services/sync.py` returns zero hits). **This is not a defect.** The DAO says so at
`:24-29`: the outbox shape is "present for the row's own consistency" but "**not wired into the
sync-push sweep in Build 3a**: no backend table or endpoint for `consultation_documents` exists yet".
The Build 3a README repeats it. Document bytes are a pre-production gate.

Worth recording anyway, because it is a live trap: `applyAck`'s `else` branch is
`error("Unknown sync table ...")`, so the day a backend starts acking that table before the device
adds its branch, the drain throws rather than degrading. **(INFERRED)**

**"Twenty DAOs have `observeFailedSyncCount`" is right, with a naming wrinkle.**
**(MEASURED)** `observeFailedCount` (`:108-121`) combines **20 counters**. Two of them are on
`PrescriptionDao` under different names (`observePrescriptionFailedSyncCount`,
`observeMedicationLineFailedSyncCount`), which is why a literal grep for the common name returns 15
files rather than 20 counters. Coverage is complete over the 20 pushed tables.

**Confirmed unchanged: zero requeue paths, zero `failedCount` renders.**
**(MEASURED)** `grep -riE "FAILED.*->.*PENDING|retryFailed|resetFailed|requeue"` over `app/src/main`:
**0 hits.** `grep failedCount` under `presentation/`: **0 hits.** It exists only in
`domain/sync/SyncStatus.kt` and `data/sync/SyncStatusImpl.kt`.

### 1.3 The finding the previous audit did not name: the cause is already on the device

**(MEASURED)** Every entity carries a `syncErrorCode: String?` column. Every `applySyncResult`
writes it. `applyAck` passes `result.code` into it on all 20 branches.

**Nothing ever reads it. `grep syncErrorCode` across `app/src/main` returns zero SELECT statements.**

So the per-row cause of failure is **already persisted, per row, on the device**, and has been since
the outbox landed. The data needed to triage a FAILED row is not missing. It is stored and unread.
That materially changes the shape of the fix: surfacing does not require a new column, a new sync
round trip, or a backend change.

**(MEASURED)** A second field is lost, though. `SyncResultDto` (`SyncPushDto.kt:47-55`) carries
`code`, `message`, and `serverState`. `applyAck` persists **`code` only**. The human-readable
`message`, which is the field that says "this record already exists with different data" rather than
just `SAMD-SYNC-6003`, is discarded at the seam and never stored.

### 1.4 How many distinct causes are collapsed into one state

**(MEASURED)** Seventeen `_reject(...)` call sites in `services/sync.py`. Every one of them except
the forbidden-field case emits the **same** code, `SAMD-SYNC-6003` (`ErrorCode.SYNC_RECORD_INVALID`).

| Cause | Anchor | Retryable unchanged? |
|---|---|---|
| Forbidden field present (for example `ailments.audio_local_uri`) | `:265`, code `SAMD-SYNC-6006` | no |
| Unexpected field, generic table | `:281` | no |
| Unexpected field, audit_log | `:383` | no |
| Unknown audit action | `:387-389` | **no, and this is the mirror-drift trap** |
| Invalid timestamp | `:394-396` | no |
| `user_id` required | `:400` | no |
| `payload` must be a string | `:404-406` | no |
| Field must be a string | `:410-412` | no |
| `id` belongs to another facility | `:290-291` | no |
| `payload_json` invalid JSON | `:322-323` | no |
| `id` required | `:468` | no |
| Unsupported `op` | `:470` | no |
| `data` required | `:472` | no |
| `base_version` not an integer | `:474-476` | no |
| Constraint violation, `23502` missing required field | `:511` via `_constraint_message` | no |
| Constraint violation, `23503` FK not present yet | `:511` | **YES. See below** |
| Constraint violation, `23505` duplicate | `:511` | sometimes |
| Constraint violation, `23514` check failed | `:511` | no |

**(MEASURED)** The four constraint cases share one exit (`:511`) and one code, and their distinct
messages come from `_SQLSTATE_MESSAGES` (`:122-127`), which is precisely the field `applyAck`
discards.

**(INFERRED, and it is the important row.) `23503`, a foreign key whose parent does not exist yet, is
ordinarily retryable and is currently terminal.** A child row pushed before its parent landed is not
malformed. It is early. The Phase 6b decision that made `rejected` terminal is correct for
"malformed, stop retrying forever" and is wrong for this one case, which the same code path produces.
That single sqlstate is the difference between a permanent loss and a row that would have synced on
the next drain.

### 1.5 What a worker can observe today

**Nothing.** **(MEASURED)** No screen reads `failedCount`, no screen reads `syncErrorCode`, no
per-row sync badge exists, and there is no retry affordance for a FAILED row. A record that the
server refused looks, from every surface in the app, exactly like a record that synced.

### 1.6 What the data loss actually is

**(MEASURED)**

- **The local row survives.** `applySyncResult` updates the sync columns only. Clinical content is
  untouched and remains readable in the app.
- **The server never receives it, ever.** No drain re-collects it and no code path resets the state.
- **The subtree goes with it.** A rejected parent means dependent rows fail their own FK check
  (`23503`) and go FAILED by the same path, so losing one patient row loses its case records, its
  consultations and everything hanging off them.
- **Recoverable by a worker: no.** There is no affordance.
- **Recoverable by an operator: yes, in principle, and only off-device.** The device database is
  SQLCipher-encrypted with a non-exportable Keystore key, so recovery means a manual intervention on
  the handset, not a query. **(INFERRED)** No tooling for this exists.

---

## Symptom 2: the blanket catch

### 2.1 What reaches `GenerateKernelReportUseCase.kt:250`

**(MEASURED)** The `try` opens at `:148` and wraps `remoteKernelSource.assess(...)` plus the result
handling through `:247`. `RetrofitKernelSource`'s own KDoc (`:19-20`) states that it never swallows
exceptions and lets `IOException` / `HttpException` propagate.

| Class | Arrives by | Currently becomes |
|---|---|---|
| `java.net.UnknownHostException` | no DNS, offline | `UNAVAILABLE` |
| `java.net.ConnectException` | host down, refused | `UNAVAILABLE` |
| `java.net.SocketTimeoutException` | connect or read timeout | `UNAVAILABLE` |
| `javax.net.ssl.SSLException` | TLS failure | `UNAVAILABLE` |
| `retrofit2.HttpException` 401/403 | expired session, role | `UNAVAILABLE` |
| `retrofit2.HttpException` 404 | `SAMD-ENC-4002`, case not on server | `UNAVAILABLE` |
| `retrofit2.HttpException` 422 | `SAMD-KERN-5003` or `5005` | `UNAVAILABLE` |
| `retrofit2.HttpException` 502/503/504 | kernel unreachable, circuit open, timeout | `UNAVAILABLE` |
| `com.google.gson.JsonSyntaxException` | malformed body | `UNAVAILABLE` |
| `IllegalStateException` / `NullPointerException` | a device-side bug in result handling | `UNAVAILABLE` |

**One correction to the carried anchor. (MEASURED)** The audit quotes `:250-255`. Lines `:248-249`
are `catch (e: CancellationException) { throw e }`, immediately above. Structured concurrency is
**not** broken here; the perf audit's excerpt simply started one line too late. The defect is the
collapse of ten typed classes onto one untyped outcome, and nothing more.

### 2.2 What the worker sees

`InferenceSource` has three values (`domain/model/InferenceSource.kt:12`): `REAL_INFERENCE`,
`MOCK_FALLBACK`, `UNAVAILABLE`. **(MEASURED)** `MOCK_FALLBACK` is dev-flavor only. So in staging and
prod every row in the table above produces the same two strings
(`GenerateKernelReportUseCase.kt:73-75`):

> `"Assessment unavailable"` and `"Assessment unavailable. The AI did not produce a result for this
> case and no diagnosis was generated. Tap Retry to run the assessment again."`

**(INFERRED)** "Tap Retry" is sound advice for six of those ten rows and useless for four. For a 404
caused by an unsynced case, for a 401, for a 422 payload rejection and for a device-side bug, retry
will fail identically and forever, and the copy invites the worker to keep trying.

### 2.3 The pattern, swept

**(MEASURED)** 25 broad catch sites in `app/src/main` (`catch (x: Exception|Throwable)`), plus two
matches that are KDoc prose.

| Verdict | Count | Sites |
|---|---|---|
| **Same defect** | 1 | `domain/usecase/GenerateKernelReportUseCase.kt:250` |
| **Deliberate boundary, correct** | 9 | `data/repository/ResultCatching.kt:13`; `domain/usecase/AssessmentRunner.kt:63`; `domain/usecase/GenerateEvaluateReportUseCase.kt:80`; `domain/usecase/GenerateKernelReportUseCase.kt:193`; `domain/slm/SlmReadbackUseCase.kt:299`; `domain/slm/ApprovedRecordReader.kt:53`; `data/remote/GeminiBrandLookupSource.kt:63`; `data/local/security/DocumentEncryptionProvider.kt:99` and `:126` |
| **Benign** | 15 | the `AndroidDocumentCaptureStore`, `AndroidAilmentAudioRecorder`, `SherpaOnnxTranscriptionService`, `ConsultationDocumentRepositoryImpl`, `DatabasePassphraseProvider`, `DocumentCameraCapture`, `DocumentViewerViewModel` and `SendingViewModel` sites: platform and IO boundaries that already produce a typed local result or a visible UI error |

Three of the "deliberate" entries deserve their reasoning recorded, because they are the proof that
this codebase already knows how to do the thing symptom 2 fails to do:

- **`ResultCatching.kt:13`**, `asDataResult`, is the established repository error boundary, with a
  `CancellationException` rethrow above it, remapping to a typed `DataError`. **(MEASURED)** 32 call
  sites. This is the house pattern.
- **`GenerateEvaluateReportUseCase.kt:80`** catches broadly and then **persists the exception class
  name** as a failure marker (`saveFailure(caseRecordId, e::class.simpleName ?: "UNKNOWN_ERROR")`,
  `:85`) so the failure is readable back and distinguishable from "has not run yet". That is H-14,
  and it is a crude version of exactly what symptom 2 needs. **The evaluate leg already does it and
  the assess leg does not.**
- **`GenerateKernelReportUseCase.kt:193`** wraps only the audit-log write, and its KDoc at `:172-176`
  explains that it is separate specifically so an audit failure cannot fall into the outer catch and
  route the case to the fallback source. That is careful, correct reasoning about this very control
  flow, forty lines above the defect.

---

## Symptom 3: the duplicate-ABHA chain

### 3.1 The chain

| Hop | What happens | Anchor | Correct? |
|---|---|---|---|
| 1 | Quick-fill writes a constant ABHA for every patient | `RegisterViewModel.fillDemoData():179-204`, `DemoPatientProfile.ABHA_NUMBER` | dev affordance |
| 2 | The second patient violates the unique index | `backend/core/app/models/patient.py:73`, `alembic/versions/0002_clinical_tables.py:235` | **yes** |
| 3 | `23505` becomes a `rejected` ack with a PHI-safe message | `services/sync.py:125`, `:511` | **yes** |
| 4 | The patient row goes FAILED and silent | `SyncAckMapping.kt:14`, then symptom 1 | yes, given the Phase 6b decision |
| 5 | The dependent case record fails its FK check and goes FAILED too | `23503`, `services/sync.py:125` | **yes** |
| 6 | `/assess` 404s because the case was never accepted | `services/kernel.py:86-94`, `:449` | **yes, and deliberately 404 not 403** |
| 7 | The 404 is collapsed into `UNAVAILABLE` | `GenerateKernelReportUseCase.kt:250` | **NO** |

**Hops 1 to 6 are correct behaviour.** Several are documented decisions with good reasons:
`_resolve_case_record` returns 404 rather than 403 because confirming existence would leak across the
facility boundary; `_SQLSTATE_MESSAGES` refuses to echo the driver's text because it embeds the
offending value and `detail` must never carry PHI; `AssessmentRunner.kt:73-84` already pushes before
assessing, which fixes the common case.

### 3.2 The exact hop where the true error is destroyed

**`GenerateKernelReportUseCase.kt:250`.** One `catch (e: Exception)`.

At that line the exception is a `retrofit2.HttpException` whose response body is an RFC 9457 problem
document carrying `"code": "SAMD-ENC-4002"`. The catch reads `e.message`, which for an
`HttpException` is the status line, and discards the body.

### 3.3 Does the true error survive anywhere?

**(MEASURED)** Three answers, and the third is the useful one.

1. **In a device log line, partially.** `:254` logs `e.message`, which is `HTTP 404 Not Found`. The
   SAMD code is not in it. Logcat only, not persisted.
2. **In the backend, not at all for this case.** `_resolve_case_record` raises **before** `_forward`
   is entered, and `KERNEL_CALL_FAILED` is written inside `_fail`, which only `_forward` calls. So a
   404 from this path writes **no `kernel_call_log` row and no `KERNEL_CALL_FAILED` audit row**. The
   audit trail that exists for every other kernel failure does not exist for this one.
3. **In the HTTP response, fully, and the device already knows how to read it.**
   **`ProblemDetailDto` exists** (`data/remote/dto/ProblemDetailDto.kt:11-21`) with a `code` field,
   and it is already parsed on two paths: `RetrofitAuthService.kt:57-58` and
   `RetrofitAbhaSource.kt:149-150`. **`RetrofitKernelSource` does not parse it.** Its KDoc says it
   lets `HttpException` propagate untouched.

   Its KDoc also settles the safety question in advance: "`detail` never contains PHI, so it is safe
   to surface directly in UI error text."

**So the fix for symptom 3 is not new machinery. It is the house pattern, already written twice,
applied to a third caller.**

### 3.4 What the worker should see

Not "Assessment unavailable. The AI did not produce a result." Something they can act on, for
example:

> **This patient could not be saved to the server.** Another patient is already registered with the
> same ABHA number. Check the ABHA number on the registration screen, correct it, and send again.
> The assessment will run once the patient is saved.

Three properties that matter: it names the **record** that failed rather than the feature that did
not run; it names the **action** (fix the ABHA number) rather than "Retry"; and it does not mention
the AI, which is working correctly.

---

## Symptom 4: the ABDM adapter KeyError

### 4.1 What the site actually is

**(MEASURED)** `backend/abdm-adapter/abdm_adapter/client.py:155`:

```
token = str(body["accessToken"])
```

Direct index, no envelope validation. The next line, `:156`, reads `int(body.get("expiresIn", 300))`,
with a default. Two adjacent lines, two different idioms.

**(MEASURED)** No Pydantic model validates any gateway **response** anywhere in the adapter. The
models in `schemas.py` describe the adapter's own outputs.

### 4.2 Every unvalidated access

**(MEASURED)** Six, excluding docstrings:

| Site | Field | Covered by a handler? |
|---|---|---|
| `client.py:155` | `accessToken` | **yes** |
| `mapping.py:42` | `ABHANumber` | **yes**, called inside a covered region |
| `errors.py:63` | `code`, `message` | **not unvalidated**: guarded by `isinstance` at `:60-62` |
| `service.py:289` | `txnId` | **no**, outside the try |
| `service.py:359-363` | `ABHAProfile`, `tokens`, `token`, `expiresIn`, `ABHANumber` | **no**, outside the try |

### 4.3 Is this the same defect as 2 and 3? Argued both ways.

**For "same defect":** an unexpected upstream shape becomes an untyped exception, and the caller
cannot tell it apart from a transport failure. Structurally that is symptom 2's sentence.

**Against, and this is correct.** **(MEASURED)** The adapter does the opposite of symptom 2, on
purpose, at a named boundary. `service.py:163-181`, `_result_from_transport_error`, converts exactly
these exceptions into a **typed** `AbdmResult` carrying an `error_code` and a `RetryClass`. Its
docstring names this precise case: "indexing a malformed token response (`body[\"accessToken\"]`)
raises `KeyError`". Six call sites route into it (`:262`, `:284`, `:326`, `:350`, `:429`, `:446`),
each catching `(httpx.HTTPError, [TypeError,] ValueError, KeyError)`. It even refuses to pass the raw
exception text outward, because `str(httpx.HTTPError)` embeds the request URL and a JSON parse error
embeds a slice of the upstream body.

**Verdict: different defect, and the KeyError in the test log is the design working.** A sandbox
gateway returning a body without `accessToken` becomes a typed, retry-classified `502` with a bounded
message and a logged traceback. That is the correct outcome. **The 14 failing ABDM tests are an
environment without gateway credentials, not this bug.**

**Correction, S-5b, 2026-09-19:** the last sentence is wrong and is now known to be wrong. S-5
(`scratchpad/s5-abdm-response-unpacking.md` section 0) measured the actual cause: `app.config
.Settings` reads `ABDM_MODE` from the environment/.env ahead of its own `"stub"` default, and this
tree's `docker-compose.yml` (and/or local `.env`) sets a live `ABDM_MODE`, so the suite makes real
outbound requests to `https://dev.abdm.gov.in`. These are not tests idling in an environment that
lacks credentials; they are failed live calls, and the KeyError is what a live sandbox response
missing `accessToken` produces. "The design working" verdict for the code path itself still holds
(that part of this section is unaffected); only the credentials-environment explanation for why the
suite reaches that path at all is wrong. S-5b added an autouse `ABDM_MODE=stub` guard
(`backend/core/tests/conftest.py`, `_stub_abdm_gateway`, opt-out only via
`SAMD_ALLOW_LIVE_ABDM_TESTS=1`) so this cannot recur silently.

### 4.4 The finding that is real, and it is not the one filed

**(MEASURED)** The response unpacking at `service.py:359-363` sits **outside** every try block. The
`try` at `:335` wraps only the client call; `_fail` is `NoReturn` (`:103-106`), so the except arms
never fall through.

The order of operations is the problem:

```
:355   txn.state = State.OTP_VERIFIED.value
:356   await session.flush()
:357   await _audit(session, worker, txn, AuditAction.ABHA_OTP_VERIFIED)
:359   profile = result.body["ABHAProfile"]      <-- uncaught KeyError lives here
:360   tokens  = result.body["tokens"]
:361   txn.external_token_encrypted = str(tokens["token"])
:362   ... int(tokens["expiresIn"])
:363   ... str(profile["ABHANumber"])
```

**(INFERRED)** A gateway `200` whose body lacks any of those five keys raises an uncaught
`KeyError`, **after** the transaction has been advanced to `OTP_VERIFIED` and audited as verified,
and **without** `_fail` being called. The transaction is therefore never marked FAILED. Whether the
state change survives depends on the session scope's rollback behaviour, which was not measured
here; either outcome is wrong. If it rolls back, the worker gets a 500 and no FAILED marker. If it
does not, the transaction is stuck in a state its own machine says it reached successfully.

`service.py:289` has the same structure with the opposite ordering: the unpack precedes the state
change, so a malformed `txnId` fails before anything is advanced. That asymmetry is almost certainly
accidental. **(INFERRED)**

This is the same class the project's own `CLAUDE.md` rule about asserting persisted rows was written
for, and it is a **state-machine integrity** finding, not a failure-taxonomy one.

---

## Synthesis

### One defect, or not

**Symptoms 1, 2 and 3 are one defect.** **Symptom 4 is not, and the evidence says so plainly.**

The shared defect, stated once:

> **A failure that the system has already classified correctly is written down in a place nothing
> reads, and then re-presented to the worker in a vocabulary that has fewer states than the failure
> had causes.** The backend classifies seventeen reject conditions and emits a message per sqlstate;
> the device stores the code, discards the message, and renders neither. The kernel proxy classifies
> eight outcomes and returns an RFC 9457 document naming one; the device catches the exception,
> reads its status line, and renders "the AI did not produce a result". In both cases the
> information exists, is correct, and travels most of the way. The loss is always at the last hop,
> and always into a vocabulary too small to hold it.

This is why symptom 4 does not belong: the adapter is the one component that **does** convert an
untyped failure into a typed, retry-classified result at a named boundary, with a docstring
explaining why. It is the counter-example, not another instance. Its real defect is unguarded
unpacking after a state transition, which is a different failure of a different kind.

**Forcing all four into one story would be the wrong call.** Symptom 4's fix touches the ABDM
transaction state machine and nothing in the sync taxonomy. Bundling it would put an ABHA state-model
change in a PR whose reviewers are looking at Room DAOs.

### Proposed design

Named after `KernelCallOutcome` and `SlmCallOutcome`, per PR-2's discipline. Not a parallel
vocabulary.

**The device-side state.** `SyncState` gains one value and loses its ambiguity:

| State | Meaning | Terminal? | Who retries |
|---|---|---|---|
| `PENDING` | queued | no | the drain |
| `SYNCED` | server has it | terminal, success | n/a |
| `CONFLICT` | surfaced for review | no | a human, after review |
| `RETRYABLE` | **new.** Rejected for a reason that may clear on its own | no | the drain, with backoff and an attempt cap |
| `FAILED` | rejected for a reason that cannot clear without an edit | terminal until edited | a human, via an explicit action |

**The classification lives on the backend, not the device.** The ack gains a `retry_class` field
alongside `code` and `message`, exactly as the ABDM adapter already carries `RetryClass` on
`AbdmResult`. The device must not infer retryability from a code string, because that is the mirror
drift the project has already paid for twice.

Initial mapping, from §1.4:

| Backend condition | `retry_class` | Device state |
|---|---|---|
| `23503` FK parent not present yet | `RETRYABLE` | `RETRYABLE` |
| `23505` duplicate | `NON_RETRYABLE` | `FAILED` |
| every validation reject | `NON_RETRYABLE` | `FAILED` |
| unknown audit action | `NON_RETRYABLE` | `FAILED` |
| `23502`, `23514` | `NON_RETRYABLE` | `FAILED` |

**What the worker sees.** A sync card on Home, with a count, and a list behind it. Per row: what it
is ("Patient: Sunita Devi"), what happened in plain words derived from the stored code, and one
action. Never a SAMD code in worker-facing text.

| State | Worker text | Action |
|---|---|---|
| `RETRYABLE` | "Waiting to send. Will retry automatically." | none |
| `FAILED`, duplicate | "Another patient already has this ABHA number." | Open and correct |
| `FAILED`, validation | "This record has a problem and could not be saved." | Open and correct |
| `FAILED`, unknown action | "This record could not be saved. Report to support." | Copy diagnostics |

**What the operator sees.** The stored `syncErrorCode` and the newly stored `syncErrorMessage`, per
row, exportable. Plus, on the backend, a `KERNEL_CALL_FAILED`-shaped audit row for the 404 path that
currently writes none (§3.3 item 2).

### Files that must change, and what must be one commit

| Group | Files | Same commit? |
|---|---|---|
| **A. Ack contract** | `services/sync.py` (`_reject` gains `retry_class`), `docs/backend/api-contract.md` §6.1, `SyncPushDto.kt`, `SyncAckMapping.kt`, `RoomSyncOutboxRepository.applyAck` | **Yes, all of it.** This is the PR-2 mirror coupling exactly: a device that reads a field the backend does not send, or a backend that sends a value the device's `when` does not handle, is a silent permanent rejection |
| **B. Schema** | `SyncState` enum, a Room migration adding `syncErrorMessage`, 20 entities, 20 `applySyncResult` signatures, 20 drain queries to include `RETRYABLE` | Yes, internally |
| **C. Requeue** | a backoff and attempt cap in `SyncPushWorker`, a `RETRYABLE -> PENDING` transition | with B |
| **D. Visibility** | `SyncStatus`, `SyncStatusImpl`, a Home card, a failed-records screen | separable, after A to C |
| **E. Kernel error typing** | `RetrofitKernelSource` parses `ProblemDetailDto`, a typed `KernelFailure`, `GenerateKernelReportUseCase.kt:250` classifies, worker copy per class | **Independent of A to D.** Different files, different vocabulary, ships alone |
| **F. ABDM unpacking** | `service.py:289`, `:359-363` moved inside the guarded region or validated | **Independent of everything above** |

### Visible AND re-collectable: both halves, named

F6B-01's point is that neither half is sufficient, so both are stated as separate acceptance
criteria.

- **What makes a FAILED row visible:** `SyncStatus` exposes the count **and** a per-row list; a Home
  surface renders it; each row shows plain-language cause derived from the stored `syncErrorCode` and
  `syncErrorMessage`. Acceptance: a worker who has a rejected record can find out, without being
  told, that they have one.
- **What makes it re-collectable:** every drain query selects `PENDING` **or** `RETRYABLE`; the
  worker-triggered correction path sets an edited `FAILED` row back to `PENDING`; a bounded automatic
  requeue exists for `RETRYABLE` only. Acceptance: a `23503` row that arrived early syncs without a
  human, and an edited duplicate re-enters the queue.

**Neither alone.** Visibility without re-collection is a screen that tells a worker about a loss they
cannot undo. Re-collection without visibility is a row silently re-pushed forever against a server
that will keep refusing it, which is what the Phase 6b decision was right to prevent.

### The migration question, posed and not answered

**(MEASURED)** Rows are already in `FAILED` today, on real devices, written by the current mapping.
They carry a `syncErrorCode` and no `retry_class`, because the backend did not send one.

The question, which is the owner's:

> On upgrade, are existing `FAILED` rows re-examined, or do they stay terminal? Re-classifying them
> means inferring `retry_class` from the stored code on-device, which is the inference this design
> otherwise forbids. Leaving them means shipping a fix that does not repair anything already broken,
> and a worker whose records were lost before the upgrade stays lost.

A third option exists and is worth naming: move every existing `FAILED` row to a one-time
`NEEDS_REVIEW` state that is visible but not automatically re-pushed, so nothing is silently retried
against a server that already refused it, and nothing is silently abandoned either.

**This memo does not choose.** It is a clinical-data-retention decision, not an engineering one.

---

## Sizing

| PR | Scope | Tier | Notes |
|---|---|---|---|
| **S-1** | Group A, the ack contract and its mirror | high | **Must be one commit.** Backend, contract doc, DTO, mapping, applyAck |
| **S-2** | Groups B and C, schema, states, drain queries, bounded requeue | high | After S-1. 20 tables, a migration, and the drain predicate |
| **S-3** | Group D, visibility | medium | After S-2 |
| **S-4** | Group E, kernel error typing | medium | **Ships independently.** Reuses `ProblemDetailDto` |
| **S-5** | Group F, ABDM unpacking | low | **Ships independently and first if desired.** Smallest diff here |

**The four symptoms should not all be fixed together, and the reason is not size.** S-4 and S-5 touch
different vocabularies, different layers and different reviewers from S-1 to S-3. S-5 in particular
is a small correction to a component that is otherwise the best example of typed failure handling in
this codebase, and burying it inside a sync-taxonomy PR would misattribute it. S-4 is the one with
the highest ratio of worker-visible improvement to risk, because the machinery it needs already
exists and is already proven on two other paths.
