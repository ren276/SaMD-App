# On-device SLM on the Tensor G6 NPU: design memo (STOP N0)

Status: DESIGN, AWAITING OPERATOR RULINGS (section 9). Nothing here is built yet.
Branch: `feat/on-device-slm-npu`, cut from `feat/on-device-slm` at `a270074` (PR #81, unmerged).
Date: 2026-10-26.

This is the follow-up PR ruled in Y-18 (on-device SLM memo 3.7): the Google Tensor G6 artifact,
with its own pin and its own parity run, after the generic path. It is step 1 of the order agreed
with the operator on 2026-10-26: (1) NPU on dev with a repeatability gate, (2) prompt template
`slm-readback-v2` (V-4), (3) the product-wide facility-scope PR, (4) artifact delivery (Y-10),
then staging enablement. The remote engine stays (operator, 2026-10-26: "keep remote fallback").

Tag convention as in `docs/design/on-device-slm-memo.md`: VERIFIED is a file:line, a command and
its output, or a document at a pinned commit; INFERRED gives its reason; UNVERIFIED needs the
device. LiteRT-LM citations are to `v0.18.0` = `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`. Files
under `runtime/` that were not in the earlier source fetch were fetched at that commit into
`/media/sandesh/extra-ssd/samd-slm-parity/litertlm-src-b2f686e2-npu/`.

---

## 1. What the NPU does and does not give

| Expectation | Finding |
|---|---|
| Faster | Likely, UNVERIFIED. Gallery 1.0.20 on the Pixel: "OK" in 140 ms on TPU against 527 ms on CPU, BP echo 1.1 s against 2.3 s (on-device SLM memo 3.7, Gallery sampling defaults, single runs, so INFERRED only). Real numbers come from the N6 batches. |
| Longer context | **No.** The G6 artifact's KV cache is static at 4096 tokens; the generic artifact we run on CPU is compiled for 32003 (2.2). Both exceed what we use: the context is 2560 because the server refuses prompts over 2048 tokens (SAMD-SLM-8003) and refusal parity needs the same cap (Y-7', Y-20). A longer context would need a server change first and is out of scope. |
| More variability in power | Variability of *output* is a hazard, not a gain: the same approved record must give the same text (H-55, M-2). GPU was dropped for 9 of 30 repeatability (V-1). The NPU decodes by greedy argmax inside the executor (2.3), which is deterministic by construction if the NPU arithmetic is; that is measured on the device, and gated (N-4). Power and thermal headroom are measured in N6. |
| Use all the juice, within the app's limits | The engine keeps the S7 release policy (idle 5 minutes, trim at BACKGROUND), the thermal refusal at SEVERE (Y-15) and single-flight. The NPU frees the CPU cores that the CPU backend now holds for about 10 s per readback. |

---

## 2. Verified facts

### 2.1 The artifact

- HF `litert-community/gemma-4-E2B-it-litert-lm` at the pinned commit
  `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` lists `gemma-4-E2B-it_Google_Tensor_G6.litertlm`,
  3,313,938,293 B, LFS sha256 `f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7`
  (VERIFIED, `GET /api/models/.../tree/b3ca0d2f...`). The repo head has moved to
  `1caa527c1e88b55f046fcd8d2b4265606680663d` (lastModified 2026-10-23); this memo stays on the pin.
- Downloaded to `/media/sandesh/extra-ssd/samd-slm-parity/model/`; `sha256sum` gives the same
  digest (VERIFIED, `model/sha256-g6.txt`).
- Sections (VERIFIED, scratch reader `litertlm_sections.py` over the header flatbuffer of
  `schema/core/litertlm_header_schema.fbs`): format 1.6.0 (generic: 1.5.0); 14 sections.
  - `SP_Tokenizer` 4,689,013 B, sha256 `e594c8a9...02ef03`: **byte-identical** to the generic
    artifact's tokenizer section. So the G-4 vocabulary equivalence and the G-5 / T1 tokenizer
    facts carry over unchanged.
  - `LlmMetadataProto`, decoded with `protoc --decode=litert.lm.proto.LlmMetadata` against
    `runtime/proto/llm_metadata.proto` at the pin: start token 2, stop tokens {1, 50, 106}, the
    same `llm_model_type.gemma4` block, and a `jinja_prompt_template` **identical** to the
    generic artifact's (12,587 characters, string-equal). The one difference: G6 declares no
    `thought` channel. We pass `channels = emptyList()` (Y-19), so it changes nothing.
  - Neither artifact sets `max_num_tokens` or `sampler_params` in its metadata.
  - `tf_lite_prefill_decode` 1,123,447,584 B (generic: 818,275,184 B) plus `tf_lite_aux` and
    `tf_lite_mtp_aux` sections the generic artifact does not have: a different compiled model.
    INFERRED: NPU-quantized and AOT-compiled for the G6, so content parity with CPU cannot be
    assumed and the parity run is repeated in full.

### 2.2 Context

