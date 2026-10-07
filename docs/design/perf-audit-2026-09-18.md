# SaMD App performance and stability audit

Date: 2026-09-18
Scope run so far: Phase 0 (ground truth), Phase 1 (cold start), Phase 2A to 2D, Phase 3,
Phase 6A to 6D (ASR residency mechanism, sync and WorkManager, backend, sweep close-out).
Phases 4 (Compose recomposition) and 5 (build and packaging) are DEFERRED by instruction, not skipped.

Read-only audit. No source file was modified. No commit, stage, branch, stash or clean was performed.
The four intentional uncommitted local changes (`.idea/deploymentTargetSelector.xml`, `backend/README.md`,
`backend/docker-compose.yml`, `tools/dev-connect.sh`) were left exactly as they were and verified unchanged
at the end of the run.

No `.env`, `.env.*`, `local.properties`, keystore or credential file was opened or printed at any point.
Backend database credentials were used only via the container's own environment
(`psql -U "$POSTGRES_USER"`), never read out or echoed.

---

## Section 1: Method and what was actually run

### Environment

| Item | Value | How known |
| --- | --- | --- |
| Repo | `/media/sandesh/extra-ssd/AndroidWork/SaMDApp` | MEASURED |
| Branch / HEAD | `master` / `a219da9` | MEASURED, `git rev-parse` |
| Physical device, Phases 0 to 3 | **NONE attached.** `adb devices` listed only `emulator-5554`. `lsusb` showed no Android handset on any bus. `adb kill-server`/`start-server` retried, no change. | MEASURED |
| Physical device, Phase 6 | **ATTACHED over USB.** `10BE3A09C700046`, `I2302T` / `I2302` (iQOO), ABI **arm64-v8a**, Android **16** / API **36**, `MemTotal 7,597,400 kB`. Wireless debugging was not needed; the handset appeared on USB. | MEASURED |
| Device used | `emulator-5554`, `sdk_gphone16k_x86_64`, ABI **x86_64**, API 37 / Android 17, `MemTotal 4,006,168 kB` | MEASURED, `getprop` + `/proc/meminfo` |
| Build variant measured | **devDebug**, `app-dev-debug.apk`, 759,513,916 bytes, built 2026-09-18 11:02 (pre-existing, not rebuilt by this audit) | MEASURED |
| Backend | `docker compose up -d` from `backend/`. `backend-api-1` and `backend-db-1` healthy. `/health` returns `{"status":"ok","database":"ok","abdm_mode":"live"}` | MEASURED |
| Session | Signed in as `sandesh` (ASHA_WORKER, `worker_id 2fc142e567799842`). Credentials supplied by the repo owner. | MEASURED |

### The x86_64 emulator ceiling, and what the ARM anchor did to it

Phases 0 to 3 ran entirely on an x86_64 emulator backed by a desktop CPU and host page cache.
**Those timings are a lower bound on a real ARM PHC tablet, never a prediction of one.** Storage is
host-backed, so unlink and page-fault costs are far below eMMC/UFS. CPU-bound work (PBKDF2, dex
verification, Compose composition) runs on desktop cores.

**In Phase 6 the iQOO I2302 attached and one anchor was re-taken on real ARM.** The result confirms
the ceiling was real and quantifies it:

| cold start, `run-from-apk`, 5 runs, force-stop between | TotalTime (ms) | median |
| --- | --- | --- |
| emulator, x86_64, API 37 | 839, 939, 1025, 919, 762 | **919** |
| **iQOO I2302, arm64-v8a, API 36** | **2158, 1478, 1444, 1244, 1401** | **1444** |

WaitTime on the handset: 2173, 1483, 1448, 1247, 1405. `dumpsys package dexopt` confirmed
`arm64: [status=run-from-apk] [reason=unknown]` with `[location is error]` before the sequence, so this
is the same state as the emulator run.

**Real ARM is about 1.57x the emulator on this path, and the first launch after install is 2158 ms.**
Every other emulator timing in this report should be read with that multiplier in mind as a rough
guide, not applied arithmetically, since different work scales differently.

**Two cautions on the handset, stated because they matter for the LMK argument.**
The iQOO I2302 has `MemTotal 7,597,400 kB`, roughly **7.6 GB, not the 4 GB PHC tablet target**. It
also runs Android 16 / API 36 against the emulator's API 37. It is a good ARM timing anchor and a
**poor** memory-pressure anchor.

**One class of number transfers regardless: memory residency.** Phase 6A establishes by direct page
measurement that the ASR figure is a fixed-size artifact materialised into the native allocator. Its
size is set by the `.onnx` weights, not by ABI, CPU or storage.

`Graphics:` reports `0 KB` in every `dumpsys meminfo` on the emulator. That is an emulator GL
artifact, not a real zero. Graphics PSS is NOT MEASURED anywhere in this report.

### Commands actually run

- `git rev-parse --abbrev-ref HEAD`, `git status --short`, `git log --oneline -15`
- `adb devices -l`, `lsusb`, `adb kill-server && adb start-server`
- `adb install -r -t app/build/outputs/apk/dev/debug/app-dev-debug.apk`
- `adb shell am force-stop <pkg>` then `adb shell am start -W -S -n <pkg>/com.example.samdapp.MainActivity`, five-run sequences, in three dexopt states and in signed-out and signed-in states
- `adb shell cmd package compile -m speed -f <pkg>` (ART downgraded it to `verify`, the app is debuggable)
- `adb shell dumpsys package dexopt`
- `adb shell atrace --async_start/--async_stop` with categories `am wm view dalvik sched binder_driver gfx res` and, for the final capture, `bionic database aidl` added. Five captures analysed with purpose-written Python parsers in the session scratchpad (`parse.py`, `win.py`, `find.py`, `sched.py`).
- `adb shell dumpsys meminfo <pkg>` at four reachable points, `adb shell dumpsys activity oom`
- `adb shell uiautomator dump` plus `adb shell input tap/swipe` to drive the app from Home through patient registration, medical background, compounder assessment and into the consultation screen, then to trigger one voice capture on the impact-on-daily-activities field
- `adb shell run-as <pkg>` to seed and verify orphan sweep directories
- `unzip -l`/`-v`/`-p` and `strings` on the built APK and on `lib/arm64-v8a/libsqlcipher.so`
- A purpose-written dex `class_defs` parser (`dexstat.py`) over all 21 dex entries, because `apkanalyzer` is absent
- `docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" ...'` for backend account inspection
- `graphify query` for orientation before grepping, per project CLAUDE.md

### What could not be run

- `apkanalyzer`: absent. No Android SDK `build-tools`, no `d8`, no `dexdump`, no bundled Studio copy on this machine. Replaced with a direct dex parser, not an estimate. See F2C-01.
- Perfetto host tooling (`trace_processor_shell`, `traceconv`): absent. `perfetto` and `atrace` exist on device; `atrace` ftrace text output was used because it is parseable without host tooling.
- Provisioning a fresh backend worker account: denied by the harness auto-mode classifier as a shared-resource write. Worked around by the repo owner supplying an existing account's PIN.
- **ARM anchors (ii) and (iii), the pre/post-voice `dumpsys meminfo` and the backgrounded `dumpsys activity oom`.** The handset is credential-locked (`dumpsys trust` reports `deviceLocked=1`, `trustState=UNTRUSTED`). Reaching a signed-in session and driving a voice capture needs the screen unlocked. I did not attempt to bypass the lock. Anchor (i) needed no unlock and was taken.

### Phase 6 commands added

- `adb -s 10BE3A09C700046 install -r -t ...` then the five-run `am start -W -S` sequence on the handset
- `adb shell run-as <pkg> cat /proc/<pid>/smaps_rollup` and `/proc/<pid>/smaps`, parsed per-mapping by `smaps.py`
- `javap -p -c` over the vendored `sherpa-onnx-1.13.7.aar` `classes.jar`
- `nm -D --undefined-only` and `strings` over `lib/arm64-v8a/libsherpa-onnx-jni.so` and `libonnxruntime.so`
- `curl` timing loop, 10 sequential `POST /v1/assess` against the running classifier on port 8000
- `docker stats --no-stream`, `docker compose exec -T db ... psql` for indexes, row counts and `EXPLAIN (ANALYZE, BUFFERS)`

---

## Section 2: Findings table

| id | area | sev | M/I | anchor | statement |
| --- | --- | --- | --- | --- | --- |
| F2D-01 | architecture / regulatory | **P0** | MEASURED | `GeminiBrandLookupSource.kt:33,40`; `GenerateEvaluateReportUseCase.kt:65`; `app/build.gradle.kts:74` | A generative LLM call sits on the clinical path, ships in prod, and its unverified output is rendered on the prescription. No hazard id. |
| F6A-01 | memory | **P0** | MEASURED | `/proc/pid/smaps`: `[anon:scudo:secondary]` Rss 692,840 kB, Private_Dirty 692,380 kB, Private_Clean 0 | The 692 MB of weights are anonymous private dirty pages. The kernel cannot reclaim them. Only process death can. |
| F2B-01 | memory | **P0** | MEASURED | `dumpsys meminfo`: 72,528 KB before, 819,411 KB after one voice capture | One voice capture takes the process from 71 MB to 800 MB PSS and nothing ever gives it back |
| F2B-02 | memory | **P0** | MEASURED | `dumpsys activity oom`: `cur=LAST set=LAST`, `curRaw=700`, `lastRss=0.94GB` | Backgrounded at 796 MB PSS in oom_adj bucket 700, the most attractive LMK target on the device |
| F6B-01 | sync | **P0** | MEASURED | `PatientDao.kt:18` (x20 DAOs); `domain/sync/SyncStatus.kt` `failedCount`; zero hits in `presentation/` | A FAILED sync row is never re-collected and never shown. Both anchors are absences, and both are deliberate. |
| F6B-02 | sync / clinical | **P0** | MEASURED | `GenerateKernelReportUseCase.kt:250-255` | The blanket `catch (e: Exception)` is the hop where a data-integrity failure becomes "the ML server is unavailable" |
| F6C-01 | backend / regulatory | **P1** | MEASURED | `/health` and `models/model_meta.json`: `toy-v0.6-observed-glucose-4tier` vs four different documented strings | The model version actually served matches nothing in the risk file, PROGRESS.md or the API contract |
| F6C-02 | backend / data | **P1** | MEASURED | `\d evaluate_reports`: `ix_evaluate_reports_case_record_id btree` (not unique); 5 duplicated case_record_ids in live data | The device enforces one report per case; the backend does not, and already holds duplicates |
| F6D-01 | H-18 / PHI | **P1** | MEASURED | `SaMDApplication.kt:30,36` are the only production call sites; every return value discarded | The PHI sweep has exactly one trigger and reports nothing on failure |
| F2A-01 | database | **P0** | MEASURED | atrace: 7 blocks on `SQLiteConnectionPool.waitForConnection(SQLiteConnectionPool.java:609)`, 314 to 650 ms each, chained 726 ms to 1691 ms | Four Room IO threads serialise on the SQLCipher connection pool for roughly one second after every launch |
| F1-01 | startup | P0 (conditional) | MEASURED | emulator 762 to 1025 ms vs 255 to 356 ms; **iQOO I2302 1244 to 2158 ms** | Cold start is 3.4x slower until ART dexopt lands, see correction C1 |
| F6A-02 | memory | P1 | MEASURED | `javap -c`: constructor branches `ifnull` to `newFromFile`; `Java_..._OfflineRecognizer_newFromFile` exported in the shipped `.so` | A filesystem-path load route exists in the vendored 1.13.7 and is not used |
| F6B-03 | network | P2 | MEASURED | four separate `OkHttpClient.Builder()` sites; zero `callTimeout`; zero explicit `ConnectionPool` | Four independent OkHttp clients, four connection pools, four dispatchers, no call timeout anywhere |
| F6C-03 | backend | P2 | MEASURED | `SaMDClassifier/src/app.py:32-39` at module scope; 10 calls p50 11.97 ms, first call 25.3 ms | The model loads at import, not per request. Not a P0. Confirmed by measurement, not just by reading. |
| F6C-04 | backend | P2 | MEASURED | `docker stats`: `samd-classifier 2.048GiB`; `Dockerfile:42` has no `--workers` | 2.05 GB resident for a 4.7 MB model, single worker. Any `--workers N` multiplies it. |
| F6C-05 | backend / clinical | P2 | MEASURED | `app.py:71` emits `"calibrated": True`; `model_meta.json` has `calibration_used_for_evaluation: False` | The assess response asserts calibration that the model metadata says was not used |
| F6B-04 | sync | P3 | MEASURED | `AssessmentRunner.kt:70-84` | The push-before-assess ordering fix is present and correct, and deliberately does not cover the data-failure case |
| F1-09 | provenance | **P1** | MEASURED | `data/local/security/test.kt`, `data/local/security/build.gradle.kts` | Scratch file in the default package ships in the APK; a second SQLCipher coordinate sits in the source tree |
| F2C-01 | dex size | **P1** | MEASURED | dex parse: 11,400 classes, 23,529 methods, 12,401,752 code bytes | `material-icons-extended` is 49.19% of all executable dex code, to supply 2 icons |
| F1-02 | startup | P1 | MEASURED | atrace: `bindApplication` 512.59 ms, `OpenDexFilesFromOat` 457.29 ms | Dex extract plus verify is 44% of an unoptimised cold start |
| F1-04 | startup | P1 | MEASURED | atrace: `traversal` 230.21 ms cold, 130.51 ms warm; no baseline profile in repo | First frame stays above 130 ms with no Baseline Profile to pre-compile Compose |
| F2B-03 | memory | P1 | MEASURED | grep `onTrimMemory`: zero implementations in `app/src` | Nothing in the app responds to memory pressure, anywhere |
| F3-01 | concurrency | P1 | MEASURED | `AppNavHost.kt:78,108,113,120` plus atrace at 294 to 305 ms | Four `hiltViewModel()` calls in first composition force the whole SQLCipher, OkHttp and Retrofit graph onto the main thread |
| F3-02 | tooling | P1 | MEASURED | grep `StrictMode`: no hit in `app/src/main`, `app/src/dev` | StrictMode is never installed in any production source set |
| F2B-04 | memory | P1 | INFERRED | `AndroidDocumentCaptureStore.kt` `writePdf`, `document.writeTo(sink)` after the loop | Every finished PDF page stays resident until assembly ends, about 77 MB for 10 pages |
| F1-10 | packaging | P2 | MEASURED | APK: `lib/x86_64/libonnxruntime.so` 25,000,416 B and siblings | About 37 MB of x86_64 native code ships in staging and prod for an emulator test path |
| F1-05 | startup | P2 | MEASURED | atrace offset 907.98 ms, `VerifyClass okhttp3...ConscryptSocketAdapter` inside `traversal` | The HTTP stack is constructed on the main thread inside the first frame |
| F1-06 | startup / H-18 | P2 | MEASURED negative | `SaMDApplication.kt:30,36`; seeded 3000 orphan inodes, no signal above noise | Startup sweeps are unbounded main-thread filesystem IO; cost not resolvable on emulator storage |
| F1-07 | database | P2 | MEASURED | atrace: dlopen 294.46 ms MAIN, keystore2 300.43 ms MAIN, pool open 350.60 ms `arch_disk_io_0` | SQLCipher setup is on the main thread; the KDF itself is not, and is not on the first-frame path |
| F2A-02 | database | P2 | MEASURED | grep: no `kdf_iter`, `cipher_page_size` or `SQLiteDatabaseHook` anywhere | SQLCipher runs entirely on 4.x defaults, nothing tuned, two PBKDF2 derivations per connection |
| F2B-05 | memory | P2 | MEASURED | `SherpaOnnxTranscriptionService.kt:117,175-178` and its own KDoc at :173 | The recognizer is built once and never released, by design, with the fix named in a comment |
| F3-03 | startup | P2 | MEASURED | atrace: `broadcastReceiveComp: android.intent.action.BOOT_COMPLETED` 14.78 ms on MAIN, building `WorkDatabase` | WorkManager builds its own Room database on the main thread during launch |
| F1-08 | startup | P3 | MEASURED | merged manifest lines 108-121; `files/profileInstalled` on device | Three androidx.startup initializers remain, including ProfileInstaller with no profile to install |
| F3-04 | concurrency | P3 | MEASURED | `ConsultationDocumentRepositoryImpl.kt:310-315` | `readDecrypted` is a suspend function doing Cipher plus file IO with no `withContext` |
| F3-05 | concurrency | P3 | MEASURED | `BearerInterceptor.kt:32`, `TokenAuthenticator.kt:49` | Two documented `runBlocking` calls, both on OkHttp threads, both reading DataStore |

