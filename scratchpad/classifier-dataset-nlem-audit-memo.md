# Classifier / dataset / NLEM / doctor-loop audit (STEP 1, read-only)

Run date: 2026-09-05. SaMD-App branch `master`, HEAD `cab78ae`.

**Provenance of this document, stated first because it matters for the record.** The audit was
run and gate-approved on 2026-09-05. The approved write was interrupted before it landed, so this
memo was written later the same day by transcribing the approved plan
(`~/.claude/plans/model-claude-code-on-iridescent-lightning.md`) at full evidence density. Every
figure below was derived from disk during the audit session itself and is transcribed verbatim —
nothing here was re-estimated, re-derived from memory, or gap-filled. A drift check before
transcription confirmed all three repositories and all three key artifact mtimes unchanged since
the audit ran, so the plan is a faithful description of current disk.

---

## 0. Terrain, scope and the read-only guarantee

| Thing | Absolute path |
|---|---|
| SaMD-App (this repo) | `/media/sandesh/extra-ssd/AndroidWork/SaMDApp` |
| SaMDClassifier | `/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier` |
| Dataset pipeline | `/media/sandesh/extra-ssd/dataset/dataset-make/drishti_pipeline` |
| Canonical training dataset | `SaMDClassifier/dataset/canonical_dataset.csv` |
| Trained artifacts | `SaMDClassifier/models/` |

Repository state at audit time, and re-verified at transcription time with zero drift:

| Repo | Branch | HEAD | Dirty |
|---|---|---|---|
| SaMDApp | `master` | `cab78ae` | pre-existing (scratchpad memos, `dashboard.html`, `.idea/`) |
| SaMDClassifier | `main` | `63e85af` | **clean, 0 files** |
| dataset-make | `main` | `d32a197` | 66 pre-existing (`scratch/`, `output_india/`, `longitudinal_data/`) — not mine, not touched |

Artifact mtimes, unchanged across both sessions:

    models/symptom_model.json         2026-07-23 15:41:10
    models/symptom_model_meta.json    2026-07-23 15:50:34
    dataset/canonical_dataset.csv     2026-07-23 14:59:20

No source, dataset, model or config file was written, edited or deleted. No training, generation
or side-effecting command was run. The only code executed was in-memory through the classifier's
own `.venv`: `joblib.load` of the saved vectorizers and label encoder, `TfidfVectorizer.fit` on a
dataframe, and pandas reads. Nothing persisted. No `.env`, `local.properties`, `BuildConfig` or
any other credential file was opened at any point.

---

## A. The shipped classifier

Two model lineages, both XGBoost, both loaded by `src/refine_diagnosis.py`.

### A.1 Classifier A — vitals to risk tier

`models/model_meta.json` → `toy-v0.6-observed-glucose-4tier`.

- 8 features: `age, sex_encoded, systolic_bp, diastolic_bp, bmi, heart_rate, spo2, glucose`
- 3 labels: `low_risk / moderate_risk / high_risk`, collapsed from dataset `tier` via
  `{"1": low, "2": moderate, "3": high, "4": high}`
- 22,212 rows / 17,769 train / 4,443 test
- accuracy **0.9271**, f1_macro **0.8871**
- per-class F1: low 0.9402, moderate 0.9368, high 0.7843
- 5-fold f1_macro 0.8875 ± 0.0059
- `glucose_missing_strategy: native_xgboost_nan_routing_no_imputation`
- `calibration_used_for_evaluation: false`

### A.2 Classifier B — symptom text to ICD candidate

`models/symptom_model_meta.json` → `symptom-clf-v0.2-enriched-symptom-pool`.

- Word TF-IDF `ngram_range=(1,2)`, vocab **2374**
- Char TF-IDF `analyzer="char_wb"`, `ngram_range=(3,5)`, vocab **2166**
- Combined feature dim **4540**
- 18 ICD classes, 15,105 rows / 12,084 train / 3,021 test
- accuracy **0.9212**, f1_macro **0.7983**
- 5-fold f1_macro **0.8240 ± 0.0067**, folds
  `[0.8189, 0.8232, 0.8349, 0.8272, 0.8157]`
- `calibration_used_for_evaluation: false`

Per-class F1, full table:

| Class | F1 | Class | F1 | Class | F1 |
|---|---|---|---|---|---|
| A01.0 | 0.8804 | A09 | 0.9073 | A15 | 0.9318 |
| A90 | 0.8743 | **A91** | **0.3810** | A92.0 | 0.9167 |
| **B50** | **0.4000** | B54 | 0.8989 | D50 | 0.8406 |
| E05.9 | 0.7576 | E11 | 0.7671 | E66 | 0.9448 |
| F41.0 | 0.8406 | G43.9 | 0.8966 | I10 | 0.9282 |
| **J22** | **0.3636** | M17 | 0.9315 | N39.0 | 0.9091 |

The three worst classes are `J22` (acute lower respiratory infection), `A91` (dengue
haemorrhagic fever) and `B50` (*P. falciparum* malaria). **These are the severe presentations.**
The model is weakest exactly where being wrong costs most.

### A.3 Does the checked-in script reproduce the shipped model?

**Materially yes. Identifiably no.**

Refitting both vectorizers on today's `canonical_dataset.csv` with the script's exact parameters
reproduces the shipped artifact's feature pipeline **exactly**:

    SHIPPED word vocab 2374   REFIT word vocab 2374
    SHIPPED char vocab 2166   REFIT char vocab 2166
    combined dim 4540         combined dim 4540
    rows after icd_candidate.notna() filter: 15105  (meta records 15105)
    shipped label encoder classes: the same 18, in the same order

