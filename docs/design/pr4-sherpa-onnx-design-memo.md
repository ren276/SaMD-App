# PR 4 STEP 1 design memo: swap sherpa-onnx in behind `TranscriptionService`, close the off-device transmission risk, pin the SOUP, flip `VOICE_FIELD_IMPACT_ENABLED`

Run date: 2026-09-02. Read-only session. Branch `feat/asr-voice-gate-breadcrumbs`, HEAD `fab5625`.
Working tree: no tracked modifications. Two untracked paths only, `dashboard.html` and `scratchpad/`.
Nothing staged, committed or pushed. No file outside `scratchpad/` written. No `.env`,
`local.properties`, credential file or `BuildConfig` touched in any project. No emulator run, no
model downloaded, no build executed.

---

## 0. PRECONDITION DEVIATION, report before anything else

The task states PR 3 is fully merged, "3a refusal, 3b state, 3c UI + `VOICE_FIELD_IMPACT_ENABLED`
flag, 3d breadcrumbs on master". The repo says otherwise, in one respect.

| Sub-step | State in repo |
|---|---|
| 3a refusal | merged, PR #29 (`db0046b`) |
| 3b state model | merged, PR #30 (`e264fd9`) |
| 3c UI + flag | merged, PR #31 (`9a4dac6`), which is `master` HEAD |
| 3d breadcrumbs | **NOT on master.** Lives on this branch only: `a3bc61b` (emission) and `fab5625` (H-15 residual note), two commits ahead of `master` |

`git log --oneline master..HEAD` returns exactly those two commits. The flag is `false` on both
`master` and this branch (`config/FeatureFlags.kt:61`), so the safety premise is intact, and 3d
exists in code and is tested (`ConsultationVoiceGateBreadcrumbsTest`). The only thing that is not
true is "on master".

**Consequence for PR 4:** PR 4 edits `ConsultationViewModel.kt:39-41` and `:397-398`, which are
3d's lines. Branching PR 4 off `master` today would branch off a tree where those lines do not
exist. So: **merge the 3d PR before opening PR 4a**, or branch 4a off `feat/asr-voice-gate-breadcrumbs`
and accept the stacked-PR review cost. Recommend merging 3d first; it is a one-step unblock and
keeps every later diff readable.

Nothing else in the task's premises contradicts the repo. The rest of this memo is written on the
corrected picture.

---

## PART A: the model pick, against real sherpa-onnx artifacts

### A.1 What sherpa-onnx actually ships for streaming English

Verified from the sherpa-onnx pretrained-model documentation and the model repositories, not from
the research memo. Sizes are the documentation's own listings.

| Model directory | Training data | fp32 encoder | int8 encoder | int8 dec + joiner | License |
|---|---|---|---|---|---|
| `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | LibriSpeech (icefall PR #903) | 85M | **41M** | 527K + 253K | Apache-2.0 |
| `sherpa-onnx-streaming-zipformer-en-2023-06-26` | LibriSpeech (icefall recipe `...streaming-zipformer-2023-05-17`, PR #1058) | 250M | 68M | 1.3M + 254K | Apache-2.0 |
| `sherpa-onnx-streaming-zipformer-en-2023-06-21` | LibriSpeech **and GigaSpeech** | 337M | 179M | 1.2M + 253K | Apache-2.0 (same lineage) |
| `sherpa-onnx-streaming-zipformer-en-2023-02-21` | LibriSpeech | 338M | 180M | 1.3M + 254K | Apache-2.0 (same lineage) |

The 20M model is listed by the documentation's own "small models" page as one of the models
"suitable for resource constrained embedded systems", and it is the **only English-exclusive entry
on that page**. The others there are Chinese or Chinese-English bilingual.

### A.2 The pick, and the honest trade it makes

**Recommend `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, int8, Apache-2.0. About 42 MB of
weights.**

The research memo's recommendation was "streaming Zipformer transducer on sherpa-onnx, Apache-2.0
lineage". That is **confirmed, not revised**: the family, the runtime, the streaming transducer
decoding mode and the permissive license all hold. What the memo did not fix was *which* Zipformer,
and that choice is the whole of the size and accuracy argument:

- 42 MB (20M int8) versus 69 MB (en-2023-06-26 int8) versus 180 MB (en-2023-06-21 int8). The
  180 MB option is the best domain match, because GigaSpeech is spontaneous and varied speech
  rather than read audiobooks, and it is the one that is disqualified on size.
- **All four are LibriSpeech-rooted, which means read, largely US-accented speech.** The deployment
  is Indian-accented English in a rural PHC. Nothing in the sherpa-onnx English catalogue is trained
  on that. This is not a reason to pick a different one, it is a reason the eval set from research
  memo 5.2 must exist and must be run **before** the flag flips, and a reason not to promise an
  accuracy number in advance.

Escalation path if measured accuracy is unacceptable: `en-2023-06-26` int8 at 69 MB. Escalating past
that to the 180 MB GigaSpeech model is an APK-size decision, not an engineering one, and belongs to
the operator.

### A.3 Decoding mode and endpointing: the gate's exact requirement is met

**Streaming transducer (`OnlineRecognizer`), not offline.** sherpa-onnx ships endpoint detection in
the online recognizer config, with three documented rules:

