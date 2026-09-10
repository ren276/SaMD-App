# Production classifier architecture (STEP 1, read-only research memo)

Run date: 2026-09-05. SaMD-App branch `master`, HEAD `cab78ae`.
Input: `scratchpad/classifier-dataset-nlem-audit-memo.md`, whose measured findings are treated
here as established fact and are not re-derived.

**The question.** Production input to the chief-complaint path is changing from a closed 118-term
pipe-joined token pool to open-vocabulary, ASR-produced, Indic-vernacular clinical prose. What is
the right classifier architecture, input representation and dataset strategy for a regulated,
offline, on-device Android Class B SaMD used as a general first-line differential-diagnosis aid for
common conditions in rural Indian PHCs?

**The headline, stated first because it reorders the work.** The label space is wrong before the
input representation is wrong. Measured against the only real Indian PHC presenting-complaint study
with usable provenance, the primary-level reason-for-encounter mix is **General 45.6%, Digestive
17.7%, Musculoskeletal 12.0%**, with fever the single commonest complaint and 65% of encounters
presenting as *symptoms* rather than diseases. The shipped model's largest class is **E66 Obesity
at 41.82%** of labelled rows. Obesity is not a reason for encounter; it is a finding. No change of
encoder, tokenizer or classifier repairs a label space that answers a different question from the
one the health worker is asking. **Relabel to reason-for-encounter first; re-architect second.**

Read-only guarantee: nothing was written, edited or deleted outside this memo. No training, no
generation, no model download, no weights fetched. No `.env` or credential file opened at any point.

---

## 1. Code grounding — quoted, not paraphrased

### 1.1 The seam where input representation changes

`app/src/main/java/com/example/samdapp/data/remote/RetrofitEvaluateSource.kt:45-48`:

    val symptomString = listOfNotNull(
        payload.chiefComplaint.takeIf { it.isNotBlank() },
        payload.transcription?.takeIf { it.isNotBlank() },
    ).joinToString(". ")

**This single expression is the entire input contract**, and it is where any architecture change
lands. It produces period-space prose with the ASR transcript concatenated onto the typed complaint.
The trained distribution is `" | "`-joined pool terms; the wire carries prose. Nothing between here
and the model normalizes anything.

Confirmed on the receiving side — `SaMDClassifier/src/refine_diagnosis.py:76-82`:

    def get_symptom_ranked(symptom_string):
        xw = word_vectorizer.transform([symptom_string])
        xc = char_vectorizer.transform([symptom_string])
        xin = hstack([xw, xc]).tocsr()
        probs = symptom_model.predict_proba(xin)[0]

The raw string goes straight into `transform`. **There is no inference-time preprocessing of any
kind** — no lowering, stripping, splitting, spell correction, negation handling or normalization
beyond what the two vectorizers do internally. `src/app.py:194` passes
`payload.symptom_string` through untouched.

### 1.2 The structured fields that already exist and are already discarded

`app/src/main/java/com/example/samdapp/domain/model/KernelPayload.kt`:

    data class KernelPayload(
        val caseToken: String,
        val vitals: VitalsReading,
        val chiefComplaint: String,
        val durationBucket: String?,
        val severityScore: Int?,
        val relevantHistory: String?,
        val transcription: String?,
        val attachments: List<Attachment>,
    )

`durationBucket`, `severityScore` and `relevantHistory` are captured, carried across the kernel
boundary — and then dropped. `data/remote/dto/EvaluateRequestDto.kt` has **no field for any of
them**. Structured fusion is blocked at the DTO, not at capture. The payload's own KDoc records that
`onset`, `aggravatingFactors`, `relievingFactors` and `impactOnDailyActivities` were excluded
deliberately, *"not because they're identifying, but because they weren't in the whitelisted field
set; add them deliberately if the kernel needs them"*.

### 1.3 Fabricated vitals

`RetrofitEvaluateSource.kt:38-43` and `:59-66` default `bmi = 22.0`, `systolicBp = 120.0`,
`diastolicBp = 80.0`, `heartRate = 72.0`, `spo2 = 98.0` when the real values are absent, while
honestly passing null for glucose, respiratory rate and temperature. A case with no vitals produces
a complete "normal vitals" tier prediction, and nothing persisted distinguishes a measured 120/80
from a defaulted one.

### 1.4 The offline premise, corrected against disk

`app/src/main/java/com/example/samdapp/domain/model/InferenceSource.kt:11`:

    enum class InferenceSource { REAL_INFERENCE, MOCK_FALLBACK, UNAVAILABLE }

with the KDoc defining `REAL_INFERENCE` as *"when the live FastAPI+XGBoost kernel answered"* — a
network call — and `UNAVAILABLE` as *"staging/prod's honest failure state"*.