But `scripts/train_symptom_classifier.py:17` still declares:

    MODEL_VERSION = "symptom-clf-v0.1-tfidf-xgboost"

while the shipped `models/symptom_model_meta.json` records
`"symptom-clf-v0.2-enriched-symptom-pool"`. Running the checked-in script today would reproduce
the v0.2 numbers and then **silently stamp the artifact as v0.1**, overwriting the meta file and
appending a mislabelled row to the append-only training log.

This is not cosmetic. The version identifier is not under configuration control together with the
artifact it names — an **IEC 62304 §8 (configuration management) defect**. The repo's own
`readme.md:68-78` states the rule this violates: *"Bump `model_version` … on any retrain that
changes features, thresholds, or training data — never silently overwrite a version in place."*

`logs/symptom_training_log.jsonl` holds both runs and settles the history:

    2026-07-23T09:18:30Z  symptom-clf-v0.1-tfidf-xgboost      rows 15067  dim 1730  acc 1.0000  f1m 1.0000
    2026-07-23T10:11:10Z  symptom-clf-v0.2-enriched-symptom-pool  rows 15105  dim 4540  acc 0.9212  f1m 0.7983

`logs/training_log.jsonl` holds exactly one Classifier A run:

    2026-07-23T09:01:40Z  toy-v0.6-observed-glucose-4tier  rows 22212  f1m 0.8871

### A.4 Documentation stale against the shipped artifact

- `readme.md:114-126` still asserts the v0.1 property: *"each condition's 3-phrase combination
  pool is disjoint from every other condition's, so the training/test data is perfectly linearly
  separable by TF-IDF (test-set accuracy and per-class F1 are 1.0000 across all 18 classes)"*.
  Measured on the current dataset, **183 of 7,850 distinct `symptom_string` values map to more
  than one `icd_candidate`, with a maximum of 9 distinct labels for a single string.** v0.2 is not
  separable, and its own meta file (f1_macro 0.7983) says so. The README describes a model that is
  no longer the shipped one.
- `readme.md:141` cites `B50`=21, `J22`=30, `A91`=50 rows. Actual counts are **21, 25 and 54**.
  Only `B50` is right.
- Classifier A's meta records 22,212 rows against a dataset of 22,215. A 3-row discrepancy,
  recorded as an observation, not resolved here.

---

## B. Canonical dataset shape and label distribution

22,215 rows × 30 columns. 9,040 distinct `patient_id`, 2.46 encounters per patient. **No duplicate
`(patient_id, encounter_date)` pairs** — every row is a distinct encounter; the reweighting step's
with-replacement resampling did not survive into the shipped file.

### B.1 Tier

| Tier | Rows | Share | Target ratio (`config.GLOBAL_TIER_TARGET_RATIOS`) |
|---|---|---|---|
| 1 (0 abnormal params) | 7,762 | 34.94% | 0.35 |
| 2 (1 abnormal param) | 12,670 | 57.03% | 0.57 |
| 3 (2 abnormal params) | 1,450 | 6.53% | 0.065 |
| 4 (3+ abnormal params) | 333 | 1.50% | 0.015 |

The realized tier mix matches the configured target almost exactly. This is the first sign that
the distribution is *imposed*, not observed — see §D.

### B.2 `icd_candidate` — the Classifier B label

Null on 7,110 rows (**32.01%**), by design. Within the 15,105 labelled rows the classifier
actually trains on:

| Code | Condition | Rows | Share of labelled |
|---|---|---|---|
| E66 | Obesity | 6,317 | **41.82%** |
| M17 | Osteoarthritis of the knee | 3,499 | **23.16%** |
| I10 | Essential hypertension | 1,283 | 8.49% |
| N39.0 | Urinary tract infection | 556 | 3.68% |
| A09 | Infectious gastroenteritis | 538 | 3.56% |
| A01.0 | Typhoid fever | 446 | 2.95% |
| B54 | Unspecified malaria | 445 | 2.95% |
| A90 | Dengue fever | 429 | 2.84% |
| A92.0 | Chikungunya | 315 | 2.09% |
| G43.9 | Migraine | 227 | 1.50% |
| E11 | Type 2 diabetes mellitus | 212 | 1.40% |
| A15 | Respiratory tuberculosis | 208 | 1.38% |
| F41.0 | Panic disorder | 182 | 1.20% |
| D50 | Iron deficiency anaemia | 178 | 1.18% |
| E05.9 | Thyrotoxicosis | 170 | 1.13% |
| A91 | Dengue haemorrhagic fever | 54 | 0.36% |
| J22 | Acute lower respiratory infection | 25 | 0.17% |
| B50 | *P. falciparum* malaria | 21 | 0.14% |

**Head-to-tail imbalance is 6,317 : 21 ≈ 300 : 1.** Obesity and knee osteoarthritis together are
**64.98%** of every labelled row.

### B.3 Other columns

`symptom_signal_strength`: supportive 47.98% · nonspecific 26.82% · strong 25.19%.
`sex`: M 51.79% / F 48.21%. `fever_pattern_flag`: True on 28.13% — see §D.3.
`icd_chapter` spans 11 values, led by Nutritional/metabolic 28.44%, General/nonspecific 16.72%,
Musculoskeletal 15.75%, Circulatory 14.05%, Infectious 13.72%.
`abnormal_params` has 49 distinct values, led by `bmi` alone at 35.44% and `normal` at 20.00%.
`drug_name` has 42 values; 34.81% are empty (the under-18 pediatric override plus tier-1 rows).

`symptom_string` is drawn from a fixed 118-term pool, mean **3.10** terms per row (min 2, max 5),
pipe-space joined, with zero spelling or phrasing variance. 10,353 distinct strings across all
rows, 7,850 within the labelled subset.

---

## C. Hazards