- rule 1: endpoint after N seconds of trailing silence even with nothing decoded (default 2.4s)
- rule 2: endpoint after N seconds of trailing silence once something non-blank is decoded (default 1.2s)
- rule 3: endpoint after the utterance exceeds N seconds (default 20s)

This is precisely what the confirmation gate needs. PR 3's surface shows text **after** endpointing,
one suggestion, one confirm action. Endpointing gives that boundary from the engine rather than from
a timer the app invents.

**Calibration knob, do not ship the defaults blind.** Rule 2 at 1.2s will cut off a hesitant or
elderly speaker mid-thought, and a truncated narrative that is then confirmed is exactly the H-15
failure the gate exists to catch, only quieter. Rule 2 is also the dominant term in the
end-of-speech-to-suggestion latency budget (Part F.1). Ship rule 2 as a named constant with the
value chosen from the eval recordings, not from the library default.

### A.4 Indic swap-in: STOP AND REPORT, the research memo's premise does not hold as written

The task states, following the research memo, that "AI4Bharat IndicConformer, MIT, as-published ONNX"
fits the same runtime and the same interface. **Three parts of that are wrong today.**

1. **There is no official sherpa-onnx-ready IndicConformer export.** sherpa-onnx needs
   encoder/decoder/joiner plus `tokens.txt` (transducer) or a CTC model plus `tokens.txt`. AI4Bharat
   publishes `.nemo` checkpoints and plain single-file `model.onnx` plus `vocab.json`. The
   sherpa-onnx maintainers' own discussion thread on Indic support (#3199) contains no maintainer
   commitment to such an export, and points users at other models instead. The one working example
   is community-built and single-language: a Malayalam CTC-only conversion
   (`jeswinjestin/sherpa-onnx-nemo-ctc-indicconformer-malayalam`), produced by hand-generating
   `tokens.txt` from `vocab.json` after working around a `KeyError: 'dir'` when loading the `.nemo`
   checkpoint. So "as-published ONNX, no conversion" is false for the sherpa-onnx path: packaging
   work is required per language.
2. **The license is not uniformly MIT.** AI4Bharat's own model is MIT, but at least one widely
   used community ONNX republication is **CC-BY-4.0**. Whichever artifact is actually shipped is the
   one whose license binds, and that is the artifact that has to be pinned. This has to be settled
   per file at the time an Indic model is picked, not inherited from the upstream repo's headline
   license.
3. **It is not the same decoding mode.** IndicConformer is a ~120M-parameter offline hybrid
   CTC-RNNT, and the plain ONNX republications are roughly 493 MB per language. That is an
   `OfflineRecognizer`, not a streaming transducer, and at that size it is not a bundled-asset
   candidate at all without quantisation work.

**What this does and does not break.** It does not break the seam. sherpa-onnx supports offline
recognizers in the same library and the same JNI surface, so an Indic implementation still lands
behind an unchanged `TranscriptionService` and still returns `Result<String>`. What it breaks is the
"drop in the Indic weights, same code path" story. Treat Indic as **a later PR with its own model
selection and its own SOUP entries**, not a swap.

What sherpa-onnx does ship for Indic today, for whoever picks that up: Dolphin CTC models covering
Hindi, Tamil, Telugu, Gujarati, Punjabi, Marathi, Odia, Bengali and Kashmiri (no Malayalam, no
Kannada), and Whisper, which the research memo already recommends against on hallucination grounds
(JMIR 11.5%) and which is worse, not better, behind a confirmation gate.

PR 4 stays English-only. That is unchanged. The correction is to the claim about how cheap the Indic
follow-on is.

---

## PART B: module placement and the interface seam

### B.1 New and changed files

Following field-audit memo B.1's convention, which the repo already obeys.

| Path | Change |
|---|---|
| `app/src/main/java/com/example/samdapp/domain/transcription/TranscriptionService.kt` | **unchanged.** See B.3 |
| `app/src/main/java/com/example/samdapp/data/transcription/SherpaOnnxTranscriptionService.kt` | new, the on-device implementation |
| `app/src/main/java/com/example/samdapp/data/transcription/AndroidSpeechRecognizerService.kt` | **deleted.** See B.2 |
| `app/src/main/java/com/example/samdapp/di/MockBoundaryModule.kt:34-35` | binding target changes, one line, all flavors |
| `app/src/main/assets/asr/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/` | new, model assets |
| `app/build.gradle.kts` | sherpa-onnx dependency, ABI filters, asset no-compress |

Assets go under `assets/`, not `res/raw`, per B.1, and the directory name **is** the pinned model id
(Part E). sherpa-onnx reads model files straight from the `AssetManager` on Android, so no copy to
`filesDir` is needed and no writable copy of the weights ever exists.

Two build-file details that are easy to miss and expensive to discover on device:

- **ABI filters.** `app/build.gradle.kts` has no `abiFilters` today, because nothing native ships
  today. The sherpa-onnx AAR carries native libraries for `arm64-v8a`, `armeabi-v7a`, `x86_64` and
  `x86`, and ONNX Runtime with them. Unfiltered, all four ship in every APK. Recommend `arm64-v8a`
  plus `x86_64`, the second only so the instrumented tests can run on an emulator. Confirm the size
  effect by measuring the APK, do not estimate it.