---

## Section 3: Per-finding detail

### F2D-01 (P0, MEASURED) A generative LLM sits on the clinical path

**Flagging this loudly, as instructed.** This is a design finding, not a performance one.

Three answers, without printing any value and without opening any credential or properties file:

1. **Is `BuildConfig.GEMINI_API_KEY` referenced in `app/src`?** **Yes, two sites:**
   - `app/src/main/java/com/example/samdapp/data/remote/GeminiBrandLookupSource.kt:33` (`if (BuildConfig.GEMINI_API_KEY.isBlank()) return null`)
   - `app/src/main/java/com/example/samdapp/data/remote/GeminiBrandLookupSource.kt:40` (`apiKey = BuildConfig.GEMINI_API_KEY`)

2. **Where does the build source the value?** `app/build.gradle.kts:74`, from a **properties file read through `providers.fileContents`**, with `""` as the default when the key is absent. Mechanism only; no value read, no file opened.

3. **Is it in `defaultConfig`?** **Yes.** Line 74 sits inside the `defaultConfig { }` block that runs from line 56 to line 76. It therefore compiles into **dev, staging and prod alike**. The three `productFlavors` blocks (lines 79 onward) do not override or remove it.

**Why this is not just a stray field.** The call is on the clinical path, not a side feature:

- `GenerateEvaluateReportUseCase.kt:65` calls `brandLookupSource.lookupTopIndianBrand(result.nlemTreatment.recommendedDrug)`
- the result is stored as `EvaluateReportOutput.topIndianBrand`
- `EvaluateReportOutput.kt:20` states it is displayed next to the recommended drug
- `GeminiBrandLookupSource.kt`'s own KDoc says it is "for display next to `EvaluateNlemTreatment.recommendedDrug` **on the prescription**"

So a Gemini generation produces a brand name and manufacturer that a PHC worker sees on a prescription surface. The prompt carries only the generic drug name, not PHI, and traffic goes to `https://generativelanguage.googleapis.com/` (`GeminiNetworkModule.kt:41`), a third party, not `backend/core`.

**The containment is a build-time switch, not a structural absence.** `GeminiBrandLookupSource.kt:33` returns null on a blank key, so the feature is inert without one. Contrast this with how the same codebase handles the platform-ASR ban: that one is enforced by physical source absence, `src/dev` containment, and two egress-proof scan tests (`NoPlatformRecognizerSourceScanTest`, `TranscriptionPathHasNoNetworkDependencyTest`) that `app/build.gradle.kts` goes out of its way to keep from being skipped by an up-to-date check. The Gemini path has none of that. Any build where the properties file carries a key, including a local prod release, calls out.

**Second, independent defect: the generated text reaches a clinical artifact unverified.**

This is separate from the egress question and would survive even if the call were moved on-device.
`GeminiBrandLookupSource.kt`'s entire validation of the model's output is:

```kotlin
val parts = rawText.split("|").map { it.trim() }
if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) { ... return null }
IndianBrandSuggestion(brandName = parts[0], companyName = parts[1])
```

A shape check, and nothing else. **There is no post-generation entity verification at all:** nothing
confirms the brand exists, nothing confirms the manufacturer exists, nothing cross-checks the pair
against NLEM or any formulary, nothing checks that the returned brand actually contains the generic
drug that was asked about. Whatever two non-blank strings come back are rendered on the prescription
as fact.

The architecture already knows this is required. The SLM track built exactly that guard:
`SlmStreamSanitizer` (`app/src/test/.../SlmStreamSanitizerTest.kt`, Stage 3a) exists specifically to
sit between generated text and what a worker sees. **The Gemini path has no equivalent and is not
routed through it.** A hallucinated brand and manufacturer, confidently formatted, on a prescription
handed to a patient at a PHC, is the failure mode, and nothing in the current code prevents it.

Marked MEASURED: the absence of any verifier on this path is read directly from the only validation
the source performs.

**What a fix would touch.** This is a design decision for the owner, not a mechanical change. The options are, in increasing strength: delete the field and the source; move `GeminiBrandLookupSource` into a flavor source set the way `PiGatewayVitalsSource` is; or add a scan test asserting the symbol is absent from staging and prod, matching the ASR precedent. If the feature is kept in any form, it additionally needs a post-generation entity verifier before render, not just a shape check. Whichever is chosen, it is a risk-file change, not a perf ticket.

**Risk file.** Not currently mapped to a named hazard id in `docs/quality/risk-management-file.md`. **It needs one.** Two distinct hazards are in play and neither is registered: unverified generative text on a clinical decision surface, and third-party egress from a build that is supposed to have none. The design history file does record the feature (`docs/quality/design-history-file.md:45`, "India-brand lookup via Gemini"), so it was a considered addition, which makes the missing hazard entry an omission rather than an oversight in scope.

### F2B-01 (P0, MEASURED) One voice capture moves the process from 71 MB to 800 MB

`dumpsys meminfo com.example.samdapp.dev`, same session, same process, all figures in KB:

| point | TOTAL PSS | Native Heap PSS | Dalvik Heap PSS | Graphics |
| --- | --- | --- | --- | --- |
| (a) fresh launch, signed out | NOT MEASURED, see Section 6 | | | |
| (b) signed in, Home | **56,022** | 12,505 | 6,976 | 0 (emulator artifact) |
| (c1) Consultation screen, pre-voice | **72,528** | 17,097 | 17,698 | 0 |
| (c2) after one voice capture | **819,411** | **744,441** | 10,474 | 0 |
| (d) after a 5-page document capture | NOT MEASURED, see Section 6 | | | |
| (e) backgrounded from (c2) | **814,431** | 739,109 | 15,814 | 0 |

**Delta from one voice capture: +746,883 KB PSS, about 730 MB, essentially all native heap.**
Native heap size grew to 795,904 KB with 772,931 KB allocated. `.apk mmap` PSS rose from 2,014 to
21,858 KB at the same time, which is the asset read.

**What it costs.** The process becomes the largest userspace consumer on a 4 GB device after the system
server, from a single tap on a mic button, and stays there for the rest of the process lifetime.

**How I know.** Measured directly, at both ends of a single user action, by driving the real UI:
Home to patient registration to medical background to compounder assessment to consultation, then one
tap on the control whose `content-desc` is `"Record impact on daily activities"`, then a second tap to
stop and transcribe.

**This is the one emulator number that transfers.** The allocation is the `int8` encoder plus ONNX
Runtime arenas. `assets/asr/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/encoder.int8.onnx` is
652,184,296 bytes (622 MiB) and is `Stored` in the APK, so it is the same size on arm64. The residency
is set by the model artifact, not by ABI or CPU.

**What a fix would touch.** `SherpaOnnxTranscriptionService.kt:117` (the `@Volatile` field) and
:175-178 (the double-checked build under `recognizerLock`). A release path plus an `onTrimMemory` hook.
The service's own KDoc at :173 already names this: "the upgrade is to release the recognizer on
`onTrimMemory` and pay a reload; that is not [done]".

**Risk file.** Releasing the recognizer changes ASR availability timing, not egress. The egress-proof
guarantees (Layer 1/2 scan tests, `TranscriptionPathHasNoNetworkDependencyTest`) are unaffected by when
the model is resident. This one is closer to a pure perf fix than most in this report, but it should
still be checked against the transcription hazard entries before it is scheduled.

### F2B-02 (P0, MEASURED) Backgrounded at 796 MB in oom_adj 700

`adb shell dumpsys activity oom`, immediately after backgrounding from the post-voice state:

```
Proc # 1: prev     b/ /LAST --------I  t: 0 2842:com.example.samdapp.dev/u0a275 (previous)
    oom: max=1001 curRaw=700 setRaw=700 cur=700 set=700
    state: cur=LAST set=LAST lastRss=0.94GB lastCachedRss=0.00
```

`mPreviousProcess: ProcessRecord{f07b3b 2842:com.example.samdapp.dev/u0a275}`

**What it costs.** oom_adj 700 is `PREVIOUS_APP_ADJ`. Android's low-memory killer works from the highest
adj downward. A process holding 0.94 GB RSS at adj 700 is the single most attractive kill target on the
device the moment anything else needs memory. Backgrounding released nothing: PSS went from 819,411 KB
to 814,431 KB, a 0.6% drop.

**This is the underlying cause the audit was asked to find.** PR #54 (in-process CameraX) and PR #57
(saveable nav back stack) fixed what the eviction *did* to the user. This is *why* the eviction happened:
the process was not cheap to evict, it was enormous, and enormous plus adj 700 means it dies first,
every time.

**The claim the fix sequence depends on, answered honestly.** The question was whether the process is
large enough post-voice that LMK eviction while backgrounded is expected on a 4 GB handset, and not
large enough pre-voice. On the evidence here: post-voice the process is 800 MB on a device with
`MemTotal 4,006,168 kB`, at adj 700, releasing nothing on background. Pre-voice it is 72 MB, which is
unremarkable. **The shape of the answer is measured. The eviction event itself is NOT MEASURED**, because
this is an emulator with no real memory pressure and no physical 4 GB handset was available. I am not
asserting the kill; I am asserting the size, the adj bucket and the absence of any release path, all
three measured, and noting that those are the three inputs LMK uses.

**What a fix would touch.** Same as F2B-01, plus F2B-03.

### F2A-01 (P0, MEASURED) SQLCipher connection pool serialises four Room IO threads for about a second

From the atrace capture with `dalvik` and `database` categories, app pid 2779, offsets relative to the
first app slice:

```
 725.90ms dur=649.95ms [arch_disk_io_1] monitor contention with owner arch_disk_io_3
 725.93ms dur=331.46ms [arch_disk_io_2] monitor contention with owner arch_disk_io_3
 726.60ms dur=330.83ms [arch_disk_io_0] monitor contention with owner arch_disk_io_3
1057.44ms dur=318.50ms [arch_disk_io_2] monitor contention with owner arch_disk_io_0
1057.50ms dur=318.48ms [arch_disk_io_3] monitor contention with owner arch_disk_io_0
1376.36ms dur=314.27ms [arch_disk_io_1] monitor contention with owner arch_disk_io_2
1376.39ms dur=314.33ms [arch_disk_io_3] monitor contention with owner arch_disk_io_2
```

The full contention strings name the exact monitor:

```
monitor contention with owner arch_disk_io_0 (2815) at
  net.zetetic.database.sqlcipher.SQLiteConnection
  net.zetetic.database.sqlcipher.SQLiteConnectionPool.waitForConnection(
    java.lang.String, int, android.os.CancellationSignal)(SQLiteConnectionPool.java:609)
  waiters=2 blocking from ...SQLiteConnectionPool.releaseConnection(...)(SQLiteConnectionPool.java:375)
```

and the first holder is in native prepare:

```
monitor contention with owner arch_disk_io_3 (2818) at
  long net.zetetic.database.sqlcipher.SQLiteConnection.nativePrepareStatement(long, java.lang.String)
```

**What it costs.** Four threads, waiters up to 2 deep, chained at 726 ms, 1057 ms, 1376 ms and 1691 ms.
Roughly **one full second of serialised database access immediately after every launch**, on x86_64.
Everything on Home that reads the database waits behind it.

**How I know.** MEASURED, from ART's own monitor-contention instrumentation in the trace, with file and
line numbers from the SQLCipher source. Not inferred.

**What drives the fan-out.** `HomeViewModel.init` (`HomeViewModel.kt:52-83`) starts **three** concurrent
`viewModelScope.launch` blocks, each collecting a separate Room Flow: `getTodaysPatientsUseCase()`,
`syncStatus.state`, and a chain of `observeResumableDraftForUser` into `observePatient`. Add
`NavRestoreViewModel`, `ConnectivityViewModel` and `SyncStatusImpl`'s own collectors and the demand
exceeds what the pool hands out.

**The 314 to 330 ms quantum.** MEASURED as a fact. Its attribution to key derivation is **INFERRED**:
each new pooled connection must run the SQLCipher KDF, the quantum is stable across seven independent
blocks, and nothing else in an open path has that shape. I did not isolate it.

**What a fix would touch.** `DatabaseModule.kt:92-103`. Candidates, in order of laziness: raise or pin
the SQLCipher pool size so connections are not repeatedly opened; hold the derived key rather than the
passphrase so per-connection KDF collapses (SQLCipher supports a raw-key form that skips PBKDF2
entirely); reduce the Home query fan-out. The first two change how the database key is handled.

**Risk file.** The raw-key option in particular changes the key handling that
`DatabasePassphraseProvider` exists to protect. That is a security-control change, not a perf knob, and
must be argued against the database-encryption control in `docs/quality/risk-management-file.md` before
it is written.

### F2A-02 (P2, MEASURED) SQLCipher runs on stock 4.x defaults

Searched the whole app source for `kdf_iter`, `cipher_page_size`, `cipher_memory_security`,
`cipher_default_*`, `PRAGMA` and `SQLiteDatabaseHook`.

- **Zero customisation.** `DatabaseModule.kt:97` is `SupportOpenHelperFactory(passphrase)` with no hook and no PRAGMA.
- The only `PRAGMA` calls in the app are in the one-time plaintext-to-encrypted migration path: `DatabasePassphraseProvider.kt:74-76` (`ATTACH` / `sqlcipher_export` / `DETACH`) and `:88` (`PRAGMA integrity_check`). None configures the cipher.
- The seven `SupportOpenHelperFactory` uses in `androidTest` mirror production exactly.

