# Classifier training design memo (STEP 1, read-only)

Run date: 2026-09-07. SaMDApp branch `master`.
dataset-make `feat/drishti-v2-dataset-generator`, HEAD post-build-2.
Corpus: `drishti_v2_output/corpus.csv`, 23,000 rows × 499 columns, all 8 gates PASS.

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No training,
no generation, no model download, no weights fetched, no `.env` or credential file opened at any
point. No source, dataset, model, config or generator file touched in any repo.

**Inputs treated as established fact and not re-derived:**

- `scratchpad/production-classifier-architecture-memo.md` — the three-layer design (Layer 2
  calibrated boosted head, Layer 3 conformal abstention).
- `scratchpad/dataset-regeneration-design-memo.md` — corpus contract, schema, generation rules.
- `scratchpad/reason-for-encounter-category-system-memo.md` — label space and category semantics.
- `scratchpad/classifier-dataset-nlem-audit-memo.md` — defects to avoid.
- `scratchpad/branch-authoring-batch-{1..4}-memo.md` — full label space, disposition
  floor/abstain semantics, DECISION-1 (REFER_EMERGENCY forces abstention).
- `drishti_v2_output/corpus.csv` and `generation_manifest.json` — ground truth, read on disk.

---

## 0. The corpus this memo designs against, read from disk

23,000 rows. 499 columns. 27 categories (26 `LEARNED` + `other_not_in_list` `ALWAYS_ABSTAIN`).
6 Tier-0 emergency categories exist in the category registry but are **not in the corpus** — they
are `ALWAYS_ABSTAIN`, rule-based, and never reach the classifier (category memo §6).

### 0.1 Realized category distribution

| Category | Rows | % | Chapter |
|---|---|---|---|
| `fever` | 7,849 | 34.13 | A General |
| `abdominal_pain` | 1,563 | 6.80 | D Digestive |
| `other_not_in_list` | 1,453 | 6.32 | — (fallback) |
| `joint_pain` | 1,125 | 4.89 | L Musculoskeletal |
| `acidity_heartburn` | 1,073 | 4.67 | D Digestive |
| `weakness_unwell` | 1,039 | 4.52 | A General |
| `back_neck_pain` | 803 | 3.49 | L Musculoskeletal |
| `diarrhoea` | 766 | 3.33 | D Digestive |
| `antenatal_visit` | 723 | 3.14 | W Pregnancy |
| `body_ache` | 709 | 3.08 | A General |
| `known_hypertension` | 696 | 3.03 | K Circulatory |
| `cough` | 638 | 2.77 | R Respiratory |
| `injury` | 622 | 2.70 | L Musculoskeletal |
| `cold_sore_throat` | 613 | 2.67 | R Respiratory |
| `vomiting_nausea` | 474 | 2.06 | D Digestive |
| `pallor_anaemia` | 418 | 1.82 | B Blood |
| `rash` | 329 | 1.43 | S Skin |
| `headache` | 299 | 1.30 | N Neurological |
| `itching` | 286 | 1.24 | S Skin |
| `chest_pain` | 276 | 1.20 | K Circulatory |
| `skin_infection` | 230 | 1.00 | S Skin |
| `weight_loss` | 218 | 0.95 | A General |
| `breathlessness` | 216 | 0.94 | R Respiratory |
| `known_diabetes` | 198 | 0.86 | T Endocrine |
| `oedema` | 186 | 0.81 | A General |
| `dizziness` | 130 | 0.57 | N Neurological |
| `urinary_symptoms` | 68 | 0.30 | U Urinary |

Head-to-tail ratio: 7,849 : 68 ≈ **115 : 1**. One order of magnitude — no longer the old
300 : 1. The three severe classes (J22 25 rows, B50 21, A91 54) that were the most dangerous gap
are no longer classes at all; they are conditions *behind gateways* of `fever`, `cough` and
`breathlessness` (category memo §9).

### 0.2 Column groups (on disk)

| Group | Count | Role |
|---|---|---|
| Audit/metadata | 18 | `row_id`, `master_seed`, `stream_id`, `generator_commit`, `branch_version`, `category_registry_version`, `answer_model_version`, `threshold_table_version`, `path_taken`, `fired_gateways`, `disposition_floor`, `routed_to`, `condition_id`, `severity`, `icd10_mapping`, `icpc3_basis`, `synthetic`, `source` |
| Demographics | 2 | `age`, `sex` (+ `age_band`, `age_at_encounter`, `encounter_date` — audit only) |
| Vitals — true | 8 | `<vital>_true` — generator ground truth, **never a feature** |
| Vitals — observed | 8 | `<vital>` — the device/worker reading, **feature** |
| Vitals — provenance | 8 | `<vital>_provenance` — `DEVICE_MEASURED` / `MANUAL_ENTERED` / `NOT_MEASURED` / `DEVICE_ERROR`, **feature** |
| Status columns | 141 | `<fieldId>__status` — `ANSWERED` / `NOT_ASKED` / `UNKNOWN`, **feature** |
| Boolean multi-choice | 178 | `danger_signs__ds_*`, `previous_pregnancy_complication__*`, etc. — **feature** |
| Single-answer fields | ~136 | `fever_pattern`, `chills_rigors`, `cough_ge_2_weeks`, etc. — **feature** |

### 0.3 Disposition distribution (on disk)

| Floor | Rows | % |
|---|---|---|
| `PHYSICIAN_REVIEW_MANDATORY` | 10,887 | 47.3 |
| `REFER_URGENT` | 7,424 | 32.3 |
| `REFER_EMERGENCY` | 4,689 | 20.4 |

