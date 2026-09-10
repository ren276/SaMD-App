# AUDIT5: the uncommitted Antigravity work (read-only)

Run date 2026-09-10. Read-only characterization. No file in the working tree was changed by this
audit; the only file written is this one. `backend/core/.env` exists and is gitignored
(`.gitignore:32`). It was not opened, read, or referenced beyond noting its existence.

Branch `feat/dynamic-local-connection` at `293f3ab`, identical to `master`. Nothing committed.
Two handoff docs describe the work. They are treated as claims. The working tree wins every
disagreement, and there are several.

Verified state: `./gradlew testDevDebugUnitTest --tests '*AssessmentRunnerTest*' --tests
'*DevDynamicHostInterceptorTest*'` is `BUILD SUCCESSFUL`. Both new test classes pass.

---

## Part 1: inventory versus claims

### 1.1 What is actually in the tree

13 modified tracked files, 7 new untracked source files, plus untracked docs and a stray HTML
dump.

| Path | What actually changed |
|---|---|
| `app/src/main/.../domain/usecase/AssessmentRunner.kt` | +58. Two new constructor params, a Stage-0 drain-and-gate block at `:68-102`, three section comments, one stray double blank line at `:57-58`. |
| `app/src/main/.../data/local/dao/CaseRecordDao.kt` | +6. `suspend fun getSyncState(caseRecordId: String): SyncState?` at `:36-38`. |
| `app/src/main/.../data/sync/SyncOutboxDrainer.kt` | +5. Now `: OutboxDrainer`, `override suspend fun drainAll(): Result<Unit> = drain()` at `:51`. |
| `app/src/main/.../di/RepositoryModule.kt` | +23. `@Binds` `OutboxDrainer` to `SyncOutboxDrainer` at `:101-102`, and a `companion object` `@Provides` adapting the DAO to `GetCaseRecordSyncState` at `:146-157`. |
| `app/src/main/.../di/NetworkModule.kt` | +16. `DevDynamicHostInterceptor` added as a constructor param to BOTH `provideOkHttpClient` (`:85`) and `provideAbhaOkHttpClient` (`:117`), each conditionally installed under `BuildConfig.ENVIRONMENT == "dev"`. `@Binds` for `DevServerConfig` at `:204-206`. Fully-qualified type names inline rather than imports. |
| `app/src/test/.../AssessmentRunnerTest.kt` | +74. Two new `Fixture` params, four new tests. |
| `app/src/dev/AndroidManifest.xml` | +10. `DevServerReceiver` registered `android:exported="true"`, no `android:permission`. |
| `app/build.gradle.kts` | +38. `resolveDevHostIp()` at `:27-49`, dev-flavor `auto` resolution at `:87-94`, `unitTests.isReturnDefaultValues = true` at `:173`. |
| `gradle.properties` | 1 line. `-Djava.io.tmpdir=/media/sandesh/extra-ssd/tmp -Dorg.sqlite.tmpdir=...` appended to `org.gradle.jvmargs`. |
| `backend/docker-compose.yml` | 1 line. `ABDM_MODE: stub` changed to `ABDM_MODE: live` at `:36`. |
| `backend/README.md` | +24. New "Switching ABHA / ABDM Mode" section. |
| `backend/core/app/scripts/seed_accounts.py` | +30/-6. `must_change_pin: bool = True` param, `--no-must-change-pin` flag. |
| `.idea/deploymentTargetSelector.xml` | Deploy target switched from AVD to physical device `10BE3A09C700046`. IDE config. Noted and dismissed. |

New untracked source: `DevServerReceiver.kt`, `DevDynamicHostInterceptor.kt`, `DevServerConfig.kt`,
`OutboxDrainer.kt`, `GetCaseRecordSyncState.kt`, `DevDynamicHostInterceptorTest.kt`,
`tools/dev-connect.sh`.

### 1.2 Handoff claims the code does not support

**FALSE. `handoff-for-claude.md` §5.2: "Schema is still at v13 (`MIGRATION_12_13`)."**
`AppDatabase.kt:75` reads `version = 19`, and `app/src/androidTest/.../data/local/` contains
`MigrationTest18To19.kt`. The schema is at v19 and has been for some time. The conclusion drawn
from the claim (no migration needed) happens to be correct, because `getSyncState` reads a column
that already exists. The stated fact is wrong, and it is wrong in a way that suggests the tool did
not read `AppDatabase.kt`.

**FALSE. `handoff-dynamic-local-connection.md` §2.C: `DevDynamicHostInterceptor` is an
"OkHttp `Interceptor` installed only in dev flavor".**
The class is in `app/src/main/java/`, not `app/src/dev/java/`. It compiles into every flavor,
including prod release, and Hilt constructs it in every flavor (see 3.4). Only its *installation
into the chain* is conditional. Same for `DevServerConfig.kt`, whose `RealDevServerConfig` KDoc
at `:25` literally calls itself the "Production implementation".

**FALSE, and the interesting one. Both handoffs: the interceptor "safeguards third-party requests
(e.g. Google Gemini API) from being rewritten."**
The Gemini stack has its own `OkHttpClient` from `GeminiNetworkModule.provideGeminiOkHttpClient`
(`GeminiNetworkModule.kt:46-52`) with no interceptors at all. A Gemini request never reaches
`DevDynamicHostInterceptor`. The safeguard at `DevDynamicHostInterceptor.kt:32-35` is dead code
with respect to Gemini, and `DevDynamicHostInterceptorTest.externalRequests_arePassedThroughUnchanged`
tests a path that does not exist in the wiring. It asserts a property of the interceptor in
isolation, not a property of the app.

**FALSE. `handoff-dynamic-local-connection.md` §2.A: the branch was "Branched From
`fix/sync-before-assess-v2`".**
No such branch exists in the repo (`git log --oneline --all` has no reference to it). The branch
points at `293f3ab`, which is `master`. `handoff-for-claude.md` gets this right and the other one
does not. They contradict each other, as the brief said.

