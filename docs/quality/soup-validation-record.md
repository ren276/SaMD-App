# SOUP validation record: on-device ASR (sherpa-onnx, ONNX Runtime, Parakeet int8)

> **PROPOSED, AWAITING OPERATOR SIGN-OFF.** Not an approved controlled document. Nothing in this
> file is marked approved by the change that created it.

**Scope.** The five components of the hand-maintained model companion
`docs/sbom/model-soup-2026-09-02-v1.0.json`. They are SOUP under IEC 62304 and none of them appear
in the generated CycloneDX SBOM, because the weights are files under `app/src/main/assets/`, the
runtime is a file dependency (`app/libs/sherpa-onnx-1.13.7.aar`, nothing is published to Maven
Central under `com.k2fsa.sherpa.onnx`), and ONNX Runtime ships as native libraries inside that AAR.
Identity, versions, SHA-256 hashes and licences live in the companion; this record is what was
validated, how, and what is still open.

**Note (2026-09-17).** Section 5 covers a second, unrelated SOUP inventory (CameraX, H-18 Build 3b
Option A), added to this file for convenience rather than as a new file. Everything above this
note, and the framing that follows it, is ASR-specific: CameraX is Maven-published and DOES appear
in the generated CycloneDX SBOM, the opposite of what the Scope paragraph above says about the ASR
components, so section 5 does not reuse this paragraph's "why this record exists" reasoning.

**Note (2026-10-16).** Section 6 covers a third inventory, the on-device SLM (dev flavor only), and
is PROPOSED on its own terms. Like section 5 it does not reuse the ASR framing above.

**Why this record exists now.** Until PR 4b these components were compiled into the APK but
unreachable: both voice flags were `false`. `VOICE_FIELD_IMPACT_ENABLED` is now `true`, so the ASR
SOUP is live on one field and its validation state stops being hypothetical.

## 1. Requirements placed on this SOUP

| # | Requirement | Why it is a requirement and not a preference |
|---|---|---|
| S-1 | Recognition performs no off-device transmission | H-15's original cause: the deleted platform recognizer transmitted audio to a Google service. The whole gate architecture assumes recognition is local |
| S-2 | The bytes that ship are the bytes that were assessed | A silently swapped or truncated model is a design change nobody reviewed |
| S-3 | The component set changes only by shipping a new app release | Keeps a model change a design change under normal change control, rather than a post-deployment model update engaging the Algorithm Change Protocol gap in `qms-overview.md` |
| S-4 | Failure is honest: an absent or unloadable model fails visibly, never silently degrades | A silent failure on a clinical documentation aid is indistinguishable from "the worker said nothing" |
| S-5 | Licence obligations of the shipped artifacts are discharged | CC BY 4.0 on the weights attaches at APK distribution, not at the flag flip |

## 2. Validation evidence, per requirement

**S-1, no off-device transmission.** Four layers, all green:

- **L2.3**, reflection scan: no platform speech recognizer class reachable in the built bytecode.
- **L3.1**, decode: a real transcript is produced by the local engine (`AsrEgressTest`).
- **L3.2**, byte-level egress: `txDelta = 0 B` and `rxDelta = 0 B` measured across a full decode.
- **L3.3**, `StrictMode`: no network call detected on the transcription path.
- Supporting static evidence: `NoPlatformRecognizerSourceScanTest` (source-level absence in
  `app/src/main`) and `TranscriptionPathHasNoNetworkDependencyTest` (no network dependency reaches
  the transcription path in the dependency graph).

Layers L2.3 and L3.1 to L3.3 ran on an x86_64 emulator. **Runtime validation on the shipped ABI:**
**L3.4** (a capture writes no file) and **L3.5** (airplane-mode transmission witness) ran on the
**arm64 iQOO I2302 (arm64-v8a) on 2026-09-02 and passed**, with a real microphone. Transcription
completes with the device unable to reach any network, which is the strongest available runtime
evidence that the sherpa-onnx runtime and the Parakeet weights do not need one. The
x86_64-not-arm64 residue recorded against the earlier layers is therefore **closed**.