**DECISION-1** (batch-4 memo): `REFER_EMERGENCY` floor forces abstention — classifier not called,
no differential produced. These 4,689 rows (20.4%) are therefore **abstention targets by rule**,
not training examples for the classifier's differential output. They still participate in training
the *category* prediction (§1) and the *abstention calibration* (§5), but the device never
produces a ranked differential for them.

---

## 1. Label space — what the model predicts

### 1.1 The classification task

**Input:** One structured encounter record (demographics + observed vitals with provenance +
tree-walk answers with status codes).

**Output:** A probability distribution over 27 `category_id` values: the 26 `LEARNED` categories
plus `other_not_in_list`.

This is a **multi-class, single-label** classification problem. Each encounter belongs to exactly
one category. The model emits probabilities; the abstention gate (§5) decides whether to act on
them.

### 1.2 What the model does NOT predict

| Thing | Why not |
|---|---|
| ICD-10 codes | ICD-10 is an *attribute* of a category, carried for HMIS/ABDM/NLEM coding backbone. The model never emits it (category memo §3.1) |
| Condition within a category | `condition_id` is a latent variable — the clinical entity behind the presenting complaint. The model predicts the presenting complaint, not the diagnosis |
| Severity | `severity` is a latent variable that the vitals-triage path (Classifier A / track-and-trigger) addresses, not the differential classifier |
| Disposition | `disposition_floor` is a *rule output*, excluded from the feature set (tree memo §7.4 rule 3). The model must learn to be uncertain from the vitals and answers, not from reading the answer off the rule's output |
| The 6 Tier-0 emergency categories | `ALWAYS_ABSTAIN` by construction. Rule-based routing, never ML |

### 1.3 The label encoder

27 classes. `other_not_in_list` is class index 26 (or whatever position the alphabetical sort
places it — the encoder must be a reproducible, deterministic mapping from `category_id` string to
integer, recorded in the model metadata and hashed against the category registry version).

**`other_not_in_list` is a real class the model must learn.** It is not a discard bucket. The
model must assign nonzero probability to it, because it represents the ~6.32% of encounters
(1,453 rows) where the worker selected "something else". Its prediction is one input to the
abstention gate: a high `other_not_in_list` probability is evidence that the model is out of scope
and should abstain. A model that cannot predict it cannot express uncertainty about its own
coverage — it will be forced to guess among the 26 clinical categories for every input, including
inputs that belong to none of them.

---

## 2. Feature encoding

### 2.1 Feature exclusion list — features the model must never see

These are excluded because they are either target-adjacent (leak the label), generator-internal,
or audit-only.

| Excluded | Reason |
|---|---|
| `category_id` | **The label** |
| `condition_id`, `severity` | Latent variables — downstream of the label, generator-internal |
| `icd10_mapping`, `icpc3_basis` | Deterministic functions of `category_id` |
| `disposition_floor` | Rule output; training on it teaches the model to reproduce the rule, not to read the patient (dataset memo §1 D4) |
| `fired_gateways`, `routed_to` | Rule outputs |
| `path_taken` | Deterministic function of the answers — redundant with the answers themselves and structurally correlated with the label |
| `<vital>_true` × 8 | Generator ground truth, never observed by the device |
| `row_id`, `master_seed`, `stream_id`, `generator_commit`, `branch_version`, `category_registry_version`, `answer_model_version`, `threshold_table_version`, `synthetic`, `source`, `encounter_date` | Provenance/audit metadata |
| `_condition_id`, `_severity_band`, `tree_disposition_floor`, `path1_emergency`, `path2_aggregate_score` | Generator-internal derived columns |

### 2.2 Feature groups, and how each is encoded

**Group 1 — Demographics (3 features)**

| Feature | Type | Encoding |
|---|---|---|
| `age` | continuous | Numeric, as-is. Range 0–80+; the corpus now includes under-5 rows (dataset memo D3) |
| `sex` | binary | 0/1 (M=0, F=1, or label-encoded consistently with the existing Classifier A convention `sex_encoded`) |
| `facility_tier` | ordinal | Integer-encoded if present; may be excluded if it introduces a facility-level shortcut. **Flag for evaluation:** measure whether `facility_tier` carries signal beyond what it legitimately should (e.g., if certain categories are only generated at certain tiers, that is a generator artifact, not a clinical signal) |

**Group 2 — Vitals (up to 16 features: 8 observed values + 8 provenance flags)**

| Feature | Type | Encoding |
|---|---|---|
| `bp_systolic`, `bp_diastolic`, `pulse`, `spo2`, `bmi`, `glucose`, `temperature`, `respiratory_rate` | continuous, nullable | Numeric. Missing values (`NOT_MEASURED`) are represented as **NaN** and handled by XGBoost's native NaN-routing — the same strategy Classifier A already uses for glucose (`glucose_missing_strategy: native_xgboost_nan_routing_no_imputation`, audit §A.1). **No imputation.** The measured-vs-imputed provenance flag exists precisely so NaN means "not measured", not "normal" |
| `<vital>_provenance` × 8 | categorical (4 values) | One-hot or ordinal-encoded. The provenance is a **first-class feature**, not metadata. Whether a vital was measured tells the model something different from what the vital's value tells it — and the old corpus's glucose-missingness leak (audit §C.2) existed precisely because missingness carried label information without being modelled as a feature. Making it an explicit input means the model can learn the *legitimate* relationship (some vitals are more often measured in some clinical contexts) rather than exploiting the *artifactual* one |

**Group 3 — Status columns (141 features)**

Each `<fieldId>__status` column carries one of: `ANSWERED`, `NOT_ASKED`, `UNKNOWN`, or empty
string (equivalent to `NOT_ASKED` for flattened columns).

