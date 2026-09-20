# S-4: kernel error typing

Tree at a219da9 plus the four pre-existing uncommitted changes, PR-0 through PR-3, the F6C fixes,
S-5 and S-5b. All left exactly as they were. Nothing committed. No credential file opened, no ABDM
gateway contacted, `docs/quality/risk-management-file.md` untouched, nothing under `data/sync`
edited.

Every claim is tagged MEASURED (observed from a run in this session) or INFERRED (read from
source, not executed).

Two notes on the brief before the content:

- It said Sonnet 5 for parts 1 and 2. This ran as Opus 5; a session cannot downgrade its own
  model mid-run. Flagged rather than silently ignored.
- It said "any backend test run uses ABDM_MODE=stub, per the guard S-5b added". **No backend test
  was run in this session.** S-4 is entirely Android-side and touches no Python. The guard is
  present and verified to exist (`backend/core/tests/conftest.py`, the `_stub_abdm_gateway`
  session fixture and `SAMD_ALLOW_LIVE_ABDM_TESTS` opt-in) but was not exercised here.

---

## STEP 1: the vocabulary

Two enums in `app/src/main/java/com/example/samdapp/domain/kernel/KernelFailure.kt`, pure Kotlin,
no `androidx` import, so they are unit testable without a device.

`KernelRetryAdvice` is the three-way split, and it is what the screen branches on:

| Advice | Meaning | Button shown |
|---|---|---|
| `RETRY_NOW` | The request landed and one hop was slow. A second press may work. | Yes |
| `RETRY_WHEN_CONNECTED` | Nothing to press. The queued work runs itself when connectivity or the service returns. | No |
| `NEEDS_ACTION` | A person must fix a record, sign in again, or escalate. Pressing again unchanged fails forever. | No |

Three rather than `AbhaEnrolResult`'s two-way `retryable` boolean, because the kernel path has a
case that flow does not: a failure that resolves itself with no press. Offering "Retry" there
teaches a worker to press a button for something that was going to happen anyway.

`KernelFailure`, ten values, covering the ten classes the diagnosis enumerated in section 2.1:

| Diagnosis row | Value | Advice |
|---|---|---|
| `UnknownHostException`, `ConnectException` | `OFFLINE` | RETRY_WHEN_CONNECTED |
| `SocketTimeoutException` | `TIMEOUT` | RETRY_NOW |
| `SSLException` | `SECURE_CONNECTION_FAILED` | NEEDS_ACTION |
| `HttpException` 401/403 | `NOT_AUTHORIZED` | NEEDS_ACTION |
| `HttpException` 404 `SAMD-ENC-4002` | `CASE_NOT_ON_SERVER` | NEEDS_ACTION |
| `HttpException` 422 `SAMD-KERN-5003`/`5005` | `PAYLOAD_REJECTED` | NEEDS_ACTION |
| `HttpException` 502/503/504 | `KERNEL_UNAVAILABLE` | RETRY_WHEN_CONNECTED |
| `JsonSyntaxException` | `MALFORMED_RESPONSE` | NEEDS_ACTION |
| `IllegalStateException`/`NPE` | `DEVICE_ERROR` | NEEDS_ACTION |
| anything else, or an unrecognised code | `UNKNOWN` | NEEDS_ACTION |

The two the brief named explicitly:

- **`CASE_NOT_ON_SERVER`.** The duplicate-ABHA terminus. The assessment did not fail; the record
  never arrived. Retry fails forever because the fix is on the registration screen.
- **`PAYLOAD_REJECTED`.** Not retryable unchanged, and a real defect signal in both directions.
  `SAMD-KERN-5005` in particular is the H-10 identity guard firing, meaning something that must
  never cross the pseudonymization boundary was about to.

Named after `KernelCallOutcome`/`SlmCallOutcome` per PR-2's discipline, and deliberately not a
reuse of either. Those describe a different hop (backend/core to a model service on the LAN) from
a vantage point that sees both ends; this describes device to backend/core from a phone that sees
one end. Forcing correspondence would produce values no caller can populate: a device cannot tell
`KERNEL_ERROR` from `CIRCUIT_OPEN` unless told, and the backend cannot observe `OFFLINE` at all.
What carries over is the discipline, one written-down vocabulary per hop, not the values. Argued
in the enum's own KDoc so the next reader does not re-litigate it.

**`RETRY_NOW` has exactly one member, and that is not an oversight.** Of the ten, only a socket
timeout makes an immediate second press reasonable. Writing three advice classes and then quietly
assigning most values to the retryable one would have reproduced the defect in nicer clothing.