**S-2, byte identity.** The model companion carries a SHA-256 per shipped file.
`SherpaOnnxTranscriptionServiceTest` pins the same digests (`PINNED_ASSET_SHA256`) and fails if the
assets on device differ, so a swapped or partially copied model is a red test, not a quiet
behaviour change.

**S-3, change control.** The shipped runtime and model files are compiled into the APK; the
training-corpus provenance record is controlled documentation and is not shipped. There is no
download-on-first-use, no model CDN, no silent refresh and no remote config selecting a model, and
`TranscriptionPathHasNoNetworkDependencyTest` is what keeps that property from eroding. Any of
those additions would reopen both the ACP question and the egress question at once.

**S-4, honest failure.** The absent-asset test asserts a visible failure when the model is missing.
The capture path routes a blank transcript on a successful recognition to the honest-failure edge
rather than presenting an empty suggestion (`ConsultationViewModel`, gate memo A.2).

**S-5, licences.** Apache-2.0 (sherpa-onnx), MIT (ONNX Runtime) and CC BY 4.0 (weights,
`tokens.txt`, provenance record) are recorded in the companion and surfaced in-app by the Open
Source Licences screen shipped in PR 4b-1 (`OpenSourceLicensesScreenTest` asserts the CC BY 4.0
row renders). The attribution obligation is discharged.

## 3. Per-component notes

| Component | Role in the device | Validation state |
|---|---|---|
| `sherpa-onnx-android` 1.13.7 (Apache-2.0) | JNI + native ASR runtime; replaces the platform recognizer | S-1 to S-4 evidenced above. Vendored from a GitHub release asset; no Maven coordinate resolves, so version pinning is by vendored file plus SHA-256 |
| `onnxruntime-android` 1.27.1 (MIT) | Inference engine, `libonnxruntime.so` inside that AAR | Exercised by every decode in L3.1 to L3.5. Tracked as its own component because it has its own version and its own CVE surface. **Gap: no CVE monitoring is in place for it** (see section 4) |
| `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` weights (CC-BY-4.0) | The acoustic/transducer model, offline whole-utterance | S-2 pinned by digest; S-1 witnessed in airplane mode on arm64. **Accuracy for the deployment population is not established** (see section 4) |
| `tokens.txt` (CC-BY-4.0) | SentencePiece vocabulary, 1025 entries, paired with the encoder | S-2 pinned by digest; a mismatched vocabulary would surface as garbled output in L3.1 |
| Training-corpus provenance record (CC-BY-4.0) | Not shipped; records what the weights are derived from and carries the licence obligation | Documentary. Its purpose is that the CC BY 4.0 obligation and the training-data claim travel with the weights |

## 4. Open items, carried not closed

1. **Accuracy of the shipped int8 artifact for the intended speakers is not established.** The
   published 6.42 average WER is the full-precision model on A100-class hardware, not this int8
   ONNX export on an ARM SoC, and the benchmark suite is not a rural MP PHC population. An
   accented-speech evaluation of the shipped artifact is an open action and a precondition of
   **clinical deployment**, not of the dev flag flip. Full argument and both gaps:
   `docs/quality/risk-management-file.md` H-15.
2. **No CVE monitoring for the two runtime components.** Recording a version is not watching it.
   sherpa-onnx and ONNX Runtime need a periodic advisory check tied to release, and none exists.
3. **No performance requirement.** First-capture latency was measured (2612 ms cold, 613 ms warm,
   x86_64 emulator) and is reported, not asserted: there is no agreed threshold to assert against.
