# On-device SLM engine: STEP 1 design memo

Status: DESIGN, reviewed at gate M1, updated at gate M2 with the G2 report, and at gate M3 with
the T1 result and rulings batch 5 (section 16), then with rulings batch 6 (Y-21, S1-L, R-S2).
Risk and SOUP content in section 13 is PROPOSED, AWAITING OPERATOR SIGN-OFF.
Branch: `feat/on-device-slm`, worktree `/media/sandesh/extra-ssd/wt-on-device-slm`, base
origin/master `1ce3b4aada3a3a904302039d6162266b41deb3b7` (VERIFIED, `git rev-parse HEAD`).
Date: 2026-10-15.

Tag convention: VERIFIED means a file:line in this worktree, a command plus its output, or a
document at a pinned commit. "G2 REPORT section N" cites the GPU-machine report
`/mnt/twotb/ondevice-parity/REPORT.md` (VERIFIED there, pasted by the operator at M2). "T1" cites
the GPU-machine `T1_REPORT.md` (VERIFIED there, summarised by the operator at M3). INFERRED gives
its reason. PENDING FIXTURE marks the one input still to arrive: the 70-entry HTML tag piece list
file and its sha256 (G-5 final, 5.6).

LiteRT-LM citations are to tag `v0.18.0` = commit `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`,
sources fetched into `/media/sandesh/extra-ssd/samd-slm-parity/litertlm-src-b2f686e2/`.

---

## 1. Scope, non-goals, untouched

### 1.1 Scope of the first PR (R2)

- A `LocalSlmEngine` that runs Gemma 4 E2B from the pinned generic `.litertlm` through
  LiteRT-LM 0.18.0, in the **dev flavor only**.
- Engine identity, artifact verification, accelerator selection at init, deadline,
  single-flight, cancellation, release on memory pressure (SLM only; ASR release is a separate PR,
  Y-11).
- The four device `SLM_READBACK_*` audit rows, written by the seam for both engines (R6).
- An `SlmEngine` contract extension that carries "who answered" (R3), applied to both engines.
- A parity harness: golden prompt export, server run (operator terminal), desktop run, Pixel
  instrumented run, comparator.
- Proposed risk-file and SOUP amendments (section 13), all PROPOSED.

### 1.2 Non-goals

- Turning on `SLM_READBACK_ENABLED` anywhere. It stays `false` in every flavor
  (`app/src/main/java/com/example/samdapp/config/FeatureFlags.kt:176`, VERIFIED). UI in dev is a
  later, separate ruling (R2).
- Any routing between engines at runtime in the product path. The product seam keeps
  `RemoteSlmEngine` in every flavor in this PR (section 4.1).
- The Google Tensor G6 artifact: the follow-up PR right after the generic path passes parity
  (Y-18, section 3.7).
- Production artifact delivery (Y-10, deferred).
- ASR release on memory pressure (Y-11, separate PR).
- A device request id joining remote `slm_call_log` rows (Y-9, deferred: it changes the backend
  StrictModel).
- Any change to the backend proxy, the GPU service, or the remote wire contract.
- Any change to prompt text or `PROMPT_TEMPLATE_VERSION` (`slm-readback-v1`). G-5 (final) rules
  out whitespace normalization; the only prompt-side change is a new pre-call refusal (5.6).

### 1.3 Untouched, and how that is proven

| Thing | Guarantee | Proof in STEP 2 |
|---|---|---|
| staging, prod behaviour | No new class reachable; Hilt graph for `SlmEngine` unchanged; flag false | Existing `ReportReadbackTest."the flag as shipped is off in every flavor"` stays green; new test (Y-1): the R6 audit writes are unreachable in staging and prod while `SLM_READBACK_ENABLED` is false; APK scan: staging and prod contain no `liblitertlm_jni.so` and no `com.google.ai.edge.litertlm` class (slice S1) |
| `RemoteSlmEngine` request and response wire | Byte-identical JSON | `RemoteSlmEngineTest.theRequestCarriesThisBuildsPinAndTemplateAndOmitsTheDeterminismFields` stays green unchanged |
| Backend `backend/core` | No file changes | `git diff --stat origin/master -- backend/` empty at PR time |
| F1 files | No edits to F1-only files | Section 12 list; PR diff checked against it |

Y-1 (ruled): the R6 audit writes land in the shared seam (`SlmReadbackUseCase`, `src/main`),
which is compiled into staging and prod. They are unreachable there because the only caller is
behind the flag (`ReportViewModel.kt:237,267`, VERIFIED). Accepted, provided the flag-off tests
stay green and a test asserts the unreachability.

---

## 2. Verified facts relied on

All in the worktree at 1ce3b4a unless stated.

Device seam:
- F-1 `SlmEngine` at `domain/slm/SlmEngine.kt:171`; `generate(caseRecordId: String, prompt: String,
  maxOutputTokens: Int): Flow<String>` :209; `servedModelId(): String?` :240;
  `finishReason(): SlmFinishReason?` :252. `SlmEngineError` 8 values :75-135. VERIFIED.
- F-2 `RemoteSlmEngine` `@Singleton` `data/remote/RemoteSlmEngine.kt:67-68`; single-flight
  `Mutex` :98 with `tryLock` :111 throwing UNAVAILABLE; `lastResponse` :105 cleared before each
  call :121; `servedModelId()` returns null unless both `model_id` and `model_sha256` non-blank
  :146-152. VERIFIED.
- F-3 Binding `@Binds @Singleton bindSlmEngine(impl: RemoteSlmEngine): SlmEngine` in
  `di/SlmNetworkModule.kt:137-139`, in `src/main`, so all flavors. VERIFIED.
- F-4 `buildPrompt` `domain/slm/SlmReadbackUseCase.kt:441-463`, one plain string, no roles.
  `PROMPT_TEMPLATE_VERSION = "slm-readback-v1"` :430. The question is `trim()`ed :339. VERIFIED.
- F-5 Limits `MAX_QUESTION_CHARS = 500`, `MAX_PROMPT_CHARS = 6000`, `MAX_OUTPUT_TOKENS = 512`
  at `domain/slm/SlmScopeGate.kt:30,39,43`. VERIFIED.
- F-6 Pre-call control-token refusal `SlmReadbackUseCase.kt:350`; stream sanitizer :372-378;
  identity gate `servedModelMatchesSanitizer(engine.servedModelId())` :393; completeness gate
  :402 against `COMPLETE_FINISH_REASONS = {STOP, STOP_SEQUENCE}` :493-494; grounding gate for
  non-physician tier :408; tier :360, :483; engine error to refusal map :514-524 (PAYLOAD_REJECTED
  gives ENGINE_REJECTED_INPUT :519, TIMEOUT gives ENGINE_TIMEOUT :517). VERIFIED.
- F-7 Sanitizer pin `SANITIZER_TARGET_MODEL_ID = "google/gemma-4-E2B-it"`,
  `SANITIZER_TOKENIZER_REVISION = "3e22461f65e89153144f8adb70e3b8c2cc9845a7"`,
  `SANITIZER_TOKENIZER_SHA256 = "cc8d3a0c...bfe0f"` at `domain/slm/SlmStreamSanitizer.kt:60-66`;
  exact-match `servedModelMatchesSanitizer` :88-89; `CONTROL_TOKENS` :203 =
  `RESERVED_UNUSED` (:142, `<unused0>`..`<unused6226>`) + `NAMED_RESERVED` (:173-180).
  `SlmSuppression` fields `spans`, `characters`, `droppedTokens`, `unknownTokens` :37-40.
  VERIFIED.
- F-8 Approval: `ApprovedRecordReader.kt:57-58`, null `kernelDecision` gives NOT_APPROVED.
  VERIFIED.
- F-9 Audit vocabulary `AuditAction.SLM_READBACK_INVOKED/_INPUT_REFUSED/_OUTPUT_SUPPRESSED/
  _SANITIZER_ACTIVITY` at `domain/audit/AuditLogger.kt:185,191,195,205`; KDoc :167-183 says "Not
  emitted yet" and fixes the payload rule (metadata only, never question, output or drug name).
  No write site anywhere in `app/src` (grep, same commit). Writer
  `AuditLogger.log(action, patientId, caseRecordId, payload)` :6-8; helper
  `auditPayload(vararg Pair<String,String?>)` :209-210. VERIFIED.
- F-10 Backend accepts those four device actions:
  `backend/core/app/domain/audit_actions_device.py:79-93`. VERIFIED.
- F-11 Flavor guard `SlmEngineIsUnreachableFromPresentationTest.noFlavorSourceSetImplementsOrBindsTheEngine`
  fails if any flavor source set names `SlmEngine`
  (`app/src/test/java/com/example/samdapp/config/SlmEngineIsUnreachableFromPresentationTest.kt:94-105`).
  VERIFIED. This guard must be narrowed for a dev-only engine (4.1).
- F-12 SLM HTTP stack is `backendClient.newBuilder()` with 10/55/55/60 s
  (`di/SlmNetworkModule.kt:55,65,82,110-116`). VERIFIED. Not used by the local path.
- F-13 Control-token mirror test asserts the backend maps the service's 8009 to device
  CONTROL_TOKENS_REJECTED without tripping the breaker
  (`app/src/test/java/com/example/samdapp/data/remote/SlmControlTokenMirrorTest.kt:73-125`).
  VERIFIED.
- F-14 ASR: `SherpaOnnxTranscriptionService` `@Singleton`
  (`data/transcription/SherpaOnnxTranscriptionService.kt:103-111`), recognizer lazy :175-178,
  never released; `MODEL_THREADS = 4` :61; decode on `Dispatchers.Default` :145. No
  `onTrimMemory` or `ComponentCallbacks2` in `app/src`. VERIFIED.
- F-15 Gradle: AGP 9.2.1, Kotlin 2.3.10, coroutines 1.11.0 (`gradle/libs.versions.toml:2-3,18`);
  compileSdk 37, minSdk 26, targetSdk 37, `abiFilters += listOf("arm64-v8a", "x86_64")`
  (`app/build.gradle.kts:86-93,104`); `noCompress += listOf("onnx")` :256; sherpa AAR :329; no
  `ndkVersion`. VERIFIED.
- F-16 Manifest: `<application android:name=".SaMDApplication"` (`app/src/main/AndroidManifest.xml:20-21`);
  a dev manifest exists (`app/src/dev/AndroidManifest.xml`); no `uses-native-library` anywhere.
  VERIFIED.
- F-17 Dev applicationId `com.example.samdapp.dev` (`app/build.gradle.kts:91,115`). VERIFIED.