| Encoding | Details |
|---|---|
| **Ordinal integer** | `NOT_ASKED` = 0, `UNKNOWN` = 1, `ANSWERED` = 2. The ordering reflects information content: `NOT_ASKED` means the tree never reached this node (path-determined, not clinically informative beyond the gating answers that caused it); `UNKNOWN` means the node was reached but the worker/patient didn't know; `ANSWERED` means a value is present in the paired answer column |

**Why status columns are features, not metadata.** The `NOT_ASKED` / `ANSWERED` pattern encodes
which *branch* of the tree was walked, which is highly informative about the category — a `fever`
encounter will have `fever_pattern__status = ANSWERED` while a `joint_pain` encounter will have
it as `NOT_ASKED`. This is **not a leak**: the worker selected the category (dropdown) before the
tree walk began, so the tree walk's shape is downstream of the label by construction. The status
pattern is the *structured representation* of the category selection, analogous to what the old
model learned from `symptom_string` but with explicit, auditable structure.

**The anti-leak discipline (dataset memo §2.4, GATE-LEAK).** Within a category, `UNKNOWN` status
must not correlate with the *condition* — GATE-LEAK asserts this at generation time by testing
independence of `__status` from `category_id` within stratum. The model may learn "fever
encounters have `fever_pattern` answered" (legitimate, path-determined); it must not learn
"`fever_pattern = UNKNOWN` means malaria" (an availability artifact).

**Group 4 — Single-answer fields (~100+ features after exclusions)**

Each is the answer to a tree node, with values from a fixed `optionId` set defined per node.

| Encoding | Details |
|---|---|
| **Label-encoded integer per field** | Each field's `optionId` set is small (typically 2–6 values) and fixed by the branch spec. Encode each as an integer. Missing values (when `__status != ANSWERED`) are represented as a **dedicated integer** (not NaN, not -1-as-missing), because XGBoost will route NaN but the missing-answer case is semantically different from a missing vital: it means "this question was not asked or not answered", which is already encoded in the status column. The answer column carries the content *conditional on being answered*. A dedicated missing-indicator integer prevents the model from treating "not asked" and a real answer value as the same split |

**Group 5 — Boolean multi-choice fields (178 features)**

Each `danger_signs__ds_*`, `previous_pregnancy_complication__pc_*`, etc. is a boolean column
(0/1 or True/False) indicating whether that specific option was selected in a multi-choice node.

| Encoding | Details |
|---|---|
| **Binary integer** | 0 or 1. When the parent multi-choice node's status is `NOT_ASKED`, all its children are 0 — structurally correct by construction |

### 2.3 Total feature count

| Group | Features |
|---|---|
| Demographics | 2–3 |
| Vitals observed | 8 (nullable) |
| Vitals provenance | 8 (one-hot: 32, or ordinal: 8) |
| Status columns | 141 |
| Single-answer fields | ~100 |
| Boolean multi-choice | 178 |
| **Total** | **~440–470** (exact count depends on provenance encoding) |

This is the same order of magnitude as the old model's 4,540 sparse TF-IDF features, but the
features are now **structured, named, typed, and bounded** rather than high-dimensional sparse
text. Every feature has a clinical interpretation, which is what makes SHAP explanations
meaningful rather than "this n-gram had high weight".

### 2.4 What is NOT in the feature set, and why

**No `symptom_string` or `narrative_terms`.** The old model's entire input was a text string. The
new model's input is the *structured output of the tree walk* — the answers to the questions the
device asked. This is the architectural transition the memo chain has been building toward: ASR
prose → Layer 1 normalization → controlled vocabulary → category selection → tree walk →
structured features. The classifier receives the structured features, not the prose.

**No embedding features.** Layer 2's MuRIL-class encoder (architecture memo §4, Layer 2) is used
**only** as nearest-trained-term lookup for phrases Layer 1 cannot match by rule. Its output is a
*controlled-vocabulary term* that feeds the tree walk, not a dense vector that feeds the
classifier. The classifier's feature basis is interpretable and inspectable by construction.

---

## 3. Model architecture — calibrated boosted head

### 3.1 The model

**XGBoost gradient-boosted decision trees**, matching the existing Classifier A and Classifier B
lineage. The architecture memo §4 Layer 2 specifies a "calibrated boosted model over the
normalized structured representation", preserving the interpretable feature basis and the existing
SHAP posture.

**Why XGBoost, restated against this corpus:**

| Property | XGBoost | Alternative |
|---|---|---|
| Native NaN handling | Yes — XGBoost learns optimal NaN-routing per split, which is precisely the mechanism for the 8 nullable vitals and the ~100 single-answer fields whose value is undefined when status ≠ ANSWERED | Imputation required, which is either uninformative (dataset memo defect 5) or introduces a training/serving skew |
| Categorical feature support | Native since 1.6 — `enable_categorical=True` handles the ordinal-encoded optionIds without requiring one-hot expansion | One-hot expansion inflates the feature space without adding information |
| Calibration | Not inherent — must be added explicitly (§4). But the shipped model already has `calibration_used_for_evaluation: false`, so this is not a regression, it is a fix | Same |
| On-device cost | Single-digit MB for the tree ensemble; sparse integer features, no float runtime beyond the vitals | A transformer classifier would add 184+ MB (architecture memo §3.2) |
| Determinism | Fully deterministic given fixed hyperparameters and fixed data order | Same |
| Validation burden | Feature importance and SHAP are inspectable per split. A retraining changes a small, enumerable set of trees, not an opaque 236M-parameter encoder | Architecture memo §3.3: end-to-end fine-tuning is "disqualifying" under IEC 62304 §5.6 |
| Imbalance handling | `scale_pos_weight`, `sample_weight`, and the `multi:softprob` objective with per-class weighting | Same tools exist for other tree families |

