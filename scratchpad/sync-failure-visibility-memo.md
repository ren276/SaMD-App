# Sync failure visibility memo: device sync states, Home truth, assessment failure attribution

Drafted 2026-10-06 on master `b46e9a0`. Read-only design memo, no code. It covers the device
sync-failure-visibility PR filed at `PROGRESS.md:5136` (item 1, "Must merge before any pilot use"),
the offline-spinner note at `PROGRESS.md:5242`, and the duplicate-identifier assessment chain at
`PROGRESS.md:5257-5281`. Every repo claim is labelled **MEASURED** (file:line, or a command run in
this session) or **INFERRED**. Out of scope, by ruling: conflict resolution (keep mine or take the
server's, needs a pull path), patient dedupe and merge, and the doctor-review surfacing of
derivation flags (own memo, boundary named in section 3.6).

Two types share the name `SyncState`. In this memo **record state** means the per-row enum
`domain.model.SyncState` (`SyncState.kt:8-34`), and **Home sync state** means the data class
`domain.sync.SyncState` (`SyncStatus.kt:6-22`). The build should keep that distinction in names.

## 0. State re-verified, and conflicts with the brief

### 0.1 Housekeeping performed

| Item | Result | Label |
|---|---|---|
| Delete `docs/pr72-followups` locally and on origin | Already absent. `git branch -a` shows no such local branch; `git fetch --prune origin` then `git ls-remote --heads origin` lists only `fix/sync-before-assess` among the relevant heads. Nothing was deleted because nothing existed (most likely GitHub's delete-on-merge after #73). | MEASURED |
| On master at `b46e9a0`, tree state | `git rev-parse --short HEAD` = `b46e9a0`, branch `master`. `git status --short` shows only the three pre-existing untracked files (`scratchpad/asr-observations.md`, `scratchpad/pr4-design-addendum.md`, `scratchpad/sync-resync-fix-memo.md`). | MEASURED |
| Memo location | `scratchpad/backend-truthfulness-memo.md` is tracked (`git ls-files`, added by `921e7a8`, merged in #66 `afe24c5`). This memo is written beside it as `scratchpad/sync-failure-visibility-memo.md`. `git check-ignore` returns nothing, so the path is trackable. It is NOT yet in the index: creating it is the only write this session may make, and staging or committing it is the operator's call (`git add scratchpad/sync-failure-visibility-memo.md`). | MEASURED |

### 0.2 Line references in the brief, re-verified

| Brief claim | Repo | Label |
|---|---|---|
| D1 drain collects only PENDING and RETRYABLE, `SyncSql.kt:20-22` | Correct lines; the file is `data/local/dao/SyncSql.kt`, not `data/sync/`. | MEASURED |
| D1 false KDoc `SyncOutboxRepository.kt:26-29` | Correct. The same false claim also sits at `SyncState.kt:16-18` ("Stays queued, surfaced for review"). | MEASURED |
| D1 nothing renders CONFLICT | Correct. Every count and review query is `syncState = 'FAILED'` (e.g. `PatientDao.kt:52`, `:60-65`); `observeFailedCount` sums only those (`RoomSyncOutboxRepository.kt:189-200`). | MEASURED |
| D2 pending caption reads the doctor-assignment queue, `SyncStatusImpl.kt:73` | Line drift. `:73` is the `syncOutboxScheduler` parameter. The source is `caseRecordRepository.observePendingSyncCount()` at `SyncStatusImpl.kt:96`, bound to `pendingCount` at `:101`; the caption is computed at `HomeScreen.kt:270-274`. Substance correct. | MEASURED |
| D3 `HomeViewModel.kt:96-97` discards the Result | Correct. | MEASURED |
| D4 `WorkManagerAssessmentScheduler.kt:35` waits on CONNECTED, UI never says so | Correct. The VM maps QUEUED with no row to `isLoading = true` (`KernelAssessmentViewModel.kt:282`), the screen draws only a spinner for it (`KernelAssessmentScreen.kt:72-73`), and `canContinue` is false while loading (`KernelAssessmentViewModel.kt:181`), so the worker is also stuck on the screen. | MEASURED |
| D5 `AssessmentRunner.kt:82-96` awaits `syncNow()` and proceeds | Correct: `:82-84` await and log, kernel call `:86-91`, evaluate `:96-101`. | MEASURED |
| D5 "the result is labelled ML server unavailable" | **Partly in conflict.** That label is the dev flavor only: the mock fallback is consulted for every failure class (`GenerateKernelReportUseCase.kt:125`), and a MOCK_FALLBACK row is labelled "Offline fallback (mock), ML server unavailable" (`KernelAssessmentViewModel.kt:157`, em dash in source rendered here as a comma). In staging and prod the 404 is classified `CASE_NOT_ON_SERVER` (`KernelApiResult.kt:213`) and shows `kernel_failure_case_not_on_server` (`strings.xml:41-42`), which names the right cause but makes two false promises (see N2). | MEASURED |
| D6 a duplicate patient identifier is rejected TERMINAL | **Conflict.** The backend classifies sqlstate 23505 as `retry_class = CONFLICT` (`sync.py:166`), not TERMINAL. The device maps both to record state FAILED (`SyncAckMapping.kt:52-58`) and shows `DUPLICATE_RECORD` with no button (`SyncFailureReason.kt:60-64`, `strings.xml:125-126`). Outcome identical; the wire class differs. | MEASURED |
| D6 / `PROGRESS.md:5258` "ABHA number, mobile or Aadhaar already exists" | **Conflict.** The only client-reachable unique index on `patients` is `ix_patients_abha_number` (`patient.py:73`; `sync.py:149-153` states the same). Nothing in the schema rejects a duplicate mobile or Aadhaar. | MEASURED |
| `PROGRESS.md:5274-5277` open: whether `runNowAndAwait()` reports a per-record reject as a failure | Answered: it does not. `sendAndApply` returns success on any 200 whatever the acks say (`SyncOutboxDrainer.kt:186-196`), the drain returns success when the next collect is empty (`:96`), the worker maps that to `Result.success()` (`SyncPushWorker.kt:26-27`), and `runNowAndAwait` maps SUCCEEDED to success. | MEASURED |
| D8 "#69's rule 2 now compares content" | Correct (`sync.py:280-282`, `same_content` at `:481-482`). | MEASURED |

### 0.3 Defects found during recon that the brief does not list

| ID | Finding | Label |
|---|---|---|
| N1 | `KernelRetryAdvice.RETRY_WHEN_CONNECTED` promises the assessment "runs on its own" (`KernelFailure.kt:16-19`; copy `strings.xml:19`, `:50`), and the screen hides Retry for it (`KernelAssessmentScreen.kt:167`). Nothing re-runs it: `enqueueAssessment` has exactly two callers (`SendingViewModel.kt:80`, `KernelAssessmentViewModel.kt:300`), and `AssessmentWorker` always returns `Result.success()` after writing the UNAVAILABLE row (`AssessmentWorker.kt:259-264`). OFFLINE and KERNEL_UNAVAILABLE are therefore permanent dead ends with no button. | MEASURED |
| N2 | `kernel_failure_case_not_on_server_body` tells the worker to "Open the patient's record and send it again" (no such action exists: `PatientRepository` has no update, `SyncFailureReason.kt:56-59`) and says "This will finish on its own once the patient is saved" (false, per N1). | MEASURED |
| N3 | The dev mock is consulted for NEEDS_ACTION classes too (`GenerateKernelReportUseCase.kt:118-127`), so a 404, a 401 or a 422 in dev becomes a mock diagnosis labelled as a server outage, and the classified `failureCode` is discarded. | MEASURED |
| N4 | The pre-assess `syncNow()` also runs `caseRecordRepository.sendAllPendingCases()` (`SyncStatusImpl.kt:119`), so assessing one case flips every `PENDING_SYNC` case on the device to `SENT_TO_DOCTOR`. Whether that is intended is unknown. | MEASURED (effect), INFERRED (unintended) |
| N5 | `ConnectivityController` claims to be the single online switch every outbound action checks (`ConnectivityController.kt:12-18`), but neither WorkManager job checks it: both constrain on the OS network only (`WorkManagerSyncOutboxScheduler.kt:30`, `WorkManagerAssessmentScheduler.kt:35`) and `SyncPushWorker` injects only the drainer (`SyncPushWorker.kt:22`). With the manual toggle off and a real network present, Home says "Offline" while the periodic drain and any queued assessment still send. | MEASURED (code), INFERRED (runtime) |
| N6 | Children of a duplicate-rejected patient fail their foreign key (23503, RETRYABLE, `sync.py:165`), exhaust five attempts (`SyncRetryPolicy.kt:26`, `PatientDao.kt:26`), land FAILED with `RETRY_EXHAUSTED_CODE`, and the review list offers "Send again" for each (`SyncFailureReason.kt:62`, `strings.xml:137-138`, "Nothing is wrong with this record. Send it again now."). The parent will never land, so the button cannot work. | INFERRED (chain read from code, not observed) |
| N7 | Master's `resolved == null` early return writes a kernel report row and no audit entry (`AssessmentRunner.kt:68-71`). The gap `76a4ac2` closed is still open on master. | MEASURED |
| N8 | `H-30` and `H-31` are reserved by the truthfulness memo (`scratchpad/backend-truthfulness-memo.md:267-285`) though not yet in the register (highest registered row `H-29`, `risk-management-file.md:57`; `H-28` reservation note `:61`). This memo proposes from `H-32`. | MEASURED |
| N9 | A third local branch, `fix/sync-before-assess-v2`, exists. `git log master..fix/sync-before-assess-v2` is empty: it has no commit master lacks. | MEASURED |

## 1. Input 0: the unmerged branches

| | `fix/sync-before-assess` (`76a4ac2`, also on origin) | `fix/sync-before-assess-wip-parked` (`8a479c9`, local) | `fix/sync-before-assess-v2` (local) |
|---|---|---|---|
| Base | `43caa19`, 101 commits behind master | `49e63ca`, 118 commits behind | an ancestor of master |
| What it changes | New `OutboxDrainer` fun interface over `SyncOutboxDrainer.drain()`; new `CaseRecordDao.getSyncState`; new `GetCaseRecordSyncState` seam with a `@Provides`; Stage 0 in `AssessmentRunner.run`: drain in-process, read the case_record's record state, anything but SYNCED records UNAVAILABLE and returns; `recordUnavailableAudited()` gives both early returns an audit entry; two tests asserting the emitted audit plus the persisted row. 7 files, +317. | `AssessmentRunner` calls `syncStatus.syncNow()` and ignores the Result; one test counting `syncCalls`. 2 files, +21. | Nothing. |
| Still applies after #69 and #71? | No. `git apply --check` fails on `AssessmentRunner.kt`, `AssessmentRunnerTest.kt` and `SyncOutboxDrainer.kt`. Its reasoning is also stale: its KDoc says the outbox collects only PENDING and nothing requeues FAILED, both false since S-2 and S-3 (`SyncSql.kt:20-22`, `SyncOutboxRepository.kt:62`). | Superseded. Master `2bd2855` "push the case before assessing it" landed the same idea (`AssessmentRunner.kt:73-84`). | n/a |
| Gate key | The specific case_record's own persisted record state, never the drain Result (the Result is only logged). Correct key, wrong predicate: `!= SYNCED` records a false UNAVAILABLE for a case the server already holds after any local edit resets it to PENDING (the commit message admits this and says no server-presence signal exists; one does, see 3.2). It also collapses every cause to `failure = null`, so a duplicate-rejected patient gets the generic "Assessment unavailable" copy with a Retry button that cannot work. | Global `syncNow()` Result, and not even that: it gates on nothing. | n/a |
| Recommendation | **Rewrite.** Salvage two ideas into commit 7 (section 6): gate on persisted state, and the single audited early-return helper. Drop the SYNCED-only predicate, the null failure class and the stale KDoc. | **Delete.** | **Delete.** |

Deleting `origin/fix/sync-before-assess` and the three local branches needs operator authorization
(open question H7).

## 2. The device sync-state machine (spec A)

### 2.1 States

| Record state | Meaning | Collected for resend | Surfaced | Exit |
|---|---|---|---|---|
| PENDING | Local write not yet acknowledged. Default on insert (`CaseRecordEntity.kt:19`). | Yes, always (`SyncSql.kt:21`) | Counted in Home's "waiting to send" (new, 4.1) | ack, or nothing |
| RETRYABLE | Server said resend later (`retry_class = RETRYABLE`, typically a missing parent). | Yes, once `lastSyncAttemptAt` is 5 min old (`SyncSql.kt:21-22`, `SyncRetryPolicy.kt:41`) | Counted in "waiting to send" (new) | ack; FAILED at attempt 5 |
| SYNCED | Server acked applied, stale or duplicate. | No | No | local edit |
| CONFLICT | Server acked conflict. Its own state, by ruling. | **Never** | **Yes, new**: counted on the Home card and listed (4.2, 4.3) | local edit only (2.3) |
| FAILED | Refused on merits (TERMINAL or CONFLICT retry class, or no class), retries exhausted, or too large. | No | Yes, existing card and list (`HomeScreen.kt:186-192`) | "Send again" (some causes) or local edit |

### 2.2 Transitions

| # | From | Event | To | Side effects | Label |
|---|---|---|---|---|---|
| T1 | (none) | insert | PENDING | `localModifiedAt` stamped | MEASURED |
| T2 | any | local clinical write | PENDING | `localModifiedAt` restamped; `serverVersion` kept. 30 DAO statements set `syncState = 'PENDING'` (`grep -rn "syncState = 'PENDING'" data/local/dao`, excluding requeue), plus whole-row `REPLACE` inserts in 5 DAOs that default to PENDING. | MEASURED |
| T3 | PENDING, RETRYABLE | collected, packed, sent | unchanged | in-flight batch persisted; `attempted` set bounds the drain (`SyncOutboxDrainer.kt:79-103`) | MEASURED |
| T4 | sent row | ack applied, stale, duplicate | SYNCED | `serverVersion = COALESCE(ack, old)`, attempts reset to 0 (`PatientDao.kt:24-33`) | MEASURED |
| T5 | sent row | ack conflict | CONFLICT | `serverVersion` **must not** change. Today it does not only because the conflict result carries no `server_version` key (`sync.py:485-490`) and the DAO COALESCEs. Commit 2 makes this explicit (pass null for CONFLICT in `applyAck`), so a backend that starts sending one cannot arm rule 4. | MEASURED (today), PROPOSED (guard) |
| T6 | sent row | ack rejected, RETRYABLE | RETRYABLE, or FAILED + `SAMD-SYNC-RETRY-EXHAUSTED` when attempts + 1 >= 5 | attempts + 1, `lastSyncAttemptAt` | MEASURED |
| T7 | sent row | ack rejected, TERMINAL, CONFLICT or null class | FAILED | code and message stored | MEASURED |
| T8 | PENDING | local oversize check | FAILED + `SAMD-SYNC-RECORD-TOO-LARGE` | never sent (`SyncOutboxDrainer.kt:110-131`) | MEASURED |
| T9 | sent row | ack for a revision the row no longer holds (edited in flight) | unchanged | guarded UPDATE matches zero rows (`PatientDao.kt:32`) | MEASURED |
| T10 | sent row | ack with unknown status or table | unchanged | recorded in `skippedAcks`, resent next drain | MEASURED |
| T11 | FAILED | "Send again" (human) | PENDING | attempts 0, code and message cleared; guarded on FAILED, so a no-op for every other state (`PatientDao.kt:45-49`) | MEASURED |
| T12 | CONFLICT | "Send again" | no transition: not offered (4.3), and T11's guard would make it a no-op anyway | | MEASURED (guard), PROPOSED (not offered) |

### 2.3 D7: what "keep it pending" means

The contract says "Keep it pending and surface it for review on `conflict`"
(`api-contract.md:1293-1294`). On the device that sentence maps to record state CONFLICT, not to
PENDING: "pending" there means "not synced, still owed", never "re-collected". Three properties hold
it in place:

1. **Not collected.** `SyncSql.PENDING_ELIGIBILITY_FRAGMENT` excludes CONFLICT (MEASURED
   `SyncSql.kt:20-22`), pinned by `SyncDaoSqlContractTest`. No change.
2. **Never adopts the ack's `server_version`** (T5). A CONFLICT row therefore keeps its stale
   `base_version`, and any resend of it fails rule 3 (`sync.py:283-284`) instead of passing rule 4
   and overwriting newer server data. Commit 2.
3. **Never requeued by a button** (T12). Commit 3.

The one remaining exit is T2: a worker's own clinical edit puts a CONFLICT row back to PENDING. That
causes exactly one resend per human edit. With a `base_version` present the resend conflicts again
by rule 3 and returns to CONFLICT; nothing is overwritten. With no `base_version` (a row the server
got from elsewhere first), rule 5 last-write-wins applies, which is the contract's own semantics for
a newer write. It cannot loop on its own. Making CONFLICT sticky instead would touch 30 SQL
statements and 5 whole-row insert paths for no gain inside this memo's scope. Default: keep T2 as
is, documented in H-34. Operator may overrule (H5).

### 2.4 Wording for the surfaced states

CONFLICT gets a new `SyncFailureReason.CONFLICT_ON_SERVER`, action `TELL_SUPERVISOR` (no button):

- Title: "The server has a different version of this record"
- Body: "This record was changed somewhere else after this phone last sent it, so the server kept
  its own version. Your copy is safe on this phone but is not on the server. This cannot be settled
  on this phone yet. Write down the patient's name and tell your supervisor today."

FAILED keeps its five existing reasons and copy (`strings.xml:125-149`), plus the
`PARENT_NOT_ACCEPTED` fold in section 5.

## 3. Assessment-path failure taxonomy (spec C, D4, D5)

### 3.1 Where each cause is detected

The path is: screen opens, work QUEUED (waits for the OS network), runner starts, best-effort
pre-assess sync, **gate** (new), resolve local data, `/api/v1/assess` plus `/api/v1/evaluate`,
classify. The table is the whole vocabulary. "Retry" means the existing Try again button, which
re-enqueues the same unique work (`KernelAssessmentViewModel.kt:297-301`).

| # | Cause | Detected by | Title shown | Body (summary) | Retry |
|---|---|---|---|---|---|
| A1 | Waiting for a network (D4) | VM: work QUEUED, no report row, `NetworkMonitor.isNetworkAvailable` false. The OS network, not the manual toggle, because that is what WorkManager waits on (N5). | "Waiting for a connection" | "This visit will be checked by itself as soon as this phone is connected." True: WorkManager runs it on CONNECTED. | None needed |
| A2 | Checking | VM: QUEUED or RUNNING, no row, network available | spinner + "Checking this visit" | | n/a |
| A3 | Local record incomplete | `resolve()` returns null: no case record, vitals or consultation (`AssessmentRunner.kt:133-140`) | "This visit is missing information" | "The vitals or the consultation for this visit were not saved, so it could not be checked. Trying again will not help. Tell your supervisor." | None. NEW `RECORD_INCOMPLETE`, NEEDS_ACTION. Today `failure = null` with the generic copy and a Retry button. |
| A4 | Local exception while resolving | `resolve()` throws | (existing DEVICE_ERROR copy) | | None. Today `failure = null`. |
| A5 | Patient not accepted, duplicate ABHA (D5, D6) | Gate: case not on server and its patient row is FAILED with reason DUPLICATE_RECORD | "This patient is not on the server" | "The server already has another patient with the same ABHA number, so this patient and this visit were not saved there and the visit could not be checked. This cannot be fixed on this phone. Write down the patient's name and tell your supervisor today." | None. NEW `PATIENT_DUPLICATE`, NEEDS_ACTION. |
| A6 | Visit blocked in sync | Gate: not on server, and the patient, encounter or case row is FAILED (any other reason) or CONFLICT | "This visit could not be sent to the server" | "Part of this visit was not accepted by the server, so it could not be checked. Open 'Records that did not reach the server' on the home screen to see why." | None. NEW `CASE_SYNC_BLOCKED`, NEEDS_ACTION. |
| A7 | Visit not sent yet | Gate: not on server, and every row in the chain is PENDING or RETRYABLE | "This visit has not been sent yet" | "This phone has not finished sending this visit to the server. When the home screen shows nothing waiting to send, press Try again." | Button. NEW `CASE_NOT_SENT_YET`, RETRY_NOW. |
| A8 | Connection dropped mid-call | `Unreachable`, non-timeout (`KernelApiResult.kt:203-210`) | "The connection dropped" | "This phone lost its connection while checking this visit. Press Try again when it is connected." | Button. OFFLINE moves from RETRY_WHEN_CONNECTED to RETRY_NOW (N1). |
| A9 | Timeout | `SocketTimeoutException` | existing | existing | Button (unchanged) |
| A10 | TLS failure | `SSLException` | existing | existing | None (unchanged) |
| A11 | Not authorized | 401, 403 | existing | existing | None (unchanged) |
| A12 | Server cannot find the visit | 404 `SAMD-ENC-4002` after the gate passed (the server acked the case: facility mismatch, or the server lost it) | "The server could not find this visit" | "This phone sent this visit, but the server could not find it for this clinic. Trying again will not help. Tell your supervisor today." | None. `CASE_NOT_ON_SERVER` copy rewritten (N2). |
| A13 | Payload rejected | 422 `SAMD-KERN-5003/5005` | existing | existing | None (unchanged) |
| A14 | Assessment service down | 502/503/504, `SAMD-KERN-500x` | "The assessment service is down" | "The service that checks visits is not working right now. Try again in a few minutes. You can carry on with the patient." | Button. KERNEL_UNAVAILABLE moves to RETRY_NOW (N1). |
| A15 | Malformed response, device error, unknown code | existing classifier | existing | existing | unchanged |
| A16 | Kernel answered with an empty differential | `failure = null`, kernel reached | existing `kernel_failure_none_*` | existing | Button (unchanged, truthful) |
| A17 | Stalled: no row and no live work | VM (`stalledDisplay()`) | existing `kernel_failure_none_*` | existing | Button (unchanged) |

After N1 is fixed no `KernelFailure` maps to `RETRY_WHEN_CONNECTED`, so the value is deleted, and
the exhaustive `when` in `KernelAssessmentScreen.kt:167` becomes `advice == RETRY_NOW` or
`failure == null`.

**A 404 is never labelled as the ML server being unavailable.** Two rules enforce it:

1. The dev mock fallback is consulted only for A8, A9 and A14 (the classes where the real service
   was not reached). Every other class writes an honest UNAVAILABLE row with its `failureCode`, in
   every flavor (N3).
2. The MOCK_FALLBACK label becomes "Mock result, dev build only: the real assessment service was
   not reached", which is true for all three of those classes.

**Evaluate leg.** When the gate stops the run (A3, A5 to A7), `/api/v1/evaluate` is not called
either, and `evaluateReportRepository.saveFailure(caseRecordId, failure.name)` records why, so
H-14's "failed versus not run" distinction holds.

**Audit.** Every gate or resolve stop goes through one helper, salvaged from `76a4ac2`, that
persists the UNAVAILABLE row and emits `KERNEL_RESPONSE_RECEIVED` with
`inferenceSource = UNAVAILABLE` and `reason = <KernelFailure name>`. This closes N7. No new audit
action is needed.

### 3.2 The gate (D5): this case's own state, never the global Result

`/api/v1/assess` needs exactly one server row: the case_record, matched on id and facility
(`kernel.py:90-98`, MEASURED). On the server `case_records.patient_id` and `encounter_id` are
foreign keys (`clinical.py:139-143`, MEASURED), so a case_record on the server implies its patient
and encounter are there too. The gate therefore keys on the case_record, and it walks up to the
patient and encounter only to **name the cause** when the case is absent.

**Server presence** is `serverVersion IS NOT NULL OR syncState = 'SYNCED'`. `serverVersion` is set
only from an ack that carries one (applied or stale, `sync.py:496-497`) and never by a conflict
(T5). It survives a local edit (T2 keeps it), which is exactly the signal `76a4ac2` said the device
lacked. A case the server holds but that was edited since therefore still assesses.

Decision, evaluated in order over one joined read (new `CaseRecordDao` query, case LEFT JOIN
encounter LEFT JOIN patient, returning each row's record state, `serverVersion`, `syncErrorCode`
and `syncErrorMessage`):

1. Case row missing locally: A3.
2. Case present on server: proceed to resolve and call.
3. Patient FAILED and `syncFailureReasonFor(code, message) == DUPLICATE_RECORD`: A5.
4. Any of patient, encounter, case FAILED or CONFLICT: A6.
5. Otherwise: A7.

The decision is a pure function over a snapshot (JVM-testable). The pre-assess `syncNow()` stays
as the best-effort push it is today. Its Result is logged and never consulted. Replacing it with an
in-process drain (`76a4ac2`'s `OutboxDrainer`) is warranted only if N4's side effect is unwanted
(H3).

### 3.3 Retry behaviour, summarised

No class auto-retries. Every "button" class re-enqueues on press, and the runner pushes again
before gating again. Auto-retry for A7, A8 and A14 (a WorkManager `Result.retry()` with a
`runAttemptCount` cap) is skipped: it is a feature, not a truthfulness fix. Add it if field use
shows workers not pressing.

### 3.4 What the dev-only switch is for

Live checks of A8, A14 and A16 on a dev build need the mock out of the way, which is the filed
`local.properties` switch (`PROGRESS.md:5248-5253`, not built: no `kernelFallback` key in
`app/build.gradle.kts`, MEASURED). Recommendation: **land it before this work**, as its own
one-commit PR, as filed ("Queue before sync-failure-visibility"). It is independent build
configuration (`build.gradle.kts:74-75` already reads `local.properties` the same way), it is
reviewable alone, and the live checks of commits 7 to 9 depend on it. Commit 8's fallback narrowing
makes A5, A6, A7 and A12 visible on dev even without the switch; only A8, A14 and Retry need it.

### 3.5 D4 and the Continue button

A1 replaces the bare spinner. It does not by itself let the worker leave the screen:
`canContinue` requires `!isLoading` (`KernelAssessmentViewModel.kt:181`), so offline the visit is
stuck at this screen until a connection returns. Whether a worker may acknowledge "no assessment
yet" and continue is a clinical-workflow decision (H1). The build implements A1 either way.

### 3.6 Boundary with the doctor-review memo

This memo stops at what the frontline worker sees on this phone. What the doctor sees about a case
whose records never reached the server, and how derivation flags reach the doctor view, needs the
pull path and belongs to its own memo. One adjacent gap is named, not designed: a case can read
`SENT_TO_DOCTOR` locally (`sendAllPendingCases` "doesn't touch the network", `SyncStatusImpl.kt:27-28`)
while its records sit FAILED or CONFLICT (H6).

## 4. Home (spec B, D1 to D3)

### 4.1 Inputs

| Symbol | Source | Status |
|---|---|---|
| N | `ConnectivityController.isOnline` (the manual toggle AND the OS network), as today | MEASURED `HomeScreen.kt:271` |
| S | `isSyncing` | MEASURED |
| Pc | PENDING plus RETRYABLE rows across the 19 clinical tables | NEW |
| Pa | PENDING plus RETRYABLE `audit_log` rows | NEW |
| Q | Doctor-queue count (`observePendingSyncCount`), today's `pendingCount` | MEASURED |
| F, C | FAILED, CONFLICT rows across all 20 tables | F MEASURED, C NEW |
| B | Last drain failure kind, or none. Set when a drain returns failure, cleared on the next successful drain. In memory only: after process death it is unknown, and every caption below is still true when B is unknown. | NEW |

Pc, Pa, F and C come from one grouped count per table (`SELECT syncState, COUNT(*) ... WHERE
syncState != 'SYNCED' GROUP BY syncState`), replacing today's 20 `observeFailedSyncCount` flows
one for one, so the launch path carries the same number of observers (F2A-01).

B kinds, from the drain's failure. `SyncPushResult.Failure` already carries the problem `code`
(`RetrofitSyncPushService.kt:26`, `:41`), but the drainer drops it into an `IllegalStateException`
message; the build keeps it.

| B | From | Phrase |
|---|---|---|
| NO_CONNECTION | push `Failure(code = null)`: an `IOException` or a non-problem body | "could not reach the server" |
| SIGN_IN | code starts `SAMD-AUTH-` (`errors.py:30-37`) | "sign in again to send" |
| SERVER_REFUSED | any other code (e.g. `SAMD-SYS-9004`, a 500 batch as in #69's defect) | "the server is not accepting records right now" |
| LOCAL_STORE | `InFlightBatchStore.load()` failed (`SyncOutboxDrainer.kt:141-146`) | "this phone could not prepare records to send" |

### 4.2 Truth table

Rows are evaluated top to bottom; the first match wins. The card ("N records did not reach the
server", existing copy `strings.xml:86-91`) shows in **every** row where F + C > 0, independent of
the caption. "Up to date" appears in exactly one row.

| # | S | N | B (with Pc + Pa > 0) | Pc | Q | Pa | F + C | Caption |
|---|---|---|---|---|---|---|---|---|
| 1 | yes | any | any | any | any | any | any | "Sending…" |
| 2 | no | off | any | > 0 or Q > 0 | | any | any | "Offline. {Pc} records and {Q} cases for a doctor are saved on this phone, not sent yet." (each clause only when its count > 0) |
| 3 | no | off | any | 0 | 0 | > 0 | any | "Offline. The activity log is saved on this phone, not sent yet." |
| 4 | no | off | any | 0 | 0 | 0 | > 0 | "Offline. Nothing else is waiting to send." |
| 5 | no | off | any | 0 | 0 | 0 | 0 | "Offline. Everything on this phone has been sent." |
| 6 | no | on | set | any | any | any | any | "Could not send: {B phrase}. {Pc + Pa} waiting." |
| 7 | no | on | none | > 0 | any | any | any | "{Pc} records waiting to send." plus, if Q > 0, " {Q} cases waiting to go to a doctor." |
| 8 | no | on | none | 0 | > 0 | any | any | "{Q} cases waiting to go to a doctor. Press Sync now." |
| 9 | no | on | none | 0 | 0 | > 0 | any | "The activity log is waiting to send." |
| 10 | no | on | none | 0 | 0 | 0 | > 0 | "Everything else is sent. {F + C} need review." |
| 11 | no | on | none | 0 | 0 | 0 | 0 | "Up to date" |

Notes. Row 6 ignores a B left over when Pc + Pa is 0 (nothing can be blocked with nothing queued),
which falls through to rows 8 to 11. Row 2 is today's offline caption made specific; on master it
reads "Offline, saved locally, syncs when back online" (`HomeScreen.kt:271`, em dash in source
rendered here as a comma) whatever is queued. On master row 7, 9 and 10 all render as "Up to date"
(`HomeScreen.kt:272-273` reads Q only), which is D2. Captions are string resources with plurals;
the selection is a pure function `syncCaption(state, isOnline)` so the table is the test.

### 4.3 "Sync now" result (D3)

`onSyncNow` keeps the Result and emits one effect (snackbar), chosen after the state settles:

| Result | Condition | Message |
|---|---|---|
| failure | offline refusal (`SyncStatusImpl.kt:114-116`) | "This phone is offline. Nothing was sent." |
| failure | `runNowAndAwait` timed out (`WorkManagerSyncOutboxScheduler.kt:84`, 60 s) | "Still trying to send. This can take a while on a slow connection." |
| failure | drain failed, B set | "Could not send: {B phrase}." |
| success | F + C > 0 after | "Sent what could be sent. {F + C} records need review." |
| success | Pc > 0 after (RETRYABLE waiting its interval) | "Sent. {Pc} records will be tried again later." |
| success | otherwise | "Everything is sent." |

A success Result never on its own means "everything is sent" (0.2, last row but one).

### 4.4 The review list

The count and the list widen from FAILED to FAILED or CONFLICT. Each review query also projects
`syncState`, and classification checks it first: a CONFLICT row is `CONFLICT_ON_SERVER` whatever
its code. Classifying by a device-stamped code instead would misread every CONFLICT row written
before the build (their code is null, which reads UNRECOGNISED and offers a "Send again" that T11's
guard turns into a silent no-op).

## 5. D6: a TERMINAL-rejected (duplicate ABHA) patient

Wire class is CONFLICT, device state FAILED (0.2). What the worker sees and can do after the build:

| Surface | Sees | Can do |
|---|---|---|
| Home caption | Row 10 (or 6 to 9 if other work is queued). Never "Up to date". | Nothing to press for it. |
| Home card and list | One row for the patient: "Another record already uses these details" (existing `strings.xml:125-126`), with "{n} visit records for this patient are held on this phone with it." Children (encounter, consultation, case, observations, reports, audit) that are FAILED or RETRYABLE because of this patient are folded under it as `PARENT_NOT_ACCEPTED`, with no "Send again" (fixes N6). | Write down the name, tell the supervisor (copy says so). No button. |
| Assessment screen | A5: "This patient is not on the server", no Retry. | Continue the visit only if H1 allows; the local record stays usable for care on this phone. |
| Roster and local records | Patient and visit visible and editable locally as today. | Local care continues. |

What the worker **cannot** do, and the UI must not imply: edit the identifier (no update path,
`SyncFailureReason.kt:56-59`), merge, re-send, or work around it by registering the person again
without an ABHA number (that would sync, since only ABHA is unique, and create a second record of
the same person; no copy may suggest it). Recovery is the dedupe and merge work, out of scope.

The fold needs each listed row's patient state. `failedRecords()` already resolves patient names in
one `IN (:ids)` query (`RoomSyncOutboxRepository.kt:245`); that query also returns
`syncState`, `syncErrorCode`, `syncErrorMessage`, and the fold is a pure function over rows plus
patient states. A RETRYABLE child is not in the list until it exhausts its attempts; the fold
covers it when it gets there.

## 6. D8: strictly increasing `localModifiedAt`

**Still needed. #69 fixed the server half only.** Room stores epoch milliseconds
(`Converters.kt:33-34`, MEASURED). Two writes of one row in the same millisecond, with a drain
packing the first between them, leave two hazards:

1. **Device, silent loss, untouched by #69.** The ack guard is `localModifiedAt =
   :sentLocalModifiedAt` (`PatientDao.kt:32`, identical in every DAO). The second write leaves the
   timestamp equal, so the ack for the first write matches and stamps the row SYNCED with content
   the server never received. Nothing ever resends it. INFERRED from the SQL; no test exercises it.
2. **Server, now honest but a dead end.** If the second write is sent, rule 2 sees an equal
   timestamp with different content and answers conflict (`sync.py:280-282`). Before #69 that was a
   silent "stale". After this PR it is a visible CONFLICT that nothing on the device can resolve:
   a self-inflicted false conflict that needs a supervisor.

The window is one millisecond and needs a drain collect between two writes, so it is rare. The fix
is cheap and closes both, so it stays in the PR, last.

**Mechanism: one process-wide monotonic millisecond clock** (`now() = max(wallMillis, last + 1)`)
used at every site that stamps `localModifiedAt` or passes the `:updatedAt` a DAO copies into it.
Within one process every stamp is unique, so no two writes of any row share a millisecond, which
is the property both hazards need. Because DAOs copy one argument into both `updatedAt` and
`localModifiedAt`, the two columns stay equal (MIGRATION_12_13's deliberate redundancy holds). The
per-row SQL form `MAX(:now, localModifiedAt + 1)` was considered and not chosen: it covers only the
UPDATE statements, not the whole-row insert paths, and would make the two columns diverge. Its one
extra property, surviving a wall-clock step backwards across a process restart, is a different
hazard; rule 4 already protects writes that carry a `base_version`. Recorded in H-35's residual.

## 7. Room schema (spec E)

**No migration. Room stays at v22** (`AppDatabase.kt:75`, MEASURED). Every change is a query or a
Kotlin type:

| Change | Kind |
|---|---|
| 20 grouped count queries replace 20 `observeFailedSyncCount` | query |
| 20 review queries widen to `IN ('FAILED','CONFLICT')` and project `syncState` | query; `FailedSyncRow` gains a non-null `syncState` (non-null, so a projection alias mismatch fails the build instead of warning; see the Room alias trap in project memory) |
| Patient-name query also returns three sync columns | query |
| New gate snapshot query in `CaseRecordDao` | query |
| New `KernelFailure` values | none: stored by name, unknown names read as UNKNOWN (`Converters.kt:91-99`) |
| CONFLICT surfacing, B, monotonic clock | none |

Room SQL does not run in the JVM suite, so every query above needs an instrumented test.

## 8. PR breakdown (spec F)

**PR 0, before this work:** `feat(dev): local.properties switch binds the always-null kernel
fallback`. One commit. Test: a JVM test of the flag-to-binding selector (red on master: symbol
absent), plus a live check that a dev build with the flag shows the UNAVAILABLE card on a kernel
outage.

**PR 1, sync failure visibility.** Ten commits. "Red on master" means the commit's tests applied to
a master snapshot fail, by assertion where stated, and the mutation check (restore the master
logic behind the new signature) turns the named tests red; the snapshot method and the "empty
test-results directory is RED" rule from project memory apply. Instrumented tests run on the iQOO
I2302 (arm64-v8a, SQLCipher) by install plus `am instrument`, never a Gradle connected task:

```
./gradlew :app:assembleDevDebug :app:assembleDevDebugAndroidTest
adb -s <iQOO serial> install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
adb -s <iQOO serial> install -r app/build/outputs/apk/androidTest/dev/debug/app-dev-debug-androidTest.apk
adb -s <iQOO serial> shell am instrument -w -e class com.example.samdapp.data.local.<TestClass> \
  com.example.samdapp.dev.test/androidx.test.runner.AndroidJUnitRunner
```

| # | Commit | Tests | Red on master | iQOO |
|---|---|---|---|---|
| 1 | `docs(sync): CONFLICT is not surfaced today; correct two KDocs` (`SyncOutboxRepository.kt:26-29`, `SyncState.kt:16-18`) | none (no behaviour) | n/a | no |
| 2 | `fix(sync): a conflict ack never adopts server_version` (`applyAck` passes null for CONFLICT) | instrumented `ConflictAckServerVersionTest`: row at `serverVersion` 3, apply a conflict ack carrying 7, read the row back: still 3, state CONFLICT. Plus a characterization test that `requeueFailed` on a CONFLICT row changes nothing. | Assertion: master's COALESCE persists 7. | yes |
| 3 | `feat(sync): list and count CONFLICT rows for review` (20 count and 20 review queries, `FailedSyncRow.syncState`, `CONFLICT_ON_SERVER`, copy) | JVM `SyncFailureReasonTest`: CONFLICT state wins over any code, null included. Instrumented `FailedSyncReviewQueryTest` extended: one CONFLICT row per table appears in count and list. | Assertion (instrumented): master selects FAILED only. JVM by compile. | yes |
| 4 | `feat(sync): outbox counts and the last drain failure` (grouped counts, a `DrainOutcomeStore` holder injected into drainer and `SyncStatusImpl`, push failure keeps its code) | JVM `SyncOutboxDrainerTest`: null code gives NO_CONNECTION, `SAMD-AUTH-1003` gives SIGN_IN, `SAMD-SYS-9004` gives SERVER_REFUSED, unreadable store gives LOCAL_STORE, a later success clears it. JVM `SyncStatusImplTest`: counts reach Home sync state. Instrumented `OutboxCountQueryTest`: grouped counts per state per table. | Compile, then mutation: drop the store write and the four kind tests go red. | yes |
| 5 | `fix(home): caption tells the truth; Sync now reports its result` | JVM `SyncCaptionTest`: one case per truth-table row (4.2), including rows 7, 9 and 10 asserting not "Up to date". JVM `HomeViewModelTest`: each 4.3 row emits its effect. | Mutation: master's three-branch `when` behind the new signature turns rows 4, 7, 9, 10 red; replacing the effect with a discard turns every 4.3 case red. | no (live check below) |
| 6 | `fix(sync): fold a not-accepted patient's records under it` (D6, N6) | JVM test of the pure fold: a FAILED duplicate patient plus FAILED `RETRY_EXHAUSTED` children gives one patient row, children `PARENT_NOT_ACCEPTED`, no SEND_AGAIN. Instrumented: the extended patient-name query returns sync columns. | Mutation: no fold leaves a child offering SEND_AGAIN. | yes |
| 7 | `fix(assessment): gate on this case's own server presence and name the cause` (snapshot query, pure decision, audited early returns, four new `KernelFailure` values, copy) | JVM `AssessGateDecisionTest`: every 3.2 branch. JVM `AssessmentRunnerTest`: (a) `syncNow` succeeds but the patient is FAILED duplicate: kernel source called 0 times, persisted kernel report row has `failureCode = PATIENT_DUPLICATE`, audit entry carries the reason, evaluate failure persisted; (b) case PENDING but `serverVersion` 4: kernel called (no false UNAVAILABLE); (c) resolve returns null: persisted row `RECORD_INCOMPLETE` plus audit entry. Every assertion reads the persisted row or the logged audit entry from the fakes, never `run()`'s return. Instrumented `AssessGateSnapshotQueryTest`: join returns all three rows' states, and nulls for a missing encounter. | (a) and (c) by assertion: master calls the kernel and writes no audit. (b) is a regression guard against the `76a4ac2` predicate. | yes |
| 8 | `fix(assessment): truthful kernel failure labels` (fallback only for A8, A9, A14; MOCK label; delete RETRY_WHEN_CONNECTED; copy for A8, A12, A14) | JVM `GenerateKernelReportUseCaseTest`: 404 `SAMD-ENC-4002` with a non-null fallback persists `UNAVAILABLE` plus `CASE_NOT_ON_SERVER`, not MOCK_FALLBACK; 503 with a fallback still uses it. JVM `KernelFailureTest`: OFFLINE and KERNEL_UNAVAILABLE advise RETRY_NOW. | First test by assertion: master persists MOCK_FALLBACK. | no |
| 9 | `fix(assessment): say when the check is waiting for a connection` (A1) | JVM `KernelAssessmentViewModelTest`: QUEUED, no row, network unavailable gives the waiting state; network back gives the spinner. | Compile, then mutation: ignoring the network signal turns the waiting test red. | no (live check below) |
| 10 | `fix(sync): strictly increasing localModifiedAt` (monotonic clock, every stamp site enumerated in the commit) | JVM `MonotonicClockTest` with a fixed `java.time.Clock`: two calls differ by 1 ms; a wall clock stepped back still increases. JVM repository test with a capturing fake DAO: two `updateStatus` calls under a fixed clock get strictly increasing stamps. JVM contract test reading `data/repository` sources as text: no stamp site uses `Instant.now()` directly. | Compile, then mutation: returning wall time makes the repository test red. | no |

The commit-10 contract test reads `app/src/main` from the test source set, so `app/src/main` must
be declared as an input of the test task, or Gradle marks the task UP-TO-DATE and the test never
runs (project memory: Gradle mirror-test inputs).

**Live checks on the iQOO, dev flavor, PR 0 merged, after commit 10:** (1) register a patient whose
ABHA the server already holds: card shows one folded patient row, caption never "Up to date",
assessment shows A5 with no Retry; (2) airplane mode, send a visit: A1 card, then a result once
connected; (3) stop the backend: Home row 6 "could not reach the server", Sync now snackbar says
so; (4) switch on, kernel stopped: A14 with Try again, press it after restarting the kernel: real
result; (5) `adb shell` read of the CONFLICT row's `serverVersion` after forcing a conflict
(two devices or a hand-edited server row): unchanged.

Then `docs(progress)` and the PROPOSED hazard rows as their own commits, per house practice.

## 9. Hazard rows (spec G), all PROPOSED, AWAITING OPERATOR SIGN-OFF

| ID | Action | Hazard | Harm | Controls (this PR) | Residual |
|---|---|---|---|---|---|
| H-05 | AMEND | Offline data loss before sync | Lost clinical record | Replace the stale "currently sync is mocked" (`risk-management-file.md:28`) with the real outbox (Phase 6b, S-1 to S-3) and this PR's visibility. | Device wipe before sync still loses records; Home now says when that risk exists. |
| H-09 | AMEND | Kernel unavailable or mocked mistaken for real | False confidence | Mock fallback limited to not-reached classes; label says "dev build only". | Dev only; staging and prod bind no fallback. |
| H-32 | NEW | A record that has not reached the server, or never will, is shown as sent ("Up to date"; CONFLICT never surfaced) | The doctor never reviews the case; records are lost on wipe; the worker sees no reason to act | 4.2 truth table, CONFLICT on card and list, drain-failure caption, Sync now result | Records the device believes SYNCED but the server lost are invisible (no pull path). |
| H-33 | NEW | Assessment failure attributed to the wrong cause or given a false remedy (a 404 shown as the ML server down; "runs on its own" with nothing re-running it; "send it again" with no such action) | The worker waits for something that never happens, or escalates the wrong problem; the patient leaves without an assessment nobody chased | Gate on this case's server presence, taxonomy 3.1, no RETRY_WHEN_CONNECTED, fallback narrowing, audited early returns | Workers must press Try again themselves (no auto-retry). |
| H-34 | NEW | A conflicted record resent on a stale base or with an adopted `server_version` overwrites newer server data | Silent loss of a newer clinical write | CONFLICT not collected, never adopts `server_version`, no Send again | A worker's own edit resends once (T2): rule 3 rejects it, rule 5 applies last-write-wins as contracted. |
| H-35 | NEW | Two writes of one row in the same millisecond: the second is marked SYNCED unsent, or raises a false CONFLICT | Silent loss on the device, or a dead-end review item | Process-wide monotonic stamp | Wall-clock step back across a restart without a `base_version` (rule 5) is not covered. |

## 10. Open questions for the operator (spec H)

1. **H1. Continue without an assessment?** While A1 shows, or on any NEEDS_ACTION class, may a worker
   acknowledge "no AI assessment" and continue the visit? Today offline blocks the screen (3.5).
2. **H2. Manual offline toggle (N5).** Should both WorkManager jobs and the runner honour
   `ConnectivityController`, so "Offline" on Home is true? Recommend filing as its own fix; this
   PR's A1 uses the OS network to stay truthful about what WorkManager does today.
3. **H3. Doctor queue flip on assess (N4).** Is flipping every `PENDING_SYNC` case to
   `SENT_TO_DOCTOR` when any case is assessed intended? If not, commit 7 swaps `syncNow()` for an
   in-process drain.
4. **H4. Audit backlog on Home.** Default: count clinical records only, show audit-only backlog as
   "The activity log is waiting to send" (rows 3, 9). Alternative: one combined count.
5. **H5. CONFLICT and local edits.** Default: an edit returns a CONFLICT row to PENDING for one
   resend (2.3). Alternative: CONFLICT is sticky (30 SQL sites, 5 insert paths).
6. **H6.** File the `SENT_TO_DOCTOR`-while-unsynced gap (3.6) under the doctor-review memo?
7. **H7. Branch deletion.** Authorize deleting `origin/fix/sync-before-assess` and the local
   `fix/sync-before-assess`, `fix/sync-before-assess-wip-parked`, `fix/sync-before-assess-v2`.

## 11. Operator rulings (2026-10-06, final, recorded verbatim)

H1. "Continue without an AI assessment" is APPROVED in principle, but OUT of PR 1. It becomes a follow-up PR with its own short addendum (an explicit action, an audit entry, and a doctor-side "no assessment available" flag). PR 1 keeps today's blocking behaviour, with A1 wording.
H2. Filed as its own fix: WorkManager jobs and the runner should honour ConnectivityController. Not in PR 1.
H3. Keep: syncNow() on assess sends every queued PENDING_SYNC case, the same as Sync now. No in-process drain swap.
H4. Memo default: clinical counts only; audit backlog shown as "The activity log is waiting to send".
H5. Memo default: a local edit returns a CONFLICT row to PENDING for one resend.
H6. The worker-visible part is IN PR 1: wherever the worker sees a case as sent to the doctor, show "Queued for doctor, not yet on the server" until the case chain is server-present (reuse commit 7's server-presence function). The doctor-side semantics stay in the doctor-review memo.
H7. Tag 76a4ac2 as archive/sync-before-assess-76a4ac2 and push the tag. Then delete origin/fix/sync-before-assess and the local branches fix/sync-before-assess, fix/sync-before-assess-wip-parked and fix/sync-before-assess-v2.

## 12. Addendum (2026-10-06): reconnect re-run, blocked counts, timeliness copy

Written after the live checks on `59a2b85`. Check 2 failed: the A1 card's "will be checked by
itself" did not happen. Home counted records that can never send as "waiting". The
acknowledgement screen promised a doctor review within 24 hours for a case whose patient is not
on the server. Read-only; no code. Tags: MEASURED (file:line, or the live-check log of
2026-10-06 on the iQOO) or INFERRED. File paths are under
`app/src/main/java/com/example/samdapp/` unless stated.

### 12.0 Conflicts with the brief (the repo wins)

| # | Brief | Repo | Effect on this design |
|---|---|---|---|
| C1 | The push worker "was in RETRY backoff" when the runner asked. | The request the runner awaited was the fresh one `runNowAndAwait` had just enqueued with `REPLACE`. It ran once, its drain failed, and WorkManager re-enqueued it with backoff (12.1). | Same outcome, different mechanism. The fix must survive a first drain failing on a network that is reported up. |
| C2 | "The gate returned CASE_NOT_SENT_YET". | INFERRED from the A7 card copy shown on the phone and the server's `kernel_reports` row `78b52096` (case `3762c4b1`, "Assessment unavailable", confidence 0, MEASURED by SQL). The device audit entry was not read. | None. |
| C3 | 12.4: a FAILED **or CONFLICT** ancestor holds its descendants. | `_resolve_write` decides only for an EXISTING server row (`backend/core/app/services/sync.py:255`), so a CONFLICT row is on the server and its children's foreign keys are satisfied. A FAILED row whose `serverVersion` is set is also on the server (`domain/model/AssessGateSnapshot.kt:30-31`). | Held = an ancestor that is FAILED **and** has `serverVersion IS NULL`. CONFLICT never holds descendants (Q2). |
| C4 | 12.3: retry only "when the network is available". | WorkManager will not start the attempt without `CONNECTED` (`data/assessment/WorkManagerAssessmentScheduler.kt:34-36`), and the screen already shows A1 while the work is queued without a network (`presentation/kernelassessment/KernelAssessmentViewModel.kt:292-296`). | Retry on CASE_NOT_SENT_YET whatever the network state; the network only selects the card (Q3). |
| C5 | 12.2: the `OutboxDrainer` seam from `archive/sync-before-assess-76a4ac2` may be reused. | The runner already depends on `SyncStatus` (`domain/usecase/AssessmentRunner.kt:44`), and `SyncOutboxDrainer` already holds the process-wide mutex (12.2). | Seam not reused: one new `SyncStatus` method instead of a new interface plus a binding. |
| C6 | "The 19 clinical tables". | 19 clinical tables plus `audit_log` (`data/sync/RoomSyncOutboxRepository.kt:195-213`). Of the 19, `patients` and `abha_profiles` have no ancestor; 17 need resolution. | 12.4 covers 17 tables. |
| C7 | n/a | `domain/model/SyncRetryPolicy.kt:39` still says "twenty-one tables" (there are 20). | Fix that word in commit 13. |

### 12.1 Root cause (MEASURED)

1. `WorkManagerSyncOutboxScheduler.runNowAndAwait` builds a new `OneTimeWorkRequest` with
   `NetworkType.CONNECTED` and exponential backoff from `WorkRequest.MIN_BACKOFF_MILLIS`, then
   calls `enqueueUniqueWork("sync_push_now", ExistingWorkPolicy.REPLACE, ...)`
   (`data/sync/WorkManagerSyncOutboxScheduler.kt:29-31`, `:44-48`). `REPLACE` cancels any
   `sync_push_now` already in backoff. The periodic `sync_push_periodic` is a different name and is
   untouched (`:34-40`).
2. It then waits up to 60 s (`:63`, `:84`) for: RUNNING (sets `sawRunning`), then SUCCEEDED,
   FAILED or CANCELLED, or ENQUEUED **after** RUNNING (`:64-71`). The comment at `:50-56`
   documents why ENQUEUED-after-RUNNING ends the wait: a worker that returns `Result.retry()` goes
   back to ENQUEUED, never FAILED.
3. `SyncPushWorker.doWork` returns `Result.retry()` on any drain failure
   (`data/sync/SyncPushWorker.kt:25-33`).
4. Live log, 2026-10-06 (IST): 16:55:00.919 `SyncPushWorker` request `0febb682` RETRY;
   16:55:00.932 `AssessmentRunner` "Pre-assessment sync failed for case 3762c4b1...: Sync did not
   succeed (state=ENQUEUED)"; 16:55:01.005 `AssessmentWorker` SUCCESS; 16:55:11.311 the same
   request `0febb682` SUCCESS. The gap of 10.4 s is `MIN_BACKOFF_MILLIS` (10 s).
5. So the request the runner awaited ran, its drain failed, WorkManager scheduled the retry, and
   `runNowAndAwait` returned `failure("state=ENQUEUED")` at `:76`. The 60 s timeout did not fire
   (the wait lasted under a second). Why the first drain failed is INFERRED: Android reported
   `CONNECTED` when airplane mode went off, before Wi-Fi could reach the LAN backend. The failure
   code is held only in memory (`DrainOutcomeStore`) and was not captured.
6. Both workers carry the same `CONNECTED` constraint (`WorkManagerAssessmentScheduler.kt:34-36`),
   so both woke on the same network callback. The runner logs and ignores the push Result
   (`AssessmentRunner.kt:82-84`), gates (`:87-94`), and on `Stop` persists UNAVAILABLE and returns
   (`:95-98`). `AssessmentWorker` always returns `Result.success()` (`data/assessment/AssessmentWorker.kt:18-26`, `:35-40`).
   Nothing re-enqueues the assessment once the push lands 10 s later.

The design consequence: replacing the WorkManager hop (12.2) alone would not have saved check 2,
because an in-process drain at 16:55:00 would have met the same unusable network (INFERRED from
the timing). The re-run (12.3) is what makes A1 true; 12.2 removes the hop and keeps each attempt
short.

### 12.2 Pre-assess push in process

**Shape.** `SyncStatus` gains one method, `suspend fun syncNowInProcess(): Result<Unit>`.
`SyncStatusImpl` gets `SyncOutboxDrainer` injected and runs the body of `syncNow()`
(`data/sync/SyncStatusImpl.kt:115-129`) with one change: `drainer.drain()` in place of
`syncOutboxScheduler.runNowAndAwait()` (`:122`). Factor the shared body into one private function
that takes the push as a parameter, so the two cannot drift. `AssessmentRunner.kt:82` calls
`syncNowInProcess()`. `syncNow()` (the Sync now button and the reconnect auto-sync at `:69-71`)
keeps WorkManager, because its snackbar contract depends on the bounded wait (section 4.3).

**H3, preserved exactly.** The shared body still calls `caseRecordRepository.sendAllPendingCases()`
first (`:121`), so every queued PENDING_SYNC case still flips to SENT_TO_DOCTOR, as `syncNow()`
does today. The offline refusal (`:116-118`), `isSyncing` (Home row 1 "Sending..."), and
`lastSyncedAt` on success are also shared. JVM test: the in-process path calls
`sendAllPendingCases` once and the drainer once, and never `runNowAndAwait`.

**Concurrency: an existing mutex, proven.**

- `SyncOutboxDrainer` is `@Singleton` and `drain()` is `drainMutex.withLock { drainLocked() }`
  (`data/sync/SyncOutboxDrainer.kt:34`, `:45-50`, `:54`, MEASURED). The KDoc at `:45-49` names this
  case: two `SyncPushWorker` instances under different unique names. An in-process caller in the
  same process takes the same `Mutex` object.
- Everything the brief names is touched only inside `drainLocked`: `InFlightBatchStore.load`,
  `save` and `clear` (`:147`, `:159`, `:180-185`, `:201`); `collectPendingRecords`, which runs
  inside the lock so two drains can never pack the same PENDING rows (`:100`); the per-drain
  `attempted` set, a local variable of `drainLocked` (`:86`); and `applyAck` (`:118`, `:199`).
  MEASURED by grep: no other production caller of `inFlightBatchStore`, `applyAck` or `drain()`
  except `SyncPushWorker.kt:26`.
- One process: the manifest declares no `android:process` and the build has no
  `work-multiprocess` dependency (MEASURED, empty grep). A cross-process race is out of scope by
  construction.
- `kotlinx.coroutines.sync.Mutex` is not reentrant, and `drainLocked` never calls `drain()`, so no
  self-deadlock (MEASURED, `:85-111`).
- The existing JVM test "concurrent drain calls do not both send the same PENDING rows"
  (`app/src/test/.../data/sync/SyncOutboxDrainerTest.kt:170`) already covers two concurrent
  callers. One test is added: an in-process drain and a worker drain launched together send each
  row exactly once (fake push service counts per row).

**When the in-process drain fails.** It returns `failure(SyncPushFailedException)`. The runner logs
it and does not consult it (unchanged, `AssessmentRunner.kt:82-84`). `drain()` records the failure
in `DrainOutcomeStore` (`SyncOutboxDrainer.kt:55`), so Home shows row 6 "Could not send: ..."
truthfully. The in-flight batch is NOT cleared (`:204-210`), so the next drain by anyone resends it
under the same `batch_id`. The gate then reads the chain. Server-present: the runner proceeds,
since a failed push does not undo an earlier one. CASE_NOT_SENT_YET: 12.3 retries. A5 or A6: final,
as today. If WorkManager stops the worker mid-drain (constraint lost), the `CancellationException`
unwinds `withLock`, which releases the mutex, and the in-flight batch resumes on the next drain
(existing crash-resume path, `:146-166`).

### 12.3 Re-run while the visit is still being sent

**Rule.** `AssessmentRunner.run` returns an outcome instead of `Unit`: `Done` or `RetryLater`.
`RetryLater` is returned only when the gate's decision is `Stop(CASE_NOT_SENT_YET)`, meaning
every row in the chain is PENDING or RETRYABLE and none is FAILED or CONFLICT (`AssessGate.kt:42-44`),
and the attempt is not the last. On `RetryLater` the runner writes NO kernel row, NO evaluate
failure marker and NO audit entry; it logs one line. Nothing clinical is persisted, so H-14 and the
"artifact never without audit" rule (`AssessmentRunner.kt:143-151`) still hold. Every other `Stop`
(A3, A4, A5, A6) persists at once, as today.

`AssessmentWorker` passes `runAttemptCount` in and maps the outcome. A pure function
`assessmentWorkResult(outcome)` (JVM-testable) gives `Result.retry()` for `RetryLater` and
`Result.success()` otherwise. The KDoc at `AssessmentWorker.kt:18-26` ("never Result.retry") is
rewritten to state the one exception and its cap.

**Cap and backoff.** `MAX_ASSESS_ATTEMPTS = 6`: attempts with `runAttemptCount` 0 to 4 may return
`RetryLater`; attempt 5 persists A7. Backoff stays as enqueued today: exponential from
`MIN_BACKOFF_MILLIS` (`WorkManagerAssessmentScheduler.kt:41`), giving waits of 10, 20, 40, 80 and
160 s. That is 310 s of waiting, just over `RETRY_MIN_INTERVAL` (5 minutes,
`domain/model/SyncRetryPolicy.kt:41`), so a case row that went RETRYABLE gets one more eligible
send before A7 appears (INFERRED arithmetic over MEASURED constants). WorkManager's 10-minute limit
applies per attempt, not to the whole series; each attempt is bounded by the drain's HTTP timeouts.

**What the screen shows (truthfully).**

| Work state | Network | Report row | Card |
|---|---|---|---|
| QUEUED, `runAttemptCount` 0 | off | none | A1 "Waiting for a connection" (rewritten body below) |
| QUEUED or RUNNING, `runAttemptCount` 0 | on | none | A2 "Checking this visit" |
| QUEUED (backoff), `runAttemptCount` >= 1 | on | none | NEW A2b "Sending this visit first": "This visit has to reach the server before it can be checked. This phone is trying again." |
| QUEUED, `runAttemptCount` >= 1 | off | none | A1 |
| none | any | A7 | A7 with Try again (after the cap only) |

A2b needs `observeWorkState` (`WorkManagerAssessmentScheduler.kt:47-54`) to also report
`WorkInfo.runAttemptCount` (WorkManager public API). No new Room column.

**A7 only after the cap.** No `kernel_reports` row is written for attempts 0 to 4, so the screen's
`reportDisplay` is null and the screen follows work state (`KernelAssessmentViewModel.kt:286-296`).
A7 appears only when attempt 5 persists it.

**Try again.** It is shown only once a row exists. During the retries there is no row, so there is
no button and nothing to double-press. After the cap, pressing it calls
`enqueueAssessment` with `KEEP` (`WorkManagerAssessmentScheduler.kt:44`). The previous unique work
has finished, so a new request starts with `runAttemptCount` 0 and a full new budget. While that
work is live, `isRetrying` blocks a second press (`KernelAssessmentViewModel.kt:289`, `:313`).

**No duplicate kernel or evaluate calls.** `RetryLater` is decided at the gate, before the kernel
call (`AssessmentRunner.kt:95-98` come before `:100-115`), so a retried attempt has made no call.
The `Proceed` path always ends `Done`, so a run that called the kernel is never retried. Unique
work name `assess_<id>` with `KEEP` means one live run per case (`WorkManagerAssessmentScheduler.kt:44`, `:57`).
JVM test: over a `RetryLater, RetryLater, Proceed` sequence, the fake kernel source and fake
evaluate source are each called exactly once.

**A1, rewritten.** Under 12.2 and 12.3 "will be checked by itself as soon as this phone is
connected" (`app/src/main/res/values/strings.xml:441`) is still not guaranteed. After the cap a
press is needed, and an A5 or A6 outcome means no check at all. New body: "This phone will send
and check this visit when it is connected. If that does not work, this screen will say what to
do." That is true by construction: every terminal path persists a row this screen renders, and the
stalled case has its own card (A17). Section 3.3's "no class auto-retries" is superseded for A7
only.

### 12.4 Blocked (held) records

**Definition.** A row is *held* when any ancestor in its chain is FAILED and has
`serverVersion IS NULL`. Only that ancestor cannot be on the server, so the child's foreign key can
never be satisfied (C3). A held row in PENDING or RETRYABLE:

1. is not collected by the drain;
2. does not count in Pc ("waiting to send");
3. is listed for review at once, folded under its holding ancestor.

**Ancestor resolution** (MEASURED: local columns from `data/local/entity/*Entity.kt`; server
foreign keys from `backend/core/app/models/*.py`; the patient path matches each table's existing
review query, `data/local/dao/*Dao.kt` `getFailedForReview`).

| Table | Shape | Ancestors checked |
|---|---|---|
| patients | root | none |
| abha_profiles | root | none (no server FK; `AbhaProfileDao` projects `patientId` NULL) |
| audit_log | root | none (no server FK; counted apart as Pa) |
| encounters | S1 | `patientId`; `followUpOfEncounterId` (server FK `encounters.id`), one level |
| consultations | S1 | `patientId`, `encounterId` |
| observations | S1 | `patientId`, `encounterId` |
| ailments | S1 | `patientId`, `encounterId` |
| medical_history_items | S1 | `patientId` |
| allergies | S1 | `patientId` |
| family_history_entries | S1 | `patientId` |
| social_histories | S1 | `patientId` (also its primary key) |
| medication_entries | S1 | `patientId`, `encounterId` (nullable) |
| case_records | S1 | `patientId`, `encounterId` |
| prescriptions | S1 | `patientId`, `encounterId`, `caseRecordId` |
| referrals | S1 | `patientUid`, `caseRecordId` |
| attachments | S2 | `consultationId`, then that consultation's `patientId`, `encounterId` |
| kernel_reports | S2 | `caseRecordId`, then that case's `patientId`, `encounterId` |
| evaluate_reports | S2 | `caseRecordId`, then the same as kernel_reports |
| diagnosis_feedback | S2 | `caseRecordId`, then the same as kernel_reports |
| medication_lines | S2 | `prescriptionId`, then that prescription's `patientId`, `encounterId`, `caseRecordId` |

For an ancestor that is itself a child (encounter, case, consultation, prescription), "held"
means FAILED-and-absent itself, or its own patient (or encounter) FAILED-and-absent.
`ponytail:` follow-up chains are checked one level deep. A visit following a still-unsent
follow-up of a refused visit keeps today's behaviour (counted as waiting, burns retries). The fix
is a recursive CTE, if such chains are ever seen.

**Query shapes.** `@Query` needs compile-time constants, so `SyncSql` gains one `HELD_<TABLE>`
`const val` per non-root table (17), written against the outer table's real name, for example:

```
HELD_AILMENTS = "(EXISTS (SELECT 1 FROM patients hp WHERE hp.id = ailments.patientId " +
  "AND hp.syncState = 'FAILED' AND hp.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM " +
  "encounters he WHERE he.id = ailments.encounterId AND he.syncState = 'FAILED' AND " +
  "he.serverVersion IS NULL))"
```

S2 fragments nest one `EXISTS` over the intermediate row with its own patient and encounter checks
inside. Each fragment is used by four statements per table (68 in all):

1. drain: `WHERE <PENDING_ELIGIBILITY_FRAGMENT> AND NOT <HELD_T>` (`data/local/dao/SyncSql.kt:20-22`);
2. and 3. observed and one-shot grouped counts: `SELECT syncState, <HELD_T> AS held, COUNT(*) ...
   WHERE syncState != 'SYNCED' GROUP BY syncState, held`, with `SyncStateCount` gaining a
   NON-null `held` so a misspelt alias fails the build (Room only warns on a nullable alias);
4. review: `WHERE syncState IN ('FAILED','CONFLICT') OR (syncState IN ('PENDING','RETRYABLE')
   AND <HELD_T>)`, also projecting `serverVersion`, `encounterId` and `caseRecordId` (NULL where
   the table has none) for the fold.

Lookups are by primary key, so cost is one index probe per ancestor per row (INFERRED). The
observed counts now also invalidate on writes to `patients`, `encounters` and `case_records`.
That is correct, since a parent's state change can change a child's held status. No Room
migration: queries only.

**Counting.** `outboxCountsOf` (`data/sync/OutboxCounts.kt:28-32`) splits:
Pc = PENDING + RETRYABLE, not held. New H = PENDING + RETRYABLE, held. F + C is unchanged.
By construction H > 0 implies F > 0 (a held row has a FAILED ancestor in the same tables).

**Stop resending: yes.** A held row would only meet a 23503 and burn its five attempts
(`SyncRetryPolicy.kt:26`), ending as FAILED `RETRY_EXHAUSTED` with its own Send again, which is
what produced the 18 "waiting" on the phone. The exclusion is computed on every drain, so
un-blocking is automatic. It happens the moment the ancestor leaves FAILED (Send again via
`requeueFailed`, or a future dedupe or merge path) or gains a `serverVersion`. Rows that exhausted
before this build stay FAILED and keep today's fold (Q5).

**Fold.** `foldUnderNotAcceptedPatients` (`domain/sync/FailedSyncRecord.kt:46-57`) becomes
`foldHeldRecords`. Roots are listed FAILED rows with no `serverVersion` in patients, encounters,
case_records, consultations or prescriptions, plus the existing TELL_SUPERVISOR patient rule. Each
held or exhausted descendant is assigned to its nearest root by the projected ancestor ids
(patient, then encounter, then case), and `heldRecordCount` generalises from patients to any root.
Held rows never offer Send again; the root keeps its own action.

**Home truth table (amends 4.2).** Pc now excludes held rows. Rows 2, 6 and 7 read the new Pc.
Row 6's condition "Pc + Pa > 0" uses it too, so a leftover B with only held rows falls through to
row 10. Row 10 splits:

| # | S | N | B | Pc | Q | Pa | F + C | H | Caption |
|---|---|---|---|---|---|---|---|---|---|
| 10a | no | on | none | 0 | 0 | 0 | > 0 | > 0 | "Nothing is waiting to send. {F + C} need review, and {H} records are held on this phone with them." |
| 10b | no | on | none | 0 | 0 | 0 | > 0 | 0 | "Everything else is sent. {F + C} need review." (unchanged) |

Row 4 (offline, "Nothing else is waiting to send") stays true, since held rows are not waiting.
Row 11 "Up to date" is unreachable with H > 0 because F > 0. The card count stays F + C, and each
root's line gives its held count.

**Instrumented coverage plan (iQOO, by shape, as Part A).** New `HeldAncestorQueryTest`, one
method group per representative table:

- S1: `ailments` (patient and encounter), `referrals` (the `patientUid` naming),
  `encounters` (the follow-up FK).
- S2: `kernel_reports` (via case), `attachments` (via consultation), `medication_lines` (via
  prescription).

For each table, fixtures and assertions:

- (a) patient FAILED, `serverVersion` NULL: the child is excluded from the drain query, counted
  `held = 1`, and returned by the review query;
- (b) patient FAILED, `serverVersion` 3: the child is collected and not held;
- (c) patient CONFLICT: the child is collected;
- (d) encounter FAILED with NULL (or case, or consultation, or prescription for S2): held;
- (e) after `requeueFailed` on the patient: the child is collected again;
- (f) a RETRYABLE held child past its interval: still excluded.

JVM `SyncDaoSqlContractTest` (text) pins that each of the 17 tables' four statements references its
own `HELD_<TABLE>`, and that the three roots reference none. Regressions on the iQOO:
`OutboxCountQueryTest`, `FailedSyncReviewQueryTest`, `SyncPendingEligibilityTest`,
`AssessGateSnapshotQueryTest`.

### 12.5 Timeliness and outcome promises

Grep of `strings.xml` and every Kotlin string literal in `presentation/` for "within", "hours",
"will review", "will be checked", "by itself", "on its own", "automatically", "will send", "syncs
when", "will be", "later". Twelve user-facing promises were found:

| # | Where (MEASURED) | Text | Verdict |
|---|---|---|---|
| 1 | `presentation/acknowledgement/AcknowledgementScreen.kt:64` | "Your file is secured. A doctor will review this within {24} hours." | Remove. Nothing enforces a review time; the screen comes before Send to doctor; the case may never reach the server. |
| 2 | `AcknowledgementScreen.kt:69` | Hindi of #1 | Remove with #1. |
| 3 | `presentation/doctorassignment/DoctorAssignmentConfirmScreen.kt:86` | "It will go out automatically the next time you tap Sync Up on Home once you're back online." | Rewrite. It contradicts itself (automatic and tap), and "Sync Up" does not exist (the button is "Sync now"). |
| 4 | `DoctorAssignmentConfirmScreen.kt:100` | "This case will be sent to the least-busy available doctor (mock auto-assignment):" | Rewrite to "will be assigned to"; sending is not this step. |
| 5 | `presentation/patientsummary/PatientSummaryScreen.kt:189` | "Case queued, will send when back online" (em dash in source) | Rewrite. Sending depends on sync running (and H2). |
| 6 | `presentation/profile/ProfileScreen.kt:71` | "Offline, saved locally, syncs when back online" (em dash in source) | Rewrite as Home row 2 does. |
| 7 | `res/values/strings.xml:79` `case_history_queued_will_send` | "Queued, will send when online" | Rewrite. |
| 8 | `strings.xml:441` `assessment_waiting_for_network_body` (A1) | "will be checked by itself as soon as..." | Rewritten in 12.3. |
| 9 | `strings.xml:178-180` `sync_now_sent_retry_later` | "will be tried again later" | KEEP: true. A RETRYABLE row has attempts left and the periodic drain runs every 15 minutes. |
| 10 | `strings.xml:47` | "Try again in a few minutes." | KEEP: advice, not a promise. |
| 11 | `presentation/patientsummary/PatientSummaryViewModel.kt:44-50` | "This case will be added to the training dataset..." | Not verified on device. Q6. |
| 12 | `presentation/abha/AbhaAadhaarEntryScreen.kt:75` | "ABDM will send a one-time code" | False in `ABDM_MODE=stub`. Q6. |

Count: 12 found. Rows 1 to 8 change in this PR, 9 and 10 stay, 11 and 12 go to Q6.

**"Case saved locally" (#1, #2).** The body is keyed to `CaseRecord.isOnServer`, the same
`isServerPresent` rule commit 7 introduced and commit `84fae23` reused
(`domain/model/AssessGateSnapshot.kt:30-31`, `domain/model/CaseRecord.kt:17-19`). It is observed, so
it updates if the push lands while the screen is open.

- On the server: "This visit is on the server."
- Not on the server: "This visit is saved on this phone. It is not on the server yet. The home
  screen shows when it has been sent."

Each has a separate Hindi resource under the default locale, as today's bilingual display does
(Part B ruling 4; a Hindi locale is the separate pre-pilot item). The title "Case saved locally"
stays and moves to `strings.xml`. Removing the time promise collides with REQ-TRS-03
(`docs/requirements/software-requirements.md:218-222`, `docs/requirements/traceability-matrix.md:56`),
which mandates the message and `R.integer.sync_window_hours`. `docs/end-to-end-run-guide.md:276`
quotes the old message. Both are amended in the same commit if Q1 is approved. Then
`SyncWindowProvider`, `AndroidSyncWindowProvider`, its binding in `di/MockBoundaryModule.kt` and
`res/values/integers.xml:4` are deleted (no other reader, MEASURED by grep).

All new and changed copy goes in `strings.xml`. A JVM contract test reads `strings.xml` and the
`presentation/` sources as text (declared as test-task inputs, per the Gradle mirror-test memory)
and fails on "within ... hours", "will review", "by itself", "on its own" and "automatically" in
user-facing text. It is red on master by assertion.

### 12.6 Build plan

Commits continue the section 8 numbering. "Red" follows section 8's rules (master snapshot, empty
test-results directory is RED, mutation by restoring master logic behind the new signature).

| # | Commit | Tests | Red on master / mutation | iQOO |
|---|---|---|---|---|
| 11 | `fix(assessment): push in process before the gate` (12.2) | JVM `SyncStatusImplTest`: `syncNowInProcess` calls `sendAllPendingCases` once, the drainer once, `runNowAndAwait` never; offline refusal; `isSyncing` false after a failed drain. JVM `SyncOutboxDrainerTest`: in-process plus worker drain together send each row once. JVM `AssessmentRunnerTest`: the runner calls `syncNowInProcess`, not `syncNow`. | Compile (symbol absent), then mutation: routing back through the scheduler turns the call-count test red. | no |
| 12 | `fix(assessment): retry while the visit is still being sent` (12.3, A1 and A2b copy) | JVM `AssessmentRunnerTest`, reading the persisted fakes and never `run()`'s return alone: CASE_NOT_SENT_YET at attempts 0 to 4 gives no kernel row, no evaluate marker, no audit entry, and a kernel call count of 0; at attempt 5 it persists `CASE_NOT_SENT_YET` plus audit; PATIENT_DUPLICATE and CASE_SYNC_BLOCKED at attempt 0 persist at once; `RetryLater, RetryLater, Proceed` calls kernel and evaluate once each. JVM `assessmentWorkResult`. JVM `KernelAssessmentViewModelTest`: QUEUED plus attempt >= 1 plus network gives A2b; network off gives A1. | Assertion: master persists UNAVAILABLE at attempt 0. Mutation: always persisting turns the retry tests red. | no (live check 2) |
| 13 | `fix(sync): records held by a parent the server refused are not waiting` (12.4) | Instrumented `HeldAncestorQueryTest` (12.4 plan). JVM `SyncDaoSqlContractTest` extended. JVM `foldHeldRecords` cases. JVM `SyncCaptionTest` rows 10a and 10b, and Pc excluding held. | Assertion (instrumented): master's drain query collects the held child. Mutation: dropping `AND NOT <HELD>` turns the drain tests red. | yes |
| 14 | `fix(copy): no promise the app cannot keep` (12.5; needs Q1) | JVM `AcknowledgementViewModelTest`: body follows `isOnServer` and updates on change. JVM promise-phrase contract test. | Assertion: master's strings contain "within" and "by itself". | no (live check 1 screen) |
| 15 | `docs(progress)`, then the hazard rows (12.7) as their own commits | n/a | n/a | n/a |

The PROGRESS commit also carries the two items the operator added for Step 5:

- (a) the `RemoteSlmEngineTest` timeout under parallel load;
- (b) `CASE_SENT_TO_DOCTOR` is logged on a local-only queue step
  (`presentation/doctorassignment/DoctorAssignmentConfirmViewModel.kt:170`), so the raw audit log
  still says "sent". Renaming it is a synced `audit_log` contract change and needs its own memo.

**Reruns after commit 14, dev build on the iQOO.**

- **Check 2:** airplane mode on, send a visit, A1; airplane mode off and touch nothing. Expected:
  A2 or A2b, then a real result with no press. Capture logcat for `AssessmentRunner`, `SyncPushWorker`
  and `AssessmentWorker` with timestamps.
- **Check 4:** a throwaway `git worktree` at the PR head whose `local.properties` differs only by
  `samd.dev.kernelFallback=none`, created by the operator (Q7). Confirm the build log line "dev
  kernel fallback: none" before installing. Kernel stopped gives A14 with Try again; restart the
  kernel, press it, and a real result follows.
- **Check 5:** the emulator as the second device, signed in as the same worker. Force a conflict
  with no server row edits, then read the CONFLICT row's `serverVersion` by `adb shell`: unchanged.
  If the backend refuses a second device for that worker, report the response and stop; no
  workaround.
- **Regression:** checks 1 (A5 and the fold, now with held rows folded at once and Home row 10a),
  3 and 6.

### 12.7 Hazard amendments, all PROPOSED, AWAITING OPERATOR SIGN-OFF

| ID | Action | Hazard | Harm | Controls | Residual |
|---|---|---|---|---|---|
| H-32 | AMEND | Records held behind a refused parent shown as "waiting to send" (18 on the phone, live check 1) | The worker waits for records that can never send; the supervisor is not told about them | 12.4: held rows excluded from the drain and from Pc, folded under their root at once, row 10a | Follow-up chains deeper than one level still count as waiting |
| H-33 | AMEND | A1 promised "will be checked by itself"; on reconnect the gate ran before the push landed and nothing re-ran (live check 2) | The worker waits for an assessment that never comes; the patient leaves unassessed | 12.2 in-process push; 12.3 bounded re-run (6 attempts, about 5 minutes), A2b, A1 rewritten, A7 only after the cap | After the cap the worker must press Try again; with the manual Offline toggle on (H2) the push refuses while A2 or A2b shows |
| H-36 | NEW | The app promises a clinical time or outcome it cannot guarantee ("A doctor will review this within 24 hours"), including for a case not on the server | The patient or worker relies on a review that may never happen and does not seek care elsewhere | 12.5: promises removed; acknowledgement copy follows server presence; contract test bans the phrases | No review-time control or doctor-side escalation exists; any future time promise needs one first |

### 12.8 Open questions for the operator

1. **Q1. REQ-TRS-03.** Amend the requirement to "the acknowledgement states where the visit is (this
   phone or the server), with no time promise until a review-time control exists"? Then delete
   `SyncWindowProvider` and `sync_window_hours`. Default: yes.
2. **Q2. CONFLICT ancestors (C3).** Hold descendants only behind a FAILED ancestor with no
   `serverVersion`, not behind CONFLICT? Default: yes. Related: the gate's rule 4 treats a CONFLICT
   patient or encounter as A6 (`AssessGate.kt:42-44`), although the server holds it and the case
   could still send. Align it in commit 12, so that becomes CASE_NOT_SENT_YET and retries?
   Default: yes.
3. **Q3. Retry without a network (C4).** Retry CASE_NOT_SENT_YET whatever the network state, with
   the network only choosing A1 or A2b? Default: yes.
4. **Q4. Cap.** 6 attempts, about 310 s of backoff, sized to `RETRY_MIN_INTERVAL`? Default: yes.
5. **Q5. Legacy exhausted children.** When a held root is sent again, also requeue its descendants
   that are FAILED `RETRY_EXHAUSTED` from before this build? Default: no (each keeps its own Send
   again once the root lands).
6. **Q6. Copy outside the sync path.** The training-dataset outcome text
   (`PatientSummaryViewModel.kt:44-50`) and "ABDM will send a one-time code"
   (`AbhaAadhaarEntryScreen.kt:75`, false in stub mode): file them as their own item rather than PR 1?
   Default: file.
7. **Q7. Check 4 worktree.** The hard rule forbids me opening `local.properties`. Will the operator
   create the worktree's copy with the one added line? Default: operator.