Backend proxy (remote path only):
- F-18 Fixed decode `temperature 0, do_sample False, stop []`; pass-through `max_tokens`, `seed`,
  `model_id`, `prompt_template_version`, `prompt` (`backend/core/app/services/slm.py:371-380`).
  Facility scope :406-409; phi_guard :424-425 (`adapters/slm/phi_guard.py:58-81`); breaker :435;
  `_record` writes `slm_call_log` + audit :150-222; SLM_CALL_FAILED via `_fail` :225-241;
  SLM_CALL_FORWARDED :586-593; 8009 maps to SAMD-SLM-8016 :109, :530-545; any other 4xx
  (including the service's 8003 over-length) maps to `SLM_PAYLOAD_REJECTED` :546-552, which the
  device maps to PAYLOAD_REJECTED (`RemoteSlmEngine.kt:363`) and the seam to
  ENGINE_REJECTED_INPUT (F-6). No approval check. VERIFIED.

GPU service (G1 and G2 REPORT section 1): `google/gemma-4-E2B-it` rev 3e22461f, bf16,
transformers 5.5.0, torch 2.4.1+cu124, weights sha256
`2db5482b20d746879bb3ef79b5203e9075a2e2b98f54ec7c2f281c1477ddc550`; renders
`'<bos><|turn>user\n{prompt trimmed}<turn|>\n<|turn>model\n'`; greedy; EOS ids 1, 106, 50;
2048-token prompt cap (8003); decode cap 512; 40 s deadline; rejects 24 special tokens (8009).

Risk register:
- F-19 Highest row in `docs/quality/risk-management-file.md` §2 is H-36; SLM rows H-22..H-25
  (:50-53), H-27; H-28 reserved (:68). VERIFIED.
- F-20 H-37..H-45 reserved by `docs/design/samdpi-two-hub-memo.md:1813-1829`, which also states
  "H-30 and H-31 are reserved". VERIFIED. H-46 taken by F1. New rows start at **H-47**.
- F-21 `docs/quality/soup-validation-record.md` covers ASR and CameraX only; no Gemma, SLM or
  LiteRT entry (grep, VERIFIED).

Device (Pixel 11 Pro, S1 probe 2026-10-15): Android 17, SDK 37, Tensor G6, `ro.hardware.egl`
powervr, PAGE_SIZE 4096, MemTotal 16,030,796 kB, MemAvailable 4,478,500 kB at probe,
`/vendor/lib64` has `libOpenCL.so`, `libOpenCL-pixel.so`, `libedgetpu_litert.so`,
`libdarwinn_hal.so`; no Vulkan library matched; `/data` 415G free. VERIFIED (adb outputs).

Contradictions found between facts, code and rulings: none (section 15.1 lists the one gap the
rulings make visible in code: four control-token literals the device does not yet carry).

---

## 3. Runtime and dependency

### 3.1 Version (Y-2: 0.18.0)

- `litertlm-android` on Google Maven: latest and release **0.18.0**, `lastUpdated 20261006154905`
  (VERIFIED, `https://dl.google.com/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml`,
  saved as `/media/sandesh/extra-ssd/samd-slm-parity/litertlm-android-maven-metadata.xml`).
- Upstream tag `v0.18.0` = `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27` (VERIFIED, GitHub tags API).
- The G2 desktop run used the same runtime version: litert-lm 0.18.0 (litert-lm-api 0.18.0,
  litert-lm-builder 0.18.0) (G2 REPORT section 1).
- AAR sha256 `706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f`, POM sha256
  `3c631cd8e04fe4289818d41675c0f6b1f26a7a37db63606699a51e2c95695a54` (VERIFIED, `sha256sum`).
  No `-sources.jar` and no `.module` published (HTTP 404, VERIFIED). Licence Apache-2.0 (POM).

### 3.2 minSdk and transitive dependencies

- AAR manifest `<uses-sdk android:minSdkVersion="24" />` (VERIFIED). App minSdk 26: no conflict.
- POM compile dependencies (VERIFIED): `com.google.code.gson:gson:2.14.0`,
  `org.jetbrains.kotlin:kotlin-reflect:2.4.0`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`.
  - coroutines matches the app (1.11.0).
  - gson 2.14.0 wins resolution over the version `retrofit converter-gson 2.11.0` brings.
    INFERRED (highest-version resolution). Existing DTO and sync Gson tests are the net.
  - `kotlin-reflect 2.4.0` pulls `kotlin-stdlib 2.4.0`. INFERRED (same-version stdlib).

### 3.3 Kotlin metadata compatibility (Y-3)

- `META-INF/...litertlm-android.kotlin_module` header bytes
  `0000 0003 0000 0002 0000 0004 0000 0000`: metadata version **2.4.0** (VERIFIED, `xxd`).
- Kotlin 2.3.10 consuming 2.4.0 metadata and stdlib: INFERRED compatible (a compiler reads the
  next minor's metadata). Proof is a build.
- Y-3 fallback order (ruled):
  1. Build as is (slice S1) and report the resolved `kotlin-stdlib` and `kotlin-reflect`
     versions from `dependencyInsight`.
  2. If it fails, pin the newest `litertlm-android` whose Kotlin metadata is 2.3 or lower, and
     record it (the `.kotlin_module` header check above, per candidate).
  3. A Kotlin bump only as its own PR.
  `kotlin-reflect` is never excluded.
- AGP 9.2.1: no AGP-specific constraint in the AAR (plain `jni/` + `classes.jar`). Same S1 gate.

### 3.4 Native libraries added

From the 0.18.0 AAR (VERIFIED, `unzip -l`):

| ABI | Library | Bytes |
|---|---|---|
| arm64-v8a | `liblitertlm_jni.so` | 22,155,680 |
| x86_64 | `liblitertlm_jni.so` | 26,353,160 |

`NEEDED` entries are system only: `libandroid libz libGLESv2 libEGL libGLESv3 liblog libdl libm
libc` (VERIFIED, `readelf -d`).

The JNI library also names, as strings, libraries it may `dlopen` that are not in the AAR
(VERIFIED, `strings`): `libLiteRtGpuAccelerator.so`, `libLiteRtOpenClAccelerator.so`,
`libLiteRtClGlAccelerator.so`, `libLiteRtVulkanAccelerator.so`, `libLiteRtWebGpuAccelerator.so`,
`libLiteRtTopKOpenClSampler.so`, `libLiteRtTopKWebGpuSampler.so`, `libOpenCL.so`,
`libOpenCL-pixel.so`, `libvndksupport.so`, `libvulkan.so`, `libcdsprpc.so`, `libGLES_mali.so`.

- `com.google.ai.edge.litert:litert-gpu:2.3.0` ships `libLiteRtClGlAccelerator.so` (arm64
  3,253,032 B) and depends on `litert:2.3.0` (`libLiteRt.so`, arm64 5,629,696 B). VERIFIED.
- The pinned getting-started doc says GPU needs only `uses-native-library` for `libvndksupport.so`
  and `libOpenCL.so`, both `required="false"` (`docs/api/kotlin/getting_started.md:101-109`,
  VERIFIED). It does not mention `litert-gpu`.
- Y-4 (ruled): `litert-gpu:2.3.0` is added, as a second SOUP item, only if slice S2 shows on the
  Pixel that `Backend.GPU()` fails to initialise with the AAR alone.

### 3.5 16 KB alignment

- `liblitertlm_jni.so` LOAD `p_align` is `0x4000` for both ABIs (VERIFIED, `readelf -lW`).
- Current dev debug APK: `zipalign -c -P 16 -v 4` with build-tools 37.0.0 (highest at or above
  35.0.0 under `/media/sandesh/extra-ssd/Android/Sdk/build-tools/`) reports every `lib/` entry
  `(OK)` and `Verification successful` (VERIFIED, saved as
  `/media/sandesh/extra-ssd/samd-slm-parity/zipalign-dev-debug.txt`). That APK came from the F1
  tree (INFERRED from date and branch). S1 re-runs the check on the dev APK containing
  `liblitertlm_jni.so`.
- The Pixel uses 4 KB pages, so 16 KB is a Play requirement here, not a runtime one.

### 3.6 Packaging and ABI

- No file-name collision with sherpa-onnx or SQLCipher libraries (VERIFIED against the S1 APK
  listing). No `pickFirst` needed. Symbol-level conflict between two native runtimes in one
  process: INFERRED low risk (separate `.so` namespaces), proven in S2 by loading both.
- `.litertlm` is never packaged in the APK (section 9), so no `noCompress` change.
- Y-5 (ruled): keep `lib/x86_64/liblitertlm_jni.so` in dev (F1 Phase 3 uses an API 36 AVD).
  The release ABI split stays on the housekeeping backlog.

### 3.7 NPU (R4, Y-18)

- Gallery 1.0.20 on the Pixel (VERIFIED, `/media/sandesh/extra-ssd/samd-slm-parity/gallery/GALLERY_REPORT.md`):
  only "Gemma-4-E2B-it TPU" (3.3 GB) downloaded, TPU the only accelerator; BP echo exact in 1.1 s,
  "OK" in 140.0 ms, thermal 0; no TTFT or tok/s (no stats view). Y-0: a Gallery smoke test of the
  generic model on GPU and CPU runs separately; the S2 harness produces the real numbers.
- Generic "Gemma-4-E2B-it" smoke test (VERIFIED, same report, section "Generic build"): offers
  GPU and CPU only; both initialised with no error and no silent fallback (reply labels "Model on
  GPU", "Model on CPU"); BP echo exact on both; latency BP / OK: GPU 2.0 s / 304.0 ms, CPU 2.3 s /
  527.0 ms; thermal 0. Run under Gallery sampling defaults (TopK 64, TopP 0.95, temperature 1.00),
  so not a determinism result. GPU initialising inside Gallery says nothing about our APK's need
  for `litert-gpu` (INFERRED: Gallery ships its own native libraries); Y-4 still decides on S2
  evidence.
- The Tensor G6 artifact `gemma-4-E2B-it_Google_Tensor_G6.litertlm`, 3,313,938,293 B, exists in
  the same repo and was not downloaded (G2 REPORT section 1). Y-18 (ruled): out of this PR; it is
  the follow-up PR right after the generic path passes parity, with its own pin and its own
  parity run, and adds `litert-npu-runtime-google-tensor:2.3.0`
  (`libLiteRtCompilerPlugin_google_tensor.so` 939,376 B, `libLiteRtDispatch_GoogleTensor.so`
  579,024 B, VERIFIED listing). Kotlin entry points `Backend.NPU(nativeLibraryDir)` and
  `Backend.GOOGLE_TENSOR` exist (`Config.kt:158,160`, VERIFIED).

---

## 4. Engine design

### 4.1 Contract: extend, do not fork

`LocalSlmEngine` implements the existing `SlmEngine` (`generate`, `servedModelId`,
`finishReason`). One additive member carries R3:

```kotlin
/** Who answered the generation just collected. Read after the flow completes, like servedModelId. */
fun answeredBy(): SlmEngineIdentity?

data class SlmEngineIdentity(
    val engine: SlmEngineKind,          // REMOTE or LOCAL
    val accelerator: String?,           // null for REMOTE; "GPU" or "CPU" for LOCAL
    val modelId: String?,               // served model id, verbatim
    val artifactSha256: String?,        // envelope model_sha256 (REMOTE) or verified file digest (LOCAL)
    val runtimeVersion: String?,        // null for REMOTE; "litertlm-android 0.18.0" for LOCAL
)
```

`servedModelId()` stays the identity gate's only input (F-6, F-7); `answeredBy()` feeds audit.

Call sites touched (VERIFIED by grep of `SlmEngine` implementers and constructors):
`domain/slm/SlmEngine.kt`; `data/remote/RemoteSlmEngine.kt` (REMOTE, from `lastResponse`);
`domain/slm/SlmReadbackUseCase.kt` (audit, section 8); test fakes `RecordingSlmEngine`
(`SlmReadbackUseCaseTest.kt:61`) and `RecordingEngine` (`ReportReadbackTest.kt:479`); new
`LocalSlmEngine` (dev).

Binding (R2, R3): the Hilt binding does not change. `SlmNetworkModule.Bindings` keeps binding
`RemoteSlmEngine` in `src/main` for all flavors. `LocalSlmEngine` lives in `src/dev`, is not bound
to `SlmEngine`, and is constructed only by the dev parity harness and its tests. So there is no
runtime engine switch anywhere in this PR, which meets R3's "never changed mid-request" by
construction; the later UI ruling adds an explicit pre-request choice.

F-11's guard is narrowed, not deleted: exactly one flavor file,
`app/src/dev/java/com/example/samdapp/data/slm/LocalSlmEngine.kt`, may *implement* `SlmEngine`;
any `@Binds`/`@Provides` of `SlmEngine` outside `di/SlmNetworkModule.kt` fails, and any
`SlmEngine` mention in `src/staging` or `src/prod` fails.

### 4.2 Lifecycle

- Lazy: nothing loads at app start. First `generate` (or the harness's `warmUp()`) verifies the
  artifact (6.3), then `Engine(EngineConfig(...)).initialize()` (`Engine.kt:61`; KDoc "can take a
  significant amount of time (e.g., 10 seconds)" :53-57, VERIFIED).
- `EngineConfig(modelPath, backend, visionBackend = null, audioBackend = null, maxNumTokens = 2560,
  cacheDir = <cacheDir>/slm/<artifact sha256>/)` (`Config.kt:202-212`, VERIFIED). `maxNumTokens` is "the sum
  of input and output tokens ... the size of the kv-cache" (`Config.kt:189-190`, VERIFIED);
  2560 = 2048 + 512, matching G2's `max_num_tokens=2560` (G2 REPORT section 1; Y-7').
- **Derived cache directory (C-2, batch 7).** The runtime's cacheDir is a dedicated directory
  created by us, `cacheDir/slm/<artifact sha256>/`, not the app `cacheDir` itself, so its whole
  content is the runtime's and can be integrity-pinned and deleted as a unit (6.3).
- **Speculative decoding pinned (C-3, batch 7).** `ConversationConfig.enableSpeculativeDecoding =
  false` explicitly, never null (null inherits the runtime's choice). `automaticToolCalling = false`
  approved. The desktop golden run (S5) pins the same.
- **API floor (C-4, batch 7).** Below API 29 (`Build.VERSION_CODES.Q`, where
  `PowerManager.getCurrentThermalStatus()` first exists) the local engine refuses to start:
  UNAVAILABLE, reason `thermal_unavailable`. No start without a readable thermal status (10).
- `ExperimentalFlags.enableBenchmark = true` before `initialize()` (`ExperimentalFlags.kt:34`,
  passed into `nativeCreateEngine` at `Engine.kt` init args, VERIFIED), because the finish reason
  and prompt-length backstop read `BenchmarkInfo` (5.3, 5.5).
- Holder: `@Singleton` in the dev Hilt graph (not bound to `SlmEngine`), owning one `Engine`.
- **One new `Conversation` per readback, closed after it, never reused** (G-1'):
  `engine.createConversation(config)` (`Engine.kt:120`, VERIFIED) inside `generate`, `close()`
  (`Conversation.kt:743`, VERIFIED) in `finally`. No history can accumulate.

### 4.3 Threading

- Dedicated single-thread dispatcher (`Executors.newSingleThreadExecutor { Thread(it, "slm-local") }
  .asCoroutineDispatcher()`), owned by the holder. Engine init, conversation creation and close,
  and cancellation calls run on it. `sendMessageAsync` returns a `callbackFlow`
  (`Conversation.kt:441-470`, VERIFIED) driven by native callbacks; collection happens in the
  caller's flow, the setup on `slm-local`.
- Reason: native init and conversation calls block (`Engine.kt:53-57`), one thread serialises
  native ordering, and generation stays off `Dispatchers.Default`, which ASR decode uses (F-14).

### 4.4 Single-flight and cancellation

- Same rule as remote: `Mutex.tryLock()`, a second call throws `SlmEngineException(UNAVAILABLE)`
  (F-2). Per-generation state (`lastFinish`, `lastIdentity`) cleared before each call.
- Cancellation: on flow cancellation the engine calls `Conversation.cancelProcess()`
  (`Conversation.kt:669`, VERIFIED) and then closes the conversation. Unlike the remote path
  (`SlmEngine.kt:194-207`), cancelling stops compute. `CancellationException` is rethrown, never
  turned into a refusal (`SlmReadbackUseCase.kt:379-380`). Since the conversation is discarded,
  the KDoc note that rollback of internal state is a pending feature (`Conversation.kt:668`,
  "b/450903294") does not matter here.
- **Emit once, after the post-run checks (Y-20).** The engine buffers the runtime's chunks
  internally and emits a single chunk only after `onDone`, the prefill backstop (5.5) and the
  finish derivation (5.3) have run. Any check that fails throws from the flow before the emit, so
  no output of a refused generation reaches the seam. This matches `RemoteSlmEngine`, which also
  emits the whole text as one chunk (`RemoteSlmEngine.kt:127`, VERIFIED) and keeps the seam's
  sanitizer contract unchanged (it accepts any chunking, `SlmEngine.kt:40-45`, VERIFIED).

### 4.5 Deadline

- `withTimeout(40.seconds)` around generation (init excluded), matching the service's 40 s from
  arrival. On expiry: `cancelProcess()`, close, throw `SlmEngineException(TIMEOUT)` (maps to
  ENGINE_TIMEOUT, F-6).
- Init has its own bound: 60 s, then UNAVAILABLE. Y-16: the harness records cold-init time; the
  bound is revisited if init exceeds 10 s.

### 4.6 Accelerator selection (R4)

- Order: `Backend.GPU()`; if `initialize()` throws, close, log, then `Backend.CPU(threadCount = 4)`
  (`Config.kt:144-149`, VERIFIED); if that throws, UNAVAILABLE and the init latch (section 7).
  Decided at init only; reported in every `answeredBy()`.
- **The default order is not final (Y-16', batch 7).** It is decided from S5 time-to-answer data,
  including init, over the golden prompts. Measured so far (S2): GPU cold init 30,640 ms, warm
  7,663 ms; CPU init 2,355 ms; probe (b) total GPU 1,671 ms vs CPU 1,882 ms.
- **GPU init failure in the audit (C-7, batch 7).** A GPU init failure that fell back to CPU
  reaches the audit only as the boolean `gpu_init_failed` in the `SLM_READBACK_INVOKED` payload
  (8), never as error text.
- No mid-generation fallback: a GPU error during decode is ENGINE_ERROR for that readback.
- `threadCount = 4` matches ASR's `MODEL_THREADS` (F-14). INFERRED; tune from S2.

---

## 5. Prompt and decoding parity (G-1', G-2', G-3', Y-7')

### 5.1 Rendering: Conversation with the bundled template

- Path: `Engine.createConversation(ConversationConfig(...))` and `sendMessageAsync(Message.user(prompt))`.
  `Message.user(text)` (`Message.kt:78`, VERIFIED).
- **The bundled template is used, never overridden.** `ConversationConfig.chatTemplate = null`;
  `Engine.createConversation` falls back to `ExperimentalFlags.overwritePromptTemplate` when
  `chatTemplate` is null (`Engine.kt:150,163`, VERIFIED), so the engine also asserts at init that
  `ExperimentalFlags.overwritePromptTemplate == null`, and a test pins it.
- Why the bundled template is right: it is 11,995 B, sha256 `02b3091a...f629`, byte-identical to
  the repo `chat_template.jinja`; it is a different version from the service's 18,569 B template,
  but renders a single user turn identically (G2 REPORT section 2). The artifact sha256 (6.3) pins
  it, since the template is inside the file.
- Server rendering vs device rendering, prompt (a): litertlm renders
  `'<|turn>user\nReply with the single word OK.<turn|>\n<|turn>model\n'`; the server renders the
  same with a leading `<bos>`; that text is the only difference (G2 REPORT section 2). The runtime
  prepends BOS id 2 (INFERRED in G2 from prefill counts 16/34/39 matching the server); literal
  `<bos>` text would tokenise as 3 ordinary tokens `[236820,46757,236813]`, so it must never be in
  the string (G2 REPORT section 2).
- Token ids for prompt (a) match position by position after BOS is prepended, 16 each; prompts
  (b), (c), a Hindi string and a padded string also match (G2 REPORT section 2).
- `systemInstruction = null`, `initialMessages = emptyList()`, `tools = emptyList()`
  (`Config.kt:265-267`): one user turn, nothing else.
- `thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)`
  (`Config.kt:128-130,275`, VERIFIED; same values G2 used, G2 REPORT section 3; the artifact
  reports "Supports Thinking: NO").
- `channels = emptyList()` (Y-19, approved), which disables runtime channel parsing so any
  channel text reaches `SlmStreamSanitizer` instead of being split into `Message.channels`
  (`Engine.kt` channels handling, "If channels is empty, channels will be disabled", VERIFIED).
  Parity compares post-sanitizer text on both engines (11.5).
- Output text: concatenation of `Message.contents.toString()` per emitted message
  (`Message.kt:67,120`, VERIFIED), fed chunk by chunk to the sanitizer.

**Required test (G-1'):** for every golden prompt, `conversation.renderMessageIntoString(
Message.user(prompt))` (`Conversation.kt:712`, `@ExperimentalApi`, VERIFIED) on the device path
equals the server rendering minus the leading `<bos>`, byte for byte. The server renderings come
from the GPU-machine G-6 run as `golden/server-render-v1.jsonl`. Runs on the Pixel as part of the
dev instrumented suite (slice S3). If it fails, overriding the template with the server's 18,569 B
Jinja needs a new ruling.

Trim: the server trims the prompt (G1). The device prompt starts with fixed text and ends with
the question, which is `trim()`ed (F-4), so it carries no leading or trailing whitespace unless
the two trims disagree. Kotlin `String.trim()` strips chars where `Char.isWhitespace()` is true;
Jinja's `trim` is Python `str.strip()`. INFERRED: both strip U+00A0 and U+2003 and neither strips
U+200B; not measured. S3 adds a test over leading and trailing ASCII space, tab, CRLF, U+00A0,
U+2003 and U+200B in the question that compares the device rendering with the server rendering of
the same input (both from the G-6 run), so any divergence is measured rather than assumed.
Divergent whitespace is also G-5's subject (5.6).

### 5.2 Sampler (G-2')

`ConversationConfig.samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0,
seed = 20260918)` and `ConversationConfig.maxOutputToken = 512` (`Config.kt:274,295-299`,
VERIFIED), every field set explicitly. The JNI maps all four fields and forces sampler type TOP_P
(`jni/litertlm.cc:246-267`, VERIFIED), matching G2's Python finding that the type is always TOP_P
(G2 REPORT section 3). Unset fields would default to top_p 0.95 and temperature 1.0 (G2 REPORT
section 3), which is why none is left unset. A JVM test asserts the config the engine builds,
field by field. Seed equals the remote `SLM_REQUEST_SEED` (`RemoteSlmEngine.kt:40`, VERIFIED).

Determinism: two desktop runs per prompt were identical (G2 REPORT sections 3 and 4). top_k 1 as
the greedy mechanism is INFERRED in G2.

### 5.3 Stop ids and finish reason (G-3')

- Stop ids are met offline: litertlm reports BOS 2 and stop ids 1, 50, 106; the server's EOS ids
  are 1, 106, 50; same set (G2 REPORT section 2), for artifact sha256
  `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`. They come from the model
  metadata and are read-only (G2 REPORT section 3). The sha256 check before every engine start
  (6.3) pins them.
- The Kotlin API returns no finish reason (no such field on `Message`, `Message.kt:35-40`,
  VERIFIED). The engine derives it after `onDone`:
  `getBenchmarkInfo().lastDecodeTokenCount` (`Conversation.kt:682`, `Benchmark.kt:31-38`,
  VERIFIED) below 512 gives STOP; at or above 512 gives LENGTH; benchmark unavailable or any
  exception gives null, which the seam refuses (existing test "a response carrying no finish
  reason is refused as truncated"). INFERRED: a generation that ends naturally on exactly the
  512th token is reported LENGTH and refused; that is the fail-closed direction.
- Required device test (G-3'): golden short prompts (a) and (b) end with finish reason STOP, not
  LENGTH (S3). (a) and (b) are G2's probe strings (G2 REPORT section 4), carry no record data, and
  are used verbatim as engine behaviour tests; they are not members of the R8 golden record set.
- Companion device test: a prompt asking for a long enumeration, with `maxOutputToken` lowered to
  16 in the test only, finishes LENGTH, so the derivation is proven in both directions.

### 5.4 Special tokens in output

The device output goes through `SlmStreamSanitizer` (F-6) before any gate. Parity is compared
after the sanitizer on both sides (11.5).

### 5.5 Limits (Y-7')

| Limit | Server | Device local | On breach |
|---|---|---|---|
| Question chars | n/a | 500 (F-5) | QUESTION_TOO_LONG before engine (unchanged) |
| Prompt chars | 6000 schema | 6000 (F-5) | RECORD_TOO_LARGE before engine (unchanged) |
| Prompt tokens after template | 2048, 8003 | engine context 2560 = 2048 + 512 | typed `SlmEngineException(PAYLOAD_REJECTED)`, the same refusal the remote path gives for 8003 (ENGINE_REJECTED_INPUT, F-18, F-6) |
| Output tokens | 512 | `maxOutputToken = 512` | LENGTH, seam refuses OUTPUT_TRUNCATED |
| Wall clock | 40 s | 40 s (4.5) | TIMEOUT |

- The 0.18.0 Kotlin API exposes no tokenizer (VERIFIED: no tokenize `external fun` in
  `LiteRtLmJni.kt`), so the bound is enforced by context size plus the mandatory backstop below.
  The G-6 GPU-machine token-parity run reports every golden prompt's server token count.
- **The 2049 to 2560 band (Y-20, mandatory).** With context 2560, a prompt of 2049 to 2560
  tokens fits the KV cache, so the runtime will not fail, while the server refuses it with 8003.
  The backstop gives refusal parity in that band: after `onDone`, if
  `getBenchmarkInfo().lastPrefillTokenCount > 2048` (`Benchmark.kt:31-38`, VERIFIED), the engine
  discards the buffered output and throws `SlmEngineException(PAYLOAD_REJECTED)`. It throws before
  the flow's single emit (4.4), so no output reaches the seam. If benchmark info is unavailable,
  the engine throws the same error: the prompt length cannot be proven, so the generation is
  refused (fail closed).
- Required device tests (Y-7', Y-20), engine-level so they bypass the seam's 6000-char bound:
  1. A prompt of about 2100 tokens is refused by the backstop (ENGINE_REJECTED_INPUT).
  2. A prompt over 2560 tokens is refused, by a runtime error or by the backstop, both mapped to
     ENGINE_REJECTED_INPUT.
  3. Both assert the flow emitted nothing.
  **If either produces visible output, or the runtime truncates silently and the backstop does not
  fire, STOP and report: that needs a new ruling.**
- Through the seam, a 6000-char prompt can exceed 2048 tokens (INFERRED: Devanagari text packs
  fewer characters per token than English). Such a prompt is refused on both engines, so it is a
  refusal-parity case, not a failure (11.5).

### 5.6 Tokenization-divergent text (G-5 final, T1)

T1 result (VERIFIED on the GPU machine, T1): 209 synthetic probes, each standalone and embedded in
the readback skeleton, 418 comparisons.
- Rendered strings identical in all 418.
- Ids diverged in 29 probes (the same 29 in both modes), only for HTML tags and `<unusedN>`
  literals.
- Rule, predicting all 418 with no false positive or false negative: the two tokenizers differ if
  and only if the text contains a litertlm user-defined piece spelled `<tag>`, `</tag>` or
  `<unusedN>`: 70 HTML tag pieces plus `<unused0>` to `<unused6226>`, 6,297 pieces.
- No divergence for: whitespace runs, Unicode whitespace, clinical angle brackets (`BP <90`, `>=`,
  the symbols `≤ ≥`), units, Hindi, romanized Hindi, bullets, emoji, other bracket forms, and
  near-miss non-pieces (`<UNUSED5>`, `<unused05>`, an unclosed tag, `<unused6241>`).
- Correction to G2 REPORT section 2: the "whitespace runs" divergence was wrong; that test string
  also contained `<b>`.

Final rule (G-5):
- No whitespace normalization and no `PROMPT_TEMPLATE_VERSION` change.
- `<unusedN>`: already refused before any call on both engines, because `RESERVED_UNUSED` is
  exactly `<unused0>`..`<unused6226>` (`SlmStreamSanitizer.kt:142`, VERIFIED), the same range T1
  names.
- The 70 HTML tag pieces: the pre-call input guard, at the same point as `firstControlTokenIn`
  (`SlmReadbackUseCase.kt:350`, both engines), also refuses any prompt containing one of them,
  matched as exact case-sensitive substrings against a pinned list file.
  - File: `app/src/main/resources/slm/litertlm-html-tag-pieces-v1.txt`, one piece per line,
    transferred by the operator from the GPU machine. **sha256: PENDING FIXTURE** (recorded in this
    memo when the file arrives, before slice S6 lands).
  - Loaded once, lazily, by an `internal` object in `domain/slm/`. A missing or unreadable file
    refuses every prompt (fail closed); the JVM test below makes that a build-time failure rather
    than a field one.
  - Test: the file has exactly 70 entries, its sha256 equals the recorded digest, every entry
    matches `^</?[a-z0-9]+>$` (INFERRED shape from T1's `<tag>`/`</tag>` description; the test
    fails loudly if T1's list contains another shape, which then needs a look), and the T1 near
    misses above are not refused.
  - Refusal: the existing `SlmRefusal.PROMPT_CONTROL_TOKENS` and its copy (no new user-facing
    text). The `SLM_READBACK_INPUT_REFUSED` audit `reason` is `tokenizer_divergent_markup`, so it
    is distinguishable from a control-token refusal (8).
- Scope: the guard is in the shared seam, so the remote engine refuses these prompts too. This is
  intended (both engines, per G-5) and unreachable in staging and prod while the flag is off (Y-1).
- Field consequence (INFERRED): a physician who types an HTML-like tag such as `<b>` into a
  diagnosis makes that record's readback refuse on both engines; the record itself stays readable.

---

## 6. Identity and integrity

### 6.1 Local identity (G-4)

- Artifact: `litert-community/gemma-4-E2B-it-litert-lm` commit
  `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`, file `gemma-4-E2B-it.litertlm`, 2,588,147,712 B,
  sha256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` (equals the Hub LFS
  hash) (G2 REPORT section 1).