`app/src/main/assets/` contains exactly one model directory, `asr/`. There is **no on-device
clinical inference today**, no ONNX or TFLite outside ASR, and no reference to any inference runtime
in the app outside the transcription package. *Offline on-device is the target this memo designs
for; it is not the current build.* Every architecture option below is therefore also a port, and
that cost is counted.

### 1.5 The shipped ASR stack

From `docs/sbom/model-soup-2026-09-02-v1.0.json`:

| Component | Version | Licence | Notes |
|---|---|---|---|
| Parakeet TDT 0.6B v2 (int8) | — | **CC-BY-4.0** | offline transducer, greedy_search, `model_type=nemo_transducer` |
| sherpa-onnx-android | 1.13.7 | Apache-2.0 | vendored AAR, arm64-v8a + x86_64 |
| onnxruntime-android | 1.27.1 | MIT | inside the AAR, version read off the `.so` |

Two facts that matter for §7: on-disk total **661,190,513 bytes**, 622 MiB of it the encoder; and
training data recorded as `nvidia/Granary` and `nvidia/nemo-asr-set-3.0`, **English**. The Indic gap
is a documented property of the shipped weights, not an inference.

The SOUP record also states the change-control posture that governs everything below:

> Every component below is compiled into the APK and changes only by shipping a new app release.
> That makes a model change a design change under normal change control, not a post-deployment
> model update … no download-on-first-use, no model CDN, no silent refresh, no remote config
> selecting a model.

---

## 2. The label-space finding

### 2.1 What a rural Indian PHC actually sees

Kumar *et al.*, **"Self-reported symptom burden among patients attending public health care
facilities in India: Looking through ICPC-3 lens"**, *PLOS Global Public Health*, is the first
Indian study to apply ICPC-3 across all three levels of care. Cross-sectional, three districts of
Odisha (Cuttack, Sambalpur, Nabarangapur), 20 facilities — 6 PHCs, 6 CHCs, 3 sub-district
hospitals, 3 district hospitals, 2 tertiary medical colleges. 3,044 patients interviewed, **2,565
analysed** with valid chief complaints. 15 of 19 ICPC-3 chapters realised.

**At primary facilities specifically** — the tier this device targets:

| ICPC-3 chapter | Share of chief complaints |
|---|---|
| General / unspecified | **45.6%** |
| Digestive | **17.7%** |
| Musculoskeletal | **12.0%** |

All levels pooled: General 28.8%, Digestive 16.3%, Musculoskeletal 15.3%, Respiratory 8.3%,
Circulatory 7.4%. Commonest individual complaints, in order: **fever, hypertension, abdominal pain,
chest pain, arthritis, skin disease, cough, diabetes, injury**. And the structural finding:
**65% of patients reported symptoms, 35% reported a disease.**

### 2.2 Against the shipped label space

| What the PHC presents with | What the model can output |
|---|---|
| General/unspecified 45.6% — fever, weakness, "not feeling well" | No general class. Fever routes only through the six infectious codes. |
| Digestive 17.7% — abdominal pain, acidity, loose stools | **A09 alone**, 3.56% of labelled rows |
| Musculoskeletal 12.0% — arthritis, back pain, joint pain | M17 (knee OA specifically) at 23.16% — over-weighted and wrong-sited; no back-pain class |
| Skin disease (top-6 complaint) | none |
| Injury (top-9 complaint) | none |
| — | **E66 Obesity, 41.82%** — not a reason for encounter at any level |

The mismatch is not a tuning problem. The model's largest class is a *finding recorded during an
encounter*, not a *reason for the encounter*, and it holds that position because of the
US-BMI-distribution × WHO-Asian-cutoff interaction the audit measured (67.1% of BMI-bearing rows
≥ 23). The corpus is answering "what is abnormal about this person's vitals" while the health
worker is asking "why did this person come in today".

This corroborates from an external, real-world direction the audit's finding X-4 — that roughly 17
of 41 researched presenting complaints have no representation in the model at all — and it upgrades
that finding from a vocabulary observation to a **label-space defect**.

### 2.3 Consequence for the architecture question

Any architecture measured on the current label space will report a number that means nothing about
field performance. **The relabelling is not a prerequisite to be scheduled after the architecture
work; it is the thing that determines what the architecture is classifying.** ICPC-3 is the natural
target taxonomy: it is designed for reason-for-encounter, it is what the only usable Indian primary-
care distribution is expressed in, and it accommodates the symptom-not-disease reality of 65% of
encounters, which ICD-10 codes do not.

---

## 3. Architecture families, scored against this device