So the 4.x defaults apply.

**What the shipped artifact actually contains.** MEASURED, `strings` over
`lib/arm64-v8a/libsqlcipher.so` from the built APK:

```
cipher_default_kdf_algorithm      PBKDF2_HMAC_SHA1
cipher_default_kdf_iter           PBKDF2_HMAC_SHA256
cipher_page_size                  PBKDF2_HMAC_SHA512
kdf_iter                          HMAC_SHA512
%s: deriving key using PBKDF2 with %d iterations
%s: deriving hmac key from encryption key using PBKDF2 with %d iterations
```

**MEASURED from the artifact:** the library derives **two** keys per connection by PBKDF2, the cipher
key and a separate HMAC key, and exposes `kdf_iter` / `cipher_default_kdf_iter` as tunable PRAGMAs that
this app never sets.

**INFERRED, not measured:** the default iteration count. SQLCipher 4 ships 256,000 PBKDF2-HMAC-SHA512
iterations. That number is a compiled-in integer constant, not a string, so `strings` cannot recover it
and I did not read it out of the artifact. I am quoting general knowledge and marking it as such. To
measure it, `PRAGMA kdf_iter` would have to be run against the open database, which needs the
passphrase.

### F1-07, now closed (P2, MEASURED) The SQLCipher open path

Answering the four sub-questions from the trace, not from reading code. App pid 2779, offsets relative
to first app slice. First frame completed at about 308 ms.

**(a) Does `dlopen` of `libsqlcipher.so` appear on the main thread?** **Yes.**

```
294.46ms dur=1.24ms [MAIN] dlopen: /data/app/.../com.example.samdapp.dev-.../base.apk!/lib/x86_64/libsqlcipher.so
```

1.24 ms on this emulator. The library is `Stored` in the APK (2,231,416 B x86_64, 2,100,040 B arm64), so
it is mmap'd rather than inflated.

**(b) Does a binder transaction to keystore2 appear on the main thread?** **Yes.**

```
300.43ms [MAIN] BinderCacheWithInvalidation::updateCache : android.system.keystore2.IKeystoreService/default
```

That is `DatabasePassphraseProvider.getOrCreatePassphrase()` reaching the Keystore service, on the main
thread, four milliseconds after the dlopen. It is followed at 304.66 ms by
`Lnet/zetetic/database/sqlcipher/SupportOpenHelperFactory;` and at 305.98 ms by
`Landroidx/room/RoomConnectionManager$SupportOpenHelperCallback;`, both MAIN.

**(c) Where does the first actual database open land?** **On `arch_disk_io_0`, not the main thread**, at
350.60 ms:

```
350.60ms [arch_disk_io_0] Lnet/zetetic/database/sqlcipher/SQLiteConnectionPool;
350.68ms [arch_disk_io_0] Lnet/zetetic/database/sqlcipher/SQLiteConnection$OperationLog;
350.74ms [arch_disk_io_0] Lnet/zetetic/database/sqlcipher/SQLiteConnection$PreparedStatementCache;
353.42ms [arch_disk_io_0] Lnet/zetetic/database/sqlcipher/SQLiteConnection$PreparedStatement;
```

`Room.databaseBuilder(...).build()` does not open the file, so the open, the KDF and the page-1 decrypt
are deferred to the first DAO call, which Room dispatches to its own IO executor.

**(d) Main-thread delta, signed-in versus signed-out.**

| | signed out | signed in |
| --- | --- | --- |
| `am start -W -S` TotalTime, 5 runs | 255, 264, 274, 356, 356 | 287, 290, 299, 314, 319 |
| median | 274 ms | 299 ms |
| `bindApplication` | 42.81 ms | 40.27 ms |
| first frame `traversal` | 130.51 ms | 107.38 ms |

**About 25 ms of median delta, and the first frame is if anything faster.** The signed-in path composes
`MainNavHost` instead of `LoginScreen`, which is not obviously heavier.

**The yes/no with a number, as asked.** **No.** The KDF cost is not on the critical path to first frame
after sign-in. First frame completes at about 308 ms; the SQLCipher factory is not even constructed on
the main thread until 304.66 ms, and the pooled open does not start until 350.60 ms, on
`arch_disk_io_0`. The cost lands **after** the frame, and it lands off-thread. What it does instead is
F2A-01: it lands as one second of contended, serialised database access that the user experiences as a
slow, late-populating Home screen rather than as a slow launch.

**Correction to my own Phase 1 wording.** F1-07 was filed as "items 1 to 3 run on the main thread during
first composition after sign-in". Items 1 and 2 (the dlopen and the Keystore round trip) are confirmed
on the main thread. Item 3, the expensive part, is not, and my Phase 1 note implied a first-frame cost
that the measurement does not support. The real cost is bigger than I guessed, but it is in a different
place.

### F2B-03 (P1, MEASURED) Nothing responds to memory pressure

Grep for `onTrimMemory`, `onLowMemory`, `ComponentCallbacks2` and `registerComponentCallbacks` across
all of `app/src`:

**One hit, and it is a comment.** `SherpaOnnxTranscriptionService.kt:173`, inside a KDoc, saying the
upgrade would be to release on `onTrimMemory`.

**Zero implementations.** Not in `SaMDApplication`, not in `MainActivity`, not in any ViewModel, not in
any singleton. `SaMDApplication.onCreate` (`SaMDApplication.kt:25-37`) does the two sweeps and nothing
else. The app never learns that the system is under pressure, and would not act on it if it did.

**What it costs.** Combined with F2B-01 and F2B-02: the process holds 730 MB of releasable native
allocation, is told by the framework when memory is tight, ignores the message, and is killed.

**What a fix would touch.** `SaMDApplication` (implement `ComponentCallbacks2`), plus a release method on
`SherpaOnnxTranscriptionService`. Nothing else currently holds enough to be worth trimming.

### F2B-05 (P2, MEASURED) The recognizer is built once and never released

`SherpaOnnxTranscriptionService.kt`:

- `:115` `private val recognizerLock = Mutex()`
- `:117-118` `@Volatile private var recognizer: OfflineRecognizer? = null`
- `:175-178` double-checked lazy build under the mutex, on `Dispatchers.IO`
- `:180` `buildRecognizer()`

There is no `release()`, no `close()`, no path that nulls the field. The KDoc at :168 confirms the
design intent ("cannot build two recognizers, and never on the main thread") and :173 names the missing
upgrade. The concurrency and threading here are correct; the lifetime is the problem.

### F2B-04 (P1, INFERRED) PDF assembly holds every finished page until the end

`AndroidDocumentCaptureStore.kt`:

- `MAX_PAGES = 20` (`:337`)
- `PAGE_MAX_DIMENSION = 1600`, with the file's own comment putting one decoded page at "about 1600x1200x4 = 7.7 MB"
- `MAX_DOCUMENT_SIZE_BYTES = 20 MB` (`ConsultationDocumentRepositoryImpl.kt:53`)

`writePdf` creates one `PdfDocument()`, loops `drawPage(...)` over every page, and calls
`document.writeTo(sink)` **only after the loop finishes**. `drawPage` does recycle its own source bitmap
in a `finally`, but `document.finishPage(page)` has already recorded that page's content into the
`PdfDocument`, which retains it until `writeTo`.

**Peak simultaneous allocation, 10-page capture, INFERRED:**

- all 10 finished pages retained in the `PdfDocument`, about **77 MB**
- plus the current page's decrypted JPEG as a `ByteArray`, up to 20 MB by the document cap
- plus the `ByteArrayOutputStream` it was built in, transiently doubling that during `toByteArray()`
- plus the current decoded bitmap, about 7.7 MB

**The single line holding the largest buffer** is `drawPage`'s
`ByteArrayOutputStream().also { encryptionProvider.decryptToStream(encrypted, it) }.toByteArray()`,
which materialises the whole page plaintext twice and is bounded only by the 20 MB document cap.

At `MAX_PAGES = 20` the retained-page figure roughly doubles to about 154 MB. Landing that on top of a
post-voice 800 MB process is what turns F2B-01 from bad into fatal.

**Marked INFERRED.** Read from code. The 5-page capture measurement point was not reachable, see
Section 6.

**What a fix would touch.** `writePdf` and `drawPage`. Streaming each page out rather than accumulating,
or capping concurrent retention. This is the H-18 capture path, so any change to when plaintext exists in
memory is a risk-file change.

### F3-01 (P1, MEASURED) The eager first-composition dependency set

`AppNavHost.kt` makes exactly **four** `hiltViewModel()` calls during first composition:

| line | ViewModel | what it forces |
| --- | --- | --- |
| 78 | `AuthViewModel` | `AuthSession` to `BackendAuthSession` to `RetrofitAuthService` to `AuthApiService` to `Retrofit` to `OkHttpClient` (with `BearerInterceptor`, `TokenAuthenticator`, `HttpLoggingInterceptor`) plus `Gson`, plus `DataStoreAuthTokenStore` |
| 108 | `NavRestoreViewModel` | `AuditLogger` to `RoomAuditLogger` to `AuditLogDao` to **`AppDatabase`**, which runs `System.loadLibrary("sqlcipher")`, constructs `DatabasePassphraseProvider` (SharedPreferences read plus `KeyStore.load`) and calls `getOrCreatePassphrase()` (Keystore AES-GCM decrypt) |
| 113 | `ConnectivityViewModel` | `ConnectivityController` to `NetworkMonitor` to `AndroidNetworkMonitor` |
| 120 | `IdleLockViewModel` | nothing, `@Inject constructor()` is empty (`IdleLockViewModel.kt:24`) |

**Two of the four are expensive, and both are confirmed in the trace on the main thread:**
`VerifyClass okhttp3.internal.platform.android.ConscryptSocketAdapter` at 907.98 ms inside `traversal`
(F1-05), and the SQLCipher dlopen plus keystore2 round trip at 294 to 305 ms (F1-07).

`NavRestoreViewModel` is the one that hurts most for the least reason. Its whole job is
`logPendingDiscard()`, which `NavRestoreViewModel.kt:32` starts with
`val reason = NavStackRestoreReporter.consume() ?: return` and so does nothing at all on the overwhelmingly
common path. To decide not to write an audit row, first composition drags in the entire encrypted
database stack.

**What a fix would touch.** Constructor-inject `dagger.Lazy<AuditLogger>` or `Provider<AuditLogger>` in
`NavRestoreViewModel` so the graph is only realised when there is actually a discard to log. The same
shape applies to `AuthViewModel`'s HTTP stack, though that one is genuinely needed on the sign-in path.
**Two sites, one pattern.**

### F3-02 (P1, MEASURED) StrictMode is never installed

Grep for `StrictMode` across all source sets:

- `app/src/main`: no policy installation. Two incidental mentions in comments (`FeatureFlags.kt:94`, `AbhaDto.kt:9`, the latter being Pydantic `StrictModel`, unrelated).
- `app/src/dev`: nothing.
- `app/src/androidTest/.../AsrEgressTest.kt:126-158`: installs `detectNetwork().penaltyDeath()` and `detectUntaggedSockets().penaltyDeath()` **inside one test**, saves and restores the prior policy, and is scoped to proving the ASR path makes no network call.

So the codebase knows what StrictMode is and uses it precisely, in exactly one place, for a security
proof. It is not installed in any build a developer actually runs.

**What it costs.** Every finding in Phase 3 that is currently "correct by inspection" has no automated
guard. `readDecrypted` (F3-04) is one refactor away from running Cipher work on the main thread and
nothing would say so.

**What a fix would touch.** `SaMDApplication.onCreate`, guarded on `BuildConfig.ENVIRONMENT == "dev"`
or `BuildConfig.DEBUG`. Roughly ten lines. It is the cheapest permanent regression guard available here
and it would have caught F3-03 and F1-07 without a trace capture.

### F3-03 (P2, MEASURED) WorkManager builds its own Room database on the main thread at launch

From the signed-in trace, main thread:

```
 82.2ms  d0  14.78ms  broadcastReceiveComp: android.intent.action.BOOT_COMPLETED
 85.97ms     [MAIN] Landroidx/work/impl/WorkDatabase;
 88.33ms     [MAIN] Landroidx/work/impl/WorkDatabase_Impl;
```

**14.78 ms of main-thread time during `bindApplication`**, building WorkManager's own Room database
(plain framework SQLite, not SQLCipher). Also visible: a `WM.task-1` thread loading
`androidx/sqlite/SQLiteConnection` at 93.47 ms.

This is a consequence of the deliberate manifest choice to remove `WorkManagerInitializer` and provide
`Configuration.Provider` from `SaMDApplication` (`SaMDApplication.kt:20-23`, AndroidManifest comment).
That choice is correct and documented; the cost is a side effect worth knowing about, not a defect in
the choice.

### F3-04 (P3, MEASURED) An unguarded suspend function doing crypto

`ConsultationDocumentRepositoryImpl.kt:310-315`:

```kotlin
override suspend fun readDecrypted(documentId: String, output: OutputStream) {
    val entity = consultationDocumentDao.getById(documentId)
        ?: throw IllegalArgumentException("No document $documentId")
    val file = File(documentsDir(entity.consultationId), entity.storageKey)
    encryptionProvider.decryptToStream(file, output)
}
```

`DocumentEncryptionProvider.decryptToStream` (`:110-122`) is a plain non-suspend function doing
`CipherInputStream(fis, cipher).use { cis -> cis.copyTo(output) }`. `readDecrypted` is `suspend` with
**no `withContext`**, so it runs on whatever dispatcher the caller happens to be on.

**Not currently a live bug.** The only caller is `DocumentViewerViewModel.kt:141`, and it is already
inside `withContext(Dispatchers.IO)` (`:138`). The other encryption call sites are also correctly
placed: `AndroidDocumentCaptureStore.ingestPage` wraps in `withContext(Dispatchers.IO)` (`:113`), and
`assemble` wraps in `withContext(Dispatchers.Default)` (`:~160`).

**Why it is still a finding.** A `suspend` signature is a promise that the function is safe to call from
any dispatcher. This one is not; it silently depends on every caller doing the right thing, with no
StrictMode to catch the first one that does not. One `withContext(Dispatchers.IO)` inside
`readDecrypted` makes the signature honest.

**Minor, related:** `assemble` uses `Dispatchers.Default` for work that is file IO plus Cipher plus
bitmap decode. `Default` is sized to CPU count and is defensible for the bitmap and PDF work, but the
decrypt and file writes belong on `IO`. Not a defect, worth a note.

### F3-05 (P3, MEASURED) Two `runBlocking` calls, both deliberate, both off the main thread

- `BearerInterceptor.kt:32` `val accessToken = runBlocking { tokenStore.snapshot().accessToken }`
- `TokenAuthenticator.kt:49` `return runBlocking { ... }`