### 3.2 Objective and hyperparameters

**Objective: `multi:softprob`** with 27 classes. This produces a calibratable probability vector
rather than a hard classification, which is the input to both the calibration step (§4) and the
abstention gate (§5).

**Hyperparameter search space** (to be validated, not shipped as-is):

| Parameter | Range | Rationale |
|---|---|---|
| `n_estimators` | 200–800 | The old model used XGBoost defaults; 200–800 covers the useful range without overfitting on 23k rows |
| `max_depth` | 4–8 | Deeper trees risk memorizing the closed-pool structure; shallower trees may miss interaction effects between vitals and tree answers |
| `learning_rate` | 0.01–0.1 | Standard range; lower rates require more estimators but generalize better |
| `min_child_weight` | 5–50 | The tail class (`urinary_symptoms`, 68 rows) constrains this: a leaf must not require more samples than the smallest class can provide in a fold |
| `subsample` | 0.7–0.9 | Row subsampling per tree for variance reduction |
| `colsample_bytree` | 0.5–0.8 | Feature subsampling per tree — important because the 141 status columns are structurally correlated with the label (§2.2 Group 3) and should not all be available to every tree |
| `gamma` | 0–5 | Minimum loss reduction for a split; regularization |
| `reg_alpha`, `reg_lambda` | 0.01–10, 0.1–10 | L1 and L2 regularization on leaf weights |

**Search method:** Optuna with 5-fold stratified CV on the training set (§6), optimizing
**macro-averaged F1** (not accuracy, not micro-F1), because the clinical cost of missing a tail
category is disproportionate to its prevalence. The old model reported accuracy 0.9212 and
macro-F1 0.7983 — the 12-point gap is the imbalance showing through, and accuracy would hide it.

### 3.3 Class weighting

Two approaches, to be compared empirically:

1. **Inverse-frequency weighting.** `sample_weight[i] = N / (K * n_k)` where `n_k` is the count
   of class `k`. This gives `urinary_symptoms` (68 rows) a weight of 23000 / (27 × 68) ≈ 12.5
   and `fever` (7849 rows) a weight of 23000 / (27 × 7849) ≈ 0.109.

2. **Clinically-informed weighting.** Weight is `1 / n_k` × a clinical-severity multiplier that
   reflects the cost of missing the category. Categories with `redFlagGateway.gatewayStrength =
   HIGH` (fever, cough, breathlessness, chest_pain, etc.) get a multiplier > 1; low-risk
   categories get 1.0. **The multiplier values are declared, not silent**, recorded in the
   training manifest exactly as the answer model's weights are (dataset memo §2.3). `ASSUMED`
   is a legal value; silence is not.

**The principle (architecture memo §3.5):** recall on severe presentations over precision, with
the physician AGREE/MODIFY/REJECT gate absorbing the false positives.

### 3.4 Treatment of `other_not_in_list`

`other_not_in_list` (1,453 rows, 6.32%) is class 26. It is `ALWAYS_ABSTAIN` — a prediction of
`other_not_in_list` produces no differential, only `InferenceSource.UNAVAILABLE` plus
`PHYSICIAN_REVIEW_MANDATORY`.

**It must be trained at its natural prevalence, not downweighted.** Downweighting it teaches the
model to force every input into a clinical category, which is exactly the E66-at-74.95% failure
mode (H-20) wearing a different mask. A model that underpredicts `other_not_in_list` will
overconfidently assign OOD inputs to clinical categories, defeating the abstention gate before it
even fires.

---

## 4. Calibration — making the probabilities honest

### 4.1 The problem, stated concretely

The shipped model has `calibration_used_for_evaluation: false` (audit §A.1, §A.2). Its
`predict_proba` outputs are **uncalibrated**: a 0.7 probability does not mean the model is
correct 70% of the time when it says 0.7. The conformal abstention gate (§5) requires calibrated
probabilities to provide its coverage guarantee. **Calibration is a prerequisite to abstention,
not an independent improvement.**

### 4.2 Method: Venn-ABERS calibration

**Venn-ABERS predictors** (Vovk & Petej, 2014) are the recommended calibration method, for three
reasons that each independently disqualify the alternatives in this setting:

1. **Distribution-free validity.** Venn-ABERS provides well-calibrated multi-probabilities without
   assuming the model is well-specified or the data is IID — both of which are false here (the
   model is XGBoost trained on synthetic data; the deployment data is real PHC encounters).
   Platt scaling assumes a sigmoid relationship between logits and true probabilities; temperature
   scaling assumes a single scalar corrects the entire confidence surface. Neither assumption is
   defensible when training and deployment distributions differ.

2. **Automatic multi-class extension via one-vs-rest.** For K classes, K Venn-ABERS calibrators
   are fitted (one per class), each producing a calibrated probability for "is class k vs. not
   class k". The K calibrated probabilities are then normalized to sum to 1. This avoids the
   multi-class calibration problem (matrix scaling, Dirichlet calibration) where the calibration
   model itself has parameters that can overfit on a small calibration set.

3. **Finite-sample guarantees.** The calibrated probability `p` for class `k` satisfies
   `P(Y = k | p̂ = p) ∈ [p_lower, p_upper]` where the interval width shrinks as the calibration
   set grows. The width itself is a measure of calibration uncertainty that the abstention gate
   can use — a wide Venn-ABERS interval means "the model's confidence in this class is itself
   uncertain".

**Calibration set:** a held-out subset of the training data, **never** part of the training set
and **never** part of the test set. See §6 for the three-way split.