- **Asset compression.** Compressed `.onnx` assets are reported to break the alignment the runtime
  needs, with a runtime crash rather than a clean error. The commonly cited fix is
  `androidResources { noCompress += listOf("onnx", "bin", "ort") }`. Flagged as **reported, not
  verified by this session**: confirm against the sherpa-onnx Android sample at build time.

New domain types from B.1 (`VoiceFieldSlot`, `VoiceCapture`) are **not** part of PR 4. PR 3 shipped
the slot as a `const val` string with a documented promote-when-a-second-slot-exists note
(`ConsultationViewModel.kt:36`), and PR 4 adds no second slot. Building those types now would be
speculative structure.

### B.2 `AndroidSpeechRecognizerService`: delete it, do not keep it as a fallback

**Recommend deletion in sub-step 4a.** Four reasons, in order of weight:

1. **A fallback that transmits is worse than no fallback.** The whole point of PR 4 is that "audio
   never leaves the device" becomes an architectural property. Retaining a flavored binding that
   reaches `createSpeechRecognizer` means the property is now conditional on build configuration,
   and every future reviewer has to re-derive which flavors are safe. The repo already has the
   precedent and it points this way: `UnavailableVitalsSource` returns an honest empty reading in
   staging and prod rather than degrading to mock data (H-13).
2. **It is unpinnable SOUP.** The recognizer version is whatever the device has. It cannot be given
   a version in the SBOM, cannot be given a hash, and cannot be validated. Part D exists to pin
   things; this component is unpinnable by construction.
3. **Deleting it is what makes the Part C proof possible.** Once the last call site is gone, "no
   off-device ASR path remains" becomes a grep that a test can run in CI. With the class retained,
   the strongest available claim is "not reachable in this flavor", which is an argument, not a
   proof.
4. **It is already half-broken.** `transcribe()` reads from a `ConcurrentHashMap` (`:32`, `:41-43`),
   so it fails after process death, and its `uri` is a synthetic `speech-session://<uuid>` that
   points at nothing. There is no working behaviour being preserved.

The cost of deleting: `SherpaOnnxTranscriptionService` becomes the single binding for **both**
`captureAudioAttachment()` and `transcribe()`, including the `TranscriptionScreen` path routed at
`AppNavHost.kt:320-326`. That path is dark (`VOICE_INPUT_ENABLED = false`) but it must still
compile and behave. The new implementation therefore honours the same contract: a synthetic URI
key, an in-memory transcript map, and **no audio file written**, matching research memo 1.4's
do-not-retain-the-audio rule. The process-death staleness carries over unchanged; it is pre-existing,
it is not PR 4's job, and it is noted here so nobody reads its survival as a regression.

### B.3 The interface does not change shape

`TranscriptionService` is two suspend functions returning `Result` (`TranscriptionService.kt:11-14`).
sherpa-onnx fits without touching it: capture microphone audio, feed 16 kHz float samples to the
`OnlineStream`, decode incrementally, and return the finalised text at the endpoint. 3a, 3b, 3c and
3d are untouched. The only PR 4 edits outside `data/transcription/` and `di/` are the two constant
values in Part E.

**The one place where a change could be argued, surfaced explicitly rather than assumed:** streaming
decoding produces partial hypotheses continuously, and the interface has nowhere to put them. Two
options:

- **(a) Keep partials internal. Recommended.** The gate shows one suggestion after endpointing, and
  that is the designed behaviour, not a limitation. The UI already has an `isRecordingVoice` state
  from 3b for the "listening" affordance. Interface unchanged, PR 4 stays a swap.
- **(b) Surface partials**, which needs a new callback or a `Flow<String>` on the interface, and
  therefore touches 3b's state model and 3c's UI. That is a **design change, not a swap**, and it
  would put live unconfirmed text on screen during capture, which is precisely the automation-bias
  surface the research memo's section 1.3 argues against.

Recommend (a). Listed in the DECISION GATE so it is a decision and not an omission.

### B.4 DI binding

sherpa-onnx is not a clinical mock, so it binds for every flavor in
`app/src/main/java/com/example/samdapp/di/MockBoundaryModule.kt:34-35`, replacing the
`AndroidSpeechRecognizerService` type in that one `@Binds`. Nothing moves to a flavor module. The
module's KDoc at `:21-22` names `AndroidSpeechRecognizerService` and needs updating with the class.

No dev-flavor canned-transcript double in PR 4. The instrumented test uses the real engine against a
bundled clip, which is a stronger test and less code.

---

## PART C: closing the transmission risk, which gates the flag

### C.1 Why the in-process engine closes it by construction

The exposure PR 0 diagnosed and E.2 of the PR 3 memo re-stated is specific:
`AndroidSpeechRecognizerService.kt:57` calls `SpeechRecognizer.createSpeechRecognizer(context)`,
which binds to whatever `RecognitionService` the device provides; `:58-61` never sets
`EXTRA_PREFER_OFFLINE`, whose documented default permits network recognition; and the app holds
`INTERNET`. Patient narrative may therefore reach a third-party processor with no data-processing
agreement and no H-11 coverage.