**UNVERIFIABLE. Both handoffs: end-to-end verification on device `10BE3A09C700046`, case
`bc51fb0d-bbee-466b-9901-956a61dc45e4`, "Type 2 diabetes mellitus (97.98% confidence)".**
There is no artifact in the tree supporting this: no log capture, no screenshot, no test. The
`.idea` diff confirms only that the deploy target was pointed at that serial. Treat the clinical
result as an unsourced claim.

**MISLEADING. `handoff-for-claude.md` §1.1 calls the sync-before-assess problem a "Race Condition".**
It is a race only for the subset of cases that are merely late. For a case whose parent patient was
rejected `SAMD-SYNC-6003`, there is no race: the row is permanently `FAILED` and no amount of
waiting fixes it. See Part 2.4.

### 1.3 Changes the handoffs do not mention, or mention wrongly

**`backend/docker-compose.yml` flipped `ABDM_MODE: stub` to `ABDM_MODE: live`.**
`handoff-for-claude.md` lists the file under "[MODIFIED]" with no description.
`handoff-dynamic-local-connection.md` §2.F describes only the README addition and does not mention
the compose change at all. This is the single highest-impact undocumented change in the tree: the
local backend now transacts against the real ABDM sandbox on every `docker compose up`.

Worse, the README the same tool wrote directly contradicts the compose file it also edited.
`backend/README.md:57` states "`docker-compose.yml` sets `ABDM_MODE: stub` by default so local
development and unit tests do not depend on external sandbox connectivity", and instruction 1 tells
the reader to "Change `ABDM_MODE: live`" as a deliberate act. It is already `live` in the same
commit-less working tree. Documentation and artifact disagree at the moment of authorship.

**`app/build.gradle.kts:173`, `unitTests.isReturnDefaultValues = true`.**
Mentioned by both handoffs as a convenience so `android.util.Log` "does not throw Method not
mocked". Neither states its actual scope: it is a project-wide `testOptions` setting that makes
*every* `android.*` framework method return `null`/`0`/`false` in *every* host unit test in this
module, instead of throwing. See Part 5.2.

**`local.properties`.** Both handoffs list it as modified. It is gitignored
(`.gitignore:15`) and does not appear in `git status`. Not a tree change; noted so the inventory
is complete.

**`dashboard.html`** (untracked, repo root, 9.4 KB). Mtime 2026-08-24, which predates this work, so
it is almost certainly not Antigravity's. Neither handoff mentions it. It is a rendered dump of the
dev database containing worker ids, display names, roles, facility ids, login timestamps and audit
`entry_hash` values. Both listed accounts show `must_change_pin` `False`. It must not be committed.