- `servedModelId()` for LOCAL returns exactly
  `litert-community/gemma-4-E2B-it-litert-lm@b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1#181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`,
  built from the verified digest of the file actually loaded, never from a constant. A digest
  mismatch means init never happens, so there is no id at all.
- Gate (G-4): `servedModelMatchesSanitizer` stays an exact string comparison, now against an
  explicit allowlist of two exact entries: `SANITIZER_TARGET_MODEL_ID` (remote, unchanged) and the
  local id above. No prefix, contains or case folding. The exact match is never loosened.
- `answeredBy()` LOCAL: `modelId` the local id, `artifactSha256` the verified digest,
  `accelerator`, `runtimeVersion = "litertlm-android 0.18.0"`.

### 6.2 Vocabulary equivalence (R5, met per G-4)

- G2 VERIFIED: all 24 service special tokens are present in the litertlm SentencePiece vocab at
  identical ids, none missing (G2 REPORT section 2).
- Device test: `CONTROL_TOKENS` covers all 24. **It fails today on four**: the G1 list contains
  `<|image>`, `<image|>`, `<|audio>`, `<audio|>`, and `NAMED_RESERVED` (`SlmStreamSanitizer.kt:173-180`,
  VERIFIED) carries `<|image|>`, `<|audio|>`, `<|video|>` but not those four. These are the four
  boundary markers the sanitizer KDoc says were "not guessed" (:160-167, VERIFIED); G1 now gives
  their spellings. Slice S6 adds them, with the test as the gate, in the same commit as the
  allowlist entry. Not a contradiction: the ruling requires the test, and the test drives the fix.