- The G6 decoder's KV cache inputs are static: `kv_cache_k_0` shape `[1, 1, 4096, 256]` in the
  `decode`, `prefill_128` and `verify` signatures; the only prefill signature is `prefill_128`
  (VERIFIED, scratch reader `tflite_signatures.py`). Generic: `[1, 1, 32003, 256]`, with
  `prefill_1024` and `prefill_128`.
- On a static NPU model the executor takes the model limit, then lowers it to
  `EngineConfig.maxNumTokens` when that is smaller (`llm_litert_npu_compiled_model_executor.cc:2393-2405`,
  VERIFIED). Our 2560 is under 4096, so it is respected; a value over the model's limit would be
  silently replaced by the model limit with a WARNING log only (`:2396-2399`, `:2482-2486`).
- The over-context refusal is the same: `Input token ids are too long` is raised in `tasks.cc:552`
  and `:884`, before the executor, for every backend (VERIFIED), so the R9-a pin still holds. The
  NPU executor's own checks (`:1279-1284` prefill, `:825-826` decode, "Reached maximum number of
  tokens") give other text, which maps to ENGINE_ERROR, also a refusal.

### 2.3 Sampling

- For NPU the engine leaves the sampler type `TYPE_UNSPECIFIED` (`engine_settings.cc:400-403`,
  VERIFIED). An external sampler runs only when `SessionConfig.UseExternalSampler()` is set
  (`serial_execution_manager.cc:189-205`); its default is false (`engine_settings.h:377`).
- The NPU executor samples by greedy argmax itself: `DecodeNonSpeculative` calls
  `ApplyGreedySampling` on the decode logits (`llm_litert_npu_compiled_model_executor.cc:895-945`,
  VERIFIED). No `SamplerParameters` field is read by the NPU executor (VERIFIED, grep).
- Consequence: G-2' (every sampler field set explicitly) stays in the conversation config and is
  harmless, but on NPU it is not the mechanism that makes decoding greedy. The record must say so;
  the repeatability gate (N-4) is what proves determinism.

### 2.4 Speculative decoding

- C-3 holds on NPU: MTP runs only when `enable_speculative_decoding` is set
  (`:2843-2877`); otherwise `DecodeNonSpeculative` (`:1028-1033`) (VERIFIED). We pass false.

### 2.5 Backend selection and native libraries

- Kotlin `Backend.NPU(nativeLibraryDir)` and `Backend.GOOGLE_TENSOR` (`"GOOGLE_TENSOR_ARTISAN"`)
  both exist (`Config.kt:152-160`, VERIFIED). The directory reaches the executor as the LiteRT
  dispatch library directory (`jni/litertlm.cc:546-554`, `SetLitertDispatchLibDir`, VERIFIED).
  `Backend.NPU` is the `Backend::NPU` path whose executor is read above; `GOOGLE_TENSOR_ARTISAN`
  has no executor in the fetched sources (INFERRED: a separate, not yet used path).
- `com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.0` is on the SSD: arm64-v8a
  only, `libLiteRtCompilerPlugin_google_tensor.so` 939,376 B and `libLiteRtDispatch_GoogleTensor.so`
  579,024 B, no Java (VERIFIED, `unzip -l`). `litertlm-android` 0.18.0 bundles its LiteRT inside
  `liblitertlm_jni.so` and declares no `litert` dependency (VERIFIED, pom). **UNVERIFIED:** that
  dispatch 2.3.0 is ABI-compatible with the LiteRT inside `liblitertlm_jni.so` 0.18.0. The
  strings carry no LiteRT version (VERIFIED, `strings`); only an init on the device can tell.
- `minSdk` is 26 (`app/build.gradle.kts:92`) and `packaging` sets no `jniLibs` option
  (`:251-255`), so AGP keeps native libraries uncompressed inside the APK and does not extract
  them. **UNVERIFIED:** whether the dispatch loader finds a library that is not a file under
  `nativeLibraryDir`. If not, the dev variant needs legacy packaging (N-8).

---

## 3. Design

### 3.1 Accelerator and artifact

- `LocalAccelerator` gains `NPU`; `engineConfig` maps it to
  `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)` (N-1).
- An engine start is a list of plans, each `(accelerator, artifact sha256)`, tried in order at
  start only (memo 4.6 rule unchanged: no fallback mid-generation). NPU uses the G6 artifact, CPU
  the generic one. The sha256 check before every start, the content-addressed path
  `filesDir/slm/<sha256>.litertlm` and the derived cache dir `cacheDir/slm/<sha256>/` with its
  C-2 manifest all apply per artifact unchanged.
- Identity: the served model id is built from the digest actually loaded
  (`localServedModelId`), so the G6 artifact gets its own id and needs its own explicit allowlist
  entry next to the generic one (G-4 rule: never loosened). `answeredBy().accelerator` says NPU.
- Audit: C-7's `gpu_init_failed` generalises to the accelerator that failed (`npu_init_failed`,
  a boolean, never error text) when NPU is tried and the start falls back (N-2).

### 3.2 Selection gate

Until the device slices pass (section 5), CPU stays the default and NPU is constructible only with
an investigation opt-in, exactly as GPU is now (`allowUnselectedGpu`, V-1). NPU becomes the
default by a ruling on the N5 verdict, not by this PR's merge (N-5).

