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

**S-3, change control.** All five components are compiled into the APK. There is no
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