Constraints being scored against: offline on-device Android, Pixel-class budget co-resident with an
ASR model; IEC 62304 validatability; deterministic where required; calibrated confidence feeding the
physician gate; open-vocabulary Indic ASR prose as input; and the locked constraint that the
ensemble is *narrow single-claim modules with late fusion at a deterministic non-ML layer*.

### 3.1 (a) Keep classical TF-IDF + boosted head

| Dimension | Assessment |
|---|---|
| Prose input | **Fails.** Measured: prose scores 0.886 top-1 against a 0.941 ceiling, and that is *in-distribution* prose built from trained terms. On genuinely open vocabulary the failure mode is worse than degradation — `char_wb` n-grams manufacture confident wrong answers. `'Toothache'` → B54 malaria at 0.437; `'Diarrhoea / loose motions'` → M17 knee osteoarthritis at 0.644. These are measured, not hypothetical. |
| On-device cost | Best of all options. Sparse matmul, no float runtime, single-digit MB. |
| Validation burden | Lowest. Feature basis is inspectable; SHAP posture already exists for Classifier A. |
| Determinism / calibration | Fully deterministic. But **uncalibrated** (`calibration_used_for_evaluation: false`) and with **no abstention** — the empty-input E66-at-74.95% prior is the direct consequence. |
| Failure mode | Confident wrong answer on out-of-vocabulary input, with `REAL_INFERENCE` stamped on it. |

Verdict: **not viable as the input representation for open vocabulary.** Its failure mode is
precisely the one the device's whole hazard posture exists to prevent.

### 3.2 (b) Indic transformer encoder embeddings + boosted/calibrated deterministic head

| Dimension | Assessment |
|---|---|
| Prose input | Strong. This is what dense multilingual encoders are for. MuRIL — 236M parameters, BERT-base, 17 Indic languages — is the strongest Indic-specific candidate and reports best-in-class results across Indic benchmarks, with XLM-R close behind (weighted F1 89.67% vs MuRIL-large 90.60% on the comparison surveyed). |
| On-device cost | Feasible but not free. int8 quantisation takes a BERT-class model from roughly 429 MB to about 184 MB. Co-resident with Parakeet's 622 MiB that is tight but within a Pixel-class budget — and see §7, where a smaller ASR model buys the headroom. |
| Validation burden | Moderate. The encoder is frozen SOUP with a pinned hash; only the head is trained. This is the key advantage over (c). |
| Determinism / calibration | Encoder inference is deterministic given fixed weights and fixed runtime. Calibration must be added explicitly; it is not inherited. |
| Failure mode | Silent semantic drift — a vernacular phrase embeds near the wrong cluster with no lexical trace to audit. Harder to diagnose than (a)'s failure because there is no inspectable feature to point at. |

Verdict: **necessary but not sufficient.** It fixes the representation and fixes nothing else — not
the label space, not the absence of abstention, not the OOV confidence problem.

### 3.3 (c) End-to-end fine-tuned encoder classifier

| Dimension | Assessment |
|---|---|
| Prose input | Best raw accuracy of the four. Transformer classifiers on clinical diagnosis text report ~89% accuracy / 87.6% F1 on MIMIC-III, ~8 points over CNN-RNN hybrids. |
| On-device cost | Same as (b), plus no reduction in the deployed graph. |
| Validation burden | **Highest, and disqualifying.** Every retrain revalidates an opaque 236M-parameter model end to end. Under IEC 62304 §5.6 and the ACP that `qms-overview.md` still lists as TODO, each retrain is a full software-verification event on a component with no inspectable intermediate. Compare with (b)/(d), where the encoder is frozen SOUP and only a small head changes. |
| Determinism / calibration | Deterministic, but transformer classifiers are characteristically over-confident out of distribution, which is exactly the operating condition here. |
| Failure mode | Confident wrong answer with no auditable intermediate — the worst of both (a) and (b). |
| Lock compatibility | **Breaks the lock.** A single end-to-end model is not "narrow single-claim modules with late fusion at a deterministic non-ML layer". |

Verdict: **rejected.** Highest accuracy on a benchmark, worst fit for a regulated device. Choosing
it would mean accepting an unvalidatable retrain cycle for a metric measured on the wrong labels.

### 3.4 (d) Deterministic clinical-NLP extraction + narrow ML ranking

| Dimension | Assessment |
|---|---|
| Prose input | Strong, and for the right reason: the open vocabulary is absorbed by a layer that can be *inspected and unit-tested*, not by one that must be trusted. Negation and uncertainty detection matter enormously here — "no fever" and "fever" currently produce near-identical TF-IDF vectors, and the model has never been shown a negation. |
| On-device cost | Rules cost essentially nothing. Cost is whatever the narrow ranking model costs. |
| Validation burden | **Lowest of the viable options.** Rules are enumerable test cases. The IMCI/CBAC branching stays exactly where the lock puts it. |
| Determinism / calibration | Layers 1 and 3 fully deterministic; the ML surface is narrowed to one ranking step that can carry a calibrated, abstaining output. |
| Failure mode | Coverage gaps — a phrase no rule matches. Which is a *detectable, loggable* failure, unlike (a)'s and (c)'s confident wrong answers. This is the decisive difference. |