4. **The egress test is not reliable in a full-suite run.** The capture-and-decode test in
   `AsrEgressTest`, which carries the L3.2 byte-level egress evidence above, fails when the
   whole instrumented suite runs and passes when it runs alone. A gate that flakes is a gate people
   learn to ignore, which is why this is recorded here rather than left as folklore. **The cause is
   not the microphone permission**, and the failure message's first clause misleads on that point:
   `RECORD_AUDIO` is granted (`dumpsys` reports `granted=true`, and the test passes alone under both
   `am instrument` and a single-class Gradle run), so no permission rule affects it. The leading
   explanation is cross-test microphone contention through the process-wide recognizer this class
   shares with `SherpaOnnxTranscriptionServiceTest`, which cancels a capture mid-read on purpose; an
   `AudioRecord` not fully released before the next class runs would present exactly as observed and
   never in isolation. That is a hypothesis, not a diagnosis: the release path has not been
   instrumented and no ordering has been forced. The likely fix is test isolation or an explicit
   release-and-await in the shared test fixture's teardown. **The fix must never be a relaxed
   assertion.** This test witnesses a zero-egress property of vendored native code as a
   pre-distribution gate, and relaxing it to silence a flake trades the flake for undetected egress
   in code nobody here wrote. The assertion's diagnostic message has been corrected to say all of
   this; the assertion condition is untouched.

## 5. CameraX (H-18 Build 3b Option A, in-process document capture)

> **PROPOSED, AWAITING OPERATOR SIGN-OFF.** Not an approved controlled document. This section was
> added 2026-09-17, independently of sections 1 to 4 above, which it does not touch.

**Scope.** Four components: `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`
and `camera-compose`, all version 1.6.2, all Apache-2.0, all Maven-published. Unlike the ASR
components above, these ARE Maven coordinates and DO appear in the generated CycloneDX SBOM, so
identity, exact version and hash are already covered by that automated pipeline rather than needing
a hand-maintained companion file. This section is the validation record IEC 62304 also requires:
what these components are used for, what was checked, and what is still open.

**Why this record exists now.** `docs/design/capture-process-death-memo.md` traces a field-reported
lost captured page to the previous camera-capture mechanism (`ActivityResultContracts.TakePicture`,
an external camera app) backgrounding this process and making it a low-memory-killer target. CameraX
replaces that external hand-off with an in-process viewfinder (`DocumentCameraCapture`), and its
validation state stops being hypothetical the moment that code ships.

### 5.1 Requirements placed on this SOUP

| # | Requirement | Why it is a requirement and not a preference |
|---|---|---|
| C-1 | The capture path performs no off-device transmission | CameraX is a local camera-hardware abstraction with no network dependency of its own; a document-capture pipeline processing PHI-adjacent clinical imagery must not silently acquire one through a library upgrade |
| C-2 | A captured page's bytes never reach disk as plaintext | H-18's core PHI-at-rest guarantee (control (xi)); CameraX is the layer that produces the bytes this guarantee is built on top of, so its own API must not write a file this app never asked for |
| C-3 | The component set changes only by shipping a new app release | Same reasoning as the ASR record's S-3: a silent version change is a design change nobody reviewed |
| C-4 | Camera failure is honest: an unavailable camera fails visibly, never silently | A silently-failed capture that produces no error and no page is indistinguishable from "the worker forgot to take the photo" - a clinical document could be missing with nothing to say why |
| C-5 | Licence obligations of the four components are discharged | Apache-2.0 attribution is owed at distribution, the same posture as every other Apache-2.0 component already listed on the Open Source Licences screen |

### 5.2 Validation evidence, per requirement

**C-1, no off-device transmission.** Not independently instrumented the way the ASR record's L3.2
byte-level egress measurement was (this SOUP has no `StrictMode`/traffic-delta test of its own).
Argued instead from what CameraX's public API surface is: `Preview`, `ImageCapture`,
`ProcessCameraProvider` and `CameraXViewfinder` are a hardware-abstraction and rendering layer with
no networking classes anywhere in this app's use of them, and no permission beyond `CAMERA` is
requested for this path. **Open**: no automated test asserts this the way the ASR SOUP's egress
tests do; recorded as an open item in 5.4 rather than a pass.