### C.1 Already recorded, and load-bearing

`scratchpad/classifier-wire-format-investigation.md` (2026-08-31, finding X-3) measured the
**empty-`symptom_string` prior**:

    E66 0.7495 · I10 0.0709 · M17 0.0397 · A90 0.0337 · B54 0.0308

An empty input does not produce an empty or low-confidence result. It produces a **confident-
looking Obesity differential at 74.95%**. Any code path that can yield an empty resolved term list
therefore yields a confident wrong differential, stamped `REAL_INFERENCE`.

The same memo measured that out-of-vocabulary terms are not merely inert — several are
confidently wrong:

    'Diarrhoea / loose motions'   -> M17 (knee osteoarthritis)  0.644
    'Swelling / oedema'           -> M17                        0.712
    'Skin complaint / itching'    -> M17                        0.598
    'Nasal congestion / sneezing' -> M17                        0.461
    'Toothache'                   -> B54 (malaria)              0.437
    'Child fever'                 -> N39.0 (UTI)                0.416

And the granularity result: **12 of 13 generic-versus-qualified term pairs flip the top-1
diagnosis** (`body ache`→B54 94% vs `severe body ache`→A90 87%; `exertional dyspnoea`→E66 100%
vs bare `breathlessness`→A91 40%). Measured over 1,500 labelled rows, a 41-item collapsed
vocabulary scores **0.580 top-1** against free text's 0.886 and the 118-term pool's 0.941 — a
**-30.6 point** regression.

**Verified during this audit: X-3 is still not registered.** `docs/quality/risk-management-file.md`
runs H-01 through H-19 and none of them covers the empty-or-OOV-input confident-wrong-answer
shape. It exists only in a scratchpad memo.

### C.2 New in this audit — missingness leaks the label

`glucose` present-rate, by `icd_candidate`:

| Class | glucose notna | Class | glucose notna |
|---|---|---|---|
| **E11** | **1.000** | N39.0 | 0.266 |
| E66 | 0.479 | (null) | 0.265 |
| M17 | 0.469 | A15 | 0.231 |
| A01.0 | 0.343 | E05.9 | 0.218 |
| B54 | 0.337 | F41.0 | 0.203 |
| A92.0 | 0.330 | **G43.9** | **0.009** |
| A09 | 0.312 | **I10** | **0.007** |
| A90 | 0.277 | **A91 / B50 / J22** | **0.000** |
| D50 | 0.270 | | |

`glucose_high` cannot enter `abnormal_params` unless a glucose value exists, so glucose
*presence* is a near-deterministic shortcut for E11 and a near-deterministic veto against I10,
G43.9, A91, B50 and J22. In the field, whether a glucometer strip was available is a supply-chain
fact, not a clinical one.

`readme.md:88-92` argues the NaN is deliberately preserved *precisely* to avoid biasing training
toward whichever subpopulation got tested. The argument is right; the label construction
reintroduces the same bias one level up, where the NaN-routing defence does not reach.

### C.3 New in this audit — the app fabricates the vitals it cannot measure

`app/src/main/java/com/example/samdapp/data/remote/RetrofitEvaluateSource.kt:38-66`:

    val bmi = if (vitals.weightKg != null && vitals.heightCm != null && vitals.heightCm > 0) {
        ...
    } else {
        22.0
    }
    ...
    systolicBp  = vitals.bpSystolic?.toDouble()  ?: 120.0,
    diastolicBp = vitals.bpDiastolic?.toDouble() ?: 80.0,
    heartRate   = vitals.pulseBpm?.toDouble()    ?: 72.0,
    spo2        = vitals.spo2Percent?.toDouble() ?: 98.0,

A case with no vitals at all produces a complete, plausible "normal vitals" risk-tier prediction.
No persisted field anywhere distinguishes a measured 120/80 from a defaulted one. The code's own
comment marks the asymmetry — glucose, respiratory rate and temperature are honestly passed as
null *"instead of fabricating vitals data, unlike the required fields defaulted above"* — so this
is known at the call site and unmitigated downstream. It is adjacent to H-13 (fabricated vitals
reachable in a non-dev build) but is not the same hazard and is not covered by it.

---

## D. Dataset generation pipeline — every place bias enters

The classifier's corpus is produced by `drishti_pipeline/` (`step1` → `step6`), output to
`drishti_dataset/canonical_dataset.csv`, mtime 2026-07-23 14:59:10, matching the classifier's copy.

A second pipeline, `longitudinal_pipeline/` (v0.1.3, LLM/instruction dataset track), also exists on
disk. **It does not feed this classifier.** Only its `config/india_geography.yaml` mentions Madhya
Pradesh, and it lists MP alongside Uttar Pradesh, Rajasthan, Bihar and Maharashtra with no
weighting between them. It is not a region-calibration source for this model.

### D.1 The headline answer on region

**The produced prevalence reflects neither Madhya Pradesh, nor national India, nor America. It is
authored.** Three mechanisms, in descending order of magnitude.