- The 6,386 further litertlm atomic pieces (G2 REPORT section 2) are G-5 / T1's subject, not R5's.

### 6.3 Artifact sha256 before every engine start (Y-8, closes A-04 for local)

- Full SHA-256 of the 2,588,147,712 B file on the `slm-local` thread before `Engine.initialize()`,
  on every engine start (first use and after every release). Mismatch: UNAVAILABLE, reason
  `artifact_digest_mismatch`, never loaded.
- Cost INFERRED at a few seconds on the Pixel; the harness records the measured hash time per
  engine start (Y-8).
- This also pins the bundled template (5.1) and the stop ids (5.3), both inside the file.

**Derived runtime cache integrity (C-2, batch 7; extends H-48).** The runtime writes derived
files (the GPU program cache, about 1.6 GB at S2) into its cacheDir and keys them by
name, mtime and size, not by digest (S2 finding). The artifact digest does not cover them, so:
- The engine cacheDir is `cacheDir/slm/<artifact sha256>/`, dedicated, created by us (4.2).
- At every clean release (engine closed normally) the engine writes a manifest of every file in
  that directory (relative path, size, sha256) to `filesDir/slm/<artifact sha256>.cache-manifest.json`,
  atomically: a temp file, then rename. A crash between the write and the rename leaves no valid
  manifest.
- At every engine start, before `initialize()`: if a manifest exists, every listed file is verified
  (size and sha256) and no unlisted file may exist; on any mismatch the cache directory and the
  manifest are deleted, then cold init. If the cache directory is non-empty and no manifest exists,
  the directory is deleted, then cold init.
- The verify time is measured per start; over 3,000 ms is reported to the operator (YOUR CALL).
- Threat model: corruption (a crash mid-write, storage faults), not an attacker with app-private
  write access. Such an attacker can replace the manifest too; the artifact digest gate is the
  control against substitution of the model itself.

---

## 7. Guards: backend-only controls on the local path

| Remote control (F-18) | Purpose | Local replacement | Reason |
|---|---|---|---|
| Facility scope (`services/slm.py:406-409`) | Stop a caller reading another facility's case | Readback seam refuses, fail closed, when the session's facility differs from the facility recorded as owning the local data, or either is unknown (Y-21 Option 3, slice S4) | F-1 finding below: the INFERRED claim of M2 is not supported by code. |
| `phi_guard` case id + 10-digit run (`adapters/slm/phi_guard.py:58-81`) | Stop identifiers leaving the backend toward the GPU host | Case-id absence kept as a prompt-construction invariant | No egress on the local path. S4 adds a JVM test that `buildPrompt` output never contains `snapshot.caseRecordId`. The digit-run rule is an egress rule and does not apply. |
| Circuit breaker (`services/slm.py:435`) | Stop hammering a failing remote | Init-failure latch | After GPU and CPU init both fail, the engine stays UNAVAILABLE until a release resets it. Prevents a slow failing init per request. |
| `slm_call_log` | Per-call operational record | The four device audit rows (section 8), synced | Engine, accelerator, model id, sha256, runtime, template version, prompt chars, token counts, duration, outcome. |
| `SLM_CALL_FORWARDED` / `SLM_CALL_FAILED` | Server-origin audit | `SLM_READBACK_INVOKED` with `answeredBy()` and outcome | The server cannot see a local call. Joining remote rows to device rows needs a device request id: Y-9, deferred and filed. |
| Auth hop (service token) | Authenticate backend to GPU | None | No network hop. |

### 7.1 F-1: facility scope of local case data (finding)

The M2 claim "no cross-facility data exists locally" was INFERRED and is **not** supported by code.
VERIFIED:
- The case read has no facility filter: `CaseRecordRepositoryImpl.observeCaseRecord` is
  `caseRecordDao.observeById(caseRecordId)` (`data/repository/CaseRecordRepositoryImpl.kt:61-62`),
  and `ApprovedRecordReader` reads through it (`domain/slm/ApprovedRecordReader.kt:40`).
- No local entity carries a facility id or an owning worker id (grep for
  `val .*([Ff]acility|[Ww]orker|createdBy|[Oo]wner)` over `data/local/entity/*.kt` finds only
  `DoctorEntity.facilityName` :15). `CaseRecordEntity` columns are listed at
  `data/local/entity/CaseRecordEntity.kt:13-40`.
- Sign-out clears tokens only, not the database: `BackendAuthSession.signOut` calls
  `authApiService.logout` and `tokenStore.clear()` (`data/local/auth/BackendAuthSession.kt:67-73`).
  The only database deletions are on Keystore loss and on the plaintext-to-encrypted migration
  (`data/local/security/DatabasePassphraseProvider.kt:40,97`).
- The backend binds a device per worker, not per facility: `touch_device` records a
  `(device_id, worker_id)` pair and adds one for any new worker (`backend/core/app/services/auth.py:280-307`).
  Nothing found refuses a sign-in on a phone previously used by another facility's worker.

What happens today when a worker from facility B signs in on a phone holding facility A's cases
(INFERRED from the code above; not exercised): A's case records remain in the encrypted local DB
and are readable through every local read path that does not filter by worker or facility. On the
**remote** SLM path, the server's facility check refuses the readback (`services/slm.py:406-409`,
404 `ENC_CASE_NOT_FOUND`). On the **local** path no such check exists, so the local engine would
read back facility A's approved record to the facility B worker. That is a parity regression for
the readback feature specifically, on top of a pre-existing cross-facility exposure for the rest
of the app's local reads. Listed as YOUR CALL Y-21 (15.3) and proposed hazard H-54 (13.2).

**Ruling Y-21 (batch 6): Option 3 in this PR.** The readback seam (both engines) refuses, fail
closed, when the signed-in session's facility differs from the facility recorded as owning the
local data, or when either is unknown. It lands in slice S4 (Opus), in the shared seam, so the
remote path gains the same device-side refusal ahead of the server's 404. The product-wide
exposure (local clinical data visible across facilities on a shared phone, on every screen) is a
separate HIGH PR with its own hazard row, leaning to: wipe local clinical data on a
different-facility sign-in only after the outbox has fully drained, and block that sign-in, never
wipe, while it has not.

**Ruling Y-21' (S4, supersedes the Step 3 storage design; verbatim in 16).** No schema change, no
migration and no sign-in change in this PR. As built in S4:
- Session facility: `worker.facility_id` of the backend login response (`LoginResponseDto.worker`,
  `data/remote/dto/AuthDtos.kt:17,27`), stored by `BackendAuthSession.signIn`
  (`data/local/auth/BackendAuthSession.kt:45`) into `DataStoreAuthTokenStore` (`FACILITY_ID`,
  `data/local/auth/DataStoreAuthTokenStore.kt:38`, written at :119, cleared with the session at
  :145). Read through `SessionFacilitySource`, which that store implements (`currentFacility`,
  :75-80); only while a worker is signed in; never from a token.