**C-2, no plaintext on disk.** `ingestPageWritesNothingUnderCacheDir` (`DocumentCaptureAssemblyTest`,
instrumented) snapshots `cacheDir` before and after a real `ingestPage` call and asserts no file was
created anywhere in it - the direct replacement for the old staging-file-specific check, and a
stronger claim (no file anywhere, not just the one staging file gone). `DocumentCameraCaptureTest`
(unit) asserts the `ImageProxy` the frame arrives in is closed on both the success and the failure
path, so the platform-owned buffer behind the capture is released promptly either way.

**C-3, change control.** The version is pinned once, in `gradle/libs.versions.toml`
(`camerax = "1.6.2"`), read by all four component declarations; a version bump is an ordinary
reviewed dependency-catalog change, not a silent transitive upgrade of any one of the four.

**C-4, honest failure.** A5 (`docs/design/capture-process-death-memo.md` amendment A5): provider
init failure, no back camera, or the camera already in use elsewhere all route to
`onCameraUnavailable`, which sets an explicit error state the capture surface renders instead of
the viewfinder - never a silent fallback and never a shutter that does nothing. Covered by
`camera unavailable is recorded and blocks further pages` (`ConsultationDocumentCaptureTest`, unit,
the ViewModel-state half of this contract).

**C-5, licences.** Apache-2.0 for all four components, recorded on the Open Source Licences screen
(`OpenSourceLicensesScreen.kt`, a single "CameraX" entry covering all four - they share one licence
and one release cadence, so one row is the accurate representation, not four identical ones).

### 5.3 Per-component notes