### 4.3 Calibration metrics — how to know it worked

| Metric | What it measures | Target |
|---|---|---|
| **Expected Calibration Error (ECE)** | Mean absolute difference between predicted probability and observed frequency, binned | < 0.05. The uncalibrated model's ECE is unknown because it was never measured (audit §A) |
| **Maximum Calibration Error (MCE)** | Worst-bin difference | < 0.15. The worst bin is the most dangerous one for a safety device |
| **Reliability diagram** | Visual: predicted probability vs. observed frequency, per class | Diagonal. Any class whose curve bows above or below the diagonal is miscalibrated in that range |
| **Brier score** | Mean squared error of the probability vector | Lower is better; decomposable into reliability (calibration) + resolution + uncertainty |

**Per-class calibration is mandatory, not optional.** A model can be well-calibrated on average
while being catastrophically miscalibrated on `breathlessness` or `chest_pain`. Per-class
reliability diagrams and per-class ECE are reported for all 27 classes.

### 4.4 What changes at inference time

The inference pipeline becomes:

```
raw_features → XGBoost.predict_proba → [27-dim uncalibrated vector]
    → Venn-ABERS calibrator × 27 → [27-dim calibrated vector]
    → Conformal gate (§5) → RETAIN with ranked differential
                           or ABSTAIN with InferenceSource.UNAVAILABLE
```

The Venn-ABERS calibrator is a **frozen artifact** shipped in the APK alongside the XGBoost model.
It is a set of K isotonic regression models (one per class), each parameterized by a sorted array
of threshold-probability pairs learned from the calibration set. Total size: negligible (a few KB
per class). No float runtime beyond what XGBoost already requires.

---

## 5. Conformal abstention — Layer 3

### 5.1 The clinical question this answers

> When should the device refuse to produce a differential and instead emit
> `InferenceSource.UNAVAILABLE`?

Not "when the model is wrong" — that is unknowable at inference time. But "when the model's
calibrated confidence is below a threshold that guarantees, over the calibration set, a minimum
accuracy on retained predictions."

### 5.2 The mechanism: Inductive Conformal Prediction (ICP)

Given a new input `x` with calibrated probability vector `p̂ = [p̂_1, ..., p̂_27]`:

1. **Compute the nonconformity score:** `α(x) = 1 - max(p̂)`. This is the complement of the
   model's highest calibrated confidence. A high `α` means the model is uncertain.

2. **Compare against the calibration quantile:** On the calibration set, compute the `α` scores
   for all correctly-classified examples. The **(1 - ε) quantile** of these scores is the
   threshold `τ`, where `ε` is the **target error rate** — the maximum fraction of retained
   predictions allowed to be wrong.

3. **Decision:**
   - If `α(x) ≤ τ`: **RETAIN** — produce the ranked differential from the calibrated
     probabilities.
   - If `α(x) > τ`: **ABSTAIN** — emit `InferenceSource.UNAVAILABLE`, no differential.

### 5.3 The coverage guarantee

By the exchangeability assumption of conformal prediction:

> **P(Y ≠ ŷ | RETAIN) ≤ ε**

with finite-sample validity — no asymptotics, no model assumptions. The guarantee holds for any
model, any feature encoding, any data distribution, as long as the calibration set and the
deployment data are exchangeable (drawn from the same distribution). **They are not, and this is
the honest-numbers framing (§7): the guarantee holds on the calibration set's distribution, and
any claim about field performance must be qualified with the distribution gap.**

### 5.4 Setting ε — the target error rate

ε is not a hyperparameter to be optimized. It is a **clinical safety decision** — a declared
constant that reflects the operator's tolerance for wrong answers among cases the device retains.

| ε | Meaning | Consequence |
|---|---|---|
| 0.10 | ≤ 10% error among retained predictions | More cases retained; some wrong answers |
| 0.05 | ≤ 5% error among retained predictions | Fewer cases retained; more abstentions |
| 0.01 | ≤ 1% error among retained predictions | Many abstentions; very few wrong answers |

**Recommendation: ε = 0.05 as the starting point, with per-class monitoring.** A 5% error rate
among retained predictions means that for every 20 cases the device produces a differential, at
most 1 is wrong (on the calibration distribution). But:

> **ε is a global guarantee, not a per-class one.** A model that is very accurate on `fever`
> (34% of data) and inaccurate on `urinary_symptoms` (0.3%) will retain most fever cases and
> abstain on most urinary cases — which is correct behaviour, not a defect. But it means the
> **effective abstention rate differs by category**, and that must be reported honestly (§7.3).

**ε is a declared constant in the training manifest**, not a silent weight. If the operator changes
it, that is an algorithm change under the ACP — the same discipline the model weights themselves
carry.

### 5.5 Interaction with DECISION-1 (REFER_EMERGENCY → abstain)

DECISION-1 (batch-4 memo) says: `REFER_EMERGENCY` floor forces abstention, classifier not
called. This is a **rule-based abstention** that fires before the conformal gate.

The two abstention channels are complementary:

| Channel | Trigger | Mechanism | When |
|---|---|---|---|
| **Rule-based** (DECISION-1) | `disposition_floor = REFER_EMERGENCY` | The tree walk or vitals triage raised the disposition to emergency | Before inference |
| **Conformal** (§5.2) | `α(x) > τ` | The model's calibrated confidence is too low | After inference |

A case that triggers DECISION-1 never reaches the conformal gate. A case that passes DECISION-1
(disposition is `PHYSICIAN_REVIEW_MANDATORY` or `REFER_URGENT`) then faces the conformal gate.
Both channels produce `InferenceSource.UNAVAILABLE`; the reason code distinguishes them.

