# Dataset regeneration (STEP 1, read-only design memo)

Run date: 2026-09-06. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af` — read-only, `git status --short` empty.
dataset-make `main`, HEAD `d32a197` — read-only. **Baseline recorded before any read: the working
tree was already dirty** (`drishti_pipeline/scratch/*.csv` and `output_india/csv/*.csv` modified by
a prior run). That dirt is pre-existing, not this session's. Nothing written, staged or committed
there.

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
dataset, model, config, generator or tree file touched, in any of the three repos. No generation
run, no training, no side-effecting command. No `.env`, `local.properties` or credential file opened
at any point. No commit, no staging.

**Inputs treated as established fact and not re-derived:**
`scratchpad/questionnaire-tree-design-memo.md` (its §7 dataset contract is this memo's **binding
specification**), `scratchpad/reason-for-encounter-category-system-memo.md` (the label space and the
§2/§7 Indian-PHC anchors), `scratchpad/production-classifier-architecture-memo.md`,
`scratchpad/classifier-dataset-nlem-audit-memo.md` (every defect this regeneration must not repeat).

**Generator grounding, read on disk this session:** `drishti_pipeline/run_pipeline.py`, `config.py`,
`step2_extract_vitals.py`, `step3_tiered_generator.py`, `step4_symptom_pairing.py`,
`step5_aggregate.py`, `step6_noise_injection.py`; `SaMDClassifier/models/model.json` (feature
names); `canonical_dataset.csv` header.

---

## 0. One correction, first, because it changes a downstream decision

The questionnaire-tree memo §2.2 and §7.2 state that the six `<vital>_observed` columns are the
measured-versus-imputed provenance flags, and that the corpus therefore already carries the
discipline the architecture memo §5 demands. **That is wrong, and disk says so plainly:**

- `config.py:278-283` and `:311-316` — the six `_observed` columns are typed **`float32`**, not
  boolean.
- `step6_noise_injection.py:71-72` — `df.loc[mask, obs_col] = noised[mask]`. The **Gaussian-noised
  measurement** is what lands in `<vital>_observed`.
- Its own log note, `step6:86` — *"Observed noised values are written to _observed columns."*
- `SaMDClassifier/src/refine_diagnosis.py:188-193` — Classifier A's features are read from
  `bp_systolic_observed`, `bp_diastolic_observed`, `bmi_observed`, `pulse_observed`,
  `spo2_observed`, `glucose_observed`.

So the existing pair means: `<vital>` = the generator's ground truth, `<vital>_observed` = the
simulated device reading, and the model trains on the latter while the label was computed from the
former (`step6`'s design note, `:7-12`). That is a deliberate and defensible label-noise setup. It
is **not** provenance.

> **There is no measured-versus-imputed flag anywhere in the corpus.** `step2.impute_missing_vitals`
> fills every missing SpO2 and pulse from `rng.uniform(95,100)` and `rng.uniform(60,100)`
> (`step2:157-169`), step6 then noises those imputed values identically to real ones, and nothing
> downstream can tell them apart. The corpus has exactly the defect the app has at
> `RetrofitEvaluateSource.kt:38-66`, one layer deeper.

Consequence for this memo: the regenerated schema needs a **third** column per vital, and it must
not be called `_observed` — that name is taken and means something else. §5 defines it.

---

## 1. Column list — deltas against tree memo §7.2

Tree memo §7.2 is binding and is not restated. Confirmed aligned, unchanged: the label is
`category_id`; `icd10_mapping` / `icpc3_basis` are carried as non-feature coding-backbone
attributes; every tree field is a `<fieldId>` + `<fieldId>__status` pair; `MULTI_CHOICE` fields
expand to one boolean column per `optionId`; `path_taken` / `fired_gateways` / `disposition_floor` /
`routed_to` are audit columns excluded from the feature set.

Six deltas.

### D1 — the per-vital pair becomes a triple

| Column | Meaning | Feature? |
|---|---|---|
| `<vital>_true` | Generator ground truth. The value the latent clinical state actually had. | **Never.** Audit and label-construction only |
| `<vital>` | The reading the device or worker actually saw — truth plus measurement error, or null | **Yes.** This is the model's feature, matching `refine_diagnosis.py:188-193`'s existing habit of feeding the observed value |
| `<vital>_provenance` | `DEVICE_MEASURED` · `MANUAL_ENTERED` · `NOT_MEASURED` · `DEVICE_ERROR` | **Yes** (§5) |

The `_observed` suffix is retired to avoid carrying a name that already means "noised value" into a
schema where it would mean "provenance flag". Renaming is cheaper than a permanent ambiguity in a
regulated corpus.

### D2 — `temperature` and `respiratory_rate` must be added as first-class vitals

`config.py:94-102` (`VITAL_LOINCS`) extracts six vitals: pulse, SpO2, BP systolic, BP diastolic,
BMI, glucose. **Temperature and respiratory rate do not exist in the corpus at all.**

The two-path emergency trigger (§6) keys on `temp < 35` and `RR ≥ 30 or < 9`. The `fever` branch's
FV-03 node prefills from measured temperature. The app's `EvaluateRequestDto` already carries both
as optional fields, and `VitalsSnapshot.kt:7-17` already captures both.

> **Hard prerequisite: emergency Path 1 is unrepresentable in the corpus until these two columns
> exist.** This is not a nice-to-have; a trigger the corpus cannot contain is a trigger the model
> cannot be evaluated against.

### D3 — the age window must open below 5

`config.py:36-37`: `MIN_AGE = 5`, `MAX_AGE = 80`. The corpus contains **no under-5 rows at all.**

The tree's paediatric gateways key on exactly that band — `GW-FEV-EMG-5` fires on
`age_band < 5 AND danger_signs contains ds_cannot_feed`, and §6's child trigger path is IMCI danger
signs plus SpO2 < 90%. Under-5 fever, diarrhoea and pneumonia are also among the highest-volume and
highest-mortality PHC presentations in rural India.

> **Second hard prerequisite: the paediatric emergency path cannot be exercised by a corpus whose
> minimum age is 5.** The window must open to 0 (or at minimum to 2 months, the IMCI young-infant
> boundary), with paediatric-appropriate thresholds and the existing `PEDIATRIC_BMI_STRATEGY = "B2"`
> not-assessed rule preserved.

### D4 — `tier` and `abnormal_params` leave the differential model's feature set

Both are deterministic functions of the vitals crossed with the threshold table
(`step3.assess_row`, `step3.assess_dataframe`). Feeding them to the differential model is the same
error as feeding `disposition_floor`: the model learns to reproduce the rule rather than to read the
patient, and its accuracy becomes a measurement of the threshold table.

`tier` remains **Classifier A's label** and both remain audit columns. Neither is a Classifier B
feature. This extends tree memo §7.4 rule 3 by the same argument that motivated it.

### D5 — retirements, confirmed

`icd_candidate` → `category_id`. `symptom_string` → `narrative_terms` (Layer 1 controlled terms).
`symptom_signal_strength`, `differential_candidates` → deleted as generator artifacts with no
capture affordance. `fever_pattern_flag` → deleted; replaced by the tree's six-valued `fever_pattern`
node, which is *asked* rather than hashed (§4, defect 4).

### D6 — per-row manifest columns

Added alongside tree memo §7.2's `branch_version` and `category_registry_version`:
`row_id` · `master_seed` · `stream_id` · `generator_commit` · `threshold_table_version` ·
`answer_model_version`. §7 explains why each is per-row rather than only in the manifest.

---

## 2. The tree-walk generation mechanism (rule 1)

> **Rule 1 (tree memo §7.4).** Generate by walking the tree, not by sampling columns. Every synthetic
> row is produced by an actual branch traversal, so `NOT_ASKED` is path-determined. Independent field
> sampling is forbidden.

### 2.1 What the current pipeline does, and why the fix is an inversion

The current pipeline runs **vitals → label**:

1. Synthea emits US-prevalence patients (`step1`).
2. `step2` extracts six vitals, imputes SpO2/pulse, drops sparse rows.
3. `step3.assess_row` marks abnormal params against Indian thresholds; `_balanced_sample` imposes a
   uniform quota over `abnormal_params` combinations; `resolve_condition` picks a condition from
   hand-set weights.
4. `step4.sample_symptom` samples symptoms **from the resolved condition's pool**.

The label is *derived from* the vitals, and the symptoms are *derived from* the label. The model is
therefore trained to invert a generator, which is why v0.1 scored 1.0000 and why v0.2's 0.9212 is
measuring separability rather than generalization (audit §G.1).

**The fix is to run the arrow the other way**, matching how an encounter actually happens.

### 2.2 The five stages

```
1. DRAW CATEGORY          category_id ~ TargetMarginal            (§3)
2. INSTANTIATE LATENT     condition | category_id
                          severity  | condition
                          vitals_true | condition, severity, age, sex
3. WALK THE BRANCH        for each node reached:
                            answer ~ AnswerModel(node, condition, severity)
                          gateways evaluate as rules
                          pathTaken accumulates
4. NOT_ASKED              = every node the walk never reached      (by construction)
5. FLATTEN                BranchOutput -> one row
```

**Stage 3 executes the same authored branch file the device executes.** Not a re-implementation, not
a copy — the same artifact, loaded by both. That is what makes tree memo R4 enforceable: a branch
change that adds or renames a `fieldId` invalidates the corpus mechanically, because the walker
cannot produce the old columns any more.

### 2.3 The answer model — the honest successor to `ICD_MAPPING`

For each `(node, condition, severity)` the answer model is a declared distribution over that node's
**fixed `optionId` set**. It is the only place clinical knowledge enters generation, and every entry
carries the category memo §2 provenance typing:

```
AnswerModelEntry {
  nodeId, conditionId, severityBand
  distribution: Map<optionId, weight>     // sums to 1.0, asserted at load
  sourceType    // IND-PRESENT | IND-POP | IND-PROG | WORLD | ASSUMED
  source        // citation, or the reasoning when ASSUMED
  declaredOn    // date
  reviewedBy    // the physician who signed this entry
}
```

The current `ICD_MAPPING` weights — hypertension 0.70 / white-coat 0.15 / migraine 0.15, obesity
0.65 / osteoarthritis 0.35, the fever block 0.20/0.20/0.20/0.10/0.10/0.10 — carry no provenance
whatsoever (audit §D.1(2)). **Fixing that does not mean finding a citation for every number.** It
means every number is *typed, sourced-or-declared-ASSUMED, dated and signed*. `ASSUMED` is a legal
value; silence is not. This is the category memo's own discipline applied one level down.

`config.py:1341-1347` already asserts candidate weights sum to 1.0 at module load. Keep that; extend
it to assert that every entry has a non-empty `sourceType`, `source` and `declaredOn`.

### 2.4 Anti-leak by construction (rule 2)

> **Rule 2.** `__status` must never correlate with the label beyond what the path structurally
> forces.

Two nuisance channels decide what is missing. Both are drawn from distributions that depend **only**
on node identity and facility/worker covariates — never on the latent condition:

| Channel | Drawn from | Never depends on |
|---|---|---|
| `UNKNOWN` (worker asked, patient/worker does not know) | node id, worker cadre, facility, time of day | the latent condition or its severity |
| Vitals availability (`NOT_MEASURED`) | device present, consumable in stock, staffing, facility tier | the latent condition or its severity |

Because condition ⊥ nuisance holds **by sampling design**, the glucose-missingness leak the audit
measured (§C.2: present-rate 1.000 for E11, 0.000 for A91/B50/J22) cannot be generated. The current
pipeline produced it structurally — `glucose_high` cannot enter `abnormal_params` unless a glucose
value exists, so presence *was* the label route. Under the new design glucose presence is a
supply-chain draw and the label was already fixed at stage 1.

`NOT_ASKED` needs no such treatment: it is a deterministic function of prior answers, so conditional
on the gating answers it carries no information at all. That is the whole reason stage 4 is defined
as "whatever the walk never reached" rather than as a post-hoc blanking pass.

### 2.5 The assertion the run makes on its own output

Rule 2 is stated above as a property. It is enforced as a **build gate that fails the run**, not a
warning:

> **GATE-LEAK.** For every `fieldId` *f*: stratify rows by `category_id` **and** by the answer values
> on the nodes that gate *f*. Within each stratum, test independence of `f.__status` from
> `category_id`. Report mutual information and a χ² p-value per stratum. If any stratum's mutual
> information exceeds the declared threshold `MI_MAX` (a manifest constant), **the generation run
> exits non-zero and writes no corpus.**

Two notes on honesty. First, the stratification is what makes the test meaningful — an unstratified
test would flag the *legitimate* path-determined correlation and be useless. Second, `MI_MAX` is a
declared number and should be set from a null run (generate with the label shuffled, measure the
distribution of MI under the null, set the threshold above its upper tail). Picking it by intuition
would be another silent weight.

The same gate shape applies to vitals: `<vital>_provenance == NOT_MEASURED` must be independent of
`category_id` within stratum.

---

## 3. Anchor distribution → class balance (rule 4)

> **Rule 4.** Class balance follows the category memo §7 Indian-PHC anchors, not a uniform quota.

### 3.1 The mechanism is stage 1, not a post-hoc resample

`category_id` is **drawn first**, from an explicit target marginal. The class mix is therefore an
*input* to generation, recorded in the manifest and asserted against the realized output — not an
emergent property of `_balanced_sample`'s quota arithmetic. `step3._balanced_sample` is deleted
outright, and with it the entire uniform-quota defect.

### 3.2 Constructing the target marginal — and the denominator problem, stated

The category memo §0.1 established that the two Odisha tables have **different denominators**:
Table 3's "Primary" is PHC-only (n = 485), Table 2's is PHC + CHC pooled (n = 701). The chapter
shares therefore **cannot be concatenated** — doing so sums to 108.1%, and the excess is not error,
it is the denominator difference showing through.

Construction, with the rescale declared rather than hidden:

1. **The three T3 chapters anchor the core**, at PHC-only granularity: General **45.6%**, Digestive
   **17.7%**, Musculoskeletal **12.0%** — together **75.3%**, exactly the coverage the paper reports
   for those three.
2. **The remaining 24.7%** is apportioned across the chapters only Table 2 reports at primary level,
   *in their Table 2 proportions*: Respiratory 10.1, Circulatory 6.6, Skin 5.7, Pregnancy 5.0,
   Neurological 3.6, Endocrine 1.3, Urinary 0.4, Blood 0.1, plus the four realized-but-deferred
   chapters (eye 1.4, ear 1.4, genital 0.9, psychological 0.1). Those sum to **36.6%**, so the
   rescale factor is **24.7 / 36.6 = 0.6749**, and it is a manifest constant.

| Chapter | T2 share | × 0.6749 | Categories (within-chapter split, all `ASSUMED`) |
|---|---|---|---|
| A General | — (T3 45.6) | **45.60** | `fever` 36.03 · `weakness_unwell` 4.56 · `body_ache` 3.19 · `weight_loss` 0.91 · `oedema` 0.91 |
| D Digestive | — (T3 17.7) | **17.70** | `abdominal_pain` 7.08 · `acidity_heartburn` 4.96 · `diarrhoea` 3.54 · `vomiting_nausea` 2.12 |
| L Musculoskeletal | — (T3 12.0) | **12.00** | `joint_pain` 5.40 · `back_neck_pain` 3.60 · `injury` 3.00 |
| R Respiratory | 10.1 | **6.82** | `cough` 3.07 · `cold_sore_throat` 2.73 · `breathlessness` 1.02 |
| K Circulatory | 6.6 | **4.45** | `known_hypertension` 3.12 · `chest_pain` 1.34 |
| S Skin | 5.7 | **3.85** | `rash` 1.54 · `itching` 1.35 · `skin_infection` 0.96 |
| W Pregnancy | 5.0 | **3.37** | `antenatal_visit` 3.37 |
| N Neurological | 3.6 | **2.43** | `headache` 1.34 · `dizziness` 0.61 · (0.49 → `other_not_in_list`) |
| T Endocrine | 1.3 | **0.88** | `known_diabetes` 0.88 |
| U Urinary | 0.4 | **0.27** | `urinary_symptoms` 0.27 |
| B Blood | 0.1 | **0.07** | `pallor_anaemia` 0.07 |
| Deferred chapters | 3.8 | **2.56** | → `other_not_in_list` |

Within-chapter percentages are taken from the category memo §7 where it declared them
(`weakness_unwell` ~10% of chapter A, `body_ache` ~7%, `weight_loss` ~2%, `oedema` ~2%, `diarrhoea`
~20% of D, `vomiting_nausea` ~12%, `back_neck_pain` ~30% of L, `breathlessness` ~15% of R, `rash`
~40% / `itching` ~35% / `skin_infection` ~25% of S, `headache` ~55% / `dizziness` ~25% of N) and are
**`ASSUMED`** exactly as it declared them. The remainders (`fever`, `abdominal_pain`,
`acidity_heartburn`, `joint_pain`, `injury`, `cough`, `cold_sore_throat`, `known_hypertension`,
`chest_pain`) are also `ASSUMED`, each with the reasoning the category memo gave.

**One corroboration worth recording.** The construction leaves `fever` at **36.03%** of PHC
encounters as chapter A's residual. POSEIDON independently measures fever at **35.5%** of all
primary-care patients. Two different studies, two different methods, 0.5 points apart. That is the
strongest single validation the target marginal gets, and it was not fitted — it fell out.

### 3.3 Three declared overrides, none of them silent

| Category | Anchor says | Problem | Recommendation |
|---|---|---|---|
| `pallor_anaemia` | 0.07% | The category memo §7 already overrules this on Anaemia Mukt Bharat programme grounds — NFHS-5 MP anaemia is 72.7% in children, 54.7% in women. A 0.07% class is 15 rows in a 22k corpus | **Declared floor, recommended 2.0%**, taken proportionally from the whole, with the override written into the manifest exactly as the category memo wrote it. Guardrail from the category memo stands: it may be *selected* by a worker, never auto-assigned from a vitals value |
| `urinary_symptoms` | 0.27% (Odisha) vs ~8.1% (AIIMS Bhopal, the only MP source) | A 30× conflict the category memo instructs must **not** be averaged | **Generate the primary corpus at the Odisha value, and generate a declared sensitivity corpus at the AIIMS value from the same manifest with one constant changed.** Two runs, one difference, the choice visible and testable rather than baked in |
| `other_not_in_list` | 3.05% by construction | The category memo's `ASSUMED` residual is **~7%**, allocating within-chapter remainder as well as unrealized chapters. The two do not agree | **Flagged, not silently reconciled.** Layer 3's abstention calibration trains on these rows, so too few is a real cost. Recommended floor 5.0%, but this needs the operator's number, not mine |

### 3.4 The rare-severe tail — it dissolves, and what is left is not a class

The audit's headline imbalance is **300:1**, with the three thinnest classes being the three most
dangerous: J22 (25 rows, F1 0.364), B50 (21, 0.400), A91 (54, 0.381).

**Under the new label space that imbalance does not exist**, because those are no longer classes.
Per the category memo §9, A91/B50/J22 are *conditions behind the gateways* of `fever`, `cough` and
`breathlessness`. The thinnest actual class in the table above is `pallor_anaemia` before its
override, and after the override the head-to-tail ratio is roughly **36 : 2 ≈ 18 : 1** — a
one-order-of-magnitude problem instead of two.

What remains rare is a **severe presentation within a category** — a latent variable, not a label.
Four decisions:

1. **Stratified severity sampling within category.** Severity is drawn from a declared distribution
   per category, with a **floor on severe-presentation rows that binds on training rows**. The audit
   §G.2 measured why this matters: `MIN_RARE_DISEASE_FLOOR = 300` acted on the *pool*, before
   `_balanced_sample` and before the `icd_candidate.notna()` filter, so it never bound on anything
   the model saw.
2. **No SMOTE, no synthetic oversampling, no interpolation.** The architecture memo §3.5 records
   that SMOTE degrades in high dimensions and can inject uninformative points. It is worse than
   unhelpful here: the new feature space is overwhelmingly *categorical* `optionId` codes, and
   interpolating half-way between "shaking chills" and "feels cold only" produces a row that
   corresponds to no possible encounter. **Rarity is fixed by generating more walks, never by
   inventing points between existing ones.**
3. **Recall over precision on severe presentations**, with the physician AGREE/MODIFY/REJECT gate
   absorbing the false positives — the explicit clinical trade the architecture memo §3.5 names.
4. **Volume held flat at ~22–25k.** Audit §H.3 is unambiguous: *"scaling a biased distribution
   scales the bias while improving every metric that is measured on the same biased distribution."*
   Any increase must be justified against a measured gain on the **perturbation set** (§8), never on
   the in-distribution split.

---

## 4. Per-defect prevention

Every audit-measured defect, named with the mechanism that prevents it.

| # | Defect (as measured) | Prevention |
|---|---|---|
| 1 | **US BMI × WHO-Asian cutoff.** BMI mean 25.01; 67.10% of BMI-bearing rows ≥ 23; `abnormal_params == "bmi"` alone is 35.44% of the file, resolving 0.65 obesity / 0.35 knee OA — the direct cause of E66 41.82% and M17 23.16% (audit §D.4) | BMI is drawn **conditional on the already-drawn category**, never drawn first and never a route to a label. E66 leaves the label space entirely (category memo §9). **GATE-BMI:** the realized BMI distribution is compared against NFHS-5 Madhya Pradesh and the run fails outside a declared tolerance — the check that "nothing in the pipeline" currently performs |
| 2 | **Uniform quota over `abnormal_params`** (`step3._balanced_sample:194-218`) — each combination contributes the same count, so the class mix is a sampling artifact | `_balanced_sample` deleted. Category drawn first from §3.2's marginal; **GATE-MARGINAL** asserts realized-vs-target within tolerance |
| 3 | **Uncited hand-set `ICD_MAPPING` weights** — "these numbers *are* the conditional prevalences of the dataset and they carry no provenance whatsoever" | Replaced by the answer model (§2.3). Every weight typed, sourced-or-`ASSUMED`, dated, physician-signed. Module-load assertion extended from "sums to 1.0" to "has provenance" |
| 4 | **Invented fever channel**, documented at 8% base / 22% monsoon, realized at **28.13% / 45.00%** — a 2× discrepancy with both documents (audit §D.3) | `assess_fever_pattern` and `fever_pattern_flag` deleted. Fever pattern becomes the tree's FV-02 node with six fixed options, **asked** rather than SHA-hashed. **GATE-DECLARED:** every declared rate in the manifest is compared to its realized rate and the run fails outside tolerance. The specific failure mode here — code correct, *row selection* moved the rate — is caught because the gate measures the output, not the function |
| 5 | **Uniform SpO2 and pulse.** `biometrics.yml` draws flat bands and `step2.impute_missing_vitals` fills `uniform(95,100)` / `uniform(60,100)`; both columns end 100% non-null. "A real SpO2 histogram is sharply peaked at 97-99, not flat across 95-100" | Flat-band imputation deleted outright. Vitals drawn from distributions conditioned on condition and severity. A missing vital is `NOT_MEASURED` with a null value (§5), never a filled box |
| 6 | **Bounded glucose** — 7,789 values, min 70.0, max 219.8, zero below 70, zero above 220; the `biometrics.yml` bin edges showing through. No hypoglycaemia and no DKA-range reading exists anywhere in training | Glucose drawn from a distribution spanning hypoglycaemia (< 54 mg/dL) through DKA range (> 300), conditioned on condition. **GATE-RANGE** asserts both tails are populated — a corpus that cannot represent hypoglycaemia cannot support the `known_diabetes` branch's `hypoglycaemia_episodes` field or §6's emergency path |
| 7 | **Encounter dates span 1954-08-07 to 2026-07-23** — Synthea lifetime-simulation encounters presented as current PHC visits, with a monsoon seasonality flag applied across seventy years | Declared encounter window, recommended a rolling **24 months** ending at the generation date, as a manifest constant. Seasonality applied only inside it. **GATE-WINDOW** fails on any row outside |
| 8 | **Physiologically implausible ambulatory rows survive**: SBP < 70 on 45 rows, DBP < 40 on 75, pulse > 180 on 61, **SpO2 < 85 on 608 rows (2.74%)**, BMI < 14 on 76. `step2.drop_sparse_rows` filters for vital *count*, never plausibility | A declared plausibility table per vital per age band, applied as **GATE-PLAUSIBLE**. Note the interaction with §6: a genuinely-emergency row *should* carry SpO2 < 90. The gate therefore rejects the *physiologically impossible*, not the *clinically severe*, and the two are separated by requiring an emergency row to have a latent condition that explains its vital — which the tree walk provides and the current pipeline cannot |
| 9 | **Not reproducible.** `step2.impute_missing_vitals` gained a `seed` on 2026-08-11; the shipped corpus is dated 2026-07-23 and was produced by the unseeded predecessor. "The document is stale against the code, and the code is newer than the artifact. Neither describes what actually shipped" | §7 in full: counter-based per-row RNG, complete manifest, per-row provenance, and a regeneration test that reproduces N rows from the manifest alone |

### 4.1 Four further findings, grounded on disk this session, not in the audit

| # | Finding | Prevention |
|---|---|---|
| 10 | **`step2` imputes before it drops sparse rows** (`step2:202-204`: `filter_age` → `impute_missing_vitals` → `drop_sparse_rows`). Imputation fills SpO2 and pulse on *every* row, so both always count toward `MIN_VITALS_PER_ROW = 3`. The floor is therefore really *"at least one real vital among BP-systolic, BP-diastolic, BMI, glucose"* — a materially weaker filter than the constant reads as | Density filters count **measured** vitals only (`<vital>_provenance == DEVICE_MEASURED` or `MANUAL_ENTERED`). Ordering made irrelevant by there being no imputation step to order |
| 11 | **`_balanced_sample`'s top-up draws from a wrongly-defined remainder** (`step3:211-217`). After `pd.concat(parts, ignore_index=True)` the sampled frame carries a fresh `RangeIndex`, so `df[~df.index.isin(used_idx)]` excludes pool rows whose *original* index happens to fall in `0..len(result)-1` — arbitrary rows, not the sampled ones. The top-up can therefore redraw rows already taken, which `step5`'s dedup on `(patient_id, encounter_date)` then silently removes, shifting the realized tier mix below target | Moot: `_balanced_sample` is deleted (defect 2). Recorded so it is not reintroduced, and as a caution that a dedup step downstream can mask a sampling bug upstream |
| 12 | **`step4` is not reproducible even with every seed fixed.** `step4:83` — `r = random.Random(hash(abnormal_params_str) + seed_offset)`. Python 3 salts string hashing per process unless `PYTHONHASHSEED` is set, so `symptom_string`, `symptom_signal_strength`, `drug_name` and `drug_dosage` differ between runs of the same command. This is a *second, independent* reproducibility break, distinct from the audit's unseeded-`step2` finding, and it affects the classifier's actual input column | **`hash()` banned in the generator.** All row-level randomness goes through the counter-based `hashlib` stream of §7.2 — which is the pattern `config.py:213-223` (`_stable_unit_interval`, SHA-256) already gets right and which `step4` simply failed to use |
| 13 | **The generative direction is inverted.** `step4.sample_symptom` resolves the condition and then samples symptoms *from that condition's pool*, so symptoms are a function of the label. The model is trained to invert a generator, which is the mechanism behind v0.1's 1.0000 accuracy and behind the audit's judgement that v0.2's 0.9212 measures "less of the same thing" | The tree walk produces answers from a *latent clinical state*, through the same fixed questions the device asks, subject to the same `UNKNOWN` and availability noise a real encounter has. The output is an **encounter record**, not a label-conditioned symptom draw. This does not make the corpus real — it is still synthetic and must still say so — but it removes the trivially-invertible mapping |

---

## 5. Vitals provenance, and the IoT ingestion contract it must match

### 5.1 The representation

Per vital, the triple of D1. The provenance enum:

| Value | Meaning | `<vital>` value |
|---|---|---|
| `DEVICE_MEASURED` | A normalized reading arrived from the IoT path | the reading |
| `MANUAL_ENTERED` | A worker typed or read it from an analogue instrument | the entered value |
| `NOT_MEASURED` | No reading was taken — no device, no consumable, no time, patient declined | **null** |
| `DEVICE_ERROR` | The device reported a failure or an out-of-range fault | **null**, with the fault distinguished from a simple absence |

> **There is no `DEFAULTED` value, and the schema cannot express one.** That is deliberate. A
> fabricated normal must be *unrepresentable* in the corpus, not merely discouraged, because the
> corpus is the thing that teaches the model what "no vitals recorded" looks like.

### 5.2 The IoT ingestion contract the dataset aligns to

Named here as the foundation the dataset must match. **The Raspberry Pi software and the app UI are
not designed in this memo** — only the data contract.

In production, vitals reach the app through an IoT ingestion path: a **Raspberry Pi** pairs with a
medical device over **Bluetooth, Wi-Fi or NFC**, decodes the device-specific wire format, and pushes
a **normalized reading** to the app, which autofills the vitals fields behind a **capture button and
loader**. The Pi absorbs per-device variation so the app receives one standard-shaped reading
regardless of which manufacturer's instrument produced it.

- **Device side:** IEEE 11073-20601 personal-health-device data (the PHD exchange protocol and
  domain information model).
- **Resource shape:** the HL7 FHIR Personal Health Device Implementation Guide.

**The normalized reading, as the dataset models it:**

```
NormalizedReading {
  vitalType      // pulse | spo2 | bp_systolic | bp_diastolic | temperature |
                 // respiratory_rate | bmi (derived) | glucose
  value          // numeric, in the canonical unit
  unit           // canonical, fixed per vitalType
  measuredAt     // timestamp of the reading, not of the sync
  deviceId       // stable identifier of the instrument
  deviceType     // pulse oximeter | BP monitor | thermometer | glucometer | scale…
  provenance     // DEVICE_MEASURED, or DEVICE_ERROR when the device faulted
}
```

**Alignment rules, so the model trains on the shape the field produces:**

1. `<vital>_provenance` mirrors the reading's provenance **exactly**. One enum, one meaning, both
   sides.
2. A measured reading contributes its value to `<vital>` and `DEVICE_MEASURED` to
   `<vital>_provenance`. An absent one contributes **null** and `NOT_MEASURED`. Never a substituted
   normal, at any layer.
3. `MANUAL_ENTERED` is a first-class value, not a degraded case — a PHC with one working oximeter
   and a manual cuff produces both provenances in the same encounter, and the corpus must contain
   that mixture.
4. The corpus contains `NOT_MEASURED` rows at **realistic rates**, drawn from the availability model
   of §2.4 — dependent on facility tier and consumable supply, **independent of the latent
   condition**. This is what makes GATE-LEAK pass for vitals rather than merely for tree fields.
5. `measuredAt`, `deviceId` and `deviceType` are carried on the encounter record for audit even
   though they are not model features — device-level bias (one miscalibrated oximeter across a
   district) is only detectable after the fact if the device identity was recorded.

### 5.3 Blocking prerequisite, stated loudly

`RetrofitEvaluateSource.kt:38-66` still substitutes `bmi = 22.0`, `systolicBp = 120.0`,
`diastolicBp = 80.0`, `heartRate = 72.0`, `spo2 = 98.0` when the real values are absent. The
audit §C.3 measured the consequence: *"A case with no vitals at all produces a complete, plausible
'normal vitals' risk-tier prediction."*

> **While those defaults exist, this corpus is invalid in the field.** The model would be trained on
> honest absence and served fabricated normals — a training/serving skew on the exact feature the
> emergency path depends on. Removing them is a **precondition** for the regenerated corpus to mean
> anything, not a follow-up task.

The same applies at the DTO: `EvaluateRequestDto` types `systolicBp`, `diastolicBp`, `bmi`,
`heartRate` and `spo2` as non-null `Double`, which is *why* the defaults exist. The wire contract has
to carry nullable values plus provenance before the app can stop fabricating. That is a contract
version bump and, under the ACP, a design change — the architecture memo §5 already says it should
be made once, together with the relabelling.

---

## 6. Two-path emergency vitals — track and trigger

The behaviour resolved this session: a genuine single-vital danger-band reading forces a
`REFER_EMERGENCY` floor and abstention; an aggregate of several mildly-abnormal vitals also raises
disposition. Both paths must be exercised by the corpus.

### 6.1 Path 1 — single-vital danger band (any one suffices)

**Adult (provisional, early-warning-score-derived):**

| Vital | Danger band | Critical band |
|---|---|---|
| Systolic BP | ≤ 90 mmHg | ≤ 70 mmHg |
| SpO2 | < 92% on air | — |
| Respiratory rate | ≥ 30 or < 9 /min | — |
| Heart rate | ≥ 130 or ≤ 40 /min | — |
| Temperature | < 35 °C | — |

**Child:** IMCI general danger signs, plus **SpO2 < 90%**.

Effect: `disposition_floor := REFER_EMERGENCY` **and abstention** — no differential is produced.
This is the vitals-side counterpart of the tree's Tier-0 `routeTo`, and it resolves the open question
the tree memo §3.5 left standing for the vitals channel specifically: on Path 1 the device abstains.

### 6.2 Path 2 — aggregate track-and-trigger

Several mildly-abnormal vitals, none of them individually breaching, raise disposition together
(NEWS2-shaped: a per-vital ordinal score summed to an aggregate, with declared aggregate thresholds).
This is the path that catches early sepsis and compensated shock, where every single vital is
"nearly normal" and the combination is not.

Path 2 raises `disposition_floor` (typically to `REFER_URGENT`, and to `REFER_EMERGENCY` above the
upper aggregate threshold). Whether Path 2 also forces abstention is **the same open operator
question** the tree memo §3.5 flagged, and it is not answered here.

### 6.3 Provisional, and the reconciliation is a build-time requirement

> **These thresholds are international early-warning-score-derived and are PROVISIONAL.** They are
> not an Indian-context clinical standard and must not be shipped as one.

**Build-time requirement:** reconcile against **CBAC** (the Indian NCD screening instrument),
**IMCI** (the paediatric protocol Indian PHC staff are trained on) and **IHCI** (the hypertension
initiative whose 140/90 thresholds `config.py:109-120` already correctly uses instead of AHA 130).
The reconciled table is a **versioned, cited artifact** reviewed by a physician before the corpus
backs any regulatory claim, and **every row records `threshold_table_version`** so a corpus generated
under provisional thresholds is distinguishable from one generated under reconciled thresholds
without re-deriving anything.

Note the precedent on disk: `config.py` already refuses AHA 130 in favour of IHCI 140 with the
reasoning in a comment. That is the right posture, applied to two of the six vitals. §6 extends it to
all of them, and to the two vitals that do not exist yet (D2).

### 6.4 How the corpus exercises both paths

- **Declared minimum row counts per trigger path per age band**, in the manifest, asserted as
  **GATE-EMERGENCY**. A corpus that contains no paediatric Path-1 row cannot support the paediatric
  emergency claim, and the run should say so rather than the evaluation discovering it later.
- **Generated by seeding the latent state into the danger band** — a condition and severity that
  *produce* SpO2 < 92, drawn at stage 2 — **never by post-hoc editing of vital values.** Editing a
  vital after the fact decouples it from the condition that produced it, which is precisely the
  incoherence that lets defect 8 (implausible rows) and defect 1 (BMI artifact) exist: a number with
  no clinical story behind it.
- Per rule 3, `disposition_floor`, `fired_gateways` and `routed_to` are present as **audit columns
  and excluded from the feature set.** The model must learn to be uncertain from the *vitals and
  answers*, not to read the answer off the rule's output.

---

## 7. Reproducibility

### 7.1 `generation_manifest.json` — one artifact, everything needed to rebuild

Master seed · per-stream derived seeds · the full target marginal (§3.2) with the 0.6749 rescale
factor and every within-chapter `ASSUMED` split · every answer-model entry with `sourceType`,
`source`, `declaredOn`, `reviewedBy` · the availability and `UNKNOWN` models · the plausibility table
· the threshold table with `threshold_table_version` · `branch_version` per category and the SHA of
each branch file · `category_registry_version` · `answer_model_version` · the generator commit SHA ·
library versions (pandas, numpy, Python) · the encounter window · every gate's declared tolerance
including `MI_MAX` · and the **realized** marginal alongside the target.

Recording target and realized side by side in one file is what would have caught defect 4 at
generation time: the 8%-documented / 28.13%-realized gap existed for weeks in two documents that
each described a different thing, and neither described the artifact.

### 7.2 Counter-based per-row RNG

```
u(row_id, stream_name) = int(sha256(f"{master_seed}|{row_id}|{stream_name}").hexdigest()[:12], 16) / 16^12
```

Properties that matter: **order-independent** (row 7,412 is the same whether generated first or
last), **parallel-safe** (no shared RNG state, so the run can be sharded), and **individually
reproducible** (one row can be regenerated in isolation for debugging, without replaying the corpus).

This is not new invention — it is `config.py:213-223`'s `_stable_unit_interval` promoted from a
special case to the only source of randomness in the generator. One named stream per decision
(`category`, `condition`, `severity`, `vitals`, `availability`, `answer::<nodeId>`, `unknown`,
`noise`), so adding a stream never perturbs existing ones.

**`hash()` is banned in the generator** (defect 12), as is any sequential global RNG, `np.random`
module-level state, and `random` module-level state.

### 7.3 Per-row provenance

Every row carries `row_id`, `master_seed`, `stream_id`, `generator_commit`, `branch_version`,
`category_registry_version`, `answer_model_version`, `threshold_table_version`, `synthetic`,
`source`. Per-row rather than manifest-only because corpora get concatenated, subsetted and
re-shuffled, and a row that has been separated from its manifest must still be able to say what
produced it.

### 7.4 The regeneration test

Regenerate N rows from the manifest alone, on a different machine, and assert **byte-identical**
output. This is the test that would have failed on the shipped corpus for two independent reasons
(unseeded `step2`, salted `hash()` in `step4`) and it is the only claim of reproducibility worth
making.

### 7.5 The build gates, collected

`GATE-LEAK` (§2.5) · `GATE-MARGINAL` · `GATE-DECLARED` · `GATE-BMI` · `GATE-RANGE` · `GATE-WINDOW` ·
`GATE-PLAUSIBLE` · `GATE-EMERGENCY`. **All of them fail the run and write no corpus.** A warning that
scrolls past in a build log is how a 28.13% realized rate lived alongside an 8% documented one.

---

## 8. Eval-set design (architecture memo §6.3 — neither set exists today)

Today every reported figure is measured on the generator's own separability. Nothing measures
generalization at all. Two sets, with different purposes and very different feasibility.

### 8.1 The perturbation set — robustness

Built by applying **declared perturbation operators** to held-out in-distribution rows. Because the
operators do not change the clinical content, **the label is unchanged by construction, so any
prediction change is a measured robustness failure** — no new labelling effort is required.

| Channel | Operators |
|---|---|
| Narrative | typos · realistic ASR substitutions · synonyms · Hindi/Malwi code-mixing · partial phrasing · term reordering |
| Tree | option-order permutation within a node · `ANSWERED` → `UNKNOWN` substitution on one field · single-field drop · `DEVICE_MEASURED` → `MANUAL_ENTERED` provenance flip · measurement-error resampling within the declared noise band |

The tree-channel operators are the new and more valuable half: they measure whether the model's
answer depends on *what the patient has* or on *how completely the worker filled the form*. The
audit's 12-of-13 qualifier flips predict that the current model would fail this badly; establishing
the new model's number is the point.

### 8.2 The out-of-distribution set — abstention

Presentations the label space **cannot** express: the category memo §8 Tier-2 deferred set —
toothache, ear pain, eye complaints, menstrual problems, mental health, jaundice, malnutrition. The
correct behaviour is **abstention, not accuracy**, and this is the set Layer 3's conformal gate is
calibrated against.

> **It cannot be synthetic.** The architecture memo §6.3 established that no Indic clinical text
> corpus with usable provenance exists, and that the OOD eval set therefore has no external source
> and must come from **real recorded encounters under consent**. A synthetic OOD set generated by the
> same generator would measure only whether the model recognises its own generator's absence — which
> is not the question.

Therefore: the real OOD set is a **collection task**, gated on consent and ethics approval, and it is
plausibly publishable in its own right (architecture memo §6.3 says so explicitly). A synthetic
Tier-2 proxy is **permitted for development only**, must be labelled a proxy in the manifest, and
**must never be quoted as an abstention performance claim.**

### 8.3 Isolation

Both eval sets are drawn on **separate RNG streams with disjoint `patient_id` space**, never seeded
from training rows, and never touched by the class-balance gates (a perturbation set is deliberately
not distributed like the training set).

---

## 9. A recommendation on Synthea, stated rather than buried

**Drop Synthea from the label path.**

Its three contributions to the current corpus are all things this regeneration is removing:

1. **US disease-module prevalence** decides which patients are internally tagged hypertensive or
   diabetic (audit §D.2) — and that is precisely the distribution being replaced by §3.2's anchors.
   Under stage-1 category drawing it is not needed.
2. **The lifetime simulation** is what produced encounters back to 1954 (defect 7).
3. **US-shaped biometrics** are one half of the largest single defect in the corpus (defect 1). The
   audit is careful here and so is this memo: *each half is defensible alone* — the WHO-Asian cutoff
   is correct for an Indian population and Synthea's biometrics are what they are. **Their
   composition is not defensible**, and dropping the composition is easier than repairing either
   half.

`synthea-international/in/` contains exactly one file (`biometrics.yml`), which overrides value
ranges and **does not touch prevalence** — there is no India demographics, geography, names or
disease module, unlike the `de`, `fr` and `gb` packs. And `step2b_demographic_reweight` already
resamples toward Census age/sex bands post hoc, which is a correction that would be unnecessary if
the age/sex draw came from Census/NFHS-5 directly in the first place.

Retaining Synthea only for within-condition vital realism is defensible, but it would have to be
conditioned on the drawn condition rather than sampled first — and at that point declaring the
conditional distributions outright is simpler, inspectable, and carries provenance the Synthea draw
never can.

**This is a real scope call and it is the operator's, not mine.** It is stated here because a
regeneration memo that quietly assumed the existing pipeline would be reused would be designing
around a decision it had not surfaced.

---

## 10. Dependencies — what is NOT in this memo

| Item | Status |
|---|---|
| **Building the generator** | Not started. This memo is the specification it would be built against |
| **Running generation** | Not started. No data produced |
| **Training / retraining** | Not started. Every published figure for the current model (0.9212 accuracy, 0.7983 macro-F1) is void on the new label space — the correction, not a regression |
| **Removing `RetrofitEvaluateSource`'s vitals defaults, and widening the DTO to nullable-plus-provenance** | **Precondition** (§5.3), not a follow-up. Separate change, separate review |
| **Adding `temperature` and `respiratory_rate` to the corpus; opening the age window below 5** | **Preconditions** (D2, D3). Without them the emergency paths are unrepresentable |
| **CBAC / IMCI / IHCI threshold reconciliation** | Build-time requirement (§6.3). The provisional table may be used for development, never for a claim |
| **Authoring the remaining 24 tree branches** | Deferred per tree memo §6. The generator can be built and tested against the three specified branches (`fever`, `known_hypertension`, `rash`) first |
| **Layer 1 normalization lexicon** | Separate artifact. It produces `narrative_terms`; this memo only reserves the column |
| **Layer 3 abstention calibration** | Separate. §8.2's OOD set is its input, and that set is a collection task |
| **Real OOD corpus collection under consent** | Not started. Gated on ethics approval; plausibly publishable |
| **Risk-file registration** | H-20 is the highest id on disk. The red-flag gateway (tree memo) and the fabricated-vitals training/serving skew (§5.3) each warrant a row from **H-21** onward. Operator-signed docs change, separate commit, **not done here** |

---

## 11. Boundaries observed

No source, dataset, model, config, generator, questionnaire or tree file was written, edited or
deleted in any of the three repos. No generation, training or side-effecting command was run. The
SaMDClassifier repo was read only (`models/model.json` feature names). The dataset-make repo was read
only, and its pre-existing dirty working tree — `drishti_pipeline/scratch/*.csv` and
`output_india/csv/*.csv`, from a prior run at HEAD `d32a197` — was recorded as a baseline **before**
any read and is unchanged by this session. No `.env`, `local.properties` or credential file was
opened at any point. No commit, no staging.