The sherpa-onnx path removes every element of that chain:

- **No IPC.** Recognition runs in the app process through JNI into the sherpa-onnx native library
  and ONNX Runtime. No `RecognitionService` is bound, no `Intent` is broadcast, no other package is
  involved. There is no vendor policy to trust because there is no vendor in the path.
- **No network client.** sherpa-onnx's own description is offline operation "without Internet
  connection". It opens no sockets for recognition.
- **No `RecognizerIntent`, no `createSpeechRecognizer`, no `isRecognitionAvailable`.** After B.2's
  deletion, those symbols do not appear anywhere in `app/src/main`.
- **The model is in the APK.** Weights are read from `assets/`, pinned by hash (Part D), and never
  fetched. No download-on-first-use, ever: that would reopen both the egress question and the ACP
  question in one move (Part D.5).

**One thing that is honestly NOT proof and must not be claimed as such:** the `INTERNET` permission
stays, because the app syncs to its own backend. Permission removal is unavailable as evidence. The
proof has to be about the ASR path specifically, which is what C.2 builds.

### C.2 How PR 4 proves it, three layers

**Layer 1, source-level absence, automated and permanent.** A test that scans `app/src/main` for
`createSpeechRecognizer`, `RecognizerIntent`, `SpeechRecognizer` and `isRecognitionAvailable` and
fails on any hit. Cheap, runs on the JVM in CI, and it is the regression guard: it fails the build
the day someone reintroduces a platform-recognizer fallback. This is the layer that converts
"we removed it" into a standing invariant.

**Layer 2, runtime egress assertion, automated on device.** Run one full recognition inside an
instrumented test with a `StrictMode.ThreadPolicy` installed on the recognition thread with
`detectNetwork()` and `penaltyDeath()`. Any socket the ASR path opens kills the test. This is the
strongest programmatic statement available: not "we saw no traffic", but "a socket would have
failed the build".

**Layer 3, physical device confirmation, operator-run.** Instrumentation cannot toggle airplane
mode without system permissions, so this stays a manual step: airplane mode on, one capture on the
impact field, transcript appears. Recorded as the SOUP validation record in the DHF (Part G.3).
This is the operator's outstanding Item 6 check, and note what has changed about it: under the
platform recognizer it was an **exploratory** check, where the burden was on evidence of no
transmission. Under sherpa-onnx it is **confirmatory**, because layers 1 and 2 already establish
the property by construction and layer 3 witnesses it.

### C.3 Ordering: the flag flips last, in the same PR series, never before the proof

**Recommend: one branch, two sub-steps, flag flip as the final commit of 4b.**

- 4a lands the engine, the assets, the deletion, the DI binding, the SOUP entries and the
  transcription test, with `VOICE_FIELD_IMPACT_ENABLED` **still false**.
- 4b lands the three egress layers and, only after they are green, the flag flip and the
  controlled-doc drafts.

The flag then cannot flip in a tree where the engine is absent, and the flip is its own reviewable
commit with the egress evidence sitting directly beneath it in the same diff. Splitting 4b into a
separate follow-up PR is acceptable and slightly better for review focus, but only if 4a's PR
description states plainly that the feature is still dark, exactly as PR 3's sub-steps did.

---

## PART D: SOUP and SBOM

### D.1 The structural finding, which comes before the item list

`docs/sbom/README.md` states the SBOM is "generated from the resolved Gradle dependency graph, not
hand-maintained", and `app/build.gradle.kts:184-193` scopes `cyclonedxBom` to
`releaseRuntimeClasspath`. That convention has a hole PR 4 is the first change to fall into:

**Model weights in `assets/` are not a Gradle dependency and will never appear in a generated SBOM.**
The single most safety-relevant SOUP item PR 4 introduces is invisible to the mechanism that exists
to record SOUP items. `docs/regulatory-foundation.md:198` marks the SOUP/SBOM gap closed and
explicitly conditions that on the convention continuing to be honoured; PR 4 is where the convention
stops being sufficient on its own.

PR 4 therefore proposes, for operator sign-off:

1. A hand-maintained companion, `docs/sbom/model-soup-<YYYY-MM-DD>-v<versionName>.json`, in
   CycloneDX 1.6 with the model as a `machine-learning-model` component (the schema supports that
   type and a model card, which is the right home for the training-corpus facts).
2. An amendment to `docs/sbom/README.md` recording that the generated SBOM is necessary but no
   longer sufficient, and that a release SBOM is now the generated file **plus** the model
   companion.

Both drafted PROPOSED, per the standing docs rule. Neither written by this session.

### D.2 The SOUP items to pin, five of them

**1. sherpa-onnx runtime.** License Apache-2.0. Latest release at the time of writing is **v1.13.7,
published 2026-09-01**, whose assets include `sherpa-onnx-1.13.7.aar`. Pin the exact version and the
SHA-256 of the AAR actually consumed.

  Sourcing, and it matters for the SBOM: if the AAR is consumed as a **Maven coordinate**, CycloneDX
  picks it up automatically with hashes and a purl, and item 1 needs no manual work. If it is dropped
  into `libs/` as a file dependency, it will not appear properly and becomes a second manual entry.
  A Maven coordinate of the form `com.k2fsa.sherpa.onnx:sherpa-onnx-android` is cited in third-party
  write-ups; **this session could not verify it on Maven Central** (the artifact page returned no
  version rows). Resolve this at build time. Prefer the Maven coordinate if it resolves; otherwise
  consume the GitHub release AAR and add the manual entry.