Both carry KDoc explaining the choice (`BearerInterceptor.kt:15`, `TokenAuthenticator.kt:46`). Both run
on OkHttp dispatcher threads, never the main thread, because OkHttp's `Interceptor` and `Authenticator`
interfaces are synchronous. That is the standard idiom and the right call.

The residual is that `tokenStore.snapshot()` reads a DataStore file, so a cold read blocks an OkHttp
thread on disk IO. Bounded and acceptable. Recorded so a future reader does not re-litigate it.

### Phase 3, the clean results

Worth stating explicitly, because these were the things most likely to be wrong and are not:

- **`Thread.sleep`**: zero hits in `app/src/main`.
- **`GlobalScope`**: zero hits in `app/src/main`.
- **Blocking DAO calls**: **zero.** Every DAO method in `app/src/main/.../data/local/dao/` is either `suspend` or returns `Flow`. Checked by exclusion: no method is both non-suspend and non-`Flow`-returning. No synchronous DAO call from any ViewModel `init` or composable exists to find.
- **Coroutines launched in composable bodies**: none that bypass the idiom. `DocumentCameraCapture.kt:156,160` use a `mainScope` captured for a CameraX `OnImageCapturedCallback`, which is a callback boundary, not a composition body. `CompounderViewModel.kt:613` uses a dedicated `teardownScope` inside a ViewModel. `rememberCoroutineScope` is used in exactly one file.
- **`assemblyJob` / `ingestJob` cancellation**: propagates correctly. `ConsultationViewModel.kt:1141,1146` hold the jobs; `:1281-1282` and `:1298-1301` cancel and null them. `AndroidDocumentCaptureStore.assemble` captures `coroutineContext` into `callerContext` before entering the non-suspend PDF lambda, and `writePdf` calls `callerContext.ensureActive()` once per page, so a cancel mid-assembly is honoured between pages rather than after all 20. `assemble` rethrows `CancellationException` after `dest.delete()` instead of swallowing it into a `Result`. `ingestPage` rethrows `CancellationException` before its generic catch. This is done properly.

### F2C-01 (P1, MEASURED) Exact dex composition

**`apkanalyzer` is absent on this machine**, along with the whole Android SDK `build-tools` directory,
`d8` and `dexdump`. No bundled Studio copy exists either. Rather than estimate, I wrote a dex parser
that walks the `header`, `string_ids`, `type_ids`, `class_defs`, `class_data_item` and `code_item`
structures of all 21 dex entries in the APK and attributes classes, defined methods and executable code
bytes per package.

Whole APK: **38,341 classes, 195,915 defined methods, 25,214,442 code bytes.**

| package | classes | methods | code bytes | % of code |
| --- | ---: | ---: | ---: | ---: |
| **androidx.compose.material.icons** | **11,400** | **23,529** | **12,401,752** | **49.19%** |
| (everything unclassified) | 5,106 | 30,250 | 2,136,174 | 8.47% |
| androidx.compose.material3 | 2,415 | 13,624 | 1,613,502 | 6.40% |
| androidx.compose.ui | 2,865 | 22,348 | 1,501,656 | 5.96% |
| **com.example.samdapp** | **3,293** | **16,924** | **1,462,982** | **5.80%** |
| androidx.compose.foundation | 3,291 | 18,724 | 1,392,106 | 5.52% |
| androidx.camera | 2,712 | 15,776 | 1,081,316 | 4.29% |
| kotlin | 1,069 | 10,480 | 743,298 | 2.95% |
| androidx.compose.runtime | 975 | 7,856 | 569,276 | 2.26% |
| androidx.datastore | 748 | 10,626 | 526,476 | 2.09% |
| kotlinx | 1,371 | 7,979 | 519,776 | 2.06% |
| androidx.compose.animation | 540 | 3,265 | 241,196 | 0.96% |
| okhttp3 | 327 | 2,818 | 223,728 | 0.89% |
| androidx.work | 525 | 2,657 | 196,632 | 0.78% |
| androidx.room | 427 | 2,161 | 143,006 | 0.57% |
| com.k2fsa | 123 | 1,912 | 128,378 | 0.51% |
| androidx.lifecycle | 329 | 1,326 | 91,018 | 0.36% |
| com.google.gson | 217 | 1,168 | 79,744 | 0.32% |
| net.zetetic | 65 | 760 | 50,274 | 0.20% |
| androidx.navigation3 | 133 | 563 | 48,276 | 0.19% |
| retrofit2 | 119 | 423 | 29,648 | 0.12% |
| androidx.compose.material | 46 | 268 | 18,100 | 0.07% |
| dagger | 245 | 478 | 16,128 | 0.06% |

**The icon library is 8.5x the app's own code and is half of everything ART has to verify.**

**What dropping `material-icons-extended` removes:** 11,400 classes, 23,529 defined methods, 12,401,752
code bytes, 49.19% of executable dex. Note `androidx.compose.material` (the `Icons` object itself plus
core) is only 46 classes and 18,100 bytes, so almost none of that 49% is load-bearing.

**Which of the 8 used icons need `extended`.** MEASURED by checking each against the
`material-icons-core` artifact in the Gradle cache:

| icon | in material-icons-core? |
| --- | --- |
| `Icons.Filled.DateRange` | yes |
| `Icons.Filled.Home` | yes |
| `Icons.Filled.List` | yes |
| `Icons.Filled.Lock` | yes |
| `Icons.Filled.Person` | yes |
| `Icons.Filled.Send` | yes |
| **`Icons.Default.Mic`** | **no, needs extended** |
| **`Icons.Filled.PhotoCamera`** | **no, needs extended** |

**Six of eight are already in core. Two are not.** Replacing `Mic` and `PhotoCamera` with two vector
drawables in `res/drawable` removes the dependency entirely.

**Caveat, stated plainly.** The dex surface only costs what F1-02 measured while the app is in
`run-from-apk` state. Once ART dexopt produces a vdex, verification is cached and the per-launch cost
collapses (F1-02: `OpenDexFilesFromOat` 457.29 ms to 4.37 ms). Dropping the icons library is worth doing
for APK size, build time, R8 scope and the sideload-deployment case, but it is **not** a 49% cold-start
win on a settled device.

### F6A-01 (P0, MEASURED) The weights are anonymous private dirty. Only process death reclaims them.

This was the question to settle before any fix is designed, and the arithmetic in the brief was right.

**How the weights are loaded.** `SherpaOnnxTranscriptionService.buildRecognizer()` constructs
`OfflineRecognizer(assetManager = context.assets, config = ...)` with **asset paths**, not filesystem
paths. The JNI side of the shipped `lib/arm64-v8a/libsherpa-onnx-jni.so` imports exactly
`AAssetManager_fromJava`, `AAssetManager_open`, `AAsset_getLength`, `AAsset_getBuffer` and
`AAsset_close` (`nm -D --undefined-only`). `AAsset_getBuffer` returns a pointer that is only valid
until `AAsset_close`, so the contents must be copied into a heap buffer before the asset is closed,
and that buffer is what reaches ONNX Runtime. **The model is handed to ORT as bytes, not as a path.**
Named function: `Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromAsset`, exported in the shipped
`.so`. MEASURED from the artifact.

**The page measurement.** `/proc/<pid>/smaps_rollup`, same process, before and after one voice capture,
all figures in kB:

| | pre-voice | post-voice | delta |
| --- | ---: | ---: | ---: |
| Rss | 219,164 | 962,460 | +743,296 |
| Pss | 97,607 | 842,735 | +745,128 |
| **Private_Dirty** | **42,188** | **763,696** | **+721,508** |
| Private_Clean | 37,480 | 56,368 | +18,888 |
| Pss_Anon | 42,540 | 764,762 | +722,222 |
| Pss_File | 44,594 | 67,241 | +22,647 |
| Anonymous | 62,284 | 783,708 | +721,424 |

**+721 MB of anonymous private dirty against +19 MB of private clean.** The answer is not ambiguous.

**Top 10 mappings by Rss, post-voice, from `/proc/<pid>/smaps`** (kB):

| mapping | Rss | Pss | Private_Dirty | Private_Clean | Shared_Clean |
| --- | ---: | ---: | ---: | ---: | ---: |
| **`[anon:scudo:secondary]`** | **692,840** | **692,406** | **692,380** | **0** | **0** |
| `[anon:scudo:primary]` | 45,788 | 45,171 | 45,140 | 0 | 0 |
| `.../oat/x86_64/base.vdex` | 34,980 | 34,980 | 0 | 34,980 | 0 |
| `/system/framework/framework.jar` | 33,076 | 3,203 | 0 | 52 | 33,024 |
| `.../com.example.samdapp.dev-.../base.apk` | 21,496 | 21,188 | 628 | 20,300 | 568 |
| `/memfd:jit-cache (deleted)` | 14,012 | 7,086 | 160 | 0 | 0 |
| `[anon:dalvik-/system/framework/boot-framework.art]` | 13,032 | 5,146 | 4,648 | 0 | 0 |
| `/apex/com.android.art/lib64/libart.so` | 8,788 | 756 | 4 | 76 | 8,568 |
| `[anon:dalvik-main space]` | 7,972 | 7,972 | 7,972 | 0 | 0 |
| `[anon_shmem:dalvik-jit-code-cache]` | 7,196 | 3,598 | 0 | 0 | 0 |
| TOTAL | 962,548 | 842,546 | 763,776 | 56,368 | 100,272 |

`[anon:scudo:secondary]` is Scudo's large-allocation path: allocations above its primary-allocator
size class are served by a direct anonymous `mmap`. **692,840 kB of it, 692,380 kB private dirty, zero
clean, zero shared.** That is ONNX Runtime's own copy of the weights, in the native allocator.

**The `base.apk` mapping is the control.** Only 21,496 kB resident, almost all private clean. If the
weights were being served file-backed out of the APK, this row would be ~650 MB of clean pages. It is
not. The APK is mmap'd briefly so `AAsset_getBuffer` can hand over a pointer, the bytes are copied
out, and the mapping is dropped back to nothing.

**The mechanism behind F2B-02, stated plainly.** Anonymous private dirty pages have no backing file.
The kernel cannot drop them under pressure, because there is nowhere to drop them to. The only
options are swap or death. The earlier `dumpsys meminfo` recorded `TOTAL SWAP PSS: 156 KB` against
763,696 kB of private dirty, and `Referenced: 908,292 kB` of `Rss 962,460 kB`, meaning the pages are
hot and will not be preferentially swapped by zram. **So the only available reclamation for the 692 MB
is killing the process.** That is not an accident of configuration. It is a direct consequence of
loading the model through a buffer rather than a path, and it is the mechanism that makes F2B-02
inevitable rather than merely likely.

### F6A-02 (P1, MEASURED) The filesystem-path route exists in 1.13.7 and is not used

Answering the yes/no from the brief: **yes.**

`javap -p` over the vendored `sherpa-onnx-1.13.7.aar` shows `OfflineRecognizer` declares **two**
native entry points:

```
private final native long newFromAsset(android.content.res.AssetManager, OfflineRecognizerConfig);
private final native long newFromFile(OfflineRecognizerConfig);
```

`javap -p -c` on the public constructor shows it branches on the first argument being null:

```
16: aload_0
17: aload_1
18: ifnull        33
21: ...  invokespecial #26  // Method newFromAsset:(...)J
30: goto          41
33: ...  invokespecial #30  // Method newFromFile:(...)J
41: putfield      #34       // Field ptr:J
```

and `Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_newFromFile` is present in the shipped
`libsherpa-onnx-jni.so`. So `OfflineRecognizer(assetManager = null, config = ...)` reaches the
path-based loader. **No new constructor, no new field, no library upgrade is required.**
`OfflineTransducerModelConfig`'s `encoder` / `decoder` / `joiner` are plain `String` fields
(`javap`), reinterpreted as filesystem paths on that route.

### F6A-03 (INFERRED) Assessing the extract-to-filesDir route

Marked INFERRED throughout except where noted, because none of it was measured end to end.

**Would it produce file-backed clean pages?** Partly supported, not established. The shipped
`lib/arm64-v8a/libonnxruntime.so` contains these strings (MEASURED, `strings` over the artifact):

```
ORT model loaded via memory-mapped I/O.
Cannot memory-map an empty file:
mmap error: %d
common::Status onnxruntime::session_state_utils::SaveInitializedTensors(const Env &,
  const std::basic_string<PATH_CHAR_TYPE> &, ... const ExternalDataLoaderManager &, ...)
```

So ORT does have a memory-mapped load path, and it is reached through the `PATH_CHAR_TYPE` (path)
overload, not the byte-buffer one. That is real evidence the route can help. **What is not
established** is whether ORT keeps the initializers pointing into that mapping for this specific
model, or copies them into the session allocator anyway during `SaveInitializedTensors`. ORT's
default for a CPU execution provider is frequently to copy. **This has to be measured before it is
scheduled, and the measurement is cheap:** load by path once, re-run the same `smaps` capture, and
look at whether `[anon:scudo:secondary]` shrinks and a new file-backed 650 MB mapping appears.

**Disk cost.** A second copy of the weights: 652,184,296 B encoder plus 7,257,753 B decoder plus the
joiner, roughly **630 MB in `filesDir`**, on top of the 631 MB already inside the APK. A PHC tablet
would need about 1.3 GB for the model alone. That is a real cost and might on its own be
disqualifying on low-storage hardware.

**Where SHA-256 verification would have to happen.** This is the part that must not be got wrong.
Today the integrity story is: the asset is inside the signed APK, and
`SherpaOnnxTranscriptionService.requireModelAssetsReadable()` only proves each asset is *readable*,
with the KDoc explicitly conceding it does not catch a present-but-corrupt file, and the real guard
being the per-file SHA-256 pinned in the SBOM companion and asserted by an on-device instrumented
test. An extracted copy in `filesDir` **leaves the APK signature boundary**. It is writable by
anything running as the app uid and survives across upgrades. So the SHA-256 check must move from a
one-time instrumented test to a **runtime verification of the extracted file, on every load, before
the path is handed to ORT** and not only after extraction. Otherwise the extracted copy is a strictly
weaker artifact than the asset it replaced, and a model-substitution path is created where none
existed. Hashing 630 MB on every voice capture is itself a cost.

**One-time extraction cost.** NOT MEASURED. It is a 630 MB read plus a 630 MB write on first use, on
eMMC. On the target hardware this plausibly exceeds the current cold-load time by a wide margin, and
it happens exactly when the worker has just tapped the mic.

### F6A-04 (MEASURED, from the repo's own records) The reload cost the two routes trade against

`PROGRESS.md:4259` already carries a measured figure and this audit did not need to re-take it:

> Measured on the x86_64 emulator: first-capture latency **2612 ms cold** (model load on first mic
> tap in a process), **613 ms warm**.

So release-and-reload costs about **2.0 s of added latency on the first capture after every trim**,
on the emulator. `PROGRESS.md:4215` records the handset check qualitatively only: "Cold-load latency
noted (model loads on first mic tap, seconds, not milliseconds; subsequent taps are fast)". **There
is no measured ARM cold-load number anywhere in the repo,** and this audit could not take one because
the handset is locked. On the 1.57x ARM multiplier from Section 1 it would plausibly be 3 to 4 s, but
that is an extrapolation, not a measurement.