**One deliberate collapse, stated rather than hidden.** `SAMD-KERN-5001/5002/5004/5006/5007` are
five distinct server conditions and become one `KERNEL_UNAVAILABLE`. The backend already keeps
them apart in `kernel_call_log.outcome`, which is where an operator looks; on the phone they
produce one identical worker action. Collapsing ten classes with four different actions onto one
is the defect. Collapsing five codes with one identical action into one named value is design.
The distinction is kept where it is useful and dropped where it is not.

## STEP 2: parse the problem document

`RetrofitKernelSource` now reads the RFC 9457 body. The block is copied, not reinvented:

```kotlin
val problem = response.errorBody()?.charStream()?.use { reader ->
    runCatching { gson.fromJson(reader, ProblemDetailDto::class.java) }.getOrNull()
}
```

Character for character what `RetrofitAuthService.call` and `RetrofitAbhaSource.call` already do,
so a reader sees one thing three times.

**One change this forced.** `KernelApiService.assess` returned the bare `ApiEnvelopeDto`, so
Retrofit raised an `HttpException` on any non-2xx and there was no `Response` to read an error
body from. It now returns `Response<ApiEnvelopeDto<...>>`, matching the auth and ABHA services,
which have always done so. MEASURED as the actual reason the code was unreadable, not a
refactor for its own sake.

The three cases the other two callers already handle, all handled identically here and all
tested: **an absent body** (`errorBody()` null, or empty) gives `code = null`; **a body that is
not a problem document** (valid JSON, wrong shape, or not JSON at all) gives `code = null` via
the `runCatching`; **an unknown code** is carried through verbatim to the classifier, which falls
back to the HTTP status class before giving up to `UNKNOWN`. None crashes, none is silent.

## STEP 3: classify at the catch site

### Does `InferenceSource` gain values? No. Agreeing with the brief's prior, with one addition.

`InferenceSource` answers "where did this text come from" and is stamped on a clinical record
that syncs to the backend. The failure answers "why is there no text" and is a remedy for the
worker holding the phone. Merging them would make `MOCK_FALLBACK` and a 404 the same kind of
thing, which is the brief's argument and it is right.

The addition, which only became visible while wiring it: `InferenceSource` is in
`KernelReportSyncPayloadDto` and goes to the backend; the failure must not. A 404 meaning "this
patient was never saved" is a fact about this device's outbox, not a clinical fact about the
patient, and pushing it would put a device-local remedy into a clinical record on a server that
already knows the case is missing. Adding values to `InferenceSource` would have forced that,
because the enum is already on the wire. So the two must be separate for a second, structural
reason beyond the semantic one.

`KernelFailure` therefore travels **beside** `InferenceSource`, as a new nullable
`KernelReportOutput.failureCode` / `KernelReportEntity.failureCode`, absent from
`KernelReportSyncPayloadDto`. `data/sync` is untouched: MEASURED, `SyncRecordMappers.kt:172-186`
is an explicit field list, so a new entity column does not reach the wire by default. That is
also exactly what `EvaluateReportEntity.failureCode` already does.

### The failure marker: recommended, and done, because without it the PR has no effect

The brief asked for a recommendation and said to skip it if it grew the PR. **It is not optional,
and that is a finding rather than a judgement call.**

MEASURED: the assessment runs asynchronously (`AssessmentRunner` under the assessment work
queue), and `KernelAssessmentViewModel` builds its display from
`kernelReportRepository.observeForCase(caseRecordId)`, a Flow over the persisted row. Nothing
carries an in-memory return value from the use case to the screen. A classification that is not
persisted reaches no worker at all, so Step 4's copy would have been dead code.

Cost, measured by doing it: one nullable column, a four-line `MIGRATION_19_20`, a database
version bump 19 to 20, a tolerant Room type converter, two mapper lines, and an instrumented
migration test. Comparable to `MIGRATION_18_19`. Small enough.

Two choices inside it worth recording:

- **No backfill.** Every pre-existing UNAVAILABLE row was written by the collapsing catch, so its
  cause was never recorded and cannot be recovered. NULL means "not known", which is true.
  Backfilling a guess would put a fabricated remedy on a real clinical record. The migration test
  asserts the pre-existing row stays NULL.
- **The converter is tolerant on read, unlike the `InferenceSource` one beside it.** A name
  written by a newer build and read back after a downgrade would make `valueOf` throw inside
  Room's cursor mapping and take the assessment screen's whole Flow down. It reads as `UNKNOWN`
  instead, which is what that value is for.

### What is NOT classified, deliberately

- **A reached kernel that answered 200 with an empty differential** gets `failureCode = null`. It
  is a real answer that happens to be empty, not a failure, and attaching a remedy to a screen
  where there is nothing to remedy would be worse than the old copy. This is the only place the
  reach-neutral wording survives, and it is now correct rather than a compromise.
- **A case whose payload could not be built** (`recordUnavailable`) also gets null. It is a real
  class and it is not one of the ten; guessing at it here would have been inventing an eleventh.
  Open item below.