**2. ONNX Runtime.** License MIT, shipped inside the sherpa-onnx AAR as native libraries, therefore a
distinct component with its own version and its own CVE surface. The sherpa-onnx Android build
documentation states **1.17.1**; that page is not necessarily current for v1.13.7, and this session
**could not verify** the version bundled in the v1.13.7 AAR (the cmake file path checked returned
404). Read it off the consumed AAR at build time and pin the observed value. Do not copy 1.17.1 into
the SBOM on this memo's authority.

**3. Model weights.** `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, Apache-2.0, distributed
from the k2-fsa `asr-models` release tag and mirrored on Hugging Face
(`csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`), exported from icefall PR #903.
Pin **SHA-256 per shipped file**, computed from the artifacts actually placed in `assets/`:

  - `encoder-epoch-99-avg-1.int8.onnx` (41M)
  - `decoder-epoch-99-avg-1.int8.onnx` (527K)
  - `joiner-epoch-99-avg-1.int8.onnx` (253K)

**4. `tokens.txt`, as its own pinned item.** Not folded into item 3, for the reason the task names
and which is worth restating in the SBOM entry itself: a mismatched tokens file produces **plausible
wrong text, not an error**. It is a small file whose corruption is silent and whose failure mode is
exactly H-15's, so it gets its own SHA-256 and its own line.

**5. Training corpus provenance, recorded on the model card, not shipped.** LibriSpeech, which is
released under **CC BY 4.0**. The weights are Apache-2.0 and the corpus is CC BY 4.0, and those are
different obligations. **Decision item for the operator:** whether the attribution obligation
attaching to the corpus reaches a shipped derived model, and if so where the attribution appears
(open-source licences screen, or the SOUP record only). Not a blocker for 4a, and not something this
memo should decide.

Note what is deliberately **absent** from this list: no entry for the platform speech recognizer.
That is the point of B.2. An unpinnable component cannot be given a row here, and shipping a
component that cannot be given a row is the thing the SOUP discipline exists to prevent.

### D.3 Proposed SBOM entry structure, for sign-off, not for writing

Shape only, matching the CycloneDX 1.6 conventions the existing `sbom-2026-07-20-v1.0.json` already
uses (`type`, `bom-ref`, `group`, `name`, `version`, `hashes`, `licenses`). Values marked `<...>`
are to be filled from the artifacts actually consumed.

```
components:
  - type: library
    bom-ref: "pkg:maven/com.k2fsa.sherpa.onnx/sherpa-onnx-android@1.13.7?type=aar"
    name: sherpa-onnx-android
    version: "1.13.7"
    licenses: [ Apache-2.0 ]
    hashes: [ { alg: SHA-256, content: "<sha256 of the consumed aar>" } ]
    description: "On-device ASR runtime. Replaces the platform SpeechRecognizer; no network path."

  - type: library
    bom-ref: "pkg:maven/com.microsoft.onnxruntime/onnxruntime-android@<version>"
    name: onnxruntime-android
    version: "<read from the consumed sherpa-onnx AAR>"
    licenses: [ MIT ]
    hashes: [ { alg: SHA-256, content: "<sha256>" } ]
    description: "Inference engine bundled inside the sherpa-onnx AAR. Tracked separately for CVE purposes."

  - type: machine-learning-model
    bom-ref: "model/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    name: sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
    version: "2023-02-17"
    licenses: [ Apache-2.0 ]
    hashes:
      - { alg: SHA-256, content: "<encoder-epoch-99-avg-1.int8.onnx>" }
      - { alg: SHA-256, content: "<decoder-epoch-99-avg-1.int8.onnx>" }
      - { alg: SHA-256, content: "<joiner-epoch-99-avg-1.int8.onnx>" }
    modelCard:
      considerations:
        trainingData: "LibriSpeech (CC BY 4.0). Read, predominantly US-accented English."
        limitations: "Not trained on Indian-accented English or on spontaneous clinical narrative."
      properties:
        - { name: "decoding", value: "streaming transducer, int8" }
        - { name: "source", value: "icefall PR #903; k2-fsa asr-models release" }
        - { name: "frozen", value: "true; changes only with an app release" }

  - type: data
    bom-ref: "model/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/tokens.txt"
    name: tokens.txt
    version: "2023-02-17"
    licenses: [ Apache-2.0 ]
    hashes: [ { alg: SHA-256, content: "<tokens.txt>" } ]
    description: "Tokenizer vocabulary. A mismatch yields plausible wrong text rather than an error, so it is pinned separately."