Mitigation already in place either way: the "Listening…" state covers the cold-load window, so the
latency is visible to the worker rather than a hang.

### F6B-01 (P0, MEASURED) The two anchors for the FAILED-row defect

The classifier-reliability track asked for anchors, not a description. **Both anchors are absences.**

**Anchor 1, what makes FAILED terminal.** Every syncable DAO collects work with the same query shape.
`PatientDao.kt:18`:

```kotlin
@Query("SELECT * FROM patients WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC")
suspend fun getPendingForSync(): List<PatientEntity>
```

replicated across all twenty `MIGRATION_12_13` tables. `FAILED` is not `PENDING`, so a FAILED row is
never selected by any drain, ever. The mapping that puts it there is `SyncAckMapping.kt:14`
(`"rejected" -> SyncState.FAILED`), applied through `RoomSyncOutboxRepository.applyAck():80`.

**There is no requeue path.** Grep for any `FAILED -> PENDING` transition, any `retryFailed`, any
`resetFailed`, any `requeue`: **zero hits in `app/src/main`.** Nothing, anywhere in the app, can move
a row out of FAILED. It is terminal by construction and by omission together.

**Anchor 2, what makes it invisible.** The count *is* queryable. Each DAO has
`observeFailedSyncCount()` (`PatientDao.kt:31` and nineteen siblings), combined by
`RoomSyncOutboxRepository.observeFailedCount():108`, surfaced as `SyncState.failedCount` in
`domain/sync/SyncStatus.kt`. **It is never rendered.** Grep for `failedCount` across
`app/src/main/java/com/example/samdapp/presentation/`: **zero hits.**

The KDoc on the field states the position outright:

> Rows the backend judged malformed (`rejected`, SAMD-SYNC-6xxx) and the outbox has stopped retrying
> (Phase 6b). Phase 7's admin view surfaces these; this field only makes the count queryable, no UI
> here.

**So this is not a bug in the ordinary sense.** Both halves were decided deliberately and documented.
The defect is that the two decisions compose into a silent permanent data-loss path: a clinical record
can be rejected by the server, stop being retried forever, and produce no signal of any kind on the
device that produced it. Neither half is wrong alone. Together they are.

**What a fix would touch.** `SyncState.failedCount` into the Home sync card, plus a requeue path that
does not exist yet. The requeue is the harder half, because "rejected" means malformed, and blindly
re-pushing a malformed row is what the FAILED state exists to prevent. The honest fix is surfacing
first, requeue second and only after editing.

### F6B-02 (P0, MEASURED) The quick-fill duplicate-ABHA chain, hop by hop

Every hop, with anchors. The finding is one specific hop, named at the end.

**Hop 1, the device sends a fixed ABHA.** `RegisterViewModel.fillDemoData():179-204` writes
`RegisterField.ABHA_NUMBER to DemoPatientProfile.ABHA_NUMBER`, a **constant**. Every quick-filled
patient carries the same ABHA number. The "Fill demo patient data" button is
`RegisterScreen.kt:143-147`, and it is present in the dev flavor UI that the field demo uses.

**Hop 2, the backend rejects the second one with 23505.**
`backend/core/app/models/patient.py:73` declares
`Index("ix_patients_abha_number", "abha_number", unique=True)`, created in
`alembic/versions/0002_clinical_tables.py:235`. The second patient carrying that ABHA raises
`IntegrityError` with sqlstate `23505` under its own savepoint
(`backend/core/app/services/sync.py:428, 510`).

**Hop 3, 23505 becomes a `rejected` ack with a deliberately non-specific message.**
`services/sync.py:125` maps it to `"this record already exists with different data."` The comment
above `_SQLSTATE_MESSAGES` explains why the driver's own text is not echoed: it "often embeds the
offending value (errors.py rule: detail never contains PHI)". That reasoning is correct. Its side
effect is that the device learns only that something already exists.

**Hop 4, the patient row goes terminal and silent.** `rejected` maps to `SyncState.FAILED`
(`SyncAckMapping.kt:14`), which by F6B-01 is never re-collected and never displayed. **The patient
never exists server-side.**