Verdict: **the right frame.** And note it is the frame the existing lock already mandates; this is
not a new architecture so much as an honest implementation of the one already decided.

### 3.5 Supporting evidence on the imbalance question

This matters because the device's weakest classes are its most dangerous ones — J22 at 25 rows
(F1 0.3636), B50 at 21 (0.4000), A91 at 54 (0.3810).

- A 336-pipeline benchmark on extreme class imbalance in low-resource clinical text found the top
  configuration was **TF-IDF + Stacking + boundary-cleaning undersampling at F1 0.727**, with
  **RandomOverSampler + LightGBM at 0.717** — statistically comparable. The classical family remains
  competitive at the rare-severe tail; a transformer is not automatically the answer to thin classes.
- SMOTE's performance **degrades in high dimensions**, where synthetic points may be uninformative
  and inject noise. Relevant directly: the current feature space is 4,540 sparse dimensions.
- A comprehensive 2025 evaluation of oversampling for text classification finds BorderlineSMOTE/SMOTE
  *do* consistently improve minority F1 in clinical text — but frames the real decision as a
  sensitivity-versus-precision trade-off that should be set by clinical goal, not by metric.
  For a first-line PHC differential aid, that trade is explicit: **recall on severe classes over
  precision**, with the physician gate absorbing the false positives.

### 3.6 Supporting evidence on calibration and abstention

This is the mechanism that answers the E66-at-74.95% hazard directly, and it is the piece missing
from every family above as normally implemented.

Conformal selective prediction with cost-aware deferral produces set-valued predictions with
**finite-sample coverage guarantees** and defers low-confidence cases to the clinician when doing so
reduces expected clinical cost. On early sepsis prediction it reduced error on retained cases by
**49.6% in-distribution and 46.7% out-of-distribution**. Inductive conformal prediction frameworks
such as FairICP apply a certainty threshold that partitions predictions into retained and abstained
groups by the model's *scope of competence*, guaranteeing that retained accuracy meets a
user-defined confidence level.

Two properties make this the right fit rather than merely a good idea:

1. **It is distribution-free and finite-sample.** It does not require the calibration set to be
   large or the model to be well-specified — both of which are false here.
2. **Abstention is a first-class output.** `InferenceSource.UNAVAILABLE` already exists as the
   device's honest failure state. Conformal abstention gives it a principled trigger instead of only
   firing on transport failure.

### 3.7 Supporting evidence on retrieval and the diagnosis path

The 2025 literature converges on hybrids that keep retrieval bounded and deterministic logic in
charge. Medi-Gemma pairs deterministic EMR analytics with RAG behind rule-based clinical safety
mechanisms and intent routing. Evaluations of RAG variants for clinical decision support report
self-reflective RAG reducing hallucination to 5.8% and identify retrieval confidence thresholds and
external fact-checking as the load-bearing controls. Auditable-RAG frameworks name the feasibility
challenges precisely: **knowledge-base governance and updating, citation fidelity, bias propagation
from the underlying evidence, and alignment with SaMD regulatory frameworks.**

Read against this project: retrieval belongs where it already is — on a **fixed, versioned,
citation-bearing NLEM corpus** — and does not belong on the diagnosis path, where there is no fixed
corpus to retrieve from and no citation to attach. The audit's finding L-7 (the NLEM path *is*
embedding retrieval, not the "deterministic lookup" the lock describes) is the governance problem
this literature names, and it needs the operator ruling the audit already requested.

---

## 4. Recommendation

**Deterministic extraction → narrow calibrated ranking → deterministic fusion with abstention.**

Family (d), with a bounded and strictly-scoped borrow from (b). This is the shape the existing lock
already mandates; the recommendation is to implement it honestly rather than to replace it.

### Layer 1 — deterministic clinical NLP, no ML

Indic-aware normalization of ASR prose to a controlled vocabulary:

- **Negation and uncertainty detection.** Non-negotiable and currently entirely absent. "no fever",
  "fever since 3 days", "?fever" are three different clinical claims that today produce nearly the
  same vector.
- **Term normalization** — vernacular and code-mixed surface forms to controlled terms, including
  the transliterated Hindi/Malwi forms an Indic ASR will emit.
- **The locked IMCI/CBAC branching, untouched**, staying rule-based exactly as the lock requires.