| Component | Role in the device | Validation state |
|---|---|---|
| `camera-core` 1.6.2 (Apache-2.0) | `ImageCapture`, `Preview`, `ImageProxy` - the capture and frame-delivery API this app calls directly | C-2 and C-4 evidenced above |
| `camera-camera2` 1.6.2 (Apache-2.0) | Camera2-backed implementation CameraX binds to at runtime; this app never calls it directly | Exercised transitively by every test above; no test targets it independently |
| `camera-lifecycle` 1.6.2 (Apache-2.0) | `ProcessCameraProvider`, lifecycle-bound bind/unbind | Unbind-on-dispose is exercised manually (`DocumentCameraCapture`'s `DisposableEffect`), not covered by an automated test - see 5.4 |
| `camera-compose` 1.6.2 (Apache-2.0) | `CameraXViewfinder`, the Compose interop surface for the live preview | Rendered in `DocumentCameraCapture`; no dedicated Compose UI test exists for it - see 5.4 |

### 5.4 Open items, carried not closed

1. **Transitive AndroidX dependencies below these four are not independently validated or
   registered as their own SOUP components.** They are captured by the generated CycloneDX SBOM,
   which is necessary but not sufficient for a IEC 62304 SOUP determination on any one of them.
   **Marked pending QA countersign**: whether each transitive dependency needs its own SOUP entry,
   or whether the SBOM plus this section's coverage of the four direct dependencies is sufficient,
   is a QA/regulatory-process call this section does not make.
2. **No automated egress test for C-1.** Argued from API surface, not measured, unlike the ASR
   record's L3.2. If the operator wants this to the same evidentiary standard as the ASR SOUP, a
   `StrictMode`/traffic-delta test analogous to `AsrEgressTest` would need writing.
3. **No live process-death run through the real capture screen.** The claim in H-18 control (xxiii)
   is narrower than it may read: **the process stays foreground during capture by construction, and
   the automated `oom_score_adj` check asserts this; a live deep-stack process-death run is still
   owed** - the automated check (`scripts/process_death_check.sh`) exercises a standalone capture
   window, not the full sign-in-and-navigate-to-capture path, because the backend that path needs
   was not available when this section was written.
4. **No Compose UI test exercises `DocumentCameraCapture` directly.** Its CameraX plumbing is
   verified structurally (compiles against the real 1.6.2 API, binds/unbinds without a crash in
   manual runs); the byte-extraction half is unit-tested (`DocumentCameraCaptureTest`); the
   viewfinder rendering and shutter-disabled-while-ingesting behaviour have no automated UI test.

## 6. On-device SLM (dev flavor only): LiteRT-LM runtime, Gemma 4 E2B artifact, remote model

> **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED.** Not an approved controlled document. This
> section was added 2026-10-16 (slice S8, `docs/design/on-device-slm-memo.md` section 13.3) and does
> not touch sections 1 to 5.

**Scope.** Four entries:

1. **LiteRT-LM Android runtime**, `com.google.ai.edge.litertlm:litertlm-android:0.18.0`
   (`gradle/libs.versions.toml:29,37`), Maven-published (Google Maven), declared through
   `devImplementation` only (`app/build.gradle.kts:332`). It is in the dev APK and in no staging or
   prod APK.
2. **Gemma 4 E2B `.litertlm` artifact**, the model the local engine runs. It is in no APK: it is
   delivered to a dev phone by `adb` into the app's private files directory
   (`docs/design/on-device-slm-memo.md:771-787`) and pinned by digest in code
   (`app/src/dev/java/com/example/samdapp/data/slm/LocalSlmEngine.kt:42`).
3. **The remote model as served**, `google/gemma-4-E2B-it` bf16 behind the backend's SLM proxy. It
   was SOUP with no entry before this section (F-21, `docs/design/on-device-slm-memo.md:156-157`).
4. **Dev-only transitive versions** that LiteRT-LM pulls in: `kotlin-stdlib` 2.4.0,
   `kotlin-reflect` 2.4.0 and `gson` 2.14.0.

**Why this record exists now.** Ruling R-DEV made the readback reachable in the dev flavor on the
local engine (`SLM_READBACK_ENABLED` true in dev only, `app/build.gradle.kts:179,200,208`;
`docs/design/on-device-slm-memo.md:1312-1315`). The runtime and the artifact therefore execute on a
dev phone, and the remote model is the parity reference the local one is judged against.

### 6.1 Requirements placed on this SOUP

| # | Requirement | Why it is a requirement and not a preference |
|---|---|---|
| L-1 | The model bytes that run are the bytes that were assessed | A swapped or partially copied artifact carries a different template, vocabulary or stop ids (H-48) |
| L-2 | The local path performs no off-device transmission | The local engine's purpose is on-device inference; a network dependency acquired through a runtime upgrade would be unreviewed egress of a clinical prompt |
| L-3 | The runtime and the artifact change only by a reviewed change | A version or artifact change is a design change; the parity evidence below is valid for exactly one runtime version and one artifact digest |
| L-4 | Failure is honest: a missing, corrupt or unloadable model, or an over-length prompt, refuses; it never degrades or truncates | H-25, H-53 |
| L-5 | The runtime never reaches staging or prod | Its validation below is dev evidence only; prod is tested on its own classpath (S1-L) |
| L-6 | Licence obligations are known and discharged before distribution beyond the dev team | Apache-2.0 attribution, and the model's own terms (G-7) |

### 6.2 Validation evidence, per requirement

**L-1, byte identity.** Artifact: full SHA-256 before every engine start, refusal on mismatch
(`LocalSlmEngine.kt:42,380`), and the derived runtime cache is checked against a manifest written
at clean release (`app/src/dev/java/com/example/samdapp/data/slm/LocalSlmRuntimeCache.kt:49,78`,
C-2). On the Pixel the file is 2,588,147,712 B with the pinned digest (verified on device
2026-10-16, STOP S7 install check). The served model id passes the identity gate only through its
exact allowlist entry (`app/src/main/java/com/example/samdapp/domain/slm/SlmStreamSanitizer.kt:99,107`,
G-4). Runtime: the AAR sha256 `706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f`
and POM sha256 `3c631cd8e04fe4289818d41675c0f6b1f26a7a37db63606699a51e2c95695a54` are recorded
(`docs/design/on-device-slm-memo.md:179-180`) but **not enforced by the build** (no Gradle
dependency verification file exists under `gradle/`); see 6.4.

**L-2, no off-device transmission.** `LocalSlmSourceScanTest` fails if any file of the engine
package names `okhttp3`, `retrofit2`, `java.net` or `android.net`; `LocalSlmOfflineSmokeTest`
(`app/src/androidTestDev/java/com/example/samdapp/slm/LocalSlmOfflineSmokeTest.kt:13-20`) first
asserts no active network, then runs the smoke. On the Pixel with airplane mode on and Wi-Fi and
Bluetooth off, both probes were exact with finish STOP on GPU and on CPU; with the network on, the
test failed at its assertion (commit `140c51e`). This is a runtime witness on one device, not a
byte-level egress measurement like the ASR record's L3.2.

**L-3, change control.** The runtime version is pinned once in the catalog
(`gradle/libs.versions.toml:29`), and `LiteRtLmIsDevOnlyTest`
(`app/src/test/java/com/example/samdapp/config/LiteRtLmIsDevOnlyTest.kt:22,39`) fails if the library
is declared other than through `devImplementation` or at another version. The artifact digest is a
code constant (`LocalSlmEngine.kt:42`); a different artifact is a code change.

**L-4, honest failure.** Missing file: UNAVAILABLE `artifact_missing`; wrong file: UNAVAILABLE
`artifact_digest_mismatch`, never loaded (`docs/design/on-device-slm-memo.md:794-795`); init bound
60 s then UNAVAILABLE with a latch (`LocalSlmEngine.kt:174,351-356`); over-length prompts refused by
the prefill backstop or the runtime's own error, both emitting nothing
(`LocalSlmEngine.kt:441-443,456-457`; on-device `LocalSlmLengthVerificationTest`,
`app/src/androidTestDev/java/com/example/samdapp/slm/LocalSlmLengthVerificationTest.kt:53-99`).

**L-5, dev only.** Declaration guarded as above. APK scans at the merge of S9 (2026-10-16, merge
commit `916b722`): the staging and prod debug APKs contain no `com/google/ai/edge/litertlm` class,
no `liblitertlm_jni.so`, and no `LocalSlmEngine`, `LiteRtLmRuntime` or `LocalSlmModule`.

**L-6, licences.** Runtime: Apache-2.0 (POM, `docs/design/on-device-slm-memo.md:181`). Artifact:
the model card says apache-2.0 and the repository has no LICENSE file; both facts are recorded and
a confirmation against the upstream Gemma 4 terms is filed for the sign-off batch (G-7,
`docs/design/on-device-slm-memo.md:1239`). **Not discharged:** the Open Source Licences screen
lists sherpa-onnx, ONNX Runtime and CameraX but not LiteRT-LM or Gemma
(`app/src/main/java/com/example/samdapp/presentation/licenses/OpenSourceLicensesScreen.kt:49,58,67`);
see 6.4.

**Functional validation (parity, all four entries).** Prompt path: token-id and rendering parity
120 of 120 between the remote tokenizer and the artifact's, and trim parity 28 of 28 on desktop and
on device. Refusal parity PASS. Content gates FAIL, attributable to the language gate on Devanagari
questions (operator verdict V-0, `docs/design/on-device-slm-memo.md:1319`; v3 CPU numbers in the
risk file, H-47). Repeatability: CPU 28 of 28 comparable byte-identical, GPU 9 of 30, so the engine
selects CPU only (V-1, `docs/design/on-device-slm-memo.md:1320`; H-55).

### 6.3 Per-component notes

| Component | Role in the device | Validation state |
|---|---|---|
| `litertlm-android` 0.18.0 (Apache-2.0), AAR sha256 `706cbb8a...595f`, tag `v0.18.0` at `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27` (`docs/design/on-device-slm-memo.md:176-181`); `liblitertlm_jni.so` arm64-v8a 22,155,680 B, x86_64 26,353,160 B (:212-215); `NEEDED` system libraries only (:217-218); 16 KB aligned (:236) | Kotlin API and JNI runtime for the local engine, dev flavor only | S1: builds and packages in dev only (commits `de26a8d`, `fa204f5`). S2: initialises on the Pixel on GPU and on CPU with the AAR alone, so `litert-gpu` was not added (Y-4; GPU cold init 30,640 ms, CPU 2,355 ms, `docs/design/on-device-slm-memo.md:403-404`; the catalog has no `litert-gpu` entry, `gradle/libs.versions.toml:29,37`). Offline proof and parity as in 6.2. Only the CPU backend is selected (V-1) |
| Gemma 4 E2B artifact, `litert-community/gemma-4-E2B-it-litert-lm` at `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`, `gemma-4-E2B-it.litertlm`, 2,588,147,712 B, sha256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, not gated; embedded template 11,995 B sha256 `02b3091a...f629` (`docs/design/on-device-slm-memo.md:1102-1108`) | The model the local engine runs, with its bundled chat template (G-1') | L-1 pinned by digest before every start. Vocabulary and stop ids {1, 50, 106} established offline (G2, G-3', `docs/design/on-device-slm-memo.md:1245`); rendering parity 120 of 120 on device. Licence per G-7, confirmation filed. Parity verdict V-0 |
| Remote `google/gemma-4-E2B-it` at `3e22461f65e89153144f8adb70e3b8c2cc9845a7`, bf16 weights sha256 `2db5482b20d746879bb3ef79b5203e9075a2e2b98f54ec7c2f281c1477ddc550`, served by transformers 5.5.0 and torch 2.4.1+cu124, chat template 18,569 B (`docs/design/on-device-slm-memo.md:96,145-149,1109-1112`) | The generation service behind the backend's SLM proxy (all flavors; unreachable in staging and prod while the flag is false), and the parity reference | The device identity gate pins the model id exactly (`SlmStreamSanitizer.kt:89,107`). G-6 token parity 120 of 120; the server run is the reference of the parity comparison and fails its own language gate on 22 of 34 Devanagari answers (filed as a readback-quality finding, V-4). Licence not recorded in this section; PROPOSED: extend the filed G-7 confirmation against the upstream Gemma 4 terms to this entry |
| `kotlin-stdlib` 2.4.0, `kotlin-reflect` 2.4.0, `gson` 2.14.0 (dev resolution) | Transitive dependencies of `litertlm-android`; dev only | S1-L (`docs/design/on-device-slm-memo.md:1272`): dev resolves these while prod resolves `kotlin-stdlib` 2.3.21, no `kotlin-reflect`, and `gson` 2.10.1, and prod stays tested on its own classpath at every hash. **Filed:** align `gson` and Kotlin deliberately, each in its own PR, before LiteRT-LM leaves dev |

### 6.4 Open items, carried not closed

1. **The SBOM is not regenerated in this PR.** The controlled CycloneDX SBOM is generated from
   `devReleaseRuntimeClasspath` (`app/build.gradle.kts:363`, `docs/sbom/README.md:73-76`), so its
   next regeneration will list `litertlm-android` 0.18.0 and the dev transitive versions above,
   none of which ship in a staging or prod build. That the SBOM tracks the dev classpath is an
   existing filed item (`PROGRESS.md:5495`); regenerate and review with the next SBOM pass.
2. **No model companion entry for the artifact.** The companion convention
   (`docs/sbom/README.md:17-40`) covers SOUP the generated SBOM cannot see in a release. The
   `.litertlm` is in no APK today (adb delivery to dev phones only), so the convention does not
   yet apply; production artifact delivery (Y-10, deferred) must add one.
3. **The AAR hash is recorded, not enforced.** Resolution is by version from Google Maven with no
   dependency verification metadata; a republished artifact at the same version would not be
   caught by the build.
4. **Licences not surfaced.** LiteRT-LM and Gemma are not on the Open Source Licences screen, and
   the G-7 confirmation is outstanding. Both are owed before any distribution beyond the dev team.
5. **No CVE monitoring for the runtime**, the same gap section 4 item 2 records for the ASR
   runtimes.
6. **Validation is one device, one runtime version, one artifact.** Pixel 11 Pro only; field phones
   are unmeasured (Y-12). The GPU backend is not selected and needs its own determinism
   investigation and parity run before re-enablement; the Tensor G6 NPU artifact is a follow-up
   that must measure repeatability first (Y-18, V-1).