### 3.3 Repeatability as a hard gate

The comparator gains H6: on the selected local backend, every repeatability pair that completed
on both runs is byte-identical (30 of 30 comparable). Any differing pair fails. "Not comparable"
pairs (a run not ok) are re-run, as the CPU g-033 and g-037 were (V-3 step). This is the gate V-1
asked for before any non-CPU backend is selected (N-4).

### 3.4 Untouched

The seam, the prompt, the template version, the 2048 cap, the backstop, the thermal refusal, the
release policy, the facility check, staging and prod (still `RemoteSlmEngine`, no LiteRT class or
library in their APKs, guarded by `LiteRtLmIsDevOnlyTest` and the APK scans), the backend.

---

## 4. Hazards (PROPOSED, AWAITING OPERATOR SIGN-OFF)

- **H-48** extended: a second pinned artifact, same controls (sha256 before every start, per
  artifact cache manifest).
- **H-52** extended: an NPU init failure falls back to CPU at init only; `npu_init_failed` and the
  accelerator in every audit row.
- **H-55** extended: repeatability is a hard gate per selected backend (H6).
- **H-25** extended: NPU PSS, timings and thermal measured from the host (M-1) in N6.
- **H-56** (new): two artifacts take about 5.9 GB of app storage. If the phone fills, the app can
  fail to save a clinical record. Control here: none (dev only, operator-copied files). The
  delivery PR (step 4, Y-10) must check free space before any download and never evict clinical
  data. Filed as a dependency of step 4.

SOUP section 6 gains `litert-npu-runtime-google-tensor` 2.3.0 (Apache-2.0 per its bundled
`LICENSE`, to confirm in the sign-off batch) and the G6 artifact pin.

---

## 5. Slices

| Slice | Content | Needs the phone |
|---|---|---|
| N1 | `devImplementation` of the NPU runtime; dev-only packaging if N-8 requires it; staging and prod APK scans still free of every LiteRT library | no (the scan), yes (whether packaging is needed) |
| N2 | `LocalAccelerator.NPU`, per-accelerator artifact plans, allowlist entry, `npu_init_failed`, opt-in guard; JVM tests and mutation rows | no |
| N3 | Comparator H6 repeatability gate; Python tests and mutation rows | no |
| N4 | Device: NPU init, offline proof, rendering 120 of 120, trim 28 of 28, over-length band and over-context on the G6 artifact | yes |
| N5 | Device: full golden parity run on NPU, 30-prompt repeatability, the comparator verdict | yes |
| N6 | Device: D4-style performance, thermal and host PSS batches on NPU | yes |
| N7 | RMF, SOUP and DHF rows (PROPOSED) | no |

Order: N2, N3 and N7 now; N1's code now; N4 to N6 when the phone is back.

---

## 6. Device checklist for N4 to N6 (when the phone is back)

1. Copy the G6 artifact to `files/slm/f86c7c19...95b7.litertlm` with the C-5 modes.
2. NPU init succeeds through `Backend.NPU(nativeLibraryDir)`; if it fails on a missing dispatch
   library, apply N-8 and retry; if it fails on a version mismatch, STOP (needs a ruling on the
   runtime pair).
3. N4 tests on NPU. 4. N5 parity and repeatability. 5. N6 batches, Pixel rested between batches.

---

## 7. Merge order

Branch is on top of PR #81. If #81 merges first, this branch merges master in with a merge commit
(Y-14 rule). F1 is unaffected.

---

## 8. Not in this PR

The Tensor G5 artifact (Pixel 10), other vendors' NPU artifacts, any context above 2560, any
change to staging or prod, any runtime local-to-remote fallback (see N-3).

---

## 9. Rulings requested

- **N-1** Backend: `Backend.NPU(nativeLibraryDir)` (recommended, its executor is the one read in
  2.3 to 2.5), not `Backend.GOOGLE_TENSOR`.
- **N-2** Fallback: (a) NPU then CPU at start, both artifacts on the phone, about 5.9 GB
  (recommended, matches the ruled GPU-then-CPU pattern); or (b) NPU only, a failed NPU start
  latches UNAVAILABLE and the seam refuses.
- **N-3** "Keep remote fallback": confirm it means the remote engine stays in the code and stays
  the staging and prod binding, chosen statically per flavor (R3, R-DEV). A per-request fall back
  from local to remote is a different design (identity, audit and facility rules differ per
  engine) and would need its own memo. Recommended: static, as ruled.
- **N-4** H6 repeatability hard gate: 30 of 30 comparable pairs byte-identical on the selected
  backend, as in 3.3.
- **N-5** NPU stays unselected (opt-in only) until N4 and N5 pass; selection is a separate ruling.
- **N-6** Pin the G6 artifact at `b3ca0d2f`, sha256 `f86c7c19...95b7`; do not follow the repo head.
- **N-7** Context stays 2560 on every backend (server parity); the NPU's 4096 is not used.
- **N-8** If the device shows that the dispatch library must be a file, legacy JNI packaging for
  the dev variant only (staging and prod packaging unchanged).