- Owner: `LocalDataOwnerFacility` returns `OwningFacility.Known(id)` or `Unknown`. The only
  implementation in this PR, `UnknownLocalDataOwnerFacility`, always returns `Unknown`, so the seam
  refuses every readback.
- The check is step 0 of the seam, before the record is read, for every tier: allowed only when
  both sides are known and the ids are equal; never compared by name. Refusal reuses
  `ENGINE_REJECTED_INPUT` (the same refusal and copy the remote path gives for another facility's
  case, the backend's 404) with `INPUT_REFUSED` reason `facility_scope`.
- The marker store (where it lives, the migration, when the claim runs, the wipe-after-drain
  policy) moves to the product-wide facility-scope PR (HIGH, filed), with its own Opus memo.

---

## 8. Audit rows (R6, closes A-03)

Written by `SlmReadbackUseCase`, so one code path covers both engines. `AuditLogger` is injected
into the use case (new constructor parameter; touches `SlmReadbackUseCaseTest.kt:164` and
`ReportReadbackTest.kt:457`, VERIFIED).

| Row | When | Payload fields (`auditPayload`) |
|---|---|---|
| `SLM_READBACK_INPUT_REFUSED` | Any return before `engine.generate` (`SlmReadbackUseCase.kt:322-365`) | `reason` (the `SlmRefusal` name, except `tokenizer_divergent_markup` for the G-5 guard, 5.6), `tier`, `template_version`, `question_chars`, `prompt_chars` (null if not built) |
| `SLM_READBACK_INVOKED` | Exactly once per call that reached `engine.generate`, written after the flow ends so it carries the outcome | `engine`, `accelerator`, `model_id`, `artifact_sha256`, `runtime_version`, `template_version`, `tier`, `prompt_chars`, `max_output_tokens`, `finish_reason`, `engine_error`, `duration_ms`, `thermal_status` (Y-15), `gpu_init_failed` (C-7, boolean, never error text) |
| `SLM_READBACK_SANITIZER_ACTIVITY` | After every collected generation, including zero counts | `spans`, `characters`, `dropped_tokens`, `unknown_tokens` (`SlmSuppression`, F-7) |
| `SLM_READBACK_OUTPUT_SUPPRESSED` | Any refusal after generation: SERVED_MODEL_MISMATCH, OUTPUT_TRUNCATED, OUTPUT_NOT_GROUNDED (:393-410) | `reason`, `engine`, `model_id` |

Rules: `caseRecordId` in its column, `patientId = null`. Never the question, prompt, output, drug
name or diagnosis (F-9). Every value is an enum name, count, id or digest. An audit write failure
never changes the readback result (`CancellationException` rethrown, other failures logged).

**Ordering (A-1).** `SLM_READBACK_INVOKED`, and `SLM_READBACK_OUTPUT_SUPPRESSED` when it applies,
are written before the use case returns any result to its caller, so a readback is never shown
without its audit row. A failed audit write is logged and does not block the result (rule above);
the ordering guarantees the write was attempted first.

A-1 finding, VERIFIED from code: no chunk reaches the UI before the post-generation gates.
- The seam collects every chunk into a local `StringBuilder` through the sanitizer
  (`SlmReadbackUseCase.kt:372-378`); nothing is emitted while collecting.
- The identity, finish and grounding gates run on the finished text (:393-410), and the only
  text-bearing return is `SlmReadbackResult.Answer` after them (:417-421).
- `invoke` is a `suspend` function returning one `SlmReadbackResult`, not a flow (:320).
- The ViewModel sets `ReadbackState.Generating`, then replaces it with the single result
  (`presentation/report/ReportViewModel.kt:275-287`); its comment says "There is no token trickle
  to wait for either: the grounding gate withholds the whole generation until it completes"
  (:273-274).
- With 4.4's single emit, the local engine also withholds its output until its own checks pass.

Sync: existing device audit outbox; actions already allowlisted (F-10), no backend change.

Tests (S4): one per row per trigger class; refused-before-engine writes INPUT_REFUSED and not
INVOKED; sentinel strings placed in question and output are absent from every payload; both
engines through the fake (`answeredBy()` LOCAL and REMOTE); the persisted Room row is read back,
not only the fake's call list (CLAUDE.md backend convention applied to the device); Y-1's
unreachability test for staging and prod with the flag false; A-1 ordering: a recording
`AuditLogger` and a recording caller assert the INVOKED (and OUTPUT_SUPPRESSED) write happens
before `invoke` returns; the G-5 guard writes INPUT_REFUSED with reason
`tokenizer_divergent_markup` and the user-facing refusal is PROMPT_CONTROL_TOKENS.

---

## 9. Artifact delivery

### 9.1 Dev method (API 37, debuggable dev build)

Location: `/data/user/0/com.example.samdapp.dev/files/slm/181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c.litertlm`.
Content-addressed so a wrong file cannot pass by name.

Operator steps (device `adb-67041FDKX004ZY-Eggjhy._adb-tls-connect._tcp`):
```
SHA=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
F=/media/sandesh/extra-ssd/samd-slm-parity/model/gemma-4-E2B-it.litertlm
sha256sum "$F"                                   # must print $SHA
adb -s <dev> push "$F" /data/local/tmp/slm.litertlm
adb -s <dev> shell "run-as com.example.samdapp.dev mkdir -p files/slm"
adb -s <dev> shell "cat /data/local/tmp/slm.litertlm | run-as com.example.samdapp.dev sh -c 'cat > files/slm/$SHA.litertlm'"
adb -s <dev> shell "run-as com.example.samdapp.dev sha256sum files/slm/$SHA.litertlm"
adb -s <dev> shell "run-as com.example.samdapp.dev chmod 600 files/slm/$SHA.litertlm"   # C-5, batch 7
adb -s <dev> shell rm /data/local/tmp/slm.litertlm
```
INFERRED to work (`run-as` needs a debuggable package; the pipe avoids the app domain reading
`/data/local/tmp`). Not executed yet; slice S2 runs it first and records output. The model file is
downloaded on the GPU machine or desktop by the operator (not gated, G2 REPORT section 1) and
copied to the SSD path above.

Missing file: UNAVAILABLE, reason `artifact_missing`. Corrupt or wrong file: UNAVAILABLE, reason
`artifact_digest_mismatch`, file left in place, never loaded.

### 9.2 ASR comparison

ASR weights ship as APK assets (`encoder.int8.onnx` 652,184,296 B, VERIFIED S1 listing), making
the dev APK about 760 MB. A 2.59 GB `.litertlm` is never an APK asset (INFERRED: Play size limits
and install cost).

### 9.3 Production options (Y-10, deferred)

| Option | Pin and integrity | Tradeoff |
|---|---|---|
| Play AI packs / asset delivery | Play-signed, versioned with the release | Needs Play; government MDM fleets may not use it |
| MDM push to app-specific storage | Operator file + our sha256 check | Per-state MDM, no uniform path |
| First-run download from backend/core | Our server, our sha256, resumable | 2.59 GB over rural links; endpoint, storage, a partial-download hazard |
| Field-engineer sideload | Dev method without `run-as` | Not viable on non-debuggable builds |

---

## 10. Memory, thermal, residency

- Desktop CPU peak RSS 1,304,748 to 1,363,720 kB (G2 REPORT section 4), well under the 2.59 GB
  file size (INFERRED: weights memory-mapped). Phone GPU buffers untested.
- ASR resident about 630 MB plus ORT arena (F-14). On the 16 GB Pixel (4.3 GB MemAvailable at
  probe) both co-reside; on a 4 to 6 GB field phone that is not established (Y-12, deferred).
- Release policy (SLM only; ASR release is the separate PR, Y-11):
  - On `onTrimMemory(level >= TRIM_MEMORY_BACKGROUND)` and after 5 minutes idle (Y-16), close the
    `Engine`. Reload pays hash + init again; the harness records cold-init time.
  - A `ComponentCallbacks2` registered from the dev source set only (dev `Application` hook or an
    `androidx.startup` initializer in the dev manifest), so staging and prod are untouched.
  - Never release mid-generation: release takes the engine `Mutex` and defers if busy.
- Thermal (Y-15): before a run, `PowerManager.getCurrentThermalStatus()`; at
  `THERMAL_STATUS_SEVERE` or above, refuse with UNAVAILABLE reason `thermal`. Status recorded in
  `SLM_READBACK_INVOKED` (`thermal_status`).
- Below API 29 the status cannot be read, so the local engine refuses to start: UNAVAILABLE,
  reason `thermal_unavailable` (C-4, batch 7). It never treats an unreadable status as NONE.
- Field-phone capability probe (RAM, `isLowRamDevice`, OpenCL presence, free storage, Android
  version): described only; deferred to a fleet ruling (Y-12).

---

## 11. Parity harness

Root: `/media/sandesh/extra-ssd/samd-slm-parity/` (exists). JVM and Gradle processes run with
`TMPDIR=/media/sandesh/extra-ssd/tmp` and `-Djava.io.tmpdir=/media/sandesh/extra-ssd/tmp`.

### 11.1 Golden export (R8, Y-13, Y-17)

- Dev-only JVM test `app/src/testDev/java/com/example/samdapp/slm/GoldenPromptExport.kt`, gated by
  a system property so CI writes nothing: builds `ApprovedRecordSnapshot`s from synthetic, mock-only
  data and calls the real `buildPrompt` (F-4). Writes `golden/prompts-v1.jsonl`:
  `{id, template_version, prompt, prompt_sha256, question, question_script, tier, snapshot_fields}`.
  Also writes `golden/control-tokens.txt` (the exact `CONTROL_TOKENS` list) for the comparator.
- 120 records (Y-17). Questions in roughly equal thirds (Y-13): English, Hindi in Devanagari,
  romanized Hindi. Record coverage, each cell at least 3: every `kernelDecision` value; diagnosis
  null, short, long (near the 6000-char prompt bound), with numerals, with units; medications 0, 1,
  5, 12 lines with strengths, frequencies, durations; referral yes and no; one question per
  `ALLOWED_INTENTS` family (`SlmScopeGate.kt:103-110`) per language; 10 questions the worker gate
  refuses, to prove they are never sent (flagged `expected_input_refusal` by calling the real
  `inputScopeRefusal`, so the comparator excludes them from content scoring, 11.5).
- Records are generated, never hand-written; prompts come only from `buildPrompt`; no real
  patient data (R8).

### 11.2 Server run and token parity (operator, GPU machine)

`scripts/slm-parity/run_server.py` (committed, reviewed, stdlib only), run by the operator:
```
read -rs SLM_SERVICE_TOKEN; export SLM_SERVICE_TOKEN
python3 -I scripts/slm-parity/run_server.py --base-url <service URL> \
  --in golden/prompts-v1.jsonl --out runs/server-bf16.jsonl
unset SLM_SERVICE_TOKEN
```
Same body the backend sends (F-18). Records `text`, `finish_reason`, `model_id`, `model_sha256`,
usage, wall time. The token never reaches me.

A second operator script `scripts/slm-parity/token_parity.py` (G-6) renders each golden prompt
with the HF tokenizer at 3e22461f and with the litertlm tokenizer (BOS prepended), writes
`golden/server-render-v1.jsonl` (server rendering, for the 5.1 byte test) and
`runs/token-parity.jsonl` (`{id, server_ids_len, litertlm_ids_len, first_diff}`), and fails if any
prompt exceeds 2048 tokens (Y-7').

### 11.3 Desktop run

Python litert-lm 0.18.0 on the GPU machine, generic `.litertlm`, CPU, `create_conversation` with
the bundled template (the device path, G2 REPORT section 3), thinking off, sampler and
`max_output_tokens` as 5.2, `max_num_tokens=2560`. Writes `runs/desktop-cpu.jsonl`. G2's three
probes (section 4) are the smoke evidence; the golden run is new.

### 11.4 Pixel run

- Dev-only instrumented test `app/src/androidTestDev/java/com/example/samdapp/slm/LocalSlmParityHarness.kt`.
  Reads `prompts-v1.jsonl` from the app files dir (same `run-as` pipe), runs `LocalSlmEngine` per
  backend (GPU, CPU), applies `SlmStreamSanitizer`, writes one line per prompt per backend:
  `{id, backend, raw_text, sanitized_text, finish_reason, identity, hash_ms, init_ms, ttft_ms,
  prefill_tokens, prefill_tps, decode_tokens, decode_tps, total_ms, pss_kb_peak, thermal_before,
  thermal_after}`.
- Run: `./gradlew :app:installDevDebug :app:installDevDebugAndroidTest`, then
  `adb -s <dev> shell am instrument -w -e class com.example.samdapp.slm.LocalSlmParityHarness
  com.example.samdapp.dev.test/androidx.test.runner.AndroidJUnitRunner`, then pull via
  `run-as ... cat` to `runs/pixel-<backend>.jsonl`.
- Calls the engine directly, not the seam. The seam is covered with the fake.
- Performance (R7, measured first): one warm-up, then 20 consecutive generations per backend;
  `Debug.getPss()` sampled every 250 ms; thermal before and after.

### 11.5 Comparator (R7 + G-6)

`tools/slm-parity/compare.py` (stdlib only, `python3 -I`). Inputs: golden file, run files,
token-parity file. Semantics per C-1 (finalizes R7).

**Step 1, what would be SHOWN.** For each engine and prompt, the comparator reproduces the seam:
post-sanitizer text, then the post-generation gates in the seam's order
(`SlmReadbackUseCase.kt:393-410`):
1. Identity: the recorded `servedModelId` is one of the two exact allowlist entries (6.1).
2. Finish: `finish_reason` in {STOP, STOP_SEQUENCE} (:493-494).
3. Grounding: for non-physician tiers only, the port of `outputIsGrounded` (`SlmScopeGate.kt:155-162`).

Any failure, and any engine error, makes the result `REFUSED`, which is safe. Otherwise the shown
output is the post-sanitizer text. Golden rows whose question the seam's input gate refuses
(`inputScopeRefusal`, `SlmScopeGate.kt:129-138`, flagged by the golden export as
`expected_input_refusal`) are excluded from content scoring: in the product they never reach an
engine. The Pixel harness calls the engine directly, which is why the comparator re-applies the
seam. A Kotlin/Python agreement test over one fixture file proves the grounding and control-token
ports give identical verdicts.

**Step 2, content hard gates.** Four gates, deterministic:

| Gate | Computation |
|---|---|
| Numbers | Extract digit runs over ASCII digits and Devanagari digits U+0966 to U+096F (`[0-9०-९]+(?:[.,/][0-9०-९]+)*`); normalize Devanagari digits to ASCII; drop only line-leading enumeration markers matching `^\s*[0-9]+[.)]\s` (multiline); each remaining number must appear in the prompt's APPROVED RECORD section, normalized the same way. Numbers written as words are not caught (residual in H-47) |
| No control tokens | No literal from `golden/control-tokens.txt` in the shown output, plus the bracket shape rule |
| No fact absent from record | Port of `outputIsGrounded`: no drug-shaped token outside the medication lines, no dose numeral outside the record. Applied to every tier's shown output, including physician |
| Correct language (Y-13) | Dominant script of the shown output's letters (Latin or Devanagari), after removing every substring copied verbatim from the record (numbers, units, drug names), equals the question's script; romanized Hindi questions expect Latin |

Pass criteria:
- **LOCAL:** the four gates pass for 100 percent of the local engine's shown outputs.
- **SERVER:** the same gates are computed and reported for the server's shown outputs. A server
  failure is a readback-safety finding filed separately; it never relaxes a gate.
- **Non-inferiority:** no prompt where the server's shown output passes all four gates while the
  local shown output fails any.
- **Refusal rates** are reported per engine. A local refusal where the server answered is reported,
  not failed, unless the local refusal rate exceeds the server's by more than 10 percentage
  points, which is a failure to investigate.

**Step 3, prompt token-id parity (G-6), from `runs/token-parity.jsonl`.**
- Golden prompts over 2048 server tokens are expected refusals on both engines (8003 on the
  server, the 5.5 backstop locally) and are scored as a refusal-parity check: both must be
  REFUSED.
- Every other golden prompt must have identical id sequences (HF tokenizer at 3e22461f against
  the litertlm tokenizer with BOS prepended), 100 percent.

Reported, not gated: exact-match rate of shown outputs vs server; chrF vs server (stdlib,
character n-grams, n = 6, beta = 2). Disagreements (content-gate verdicts differ, refused on one
engine only, or shown texts not equal) go to `review/blind-sample.csv`: 30 pairs, or all
disagreements if fewer, stratified by language, engine labels stripped, order shuffled with a
fixed seed (Y-17).

G2 already shows the expected shape: short constrained outputs match exactly; free-form Hindi
output diverged in wording while carrying 128/84, 76 and 97 correctly on both (G2 REPORT
section 4). The hard gates test exactly that property.

Performance gates are set after the baseline (R7).

---

## 12. F1 overlap and merge order (Y-14)

Files both branches touch (VERIFIED, `git diff --stat origin/master...origin/feat/local-network-permission`):

| File | F1 change | This PR | Conflict |
|---|---|---|---|
| `domain/slm/SlmEngine.kt` | +5 (`SlmEngineError.LOCAL_NETWORK_NOT_ALLOWED`) | `answeredBy()` and types | Textual, trivial |
| `domain/slm/SlmReadbackUseCase.kt` | +5 | audit writes, `AuditLogger` param | Textual near `engineRefusalFor` |
| `data/remote/RemoteSlmEngine.kt` | +2 | `answeredBy()` | Low |
| `test/.../SlmReadbackUseCaseTest.kt` | +2 | constructor, audit tests | Textual |
| `test/.../RemoteSlmEngineTest.kt` | +1 | `answeredBy()` tests | Low |
| `presentation/report/SlmRefusalCopy.kt`, `res/values/strings.xml`, `di/NetworkModule.kt` | F1 only | none planned | none |

Semantics: F1's `LOCAL_NETWORK_NOT_ALLOWED` is remote-only; the local engine never produces it.
F1's "SLM bodies never logged" rule has no HTTP to apply to locally; the local engine never logs
prompt or output, enforced by a source-scan test in S2 (same shape as F1's scan guard).

Merge order (ruled): F1 merges first if both are ready at once; this branch is never blocked on
F1; whichever merges second merges master in with a merge commit, no rebase.

---

## 13. Risk and SOUP (all PROPOSED, AWAITING OPERATOR SIGN-OFF)

### 13.1 Amendments to existing rows

- **H-22** (SLM refuses nothing on its own): applies equally to the local engine; the seam's
  controls are engine-independent; the narrowed flavor guard (4.1) keeps the local engine off the
  product path.
- **H-23** (reasoning channel leaks): thinking disabled on the Conversation (G-1'); the artifact
  reports no thinking support (G2 REPORT section 3); sanitizer activity row now written (R6).
- **H-24** (open doctor tier on unverified role): local inference has no backend attribution row;
  replaced by the device audit row carrying `tier`.
- **H-25** (on-device latency and memory): primary risk of this PR. Evidence: G2 desktop timings
  and RSS (section 4), the S2 baseline; controls: release policy, thermal refusal, init latch.
- **H-27** (unverified generated text on a clinical artifact): the parity gates are a verification
  activity for the local engine; they make the engines comparable, not the output verified. G2
  shows free-form divergence under greedy on both sides (section 5).

### 13.2 New rows

| ID | Hazard | Primary controls |
|---|---|---|
| H-47 | The local engine produces different clinical content from the server for the same approved record (numerics, omitted medicine, added fact) and the worker trusts it equally | R7 hard gates at 100 percent on the local engine's shown outputs, plus non-inferiority against the server (C-1, 11.5); engine named in audit; local id passes the identity gate only through its exact allowlist entry (G-4). **Residual:** the number gate catches digits only (ASCII and Devanagari); a number written as words ("five", "पाँच") is not caught |
| H-48 | A substituted, corrupted or partially copied `.litertlm` is loaded, carrying a different template, vocabulary or stop ids; or a corrupted derived runtime cache (crash mid-write, storage fault) is loaded beside a good artifact | Full SHA-256 before every engine start (6.3); content-addressed file name; refusal on mismatch; file mode 600 after delivery (9.1, C-5). Derived cache (C-2): dedicated `cacheDir/slm/<sha256>/`, manifest of every file (path, size, sha256) written atomically at clean release, verified before every start, any mismatch, unlisted file or missing manifest over a non-empty directory deletes the cache and cold-inits. **Residual:** the manifest is not a defence against an attacker with app-private write access (threat model, 6.3) |
| H-49 | Text the two tokenizers split differently makes the local engine see a different prompt from the server. Per T1 this happens if and only if the text contains a litertlm user-defined piece: one of 70 HTML tag pieces (`<tag>`, `</tag>`) or `<unused0>`..`<unused6226>` | G-5 final: pre-call refusal of the 70 HTML tag pieces from a pinned 70-entry list file (5.6), `<unusedN>` already refused via `CONTROL_TOKENS`; both engines; G-6 token-id parity gate on the golden set. Whitespace removed from this row (T1 corrected G2) |
| H-50 | Two resident models exhaust memory, the OS kills the app mid-encounter, unsaved clinical work is lost | SLM release on trim and idle; never mid-generation; PSS baseline; ASR release (Y-11 PR); fleet probe (Y-12) |
| H-51 | Sustained generation heats the phone; readbacks time out or the worker abandons the screen | Pre-run thermal refusal at SEVERE (Y-15); 40 s deadline; thermal in audit; 20-run measurement |
| H-52 | A GPU init failure falls back to CPU and slower or different output is not attributable | Fallback only at init; accelerator in every audit row, plus the boolean `gpu_init_failed` in `SLM_READBACK_INVOKED` (C-7, never error text); parity run per backend; default backend order decided from S5 time-to-answer data including init (Y-16') |
| H-53 | An over-length prompt is answered locally while the server refuses it: in the 2049 to 2560 token band the runtime accepts it, and above 2560 it may truncate silently and answer from a partial record | Context 2560; mandatory prefill-count backstop (Y-20): `lastPrefillTokenCount > 2048` discards the output and refuses before the flow emits (4.4, 5.5); missing benchmark info also refuses; device tests at about 2100 and over 2560 tokens (STOP if visible output); G-6 refusal-parity scoring |
| H-54 | A worker from another facility signs in on a shared phone and the local engine reads back an approved record from the previous facility, which the remote path would refuse | Y-21 Option 3 (batch 6, slice S4): the readback seam, for both engines, refuses fail closed when the signed-in session's facility differs from the facility recorded as owning the local data, or when either is unknown. **Residual:** the rest of the app's local reads stay unscoped (7.1, F-1); that exposure is a separate HIGH PR with its own hazard row (wipe on a different-facility sign-in only after the outbox has fully drained; block that sign-in, never wipe, while it has not) |

### 13.3 SOUP entries

- **LiteRT-LM Android runtime** `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, Apache-2.0,
  AAR sha256 `706cbb8a...595f`, `liblitertlm_jni.so` arm64 22,155,680 B, tag `v0.18.0` @
  `b2f686e2`. Transitives gson 2.14.0, kotlin-reflect 2.4.0, coroutines 1.11.0. Validation: S1
  build, S2 on-device init per backend, S5 parity.
- **LiteRT GPU accelerator** `litert-gpu:2.3.0` + `litert:2.3.0`: only if S2 shows GPU needs it
  (Y-4).
- **Gemma 4 E2B `.litertlm` artifact**: `litert-community/gemma-4-E2B-it-litert-lm` @
  `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`, `gemma-4-E2B-it.litertlm`, 2,588,147,712 B, sha256
  `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`; not gated. Licence (G-7):
  the model card says apache-2.0 and the repo has no LICENSE file (G2 REPORT section 1); both facts
  recorded; confirmation against the upstream Gemma 4 terms filed for the sign-off batch.
  Embedded template 11,995 B sha256 `02b3091a...f629` (G2 REPORT section 2). Validation: G2
  vocabulary and stop-id facts, R7 parity.
- **Missing remote entry**: `google/gemma-4-E2B-it` rev 3e22461f, bf16 weights sha256
  `2db5482b20d746879bb3ef79b5203e9075a2e2b98f54ec7c2f281c1477ddc550`, served by transformers 5.5.0
  + torch 2.4.1+cu124, chat template 18,569 B (G2 REPORT sections 1 and 2). SOUP today with no
  entry (F-21). Added in the same record.

---

## 14. Build slices for STEP 2

Each slice: its own commits, its tests, a mutation row per new guard (snapshot-based; an empty
test-results dir is RED). T1 is resolved (5.6); the list file it produces gates S6.

| Slice | Content | Files | Tests | Mutation rows | Model |
|---|---|---|---|---|---|
| S1 | `litertlm-android:0.18.0` as `devImplementation`; dev manifest `uses-native-library` for `libvndksupport.so` and `libOpenCL.so` (`required="false"`) | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/dev/AndroidManifest.xml` | `compileDevDebugKotlin`; `dependencyInsight` for `kotlin-stdlib`, `kotlin-reflect`, `gson` (reported per Y-3); APK scan: staging and prod contain no `litertlm` class or `.so`; `zipalign -c -P 16` on the dev APK | `devImplementation` to `implementation`: staging APK scan RED | Sonnet |
| S2 | `LocalSlmEngine` (dev), the full engine (R-S2, batch 6): hash before init, GPU then CPU at init only, init latch, dedicated dispatcher, single-flight, cancel, 40 s, overwritePromptTemplate-null assertion, thermal pre-check, no-log source scan; the conversation configuration of 5.1 to 5.5 (bundled template, thinking off, one conversation per request, explicit sampler and `maxOutputToken`, context 2560, `channels = emptyList()`, finish reason from benchmark decode count, mandatory prefill backstop and single emit after post-run checks); a thin seam over the runtime so JVM tests run on a fake; dev Hilt holder not bound to `SlmEngine`; narrowed flavor guard; adb delivery executed and recorded; device smoke per backend; GPU init with AAR alone recorded (Y-4) | `src/dev/.../data/slm/LocalSlmEngine.kt`, the runtime seam and its adapter, `src/dev/.../di/LocalSlmModule.kt`, `SlmEngineIsUnreachableFromPresentationTest.kt` | JVM over a fake native seam: mutex, cancel calls `cancelProcess` then close, timeout gives TIMEOUT, digest mismatch never inits, missing file, GPU-fail-then-CPU, latch, flag assertion, thermal refusal, one conversation per call closed on every path; built `ConversationConfig` and `SamplerConfig` field by field; finish derivation table; backstop (2049 refused, 2048 allowed, unreadable refused); single emission after checks; no-log scan. Device smoke via `am instrument`: prompts (a) and (b) per backend | skip digest check; allow mid-generation fallback; allow a second concurrent call; one sampler field left default; finish derivation always STOP; backstop removed; guard admits a second flavor file | **Opus** |
| S3 | Device-side verification only (R-S2, batch 6), run once the G-6 files exist | `androidTestDev` only | Device: `renderMessageIntoString` equals server rendering minus `<bos>` for all 120 golden prompts; trim test (ASCII, CRLF, U+00A0, U+2003, U+200B); capped long prompt finishes LENGTH; about 2100-token prompt refused by the backstop; over-2560-token prompt refused (runtime error or backstop); both emit nothing (STOP and report if visible output) | n/a (device verification of S2 guards) | Sonnet |
| S4 | `answeredBy()` and the R6 audit rows in the seam, both engines; case-id-absence prompt test; Y-21' fail-closed facility check in the readback seam, both engines, against `LocalDataOwnerFacility` (UNKNOWN only in this PR: every readback refused) | `SlmEngine.kt`, `RemoteSlmEngine.kt`, `SlmReadbackUseCase.kt`, `FacilityScope.kt`, `DataStoreAuthTokenStore.kt` (session facility read), `RepositoryModule.kt`, two fakes | per-row trigger tests; INPUT_REFUSED without INVOKED; sentinel absence; persisted Room row; Y-1 staging/prod unreachability; A-1: INVOKED and OUTPUT_SUPPRESSED written before `invoke` returns; same facility allowed; facility differs, session facility unknown, owner unknown: each refused before any engine call, every tier; compared by id | drop one row; question text in a payload; INVOKED written after the return; OUTPUT_SUPPRESSED skipped on a grounding refusal; allow when the owner is UNKNOWN; compare by name instead of id; skip the check for the physician tier | **Opus** |
| S5 | Parity harness: golden export (120, thirds by language), server and token-parity scripts, desktop script, Pixel instrumented harness, comparator per C-1: seam replay to SHOWN outputs, four content gates on LOCAL at 100 percent, server reported, non-inferiority, refusal-rate rule (10 points), token-id parity with over-2048 rows scored as refusal parity; Kotlin/Python cross-check | `testDev/.../GoldenPromptExport.kt`, `androidTestDev/.../LocalSlmParityHarness.kt`, `scripts/slm-parity/*`, `tools/slm-parity/*` | comparator fixtures per gate; Kotlin/Python grounding and control-token agreement; language-gate fixtures for all three question types; number-gate fixtures for Devanagari digits and enumeration markers | number gate ignores decimals; Devanagari digits not normalized; enumeration exclusion not anchored to line start; language gate counts copied record text; refusal-rate rule off by one point | Sonnet |
| S6 | Four missing control-token literals; `CONTROL_TOKENS` covers the 24 G1 tokens; exact local allowlist entry (G-4); G-5 pre-call refusal of the 70 HTML tag pieces from the pinned list file, reusing PROMPT_CONTROL_TOKENS, audit reason `tokenizer_divergent_markup` | `SlmStreamSanitizer.kt`, `SlmStreamSanitizerTest.kt`, `SlmReadbackUseCase.kt`, new `domain/slm/TokenizerDivergentMarkup.kt`, `app/src/main/resources/slm/litertlm-html-tag-pieces-v1.txt` (PENDING FIXTURE) | coverage test over the 24; allowlist has exactly two exact entries; prefix or case variant of either is refused; list file has exactly 70 entries and the recorded sha256; a prompt with each piece is refused before any engine call; T1 near misses and clinical angle brackets are not refused; missing list refuses every prompt | remove one of the four literals; add a prefix match; case-insensitive markup match (`<B>` refused): near-miss test RED; drop one list entry: count test RED | Sonnet, Opus review of the identity change |
| S7 | SLM residency: dev-only `ComponentCallbacks2`, release on trim and 5 min idle, thermal pre-check at SEVERE | dev holder, dev initializer | trim levels release or not; release waits for a running generation; thermal refusal | release mid-generation allowed | Sonnet |
| S8 | Controlled docs: risk rows and SOUP entries from section 13, PROPOSED | `docs/quality/risk-management-file.md`, `docs/quality/soup-validation-record.md` | none | n/a | **Opus** |

Order: S1, S2, S4 (no external input); S6 once the 70-entry list file arrives; S3 and S5 once the
G-6 run delivers the rendering and token files; then S7, S8.

---

## 15. Open items

### 15.1 Findings for the operator

- Four device control-token literals are missing relative to G1's 24 (`<|image>`, `<image|>`,
  `<|audio>`, `<audio|>`), section 6.2. Fixed in S6 under G-4's coverage test.
- `ExperimentalFlags.overwritePromptTemplate` is a process-global that silently replaces the
  bundled template when set (`Engine.kt:150,163`); S2 asserts it is null.
- The finish reason is derived, not reported, by the runtime (5.3).
- G-3' names G2 probe prompts (a) and (b) as "golden short prompts"; confirmed by Q-ab as engine
  behaviour tests outside the R8 golden record set (5.3).
- A-1 (M3): no chunk reaches the UI before the post-generation gates; the seam returns one result
  after them (8, VERIFIED `SlmReadbackUseCase.kt:320,372-421`, `ReportViewModel.kt:273-287`).
- F-1 (M3): local case data is not facility-scoped (7.1). On a shared phone the local engine would
  read back another facility's approved record that the remote path refuses. H-54 proposed.
- T1 corrected G2: whitespace does not diverge (5.6). H-49 updated.
- S2 (batch 7): the runtime's derived cache is keyed by name, mtime and size, not digest, so the
  artifact digest does not cover it. C-2 adds the manifest (6.3).
- C-6 (batch 7): the mutant "served id from the constant" is equivalent, not a missed guard. The
  digest gate refuses any file whose digest differs from the constant, so the id built from the
  verified digest and the id built from the constant are identical on every path that reaches
  init. Documented here; no mutation row.

### 15.2 PENDING

- FIXTURE (G-5 final): `litertlm-html-tag-pieces-v1.txt`, 70 entries, from the GPU machine; its
  sha256 is recorded in 5.6 when it arrives. Gates slice S6.
- Y-16' (batch 7): the default backend order, from S5 time-to-answer data including init.
- DEPENDENCY (Y-21'): the later "enable readback UI in dev" ruling cannot be taken until the
  product-wide facility-scope PR (HIGH, filed) provides a real `LocalDataOwnerFacility`. Until
  then the only implementation returns UNKNOWN and the seam refuses every readback.

### 15.3 YOUR CALL

Resolved at M3: Y-19 approved (`channels = emptyList()`); Y-20 approved and mandatory.
Resolved at batch 6: Y-21 Option 3 in this PR (S4); the product-wide fix is a separate HIGH PR.

- **Y-21 (F-1) Facility scope on the local path.** Today nothing scopes local case data to the
  signed-in worker's facility (7.1). Options, for a ruling:
  1. Product-wide: wipe local clinical data on sign-out or on a sign-in whose facility differs
     from the last one, after the outbox is drained (data-loss risk if not drained; needs its own
     design and hazard row).
  2. Store `facility_id` on case records (Room migration) and filter reads by the session's
     facility; readback and every other local read path inherit it.
  3. Readback-only stopgap: record the facility of the session that last synced or created the
     DB, and have the seam refuse a readback when the current session's facility differs (fails
     closed, small, but leaves the rest of the app exposed).
  Recommendation: 3 inside this PR as the local-path control (it restores parity with the
  server's 404), and 1 or 2 as a separate product-wide PR with its own hazard row.

Filed by ruling (not this PR): Y-9 device request id; Y-10 production delivery; Y-11 ASR release;
Y-12 fleet capability probe; G-7 licence confirmation; Tensor G6 follow-up (Y-18).

---

## 16. Rulings 2026-10-15

Appended verbatim. The third batch's G-1, G-2, G-3 and Y-7 are superseded by G-1', G-2', G-3'
and Y-7' below them, and its G-5 candidate is superseded by batch 5's final G-5; the superseded
text is kept for the record.

### Third batch (gate review of M1)

Y-0 G2 is in. A Gallery smoke test of the generic model on GPU and CPU runs separately; the S2 harness produces the real numbers.
Y-1 Accepted, provided the existing flag-off-in-every-flavor tests stay green and a test asserts the new audit writes are unreachable in staging and prod while SLM_READBACK_ENABLED is false.
Y-2 litertlm-android 0.18.0.
Y-3 Fallback order: (1) build as is and report the resolved kotlin-stdlib and kotlin-reflect versions from dependencyInsight; (2) if it fails, pin the newest litertlm-android whose Kotlin metadata is 2.3 or lower, and record it; (3) a Kotlin bump only as its own PR. Never exclude kotlin-reflect.
Y-4 Accepted.
Y-5 Keep x86_64 in dev (F1 Phase 3 uses an API 36 AVD). The release ABI split stays on the housekeeping backlog.
Y-6 Superseded by G-1.
Y-7 Count prompt tokens before generation with the engine's own tokenizer if the 0.18.0 Kotlin API exposes it (cite it); otherwise after prefill and before any decode. No character-only check. Over 2048 tokens is refused with the same refusal the remote path gives for SAMD-SLM-8003. [SUPERSEDED by Y-7']
Y-8 Accepted. The harness records the measured hash time per engine start.
Y-9 Deferred: it changes the backend's StrictModel request schema, and the backend is untouched in this PR. Filed.
Y-10 Deferred.
Y-11 Separate PR (it touches all flavors). Filed.
Y-12 Deferred to a fleet ruling.
Y-13 The golden questions are English, Hindi in Devanagari, and romanized Hindi, in roughly equal thirds. The "correct language" gate is deterministic: the dominant script of the output's letters, excluding substrings copied verbatim from the record (numbers, units, drug names), must match the question's script.
Y-14 F1 merges first if both are ready at once. The on-device branch is never blocked waiting on F1. Whichever merges second merges master in with a merge commit (no rebase).
Y-15 Refuse at THERMAL_STATUS_SEVERE and above. Record the thermal status in the audit row.
Y-16 5 minutes idle to start. The harness records cold-init time; revisit if init exceeds 10 s.
Y-17 120 golden records. A blind clinician sample of 30 pairs (or all disagreements if fewer), stratified by language.
Y-18 Confirmed: the Google_Tensor_G6 artifact (3,313,938,293 B, exists per G2) is out of this PR, and is the follow-up PR right after the generic path passes parity.
G-1 Prompt path: a LiteRT-LM Session with the template disabled, and the prompt pre-rendered by our code as '<|turn>user\n' + trimmed prompt + '<turn|>\n<|turn>model\n' with NO literal <bos> (the runtime prepends BOS id 2; literal <bos> text tokenizes as 3 ordinary tokens, G2 section 2). Cite the 0.18.0 Kotlin API for the raw-string path. If Kotlin lacks it, STOP and report (the fallback would be the Conversation API with the bundled template behind a byte-compare test, and needs a new ruling). Trimming must match the server's Jinja trim exactly: the memo states which characters each side strips, and a test covers ASCII and Unicode whitespace at both ends (U+00A0, U+2003, U+200B, CRLF). A JVM test asserts our rendering equals the server rendering minus the leading <bos> for every golden prompt. [SUPERSEDED by G-1']
G-2 Sampler: set every field explicitly (top_k 1, top_p 1.0, temperature 0.0, seed 20260918), thinking disabled, max output 512. Never rely on defaults (unset fields default to top_p 0.95 and temperature 1.0, G2 section 3). [SUPERSEDED by G-2']
G-3 Stop ids: the engine must report exactly {1, 50, 106} at init, or init is refused with a typed error. Test it. [SUPERSEDED by G-3']
G-4 Identity: R5's vocabulary equivalence is met by the G2 VERIFIED fact (all 24 service special tokens at identical ids) plus a device test that CONTROL_TOKENS covers them. The local model passes the identity check only through an explicit allowlist entry (model id, HF commit b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1, artifact sha256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c). Never loosen the exact match.
G-5 Tokenization-divergent text (HTML-like tags, <unusedN>, whitespace runs): PENDING T1, which is a characterization now running on the GPU machine. State the candidate rule: buildPrompt normalizes whitespace in record-derived fields for BOTH engines, and the input guard refuses any prompt containing a construct T1 shows to diverge (a refusal fails closed and the record stays readable). The final rule comes in a later ruling.
G-6 R7 gets a fifth hard gate: prompt token-id parity of 100 percent on the golden set (HF tokenizer at 3e22461f against the litertlm tokenizer with BOS prepended), computed on the GPU machine.
G-7 License: the model card says apache-2.0 and the repo has no LICENSE file (G2 section 1). The SOUP entry records both facts. A license confirmation against the upstream Gemma 4 terms is filed for the sign-off batch.

### Replacement rulings (supersede G-1, G-2, G-3 and Y-7)

G-1' Prompt path: the Conversation API with the template BUNDLED in the pinned artifact (do not override it; G2 section 2 shows it renders a single user turn identically to the server, and the artifact sha256 pins it). Thinking disabled. One NEW Conversation per readback request, closed after the request, never reused: no history may accumulate. Required test: for every golden prompt, renderMessageIntoString on the device path must equal the server rendering minus the leading '<bos>', byte for byte. Overriding the template with the server's 18,569 B Jinja is allowed only if that test fails, and needs a new ruling. The Session path and a patched JNI build are rejected.
G-2' Decoding: set every sampler field explicitly (top_k 1, top_p 1.0, temperature 0.0, seed 20260918) and maxOutputToken 512 explicitly on the Conversation. Never rely on defaults.
G-3' Stop ids: met offline. G2 VERIFIED {1, 50, 106} for artifact sha256 181938105e...a63c, and the sha256 check before every engine start pins them. On device: a behavioural test asserts that the golden short prompts (a) and (b) end with a STOP finish reason, not LENGTH.
Y-7' Prompt length: set the engine context to 2560 tokens (2048 prompt plus 512 output, matching G2's max_num_tokens). The GPU-machine token-parity run (G-6) proves every golden prompt is 2048 tokens or fewer. A device test feeds an over-length prompt and asserts it surfaces as a typed error mapped to the same refusal as SAMD-SLM-8003. If the runtime truncates silently instead of failing, STOP and report: that needs a new ruling.
All other rulings from the previous batch apply unchanged: Y-0 to Y-6, Y-8 to Y-18, G-4, G-5 (PENDING T1), G-6, G-7.

### Batch 5 (gate review of M2)

T1 RESULT (VERIFIED on the GPU machine, T1_REPORT.md): 209 synthetic probes, each tested standalone and embedded in the readback skeleton, 418 comparisons. Rendered strings were identical in all 418. Ids diverged in 29 probes (the same 29 in both modes), only for HTML tags and <unusedN> literals. The rule predicted all 418 with no false positive or false negative: the two tokenizers differ if and only if the text contains a litertlm user-defined piece spelled <tag>, </tag> or <unusedN> (70 HTML tag pieces plus <unused0> to <unused6226>, 6,297 pieces). Whitespace runs, Unicode whitespace, clinical angle brackets (BP <90, >=, the symbols ≤ ≥), units, Hindi, romanized Hindi, bullets, emoji and other bracket forms did NOT diverge. Correction: the "whitespace runs" finding in G2 REPORT section 2 was wrong (that test string also contained <b>). Variants that are not pieces (<UNUSED5>, <unused05>, an unclosed tag, <unused6241>) do not diverge.

RULINGS (batch 5)
G-5 (final, replaces the candidate): no whitespace normalization and no PROMPT_TEMPLATE_VERSION change. The pre-call input guard (the same point as firstControlTokenIn, SlmReadbackUseCase.kt:350, both engines) also refuses any prompt containing one of the 70 litertlm HTML tag pieces, matched as exact case-sensitive substrings against a pinned list file. <unusedN> is already refused through CONTROL_TOKENS. The list arrives from the GPU machine as a fixture file (the operator transfers it); the memo records its sha256, and a test asserts it has exactly 70 entries and the recorded digest. The refusal reuses the existing PROMPT_CONTROL_TOKENS refusal and copy (no new user-facing text); the audit reason distinguishes it as tokenizer_divergent_markup.
Y-19 Approved: channels = emptyList(), so channel text reaches SlmStreamSanitizer. Parity compares post-sanitizer text on both engines.
Y-20 Approved and MANDATORY. Reason: with context 2560, a prompt of 2049 to 2560 tokens fits the context, so the runtime will not fail, while the server refuses it (8003). The backstop (lastPrefillTokenCount > 2048: discard the output and throw PAYLOAD_REJECTED) is what gives refusal parity in that band. It must throw before the flow completes, so no output reaches the seam. Tests: a prompt of about 2100 tokens is refused by the backstop; a prompt over 2560 tokens is refused (runtime error or backstop, both mapped to ENGINE_REJECTED_INPUT). STOP and report if either produces visible output.
Q-ab Confirmed: G2 prompts (a) and (b) are engine behaviour tests outside the R8 golden record set.
A-1 Audit ordering: SLM_READBACK_INVOKED (and OUTPUT_SUPPRESSED when it applies) is written before the use case returns any output to its caller, so a readback is never shown without its audit row. Cite from code whether any chunk reaches the UI before the post-generation gates (SlmReadbackUseCase.kt:393-410). If chunks stream to the UI before the gates, STOP and report.
C-1 Comparator semantics (finalizes R7):
 - Hard gates judge what would be SHOWN: each engine's output after the sanitizer and the seam's post-generation gates (identity, finish, grounding for the non-physician tier). A seam refusal counts as "refused", which is safe.
 - The four content gates (numbers, no control tokens, no fact absent from the record, correct language) must pass for 100 percent of the LOCAL engine's shown outputs. The same gates are reported for the server's shown outputs; a server failure is a readback-safety finding filed separately, never a reason to relax a gate.
 - Non-inferiority: no prompt where the server's shown output passes while the local shown output fails.
 - Refusal rates are reported per engine. A local refusal where the server answered is reported, not failed, unless the local refusal rate exceeds the server's by more than 10 percentage points, which is a failure to investigate.
 - Number gate: extract ASCII digits and Devanagari digits (U+0966 to U+096F); normalize Devanagari digits to ASCII before matching against the record; exclude only line-leading enumeration markers matching ^\s*[0-9]+[.)]\s. Numbers written as words are not caught: record that as a residual in H-47.
 - Token parity gate (G-6): golden prompts over 2048 server tokens are expected refusals on both engines and are scored as a refusal-parity check, not a token-id failure. Every other golden prompt needs identical ids, 100 percent.
F-1 Facility scope (section 7): the INFERRED "no cross-facility data on the device" needs proof. Cite the code that scopes local case data to the signed-in worker's facility, or list it under YOUR CALL with what happens when a worker from another facility signs in on the same phone.
H-49 Update to the T1 result: HTML tag pieces and <unusedN> only; whitespace removed.

### Batch 6 (gate review of M3)

Y-21 Option 3 in this PR: the readback seam (both engines) refuses, fail closed, when the signed-in session's facility differs from the facility recorded as owning the local data, or when either is unknown. The implementation lands in S4 (Opus). The product-wide fix (local clinical data visible across facilities on a shared phone, on every screen) is a separate HIGH PR with its own hazard row, leaning to: wipe local clinical data on a different-facility sign-in only after the outbox has fully drained, and block that sign-in, never wipe, while it has not.
S1-L Accepted: dev resolves kotlin-stdlib 2.4.0, kotlin-reflect 2.4.0 and gson 2.14.0 while prod resolves 2.3.21, absent and 2.10.1. Prod stays tested on its own classpath at every hash. Filed: align gson and Kotlin deliberately, each in its own PR, before LiteRT-LM leaves dev.
R-S2 Re-slice: S2 implements the full engine including the conversation configuration of sections 5.1 to 5.5 (generate() cannot exist without it), with JVM tests. S3 becomes device-side verification only (byte-compare against the G-6 server renderings, the trim test, the over-length tests at about 2100 and over 2560 tokens), run once the G-6 files exist.

### Batch 7 (S2 follow-ups, 2026-10-16)

Y-16' The default backend order is decided from S5 time-to-answer data, including init, over the golden prompts. Measured so far: GPU cold init 30,640 ms, warm 7,663 ms; CPU init 2,355 ms; (b) total GPU 1,671 ms vs CPU 1,882 ms.
C-2 Derived runtime cache integrity (extends H-48): the engine cacheDir is cacheDir/slm/<artifact sha256>/ (dedicated, created by us). At every clean release (engine closed normally) write a manifest of every file in that directory (relative path, size, sha256) to filesDir/slm/<artifact sha256>.cache-manifest.json, atomically (temp file then rename). At every engine start, before initialize: if a manifest exists, verify every listed file and that no unlisted file exists; on any mismatch delete the cache directory and the manifest, then cold-init. If the cache directory is non-empty and no manifest exists, delete the directory and cold-init. Measure the verify time per start; if it exceeds 3,000 ms, report it under YOUR CALL. Threat model stated in the memo: corruption (crash mid-write, storage faults), not an attacker with app-private write access.
C-3 enableSpeculativeDecoding pinned explicitly to false (never inherit). automaticToolCalling = false approved. The desktop golden run (S5) pins the same.
C-4 Below API 29 the local engine refuses to start: UNAVAILABLE, reason thermal_unavailable.
C-5 Memo 9.1 gains: adb shell "run-as com.example.samdapp.dev chmod 600 files/slm/<sha>.litertlm" after the copy.
C-6 The equivalent mutant "served id from the constant" is documented as equivalent (the digest gate makes the two behaviours identical); no row.
C-7 The GPU init failure reaches the audit as the boolean gpu_init_failed in the SLM_READBACK_INVOKED payload, never error text.

### Ruling Y-21' (gate review at S4, 2026-10-16)

Ruling Y-21' (supersedes the Step 3 storage design; record it verbatim in memo section 16 and update 7.1 and 14):
No schema change, no migration, and no change to the sign-in path in this PR. Implement the Y-21 check in the seam against a small interface, LocalDataOwnerFacility, returning the owning facility id or UNKNOWN. The only implementation in this PR always returns UNKNOWN, so the readback seam refuses every request (fail closed, INPUT_REFUSED reason facility_scope, existing refusal copy, no new strings). This is acceptable because SLM_READBACK_ENABLED is false in every flavor and the parity harness calls LocalSlmEngine directly, not the seam.
Keep the session-facility lookup you found (cite it). Implement the comparison logic fully: same facility allowed; different facility refused; either side unknown refused; compare by facility id, never by name. Test it with a fake LocalDataOwnerFacility, so the logic is ready for the real store.
Mutation rows: allow when the owner is UNKNOWN; compare by name instead of id; skip the check for the physician tier.
The marker store (where it lives, the migration, when the claim runs, and the wipe-after-drain policy) moves to the product-wide facility-scope PR (HIGH, filed), which needs its own Opus memo. Record a dependency in memo section 15: the later "enable readback UI in dev" ruling cannot be taken until that PR provides a real LocalDataOwnerFacility.