```

### D.4 Where the hashes come from, and when they are checked

Compute at asset-vendoring time and record in the companion file. **Recommend additionally a
build-time or first-load verification**, cheap version: the instrumented test asserts the SHA-256 of
each asset matches the pinned value. That turns the SBOM entry from a claim into something CI
re-checks. A runtime check on every app start would cost a hash of 42 MB at launch and buys little,
since the APK is signed; the test-time check is the right rung.

### D.5 ACP: the frozen bundled model does not open the gap

Stated explicitly, because it is the reason to ship a bundled frozen model rather than an updatable
one.

`docs/quality/qms-overview.md:45` records the Algorithm Change Protocol as TODO, "required before
any post-deployment update to the kernel model". A model that is **compiled into the APK, pinned by
hash, and changed only by shipping a new app release** is a **design change under normal change
control**: it goes through the same review, the same SBOM regeneration, the same release. It is not
a post-deployment model update, and it does not trigger the ACP gap.

The property that keeps this true is that there is no update path other than an app release.
Therefore: **no download-on-first-use, no model CDN, no silent refresh, no remote config selecting a
model.** Any of those would convert the model into a post-deployment-updatable component, reopen the
ACP TODO, and simultaneously reopen the network question Part C just closed. If such a path is ever
proposed, it is a regulatory change first and an engineering change second. **This memo proposes
none.**

---

## PART E: `asrModelId` and `asrModelVersion`

PR 3d ships `ASR_MODEL_ID_PLATFORM_RECOGNIZER = "android.speech.SpeechRecognizer"`
(`ConsultationViewModel.kt:41`) and a literal `null` version (`:398`), with the KDoc at `:39-40`
already pointing at PR 4 as the change that fills them.

**Proposed values:**

| Key | PR 4 value |
|---|---|
| `asrModelId` | `"sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"` |
| `asrModelVersion` | the runtime version that produced the transcript, e.g. `"sherpa-onnx-1.13.7"` |

Rationale for the split: the model directory name already carries the model's own date, so repeating
it as the version says nothing. What varies **independently** of the model id, release to release, is
the runtime, and the audit question these two fields exist to answer is "which code plus which
weights produced this text". Model id names the weights, model version names the code.

Stricter alternative, if the operator wants the audit row to identify the exact bytes:
`asrModelVersion = "1.13.7+<first 12 hex of the encoder SHA-256>"`. It is unambiguous and it is
ugly. Recommend the simple form, with this noted as the upgrade if a second build of the same model
ever ships.

**Structural change to the payload: none.** `impactVoicePayload` (`ConsultationViewModel.kt:391-403`)
keeps its key set, its ordering and its null-for-absent convention. PR 4 changes two constants and
nothing else. The KDoc at `:383` says the null version matches a "not available yet" convention, and
that sentence is what needs rewording once the value is real.

**One test edit follows mechanically:** `ConsultationVoiceGateBreadcrumbsTest.kt:85-86` asserts the
platform id and `assertNull` on the version. Both become `assertEquals` against the new constants.
Keep the assertions on the constants' *values*, not on the constants themselves, or the test stops
testing anything.

---

## PART F: performance and failure behaviour on the real device

### F.1 Latency, and where the budget actually goes

Target from the research memo: end of speech to suggestion under about **1.5s** on the target Pixel.

The important structural point: with streaming decoding, almost none of that budget is inference.
Audio is decoded incrementally as it arrives, so at the moment speech ends the decoder is already
nearly caught up, and the remaining work is the final chunk plus finalisation. **The dominant term
is the endpoint silence rule itself.** Rule 2's default of 1.2s of trailing silence consumes most of
a 1.5s budget before the engine has done anything.

That has a direct consequence for how PR 4 measures: the honest number is **from the last speech
sample to the suggestion being displayed, including the endpoint wait**, because that is the number
the worker experiences. Measuring only "endpoint fired to text ready" would produce a flattering
number that has nothing to do with the user's experience.

Measurement: an instrumented test on the physical Pixel that feeds the eval clips, timestamps with
`System.nanoTime()` at the last fed sample and at the final result, and reports p50 and p95 across
the set. Not an average over three runs on an emulator.

**If the target is missed**, in order:

1. **Tune rule 2 down.** The largest single lever, and the most dangerous: shortening trailing
   silence cuts off hesitant speakers, and a truncated narrative that gets confirmed is an H-15
   event. Tune against the eval recordings, never against a developer speaking fluently.
2. **Threads and provider settings.** `numThreads` at 2 to 4 on a modern Pixel. Free.
3. **Not a smaller model.** The 20M int8 model is already the smallest English streaming model
   sherpa-onnx publishes. There is no next rung down.
4. **Not offline batch.** Switching to an `OfflineRecognizer` makes latency strictly worse: nothing
   is decoded until the utterance is complete. It is the wrong direction and is listed here only to
   close it off.
5. **If the target is still missed, accept it and say so.** Show the existing "listening" state from
   3b honestly rather than trading accuracy for a number. A slower correct suggestion is a better
   clinical artefact than a faster wrong one, and the confirmation gate means the worker is reading
   it either way.

### F.2 Failure edges: 3b's honest-failure paths still fire

3b defined two failure edges, ASR error and empty transcript, both emitting `VOICE_FIELD_REJECTED`.
Both survive, because the seam is unchanged: `SherpaOnnxTranscriptionService` returns
`Result.failure(...)` for engine or model-load failure and `Result.success("")` for silence, and
`ConsultationViewModel` cannot tell which implementation produced them.

Two behavioural notes worth having in the PR description:

- **The empty-transcript edge becomes the common one.** The platform recognizer tended to raise an
  error code on no-speech; sherpa-onnx will simply decode nothing and return an empty string. The
  blank-transcript edge stops being a corner case and becomes the normal outcome of a mis-tapped
  mic. It already emits `VOICE_FIELD_REJECTED` and already refuses to write, so nothing changes
  except which branch runs most often. The instrumented test should cover it deliberately.
- **Model-load failure is a new failure mode.** A missing, truncated or wrongly-compressed asset
  fails at recognizer construction, not at capture. Surface it as `Result.failure` on the first
  capture attempt rather than crashing at DI time, so the UI shows the existing honest-failure path
  instead of the app dying. Hash verification (D.4) is what catches the corrupted-asset case in CI
  before a device ever sees it.

### F.3 Model load: lazy, off the main thread, once

**Lazy on first use, not at app start.** Loading at start would pay a cold-start cost on every
launch for a feature that is off by default and reachable on exactly one field, and in a
flag-off build the model must never be loaded at all.

Shape: `@Singleton`, a `Mutex`-guarded suspend initialiser so two concurrent captures cannot build
two recognizers, construction on `Dispatchers.IO` or `Dispatchers.Default`, never on the main
thread. Once built, the recognizer is retained for the process lifetime; each capture creates and
releases only a lightweight `OnlineStream`.

The ceiling, named rather than hidden: about 42 MB of weights plus the ONNX Runtime arena stays
resident once a worker uses voice, for the life of the process. Measure the actual RSS delta on the
Pixel during 4a and record it. If it turns out to matter, the upgrade path is releasing the
recognizer on `onTrimMemory`, which trades a reload delay for the memory back. Do not build that in
4a on speculation.

---

## PART G: tests, sub-steps, docs

### G.1 Tests

| Test | Kind | Asserts |
|---|---|---|
| `SherpaOnnxTranscriptionServiceTest` | androidTest, must run on emulator or device | A bundled known clip transcribes to text containing the expected content words. **Not** exact-string equality: assert on content words, or the test becomes a WER tripwire that fails on a comma |
| Silence-clip case, same class | androidTest | Empty transcript comes back as `Result.success("")` so 3b's blank edge fires; a corrupt or absent asset comes back as `Result.failure` and does not crash |
| Asset hash check | androidTest | SHA-256 of each shipped asset equals the value pinned in the SBOM companion (D.4) |
| No-platform-recognizer source scan | JVM unit test | No `createSpeechRecognizer`, `RecognizerIntent`, `SpeechRecognizer` or `isRecognitionAvailable` anywhere in `app/src/main` (C.2 layer 1) |
| StrictMode egress assertion | androidTest | One full recognition under `detectNetwork()` + `penaltyDeath()` on the recognition thread (C.2 layer 2) |
| Latency measurement | androidTest, physical Pixel | p50 and p95 of last-sample to final-result across the eval clips (F.1). Reported, not asserted, until a threshold is agreed |
| Flag-on UI test | Compose test, 4b | With `VOICE_FIELD_IMPACT_ENABLED = true` the mic and suggestion surface render. 3c's existing tests cover the false case |

The repo's standing rule that a write surviving a failure path is asserted against the persisted row,
not the return value, is untouched by PR 4: 3a's `ConsultationVoiceUnconfirmedRefusalTest` already
does that against a real Room database and PR 4 does not go near the write path. It stays green as a
regression check.

### G.2 Sub-steps

| Step | Content | Flag | Model |
|---|---|---|---|
| **4a** | Dependency and ABI filters, assets vendored, `SherpaOnnxTranscriptionService`, DI binding swap, `AndroidSpeechRecognizerService` deleted, `asrModelId`/`asrModelVersion` filled, SOUP entries and the SBOM companion drafted, transcription and hash and silence tests | **still false** | Opus for the SOUP/SBOM structure and the deletion argument; Sonnet for the asset vendoring, DI wiring and constant swap once this memo is signed off |
| **4b** | Source-scan test, StrictMode egress test, operator airplane-mode run recorded, **then** the flag flip, then the controlled-doc drafts | **false, then true, in that order, as the last commit** | Opus. The flip and its evidence are the safety argument itself |

Recommended split, and the reason: 4a is a swap that changes no user-visible behaviour and can be
reviewed on its engineering merits. 4b is a **risk decision**. Keeping them apart means the flag flip
is reviewed as a flag flip, with the egress evidence in the same diff, rather than buried under 40
files of asset vendoring.

### G.3 Docs, per the standing rule

**Working docs, inline, same commit as each step.** A `PROGRESS.md` entry per sub-step: what landed,
the flag state, test counts, and for 4a an explicit statement that the feature is still dark. 4b's
entry records the measured latency numbers and the airplane-mode result.

**Controlled docs, drafted PROPOSED, never marked approved by the build:**

- `docs/sbom/` companion file and the `README.md` amendment (D.1). At 4a.
- **SOUP validation record in the DHF** for all five items of D.2, including the airplane-mode
  witness as the validation evidence for the runtime. At 4b.
- **H-15 residual**, `docs/quality/risk-management-file.md:39`. This is the real one. The row today
  says the gate control **exists in code** and is **not user-reachable**, and that "the residual risk
  to a user is therefore still unchanged". At 4b that sentence stops being true in both halves at
  once: the gate becomes functioning, and the transmission half of the hazard is closed by
  construction rather than by a flag. Draft it as a substantive residual-risk re-evaluation, not an
  append.
- **`docs/requirements/intended-use-statement.md` §i.** The device gains a user-reachable voice input
  path on one field. That is a change to what the device does, which is the thing an intended-use
  statement exists to state.

The last two are **controlled-doc changes needing sign-off in their own right**, as PR 3's F.2
already flagged. The flag flip is the moment they come due. A build step must not mark either as
approved.

---

## DECISION GATE

Six items, plus the precondition. Nothing below is decided by this memo.

**0. PRECONDITION.** 3d (`a3bc61b`, `fab5625`) is **not on master**; master HEAD is `9a4dac6` (3c).
PR 4 edits 3d's lines. Merge 3d before opening 4a, or branch 4a off this branch and accept a stacked
PR. Recommend merging first.

**1. The English model and its license.** `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`, int8,
**Apache-2.0**, about 42 MB of weights, streaming transducer, endpointing available. The research
memo's streaming-Zipformer recommendation is **confirmed**. Trained on LibriSpeech, therefore read
US-accented English, therefore the eval set must run before the flag flips. Escalation if accuracy
fails: `en-2023-06-26` int8 at 69 MB. Separately, the corpus is **CC BY 4.0** while the weights are
Apache-2.0, and whether the attribution obligation follows the derived model is an operator call.

**2. `AndroidSpeechRecognizerService`: DELETE, in 4a.** It is unpinnable SOUP, its retention makes
the egress property conditional on build flavor, it is already broken across process death, and
deleting it is what makes the C.2 layer-1 proof a grep instead of an argument.

**3. Flag flip: same PR series, separate sub-step, last commit of 4b**, after the three egress layers
are green. Never in a tree without the engine.

**4. SOUP items and SBOM structure.** Five items: sherpa-onnx runtime (Apache-2.0, v1.13.7 or current,
AAR SHA-256), ONNX Runtime (MIT, version read off the consumed AAR, **not** assumed to be 1.17.1),
the model weights (Apache-2.0, SHA-256 per file), `tokens.txt` (separately pinned, because a mismatch
is silent), and the LibriSpeech corpus provenance on the model card. Structure drafted in D.3. **The
generated SBOM cannot see the model**, so a hand-maintained CycloneDX companion plus a `README.md`
amendment is required, and that is a change to a convention `docs/regulatory-foundation.md:198`
depends on.

**5. Latency fallback.** Target about 1.5s, measured from last speech sample to displayed suggestion,
including the endpoint wait. If missed: tune endpoint rule 2 against real recordings (largest lever,
most dangerous), then threads. There is **no smaller English streaming model**, and offline batch is
strictly worse. Last resort is to accept the latency, not to trade accuracy for it.

**6. Interface shape change: NONE recommended.** `TranscriptionService`'s two suspend functions are
unchanged and 3a through 3d are untouched. The one live question is whether streaming **partial
results** should be surfaced to the UI. Recommend **no**: it needs a new callback on the interface,
touches 3b and 3c, and puts live unconfirmed text on screen, which is the automation-bias surface the
research memo argues against. Listed so it is a decision, not an omission.

**Unverified facts, flagged rather than invented:** the Maven Central coordinate for the sherpa-onnx
Android AAR could not be confirmed (the artifact page returned no version rows); the ONNX Runtime
version bundled in the v1.13.7 AAR could not be read (the documentation page's 1.17.1 may be stale);
the `noCompress` requirement for `.onnx` assets is reported by third parties, not confirmed against
the official sample. All three resolve at build time and none changes a decision above.

---

## Sources

- [sherpa-onnx, k2-fsa](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0; offline operation; Android support)
- [sherpa-onnx v1.13.7 release, 2026-09-01](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest) (AAR assets)
- [Zipformer transducer models, sherpa docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) (model names, training data, file sizes)
- [Small online models, sherpa docs](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/small-online-models.html) (the 20M English model as the mobile candidate)
- [csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17) (Apache-2.0; icefall PR #903; int8 sizes)
- [csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) (Apache-2.0)
- [Build sherpa-onnx for Android, sherpa docs](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html) (distribution, ABIs, ONNX Runtime 1.17.1 as documented)
- [Endpointing, sherpa docs](https://k2-fsa.github.io/sherpa/ncnn/endpoint.html) and [c-api.h](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/c-api.h) (rule 1/2/3 defaults)
- [Indic ASR discussion #3199, sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/discussions/3199) (no official IndicConformer export; Dolphin and Whisper alternatives; community Malayalam CTC conversion)
- [ai4bharat/indic-conformer-600m-multilingual](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) and [OpenVoiceOS/ai4bharat-indicconformer-ml-onnx](https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-ml-onnx) (architecture, sizes, licensing spread)