Rule-based, unit-testable, enumerable, versionable in git, near-zero model-validation burden. **This
is the layer that absorbs the open vocabulary, and it is where the ASR investment cashes out: ASR
feeds a normalizer, never the classifier directly.** That single indirection is what converts ASR
from a hazard multiplier into an input-quality improvement.

### Layer 2 — narrow ML, deliberately narrow

A MuRIL-class Indic encoder used **only** as nearest-trained-term lookup for phrases Layer 1 cannot
match by rule. Not the diagnosis classifier. Its output is a *term*, drawn from a closed controlled
vocabulary, with a similarity score — an object a human can read and a test can assert on.

The diagnosis head stays a **calibrated boosted model over the normalized structured
representation** (controlled terms + vitals + duration + severity), preserving the interpretable
feature basis and the existing SHAP posture, and keeping retrains cheap and inspectable.

### Layer 3 — deterministic fusion and abstention

Late fusion at a non-ML layer, as the lock requires, plus a conformal/selective gate. Below the
coverage threshold the device emits `InferenceSource.UNAVAILABLE` and no differential at all. The
physician AGREE/MODIFY/REJECT gate is unchanged and remains terminal.

### Why not the others, in one line each

- **(a)** Its OOV failure mode is a confident wrong answer stamped `REAL_INFERENCE` — measured, not
  theoretical.
- **(b) alone** Fixes the representation and nothing else: not the label space, not abstention, not
  the interpretable basis it discards on the way.
- **(c)** Trades an unvalidatable retrain cycle for accuracy measured on the wrong labels, and
  breaks the narrow-module lock.

### What this buys, concretely

The three worst outcomes the audit measured all get a mechanism:

| Audit finding | Mechanism |
|---|---|
| Empty input → E66 at 74.95% | Layer 3 abstention with coverage guarantee |
| OOV → confident wrong class (`'Toothache'` → B54 0.437) | Layer 1 coverage gap → detectable, loggable, abstained |
| 12/13 qualifier pairs flip top-1 | Layer 1 preserves qualifiers as controlled terms rather than collapsing them |

---

## 5. Structured-field fusion

**Recommendation: fuse, but the blocker is the dataset, not the DTO.**

`durationBucket`, `severityScore` and `relevantHistory` are already captured and already crossing the
kernel boundary (§1.2). Adding them to `EvaluateRequestDto` is a small change. What makes it
premature is that **`canonical_dataset.csv` has no duration column and no severity column at all** —
the 30-column schema contains neither. A model cannot learn from a field its training corpus does
not contain. Fusion is gated on dataset regeneration.

Two things must be true before fusion is safe:

1. **The defaulted-vitals problem is fixed first.** Fusing vitals while `?: 120.0 / 80.0 / 72.0 /
   98.0` silently substitutes normals trains the model on fabricated data and, worse, teaches it
   that "no vitals recorded" looks like "healthy". Every vital needs an explicit measured-versus-
   imputed flag on the wire and in the persisted evidence row.
2. **The glucose-missingness leak is understood as the same class of problem.** The audit measured
   glucose present-rate at 1.000 for E11 and 0.000 for A91/B50/J22. Adding more optional structured
   fields without missingness indicators adds more channels for the same shortcut.

**Expected effect on the severe classes: this is where fusion pays.** J22, A91 and B50 are exactly
the presentations where three symptom terms are ambiguous and the vitals are decisive — SpO2 for
LRTI, the pulse/SpO2 combination for severe dengue and severe malaria. Duration carries the
stepladder-versus-cyclical fever distinction that currently has to be smuggled into a symptom string
as a qualifier. The classes with the least text signal have the most structured signal, and the
current architecture uses none of it in Classifier B.

**Effect on the input contract:** it becomes wider and more explicit — each field paired with a
provenance flag. That is a contract version bump and, under the ACP, a design change. It should be
made once, together with the relabelling, not incrementally.

---

## 6. External datasets — usable and not

### 6.1 Usable

| Source | Use | Caveats |
|---|---|---|
| **ICPC-3 Odisha study** (PLOS Glob Public Health) | Reason-for-encounter distribution at PHC level; the anchor for §2 | Odisha, not Madhya Pradesh — the nearest available real Indian PHC distribution, and it must be labelled as a proxy. Deidentified data is supporting-file only and further access needs *"prior approval of State Health & Family Welfare department"*, so **cite the published distribution; do not plan on obtaining the rows.** |
| **NFHS-5 district factsheets** (Indore, Dhar) | BMI, anaemia, BP, blood-sugar prevalence by sex/age band at district granularity | Population prevalence, not presenting prevalence |
| **ICMR-INDIAB**, MP stratum | Diabetes/prediabetes, urban-rural split, random-glucose criterion matching the pipeline's own thresholds | Metabolic only |
| **IDSP / NVBDCP district returns** | Empirical seasonal shape for malaria/dengue/chikungunya — the real replacement for the invented 8%/22% `fever_pattern` rate | No denominators |
| **Nikshay / State TB Report** | District TB notification rates | Not presentation-stage |
| **HMIS OPD returns** | PHC-level visit mix | No symptom-level detail |