- **`CancellationException`** rethrow at what was `:248-249` is untouched, as instructed. It is
  rethrown in three places now (the use case's two catches and `RetrofitKernelSource`'s), each
  ordered before a broader branch it would otherwise match.

### The broad catch is narrowed, not removed

It still exists, because a device-side bug in result handling is still possible, but every
transport and HTTP outcome now arrives as a `KernelApiResult` and cannot reach it. What remains
in it can only be an app bug, so it classifies as `DEVICE_ERROR` rather than being reported to
the worker as a network problem.

## STEP 4: worker-facing copy

`app/src/main/res/values/strings.xml`, mapped by
`presentation/kernelassessment/KernelFailureCopy.kt`.

| Class | Title | Body |
|---|---|---|
| `OFFLINE` | This phone is not connected | The assessment will run on its own once this phone is back on Wi-Fi or mobile data. You can carry on with the patient. |
| `TIMEOUT` | The server did not answer in time | The connection was made but no answer came back. Try again now. |
| `SECURE_CONNECTION_FAILED` | The connection is not secure | This phone could not make a safe connection to the server. Do not send patient details until this is fixed. Tell your supervisor. |
| `NOT_AUTHORIZED` | Sign in again | Your sign-in has ended. Sign in again, then open this case. |
| `CASE_NOT_ON_SERVER` | This patient is not saved on the server | This patient has not reached the server yet, so this visit could not be checked. Open the patient's record and send it again. If sending keeps failing, check that the ABHA number is not already used by another patient. This will finish on its own once the patient is saved. |
| `PAYLOAD_REJECTED` | The server would not accept this visit | Something in this visit could not be sent. Sending it again unchanged will not work. Write down the patient's name and tell your supervisor today. |
| `KERNEL_UNAVAILABLE` | The assessment service is down | The service that reads this visit is not working right now. It will run on its own when the service is back. You can carry on with the patient. |
| `MALFORMED_RESPONSE` | The server sent something this app cannot read | Trying again will not help. Tell your supervisor. |
| `DEVICE_ERROR` | Something went wrong in this app | This is a problem on this phone, not with the patient's record. Trying again will not help. Tell your supervisor. |
| `UNKNOWN` | This visit could not be checked | Try once more. If it fails again, tell your supervisor. |
| none (reached-but-empty, or stalled) | Assessment unavailable | The AI did not produce a result for this case. No diagnosis was generated. |

Against the rules:

- **Names the record, not the feature.** `CASE_NOT_ON_SERVER` says "This patient is not saved on
  the server", never "Assessment unavailable".
- **Names the action.** "Retry" survives only where pressing can work. `KernelFailureCopy` has no
  retry string of its own for the other classes because **the button is not rendered at all** for
  `RETRY_WHEN_CONNECTED` and `NEEDS_ACTION`. A button that cannot work is worse than no button: it
  teaches a worker that pressing is the remedy when the remedy is elsewhere.
- **No SAMD codes.** None appears in any string.
- **Does not mention the AI when the AI is fine.** The `CASE_NOT_ON_SERVER` copy does not contain
  the word AI, or assessment-as-a-thing-that-failed. This is asserted in a test, on resource
  identity rather than English: a 404 must not select the `kernel_failure_none_*` pair.
- **Says what we know, not what we suspect.** The 404 copy asks the worker to *check* the ABHA
  number if sending keeps failing; it does not assert a duplicate. A 404 proves the case is not on
  the server. It does not prove why. Telling a worker a cause we have not established is how they
  stop believing the screen.

**One thing the brief asked for that this PR does inconsistently with the rest of the app, called
out rather than buried.** MEASURED: before this change `res/values/strings.xml` contained exactly
one string, `app_name`. Every user-facing string in this codebase is an inline constant
(`AbhaEnrolResult.messageForCode`, `UNREACHABLE_OR_BLOCKED_MESSAGE`,
`GenerateKernelReportUseCase.UNAVAILABLE_REASONING_SUMMARY`, every literal in every composable).
The brief said "put the strings in the normal string resources, not inline", so they are in
strings.xml, which makes this the first real use of the resource system in the app. Either the
rest should follow or this should not have. Owner's call; noted in `KernelFailureCopy`'s KDoc too
so it is not lost.

## STEP 5: tests

All unit tests, no device needed except the migration test.

- `domain/kernel/KernelFailureClassificationTest.kt`, 17 tests: one per failure class asserting
  the `KernelFailure` and its `KernelRetryAdvice`; the unknown-code, absent-body and
  non-problem-document cases; and the resource-key assertions. Assertions are on enum values and
  `R.string` ids, never on rendered English, so rewording a sentence does not require editing a
  test that nobody then re-reads.