**(1) `step3._balanced_sample()` imposes a uniform quota over vital-sign combinations.**

    quota_per_group = max(1, target_count // n_groups)
    for each group in df.groupby("abnormal_params"):
        take = min(len(subset), quota_per_group)

Each distinct `abnormal_params` combination contributes the same number of rows, capped by what
the pool actually contains. The final class mix is therefore *uniform over combinations*, not any
epidemiological distribution. Naturally rare combinations are lifted to the quota; abundant ones
are cut down to it.

**(2) `config.ICD_MAPPING` weights are hand-set with no cited source.** Within a combination, the
condition is selected by weight: essential hypertension 0.70 / white-coat 0.15 / migraine 0.15;
obesity 0.65 / osteoarthritis 0.35; sinus tachycardia 0.95 / hyperthyroidism 0.05; the fever block
0.20 / 0.20 / 0.20 / 0.10 / 0.10 / 0.10 with `monsoon_weight` overrides. These numbers *are* the
conditional prevalences of the dataset and they carry no provenance whatsoever — no citation, no
comment naming a source, nothing in `metadata.json` beyond a note that they exist.

**(3) `config.assess_fever_pattern` invents an epidemiological channel.** Six vitals carry no
infectious-disease signal, so a deterministic SHA-256 flag stands in for "presents with a
fever/infectious pattern" at 8% base rate and 22% in monsoon months. `metadata.json` is candid
that this is *"a synthetic epidemiological routing flag … NOT a measured vital"*. Without it,
malaria, dengue, typhoid, chikungunya, UTI, gastroenteritis, TB and LRTI could never realize as a
primary `icd_candidate` at all.

### D.2 Where the American data actually gets tweaked — exhaustively

`synthea-international/in/` contains **exactly one file**:
`src/main/resources/biometrics.yml`. It overrides *value ranges* conditioned on age and sex — BP
bands, an SpO2 band tightened to `[95,99]`, heart rate `[60,100]`, a South-Asian lipid profile,
and glucose bins widened to `[70, 100, 145, 220]`. **It does not touch prevalence.** There is no
India demographics module, no geography module, no names module, no disease module — unlike `de`,
`fr`, `gb` and the other country packs.

Everything else remains US/Massachusetts. `DATASET_REPOSITORY_AUDIT.md` §13 documents this
honestly and lists it: state Massachusetts, `generate.geography.country_code = US`, US
demographics driving the age/sex draw, US zip codes, US addresses, US providers, US payers, US
names, US medication costs, FHIR US Core IG — and, the one that matters most, **Synthea's own US
disease-module prevalence, which decides which patients get internally tagged hypertensive or
diabetic in the first place**.

Post-hoc correction is limited to three things:
1. `step2b_demographic_reweight.py` resamples per age-decade × sex cell to
   `TARGET_AGE_SEX_DISTRIBUTION` — **approximate Census 2011 broad age bands, national**, not
   Madhya Pradesh, not rural, not PHC-attending. `metadata.json` says so directly: *"Precision not
   required for a synthetic bootstrap dataset."*
2. `config.py` thresholds: IHCI 140/90 (explicitly not AHA 130), WHO-Asian BMI 23/25,
   RSSDI/ICMR-INDIAB random glucose 140/200, Narang *et al.* pediatric BP formulae.
3. The `ICD_MAPPING` label table above.

### D.3 Documented rate versus realized rate — a real conflict

`metadata.json` and `config.py` both state the fever pattern is *"~8% base rate, ~22% during
monsoon (Jun-Sep)"*. The shipped dataset says otherwise:

    realized fever_pattern_flag rate, overall:        28.13%
    realized rate, monsoon months (Jun-Sep):          45.00%
    realized rate, other months:                      18.52%

Recomputing `assess_fever_pattern(patient_id, encounter_date)` row-by-row against the shipped file
**matches the stored flag on 22,215 of 22,215 rows**. So the flag is computed correctly and the
code is not at fault: **row selection moved the rate**, roughly 2× above what both documents claim.
The mechanism is §D.1(1)'s uniform quota combined with the accumulation floors in
`run_pipeline.py` — `MIN_RARE_DISEASE_FLOOR = 300` across
`RARE_DISEASE_CODES = {B54, D50, E05.9, F41.0, A92.0, A15}` — which keep drawing until rare,
fever-routed conditions are represented.

Encounters in monsoon months are also 36.28% of the file against a natural 4/12 = 33.3%.

### D.4 The compounding failure — US BMI distribution × Asian cutoff

    BMI: mean 25.01, sd 5.17, non-null on 18,028 of 22,215 rows (81.2%)
    BMI >= 23 (WHO-Asian overweight): 67.10% of rows that have a BMI
    BMI >= 25 (WHO-Asian obese):      57.54%
    BMI >= 30 (WHO international):    15.14%

A US-shaped BMI distribution passed through an Asian cutoff marks two-thirds of the population as
abnormal. `abnormal_params == "bmi"` alone is 35.44% of the entire file, and that single bucket
resolves to obesity at weight 0.65 and osteoarthritis at 0.35 — which is precisely why E66 is
41.82% and M17 is 23.16% of the labelled set.

**This is the largest single distribution defect in the corpus, and it is an artifact of the
calibration method rather than of any epidemiology.** Each half is defensible alone: the WHO-Asian
cutoff is correct for an Indian population, and Synthea's biometrics are what they are. Their
composition is not defensible, and nothing in the pipeline checks the composed result against any
external prevalence.

### D.5 Further bias and validity entry points

**Encounter dates span 1954-08-07 to 2026-07-23.**

    1950s:    12      1960s:    47      1970s:    63      1980s:   213
    1990s:   473      2000s:   850      2010s: 6,837      2020s: 13,720

Synthea's lifetime simulation exports historical encounters, and the pipeline presents them as
current PHC visits. Beyond plausibility, the monsoon seasonality flag is being applied across
seventy years of dates.

**SpO2 and pulse are uniform by construction, not physiological.** `biometrics.yml` draws them as
flat bands (`oxygen_saturation.normal: [95, 99]`, `heart_rate.normal: [60, 100]`), and
`step2.impute_missing_vitals` fills every missing value with `rng.uniform(95.0, 100.0)` and
`rng.uniform(60.0, 100.0)`. In `scratch/vitals_raw_42.csv` both columns are **100% non-null** after
imputation, against BP at 84.9%, BMI 77.4% and glucose 53.8%. Two of Classifier A's six vital
features therefore carry a box, not a distribution — a real SpO2 histogram is sharply peaked at
97-99, not flat across 95-100. Note this is a joint property of the generator and the imputer: the
histogram alone cannot separate them, and this memo does not claim it can.

**Glucose is bounded.**

    count 7,789 (35.1% of rows)   min 70.0   median 88.9   p90 130.8   p95 142.1   p99 186.8   max 219.8
    values < 70: 0        values > 220: 0

No hypoglycaemia and no DKA-range reading exists anywhere in training. The bounds are the
`biometrics.yml` bin edges showing through.

**Physiologically implausible ambulatory rows survive into the corpus.**

    bp_systolic  < 70 :  45 rows
    bp_diastolic < 40 :  75 rows
    pulse       > 180 :  61 rows
    spo2         < 85 : 608 rows   (2.74% of the file)
    bmi          < 14 :  76 rows

These are Synthea death-trajectory and critical-care encounters leaking into what is documented as
an ambulatory PHC dataset. `step2.drop_sparse_rows` filters for vital *count*, never for vital
*plausibility*.

**The shipped dataset is not reproducible.** `step2.impute_missing_vitals` now takes a `seed` and
uses `np.random.RandomState(seed)` — but that file's mtime is **2026-08-11**, while the shipped
dataset's is **2026-07-23**. The corpus in use was generated by the unseeded predecessor. Compounding
it, `DATASET_REPOSITORY_AUDIT.md` §15 item 3 still lists *"Non-deterministic imputation.
`np.random.uniform()` in step2 for SpO2/pulse is unseeded"* as an open weakness: **the document is
stale against the code, and the code is newer than the artifact.** Neither describes what actually
shipped.

**Input-distribution mismatch at inference.** `symptom_string` in training is pipe-space joined pool
terms. The app sends prose joined with `". "` (`RetrofitEvaluateSource.kt:45-48`), concatenating
`chiefComplaint` with the ASR `transcription`. Measured cost: 0.886 top-1 against the 0.941 ceiling.

---

## E. NLEM prescription path against the locked architecture

### E.1 The actual call chain

    app.py  POST /api/v1/evaluate
      -> refine_diagnosis.refine(symptom_string, vitals)            # Stage 2 re-ranking
      -> pipeline_glue.execute_full_clinical_pipeline(...)
           -> rag_pipeline.get_treatment_recommendation(ranking, age)
                -> ICD_TO_DISEASE_NAME[icd]                          # static 18-entry table
                -> retriever.retrieve(disease_name, top_k=5)         # SentenceTransformer + ChromaDB
                -> extractor.extract_recommendation(...)             # regex over chunk metadata
                -> validator.validate_output(...)                    # deterministic checks
           -> indian_brands.enrich_with_indian_brands(drug)          # static exact-match map
           -> thresholds.combine_vitals(...)                         # deterministic triage

### E.2 What matches the lock — confirmed

**No generative LLM anywhere in the diagnose or prescribe path.** Every returned field is
regex-parsed chunk metadata produced at ingestion time; nothing is authored or inferred.
`extractor.py` returns `recommendedDrug`, `levelOfHealthcare`, `dosageForms` and `citation`
straight from `best.get(...)` on the retrieved chunk.

**The physician gate is structurally in the loop.** `validator.validate_output` forces
`requiresHumanReview = True` on: a null drug; a drug that cannot be verified against the NLEM
alphabetical index; missing or invalid dosage forms; a missing or incomplete citation; and
**unconditionally for `age < 18`**, which the module's docstring calls *"a hard-fail invariant, not
a soft signal"*. Every failure records a `failure_reason` string.

**Brand mapping never guesses.** `indian_brands.enrich_with_indian_brands` is exact-match on a
6-entry static map, with a safe fallback dict carrying `brand_mapping_available: false`.

**Stage 2 never collapses to a single answer.** `refine_diagnosis.refine` returns
`max(top_k, 3)` candidates and its multiplier is bounded at `0.5 + alignment` (range 0.5–1.5), so
no candidate is ever multiplied to zero.

### E.3 The divergence that needs an operator ruling

The locked architecture says **deterministic NLEM lookup, not RAG**. What is built *is* retrieval:

    QUERY_TEMPLATE = "essential medicines for {disease_name}"
    embedding = SentenceTransformer(...).encode([query])
    results = chroma_collection.query(query_embeddings=embedding, n_results=top_k)

`extractor.py` then takes `retrieved_chunks[0]` and rejects it only if
`distance > DISTANCE_THRESHOLD` (0.6 cosine).

It is reproducible given a frozen embedding model and a frozen vector store, and it is not
generative. But it is not a table lookup either. The only genuinely static table in the path is
`ICD_TO_DISEASE_NAME`, with 18 entries. Under an Algorithm Change Protocol, *which embedding model*
and *which vector-store build* become configuration items exactly as model weights are —
`chroma.sqlite3` and the two `length.bin` files were the pre-existing dirty state observed in the
2026-08-31 session, which is itself evidence that the store mutates.

**Reconcile the wording or reconcile the implementation. Do not leave both standing.**

---

## F. The doctor loop as a retraining substrate

### F.1 What is captured

`SubmitDoctorDecisionUseCase` writes `DiagnosisFeedbackEntity`, mirrored by the backend's
`DiagnosisFeedback` model. Persisted columns: `id`, `caseRecordId`, `icdCandidate`,
`physicianDecision`, `physicianFinalDiagnosis`, `clinicalNote`, `createdAt`, plus sync metadata.

**The label discipline is already correct and should be preserved.**
`SubmitDoctorDecisionUseCase.kt:132-138`:

    // Only a MODIFY correction that's actually one of the 18 trained classes is reimportable —
    // anything else (including a REJECT, which has no reliable ground truth by design) stays null.
    val finalDiagnosis = if (decision == PhysicianDecision.MODIFY) {
        correctedIcdCandidate?.takeIf { code -> TRAINED_ICD_CANDIDATES.any { it.icdCode == code } }
    } else { null }

`clinicalNote` is captured for audit only and never reaches `physicianFinalDiagnosis`. The audit
row carries an explicit `reimportable` flag. This matches `refine_diagnosis.py`'s
`DiagnosisFeedback` contract on the classifier side.

### F.2 The three questions, answered

| Question | Answer |
|---|---|
| Is the corrected label recoverable? | **Yes**, for MODIFY-to-a-trained-class. Structurally clean, correctly restricted. |
| Is it linked to the exact input features the classifier saw? | **No.** Nothing persists `symptom_string`, the vitals actually sent, or which of them were defaulted. `EvaluateReportEntity` stores only the *response* (`payloadJson`), the two inference timestamps and `failureCode`. |
| Is provenance and model version captured? | **No, on the evaluate path.** `KernelReportOutput` in `api_schemas.py` has no model-version field at all — the wire cannot carry it. `KernelReportEntity` and the backend `KernelReport` *do* carry `model_version`, `device_id`, `software_version` and `inference_source`, but that is Classifier A's `toy-v0.6-…` from `/v1/assess`. **Classifier B's `symptom-clf-v0.2-…` is recorded nowhere in the app or the backend.** |

### F.3 Two further blockers

**Re-assessment destroys the evidence.** Both `evaluate_reports` and `kernel_reports` carry a
unique index on `caseRecordId` as of MIGRATION_15_16, and their repositories upsert one row per
case, replacing it wholesale on retry. A re-assessment after feedback overwrites the very inputs
that feedback was about.

**The backend states the position outright.** `backend/core/app/models/kernel.py:104-112`:

    """The physician's AGREE / MODIFY / REJECT on the AI's top candidate (REQ-RFN-01).

    Stored, never consumed. No training-data reimport endpoint exists and none is planned in v1.

### F.4 Verdict

**The flywheel is not buildable on the current schema.** The label half exists and is well
disciplined. The feature and provenance half does not exist at all. Closing the gap requires new
capture — an immutable, append-only evidence row written at inference time carrying the exact
`symptom_string`, the vitals with measured-versus-defaulted flags, both model versions, the full
ranked differential and the `inference_source` — not new queries over what is already stored.

---

## G. Analysis: bias and variance risk surface

### G.1 Overfitting

4,540 sparse features fitted on 12,084 training rows drawn from a closed 118-term pool at ~3.10
terms per row. The combinatorial space the model can encounter in training is small enough to
memorise. The honest evidence is the v0.1 → v0.2 collapse: when the symptom pool was enriched and
183 strings became label-ambiguous, accuracy fell from **1.0000 to 0.9212** and f1_macro from
**1.0000 to 0.7983**. The v0.1 metrics were never measuring generalization; they were measuring the
generator's separability. There is no reason to believe v0.2's are measuring anything else, only
less of it.

The 5-fold spread is narrow (0.8240 ± 0.0067), which is a sign of a *stable memorisation*, not of
generalization — every fold is drawn from the same closed pool.

### G.2 Class imbalance

300:1 head-to-tail, with the three thinnest classes being the three most dangerous to miss:
`J22` (25 rows, F1 0.3636), `B50` (21 rows, F1 0.4000), `A91` (54 rows, F1 0.3810). The severity
gradient runs opposite to the data gradient. `MIN_RARE_DISEASE_FLOOR = 300` covers
`{B54, D50, E05.9, F41.0, A92.0, A15}` — it does **not** cover J22, B50 or A91, which is exactly why
those three are where they are.

Note also that the floors act on the *pool*, before `_balanced_sample` and before the
`icd_candidate.notna()` filter, so a floor of 300 pool rows does not guarantee 300 training rows.

### G.3 Distribution mismatch — five stacked layers

1. **US BMI distribution × WHO-Asian cutoff** → 67.1% abnormal-BMI, which alone produces the
   65% E66+M17 head (§D.4).
2. **Uniform quota over `abnormal_params` combinations** → a class mix that is a sampling artifact
   (§D.1).
3. **Uncited hand-set conditional weights** → the within-combination prevalences have no
   provenance (§D.1).
4. **Uniform SpO2 and pulse** → two of six vital features carry no realistic within-normal shape
   (§D.5).
5. **Prose at inference versus pool terms in training** → a measured 5.5-point top-1 gap before any
   other consideration (§D.5).

### G.4 Two shortcut features

Glucose *missingness* as a near-perfect proxy for E11 and a veto against five other classes
(§C.2); and defaulted vitals indistinguishable from measured ones (§C.3). Both are shortcuts the
model can exploit in training that will not hold in the field, and neither is currently detectable
after the fact from any persisted row.

---

## H. Analysis: proposed dataset strategy

### H.1 What distribution to target

The synthetic corpus should match the **presenting-complaint distribution of PHC-attending
patients in the Indore–Dhar–Pithampur catchment**, not the population prevalence of India and not
the prevalence of any disease register. These are different distributions and the current pipeline
targets none of them.

### H.2 How to source it — named, with what each can and cannot supply

| Source | Supplies | Does not supply |
|---|---|---|
| **NFHS-5 district factsheets** (Indore, Dhar) | BMI, anaemia, blood-pressure and blood-sugar prevalence by sex and broad age band, at district granularity | Presenting complaints; acute infectious burden; anything encounter-shaped |
| **ICMR-INDIAB**, Madhya Pradesh stratum | Diabetes and prediabetes prevalence, urban/rural split, with a random-glucose criterion matching the pipeline's own thresholds | Non-metabolic conditions |
| **IDSP / NVBDCP district returns** | Malaria, dengue and chikungunya case counts with genuine *seasonal shape* — the empirical replacement for the invented `fever_pattern` rate | Denominators; severity mix |
| **Nikshay / State TB Report** | TB notification rates at district level | Presentation stage |
| **HMIS OPD returns** | The actual OPD visit mix at PHC level — the closest thing to a reason-for-encounter denominator | Symptom-level detail |
| **Census 2011 → 2021 when released** | Age/sex bands for `step2b`, ideally district-rural rather than national | Anything clinical |

**The discipline that matters more than the source list:** where no source exists, the number stays
a *declared assumption* in `metadata.json` with a name and a date, not a silent weight in
`config.py`. Every one of the ICD_MAPPING weights is currently the latter.

### H.3 How much synthetic data is justified

**Do not scale. Hold volume flat or reduce it.**

22,215 rows already exceed what an 18-class, 118-term generator can express — 10,353 distinct
symptom strings across 22,215 rows means the pipeline is already repeating itself heavily. Adding
rows adds copies of the same uniform-quota distribution, and **scaling a biased distribution scales
the bias while improving every metric that is measured on the same biased distribution.** That is
the specific trap here: more data would make the numbers look better and the model worse.

Priority order instead:

1. **Fix the distribution** — replace the uniform quota with an explicit target distribution
   derived from §H.2, and record it in `metadata.json` as a first-class object.
2. **Fix the severe tail** — J22, B50 and A91 need to reach a floor that actually binds on
   *training* rows, not pool rows. Note the literature caution: naive SMOTE degrades in
   high-dimensional sparse spaces and can inject noise; boundary-aware undersampling with stacking
   has been shown competitive with oversampling on comparable clinical-text imbalance.
3. **Add the two eval sets that do not exist.** A deliberately-perturbed set (typos, synonyms,
   partial phrasing, term reordering) and a genuine out-of-distribution set (presentations the
   18-class space cannot express). Today every reported metric is measured on the generator's own
   separability; neither of these sets exists, so nothing measures generalization at all.
4. **Only then** consider volume, and justify any increase against a measured gain on the
   perturbed and OOD sets rather than on the in-distribution split.

### H.4 Provenance hygiene to fix in the same pass

Plausibility filters on vitals (§D.5), an encounter-date window, a measured-versus-imputed flag per
vital, a recorded generation log with the seed list, and a regeneration on the seeded `step2` so the
corpus is reproducible from its own record.

---

## I. Analysis: controlled retraining design (conceptual only)

**The principle: continuous harvest, discontinuous release.** The doctor loop may collect labels
every day. The model changes only as a deliberate, versioned, gated, offline event.

### I.1 The harvest side

The doctor loop appends immutable evidence rows. Append-only, never updated in place, never
overwritten by re-assessment. Each row carries: the exact `symptom_string` the model received;
every vital with a measured-or-defaulted flag; both model versions; the full ranked differential
with confidences; `inference_source`; the physician decision; the corrected label where one exists
and is reimportable; and the reviewer's cadre. Rows accumulate. Nothing about them changes a model.

### I.2 The release side — the gates, in order

1. **Freeze and hash** the candidate row set. It becomes a named, immutable configuration item.
2. **Bump the dataset version.** The synthetic corpus and the harvested rows are separate,
   separately versioned inputs; their mixing ratio is itself a recorded decision.
3. **Train offline**, on controlled infrastructure, never on a device and never in the field.
4. **Evaluate against a frozen held-out set** *plus* the perturbation and OOD sets from §H.3.
5. **Per-class no-regression check** with an explicit, pre-declared floor on the severe classes.
   A model that improves f1_macro while degrading J22 or A91 fails this gate.
6. **Re-assess hazards** against `docs/quality/risk-management-file.md`, including whether any
   existing control's effectiveness argument depended on the old model's behaviour.
7. **Operator sign-off**, recorded.
8. **Bump the model version**, in the script *and* the artifact *and* the meta file together —
   the defect in §A.3 is precisely this gate failing.
9. **DHF entry**, then ship in an app release.

### I.3 Why on-device or automatic self-retraining is rejected

**It is an uncontrolled algorithm change.** CDSCO Guidance CDSCO/MD/GD/MDSW/01/2026 §9.0 requires
an Algorithm Change Protocol — a *pre-declared envelope* of what may change and how it will be
verified. `docs/quality/qms-overview.md` currently lists the ACP as **TODO**, with the note that
*"no version-gating exists yet"*. A model that updates itself has no envelope to be inside of, and
every device in the field becomes a separate unvalidated variant.

**It is unvalidatable.** The field has no ground truth. The physician label is not an oracle — it is
itself the thing under test, and it is produced by a clinician who has already been shown the
model's answer. Automation bias (H-02) means self-retraining would preferentially reinforce the
outputs physicians accepted, which is a feedback loop toward the model's existing errors, not away
from them.

**It carries class-escalation risk.** A model that drifts toward predicting the severe classes
changes the device's own risk classification. Under the CDSCO Table 2 framing already recorded in
`docs/regulatory-foundation.md`, that is a change to the intended-use envelope, arriving without a
submission and without anyone deciding it.

**The project already has the right precedent for exactly this posture.**
`docs/sbom/model-soup-2026-09-02-v1.0.json` records it for the ASR weights:

> Every component below is compiled into the APK and changes only by shipping a new app release.
> That makes a model change a design change under normal change control, not a post-deployment
> model update … The property holds only while there is no other update path: no
> download-on-first-use, no model CDN, no silent refresh, no remote config selecting a model.

**The kernel model must inherit that rule verbatim.**

---

## J. Parked: clinical knowledge graph

Registered as a future exploration, not designed. The plausible seam is *between* the ranked
differential and the NLEM lookup — an auditable relation store expressing contraindication,
co-morbidity, age and pregnancy constraints, and referral rules that a flat 18-class table and a
6-entry brand map cannot represent, and that today have nowhere to live except as prose in a
symptom pool. The hazard surface it would add is real and must be priced before any design work: it
is a second inference surface with its own provenance, versioning and ACP obligations; its
traversal-derived conclusions *look* deterministic while depending entirely on graph content, so a
wrong edge produces a confident wrong recommendation with no probability attached to warn anyone;
and it introduces a second place where clinical knowledge lives, which must then be kept
consistent with `config.py` and the NLEM corpus or it will silently diverge from both.

---

## K. Analysis: ASR readiness

### K.1 Safe narrative fields — PR5 candidates

None of these reaches the classifier. `RetrofitEvaluateSource.kt:45-48` sends `chiefComplaint` and
`transcription` only; every other field is record, report and audit content.

`onset` · `durationBucket` · `severityScore` · `aggravatingFactors` · `relievingFactors` ·
`impactOnDailyActivities` · `relevantHistory` · `clinicalNote` · REJECT `rejectReason`

ASR expansion into these is a data-quality and speed question governed by the existing voice
confirmation gate (H-15), not a model question. Note that `KernelPayload` *does* carry
`durationBucket`, `severityScore` and `relevantHistory` — but `EvaluateRequestDto` has no field for
any of them, so the transport drops them before the wire. They are narrative today because the DTO
makes them narrative, not because anyone decided they should be.

### K.2 Classifier-input fields — must not become casual dropdowns

**`chiefComplaint`** — it *is* `symptom_string`. And every vital in `KernelPayload.vitals`, which
feeds Classifier A's risk tier and therefore Stage 2's re-ranking multiplier.

Changing the shape of `chiefComplaint` capture is a model change wearing a UI change's clothes. The
measured consequences, from the 2026-08-31 investigation:

- A 41-item collapsed vocabulary costs **-30.6 points** top-1 (0.580 vs 0.886).
- A 118-item pick-list matching the trained pool costs nothing and gains **+5.5 points** (0.941).
- The gap between them is entirely the qualifiers: **12 of 13 generic-versus-qualified pairs flip
  the top-1 diagnosis**.
- Terms must be whitespace-separated. `a|b|c` without spaces costs 3.4 points and can flip top-1,
  because `char_wb` splits on whitespace before building character n-grams.
- **The moment a pick-list can resolve to an empty token list, the unregistered E66-at-74.95%
  hazard goes live in production** (§C.1).

Any field-reduction work on `chiefComplaint` must either preserve the trained vocabulary at full
granularity, or pull the classifier's retraining forward into the same change. There is no third
option that keeps the accuracy.

---

## L. Conflicts register — repo/disk versus docs and memory

| # | Conflict | Disk says | Document says |
|---|---|---|---|
| L-1 | Model version | `symptom_model_meta.json` = `symptom-clf-v0.2-enriched-symptom-pool` | `train_symptom_classifier.py:17` = `symptom-clf-v0.1-tfidf-xgboost` |
| L-2 | Separability | 183 of 7,850 strings map to >1 label; f1_macro 0.7983 | `readme.md:114-126` — "perfectly linearly separable … 1.0000 across all 18 classes" |
| L-3 | Thin-class counts | J22 = 25, A91 = 54, B50 = 21 | `readme.md:141` — J22 = 30, A91 = 50, B50 = 21 |
| L-4 | Fever pattern rate | realized 28.13% overall / 45.0% monsoon / 18.5% otherwise | `metadata.json` + `config.py` — "~8% base, ~22% monsoon" |
| L-5 | Imputation seeding | `step2_extract_vitals.py` uses `np.random.RandomState(seed)`, mtime 2026-08-11 | `DATASET_REPOSITORY_AUDIT.md` §15.3 — "unseeded"; and the shipped dataset (2026-07-23) predates the fix, so neither describes what shipped |
| L-6 | Classifier A row count | dataset holds 22,215 rows | `model_meta.json` records `rows: 22212` |
| L-7 | NLEM architecture | embedding retrieval over ChromaDB with a 0.6 cosine gate | locked architecture — "deterministic NLEM lookup, not RAG" |
| L-8 | Hazard registration | E66-at-74.95% empty-input prior measured 2026-08-31 | `risk-management-file.md` H-01…H-19 — not present |
| L-9 | Region calibration | no MP/Indore/Dhar/Pithampur epidemiology anywhere in `drishti_pipeline` | project framing assumes target-region calibration |
| L-10 | "Offline on-device" | no on-device clinical inference exists; only ASR ONNX ships in the APK, and `InferenceSource.kt:11` defines `REAL_INFERENCE` as "the live FastAPI+XGBoost kernel answered" | project framing describes an offline on-device kernel |

L-10 was found while grounding the follow-on architecture work and is recorded here because it
belongs with the others: it is a premise-versus-disk gap, not a defect. The offline on-device
kernel is a target, not the current build.

---

## M. What this audit does not claim

Every accuracy figure in §A measures how well the model reproduces a synthetic generator's built-in
separability. **None of them is a clinical performance claim and none should be quoted as one.**
`readme.md:164-171` states the same thing in the project's own words, and it remains correct.

The relative comparisons in §K are robust to that caveat — collapsing discriminative terms destroys
separability regardless of whether the dataset is realistic — but the absolute numbers are not
evidence of field performance and no decision should treat them as such.