**Hop 5, the case record cannot land either.** `case_records` carries
`FOREIGN KEY (patient_id) REFERENCES patients(id)`. With no patient row, the dependent case record
fails its own FK check (sqlstate `23503`, `services/sync.py:125`, "a referenced record does not exist
yet.") and goes FAILED too, by the same path. The whole subtree is now absent from the backend and
invisible on the device.

**Hop 6, assess is called against a case the server has never seen.**
`backend/core/app/services/kernel.py:86-90`, `_resolve_case_record`, does
`select(CaseRecord).where(CaseRecord.id == case_record_id)` and returns a **404** when it misses. The
module docstring confirms the intent: "A kernel call against another facility's case is a 404, not a
403 (confirming existence would leak)". The device-side code already understands this failure mode.
`AssessmentRunner.kt:70-84` says so in as many words:

> A case created on device exists only locally until the outbox drains, so assessing before pushing
> gets SAMD-ENC-4002 "case record not found" and collapses to the fallback/unavailable path for a
> reason that has nothing to do with the kernel being unavailable. Push first, then assess.

**Hop 7, and this is the finding: the true error is destroyed here.**
`GenerateKernelReportUseCase.kt:250-255`:

```kotlin
} catch (e: Exception) {
    // Any failure (network down, timeout, HTTP error, parse error, server offline) is
    // logged here and returns null — the caller tries kernelFallbackSource next, then
    // buildUnavailableOutput. The app never crashes when the ML server is unreachable.
    logger.warning("Kernel API unavailable — trying fallback source. Reason: ${e.message}")
    null
}
```

(The two em dashes in that block are in the source file and are reproduced verbatim. They are the only
two in this report; altering a quoted anchor would be worse than carrying them.)

**One blanket `catch (e: Exception)` collapses every failure class onto one outcome.** A 404
SAMD-ENC-4002 caused six hops upstream by a duplicate ABHA is handled identically to a dead network.
The result is `InferenceSource.UNAVAILABLE` and the worker-facing strings at
`GenerateKernelReportUseCase.kt:73-74`, `UNAVAILABLE_PREDICTED_CONDITION = "Assessment unavailable"`
and `UNAVAILABLE_REASONING_SUMMARY`. The worker is told the AI did not produce a result. The truth is
that their patient was never accepted by the server because of a duplicate identifier, and nothing
anywhere will tell them that.

**Hop 7 is the finding.** Hops 1 to 6 are each individually defensible, and several are documented
decisions with good reasons. Hop 7 is where a recoverable, specific, actionable data-integrity error
is converted into a misleading infrastructure error, and it is one `catch` block.

**Note on what is already right.** `AssessmentRunner.kt:82-84` **does** push before assessing, which
is the correct ordering and fixes the common case. It also explicitly declines to special-case a
failed push: "a failed push means the kernel call below fails too and lands in the honest UNAVAILABLE
state that already exists, which is the correct outcome". That reasoning holds when the push failed
because the device is offline. It does not hold when the push failed because the server *rejected the
data*, and the code cannot currently tell those two apart. Filed separately as F6B-04.

### F6B-03 (P2, MEASURED) Four OkHttp clients, no shared pool, no call timeout

Every production `OkHttpClient.Builder()` site:

| client | file | connect | read | write | callTimeout | interceptors |
| --- | --- | ---: | ---: | ---: | --- | --- |
| backend/core | `NetworkModule.kt:88-104` | 10 s | 30 s | 30 s | **none** | dev hosts, Bearer, logging; `TokenAuthenticator` |
| ABHA | `NetworkModule.kt:116-131` | 10 s | 30 s | 30 s | **none** | dev hosts, Bearer; `TokenAuthenticator`; deliberately **no logging** |
| Gemini | `GeminiNetworkModule.kt:46-51` | 10 s | 12 s | 10 s | **none** | none |
| Pi gateway (dev only) | `PiGatewayNetworkModule.kt:65-71` | 2 s | 5 s | 5 s | **none** | none; custom `NsdGatewayDns` |

**Every one is built from a fresh `OkHttpClient.Builder()`, none from `newBuilder()` off a shared
instance.** Four independent `ConnectionPool`s, four `Dispatcher`s, four `ExecutorService`s. No
explicit `ConnectionPool` sizing anywhere, so each takes the OkHttp default of 5 idle connections at
5 minutes keep-alive, times four.

**No `callTimeout` on any client.** Grep confirms zero hits across all of `app/src`. Connect, read and
write timeouts bound individual socket operations; only `callTimeout` bounds the whole call. A server
that dribbles bytes just under the read timeout can hold a request open indefinitely, which for the
30 s read timeout on the backend client is the realistic bad case on a rural link.

**Pi gateway client: BearerInterceptor confirmed absent.** `PiGatewayNetworkModule.kt:39-41` states
the requirement explicitly ("This client MUST NOT be `NetworkModule.provideOkHttpClient`. That one
installs `BearerInterceptor` and `TokenAuthenticator`, so reusing it would attach the backend access
token"), and the builder at `:66-71` adds neither. Correct, and correctly reasoned.

**Blocking work on interceptor threads:** two sites, `BearerInterceptor.kt:32` and
`TokenAuthenticator.kt:49`, both `runBlocking` on a DataStore read. Covered in F3-05. Never on the
main thread.

**Retrofit and Gson parsing:** off the main thread. Every API interface method is `suspend`, so
Retrofit dispatches on OkHttp's executor and Gson parses there. No response body is read with
`.string()` into memory where it could stream; the one large-payload path, document bytes, does not
go through Retrofit at all. No finding.

### F6B-04 (P3, MEASURED) Worker policies

| worker | scheduler | backoff | constraints | uniqueness | expedited |
| --- | --- | --- | --- | --- | --- |
| `AssessmentWorker` | `WorkManagerAssessmentScheduler.kt:35-44` | `EXPONENTIAL`, `MIN_BACKOFF_MILLIS` | `NetworkType.CONNECTED` | `enqueueUniqueWork(uniqueWorkName(caseRecordId), KEEP)` | no |
| `SyncPushWorker` periodic | `WorkManagerSyncOutboxScheduler.kt:30-40` | `EXPONENTIAL`, `MIN_BACKOFF_MILLIS` | `NetworkType.CONNECTED` | `enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, KEEP)` | no |
| `SyncPushWorker` one-shot | `WorkManagerSyncOutboxScheduler.kt:45-48` | `EXPONENTIAL`, `MIN_BACKOFF_MILLIS` | `NetworkType.CONNECTED` | `enqueueUniqueWork(ONE_TIME_WORK_NAME, REPLACE)` | no |

The `KEEP` on assessment is a documented deliberate divergence from `REPLACE`
(`WorkManagerAssessmentScheduler.kt:22`). `WorkManagerSyncOutboxScheduler.kt:52` notes that
WorkManager has no built-in retry cap, which is why a permanently-bad row is made terminal inside the
drain rather than left to WorkManager. That reasoning is sound and is exactly what produces F6B-01.

**What makes a row terminal** is not WorkManager state at all: it is `SyncState.FAILED` in the app's
own Room tables, set at `SyncAckMapping.kt:14`. `SyncPushWorker.kt:30` says so: "a permanently-bad row
is handled inside the drain itself (FAILED state), not by [WorkManager retry]".

### F6C-01 (P1, MEASURED) The served model version matches nothing that is documented

**What is actually served.** The classifier is running and answering:

```
GET http://127.0.0.1:8000/health
{"status":"healthy","service":"samd-classifier","model_version":"toy-v0.6-observed-glucose-4tier"}
```

`SaMDClassifier/models/model_meta.json` agrees: `model_version: toy-v0.6-observed-glucose-4tier`,
trained from `dataset/canonical_dataset.csv`, 22,212 rows, 8 features, 3 labels.

**What the repository claims, in four different places, none of which match:**

| source | version string |
| --- | --- |
| `docs/backend/api-contract.md:825` | `"model_version": "xgb-2026-06-11"` |
| `docs/quality/risk-management-file.md:46` | `v0.1-xgb-venn-abers` |
| `PROGRESS.md:4510` | `v0.2-enriched-symptom-pool` |
| `PROGRESS.md:4514` | `v0.1-tfidf-xgboost` |
| **runtime** | **`toy-v0.6-observed-glucose-4tier`** |

**Five strings, no two the same.** The API contract's is a documented wire example, which is the most
forgivable. The risk management file naming a different model than the one being served is not
forgivable in a device whose risk file is a regulatory artifact: the hazard analysis is written
against `v0.1-xgb-venn-abers` and the thing answering `/assess` is `toy-v0.6`.

**And the served version says `toy`.** Whatever the intent, an artifact whose own metadata calls
itself a toy is being served through the clinical path in a build configured with `ENVIRONMENT: dev`
and `ABDM_MODE: live`.

This is a traceability finding under IEC 62304, not a performance one. The backend already takes this
class of problem seriously: `services/kernel.py:19-25` explains at length that the proxy stopped
attributing backend arithmetic to a named model version precisely "because it breaks IEC 62304
traceability". The same standard applied to the version string itself gives this finding.

### F6C-02 (P1, MEASURED) The backend does not enforce one report per case, and already has duplicates

**Confirmed from the live database, not from the migrations alone:**

```
\d kernel_reports    ->  "ix_kernel_reports_case_record_id" btree (case_record_id)
\d evaluate_reports  ->  "ix_evaluate_reports_case_record_id" btree (case_record_id)
```

Neither carries `UNIQUE`. Compare the Android side, where `MIGRATION_15_16` deliberately added
**unique** `caseRecordId` indexes to `KernelReportEntity` and `EvaluateReportEntity` to enforce
one-current-assessment-per-case, and deduplicated the existing rows to do it.

**The asymmetry is not theoretical. The duplicates are already there:**

```sql
SELECT 'evaluate', case_record_id, count(*) FROM evaluate_reports GROUP BY 2 HAVING count(*)>1;
 evaluate | a6b9606e-5ac4-4353-9241-2996e30be65f | 2
 evaluate | 1d1e5ae3-3d5c-4948-ae6a-6bd5a44677d6 | 2
 evaluate | b3e4ea2d-64e0-4dd8-b005-d998cfcb4b8b | 2
 evaluate | bc51fb0d-bbee-466b-9901-956a61dc45e4 | 2
 evaluate | dc538044-f66c-4b9b-a95b-a9d1347c8077 | 2
(5 rows)
```

Row counts: `kernel_reports` 9, `evaluate_reports` 12, `patients` 13, `case_records` 11,
`audit_events` 645. **Ten of the twelve `evaluate_reports` rows are one of five duplicate pairs.** The
contract the device enforces is already violated on the server.

**Query plan, with an honest caveat.**

```
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM evaluate_reports WHERE case_record_id = (...);
 Index Scan using ix_evaluate_reports_case_record_id on evaluate_reports
   (cost=0.23..8.25 rows=1 width=380) (actual time=0.030..0.030 rows=0 loops=1)
   Index Cond: ((case_record_id)::text = ($0)::text)
   Buffers: shared hit=1 read=1
```

The index is used. **At 12 rows this tells you nothing about production behaviour** and I am not going
to pretend otherwise. The finding here is the missing UNIQUE and the duplicates it already permitted,
not the plan cost. Plan cost at realistic volume is NOT MEASURED.

### F6C-03 (P2, MEASURED) The model is not loaded per request. Not a P0.

**First, a correction to the brief's framing: `backend/core` does not load a model at all.** There is
no `assess.py`. `/api/v1/assess` and `/api/v1/evaluate` live in
`backend/core/app/api/v1/kernel.py:29-32` and `:57-61`, and both are **proxies**: they call
`kernel_service.assess(...)` / `.evaluate(...)` (`:45`, `:72`), which forwards over
`httpx.AsyncClient` to `settings.kernel_base_url` via `call_kernel`
(`backend/core/app/services/kernel.py:223`, `app/adapters/kernel/client.py`).

**The model lives in the separate classifier service.** `SaMDClassifier/src/app.py`, lines 32 to 39,
at **module scope**, not in a request handler and not in a lifespan hook:

```python
with open(os.path.join(PROJECT_ROOT, "models", "model_meta.json"), "r") as f:
    meta = json.load(f)
model = xgb.XGBClassifier()
model.load_model(os.path.join(PROJECT_ROOT, "models", "model.json"))
explainer = shap.TreeExplainer(model)
```

**Import time. Once. Not per request.** The routes at `:54` (`/v1/assess`) and `:164`
(`/api/v1/evaluate`) close over the already-built `model`, `meta` and `explainer`.

**Measured, to confirm rather than assume.** Ten sequential `POST /v1/assess`, `curl` `time_total`:

```
25.3, 9.5, 15.6, 10.8, 12.3, 11.6, 5.6, 5.5, 12.3, 37.4  (ms)
```

p50 **11.97 ms**, p95 **about 36 ms**, max 37.4 ms. First call 25.3 ms against a steady-state median of
about 12 ms: **a delta of roughly 13 ms.**

**That delta is the answer.** A per-request model load would put hundreds of milliseconds to seconds
on the first call. Thirteen milliseconds is first-call HTTP and connection setup. The model is
resident. **Not a P0, and this is measured rather than inferred.**

Minor, noted in passing: the two routes have inconsistent prefixes, `/v1/assess` at `app.py:54`
against `/api/v1/evaluate` at `:164`, in the same file.

### F6C-04 (P2, MEASURED) 2.05 GB resident for a 4.7 MB model, single worker

```
docker stats --no-stream
samd-classifier   2.048GiB / 5.571GiB   0.11%
backend-api-1     154.6MiB / 5.571GiB   0.12%
backend-db-1      92.57MiB / 5.571GiB   1.90%
```

The model artifacts total about 4.7 MB (`model.json` 1,453,971 B, `category_model.xgb` 3,251,888 B,
plus small joblib files). **The other ~2 GB is the import graph**, and `src/app.py:4-7` names it: a
`pipeline_glue` import pulls in sentence-transformers, which pulls in torch, for RAG treatment lookup.
The comment there is about Windows OpenMP DLL ordering; the memory consequence is not commented on
anywhere.

**Worker count:** `SaMDClassifier/Dockerfile:42` is
`CMD ["uvicorn", "src.app:app", "--host", "0.0.0.0", "--port", "8000"]`. **No `--workers` flag, so
one worker, one process, one copy.** Same for `backend/core`:
`uvicorn app.main:app --host 0.0.0.0 --port 8080` in `backend/docker-compose.yml`.

**So there is no duplication today.** The finding is the exposure: because the model and the torch
graph are loaded at **module import**, adding `--workers N` (the obvious first move when the service
needs throughput) multiplies the full 2.05 GB by N, not the 4.7 MB of model. On a GPU box with, say,
`--workers 4`, that is about **8.2 GB of host RAM** to serve an XGBoost classifier that needs
megabytes. INFERRED for the multiplied figure; the 2.048 GiB per-process base is MEASURED.

### F6C-05 (P2, MEASURED) The assess response asserts calibration the metadata denies

`SaMDClassifier/src/app.py:71`, on the critical-threshold-breach branch:

```python
"model_metadata": {"version": meta["model_version"], "calibrated": True}
```

`SaMDClassifier/models/model_meta.json`:

```
calibration_used_for_evaluation: False
```

The response hard-codes `calibrated: True` while the artifact's own metadata records that calibration
was not used for evaluation. A field named `calibrated` on a clinical inference response is a
statement about confidence quality, and it is being asserted rather than read.

Related and worth a look during the fix: `model_meta.json` declares
`labels: ['low_risk','moderate_risk','high_risk']` while `target_definition.mapping` has four source
tiers with `'4' -> high_risk`, which matches the served version string
`toy-v0.6-observed-glucose-**4tier**`. The four-to-three collapse may be intended, but it is not
documented anywhere this audit found.

### F6C-06 (P3, MEASURED) SQLAlchemy session and pool

`backend/core/app/db/session.py:24-29`: one `create_async_engine` with `pool_size=settings.db_pool_size`,
`max_overflow=settings.db_max_overflow`, `pool_pre_ping=True`. Values come from settings, which are
env-sourced; **not read, per the credential rule**. A module-level `_sessionmaker` cached behind
`get_sessionmaker()` (`:40-43`), and a request-scoped `AsyncSession` dependency at
`backend/core/app/deps.py:20-30`.

This is the correct shape: one engine, one pooled sessionmaker, one session per request. `pool_pre_ping`
is on, which is right for a connection that may sit idle across a rural link.

**N+1 in the sync batch path: none found.** `services/sync.py` applies records under per-record
savepoints, with the failure mapping at `:206` and `:428`/`:510`. The per-record savepoint is a
deliberate isolation choice, not an N+1 select.

**pgcrypto on hot read paths.** Column encryption is implemented as SQLAlchemy bind and column
expressions in `backend/core/app/db/types.py:52-62`: `func.pgp_sym_encrypt(...)` on write,
`func.pgp_sym_decrypt(column, _key_param())` on read. Because it is a column expression, **every
`SELECT` that touches an encrypted column decrypts it, whether or not the caller needs the value.**
The encrypted columns live on `patients` (`models/patient.py:64` notes a CHECK cannot see through
pgcrypto) and on `abha` session fields (`models/abha.py:76`).

**How many columns are decrypted per typical request: NOT MEASURED.** Establishing it needs either a
`pg_stat_statements` capture or an `EXPLAIN` per hot endpoint under realistic load, and at 13 patient
rows neither would mean anything. The structural point stands and is MEASURED: decryption is attached
to the column, not to the projection, so it is paid on every read of that column by every query.

### F6D-01 (P1, MEASURED) The PHI sweep has one trigger and reports nothing. H-18.

This replaces Section 6 item 12, which recorded an unreproducible sweep failure as probable
measurement error. The reviewer's read was right, and the explanation is the finding.

**Is there any trigger other than cold process start? No.** Every call site of either sweep:

| call site | context |
| --- | --- |
| `SaMDApplication.kt:30` | `sweepOrphanedViewerTempFiles(this)`, in `onCreate` |
| `SaMDApplication.kt:36` | `sweepOrphanedCaptureSessions(this)`, in `onCreate` |
| `DocumentCaptureAssemblyTest.kt:60, 203` | instrumented test only |

**Two production call sites, both in `Application.onCreate`, which runs once per process.** No
`onStop`, no `onDestroy`, no `ProcessLifecycleOwner` hook, no periodic worker, no `WorkManager` job.
That fully explains the anomalous observation: a launch that does not start a new process does not run
`Application.onCreate`, so it does not sweep, and the files are still there afterwards.

**How long can files accumulate?** Bounded, but by something fragile rather than by design.
`DocumentViewerViewModel.onCleared():218-224` does `tempFile?.delete()`, so a clean viewer exit removes
its own file, and the temp name is deterministic per document (`"$documentId.$ext"`,
`DocumentViewerViewModel.kt:140`), so re-opening the same document reuses one name. **The accumulation
window is therefore one file per distinct document viewed, for any document whose `onCleared` did not
run or whose `delete()` returned false, for the entire life of the process.** In a PHC that opens the
app in the morning and leaves it running until evening, that is a full clinic day of decrypted
plaintext PHI in `cacheDir`, with no cleanup until the process next dies.

**Are failures ignored? Yes, all of them, everywhere.** Confirmed from source:

| site | return value | handled? |
| --- | --- | --- |
| `DocumentViewerViewModel.kt:51` | `listFiles()` returns `null` on IO error or non-directory | `?.` silently no-ops |
| `DocumentViewerViewModel.kt:51` | `it.delete()` returns `Boolean` | discarded inside `forEach` |
| `AndroidDocumentCaptureStore.kt:81` | `deleteRecursively()` returns `Boolean` | discarded |
| `DocumentViewerViewModel.kt:223` | `tempFile?.delete()` returns `Boolean` | discarded |

**A failed or partial sweep produces no audit row, no log line, no metric, nothing.** It is
bit-for-bit indistinguishable from a successful one, from inside the app and from outside it.

**Why this is H-18 and not housekeeping.** The sweeps are the stated mechanism by which plaintext PHI
never outlives the process that decrypted it. That is the control. As written, the control has
**exactly one trigger, no coverage of a long-lived process, and no evidence of execution**. For a
regulated device, a safety control that cannot report whether it ran is not a control that has been
verified; it is one that has been assumed. That is the finding, and it is independent of how fast the
sweep is (F1-06, where the cost was below the emulator noise floor).

**What a fix would touch.** `SaMDApplication` for a second trigger, `DocumentViewerViewModel.kt:50-52`
and `AndroidDocumentCaptureStore.kt:80-82` for return-value handling plus an `AuditAction` on failure.
The audit vocabulary already exists (`domain/audit/AuditLogger.kt`). Adding a trigger changes when PHI
is swept, so the H-18 argument has to be redone, exactly as for F1-06.

---

## Section 4: Root-cause grouping

Grouping by cause, not by area. Where findings do not share a cause I say so rather than manufacture
one.

### Root cause A: nothing in this app has a release path

**F2B-01, F2B-02, F2B-03, F2B-05, F2B-04.**

This is one defect wearing five costumes. The recognizer is built and never released
(`SherpaOnnxTranscriptionService.kt:117`). The `PdfDocument` accumulates every page and never streams
(`writePdf`). No `onTrimMemory` exists anywhere to ask for any of it back. The consequence is a process
that reaches 800 MB from one tap, releases 0.6% of it on background, and sits at oom_adj 700 with
0.94 GB RSS.

**This is the answer to the question the audit was commissioned to ask.** The camera symptom was never
about the camera. The process was not cheap to evict; it was the most expensive thing on the device and
had no way to get cheaper. PR #54 and PR #57 correctly fixed what the eviction destroyed. Root cause A
is why the eviction happened, and it is untouched.

The anchors prove the linkage: 72,528 KB before the voice tap, 819,411 KB after, 814,431 KB
backgrounded, `curRaw=700`, one comment at `SherpaOnnxTranscriptionService.kt:173` naming the fix that
was never built.

### Root cause B: the dependency graph is realised eagerly, at the worst moment

**F3-01, F1-05, F1-07, F2A-01, F3-03, and partly F2B-01.**

Four `hiltViewModel()` calls in `AppNavHost`'s first composition realise the entire singleton graph on
the main thread: the SQLCipher native library, a Keystore round trip, OkHttp, Retrofit, Gson. Measured
at 294 to 305 ms (SQLCipher, Keystore) and 907.98 ms (Conscrypt) in the trace.

`NavRestoreViewModel` is the clearest instance: it forces the whole encrypted database stack in order to
decide, on almost every launch, that it has nothing to log.

**F2A-01 is the same cause one layer down.** Because the database is opened eagerly and then immediately
hit by three concurrent `HomeViewModel` collectors plus `SyncStatusImpl` plus `NavRestoreViewModel`, four
Room IO threads pile into a connection pool that serialises them for a second.

Voice is the outlier inside this group: the recognizer is *correctly* lazy (measured, no ONNX load at
launch). Its problem is the opposite one, root cause A.

### Root cause C: R8 has never run, so nothing is ever removed

**F2C-01, F1-09, F1-02, F1-10, and the `MockAuthSession` dead code.**

`material-icons-extended` contributes 49.19% of executable dex to supply two icons. A scratch `test.kt`
with a `fun main()` ships in the default package. `MockAuthSession` is documented as unbound and ships
anyway. Both ABIs ship in every flavor. None of this is removed because, per correction C3, R8 has never
run on this app in any variant.

These are genuinely one cause. They are also the *least* urgent of the three, because ART dexopt
amortises the runtime half of the cost. What does not amortise is the provenance half (F1-09) and the
artifact size.

### Root cause D: a failure that cannot be seen is treated as a failure that did not happen

**F6B-01, F6B-02, F6D-01, F6C-01, F6C-05.**

Phase 6 found the same defect shape five times, in five unrelated subsystems, and it is not a
performance defect at all.

- A sync row is rejected by the server, goes terminal, and **nothing renders it** (F6B-01). The count is computed and thrown away.
- A data-integrity failure six hops upstream is caught by a blanket `catch (e: Exception)` and **reported as "the ML server is unavailable"** (F6B-02). The true cause is destroyed at a single line.
- The PHI sweep's `listFiles()` can return null and its `delete()` can return false, and **neither is checked**, so a safety control that did not run is indistinguishable from one that did (F6D-01).
- The model version in the risk file, PROGRESS.md and the API contract **all disagree with what is actually served** (F6C-01), and nothing compares them.
- The assess response **asserts `calibrated: True`** while the model metadata records that calibration was not used (F6C-05).

In every case the information exists somewhere in the system and is discarded before it reaches
anyone who could act on it. For an ordinary app this is a quality complaint. For a device whose
regulatory file rests on traceability and on evidence that controls executed, it is the most serious
pattern in this report after root cause A, and it is the one that gets worse quietly rather than
loudly.

**F6B-02 is where root cause D meets a real clinical surface.** The worker is told the AI is down. The
truth is their patient never reached the server. They will retry, and retry, and the retry will never
work, and no screen will ever say why.

### What does NOT share a cause

- **F1-06, the startup sweep cost**, is its own thing: a deliberate H-18 safety property whose cost happens to be unbounded. Note that its sibling F6D-01 is root cause D, not this. The cost and the invisibility are genuinely different problems in the same two functions.
- **F2D-01, the Gemini path**, shares no cause with anything else in this report. It is an architecture and regulatory question that a performance audit happened to walk past. Do not schedule it as a perf item.
- **F1-01/F1-02, the dexopt cliff**, is an install-state property, not a code defect. See correction C1.
- **F6C-03 and F6C-04**, the backend model serving, are genuinely fine. The model loads at import, one worker, measured 12 ms p50. I am recording that plainly rather than manufacturing a finding: the brief expected a possible P0 here and there is not one.

---

## Section 5: Ranked fix sequence

Phases 4 and 5 have not run, so this ranking may still change. It is ranked on what is measured today.

### Item 0, recorded deferral, not a scheduled fix

**AMENDED 2026-09-18, audit close-out (A1). This replaces the earlier item 0 in full.** The owner has
reversed the removal decision the prior draft recorded. **The Gemini brand-lookup path (F2D-01) is
RETAINED for now.** This is a decision record, not a task, and it is deliberately kept above item 1 so
a future reader does not mistake retention for an oversight.

**What the path is for, stated so the retention reads as scoped rather than open-ended.** It exists to
supply the brand name and manufacturer for the medicine the evaluate line names, for display next to
that medicine. The medicines themselves come from the NLEM database PDF, not from Gemini; Gemini never
chooses or names a drug. **Removal is deferred until a deterministic medicines database pipeline
exists** to replace the brand/manufacturer lookup. That pipeline is future work with no target date and
is not scheduled by this report.

**What lands instead, in the housekeeping PR:**

1. Move the `GEMINI_API_KEY` buildConfigField out of `defaultConfig` (`app/build.gradle.kts:74`) into
   the dev flavor only, so it no longer compiles into staging or prod.
2. Add a test asserting the key is blank under staging and prod, in the shape of the existing
   flavor-aware `RoutesSecurityTest`.

**The two independent defects the prior draft named are unchanged by this deferral, and neither is
closed by the housekeeping PR above:**

1. **Third-party egress**, now narrowed to the dev flavor rather than removed. `GeminiBrandLookupSource.kt:33`'s blank-key check remains the only runtime containment; the housekeeping PR makes the key structurally absent from staging/prod builds, which is stronger than today, but the dev-flavor path itself still calls a third party with no scan test of the kind the ASR ban has.
2. **Unverified generative text on a clinical artifact.** The only validation is a `split("|")` shape check. No entity verifier, no formulary cross-check, nothing. The SLM track already built `SlmStreamSanitizer` for exactly this purpose and this path does not use it. This defect is untouched by the flavor move and exists on every dev build that carries a key.

**Risk file. AMENDED, drafted in a separate pass.** Two hazard rows now exist in
`docs/quality/risk-management-file.md` section 2 covering these defects independently:
**H-11.C1** (the egress defect, a caveat on the existing H-11) and **H-27** (the unverified-generative-
text defect, new). Both are **PROPOSED, awaiting operator sign-off**, both **open and unaccepted**.
Referenced here by id only; their content is not restated, and that file is not touched by this pass.

| | |
| --- | --- |
| expected gain | None in performance terms. Confines Gemini egress to the dev flavor; does not touch the unverified-generation defect. |
| blast radius | `app/build.gradle.kts:74`, plus a new flavor-scoped test modelled on `RoutesSecurityTest` |
| tests that must go green | New GEMINI_API_KEY-blank-under-staging/prod test; existing `RoutesSecurityTest`; evaluate-pipeline tests |
| risk control | **H-11.C1 and H-27**, both PROPOSED, awaiting operator sign-off, in `docs/quality/risk-management-file.md` §2. |
| model tier | Sonnet for the housekeeping PR (buildConfigField move plus test). The entity-verification defect and the pipeline-replacement work remain owner-scoped and are not started by this item. |

### The ranked engineering sequence

**Renumbered 2026-09-18, audit close-out (A1, A2).** Item 1 is a new entry, formed by promoting and
merging F6C-01 and F6C-05. Item 2 (F6B-01) is moved up to sit directly below it, above the ASR
residency fix, per A2's rationale, stated in that row. Every item from 4 onward keeps its prior
substance under a new number; nothing below item 3 was re-argued.

| # | fix | expected gain | blast radius | tests that must pass | risk control | model tier |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **Stop the false attestation on the assess response (F6C-01 + F6C-05, promoted and re-filed JOINTLY, A1).** Read `calibrated` from `model_meta.json` instead of hard-coding `True`, and reconcile the served `model_version` against the risk file, PROGRESS.md and the API contract. | No perf gain. **This is a false-attestation finding on a clinical output, not backend hygiene.** The response asserts calibration (`calibrated: True`) that the artifact's own metadata denies (`calibration_used_for_evaluation: False`), from a model whose version string (`toy-v0.6-observed-glucose-4tier`) matches none of the four documented strings in the risk file, PROGRESS.md or the API contract. Note the parallel to the static `"AI-Assisted, Physician-Verified"` disclaimer (`ReportFormatter.kt:44`), which asserts a verification state regardless of whether it occurred — same defect class, argue the two together when this is scoped. | `SaMDClassifier/src/app.py:71`, `SaMDClassifier/models/model_meta.json`, `docs/quality/risk-management-file.md:46`, `PROGRESS.md:4510,4514`, `docs/backend/api-contract.md:825` | Classifier tests for the `calibrated` half; none on the app for the version-string half, it is a documentation and release-process change | **Yes.** IEC 62304 traceability and a clinical-honesty control together: the risk file names a model that is not being served, and the response claims a calibration property the model metadata denies. | **Owner decides the true intended `model_version` first; Sonnet applies both fixes once decided.** |
| 2 | **Surface the FAILED sync count (F6B-01, anchor 2).** Render `SyncState.failedCount` on Home. | No perf gain. Ends a silent permanent data-loss path. | `HomeScreen`, `HomeViewModel`. The value is already computed and plumbed. | `SyncStatusImplTest`, Home UI tests | Data-integrity control. A rejected clinical record currently disappears with no signal. | **Sonnet.** The hard half (requeue) is deliberately not in this item. |
| | **A2 rationale, why this sits above the ASR fix:** permanent silent loss of clinical sync rows has no mitigation and no observer anywhere in the app. The ASR eviction symptom (item 3) is a real defect but is already mitigated at the user-visible level by PR #54 (in-process CameraX) and PR #57 (saveable nav back stack); this one is not mitigated at all. | | | | | |
| 3 | **Fix the ASR residency.** Two routes, see below. | **MEASURED baseline: 819,411 KB PSS post-voice, 814,431 KB backgrounded, of which `[anon:scudo:secondary]` is 692,380 KB private dirty. Target: stop 692 MB being unreclaimable.** | Route A: two files. Route B: one constructor argument plus an extraction and hashing path. | ASR instrumented suite, `AsrEgressTest`, `NoPlatformRecognizerSourceScanTest`, `TranscriptionPathHasNoNetworkDependencyTest` | Route A: transcription hazard entries; egress is unaffected by residency, say so explicitly. **Route B additionally moves the model outside the APK signature boundary and needs the SHA-256 control rewritten.** | **Opus.** |
| 4 | **Stop destroying the true error (F6B-02, hop 7).** Narrow the blanket `catch (e: Exception)` so a 404 SAMD-ENC-4002 does not present as "Assessment unavailable". | No perf gain. The worker finally learns their patient was rejected instead of being told the AI is down. | `GenerateKernelReportUseCase.kt:250-255`, plus a new distinguishable UI state beside `UNAVAILABLE`. | `GenerateKernelReportUseCaseTest`, assessment-runner tests, report-renderer tests | **Yes.** `InferenceSource.UNAVAILABLE` is the honest-failure control from the kernel-mock-safety work. Adding a state beside it must not weaken it. | **Opus.** Touches the honest-failure contract. |
| 5 | **Give the PHI sweep a second trigger and a voice (F6D-01).** Handle the discarded return values, write an audit row on failure, add coverage for a long-lived process. | No perf gain. Makes an H-18 control verifiable instead of assumed. | `SaMDApplication.kt:25-37`, `DocumentViewerViewModel.kt:50-52`, `AndroidDocumentCaptureStore.kt:80-82` | `DocumentCaptureAssemblyTest`, H-18 suite, audit-log tests | **Yes, H-18.** Adding a trigger changes when plaintext PHI is swept. The argument must be redone, exactly as for F1-06. | **Opus.** |
| 6 | **Break the SQLCipher pool contention (F2A-01).** Pool sizing and/or raw-key derivation, plus reducing Home's query fan-out. | MEASURED baseline: 7 blocks of 314 to 650 ms chained across 726 to 1691 ms. Target: eliminate the serialisation. | `DatabaseModule.kt:92-103`, possibly `DatabasePassphraseProvider`, `HomeViewModel.init:52-83` | Every migration test (`MigrationTest12To13` through `MigrationTest18To19`), full `testDevDebugUnitTest` | **Yes.** Raw-key derivation changes the key handling `DatabasePassphraseProvider` exists to protect. | **Opus.** |
| 7 | **Add a UNIQUE index on `case_record_id` (F6C-02)**, after deduplicating the 5 existing duplicate pairs. | No perf gain at current volume. Makes the backend enforce the contract the device already enforces. | New alembic migration, plus a dedup step modelled on the device's `MIGRATION_15_16`. | Backend test suite, `alembic check` | **Yes.** One-current-assessment-per-case is a clinical contract, not an index preference. | **Opus.** A destructive dedup migration on clinical rows. |
| 8 | **Install StrictMode in dev (F3-02).** | No runtime gain. Permanent regression guard for items 9, 10 and every future instance. | One file, about 10 lines. | `testDevDebugUnitTest` | No | **Sonnet.** Do it before 9 and 10. |
| 9 | **Make `NavRestoreViewModel` lazy (F3-01).** `dagger.Lazy<AuditLogger>`. | MEASURED anchor: removes the SQLCipher dlopen plus keystore2 round trip (294 to 305 ms window) from first composition on the common path. | One file, one constructor parameter. | Nav saver suite, `RoutesSecurityTest`, audit-log tests | No | **Sonnet.** |
| 10 | **Fix `readDecrypted`'s dispatcher (F3-04).** One `withContext(Dispatchers.IO)`. | No measurable gain today. Makes a `suspend` signature honest. | `ConsultationDocumentRepositoryImpl.kt:310-315`. | Document viewer tests, H-18 suite | Touches the H-18 decrypt path. Low risk, still name it. | **Sonnet.** |
| 11 | **Add `callTimeout` and share one OkHttp client (F6B-03).** `newBuilder()` off a single base instance for the three production clients. | No measured gain. Bounds a stalled call and collapses four connection pools and dispatchers into one. | `NetworkModule.kt:88-131`, `GeminiNetworkModule.kt:46-51`. **Leave the Pi gateway client separate**, its isolation is deliberate and correct. | `BearerInterceptorTest`, `TokenAuthenticatorTest`, ABHA tests | The ABHA client's structural absence of a logging interceptor must survive the refactor. That is the trap. | **Sonnet**, with that one constraint stated up front. |
| 12 | **Drop `material-icons-extended` (F2C-01).** Two vector drawables for `Mic` and `PhotoCamera`. | MEASURED: removes 11,400 classes, 23,529 methods, 12,401,752 code bytes, 49.19% of executable dex. Runtime gain only in `run-from-apk` state. | `app/build.gradle.kts:203`, 8 call sites, 2 new drawables. | Full `testDevDebugUnitTest`, Compose UI tests, visual check on the 2 replaced icons | No | **Sonnet.** |
| 13 | **Delete the scratch files (F1-09).** `data/local/security/test.kt` and `data/local/security/build.gradle.kts`. | No runtime gain. Closes an SBOM and DHF exposure, see C2. | Two file deletions. | Full `testDevDebugUnitTest`, SBOM regeneration (`:app:cyclonedxBom`) | **Yes, provenance.** A DHF item for CDSCO, not a cleanup. | **Sonnet** to execute, the DHF note is the owner's. |
| 14 | **Stream PDF assembly (F2B-04).** | INFERRED baseline: about 77 MB retained for 10 pages, about 154 MB at `MAX_PAGES = 20`. Not measured. | `writePdf`, `drawPage`. | `DocumentCaptureAssemblyTest`, H-18 suite | **Yes, H-18.** Changes when plaintext exists in memory. | **Opus.** Needs a real measurement first. |
| 15 | **Split `abiFilters` per flavor (F1-10).** | MEASURED: about 37 MB of x86_64 native code out of staging and prod. | `app/build.gradle.kts:70-72`. | Instrumented ASR tests must still run on an emulator, so dev keeps x86_64 | No | **Sonnet.** |

### Item 3 in full: the two routes, and which the evidence supports

The previous version of this report assumed release-and-reload was the fix. Phase 6A established the
mechanism, and the choice is now between two genuinely different routes.

**What 6A settled (MEASURED).** The weights are **not** file-backed clean pages. They are 692,380 kB
of anonymous **private dirty** memory in `[anon:scudo:secondary]`, with zero private-clean and zero
shared-clean. The `base.apk` mapping is only 21,496 kB resident, which is the control proving the APK
is not serving them. The kernel has nowhere to evict these pages to. **The only reclamation available
today is killing the process**, which is precisely what F2B-02 measured happening.

**Route A: release and reload.** Add `release()` to `SherpaOnnxTranscriptionService`, implement
`ComponentCallbacks2.onTrimMemory` in `SaMDApplication`, null the `@Volatile` field under the existing
`recognizerLock`.

- Gain: the full 692 MB becomes reclaimable on demand. Returns the backgrounded process from 814,431 KB toward the 72,528 KB pre-voice baseline.
- Cost: **MEASURED, from the repo's own record.** `PROGRESS.md:4259`, first-capture latency **2612 ms cold against 613 ms warm** on the emulator. So about **2.0 s of added latency on the first capture after each trim**. No ARM figure exists anywhere; `PROGRESS.md:4215` records it only as "seconds, not milliseconds". The "Listening…" state already covers the window.
- Confidence: **high.** Nothing speculative. The KDoc at `SherpaOnnxTranscriptionService.kt:173` already names this as the upgrade path.
- Risk: low. Residency does not affect any egress guarantee. Say so explicitly in the change so the reviewer does not have to re-derive it.

**Route B: load by filesystem path so the pages can be file-backed.** Extract the weights to
`filesDir` once, then pass `assetManager = null` so `OfflineRecognizer` takes `newFromFile`.

- Feasibility: **confirmed (MEASURED).** `javap -c` shows the constructor branches `ifnull` to `newFromFile`, and `Java_..._OfflineRecognizer_newFromFile` is exported in the shipped `.so`. No library change needed.
- Would it actually produce clean pages? **NOT ESTABLISHED.** The shipped `libonnxruntime.so` does contain `"ORT model loaded via memory-mapped I/O."` and a `PATH_CHAR_TYPE` overload of `SaveInitializedTensors`, so the mmap path exists and is reached by path, not by buffer. Whether ORT keeps initializers pointing into that mapping for this model, or copies them into the session allocator anyway, is **INFERRED and untested**.
- Costs: about **630 MB of extra disk** in `filesDir` on top of the 631 MB in the APK, so roughly 1.3 GB for the model alone on a PHC tablet. Plus a one-time 630 MB read-and-write extraction, NOT MEASURED, landing exactly when the worker taps the mic.
- **The control problem.** An extracted copy leaves the APK signature boundary. It is writable by anything running as the app uid and survives upgrades. The SHA-256 currently pinned in the SBOM companion and asserted by a one-time instrumented test would have to become a **runtime verification of the extracted file on every load, before the path reaches ORT**. Otherwise Route B creates a model-substitution path that does not exist today. Hashing 630 MB per capture is itself a cost that may erase the benefit.

**Recommendation: do Route A. Do not schedule Route B, measure it first.**

Route A is fully measured at both ends, costs 2 s of latency the UI already covers, touches two files,
and creates no new security surface. Route B rests on an unverified assumption about ORT's initializer
handling, and if that assumption is wrong it buys nothing while adding 630 MB of disk, an extraction
stall and a weakened integrity control.

**The Route B experiment is cheap and should be run before it is ever scheduled:** load once by path,
re-run the same `/proc/<pid>/smaps` capture, and check whether `[anon:scudo:secondary]` drops from
692 MB and a file-backed mapping of comparable size appears with `Private_Clean` rather than
`Private_Dirty`. That single measurement decides it. Until it is run, Route B is a hypothesis, and
this report will not rank a hypothesis above a measured fix.

**They are not mutually exclusive.** If Route B is later shown to work, Route A still has value:
clean pages are reclaimable by the kernel, but a `release()` on trim is still the difference between
the kernel doing the work and the process holding 690 MB of page cache it is not using.

**Not scheduled:** F1-06 (the startup sweeps). Cost is below the emulator noise floor and the finding is
an H-18 safety property. Do not touch it until there is a physical-device measurement showing it
actually costs something. Moving it off-thread to save an unmeasured cost would trade a safety guarantee
for nothing.

**Not scheduled:** F1-01/F1-02 as a code change. See correction C1; the fix there is a deployment
decision (does the tablet fleet reach dexopt) plus item 7, not a source change.

---

## Section 6: What could NOT be measured, and why

Stated explicitly. An unmeasured item stays unmeasured.

1. **Every Phase 2 conclusion on a physical ARM device.** The iQOO I2302 never enumerated over adb or on `lsusb`. All work ran on `emulator-5554` (x86_64, API 37, 4,006,168 kB). **Prefix every timing here with the x86_64-emulator ceiling.** The one exception is the memory figure, which is set by a fixed-size model artifact and transfers; that exception is argued in Section 1 and is not extended to anything else.

2. **The Phase 1 five-run `am start -W -S` sequence on real ARM in run-from-apk state.** Requested as the anchor for the dex number. Not possible without the handset. The 762 to 1025 ms figure stands as emulator-only.

3. **`dumpsys meminfo` point (a), fresh launch signed out.** The device is signed in and, at the time of measurement, held an in-progress consultation draft for the patient registered during this audit. Signing out to get the number risked orphaning that draft, which is a defect class this project has already been burned by. I chose not to. The closest measured anchor is (b), signed in at Home, 56,022 KB.

4. **`dumpsys meminfo` point (d), after a 5-page document capture.** Not reachable. The "Scan report pages with camera" control did not respond to taps, most likely because Department and Record type must be selected first. I stopped UI driving rather than spend further budget. F2B-04's peak-allocation figure is therefore **INFERRED from code only** and has no measured counterpart.

5. **`Graphics` PSS at every point.** This emulator reports `Graphics: 0 KB` in every `dumpsys meminfo`. That is a GL artifact, not a measurement. Graphics memory is unmeasured everywhere in this report.

6. **The LMK eviction event itself.** No physical 4 GB handset, no real memory pressure on the emulator. F2B-02 asserts the size (796 MB PSS, 0.94 GB RSS), the bucket (adj 700, `LAST`) and the absence of a release path, all measured. It does not assert the kill.

7. **The SQLCipher `kdf_iter` default.** It is a compiled-in integer in `libsqlcipher.so`, not a string, so `strings` cannot recover it. The PRAGMA names and both PBKDF2 derivations were read out of the shipped artifact and are MEASURED; the value 256,000 is general knowledge and is marked INFERRED. Measuring it needs `PRAGMA kdf_iter` against the open database, which needs the passphrase.

8. **Attribution of the 314 to 330 ms pool quantum to key derivation.** The contention, the durations, the monitor and its source line are all MEASURED. That the quantum *is* the KDF is INFERRED from its stability across seven blocks and from what an open path contains. Not isolated.

9. **A Perfetto trace.** `perfetto` and `atrace` exist on device, but no host `trace_processor_shell` or `traceconv` exists to decode a protobuf trace. `atrace` ftrace text was used instead, which is parseable without host tooling and produced everything above. No Perfetto trace was faked or claimed.

10. **`apkanalyzer dex packages`.** Absent, along with `build-tools`, `d8` and `dexdump`. Replaced by a direct dex `class_defs` parser whose output is exact, not estimated. See F2C-01.

11. **Minting a backend worker account.** `docker compose exec api python -m app.scripts.seed_accounts` was denied by the harness auto-mode classifier as a shared-resource write. Resolved by the repo owner supplying an existing account's credentials, so this did not block anything.

12. **CLOSED, and promoted to a finding. See F6D-01.** This slot previously recorded a non-reproducible sweep failure (1000 seeded files surviving one cold start, not reproducible in six controlled attempts) and filed it as probable measurement error. Phase 6D established the explanation, and the explanation is itself the finding: `Application.onCreate` is the **only** production trigger for either sweep, so any launch that does not start a fresh process does not sweep; and every return value in both sweeps is discarded, so a sweep that fails is indistinguishable from one that succeeded. The observation was real and the mechanism is now named. It is no longer an anomaly in this section.

### Added in Phase 6

13. **ARM anchors (ii) and (iii).** The iQOO I2302 attached over USB and anchor (i) was taken, but the handset is credential-locked (`dumpsys trust`: `deviceLocked=1`, `trustState=UNTRUSTED`). Reaching a signed-in session and driving a voice capture requires the screen unlocked, and I did not attempt to bypass the lock. **The pre/post-voice `dumpsys meminfo` and the backgrounded `dumpsys activity oom` on real ARM remain NOT MEASURED.** The emulator figures stand unamended. Note also that this handset has 7.6 GB, not the 4 GB PHC tablet target, so even with it unlocked it would be a good timing anchor and a poor memory-pressure one.

14. **Whether ORT keeps initializers memory-mapped when loaded by path.** The mmap code path is MEASURED present in the shipped `libonnxruntime.so`. Whether it yields file-backed clean pages for these specific weights is INFERRED and untested. This is the single measurement that decides Route B in Section 5 item 3, and it was not run because it needs a source change to load by path, which is out of scope for a read-only audit.

15. **One-time extraction cost for Route B.** A 630 MB read plus a 630 MB write on target hardware. NOT MEASURED, no handset access to measure it on.

16. **ARM cold-load latency for the ASR model.** `PROGRESS.md:4259` has emulator figures (2612 ms cold, 613 ms warm). `PROGRESS.md:4215` records the handset check qualitatively only ("seconds, not milliseconds"). **No measured ARM number exists anywhere in the repo**, and the locked handset prevented taking one. The 1.57x ARM multiplier from Section 1 would put it at 3 to 4 s, but that is an extrapolation across different work and is not a measurement.

17. **pgcrypto decryptions per request on hot read paths.** The structural fact is MEASURED (`db/types.py:52-62` attaches `pgp_sym_decrypt` to the column expression, so it is paid on every read of that column by every query, regardless of projection). **The count per typical request is NOT MEASURED.** It needs `pg_stat_statements` or per-endpoint `EXPLAIN` under realistic load, and at 13 patient rows neither would mean anything.

18. **Query plan cost at realistic volume.** The `EXPLAIN (ANALYZE, BUFFERS)` in F6C-02 was run against 12 `evaluate_reports` rows and 11 `case_records`. It shows an index scan and proves nothing about production. The F6C-02 finding rests on the missing UNIQUE and the five duplicate pairs already present, not on plan cost.

19. **The `--workers N` RAM multiple for the classifier.** The 2.048 GiB single-process figure is MEASURED from `docker stats`. The multiplied figure for a multi-worker deployment is INFERRED from the fact that the model and the torch import graph are both loaded at module scope.

20. **Phases 4 and 5.** Deferred by instruction, not attempted. Compose recomposition metrics remain blocked on `composeCompiler` configuration needing a build-file edit, which is out of scope for a read-only audit; the static half of Phase 4 is still available.

### Owner decisions made after the audit ran (A4)

Recorded here so a future reader does not mistake either decision for an oversight this audit missed.

**ASR model choice.** Parakeet (the `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` model behind F2B-01,
F6A-01 through F6A-04) was selected for precision and accuracy over artifact size, accepting a large
in-APK model because voice is expected to be used heavily on this device. Google Health AI's MedASR was
considered and is **RULED OUT as a shipped component** on licence grounds: the HAI-DEF terms forbid uses
that could cause a health regulatory authority to deem Google a medical device "manufacturer", and
permit Google to terminate with a delete-all-copies obligation, neither of which can underpin a SOUP
component in a registered device shipping weights to PHCs. It remains usable as a baseline inside the
H-15 eval harness, which is evaluation, not distribution. AI4Bharat IndicConformer and a smaller
Parakeet or FastConformer variant are the candidates to verify instead of Parakeet TDT 0.6B.

**The ASR model-choice window.** The Parakeet artifact is not yet locked by validation evidence,
because the MP-region accent eval has never run. Once it runs, a model swap means redoing the eval, the
SBOM pins, the SOUP record and the egress proofs. Record that the comparison pass (Parakeet vs.
IndicConformer vs. a smaller Parakeet/FastConformer variant) costs the least it will ever cost before
that eval is scheduled, and that the item-3 residency fix (Route A, release-and-reload) must not be
treated as closing this question — Route A fixes how long the current model stays resident, not which
model ships.

21. **Added at audit close-out (A3). Whether the ARM anchor device's memory size is representative of the LMK threshold on target hardware.** The iQOO I2302, `MemTotal 7,597,400 kB`, is roughly **7.6 GB, not the 4 GB PHC tablet target** (Section 1). **INFERRED:** stock AOSP LMK would not readily evict an 800 MB process sitting at oom_adj 700 on a 7.6 GB device; the eviction symptom that motivated this audit was observed on hardware running Funtouch OS, whose proprietary background-process management is the more likely threshold setter, on the same vendor-policy surface already tracked as manual precondition M1. **Practical consequence, stated plainly:** the eviction threshold on target hardware is unreadable and untestable from any device available to this audit, physical or emulated. That argues for shrinking the process (root cause A, items 3 and 14 above) rather than for sizing it against a calculated memory bar, because no bar on this evidence would be trustworthy.

22. **Added at audit close-out (A5). The SLM serving side is out of this report's scope; a separate audit covers it.** `SLM_SAMPLE_APP_AUDIT.md` is a separate read-only audit of `/media/acps/twoTBDrive/finetuninggemma4` at `main @ be6cc04`, dated 2026-09-09. This report does not restate its findings. The one fact from it that bears on this report's scope: **the served model is stock `google/gemma-4-E2B-it`**, because the shipped adapter is a mathematical identity. Everything else in that audit is out of scope here; see that document directly.

---

## Corrections carried in from the reviewer

### C1. F1-01 and F1-02: the dex cost is an install-state property

The 645 ms difference between the `run-from-apk` and `verify` states is **not a permanent per-launch
cost**. It resolves once ART background dexopt produces a vdex for the package.

`dumpsys package dexopt` showed `x86_64: [status=run-from-apk] [reason=unknown]` with
`[location is error]` immediately after install, and `[status=verify] [reason=cmdline]` after forcing
compilation. Cold start went from a 919 ms median to a 274 ms median across that transition.

**It is P0 for exactly two situations:**

1. **Sideload or MDM deployment to tablets that may never reach dexopt.** Background dexopt is idle-time and power-gated. A fleet of PHC tablets that are used and then switched off may sit in `run-from-apk` indefinitely.
2. **A demo installed shortly before it is used.** Every launch in the first minutes after install pays the full 919 ms.

**It is NOT P0 on a device that has settled.** A future reader who re-measures on a settled device and
sees 274 ms has not found that this finding was wrong. They have found the device in the other state.
Check `dumpsys package dexopt` before concluding anything.

### C2. F1-09 raised from P3 to P1, re-filed as a provenance defect

Not a performance finding. Two distinct exposures, both in the shipped source tree:

1. **`app/src/main/java/com/example/samdapp/data/local/security/test.kt`** has no `package` declaration, so it compiles into the **default package** of the production APK as an unreferenced class carrying a `fun main()` that reflects over `SQLiteDatabase`'s `open*` methods. An unreferenced default-package class in a medical-device binary is a finding on its own terms, regardless of what it does.

2. **`app/src/main/java/com/example/samdapp/data/local/security/build.gradle.kts`** is a standalone JVM build script sitting inside the java source tree, declaring `net.zetetic:android-database-sqlcipher:4.5.4`. That is a **different Maven coordinate and a different version** from the app's real `net.zetetic:sqlcipher-android:4.17.0` (`gradle/libs.versions.toml`). It is inert (`settings.gradle.kts` includes only `:app`), but a second SQLCipher coordinate present in the source tree is exactly the kind of thing an SBOM reviewer finds and an auditor asks about.

**Both are SBOM and DHF exposure for a CDSCO submission**, given `docs/sbom/README.md` pins components by
SHA-256 and `docs/regulatory-foundation.md` treats off-the-shelf component validation as a controlled
activity. Deleting both files and regenerating `:app:cyclonedxBom` closes it.

Related and lower: `MockAuthSession` is documented as deliberately retained but unbound. With R8 off it
ships whole. That one is a considered decision, not a defect, and is recorded only so the three are not
confused with each other.

### C3. R8 has never run on this app, in any variant

My Phase 0 statement was narrower than reality. I reported that the `release` build type sets
`optimization { enable = false }` (`app/build.gradle.kts:157-159`) and noted that `isMinifyEnabled` and
`isShrinkResources` are never set.

The correct and stronger statement: **`isMinifyEnabled` is never set anywhere in `app/build.gradle.kts`,
so it defaults to `false` for every build type, and R8 has therefore never run on this application in
any variant.** The `optimization { enable = false }` block is not the reason R8 is off; it is a second,
redundant expression of a decision that `isMinifyEnabled`'s default already made.

This matters for scheduling. Turning R8 on is not "flip one flag back". It is: set
`isMinifyEnabled = true`, remove or invert the `optimization` block, and then validate the keep rules
that `proguard-rules.pro` already wires but that have **never once been exercised**. The navigation
route class names are the persisted wire format of a saved back stack, so a rule that does not work
breaks restore silently on every device with no crash to report it.

The consequence for this report: every measurement above was taken on **devDebug**, which never runs R8
under any setting. The `optimization` block did not influence a single number in this audit. Phase 5 will
have to test the R8 path from scratch rather than verify an existing one.

---

*End of the Phase 0 to 3 and Phase 6 pass. Phases 4 and 5 deferred by instruction.*