### 6.2 Not usable

**The Kaggle symptom-disease tier, as a category.** The concrete disqualifying example: the "Indian
Patient Disease & Treatment Dataset" states plainly that it *simulates* a registry and that **all
figures are synthetically generated for educational/portfolio purposes and do not represent actual
medical or government health data**. Others in the same family (Symptom2Disease, the 773-disease ×
377-symptom one-hot set with 246,000 rows, the various Disease-Symptom mappings) carry no stated
provenance, no collection protocol, no institution and no ethics approval.

Using any of them would repeat the exact error this whole audit chain has been unwinding: adopting
an authored distribution and treating it as epidemiology. **Training a Class B device on a corpus
whose provenance cannot be stated is not defensible in a CDSCO submission, regardless of its size or
its benchmark numbers.**

### 6.3 The gap worth naming

**No Indic clinical text corpus with usable provenance was found.** There are strong Indic
*general-language* resources — IndicNLPSuite, IndicXNLI, IndicMMLU-Pro, MuRIL's own pretraining —
and strong clinical corpora in English. There is no published, provenance-bearing corpus of Indian
clinical presenting complaints in Hindi or a regional language.

This is a finding, not an omission in the search. It means: (i) Layer 1's normalization lexicon must
be *built*, not downloaded, and is therefore a first-class versioned repo artifact with its own
review process; (ii) the OOD eval set the audit called for has no external source and must come from
real recorded encounters under consent; and (iii) any claim that a model handles vernacular clinical
prose is currently unfalsifiable for lack of a benchmark to falsify it on. Building that eval set may
be the single highest-value dataset artifact this project can produce, and it is plausibly
publishable in its own right.

---

## 7. HAI-DEF evaluation — evaluation level only, nothing recommended for build

Three roles kept strictly distinct.

### 7.1 (a) MedASR as a candidate on-device ASR feeding the classifier

**What it is.** Conformer architecture, **105M parameters**, mono-channel 16 kHz int16 audio in,
text out. Tuned for medical dictation. Fine-tuning is explicitly supported and documented for
**English accents, acoustic environments, and vocabulary expansion**, with a dedicated notebook.

**Against the shipped Parakeet TDT 0.6B v2:**

| Axis | Parakeet TDT 0.6B v2 (shipped) | MedASR (candidate) |
|---|---|---|
| Parameters | ~600M | **105M** (~5.7× smaller) |
| On-disk (int8, as shipped) | 661,190,513 bytes | not published; a 105M Conformer should land far below |
| Licence | **CC-BY-4.0** — attribution only, already discharged via the licences screen | **HAI-DEF Terms of Use** — see below |
| Training languages | English (`nvidia/Granary`, `nvidia/nemo-asr-set-3.0`) | **English only documented; no Indic support stated** |
| Domain tuning | general ASR | **medical dictation** |
| Fine-tuning path | possible, not first-class | **first-class, documented, notebook-supported** |
| Published latency/footprint | measured on-device by `SherpaOnnxTranscriptionServiceTest` | **none published** |
| Runtime | already integrated (sherpa-onnx + ORT) | **new runtime, new SOUP entries, new packaging** |

**On the Indic question, precisely.** MedASR does **not** close the Indic gap out of the box. Both
models are English-trained. What MedASR changes is the *fine-tuning starting point*: it is 5.7×
smaller (so fine-tuning is tractable on modest hardware), it is explicitly built to be fine-tuned for
vocabulary and acoustic environment, and it starts from medical rather than general speech. For a
project that will have to fine-tune for Malwi-accented Hindi clinical speech regardless, a smaller,
medical, fine-tuning-first base is a materially better starting point than a larger general one.
That is an argument for evaluation, not for a swap.

**On licensing — the sharper issue, flagged for operator legal review.** The HAI-DEF Terms of Use
define **"Clinical Use"** as *"any use in diagnosis or treatment of patients (including as part of a
research study)"*. It is **not prohibited**, but it is conditioned: the user must *"seek Health
Regulatory Authorization from the relevant Health Regulatory Authority"*. Separately and more
awkwardly, users must not use HAI-DEF *"for any use that could cause a Health Regulatory Authority
to deem Google to be a 'manufacturer' of a medical device."* Redistribution requires passing the
restrictions downstream, providing the agreement, and a Notice file. And users must **defend and
indemnify Google**, and are *"solely responsible for … Outputs and their subsequent uses."*

