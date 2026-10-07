# PR 4b STEP 1 design memo: prove no off-device ASR path remains, then flip `VOICE_FIELD_IMPACT_ENABLED`

Status: DESIGN ONLY. No code written, no file mutated outside this memo. Read-only investigation
of the repo at `master` = `8a22c00` (PR #33 merged).

Model: Opus 5, as required. This memo flips a safety-relevant flag, rewrites an H-15 risk-file
residual, and designs the only evidence that exists for the "no off-device ASR path" claim.

Controlled-document content drafted below (H-15 residual, SOUP validation record, intended-use
amendment) is **PROPOSED, AWAITING OPERATOR SIGN-OFF**. Nothing here is approved by this memo or
by any build step.

`.env`, `local.properties` and any credential file were not read at any point. One investigation
step would have benefited from reading `local.properties` (to confirm which `BACKEND_BASE_URL` a
dev build resolves, for the egress test's expected-quiet baseline): **skipped deliberately**, and
the memo works around it by asserting a zero byte delta rather than reasoning about which host
would have been contacted.

---

## PART 0: repo-versus-brief reconciliation

Everything material in the brief checks out against the tree. Confirmed, with locations:

| Brief claim | Verified |
|---|---|
| `SherpaOnnxTranscriptionService` bound across all flavors | `di/MockBoundaryModule.kt:41`, single `@Binds`, no flavored override |
| `AndroidSpeechRecognizerService` deleted | absent; `git show 786f936` deletes it |
| Weights vendored local-only, gitignored | `app/src/main/assets/asr/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`, 631M on disk, ignored |
| SHA-256 manifest is the source of truth | `docs/sbom/model-soup-2026-09-02-v1.0.json`, mirrored in `SherpaOnnxTranscriptionServiceTest.PINNED_ASSET_SHA256` |
| AAR committed | `app/libs/sherpa-onnx-1.13.7.aar`, tracked |
| Cancellation-aware capture, `ensureActive` between 100 ms chunks | `SherpaOnnxTranscriptionService.readUntilBoundary`, `CHUNK_SAMPLES = 1600` |
| `VOICE_FIELD_IMPACT_ENABLED` still false | `config/FeatureFlags.kt:64` |
| Whole voice gate exists in code, dark | refusal, state model, UI, breadcrumbs all present; both flags false |
| 4a's platform-recognizer grep already clean | re-ran: **0 hits** in `app/src/main` for `createSpeechRecognizer`, `RecognizerIntent`, `SpeechRecognizer`, `isRecognitionAvailable`, `android.speech` |
| No open-source-licences screen anywhere | confirmed: no `AboutScreen`, no `SettingsScreen`, no `NOTICE`, no licence infrastructure. `ProfileScreen.kt` is 82 lines and has three items |
| Unit tests at 299 | `PROGRESS.md`, 4a entry |

**No conflict that invalidates the brief.** Four findings that change the design and were not in
the brief:

1. **One existing test goes red the moment the flag flips.**
   `ConsultationVoiceGateUiTest.theMicButtonAndSuggestionSurfaceAreAbsentWhileTheFlagIsOff`
   (androidTest) asserts `assertDoesNotExist()` for `impact_voice_mic_button` and
   `impact_voice_suggestion_surface`. That assertion is correct today and false after the flip.
   It must be inverted **in the flip commit itself**, not in a follow-up, or the flip commit is
   knowingly red. This is the single hard coupling between the flag and the existing suite.

2. **Layer 1's grep must be scoped to `app/src/main`, not `app/src`.** There is exactly one
   surviving mention of the deleted API in the tree:
   `app/src/test/.../ConsultationViewModelTest.kt:20`, a KDoc line describing "the off-device
   `SpeechRecognizer` exposure". A scan over `app/src` fails today for a comment about a class
   that no longer exists. Scope to `app/src/main` (which is the property that matters: what can
   the shipped app reach) and reword that stale comment in 4b as a separate hygiene commit.

3. **4a's "checked by CI" overclaim fix missed two places.** The fix pass (`c3455ed`) corrected
   five occurrences. `SherpaOnnxTranscriptionServiceTest.kt` still says
   "which fails in CI before any device loads it" (KDoc of the absent-asset test) and "something
   CI re-checks" (KDoc of the hash test). Both are wrong for the same reason the other five were:
   the weights are gitignored, CI has nothing to hash. Fix in 4b's hygiene commit.

4. **The flip makes a runtime-permission dead end user-reachable for the first time.**
   `rememberPermissionAction` (`presentation/common/PermissionAction.kt`) calls `onGranted` only
   on grant and does nothing at all on denial: no error message, no state change. Today that is
   unreachable for the impact field. After the flip, a worker who declines the microphone prompt
   taps the button and observes nothing happen, forever. See Part D.3. This is a finding, not a
   scope expansion; the recommendation there is one line of existing-pattern code, and it is
   flagged for the operator rather than assumed.

---

## PART A: egress proof design

The property to be proven, stated so it can be attacked: **no audio sample and no transcript
produced by the ASR path leaves the device, and no component outside this process participates in
recognition.**

What is *not* available as evidence, stated first so no layer accidentally leans on it: the
`INTERNET` permission stays in the manifest (`AndroidManifest.xml:8`) because the app syncs to its
own backend. Permission removal is not on the table and permission absence cannot be cited.

### A.1 Layer 1: static source scan

**Shape.** One JVM unit test class, `NoPlatformRecognizerSourceScanTest`, in
`app/src/test/java/com/example/samdapp/config/` (it is a build-invariant test, not a transcription
test). Precedent for a test that parses checked-in source rather than importing it already exists
in this repo: `backend/core/tests/test_audit_actions_device.py` resolves a path to
`AuditLogger.kt`, asserts the file exists, and regex-scans it. Same discipline here.

**Scope.** `app/src/main`, all files, recursively. Not `app/src`, for the reason in Part 0 finding
2. Not the AAR, not `libs/`, not generated code.

**Patterns, exact, as literal substrings (case-sensitive):**

```
createSpeechRecognizer
RecognizerIntent
SpeechRecognizer
isRecognitionAvailable
android.speech
```

**Plain grep, not comment-stripped.** Deliberate, and the reason is 4a's, restated: 4a reworded
every KDoc in `FeatureFlags`, `PermissionAction`, `TranscriptionService` and
`ConsultationViewModel` so that none of these symbols appears anywhere in `app/src/main`,
comments included. Verified again for this memo: 0 hits. Comment-stripping would mean carrying a
Kotlin-comment parser (or a regex approximation of one, which is worse) in order to make a check
*weaker* than the one that already passes. If a future change genuinely needs to name the symbol
in prose, the right move is to fail this test and have that conversation, not to pre-weaken it.

**A second scan in the same class, for network clients on the transcription path.** File set:
`data/transcription/`, `domain/transcription/`, and `domain/usecase/TranscribeAudioUseCase.kt`
(which also holds `CaptureAudioAttachmentUseCase`). Patterns: `okhttp3`, `retrofit2`, `java.net.`,
`javax.net.`, `HttpURLConnection`, `java.net.Socket`, `com.example.samdapp.data.remote`.
Verified today: 0 hits.

**Pass looks like:** the test runs, reports the number of files scanned, finds no hit, passes.
**Fail looks like:** an assertion message listing `file:line` for every hit, so the reviewer sees
what came back rather than a bare boolean.

**Non-vacuity, which is the part that makes this a gate rather than decoration.** Three guards,
because a source-scanning test's characteristic failure is passing while scanning nothing:
(a) assert the scan root exists and is a directory, with the resolved absolute path in the message;
(b) assert the scanned `.kt` file count is above a floor (the module currently has well over 200,
so a floor of 150 catches a broken path without becoming a maintenance tripwire);
(c) a positive control: assert the scanner *does* find a sentinel that certainly exists
(`OfflineRecognizer` in `SherpaOnnxTranscriptionService.kt`). If (c) fails, the scanner is not
reading file contents and every negative result above it is meaningless.

**What Layer 1 proves:** the shipped source tree contains no reference to the platform speech
recognition API, and the four files on the transcription path import no HTTP client. It is a
standing regression gate: it fails the build the day someone reintroduces a platform-recognizer
fallback or wires a client into the ASR path.

**What Layer 1 does NOT prove:** nothing about the compiled artifact. Nothing about the
sherpa-onnx AAR or the ONNX Runtime native libraries inside it. Nothing about indirect reach: a
file on the path could call into an app class that itself holds a Retrofit service, and this layer
would not see it. That is exactly Layer 2's job. It also proves nothing about runtime behaviour.

### A.2 Layer 2: construction, the dependency graph assertion

The claim: **nothing that participates in recognition is constructed with, or can reach, a network
client, and the binding cannot be swapped for one in any flavor.** Three assertions, all
falsifiable, in one JVM unit test class `TranscriptionPathHasNoNetworkDependencyTest`, plus one
reflection assertion that has to live on-device.

**L2.1 Transitive source reachability over the app's own package.** Entry set:
`SherpaOnnxTranscriptionService.kt`, `TranscriptionService.kt` (the seam),
`TranscribeAudioUseCase.kt` (holds both use cases), and `ConsultationViewModel.kt`. Walk: for each
file, read its `import com.example.samdapp.` lines, resolve each to a file under `app/src/main`,
recurse, with a visited set. Then assert: no file in the transitive closure imports `okhttp3`,
`retrofit2`, `java.net.`, `javax.net.`, `android.net.` or `com.example.samdapp.data.remote`.

Known ceiling of the walk itself, named rather than hidden: Kotlin same-package references need no
import, so a same-package type is invisible to an import walk. Mitigated by the fact that the
transcription packages contain only the files listed above (verified), and by L2.2 below, which
does not depend on imports at all. A full type-resolution walk would mean embedding a Kotlin
compiler plugin or adding Konsist as a dependency; neither is justified for a four-file path.
`ponytail`: import walk plus a reflection check, upgrade to Konsist only if the path grows past a
handful of files.

**L2.2 Single-binding assertion.** Scan every `app/src/*/java/**/di/*.kt` (all flavors, all source
sets) for declarations that bind or provide `TranscriptionService`. Assert exactly one exists and
that it names `SherpaOnnxTranscriptionService`. This is the assertion that a flavored fallback
which can transmit cannot be reintroduced without turning this test red. It is the codified form
of the comment already sitting at `MockBoundaryModule.kt:38`, which today is prose.

**L2.3 Compiled-artifact reflection, androidTest, in `AsrEgressTest`.** Reflect over
`SherpaOnnxTranscriptionService::class.java.declaredFields` and its constructor parameter types.
Assert every type is in the allowed set: `android.content.Context`, `java.lang.String`,
`java.util.concurrent.ConcurrentHashMap`, `kotlinx.coroutines.sync.Mutex`, and the sherpa-onnx
`OfflineRecognizer`. Assert no field type's package name starts with `okhttp3`, `retrofit2`,
`java.net`, `javax.net` or `com.example.samdapp.data.remote`. This asserts against the class that
actually ships, not against text.

**Honest ceiling of L2.3, which must be stated in the test's own KDoc:** `android.content.Context`
is in the allowed set and a `Context` can reach the network. Depth-1 reflection therefore proves
"no network client and no app-level remote dependency is *injected* into this service", which is
not the same as "this service cannot open a socket". Recursing further is not a fix: the walk
would terminate in the Android framework, where everything reaches everything. The remaining
distance is Layer 3's, and Layer 3 closes it by observation rather than by construction.

**Falsifiability, and it must actually be exercised before merge.** Same non-vacuity discipline
`ConsultationVoiceUnconfirmedRefusalTest` already established in 3a: temporarily add an unused
`private val api: KernelApiService` field to the service and an `import okhttp3.OkHttpClient` to
`TranscribeAudioUseCase.kt`, confirm L2.1, L2.2 and L2.3 each go red, revert. Record the mutation
result in the PR body. A test of this shape that has never been seen to fail is a comment.

**What Layer 2 proves:** by construction, at both source and compiled-class level, the recognition
path is built from a `Context`, a string, a map, a mutex and an ONNX recognizer, and the
`TranscriptionService` seam has exactly one implementation across every flavor.

**What Layer 2 does NOT prove:** anything about the native library. sherpa-onnx and ONNX Runtime
are `.so` files inside a vendored AAR; no reflection or import walk can see inside them. It also
does not prove that the audio never reaches disk (a separate property, asserted in Layer 3).

### A.3 Layer 3: runtime, instrumented, on emulator-5554

One androidTest class, `AsrEgressTest`, device-local only (the weights are gitignored, so CI
cannot run it). It carries L2.3 plus three runtime assertions. The headline assertion is
**positive**: a real transcription completes AND no bytes moved.

**L3.1 The positive assertion.** Decode `asr-test/known-clip.wav` through the real service and the
real weights, assert the transcript is non-blank and contains the known content words (same
content-word discipline as `SherpaOnnxTranscriptionServiceTest`, not exact-string equality). If
this half is skipped the whole test degenerates into "nothing happened, therefore nothing leaked",
which is the classic vacuous egress test.

**L3.2 Egress observation A: per-UID byte accounting.** Sample
`TrafficStats.getUidTxBytes(Process.myUid())` and `getUidRxBytes` immediately before and
immediately after the decode in L3.1, on the same thread. Assert both deltas are exactly `0`.

This is the assertion that reaches the native code. `TrafficStats` per-UID counters are maintained
by the kernel, so a socket opened directly from JNI or from ONNX Runtime through libc is counted,
where a Java-level instrumentation hook would miss it entirely.

Its ceilings, all three of which must be in the KDoc: the counters are per-UID, so any *other*
component of the app process that transmits during the window pollutes the measurement (an
instrumented test's target context does not start the sync worker, and the test must not touch the
licences screen's link intent, but this is a property of test hygiene, not of the assertion);
the counters are totals, so a delta of 0 is a strong signal while a small non-zero delta is
ambiguous and must be investigated rather than tolerated (do not weaken this to a threshold: "a
few hundred bytes" is precisely the shape an exfiltrated transcript would have); and the counters
do not attribute traffic to a call stack, so a failure says "something in this UID transmitted",
not "the ASR path transmitted".

**L3.3 Egress observation B: StrictMode.** Install a `StrictMode.ThreadPolicy` with
`detectNetwork()` and `penaltyDeath()` on the thread that performs the decode, for the duration of
the decode, restoring the prior policy in a `finally`. A Java-level socket operation on that thread
kills the process and the run fails.

Its ceiling, stated plainly and it is a significant one: StrictMode's network detection is
implemented through `BlockGuard`, which instruments the Java/libcore socket layer. **Native code
calling `socket()` directly does not trip it.** L3.3 is therefore strictly weaker than L3.2 for
the case we most care about (the vendored native library) and stronger for the case we care about
least (our own Kotlin, which Layers 1 and 2 already cover). It is included anyway because it is
five lines, it fails loudly rather than by assertion, and the two observations fail in different
ways, which is the point of having two.

**L3.4 The audio-never-hits-disk assertion.** `SherpaOnnxTranscriptionService`'s KDoc claims it
"writes no audio file anywhere". Assert it: snapshot the recursive file listing of
`context.filesDir` and `context.cacheDir` before and after a capture-and-decode, assert the sets
are equal. Cheap, and it converts an existing prose claim into a check. Egress to disk is not
egress off device, but an audio file that exists is an audio file the sync outbox or a backup
agent could later move, and this is the only place that property is ever asserted.

**L3.5 Operator confirmatory run, not automated.** Instrumentation cannot toggle airplane mode
without system permissions. On the physical Pixel: airplane mode on, one real capture through the
UI on the impact field with the flag on, transcript appears, recorded as the SOUP validation
record (Part C.4). Note the change in this step's character since the platform recognizer was
deleted: it used to be an *exploratory* check where the burden was on finding evidence of no
transmission; with Layers 1 to 3 in place it is *confirmatory*, and it witnesses a property the
other layers establish.

**What Layer 3 proves:** on this device, this build, this run, a complete transcription happened,
the app's UID moved zero bytes across it, no Java-level socket was opened on the decoding thread,
and no file appeared in the app's storage.

**What Layer 3 does NOT prove:** it is an observation over one execution with one input, not a
proof over all executions. It does not prove the absence of a dormant network path in the native
library that different input, a different model or a different build could activate. And it runs
on `x86_64` (emulator-5554), while the shipped ABI is `arm64-v8a`: **the binary the egress test
exercised is not the binary that ships.** That gap is real and it is why L3.5 belongs on the
physical Pixel, not on the emulator.

### A.4 Do the three layers add up to "no off-device ASR path remains"?

Close, and the residue is nameable rather than hand-waved:

- Our own code: **covered.** Layer 1 for the deleted API and HTTP-client imports, Layer 2 for
  construction and the single binding, both standing regression gates that fail on reintroduction.
- The process boundary: **covered by construction.** Recognition is in-process JNI. No
  `RecognitionService` binding, no `Intent`, no second package, so there is no vendor policy to
  trust because there is no vendor in the path. This one is architecture, not a test.
- The vendored native code: **witnessed, not proven.** L3.2's zero-byte delta is the strongest
  statement available from inside the process, and it is an observation. **The gap:** nothing in
  4b proves sherpa-onnx 1.13.7 and ONNX Runtime 1.27.1 contain no network code at all. That is a
  SOUP question, and its controls are the ones already in place: exact version pins, per-file
  SHA-256 in the companion, no download-on-first-use, no model CDN, no remote config selecting a
  model. Version pinning plus hash pinning is what makes "the bytes we tested are the bytes that
  ship" true; it is not a proof of what those bytes do.
- The ABI gap: **open until the operator's arm64 run.** Named in L3.5.

So: the memo does not claim the three layers close the property absolutely. They close it for
everything this project authors, and they witness it for the part it vendors. State it that way in
the PR body and in the H-15 residual, and do not let any of the three layers be described as
proving more than the list above.

---

## PART B: flag-flip ordering, as a hard gate

### B.1 The gate

`VOICE_FIELD_IMPACT_ENABLED: false -> true` is the **last** functional commit in 4b, and it lands
only when all three layers are green **on-device**, with the run output pasted into the PR body.
Not "the tests are written". Not "the tests pass locally in principle". Green, on emulator-5554,
in a run whose output the reviewer can read.

The flag does not flip on a claim. Its own KDoc says so today
(`FeatureFlags.kt:56-59`: "'no off-device ASR path remains' is at this point a claim about the
code, and the flag does not flip on a claim"), and 4b is the change that either produces the
evidence or leaves the flag alone.

### B.2 `VOICE_INPUT_ENABLED`: 4b does not touch it

It stays `false`. The reasoning is already in the tree and survives the egress proof intact:

- It gates `chiefComplaint` voice and the audio attachment. `chiefComplaint` **reaches the
  `/api/v1/evaluate` wire**, which is the **High** severity half of H-15, versus **Medium** for the
  human-read narrative fields.
- Those two paths are governed by **no confirmation gate**. The whole PR 3 gate (refusal, state
  model, three-button surface, breadcrumbs) exists for `impactOnDailyActivities` only.
- The egress proof is orthogonal to both facts. It closes the transmission argument; it says
  nothing about an ungated High-severity field reaching a model.

The one thing 4b should do to that flag is documentary: its KDoc currently says the original
reason for the flag "is gone" and that it stays off for the ungated-High-severity reason. After
4b, add one sentence recording that the egress evidence now exists and still does not change this
flag's state. One line, in the flip commit, so the two flags cannot be conflated by a later reader.

### B.3 Exact commit ordering within 4b

Assuming the split in Part F (two PRs), this is the ordering inside the second one:

1. **`test(asr): source-level absence scan for the platform recognizer and network clients`**
   Layer 1. Flag untouched.
2. **`test(asr): assert no network dependency reaches the transcription path`**
   Layer 2 (L2.1, L2.2). Flag untouched. PR body records the mutation check.
3. **`test(asr): on-device egress assertion for a full transcription`**
   Layer 3 (L2.3, L3.1 to L3.4). Flag untouched. Device-local, output pasted into the PR.
4. **`docs(asr): correct two residual CI overclaims and one stale recognizer comment`**
   Part 0 findings 2 and 3. No behaviour. Kept separate so the flip diff stays about the flip.
5. **`feat(asr): flip VOICE_FIELD_IMPACT_ENABLED`**
   `FeatureFlags.kt` (one const, plus the `VOICE_INPUT_ENABLED` KDoc sentence from B.2), the
   inverted `ConsultationVoiceGateUiTest` assertion (Part 0 finding 1), the new flag-on UI test,
   and the `PROGRESS.md` entry. **Nothing else.** A reviewer must be able to read this commit in
   one screen with commits 1 to 3 sitting directly beneath it.
6. **`docs(quality): re-evaluate the H-15 residual at the flag flip (PROPOSED)`**
   The Part C rewrite, plus the intended-use amendment and the SOUP validation record. PROPOSED,
   never approved.

Commit 5 cannot precede 1 to 3 in the history. That is the gate, and it is checkable by reading
`git log` rather than by trusting anybody's account of the order things were done in.

### B.4 Two things the flip makes reachable for the first time, which the PR must state

- **The 622 MiB model load.** Lazy on first use by design, so before the flip it never happens in
  a shipped build. After the flip, the first mic tap in a process pays the whole load. It is off
  the main thread and `Mutex`-guarded, and the UI shows "Listening…" via `isCapturingImpactVoice`,
  but the *measured* first-capture latency on the target device is not recorded anywhere. Measure
  it during the 4b device run and record the number in `PROGRESS.md`. Reported, not asserted:
  there is no agreed threshold to assert against.
- **The `RECORD_AUDIO` runtime prompt on this path.** See Part D.3.

---

## PART C: H-15 residual re-evaluation

**Controlled document:** `docs/quality/risk-management-file.md`, row H-15.
**Status: PROPOSED, AWAITING OPERATOR SIGN-OFF.** Not approved. The build does not mark it
approved, and the operator's signature is the only thing that changes that.

### C.1 Why this is a rewrite and not an append

Every prior update to this row (PR 2, 3a, 3d, and 4a's deliberate non-update) shares one load
bearing clause: *the residual risk to a user is unchanged, because the flag is off and the control
is not user-reachable*. That clause is what let each update describe a control as "implemented in
code" without having to argue what the control actually achieves for a person.

At the flip, that clause becomes false. The gate is reachable. The residual has to be argued from
what the controls do when a real worker uses them, which is a different argument from the one the
row currently makes, so appending to a chain of "unchanged" updates would misrepresent the change.
Rewrite the residual section of the row. Keep the hazard, cause and severity columns; keep the
historical PR 2/3a/3d/4a sentences as a compressed provenance trail (they are the audit history of
how the control was built) but subordinate them to the new residual argument rather than letting
the newest text be a fifth "unchanged" paragraph.

### C.2 The residual, argued from scratch

**Hazard, restated:** ASR mis-transcription silently changes a clinical field value.

**What the flip changes:** voice input becomes reachable on exactly one field,
`impactOnDailyActivities`, a narrative field read by a human. It does **not** reach
`/api/v1/evaluate`; `chiefComplaint`, which does, stays behind `VOICE_INPUT_ENABLED = false`. The
severity applicable to the newly-reachable path is therefore the row's own **Medium**, not its
**High**. Nothing at the flip raises the High half.

**The four controls, and what each actually removes:**

- **(i) Confirm-after-endpoint gate.** A transcript is never written to the field. It lands in
  `impactVoiceSuggestion`, a separate piece of state from the committed value, rendered in a
  separate `Card` adjacent to the text field, so the surface cannot mutate the field even by
  construction. A blank transcript on a successful recognition is routed to the honest-failure
  edge rather than shown as an empty suggestion. **Removes:** the silent-write failure entirely.
  A voice-derived value cannot reach the field without a human tapping one of three buttons.
  **Does not remove:** anything about whether that tap was informed.
- **(ii) Three equal-weight buttons.** "Use it", "Edit" and "Discard" are identical
  `OutlinedButton`s, same weight, same row (`ConsultationScreen.kt:318-333`). No filled primary,
  no de-emphasised discard. **Removes:** the visual-hierarchy nudge toward accepting without
  reading. **Does not remove:** automation bias itself. Equal weight makes accepting no easier
  than rejecting; it does not make reading happen.
- **(iii) `FieldProvenance` plus the four `VOICE_FIELD_*` breadcrumbs.** Every committed value
  carries `VOICE_CONFIRMED`, `VOICE_EDITED` or `TYPED`, and every gate transition emits an audit
  entry carrying `slot`, `provenance`, `asrModelId`, `asrModelVersion`, `charCount`,
  `editDistance` and `dwellMs`, never the transcript itself. **Removes:** the ability for a
  voice-derived value to be indistinguishable from a typed one after the fact. `dwellMs` in
  particular makes rubber-stamping *detectable*. **Does not remove:** anything in real time.
  This is a post-hoc control, and it is worth naming what it currently lacks: **nobody reviews
  these breadcrumbs.** There is no dashboard, no threshold, no operational procedure that reads
  `dwellMs`. The detection capability exists; the detection does not. That is an operational gap,
  not a code gap, and it should be recorded as such rather than counted as mitigation.
- **(iv) `VOICE_UNCONFIRMED` structurally unpersistable.** `ConsultationRepositoryImpl.saveConsultation`
  returns `DataError.Refused` and performs **no DAO write** for a consultation carrying
  `impactOnDailyActivitiesProvenance = VOICE_UNCONFIRMED`, proven against a real Room database by
  `ConsultationVoiceUnconfirmedRefusalTest`, which asserts the absence of the persisted row rather
  than the returned value, and was verified non-vacuous by mutation. **Removes:** the entire class
  of failures where a code change downstream of the gate accidentally commits an unconfirmed
  value. It is a backstop that now, for the first time, sits behind a live path rather than behind
  nothing.

**The residual that survives all four.** A fluent, plausible mis-transcription that a worker reads
and accepts because it reads correctly. Two concrete shapes:

1. **Semantic substitution.** The recogniser returns grammatical English that differs in clinical
   meaning from what was said ("cannot walk" for "can walk", a negation dropped, a duration or a
   body site swapped). None of the four controls detect this, because all four operate on
   provenance and workflow, not on meaning. The worker is the only detector, and the worker is
   the person whose attention control (ii) can only refrain from misdirecting.
2. **Silent truncation.** The capture boundary is this codebase's, not the engine's: Parakeet is
   an offline recogniser with no endpointer, so `TRAILING_SILENCE_MS = 1500`,
   `LEAD_IN_TIMEOUT_MS = 6000` and `MAX_CAPTURE_MS = 30000` plus an amplitude gate decide when a
   speaker has finished. A hesitant or elderly speaker pausing longer than 1.5 s is cut off
   mid-narrative, and the result is a **shorter but still grammatical** suggestion. The gate shows
   it honestly; it does not know something is missing. These constants are tuned against a
   developer speaking fluently, not against the intended speakers, and that tuning has not
   happened.

**Proposed residual rating.** Severity **Medium** for the newly-reachable path (narrative field,
human-read, no evaluate-wire path). Probability: **not established for the deployment
population**, and the memo declines to assert one. The four controls remove the silent-write class
of failure entirely and leave the informed-acceptance class untouched. Whether that residual is
acceptable is an operator judgement, and the evidence available for making it is in C.3, gaps
included. This memo does not mark the risk accepted.

### C.3 Known limitation, recorded with its evidence and both gaps

Record as a **KNOWN LIMITATION** on the H-15 row, not as a closed item and not silently omitted:

> **Accuracy of the shipped ASR artifact for the deployment population is not established.**
>
> **Evidence available.** The Open ASR Leaderboard places `nvidia/parakeet-tdt-0.6b-v2` at
> **6.42 average WER, 10th** across its benchmark suite. This is real evidence and it is why this
> model family was selected, but it is **family and architecture evidence**, not evidence about
> the artifact this device runs.
>
> **Gap 1, the artifact.** The leaderboard figure is the **full-precision model evaluated on
> A100-class hardware**. What ships here is an **int8 quantised ONNX export** running under ONNX
> Runtime on an ARM mobile SoC. Quantisation error and a different runtime are not accounted for
> by that number, in either direction. No measurement of the shipped int8 artifact exists.
>
> **Gap 2, the population.** The benchmark suite is not the deployment population. Published
> district-level ASR error rates for Indian English span roughly **4 percent to 44 percent**, a
> tenfold spread. The deployment is a rural PHC in MP. There is no basis for assuming this
> district sits near the favourable end, and a spread that wide means the average is not a useful
> predictor for any particular district: the relevant question is where in the tail this one sits,
> and that is unmeasured.
>
> **The honest, deferred item.** An accented-speech evaluation of the **shipped int8 artifact**,
> on recordings representative of the intended speakers, has **not been run**. It is recorded here
> as an open action rather than omitted. Note what it is a precondition for: it is a precondition
> of **clinical deployment**, not of the dev-only flag flip this row is being updated for, and the
> row should say which of the two it is blocking so the distinction does not blur later.

### C.4 The other two controlled-document changes due at the flip

Both **PROPOSED, AWAITING OPERATOR SIGN-OFF**:

- **`docs/requirements/intended-use-statement.md` §i.** It currently states that the voice
  affordance is disabled (`VOICE_INPUT_ENABLED = false`). After the flip that sentence is
  incomplete: the device gains a user-reachable voice input path on one narrative field, behind a
  confirmation gate, with `VOICE_INPUT_ENABLED` still false. What the device does has changed,
  and an intended-use statement exists to state what the device does.
- **SOUP validation record in the DHF**, covering all five items of the model companion, with
  L3.5's airplane-mode witness as the runtime validation evidence for the sherpa-onnx runtime and
  the model.

---

## PART D: cancellation and mid-dictation UX with the flag on

### D.1 Mic release on discard and navigate-away: already covered by 4a, verify and move on

Traced end to end:

- **Navigate away mid-capture.** `AppNavHost` pops via `backStack.removeLastOrNull()`, the Nav3
  entry is disposed, the `ConsultationViewModel` is cleared, `viewModelScope` is cancelled. The
  capture coroutine's next `coroutineContext.ensureActive()` (at the top of every 100 ms chunk
  iteration in `readUntilBoundary`) throws `CancellationException`, which unwinds through
  `record()`'s `finally`, which calls `recorder.stop()` and `recorder.release()` exactly once on
  every exit path. Cancellation latency is bounded at about one chunk. Asserted by
  `cancelling_mid_capture_stops_the_microphone_read_promptly` (cancel at 250 ms, assert completion
  under 2 s).
- **Discard.** `onDiscardImpactSuggestion` acts on a suggestion that already exists, which means
  the capture has already completed and the recorder is already released. Discard is not a
  cancellation path at all. It clears `impactVoiceSuggestion` and the dwell timestamp and emits
  `VOICE_FIELD_REJECTED`. Nothing to add.

**No additional cancellation handling is needed in 4b.** 4a's chunk-level `ensureActive` covers
the mic-release property, and the existing instrumented test covers it as a regression. Do not
build a cancellation mechanism that already exists.

**What 4b must verify (device-local, one run, not new code):** with the flag on, start a capture
from the real UI, navigate back mid-capture, and confirm from `adb shell dumpsys media.audio_flinger`
(or simply that a second capture starts cleanly afterward) that the recorder was released. This is
a confirmation of an already-tested property through the newly-reachable UI path, worth one manual
run because the flip is the first time the UI is the caller.

### D.2 Backgrounding mid-capture: verify, expect it to be fine

`onStop` does not cancel the capture; the ViewModel outlives it. From Android 11 the platform mutes
the microphone for a backgrounded app, so the capture would see silence and terminate at
`TRAILING_SILENCE_MS` (if speech had been heard) or `LEAD_IN_TIMEOUT_MS`, and `MAX_CAPTURE_MS`
bounds it at 30 s regardless. Expected behaviour is therefore "capture ends by itself within
seconds, recorder released in `finally`". **Verify once on-device** rather than assume; if it does
hold, no code changes, and record that it was checked.

### D.3 Two real UX gaps the flip exposes, flagged for the operator, not built on spec

- **Permission denial is a silent dead end.** `rememberPermissionAction` calls `onGranted` only on
  grant and does nothing on denial. After the flip, a worker who declines the microphone prompt
  taps "Record impact on daily activities" and sees nothing happen, with no explanation, forever.
  This is a shared helper used by three call sites (chief-complaint voice, audio attachment,
  camera), so the fix belongs in the helper, not at the call site: one optional `onDenied`
  callback defaulting to no-op, with the consultation screen passing the existing
  `errorMessage` path. Small, root-cause-shaped, and it is the operator's call whether it rides in
  4b or is deferred. Recommendation: include it in 4b, because the flip is what makes it
  reachable, and shipping a reachable dead end is worse than the twelve lines it costs.
- **No user-facing way to stop a capture in progress.** The mic button re-entrancy guard means a
  second tap during "Listening…" does nothing. In normal use trailing silence ends the capture in
  1.5 s, so this matters only for a mis-tap in a noisy room, where the amplitude gate may keep the
  capture open toward `MAX_CAPTURE_MS`. **Not recommended for 4b.** It is a new UI state and a new
  action on a surface whose three-button symmetry is itself a safety property; adding a fourth
  control belongs in a UX change reviewed as one, not smuggled into a flag flip.

---

## PART E: attribution / open-source licences screen

### E.1 Why it rides inside 4b, and when the obligation actually bites

The Parakeet weights are **CC BY 4.0**. They ship inside the APK **regardless of the flag**, since
`VOICE_FIELD_IMPACT_ENABLED = false` hides the feature but does not remove 631 MB of assets. The
attribution obligation therefore attaches at **APK distribution**, not at the flag flip, and it
has been open since 4a merged. It is a blocker on any build that leaves this machine, which is
also why it is not a 4b-only concern: it was already due.

Because the project is dev-only right now, discharging it inside 4b is acceptable timing. It must
not slip past 4b: the next thing that happens after a flag flip is somebody building an APK to
show it working.

Alternative discharge, for completeness: a `NOTICE` asset bundled in the APK would satisfy the
licence text with less UI. The SOUP companion's own PROPOSED placement names the licences screen,
and a screen is discoverable in a way a bundled text file is not, so the screen is the
recommendation. If the operator prefers the cheaper path, say so and 4b ships the NOTICE instead.

### E.2 Scope, at build-in-STEP-2 resolution, no code

**New files: one screen, one route entry, one Profile row.** Nothing else.

- **Route.** `data object OpenSourceLicensesRoute` in `presentation/navigation/Routes.kt`, next to
  `Profile`. Explicitly **not** added to the screen-security list in `Routes.kt` (no patient data
  on it), and the decision recorded in a comment there, since that list is deliberate.
- **Entry point.** One `OutlinedButton` on `ProfileScreen`, labelled "Open-source licences",
  placed above "Sign out". `ProfileScreen` is 82 lines with three items; this is the natural home,
  and it is the screen a user goes to for "about this app" information. Requires a new
  `onOpenLicences: () -> Unit` parameter, wired in `AppNavHost` at the existing `entry<Profile>`
  block with `backStack.add(OpenSourceLicencesRoute)`, matching the pattern already used for
  `AbhaProfileRoute`.
- **Screen.** `presentation/licenses/OpenSourceLicensesScreen.kt`: `Scaffold` with a `TopAppBar`
  titled "Open-source licences", a `LazyColumn` of `Card`s, one per component. Each card: name,
  version, licence identifier, one-line role, and an optional link. A hardcoded `private val`
  list of a small data class in the same file. **`ponytail`: no Gradle licence-report plugin, no
  generated JSON, no WebView, no strings.xml.** The app hardcodes user-facing strings everywhere
  else; a licence-report plugin would add a build dependency and a generated artifact to render a
  list that changes when someone adds a dependency, which is a code review, not a build problem.
  Upgrade path if the dependency list grows unmanageable: generate the list from the CycloneDX
  SBOM that already exists.
- **Links.** `Intent(ACTION_VIEW, uri)` via `LocalContext`, wrapped in `runCatching` because a
  device with no browser throws `ActivityNotFoundException`. This is the only outbound action
  anywhere on the screen, it is user-initiated, and it has nothing to do with the ASR path; note
  it in the egress test's KDoc so nobody later reads the licences screen as a contradiction of
  Layer 3.

**Required content, in this order:**

1. **NVIDIA Parakeet TDT 0.6B v2 (int8)**, licence **CC BY 4.0**. The obligation this screen
   exists for. Text as the SOUP record specifies: `NVIDIA Parakeet TDT 0.6B v2 (CC BY 4.0)`, with
   the model link `https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2`. Role line: "On-device
   speech recognition model."
2. **sherpa-onnx 1.13.7**, Apache-2.0, "On-device speech recognition runtime."
3. **ONNX Runtime 1.27.1**, MIT, "Inference engine, bundled inside sherpa-onnx." Version read
   off the consumed `.so`, matching the SOUP companion, not the sherpa-onnx documentation's
   1.17.1.
4. The app's main third-party dependencies from the generated SBOM (Compose, Room, Hilt, SQLCipher,
   Retrofit, OkHttp, Coroutines, WorkManager). These carry no CC-BY-style attribution obligation,
   but a licences screen listing only the one component that forced it into existence reads as an
   afterthought and will be wrong the first time someone audits it.

**Test:** one Compose test, `OpenSourceLicensesScreenTest`, asserting the Parakeet entry renders
with its licence identifier. That is the assertion with a legal consequence; the rest of the list
is data. Instrumented (Compose), device-local like the rest.

### E.3 What this screen is not

Not a settings screen, not an about screen with version/build info, not a link to a hosted licence
page, not a per-dependency full licence text dump. Attribution is the obligation; a reader who
wants the full CC BY 4.0 text follows the link.

---

## PART F: split proposal

**Recommendation: two PRs.**

**PR 4b-1, `feat(about): open-source licences screen`.** Part E, alone. No dependency on any
egress proof, no flag change, no risk-file change. Reviewable as what it is: a small piece of new
UI with a legal obligation behind it.

**PR 4b-2, `feat(asr): prove no off-device path, then flip VOICE_FIELD_IMPACT_ENABLED`.** Parts A,
B, C and D.3, in the commit order of B.3.

**Why this split and not others:**

- **The licences screen must not be held hostage to the egress proofs.** It discharges an
  obligation that has been open since 4a merged and that attaches to every APK regardless of the
  flag. Bundling it behind three test layers and a device run keeps a distribution blocker open
  for no engineering reason.
- **Conversely, the flip must not be reviewed underneath 300 lines of new UI.** The flip is a risk
  decision. Its diff should be one const, one inverted assertion, one new UI test and a
  `PROGRESS.md` entry, sitting directly on top of the three commits that produced its evidence.
- **Do not split the egress proof from the flip.** Tempting, and wrong. The entire value of the
  ordering is that a reviewer sees the green evidence and the one-line flip in the same diff. Land
  them separately and the flip PR reviews as "trust me, the proofs merged last week", which is
  precisely the "the flag does not flip on a claim" failure the flag's own KDoc was written to
  prevent. They are one reviewable unit; the ordering *within* it is what B.3 specifies.

**Recommended sequence for the operator:**

1. Sign off this memo, with decisions on: the permission-denial fix in scope or out (D.3);
   licences screen versus NOTICE file (E.1); and whether the arm64 physical-device egress run
   (L3.5) blocks the flip or is recorded as an open action.
2. Build and merge **4b-1** (licences screen). Small, independent, closes a standing blocker.
3. Build **4b-2** commits 1 to 4. Run the unit layers in CI, the device layer on emulator-5554.
4. **Gate.** All three layers green, output in the PR body, mutation check recorded.
5. Commit 5 (the flip) and commit 6 (the PROPOSED controlled docs). Merge.
6. Separately, and not blocking either PR: the accented-speech evaluation of the shipped int8
   artifact (C.3). It blocks clinical deployment, not the dev-only flip.

---

## PART G: test plan

### G.1 Unit, `testDevDebugUnitTest` (currently 299, host JVM, runs in CI)

| Target | New/changed | Tests | Gate |
|---|---|---|---|
| `NoPlatformRecognizerSourceScanTest` | new | ~4 (recognizer symbols, network imports on the path, scan-root/file-count guard, positive-control sentinel) | **Must run before merge** |
| `TranscriptionPathHasNoNetworkDependencyTest` | new | ~3 (L2.1 transitive import walk, L2.2 single-binding, plus a self-check that the walk reached more than the entry files) | **Must run before merge** |
| `ConsultationViewModelTest` | changed | 0 (KDoc reword only, Part 0 finding 2) | Must stay green |
| `ConsultationVoiceGateTest`, `ConsultationVoiceGateBreadcrumbsTest`, `LevenshteinTest` | unchanged | regression | Must stay green |

Expected total after 4b-2: about **306**. If the count moves by more than the new tests, something
else changed and the PR body must say what.

### G.2 Instrumented, `connectedDevDebugAndroidTest` (device-local only)

**None of these can run in CI.** The weights are gitignored, so the CI checkout cannot build an
APK containing the model, let alone hash or decode with it. This is a known consequence of the
storage decision recorded in 4a, not a new gap.

| Target | New/changed | Asserts | Gate |
|---|---|---|---|
| `AsrEgressTest` | new | L2.3 reflection over the shipped class; L3.1 real decode is non-blank; L3.2 UID tx/rx delta == 0; L3.3 StrictMode `detectNetwork` + `penaltyDeath` on the decode thread; L3.4 no file appears in `filesDir`/`cacheDir` | **Must be green before commit 5.** Device-local |
| `ConsultationVoiceGateUiTest` | **changed, and it is the one the flip breaks** | `theMicButtonAndSuggestionSurfaceAreAbsentWhileTheFlagIsOff` inverts to `...RenderWhileTheFlagIsOn` with `assertExists()`. The three direct `ImpactVoiceSuggestionSurface` tests are flag-independent and stay as they are | Changed **in commit 5**, not before |
| Flag-on interaction test (same class) | new | with the flag on, tapping `impact_voice_mic_button` invokes `onRecordImpactVoice` on the fake actions. No engine involved | In commit 5 |
| `SherpaOnnxTranscriptionServiceTest` | unchanged | 5 tests including the hash pin and the cancellation bound | Regression, re-run |
| `OpenSourceLicensesScreenTest` | new (in **4b-1**) | the Parakeet entry renders with `CC BY 4.0` | Must be green in 4b-1 |
| Migration / DAO / other Compose tests | unchanged | regression | Re-run |

### G.3 Device discipline, which caused a false red in 4a

`adb devices` **currently lists nothing**: the emulator must be booted before any of G.2 can run.

Pin every command to the emulator. A stray physical device split a 4a run across two targets and
produced a false red:

```
adb devices                 # confirm exactly emulator-5554, and nothing else
export ANDROID_SERIAL=emulator-5554
./gradlew :app:connectedDevDebugAndroidTest
```

or `adb -s emulator-5554 ...` for any direct adb step. If a physical device is attached for other
reasons, unplug it or pin explicitly. Do not interpret a red run until `adb devices` has been
re-checked.

Two practical notes for the run: the dev-debug APK is about **750 MB**, so install time is
minutes, not seconds; and `AsrEgressTest` plus `SherpaOnnxTranscriptionServiceTest` must share one
service instance per process for the reason 4a found the hard way (each loaded recogniser is a
622 MiB resident tenant, and three at once gets the instrumentation process SIGKILLed on a 4 GB
emulator).

### G.4 The repo's persisted-row rule

`CLAUDE.md`'s backend convention (a test that verifies a write survived a failure path must assert
the persisted DB row) is untouched by 4b: nothing here goes near the write path.
`ConsultationVoiceUnconfirmedRefusalTest` already satisfies it for the gate's backstop, asserting
the absence of the row against a real Room database, and it stays green as a regression check
across the flip. Worth noting explicitly because the flip is the moment that backstop stops
guarding a dark path and starts guarding a live one.

---

## DECISION GATE

Nothing below is decided by this memo.

1. **Permission-denial dead end (D.3), in 4b or deferred?** Recommendation: **in 4b-2**, one
   optional `onDenied` on the shared helper. The flip is what makes it reachable.
2. **Licences screen or bundled NOTICE (E.1)?** Recommendation: **the screen**, matching the SOUP
   record's own PROPOSED placement.
3. **Does the arm64 physical-device egress run (L3.5) block the flip?** Recommendation: **no** for
   a dev-only flip, **yes** before any APK leaves the machine. The emulator run is `x86_64` and
   the shipped ABI is `arm64-v8a`; that gap is named in A.3 either way.
4. **H-15 residual wording (C.2, C.3).** Operator sign-off required. PROPOSED only.
5. **Split (F).** Recommendation: two PRs, 4b-1 licences then 4b-2 proofs-plus-flip. Do not split
   the proofs from the flip.
6. **`VOICE_INPUT_ENABLED` stays false (B.2).** Confirm, so the two flags are not conflated by a
   later reader.