### 5.6 Interaction with `other_not_in_list`

If the model's top-1 prediction is `other_not_in_list` (class 26), the correct behavior is
**abstain regardless of confidence**. `other_not_in_list` is `ALWAYS_ABSTAIN` by category schema
(category memo §4.2); the model predicting it is the model saying "this encounter is outside my
scope". Whether the conformal gate would have abstained anyway is irrelevant — the category-level
`ALWAYS_ABSTAIN` property is checked first.

The full inference decision tree:

```
1. If disposition_floor == REFER_EMERGENCY → ABSTAIN (DECISION-1)
2. If top-1 prediction == other_not_in_list → ABSTAIN (category schema)
3. If α(x) > τ                             → ABSTAIN (conformal gate)
4. Otherwise                                → RETAIN, produce ranked differential
```

### 5.7 Training the conformal gate

The conformal threshold `τ` is computed on the **calibration set** (§6, the same set used for
Venn-ABERS calibration — it serves double duty). The computation:

1. Run the calibrated model on all calibration-set examples.
2. For each example where the model's top-1 prediction is correct, record `α = 1 - max(p̂)`.
3. Sort the α values. τ is the `⌈(1-ε)(n+1)⌉ / n` quantile.

τ is then a **frozen constant** shipped alongside the model, exactly as the Venn-ABERS calibrator
parameters are. Changing ε changes τ, which is an algorithm change.

---

## 6. Train/eval discipline — the splits

### 6.1 Three-way split

The corpus is split into three **disjoint, stratified** subsets:

| Split | Purpose | Recommended size | Touched by |
|---|---|---|---|
| **Train** | XGBoost fitting | 70% (~16,100 rows) | Training only |
| **Calibration** | Venn-ABERS calibration + conformal threshold τ | 15% (~3,450 rows) | Calibration only — never seen during training |
| **Test** | Final held-out evaluation, reported once | 15% (~3,450 rows) | Evaluation only — never seen during training or calibration |

**Stratification** is by `category_id`, ensuring every category appears in every split at its
corpus proportion. For `urinary_symptoms` (68 rows), that means ~48 train / ~10 calibration / ~10
test — small but sufficient for conformal calibration (the conformal guarantee degrades gracefully
with small calibration sets; the interval width increases but the coverage guarantee holds).

### 6.2 Cross-validation on the training split

Hyperparameter search (§3.2) uses **5-fold stratified cross-validation within the training
split only**. The calibration and test sets are untouched during this phase.

After the best hyperparameters are selected:
1. Retrain XGBoost on the full training set with those hyperparameters.
2. Calibrate on the calibration set.
3. Evaluate on the test set, once, and report.

### 6.3 No data leakage — the discipline, enumerated

| Leak vector | Prevention |
|---|---|
| Test-set contamination | Test set drawn once, used once, reported once. No hyperparameter was chosen to improve it |
| Calibration-set contamination | Calibration set not used in training or hyperparameter search |
| Row-level leakage | No `patient_id` carries across splits — each `row_id` is unique by construction (counter-based RNG, §7 of dataset memo). Note: the drishti_v2 corpus has no longitudinal patient structure; each row is an independent encounter. If future corpora introduce longitudinal data, patient-level splitting is mandatory |
| Feature leakage | §2.1 exclusion list enforced at data-loading time, before any model sees any feature. The excluded columns are not loaded into the feature matrix at all — not loaded and then dropped, not loaded and then masked. Not loaded |
| Target leakage through status columns | GATE-LEAK (dataset memo §2.5) asserts at generation time that `__status` does not correlate with `category_id` beyond what the path structurally forces. The structural correlation (fever encounters have fever-related fields answered) is legitimate and intentional |

### 6.4 Reproducibility

The split is determined by the corpus's `row_id` field and a declared split seed. Given the same
corpus, the same split seed, and the same stratification code, the split is byte-identical.

```python
from sklearn.model_selection import StratifiedShuffleSplit

SPLIT_SEED = <declared_constant>

splitter1 = StratifiedShuffleSplit(n_splits=1, test_size=0.30, random_state=SPLIT_SEED)
train_idx, rest_idx = next(splitter1.split(X, y))

splitter2 = StratifiedShuffleSplit(n_splits=1, test_size=0.50, random_state=SPLIT_SEED + 1)
cal_idx, test_idx = next(splitter2.split(X[rest_idx], y[rest_idx]))
```

The split seed, the split indices, and the split counts per class are recorded in the training
manifest.

---

## 7. Honest-numbers framing — what the metrics mean and what they don't

### 7.1 The principle

> **Every reported figure is qualified with the distribution it was measured on and the
> distribution it claims to generalize to.** A number without a denominator is not a number.

The architecture memo §11 states it: *"It does not claim any accuracy figure for the recommended
architecture. None has been measured, and none can be until the relabelling and the eval sets
exist."* This memo does not change that — it defines how the figures will be produced and how they
will be framed.

### 7.2 The metrics, and their honest interpretation

**Primary metric: macro-averaged F1 on the test set.**

- Macro-F1 treats every class equally regardless of prevalence, which is the right metric when the
  clinical cost of missing a rare category is at least as high as the cost of missing a common
  one.