- Two regression guards, both on observable behaviour and neither on line numbers:
  `the ten failure classes do not collapse onto one outcome` asserts nine sampled inputs classify
  distinctly AND that all three advice classes are in use (a vocabulary of ten names that all say
  "Retry" would pass the first half and still be the bug); `the persisted failure classes do not
  collapse onto one value` runs five causes end to end through the use case and asserts five
  distinct codes on the saved row, which catches the more plausible regression shape of the
  classification being correct but dropped before the database.
- `RetrofitKernelSourceTest.kt`, 9 tests: the three pre-existing empty-differential ones, plus a
  404 problem document surfacing `SAMD-ENC-4002`, an absent body, a non-problem-document JSON
  body, a non-JSON body, an unrecognised code carried through verbatim, and an `IOException`
  becoming `Unreachable`.
- `GenerateKernelReportUseCaseTest.kt`, 14 tests: the pre-existing ones unchanged in intent, plus
  per-class persistence, `DEVICE_ERROR` for a throw out of `assess`, and null `failureCode` on
  both non-failure paths.
- `MigrationTest19To20.kt` (instrumented): the pre-existing row survives with `failureCode` NULL
  and is not backfilled. **NOT RUN. No device or emulator was attached in this session**, so this
  one is written and compiled but unverified.

Results, MEASURED:

```
./gradlew :app:testDevDebugUnitTest      598 tests, 0 failures, 0 errors
./gradlew :app:compileDevDebugKotlin     clean
./gradlew :app:compileStagingDebugKotlin clean
./gradlew :app:compileProdReleaseKotlin  clean
```

No detekt/ktlint/spotless is configured in this project, so there is no lint gate to report.
Room exported `app/schemas/.../20.json` on build.

## What a test caught that reasoning did not

The most valuable section in the previous records, and this time it is mostly the compiler rather
than a test, which is the point.

1. **The sealed return type found four call sites reasoning had not listed.** Changing
   `RemoteKernelSource.assess` to return `KernelApiResult` broke `MockDoctorPrescriptionInboxTest`,
   `KernelReportRepositoryImplTest`, `AssessmentRunnerTest` (three fakes) and `Fakes.kt`, plus
   `MockKernelFallbackSource` in the dev flavor. Each of those is a place that constructs a
   `KernelReportOutput`, and each therefore had to answer "is this a failure?" explicitly. The
   dev mock fallback was the interesting one: it now passes `failureCode = null` with a comment,
   which is the assertion that a mock scenario and a classified failure are not the same kind of
   thing. A non-breaking change, for example an optional field with a default, would have let all
   five sites keep silent and would have been worse.
2. **`SSLException` is an `IOException`.** Written naively, the type check would have put a TLS
   failure in the generic `OFFLINE` branch and told a worker to keep sending patient data over a
   possibly intercepted connection. The ordering is now explicit and there is a test that asserts
   a TLS failure is specifically NOT `OFFLINE`.
3. **`KernelApiService` had to change shape at all.** The diagnosis said "`RetrofitKernelSource`
   is the one caller that does not parse `ProblemDetailDto`", which reads like a missing block.
   It was not reachable without also changing the Retrofit return type, because there was no
   `Response` object to take an `errorBody()` from. Found by writing it, not by reading it.
4. **The failure marker is load-bearing, not optional.** Covered above. Reasoning from the brief
   treated it as a nice-to-have; tracing `AssessmentRunner` to `KernelAssessmentViewModel`'s Flow
   showed the copy reaches nobody without it.

## Recorded, not acted on

**The 404 path writes no backend audit row.** MEASURED by the diagnosis and re-read here:
`_resolve_case_record` raises before `_forward` is entered, and `KERNEL_CALL_FAILED` is written
inside `_fail`, which only `_forward` calls. So a 404 from this path writes no `kernel_call_log`
row and no `KERNEL_CALL_FAILED` audit row. Every other kernel failure has one and this one does
not, which means the failure class this PR just made most visible on the device is the one class
invisible on the server. An S-1 item or a standalone; not touched here because it is backend and
outside S-4's scope.

**A pre-flight failure class is unnamed.** `GenerateKernelReportUseCase.recordUnavailable`, reached
when `AssessmentRunner` cannot resolve a case or build a payload, persists `failureCode = null` and
therefore shows the reach-neutral copy. It is a real and distinct cause ("nothing was sent"), it is
not one of the ten, and inventing an eleventh value inside this PR would have been scope creep.
Worth a decision.

**strings.xml is now used by exactly one feature.** See Step 4. Either propagate or revert.

**The pre-existing log lines in `GenerateKernelReportUseCase` still contain em dashes** (for
example `"Kernel API success — case $caseRecordId"`). Untouched, since they predate this session;
the three log lines this PR added use commas instead.