For a CDSCO-registered Class B device that ships weights inside its APK, none of these is a
formality. The first is arguably satisfied by the CDSCO pathway the project is already on. The
second is a genuine question about whether embedding Google's weights in a licensed medical device
implicates that clause. The third changes the SBOM/attribution obligation from CC-BY-4.0's simple
attribution to a redistribution-terms flow-down. **This is a legal question, not an engineering one,
and it should be answered before any evaluation work is scheduled — not after.**

**Precedent — Crane AI Labs / EaseHealth.** Offline-first Android clinical decision support running
a fine-tuned **MedGemma 4B + MedASR entirely on-device, zero cloud dependency**. Deployed under
ethics and regulatory oversight across **15 health facilities in Luweero District, rural Uganda**
(population 600K+) — **Uganda, not India**, which matters when reading it as precedent. 268 health
workers consented, 87+ clinical assessments captured **entirely offline** over 5 days. Target device
**Samsung A16 with 8GB RAM**; **40-second warm-start inference**; Quantization-Aware Training applied
during fine-tuning. Reported 62% hallucination reduction via prompt engineering and safety filtering,
with forced confidence scoring on every assessment.

Two things to take from it. First, **the stated barrier is device RAM** — the team reports that most
health workers' phones lack sufficient RAM and are evaluating compression for 4GB targets. That
barrier is materially relaxed by this project's Pixel-class target, which is the most useful thing
the precedent tells us. Second, and independently valuable: EaseHealth **deliberately excluded dosage
recommendations as a safety measure.** A team running a medical-tuned generative model on-device,
under ethics oversight, chose to keep dosing out of the model. That is external corroboration of this
project's lock against generative prescribing, arrived at independently.

**Recommendation on MedASR: Parakeet stands. MedASR warrants a dedicated future on-device evaluation
PR — gated on legal review first, engineering second.** The evaluation, when authorized, should
measure: WER on Malwi/MP-accented Hindi and Hinglish clinical speech after comparable fine-tuning of
both candidates; peak RSS and warm-start latency on the target Pixel co-resident with whatever else
is loaded; and total APK delta. The 5.7× parameter reduction is the prize — it is the headroom that
makes Layer 2's encoder and a future SLM co-resident feasible at all. But it is not worth acquiring
before the manufacturer clause is answered. **No weights were downloaded and none should be until
that answer exists.**

### 7.2 (b) MedGemma and the fine-tuned Gemma-3-4B-IT

Noted only, as instructed. MedGemma is Gemma-3-based medical text/vision, available at 4B multimodal
and 27B text-only/multimodal, with MedGemma 1.5 released 2026-01-13. Together with the operator's
existing Gemma-3-4B-IT fine-tuned on pharmacology sources, this is a **separate, later on-device SLM
assistant workstream**. It is not designed here, is not folded into the classifier architecture, and
must not reach the diagnose or prescribe path under the existing lock. The EaseHealth precedent above
is directly relevant to that workstream when it opens.

### 7.3 (c) MedSigLIP, HeAR, and imaging/audio embedding models

**Out of scope. Parked by operator decision.** The image, photo, video and audio model-state work is a
large separate reengineering effort. Named here so the gap is visible in the record; not analyzed,
not designed, not evaluated.

---

## 8. Locks — one tension flagged, one carried forward

Nothing recommended in §4 breaks a lock. Two items need operator attention rather than assumption.

**Flag 1 — a second embedding model, and whether it counts as branching.** Layer 2 introduces an
Indic encoder for nearest-term lookup. The NLEM path already contains a SentenceTransformer. My
reading is that the lock *"deterministic branching stays rule-based"* is satisfied — nearest-term
lookup is input normalization producing a controlled-vocabulary term, and every branch downstream of
it is a rule over that term. But that is an interpretation of a lock the operator wrote, and it is
the operator's to confirm, not mine to assume. If the ruling goes the other way, Layer 2 collapses to
rules-plus-lexicon only, which is a viable if weaker design, and the memo's recommendation degrades
gracefully rather than failing.

**Flag 2 — carried forward from the audit, still open.** The NLEM path is embedding retrieval with a
0.6 cosine gate, not the *"deterministic NLEM lookup, not RAG"* the lock describes. The §3.7
literature names exactly this as the governable risk surface (knowledge-base versioning, citation
fidelity). Reconcile the wording or the implementation.