- The old model's macro-F1 was 0.7983 (audit §A.2). **This number is void** — it was measured on
  a label space that answers a different question (category memo §9: "that is not a regression; it
  is the correction"). No comparison between the new model's macro-F1 and the old model's is
  meaningful.

**Secondary metrics, all reported:**

| Metric | What it measures | Honest caveat |
|---|---|---|
| Per-class F1 | Performance on each category individually | The three old worst classes (A91 0.381, J22 0.364, B50 0.400) no longer exist as classes; their failure mode is now caught by gateways |
| Per-class precision and recall | Separately, because they tell different stories | High recall + low precision = too many false alarms; the physician gate absorbs these. Low recall = missed cases; nothing absorbs these |
| Confusion matrix | Where the model confuses categories | Expected: within-chapter confusions (fever ↔ body_ache, cough ↔ cold_sore_throat) will be more common than cross-chapter ones |
| ECE and per-class ECE | Calibration quality | Post-Venn-ABERS; if calibration fails, the conformal guarantee is invalid |
| Abstention rate at ε = 0.05 | What fraction of test-set cases the model refuses to answer | A high abstention rate is honest, not a failure. A low abstention rate with low accuracy is dangerous |
| Accuracy on retained predictions | The conformal guarantee's empirical verification | Must be ≥ (1 - ε) on the test set; if it is not, the calibration set was too small or the exchangeability assumption failed |
| Per-class abstention rate | Whether the model systematically refuses to answer certain categories | `urinary_symptoms` will have a high abstention rate. That is correct. A category the model cannot reliably predict should produce a high abstention rate, not a confident wrong answer |

### 7.3 What none of these numbers measure

| Gap | Why it matters | How to close it (not in this memo's scope) |
|---|---|---|
| **Generalization to real PHC encounters** | The model is trained on synthetic data generated by a tree walker. Real encounters have free-text prose, vernacular phrasing, incomplete forms, environmental noise. No synthetic metric measures real-world performance | The OOD eval set (dataset memo §8.2) — which must come from real recorded encounters under consent, not from the generator |
| **Robustness to perturbation** | The model may be brittle to input perturbations that do not change the clinical content (typos, answer reordering, single-field drops) | The perturbation set (dataset memo §8.1) — built by applying declared perturbation operators to held-out rows |
| **Temporal stability** | The model is trained on a 24-month encounter window. Seasonal patterns, emerging diseases, and population shifts are not captured | Periodic re-evaluation against new real data, under the controlled retraining discipline (audit §I) |
| **The Odisha-vs-MP gap** | The target marginal is anchored on Odisha, not Madhya Pradesh. The presenting-complaint distribution of Indore/Dhar PHCs may differ | Field data collection in MP, when available |

### 7.4 What to report, and what not to quote

**Report:** every metric in §7.2, qualified with "measured on the drishti_v2 synthetic test set,
generated at seed X, against the category_id label space defined in category_registry version Y."

**Do not quote:** any metric as a clinical performance claim. `readme.md:164-171` already states
this in the project's own words, and it remains correct. A synthetic test set measures the model's
ability to invert a generator; it does not measure the model's ability to classify real patients.
The metrics are necessary (a model that fails on synthetic data will certainly fail on real data)
but not sufficient (a model that passes on synthetic data may still fail on real data).

---

## 8. On-device inference — the deployment contract

### 8.1 Artifacts shipped in the APK

| Artifact | Format | Size estimate | Contents |
|---|---|---|---|
| XGBoost model | XGBoost binary or ONNX | ~1–5 MB | The trained tree ensemble |
| Venn-ABERS calibrator | JSON or protobuf | ~50–200 KB | 27 isotonic regression models (sorted threshold arrays) |
| Conformal threshold τ | Single float constant | Bytes | The abstention threshold for the declared ε |
| Label encoder | JSON | ~2 KB | category_id ↔ integer mapping |
| Feature schema | JSON | ~10 KB | Column names, types, encoding rules — the contract between the tree walker and the model |
| Training manifest | JSON | ~5 KB (subset) | Model version, training seed, split seed, ε, τ, category_registry_version, corpus version, generator_commit — enough to reproduce the model from scratch |

**Total on-device classifier footprint: < 10 MB.** Co-resident with Parakeet's 622 MiB ASR model,
this is negligible.

### 8.2 Inference latency

XGBoost inference on ~450 features for 27 classes is a sequence of tree traversals — each a series
of integer comparisons. On a Pixel-class ARM64 CPU, this is expected to be **< 10 ms** (the old
model's inference was already sub-millisecond on 4,540 sparse features). The Venn-ABERS calibration
adds 27 isotonic lookups (binary search on sorted arrays), each O(log n) where n ~ 3,450 (the
calibration set size). Total calibration overhead: microseconds.

The conformal gate is a single float comparison. Total overhead: negligible.

### 8.3 Determinism

The inference pipeline is fully deterministic: given the same input features, the same XGBoost
model, the same calibrator, and the same τ, the output is identical. No random sampling, no
dropout, no temperature, no stochastic anything.

### 8.4 The model-change-is-a-design-change rule

Per the SOUP record (`model-soup-2026-09-02-v1.0.json`):

> Every component below is compiled into the APK and changes only by shipping a new app release.
> That makes a model change a design change under normal change control, not a post-deployment
> model update.

The XGBoost model, the Venn-ABERS calibrator, and the conformal threshold τ are all compiled into
the APK. Changing any of them is a design change, requiring:
- A new training run with a recorded manifest
- Re-calibration on the calibration set
- Re-evaluation on the test set
- Per-class no-regression check (audit §I.2 gate 5)
- Hazard re-assessment
- Operator sign-off
- Version bump
- DHF entry

---

## 9. The training pipeline — what gets built

### 9.1 Pipeline stages, in order

```
1. LOAD         corpus.csv → DataFrame, apply §2.1 exclusion list
2. ENCODE       §2.2 feature encoding → feature matrix X, label vector y
3. SPLIT        §6.1 three-way stratified split → X_train, X_cal, X_test
4. SEARCH       §3.2 Optuna + 5-fold CV on X_train → best hyperparameters
5. TRAIN        XGBoost.fit(X_train, y_train) with best hyperparameters
6. CALIBRATE    Venn-ABERS on X_cal → 27 calibrators
7. THRESHOLD    Conformal threshold τ on X_cal → single float
8. EVALUATE     §7.2 full metric suite on X_test
9. MANIFEST     Record everything: seeds, splits, hyperparameters, metrics,
                corpus version, category_registry version, ε, τ, model hash
10. EXPORT      XGBoost binary + calibrators + τ + manifest + feature schema
```

### 9.2 Build gates — what fails the training run

| Gate | What it checks | Fails on |
|---|---|---|
| **GATE-CALIBRATION** | ECE < 0.05 on calibration set | Miscalibrated model — conformal guarantee invalid |
| **GATE-ABSTENTION** | Accuracy on retained test-set predictions ≥ (1 - ε) | Conformal guarantee violated empirically |
| **GATE-TAIL** | Per-class F1 ≥ declared floor for every class | A model that sacrifices the tail for the head — the exact pattern the old model exhibited (J22 0.364, B50 0.400, A91 0.381). The floor values are declared constants, not hyperparameters |
| **GATE-REGRESSION** | If a previous model exists: per-class F1 ≥ previous model's per-class F1 minus a declared tolerance | A retrain that improves macro-F1 while degrading a specific class (audit §I.2 gate 5) |
| **GATE-MANIFEST** | The manifest is complete and self-consistent | A model whose provenance cannot be reconstructed |

### 9.3 Outputs

| Output | Location | Tracked |
|---|---|---|
| XGBoost model binary | `models/category_model.xgb` | Git LFS or versioned artifact store |
| Model metadata | `models/category_model_meta.json` | Git |
| Venn-ABERS calibrators | `models/category_calibrators.json` | Git |
| Conformal threshold | In `category_model_meta.json` as `conformal_threshold` | Git |
| Feature schema | `models/category_feature_schema.json` | Git |
| Training manifest | `logs/category_training_manifest.json` | Git |
| Training log | `logs/category_training_log.jsonl` (append-only) | Git |

---

## 10. Dependencies — what is NOT in this memo

| Item | Status |
|---|---|
| **Building the training pipeline** | Not started. This memo is the specification it would be built against |
| **Running training** | Not started. No model produced |
| **Layer 1 normalization** | Separate workstream. This memo's model receives tree-walk output, not prose |
| **On-device port** | The architecture memo §1.4 establishes this is a port. This memo designs the model; the port designs the runtime |
| **Real OOD eval set** | Not started. Gated on consent and ethics (dataset memo §8.2) |
| **Perturbation eval set** | Not started. Depends on the trained model to perturb against (dataset memo §8.1) |
| **Removing `RetrofitEvaluateSource`'s vitals defaults** | Precondition (dataset memo §5.3). The model trains on honest absence; the app must serve honest absence |
| **ACP (Algorithm Change Protocol)** | `qms-overview.md` lists it as TODO. ε and τ are algorithm parameters; changing them is an algorithm change. The ACP must exist before any of these constants are shipped |

---

## 11. Two open questions for the operator

### Q1. The `facility_tier` feature

`facility_tier` is present in the corpus. If it carries legitimate clinical signal (different
facilities see different patient mixes), it should be a feature. If it carries only generator
artifacts (the generator assigned categories to facilities in a pattern), it should be excluded.

**Recommendation:** include it in the initial training, then measure its SHAP importance and its
contribution to per-class F1. If removing it does not degrade performance, remove it — a model
that depends on facility identity is not portable across facilities.

### Q2. The clinical-severity multiplier for class weighting

§3.3 option 2 proposes weighting classes by clinical severity, not just inverse frequency. The
multiplier values are the operator's decision — they encode how much worse it is to miss a
`chest_pain` than to miss a `cold_sore_throat`. This memo defines the mechanism; the numbers
require clinical judgement.

**If the operator defers this decision:** use inverse-frequency weighting (option 1) for the first
training run, and measure per-class recall. The classes with low recall despite inverse-frequency
weighting are the candidates for clinical multipliers.

---

## 12. What was NOT done

- No model was trained, no weights were produced, no inference was run.
- No code was written — no training script, no data loader, no evaluation harness.
- No corpus was touched — `corpus.csv` was read; nothing was written.
- No `.env` or credential file was opened.
- The feature exclusion list (§2.1) was derived from the column groups read on disk (§0.2), not
  from any training experiment.
- The calibration method was selected from the literature; no calibration was performed.
- ε = 0.05 is a recommendation, not a shipped constant.
- The hyperparameter ranges (§3.2) are search bounds, not selected values.

---

## Sources

- Vovk V, Petej I. *Venn-ABERS predictors.* In: Proceedings of the Thirtieth Conference on
  Uncertainty in Artificial Intelligence (UAI), 2014. — The foundational Venn-ABERS paper.
- Angelopoulos AN, Bates S. *A Gentle Introduction to Conformal Prediction and
  Distribution-Free Uncertainty Quantification.* Foundations and Trends in Machine Learning,
  2023. — The conformal prediction tutorial this memo's §5 is built on.
- Shafer G, Vovk V. *A Tutorial on Conformal Prediction.* JMLR 9:371-421, 2008. — The original
  conformal prediction framework.
- Kompa B, Snoek J, Beam AL. *Second opinion needed: communicating uncertainty in medical machine
  learning.* npj Digital Medicine 4, 4 (2021). — The honest-numbers framing.
- The full binding memo chain listed in the header of this document.