**`docs/end-to-end-run-guide.md` (327 lines) and `docs/product-and-development-report.md` (297
lines)**, untracked, mtimes 2026-09-10 14:22 and 14:26, inside the Antigravity window. Neither
handoff mentions either file. The second is a regulatory-framing document ("CDSCO Medical Device
Rules 2017 (Class B/C), IEC 62304 (Class B), ISO 14971") authored by an AI tool with no stated
review. It has not been checked for accuracy by this audit and should not be treated as a
controlled document on the strength of its own header.

---

## Part 2: the sync-before-assess fix (A)

### 2.1 The trace

1. `AssessmentRunner.run` `:69-82` calls `outboxDrainer.drainAll()` inside a try/catch that
   rethrows `CancellationException` and logs everything else. Failure is deliberately non-fatal.
2. `SyncOutboxDrainer.drainAll` (`:51`) delegates to the existing `drain()`, which takes
   `drainMutex` and runs the real drain loop. This is the correct choice: it reuses the existing
   process-wide mutex rather than adding a second drain path, and it bypasses WorkManager, so the
   drain genuinely completes before the next line runs. No new machinery.
3. `getCaseRecordSyncState(caseRecordId)` `:87-95`, wrapped the same way, `null` on exception.
4. Gate at `:96-102`: `if (syncState != null && syncState != SyncState.SYNCED)` then
   `generateKernelReportUseCase.recordUnavailable(caseRecordId)` and `return`.
5. `CaseRecordDao.getSyncState` `:36-38`, `SELECT syncState FROM case_records WHERE id = :id`,
   returns `SyncState?`. Converter exists (`Converters.kt:93-94`). Missing row gives `null`.
6. DI: `RepositoryModule.kt:101-102` binds the drainer; `:146-157` provides the SAM via a
   `companion object` `@Provides` closing over `CaseRecordDao`.

The extraction of `GetCaseRecordSyncState` into its own file to keep the DAO out of `domain/` is
correct layering and the `@Provides` adapter is about as thin as it can be. This part is clean.

### 2.2 Does it gate on real persisted state, and does it fail fast

Yes to both. The gate reads the actual Room column, not a response object, not a flag the drain
returned. `drainAll()`'s `Result` is explicitly *not* trusted: its failure is logged and execution
continues to the state read. That is the correct inversion of the batch-200-trust failure mode the
project has hit four times, and it is the strongest thing about this change. When the state is not
`SYNCED`, no `/assess` call is made at all.

### 2.3 The tests, and the mutation question

**The tests assert persisted behavioural state, not an HTTP response and not a mock's return
value.** Every new assertion reads `fixture.kernelReportRepository.saved["case-1"]?.inferenceSource`,
which is the write target of `recordUnavailable`, plus a `kernelCalled` boolean proving the network
call was skipped. This is the shape the project's own convention requires, and the test class KDoc
at `AssessmentRunnerTest.kt:41-45` already states the rule. Failure mode #4 is not present here.

**Mutation check: attempted, blocked, resolved analytically.** The runtime mutation (temporarily
neutering the gate condition, rerunning, restoring from a hashed backup) was refused by the
sandbox because it required writing to a repo file. The read-only constraint held. The outcome is
nonetheless determinable from the code, with certainty rather than inference:

- `Fixture` defaults supply a resolvable case: `defaultCaseRecord()` id `case-1`, encounter `enc-1`,
  a `VitalsSnapshot` for `enc-1`, a `testConsultation("enc-1")`, `defaultEncounter()`, and a
  registered `testPatient("p1")`. `AssessmentRunnerTest.kt:113-127`.
- The same defaults with `getCaseRecordSyncState = { SyncState.SYNCED }` are proven to reach
  `InferenceSource.REAL_INFERENCE` by the passing test at `:236-247`.
- Therefore with the gate removed, `syncState = PENDING` or `FAILED` changes nothing about
  stages 1 to 4: `resolve()` succeeds, `AlwaysSucceedsKernelSource` is reached, `kernelCalled`
  becomes `true` and the saved `inferenceSource` becomes `REAL_INFERENCE`.
- Both assertions in each of the two gating tests (`:250-268` and `:270-288`) invert. **Four
  assertions go red.** These tests would catch a real regression of the gate.

For the operator who wants the empirical confirmation, it is one edit and one command:

```
# in AssessmentRunner.kt:96, change the condition to `if (false && ...)`
TMPDIR=/media/sandesh/extra-ssd/tmp ./gradlew testDevDebugUnitTest --tests '*AssessmentRunnerTest*'
# expect: 2 failures, 'case record still PENDING...' and 'case record FAILED...'
```

**Coverage gaps the tests do have:**

- **No test for `syncState == null`.** The row-missing path (`:96` short-circuits on `null` and
  proceeds to the assess call) is only incidentally covered by the pre-existing `"no-such-case"`
  test, which fails earlier in `resolve()` for a different reason. The deliberate "unknown, let the
  backend decide" decision is untested.
- **No test at all for `CaseRecordDao.getSyncState`.** `grep` for `getSyncState` across `app/src/`
  returns three files, all production. There is no Room instrumentation test, despite
  `app/src/androidTest/.../data/local/` holding fifteen DAO and migration tests including
  `SyncStateResetTest.kt`. The new query is the load-bearing input to a clinical gate and no test
  reads it out of a real database.
- **Ordering is not asserted.** `drainCalled` is a boolean. Nothing proves the drain ran *before*
  the state read rather than after.

### 2.4 Root cause or symptom: SYMPTOM

Plainly: **this fixes the race and does not fix the root cause.**

What it genuinely fixes: a case_record that is merely late reaching the server. Stage 0 drains it,
the state read confirms `SYNCED`, and `/assess` succeeds. That is real and it is the scenario the
handoff verified.

What it does not fix, and what AUDIT4 §3.2 and §2.1 already documented:

- A patient rejected on `ix_patients_abha_number` lands `SyncState.FAILED`
  (`SyncAckMapping.kt:14`, per AUDIT4).
- Its children fail their FK with sqlstate 23503 and also land `FAILED` (AUDIT4 §2.1 item 3).
- **The outbox only ever collects `PENDING` rows.** `CaseRecordDao.kt:22`,
  `PatientDao.kt:18`, both `WHERE syncState = 'PENDING'`. There is no requeue of `FAILED`, no
  retry, no operator path. A `FAILED` row is terminal.
- Therefore `drainAll()` cannot help such a case. It collects nothing for it, on this run or any
  future run. The gate then reads `FAILED` and records `UNAVAILABLE`.

**Net effect for the duplicate-ABHA case: identical clinical outcome (permanent `UNAVAILABLE`),
reached faster and without a wasted network round trip.** That is a genuine but small improvement.
The patient is still permanently unsyncable, the case is still permanently unassessable, and there
is still no device-side representation distinguishing "someone else holds this ABHA" from "the
network is down". AUDIT4's finding stands untouched.

The Stage-0 KDoc at `AssessmentRunner.kt:32-40` describes only the race. It does not mention the
`FAILED`-is-terminal case, which means the next reader will believe the gate is a transient
condition when for a whole class of cases it is permanent.

### 2.5 Two real defects in the fix

**(a) The Stage-0 gate deletes the audit record for the most common `UNAVAILABLE` path.**
`AssessmentRunner.run` emits `AuditAction.KERNEL_RESPONSE_RECEIVED` at `:158-166`, at the very
bottom of the method. The Stage-0 gate returns at `:101` without logging anything.

Before this change, an offline or unsynced assessment ran stages 1 to 4, the kernel call failed,
`buildUnavailableOutput` produced the report, and `KERNEL_RESPONSE_RECEIVED` fired with
`inferenceSource = UNAVAILABLE`. After this change, that exact case returns at Stage 0 and **no
audit entry is written at all**, while a clinical artifact (`recordUnavailable` persists a kernel
report via `GenerateKernelReportUseCase.kt:124-127`) still is.

Stage 0 will be the dominant `UNAVAILABLE` path in the field, because "not synced" and "offline"
are the same condition. So this is not an edge case, it is the common case, and it silently
removes the audit trail from it. In a Class B/C SaMD with an append-only audit log this is the
most serious defect in body (A). It should block the commit until fixed.

The pre-existing `resolved == null` early return at `:120-123` has the same gap, so the new code
is consistent with the old. That makes it a pattern worth fixing in both places, not an excuse.

**(b) The gate uses local transport state as a proxy for server presence, and they diverge.**
`CaseRecordDao.kt:47`, `:52`, `:69` and `:80` all reset `syncState = 'PENDING'` on clinical
mutation (the documented syncstate-reset policy). A case_record that the server already holds is
returned to `PENDING` by any subsequent local edit. If the drain then fails for a reason unrelated
to reachability (a 401 that cannot be refreshed, a corrupt `InFlightBatchStore` which per AUDIT4
§2.1 item 8 fails the whole drain, an oversized sibling record), the gate now records `UNAVAILABLE`
for a case the backend could have assessed. Before this change that assess call would have
succeeded.

Narrow, but real, and it converts a success into a clinical `UNAVAILABLE`. The gate's comment at
`:83-86` asserts "the assess call will 404 guaranteed", which is not true in this scenario.

### 2.6 One genuine improvement worth keeping in mind

In a dev build, the pre-fix offline path fell through `GenerateKernelReportUseCase` to
`kernelFallbackSource`, which is a mock, and labelled the result `MOCK_FALLBACK`. The Stage-0 gate
short-circuits that entirely and records an honest `UNAVAILABLE`. Fewer opportunities for a
fabricated result to reach a screen. That is aligned with the project's fabrication-removal
decisions.

---

## Part 3: the dynamic-connection layer (B), and the OTP regression

### 3.1 Characterization

**`RealDevServerConfig`** (`DevServerConfig.kt:31-128`):
- Active URL held in a `@Volatile` field, initialized from `SharedPreferences("samd_dev_server_config")`,
  falling back to `BuildConfig.BACKEND_BASE_URL` (`:46-50`).
- `setBaseUrl` persists (`:54-59`). **Every failover write is sticky across app restarts.**
- Probe client: connect 1000 ms, read 1000 ms, call 2000 ms (`:40-44`, `:127`).
- Candidate order, hardcoded (`:68-88`): `http://127.0.0.1:8080/` first, then
  `BuildConfig.BACKEND_BASE_URL`, then `http://10.0.2.2:8080/`, minus any whose host equals the
  failed host.
- `tryFailover` (`:94-121`) is `@Synchronized` and **blocking**. It walks candidates, `GET`s
  `<candidate>/health`, and on the first success calls `setBaseUrl` and returns. Catches
  `IOException` only.

**`DevDynamicHostInterceptor`** (`DevDynamicHostInterceptor.kt:28-91`):
- `:33-35` early return for non-local hosts. This exclusion covers both rewriting and failover,
  since it returns before either.
- `:40-48` rewrites scheme, host and port of the outgoing request to the active dev URL.
- `:50-74` runs the call. On `ConnectException` or `SocketTimeoutException` only, calls
  `tryFailover(activeHost)`, and on recovery **re-issues the same request against a different
  host** via a second `chain.proceed` at `:69`.
- `isLocalBackendTarget` (`:77-91`) matches `127.0.0.1`, `localhost`, `10.0.2.2`, the BuildConfig
  host, the active host, and then any host starting with `10.`, `192.168.`, or `172.`.

**`DevServerReceiver`** (`app/src/dev/java/.../receiver/DevServerReceiver.kt`): `@AndroidEntryPoint`
`BroadcastReceiver`, action `com.example.samdapp.dev.SET_BACKEND_URL`, sets and persists the URL.

### 3.2 The OTP regression: CONFIRMED, with the mechanism

The ABHA stack rides `BACKEND_BASE_URL`. `NetworkModule.provideAbhaRetrofit:132-137` sets
`.baseUrl(BuildConfig.BACKEND_BASE_URL)`, and `provideAbhaOkHttpClient:114-129` now installs
`DevDynamicHostInterceptor` on that client. In dev, `BACKEND_BASE_URL` is
`http://<LAN IP>:8080/`, a `10.x` address, so `isLocalBackendTarget` returns `true` at
`DevDynamicHostInterceptor.kt:90`. **Every ABHA call, including Aadhaar identity submit and both
OTP verifications, is in scope for both rewriting and failover-retry.**

Answering the three sub-questions precisely:

**(a) Does the 1 s health check add latency to the first request after a network change? NO.**
There is no proactive probe anywhere. `tryFailover` is called from exactly one place,
`DevDynamicHostInterceptor.kt:56`, inside an `IOException` catch. The first request after a network
change goes straight to the persisted URL at full speed. Worst-case added latency is roughly 2 to 6
seconds and it is added *after* a failure has already occurred, so it cannot itself cause an OTP
send to time out. This hypothesis is not the cause.

**(b) Can the interceptor silently retry a stateful, non-idempotent OTP request against a different
host mid-flow? YES. This is the bug.**

`DevDynamicHostInterceptor.kt:50-74` has no method check, no path check, and no idempotency
concept. A `POST api/v1/abha/registration-sessions/{id}/identity` that raises
`SocketTimeoutException` is replayed verbatim at `:69`.

The decisive detail is *what the failover probes*. `tryFailover` probes `/health`
(`DevServerConfig.kt:100`), which is a trivial liveness endpoint. The ABHA path is the slow one: the
backend forwards to the live ABDM gateway (see 3.5, `ABDM_MODE` is now `live`), which does cert
fetch and RSA-OAEP work server-side. So the failure mode is:

1. App `POST`s identity submit. The 30 s `readTimeout` (`NetworkModule.kt:119`) starts.
2. The backend receives it and asks ABDM to send the OTP. **The OTP is sent to the patient's
   phone.**
3. ABDM is slow, or the sandbox stalls. The read exceeds 30 s. OkHttp raises
   `SocketTimeoutException`.
4. The interceptor catches it at `:54` and calls `tryFailover`.
5. `/health` on `127.0.0.1:8080` (or the LAN IP) answers `200` instantly, because the backend is
   perfectly healthy. It is only the ABDM leg that is slow.
6. The interceptor concludes the *host* failed, and **replays the identity submit** at `:69`.
7. The backend performs a second ABDM identity submission. ABDM either issues a second OTP that
   invalidates the first, or rejects the transaction for session/txn state, or throttles.
8. The user sees a connection error, or an OTP that does not work, or no OTP.
9. Going back and retrying starts a clean session, which succeeds.

That is the reported symptom, exactly: intermittent, correlated with slowness rather than with a
dead host, and cured by a manual restart of the flow. **A slow but entirely healthy backend is
sufficient to trigger a spurious non-idempotent replay.** The failover's health signal
(`/health` is up) is orthogonal to the failure it is reacting to (this specific call timed out).

A second, independent contributing path: the persisted active URL. Once `tryFailover` latches onto
`http://127.0.0.1:8080/` (candidate 1, always probed first) it is written to SharedPreferences and
survives restarts. The handoff itself confirms the device is in this state:
"`shared_prefs/samd_dev_server_config.xml` actively set to `http://127.0.0.1:8080/`". That address
only resolves while an `adb reverse` tunnel is live. Unplug USB, restart adb, or let the tunnel
drop, and every backend call including OTP now fails first against `127.0.0.1`, then failover-probes,
then retries. That produces the "connection error, works on retry" half of the report.

**(c) Does it exclude the ABHA/ABDM host and third parties from rewrite AND failover?**
- Third-party hosts: excluded from **both**, correctly, by the early return at `:33-35`. But the
  exclusion is moot for the named example: Gemini never reaches this interceptor (see 3.6).
- ABHA/ABDM: **not excluded from either.** There is no exclusion by path, by HTTP method, or by
  API surface anywhere in the class. ABHA traffic goes to our own backend host, so it is
  indistinguishable from any other backend call to `isLocalBackendTarget`.

**Verdict: the reported symptom is explained by this layer, at
`app/src/main/java/com/example/samdapp/data/remote/dev/DevDynamicHostInterceptor.kt:50-74`, the
unconditional replay of a non-idempotent request; enabled for ABHA by
`app/src/main/java/com/example/samdapp/di/NetworkModule.kt:117` and `:121-125`, which install the
interceptor on the ABHA client.**

Contributing: the sticky `127.0.0.1` persistence at `DevServerConfig.kt:57` combined with
`127.0.0.1` being candidate 1 at `:72`; and the `ABDM_MODE: live` flip at
`backend/docker-compose.yml:36`, which introduced the real-gateway latency that makes step 3
reachable in the first place.

### 3.3 Other defects in the same file

- **`DevDynamicHostInterceptor.kt:90`, `(host.startsWith("172.") && host != "172.217.")`.** `host`
  is a full hostname such as `172.217.14.206`; it is never the literal string `"172.217."`. The
  right-hand clause is always `true` and the guard is inert. Any `172.*` host, including public
  Google address space, is classified as local backend. Harmless today only because the two clients
  carrying this interceptor never target such a host.
- **`DevDynamicHostInterceptor.kt:41`, `.scheme(activeBaseUrl.scheme)`.** The rewrite carries the
  active URL's scheme, so an `https` request is silently downgraded to `http` if the active URL is
  `http`. Not reachable today (dev is cleartext by design) but it is a downgrade primitive sitting
  in the request path.
- **`DevServerConfig.kt:114` catches `IOException` only.** On Android 17+ with
  `ACCESS_LOCAL_NETWORK` enforced and not granted, a blocked LAN socket can surface as a
  `SecurityException`, which is a `RuntimeException`. It would escape `tryFailover`, escape the
  interceptor's `catch (e: IOException)` at `:52`, and propagate out of an OkHttp interceptor
  uncaught. `RetrofitAbhaSource.call` (`RetrofitAbhaSource.kt:116-140`) catches `RuntimeException`
  and maps it to `AbhaApiResult.ProtocolViolation("Malformed response body from the backend")`,
  which is flatly wrong: the backend was never reached and the real cause is a permission denial.
  `ProtocolViolation` is also encoded downstream as non-retryable. See 3.4.
- **Ports are never varied.** All three candidates hardcode `:8080` (`DevServerConfig.kt:72`,
  `:81`), while the rewrite at `:44` copies the candidate's port over whatever the original request
  used. Anything on another port that matched `isLocalBackendTarget` would be silently repointed at
  8080.

### 3.4 Interaction with the committed local-network permission work

`classifyLocalNetworkFailure` (`domain/connectivity/LocalNetworkFailure.kt:38-48`, the three-state
discriminator from `121a37f`) has exactly one caller: `CompounderViewModel.kt:191`, on the Pi
gateway path. The OkHttp backend path never consults it. So **the new interceptor does not corrupt
the discriminator's own logic; they are disjoint code paths.** The prime suspect named in the brief
is not confirmed in that direct form.

There are two real interactions, both worth recording:

1. **The interceptor probes LAN hosts with no permission awareness at all.** `tryFailover` opens
   sockets to `10.x` / `192.168.x` candidates (`DevServerConfig.kt:99-117`) without consulting
   `ACCESS_LOCAL_NETWORK_PERMISSION`, `ACCESS_LOCAL_NETWORK_ENFORCED_SDK`, or
   `classifyLocalNetworkFailure`. On an API 37+ device before the permission is granted, those
   probes fail and the misclassification chain in 3.3 fires: a permission denial is reported to the
   ABHA UI as a malformed backend response.
2. **Candidate 1 is permission-free and permanently preferred.** `127.0.0.1` needs no local-network
   permission, so on a permission-denied device the failover will always latch there, persist it,
   and appear to work while USB is attached. This masks the very condition
   `PermissionAction.kt`/`LocalNetworkFailure.kt` were built to surface. The permission problem
   becomes invisible in dev and reappears on any build without a tunnel.

### 3.5 Dev-only containment: NOT structurally contained

This is the clearest structural finding in the audit.

`DevDynamicHostInterceptor.kt` and `DevServerConfig.kt` live in **`app/src/main/java/`**, not
`app/src/dev/java/`. Consequences, in order of seriousness:

1. **Both classes ship in staging and prod APKs.** The project's own established pattern is the
   opposite: `app/build.gradle.kts:97-99` documents `PiGatewayVitalsSource` as safe precisely
   because "the class does not exist outside dev". These two classes do exist outside dev.
2. **`optimization { enable = false }` on the release build type** (`app/build.gradle.kts:141-145`)
   means R8 does not run, so the dead branch is not shrunk away and the classes are not stripped.
   The `BuildConfig.ENVIRONMENT == "dev"` check will be constant-folded by the Kotlin compiler, but
   the classes remain.
3. **Hilt constructs `DevDynamicHostInterceptor` in every flavor.** It is a constructor parameter
   of `provideOkHttpClient` (`NetworkModule.kt:85`) and `provideAbhaOkHttpClient` (`:117`). Dagger
   must satisfy the parameter before the method body, and therefore before the `if`. That
   constructs the `@Singleton` `RealDevServerConfig`, whose property initializers run at
   construction: it opens a `SharedPreferences` file named `samd_dev_server_config`
   (`DevServerConfig.kt:35`) and builds a second `OkHttpClient` (`:40-44`) **in a production
   build**. A prod install creates a dev-named preferences file it never uses.
4. `NetworkModule.Bindings.bindDevServerConfig` (`:204-206`) is in the main module, so
   `DevServerConfig` is injectable anywhere in a prod build. Today only the interceptor and the
   dev-only receiver inject it. Nothing structural prevents a future caller.

**Is auto-failover functionally reachable in production? No.** The interceptor is not added to
either chain outside dev, so no request is rewritten and `tryFailover` is never called. The
functional containment holds. The **structural** containment does not, and this project has an
explicit convention (documented in its own build file) that dev-only code lives in `src/dev/`.
`DevServerReceiver` follows that convention correctly. The other two do not.

**Separate finding, dev only but sharp: the receiver is exported with no permission.**
`app/src/dev/AndroidManifest.xml:5-12` declares `android:exported="true"` with an intent filter and
**no `android:permission`**. Any application installed on the same device can broadcast
`com.example.samdapp.dev.SET_BACKEND_URL` to `com.example.samdapp.dev` and permanently repoint
every backend call, including Aadhaar identity submission and OTP verification, at a host it
controls. There is no validation of the URL in `DevServerReceiver.kt:23-33` beyond non-blank. Given
that the dev flavor is the build used for demos and is now pointed at the live ABDM sandbox, this
deserves a signature-level `android:permission` before the branch is used again.

### 3.6 The Gemini path: real, pre-existing, and BENIGN, with one caveat

It exists and is not part of this diff. `GeminiBrandLookupSource`
(`app/src/main/java/com/example/samdapp/data/remote/GeminiBrandLookupSource.kt`) calls
`https://generativelanguage.googleapis.com/`, bound as `BrandLookupSource` by
`GeminiNetworkModule.kt:73-74`, and reached from `GenerateEvaluateReportUseCase.kt:63`.

**What is sent.** One string, the generic drug name, inside a fixed prompt
(`GeminiBrandLookupSource.kt:35-38`): "For the generic drug \"X\", name the top-selling brand sold
in India by an Indian pharmaceutical company... Reply in EXACTLY this format... BrandName |
CompanyName".

**No patient data leaves the device on this path.** No name, no age, no sex, no ABHA, no Aadhaar,
no vitals, no complaint text, no case token, no facility id. The generic drug name is itself an
output of the NLEM evaluate step, not patient-identifying. `IndianBrandSuggestion` comes back as
two strings.

**It is correctly fenced.** Gated on a non-blank `BuildConfig.GEMINI_API_KEY` (`:33`), which
defaults to empty (`app/build.gradle.kts:74`), so an unconfigured build never calls out. Every
failure returns `null` (`:63-65`) and the KDoc at `:20-23` states it must never fail the pipeline.
Separate `OkHttpClient` with short timeouts. Never in the diagnostic or triage path.

**Verdict: benign.** It does not contradict the DPDP-localised architecture in the way the brief
feared, because it carries no personal data. Two things to record rather than escalate:

- It is a cloud LLM whose output is displayed next to a prescription recommendation
  (`PatientSummaryScreen.kt:275`, `EvaluateReportOutput.kt:20`). The brand and manufacturer are
  unverified model output shown to a clinician. That is a clinical-content-provenance question for
  the regulatory file, not a data-residency one, and it predates this work.
- The API key is compiled into `BuildConfig` from `local.properties` and is therefore extractable
  from any APK that has one set. Pre-existing, not this diff.

**The interceptor's Gemini safeguard is dead code.** Gemini has its own interceptor-free client
(`GeminiNetworkModule.kt:46-52`), so `DevDynamicHostInterceptor.kt:32-35` never sees a Gemini
request, and `DevDynamicHostInterceptorTest.externalRequests_arePassedThroughUnchanged` tests a
wiring that does not exist. The safeguard is not harmful, but it is not the protection the handoff
claims it is, and the test gives false assurance.

---

## Part 4: security-adjacent and misc

### 4.1 `seed_accounts.py --no-must-change-pin`: NOT dev-only, and it breaks a documented invariant

**Reachability, plainly: there is no guard of any kind.** `grep` for `ENVIRONMENT`, `settings`, or
`environment` in `backend/core/app/scripts/seed_accounts.py` returns nothing. The script binds to
whatever `DATABASE_URL` the environment supplies via `app.db.session.get_sessionmaker`. Per
`backend/README.md`, "There is no self-service registration, no self-service PIN reset, and no
user-management API", which makes this script **the** production provisioning path. An
administrator can run it against a production database with `--no-must-change-pin` and it will
work.

**What the flag actually disables.** `backend/core/app/deps.py:91`, `if worker.must_change_pin:`,
is the gate that forces the PIN change before anything else is permitted. Setting the column
`False` at creation means an administrator-issued PIN, printed to a terminal, is immediately a
long-lived working credential.

**It contradicts the file's own stated decision.** `seed_accounts.py:3-6` still reads: "Decision
D-3: the facility administrator provisions accounts with this script and hands the initial PIN to
the worker in person. must_change_pin is true on every account created here, so the
administrator-issued PIN is a one-time credential and cannot become a long-lived one." That
sentence is now false and was not updated.

**Severity, fairly stated:** it is opt-in, off by default, requires an operator to type the flag,
and does not change any existing account or any other code path. It does not silently weaken a
deployment. But it removes a structural guarantee from the one production provisioning tool, in a
system whose auth design explicitly documented that guarantee as load-bearing, and it did so with
zero environment guard and a stale docstring. `dashboard.html` shows both existing dev accounts
already at `must_change_pin` `False`, which is what the flag is for.

Minimum fix before commit: guard on `settings.ENVIRONMENT != "prod"` (or an explicit
`--i-understand-this-is-dev-only` plus a refusal when the environment is production), and update
the D-3 docstring to state the exception.

### 4.2 `backend/docker-compose.yml`: NOT benign

`ABDM_MODE: stub` to `ABDM_MODE: live` at `:36`. This is a checked-in file, so the change makes
`docker compose up` transact against the real ABDM sandbox for everyone, by default, silently. It
also contradicts the README added in the same working tree (see 1.3), and it is the latency source
that makes the OTP replay bug in 3.2 reachable. It is a local-testing convenience committed into
shared configuration. Revert it; the README section that documents how to flip it deliberately is
the correct artifact and should be kept.

### 4.3 `gradle.properties`: NOT benign

`org.gradle.jvmargs` now contains `-Djava.io.tmpdir=/media/sandesh/extra-ssd/tmp
-Dorg.sqlite.tmpdir=/media/sandesh/extra-ssd/tmp`. `gradle.properties` is tracked. That is one
developer's absolute machine-specific path baked into shared build configuration. On CI or any
other machine the directory does not exist, and Room's KSP step extracts the SQLite JDBC native
library into `org.sqlite.tmpdir`. The underlying problem (a full `/tmp`) is real and the fix is
correct; the location is wrong. It belongs in `~/.gradle/gradle.properties` or an untracked
`gradle.properties.local`, not here.

### 4.4 `app/build.gradle.kts`

**`resolveDevHostIp()` (`:27-49`): benign, dev-flavor only, with a caveat.** Only referenced inside
`create("dev")` at `:88`. Staging and prod keep their hardcoded HTTPS URLs (`:110`, `:118`). The
interface filter (`:33-35`) excludes `docker`, `br-`, `virbr`, `tailscale`, `vboxnet`, `zeth`, and
skips loopback, down, and point-to-point interfaces. Reasonable. Caveat: it returns the *first*
matching address, so on a machine with both Ethernet and Wi-Fi up the result is non-deterministic
in interface-enumeration order, and it silently falls back to `127.0.0.1` on any exception, which
produces a dev build that only works over an adb reverse tunnel with no warning. It also only
triggers when `BACKEND_BASE_URL` is absent or literally `auto`, so an explicit setting still wins.
Worth a `logger.lifecycle` line naming the chosen interface and IP at configuration time.

**`unitTests.isReturnDefaultValues = true` (`:173`): NOT benign.** It is project-wide, not
scoped to the new tests. Every `android.*` framework method in every host unit test in this module
now returns `null`/`0`/`false` instead of throwing "Method not mocked". The loud failure that tells
you a unit test has accidentally wandered into the Android framework is now a silent default. It
was added only because `DevDynamicHostInterceptor` and `RealDevServerConfig` call
`android.util.Log`, which is itself a consequence of putting dev code in `src/main` (3.5). Moving
those classes to `src/dev/` and using `java.util.logging.Logger` (as `AssessmentRunner` and
`GeminiBrandLookupSource` already do) removes the need for this setting entirely.

### 4.5 `backend/README.md`

Content is accurate and useful (the `--no-cache` rebuild advice for `abdm-adapter` is a real trap
worth documenting), except that its "sets `ABDM_MODE: stub` by default" statement is false against
the compose file in the same tree. Keep the section; it becomes true again once 4.2 is reverted.

### 4.6 `tools/dev-connect.sh`

Reasonable helper. `set -euo pipefail`, tolerant `|| true` around adb calls, sensible interface
filter mirroring `resolveDevHostIp()`. Two notes: it edits the gitignored `local.properties`
in place with `sed -i` and no backup (`:70`, `:77`); and it writes `KERNEL_BASE_URL`
(`:76-79`), which per `app/build.gradle.kts:84-86` and api-contract.md §5.1 was deleted in Phase 6a
and is no longer read by the app. That line is writing a dead key.

### 4.7 `.idea/deploymentTargetSelector.xml`

IDE deploy-target selection. Noted and dismissed. It should not be in the commit; ideally
`.idea/deploymentTargetSelector.xml` joins `.gitignore`.

---

## Part 5: product-grade judgement and commit recommendation

### 5.1 Against the recurring failure modes

| Failure mode | Verdict |
|---|---|
| **Batch-200-trust** (believing a response instead of the row) | **Absent, and actively inverted.** The Stage-0 gate explicitly discards `drainAll()`'s `Result` and reads the persisted Room column instead. The new tests assert the saved report, not a return value. This is the one thing body (A) gets unambiguously right. |
| **Symptom-not-root-cause** | **Present, in body (A).** The gate fixes the race and cannot fix the terminal-`FAILED` duplicate-ABHA case, because the outbox only collects `PENDING`. AUDIT4's finding is untouched. See 2.4. Also present in body (B): a failing host is diagnosed by probing `/health`, which is not the thing that failed. |
| **Fabricated fallback** | **Absent, and slightly improved.** Stage 0 records an honest `UNAVAILABLE` instead of falling through to the dev `MOCK_FALLBACK`. |
| **Dev code reaching prod** | **Present, in body (B).** Two dev classes in `src/main`, constructed by Hilt in prod builds, not stripped because R8 is off. Functionally inert in prod; structurally against the project's own documented convention. See 3.5. |
| **New: silent audit loss** | **Present, and new.** The Stage-0 early return writes a clinical artifact with no audit entry, on what will be the dominant `UNAVAILABLE` path. See 2.5(a). |
| **New: non-idempotent auto-retry** | **Present, and it is the OTP regression.** See 3.2. |

Overall: body (A) is close to product-grade and has two fixable defects. Body (B) is prototype-grade
dev tooling with a confirmed live-flow regression and a structural containment miss, and it is not
commit-ready as written.

### 5.2 Commit split

The handoff's two-PR suggestion is right in shape and incomplete. Revised into four:

**PR 1, scratchpad and docs, first, no code.** All of `scratchpad/*.md` (60-odd untracked design
memos and audits, this file included) and the two `docs/` files. Commit these first so the audit
trail exists before the code that references it. Mark the two AI-authored `docs/` documents as
unreviewed in the PR body; `product-and-development-report.md` makes regulatory claims and must not
enter the controlled document set on an AI byline alone. Exclude `dashboard.html`.

**PR 2, `fix/sync-before-assess`.** `AssessmentRunner.kt`, `CaseRecordDao.kt`,
`SyncOutboxDrainer.kt`, `OutboxDrainer.kt`, `GetCaseRecordSyncState.kt`, `RepositoryModule.kt`,
`AssessmentRunnerTest.kt`. Ship after the fixes in 5.3. This is the valuable half.

**PR 3, dev tooling, low risk.** `gradle.properties` (relocated out of the tracked file),
`backend/README.md`, `tools/dev-connect.sh`, `app/build.gradle.kts`'s `resolveDevHostIp()` only.

**PR 4, `feat/dynamic-local-connection`, the interceptor layer.** Only after the rework in 5.3.
Do not merge as written.

`backend/docker-compose.yml` and `.idea/` belong in no PR. `seed_accounts.py` is its own decision
(5.3).

### 5.3 KEEP / FIX / THROW

**KEEP as written:**
- `OutboxDrainer.kt`, `GetCaseRecordSyncState.kt`. Clean SAM interfaces, correct layering, good KDoc.
- `CaseRecordDao.getSyncState`. Minimal, correct.
- `SyncOutboxDrainer : OutboxDrainer`. Reuses the existing mutex and drain loop. No new machinery.
- `RepositoryModule` bindings, including the `@Provides` adapter.
- The four new `AssessmentRunnerTest` cases. They assert the right thing.
- `backend/README.md`'s ABDM-mode section.
- `resolveDevHostIp()` and `tools/dev-connect.sh`.
- `DevServerReceiver.kt`'s placement in `src/dev/`. This is the one part of body (B) that respects
  the convention.

**FIX before commit:**
1. **Emit an audit entry on the Stage-0 gate.** `AssessmentRunner.kt:101` returns with a persisted
   clinical artifact and no audit record. Log `KERNEL_ASSESSMENT_FAILED` or
   `KERNEL_RESPONSE_RECEIVED` with the sync state as the reason. Fix the same gap at the
   `resolved == null` return (`:120-123`) in the same pass. **Blocking.**
2. **Exclude non-idempotent requests from failover-retry.** `DevDynamicHostInterceptor.kt:50-74`.
   The narrowest correct fix is to retry only when `request.method == "GET"`. Excluding the ABHA
   path by URL is a weaker fix that leaves `/sync/push` and `/assess` replayable. **Blocking for
   PR 4.**
3. **Revert `ABDM_MODE` to `stub`** in `backend/docker-compose.yml:36`. **Blocking.**
4. **Move `DevDynamicHostInterceptor.kt` and `DevServerConfig.kt` to `app/src/dev/java/`**, matching
   `PiGatewayVitalsSource` and `DevServerReceiver`. This makes the containment structural, removes
   the prod Hilt construction and the stray prod `SharedPreferences` file, and (with
   `java.util.logging`) lets `unitTests.isReturnDefaultValues = true` be removed. It requires a
   `src/main` no-op binding or an `Optional<Interceptor>` for the prod side; that is the right cost.
   **Blocking for PR 4.**
5. **Add `android:permission` (signature-level) to the `DevServerReceiver` declaration**
   (`app/src/dev/AndroidManifest.xml:6-12`) and validate the URL shape in
   `DevServerReceiver.kt:26`. **Blocking for PR 4.**
6. **Guard `--no-must-change-pin` on a non-production environment** and update the D-3 docstring at
   `seed_accounts.py:3-6`. **Blocking.**
7. **Relocate the tmpdir args out of tracked `gradle.properties`** into
   `~/.gradle/gradle.properties`. **Blocking.**
8. Widen `DevServerConfig.tryFailover`'s catch (`:114`) beyond `IOException`, or wrap the
   interceptor's failover so nothing non-`IOException` escapes an OkHttp interceptor.
9. Remove the `172.217.` dead clause at `DevDynamicHostInterceptor.kt:90`; keep the scheme rather
   than copying it at `:41`.
10. Add an instrumentation test for `CaseRecordDao.getSyncState` alongside `SyncStateResetTest.kt`,
    covering present-row, missing-row, and each `SyncState` value.
11. Extend the Stage-0 KDoc (`AssessmentRunner.kt:32-40`) to state that `FAILED` is terminal and
    that the gate is permanent, not transient, for those cases. Fix the comment at `:83-86` which
    claims a guaranteed 404.
12. Remove the stray double blank line at `AssessmentRunner.kt:57-58`; convert the inline
    fully-qualified type names in `NetworkModule.kt:85`, `:117`, `:122`, `:206` to imports, matching
    the rest of that file.
13. Drop the dead `KERNEL_BASE_URL` write at `tools/dev-connect.sh:76-79`.

**THROW AWAY:**
- **`dashboard.html`.** A database dump at the repo root containing worker ids, names, roles,
  facility ids, login times and audit hashes. Delete it or move it outside the repo. Never commit.
- **`.idea/deploymentTargetSelector.xml`'s change**, and ideally the file from tracking.
- **`DevDynamicHostInterceptorTest.externalRequests_arePassedThroughUnchanged`** as currently
  framed. It asserts a Gemini safeguard that the wiring makes unreachable. Either delete it or
  re-point it at a host the ABHA/backend client could actually see, and add the test that matters:
  a `POST` that times out must NOT be replayed.
- **The claim that the Gemini path is safeguarded by this interceptor**, wherever it appears in the
  handoffs. It is not; Gemini uses a separate client.

### 5.4 The one-paragraph verdict

The sync-before-assess fix is good work with two defects: it silently drops the audit record on the
path it makes dominant, and it is a race fix sold as a root-cause fix while the duplicate-ABHA case
it was traced from remains permanently broken. Fix the audit gap, correct the framing, add the DAO
test, and it ships. The dynamic-connection layer is a plausible idea implemented without the
distinctions this codebase needs: it retries non-idempotent clinical requests against a different
host on a timeout, it diagnoses that timeout by probing an endpoint that is not the one that timed
out, it persists a USB-only address as the preferred backend, and it lives in the production source
set with an unprotected exported receiver. It is the confirmed cause of the OTP regression. Rework
it or drop it; do not commit it as written.