**Locks respected without qualification:** no generative LLM in the diagnose or prescribe path
(§4 introduces none, and §7.2 explicitly excludes the SLM from it); deterministic branching stays
rule-based (Layer 1); physician gate always in the loop (Layer 3 leaves it terminal and adds an
abstention that fails *toward* the human); ensemble as narrow single-claim modules with late fusion
at a deterministic non-ML layer (this is precisely Layers 1–3); a model change is a design change
under normal change control, with no download-on-first-use and no silent refresh (every model named
here ships frozen in the APK, exactly as the model-SOUP record requires).

---

## 9. Phased path

### 9.1 Demo-safe now — clinical core untouched

Nothing in the model, the dataset, the DTO or the wire format changes. The current classifier, the
current corpus and the current `symptom_string` construction all stand. ASR work continues only on
the §K narrative fields the audit cleared — none of which reaches the classifier.

The one thing worth doing now because it is pure addition and closes a live hazard: **register the
empty/OOV-input hazard** (audit L-8) in `docs/quality/risk-management-file.md`, and gate the empty
resolved-term case to `UNAVAILABLE` rather than calling evaluate. That is a guard, not an
architecture change, and it stops the E66-at-74.95% path from being reachable.

### 9.2 Post-demo production rebuild, in dependency order

1. **Relabel to ICPC-3 reason-for-encounter.** Everything else depends on this; doing it later means
   redoing the work above it.
2. **Regenerate the dataset** against the §6.1-anchored distribution, with plausibility filters, an
   encounter-date window, measured-vs-imputed flags, and a seeded reproducible run.
3. **Build the perturbation and OOD eval sets** (§6.3). Without them nothing below can be measured.
4. **Layer 1** — the deterministic normalizer, negation detection, and the controlled vocabulary as a
   versioned repo artifact.
5. **Layer 3** — conformal abstention wired to `InferenceSource.UNAVAILABLE`.
6. **Layer 2** — the Indic encoder, subject to the §8 Flag 1 ruling.
7. **Structured fusion** (§5), once the corpus actually carries duration and severity.
8. **The on-device port**, which §1.4 establishes is a port and not a configuration change.

### 9.3 Parked

Clinical knowledge graph (audit §J). Imaging, CXR, photo, video and audio modalities including
MedSigLIP and HeAR (§7.3). The MedGemma/Gemma-3-4B-IT on-device SLM assistant workstream (§7.2).
MedASR evaluation, pending legal review (§7.1).

---

## 10. How the ASR investment pays off

Worth stating plainly, because the current wiring makes ASR a liability rather than an asset.

Today `transcription` is concatenated onto `chiefComplaint` with `". "` and shipped straight into a
TF-IDF model trained on pipe-joined pool terms with zero phrasing variance (§1.1). Every ASR
improvement therefore produces *more* natural language on a path that punishes natural language,
and any transcription error becomes a silent input corruption on a model that cannot express doubt.
ASR quality and model input quality are currently anti-correlated.

Under the recommended architecture the relationship inverts. ASR feeds **Layer 1**, whose job is to
turn prose into controlled terms. Then:

- **Better ASR means better normalization coverage**, which means fewer abstentions and more usable
  encounters — a direct, measurable payoff on a metric that matters.
- **Vernacular capability becomes usable rather than dangerous.** A health worker can speak Malwi or
  Hindi, and Layer 1 maps it to controlled terms the model was trained on. Today vernacular input
  would be pure OOV, which the audit measured as *confidently wrong*, not merely useless.
- **Qualifiers survive.** The 12-of-13 top-1 flips are driven by qualifiers ("severe", "exertional",
  "stepladder"). Free speech carries those naturally; a dropdown destroys them. This is the strongest
  argument that ASR-plus-normalization beats ASR-plus-field-reduction on model input, and it reverses
  the framing that ASR is primarily a data-entry-speed feature.
- **The confirmation gate keeps working**, because Layer 1's output is a short list of controlled
  terms — far easier for a worker to verify at a glance than a paragraph of transcript.

The ASR investment pays off precisely when a normalization layer exists between it and the model.
Without that layer, improving ASR makes the model's input worse.

---

## 11. What this memo does not claim

It does not claim any accuracy figure for the recommended architecture. None has been measured,
and none can be until the relabelling and the eval sets exist. The cited literature establishes that
each component is sound in comparable settings; it does not establish what this system will score,
and nothing here should be quoted as a performance claim.

It does not claim the ICPC-3 Odisha distribution is Madhya Pradesh's. It is the nearest real Indian
PHC presenting-complaint distribution available, from a different state, and it is used to establish
that the current label space is answering the wrong question — a conclusion robust to the state
difference. Calibrating to Indore and Dhar specifically still requires the §6.1 sources.

It does not resolve the HAI-DEF manufacturer clause, which is a legal question.
