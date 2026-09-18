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

**Why this record exists now.** `scratchpad/capture-process-death-memo.md` traces a field-reported
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

**C-4, honest failure.** A5 (`scratchpad/capture-process-death-memo.md` amendment A5): provider
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
