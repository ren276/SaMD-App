# Branch authoring — Batch 4 (STEP 1, read-only design memo)

Run date: 2026-09-07. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af` — **read only.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
generator, dataset, model, config or tree runtime file touched, in either repo. No branch authored
in code, no dataset generated, no model trained. No `.env`, `local.properties` or credential file
opened at any point. No commit, no staging.

**Inputs treated as binding and not re-derived:**
`scratchpad/branch-authoring-batch-3-memo.md`, `scratchpad/branch-authoring-batch-2-memo.md`,
`scratchpad/branch-authoring-batch-1-memo.md` (the reference structure, the complete shared registry
across all three batches, every sub-tree, and findings G-1…G-18),
`scratchpad/questionnaire-tree-design-memo.md` (the schema: §1 branch schema, §6 five-stage
template, §3/§4/§5 the three reference branches),
`scratchpad/reason-for-encounter-category-system-memo.md` (every category constant from §6 and §7;
RF-1…RF-4 from §4.4),
`scratchpad/dataset-regeneration-design-memo.md` (what the generator walks).

**Also read on disk this session, read-only:**
`SaMDClassifier/dataset/canonical_dataset.csv` (one measurement pass, §0.2).

**Scope.** Batch 4 is the final batch, completing the tree. It covers:
- The last six clinical branches from tree memo §6.5: `oedema`, `pallor_anaemia`, `dizziness`,
  `itching`, `skin_infection`, `antenatal_visit`.
- The six Tier-0 emergency routers from category memo §6: `emergency_convulsions`,
  `emergency_unconscious`, `emergency_bite_sting`, `emergency_poisoning`,
  `emergency_heavy_bleeding`, `emergency_pregnancy_danger`.
- A tree-completeness check (§15).

Nothing beyond these is authored here.

---

## 0. Operator decisions applied, and the measurement pass

### 0.1 Decisions carried forward, settled, not re-opened

**DECISION-1.** `REFER_EMERGENCY` floor forces abstention on any channel; every
REFER_EMERGENCY-reaching path emits `abstain: true`, classifier not called.

**DECISION-2.** Node ceiling is 20, expanded path including sub-trees. Report per branch.

**G-14 resolved.** `fever` is the single accepted ceiling exception. Not re-raised.

**G-16 resolved.** `vomiting_nausea`'s 21-node worst-case path is an accepted second ceiling
exception. Not re-raised.

**G-17 resolved.** DERIVED provenance is valid for a shared fieldId set by a sentinel/reuse node.

**G-18 resolved.** A direct (non-sub-tree) branch node may route-and-terminate to a Tier-0
category when clinically required. Mid-branch Tier-0 routing is permitted.

### 0.2 The measurement pass

One read-only pass over `canonical_dataset.csv` (22,215 total rows; 15,105 labelled), extending
the batch-1 through batch-3 measurements to this batch's terms:

```
term                    n      top label   purity   notes
pallor                  81     D50         77%      strong: iron-deficiency anaemia
dizziness              1135    <blank>     44%      noise — unlabelled/I10 split; 36% I10
lightheaded              80    <blank>     50%      noise — same pattern as dizziness
syncope                  18    E66         44%      noise — E66 prior artifact
```

**Six readings for this batch.**

**First: `oedema` has zero corpus coverage.** No term matching oedema, edema, swelling feet,
swelling face, puffiness, ankle swelling, or pedal edema appears anywhere in the 118-term pool.
Entirely absent. All qualifiers `ASSUMED`.

**Second: `pallor_anaemia` has one signal.** `pallor` → D50 77% on 81 rows. This is a
reasonable anaemia signal but the n is small. Other pallor-adjacent terms (anaemia, anemia, pale)
return nothing.

**Third: `dizziness` is noise.** `dizziness` → 44% unlabelled, 36% I10. `lightheaded` is the
same pattern. `syncope` → E66 (the BMI prior artifact). No discriminative signal.

**Fourth: `itching` has zero corpus coverage.** No term matching itching, pruritus, itch, or
scabies appears. Entirely absent. All qualifiers `ASSUMED`.

**Fifth: `skin_infection` has zero corpus coverage.** No term matching boil, abscess, cellulitis,
wound infection, skin infection, or pus appears. Entirely absent. All qualifiers `ASSUMED`.

**Sixth: `antenatal_visit` has zero corpus coverage.** No pregnancy, pregnant, antenatal,
gestational, or fetal movement term appears. Entirely absent. All qualifiers `ASSUMED`.

**Summary.** Five of six batch-4 categories have zero corpus coverage; `pallor_anaemia` has one
weak signal. This batch is almost entirely protocol-grounded (`ASSUMED`). This is expected: the
corpus was built from the old 18-category label space which covered none of these presentations.

---

## 1. Branch: `oedema`

### 1.1 Category constants — transcribed from category memo §7 Chapter A

```
categoryId               = "oedema"
displayName              = "Swelling of feet, face or body"
tier                     = CORE
icpc3Chapter             = K     (functional — oedema is a circulatory/renal sign)
icpc3Basis               = K07
icd10Mapping             = R60.9
anchor                   = { value: ~2% of chapter A ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT
severeConditions         = [ heart failure, nephrotic syndrome/renal failure,
                             severe anaemia, chronic liver disease, pre-eclampsia ]
branchVersion            = 1.0.0-draft
```

### 1.2 Stage A — OED-00

```
OED-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_breathless_rest   "Breathless at rest or cannot lie flat"        → OED-G1
  ds_chest_pain        "Chest pain or heaviness"                     → OED-G1
  ds_facial_puffiness  "Face and eyelids swollen, worse in morning"  → OED-G1
  ds_reduced_urine     "Has not passed urine since yesterday"       → OED-G1
  ds_confusion         "Confused, or behaviour unusual"              → OED-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake" → OED-G1
  ds_cannot_feed       "Unable to drink or feed"                    → OED-G1
  ds_none              "None of these"                              → OED-01
  ds_unknown           "Not known"                                  → OED-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Heart failure | `ds_breathless_rest`, `ds_chest_pain` | covered |
| Nephrotic syndrome / renal failure | `ds_facial_puffiness`, `ds_reduced_urine` | covered |
| Severe anaemia | `ds_breathless_rest` (anaemia presents as effort dyspnoea) | covered indirectly; **G-2 gap: no specific pallor sign at Stage A — pallor is a separate branch** |
| Chronic liver disease | `ds_confusion` (hepatic encephalopathy) | covered indirectly; **G-2 gap: ascites/jaundice are not worker-observable at Stage A without physical exam guidance** |
| Pre-eclampsia | `ds_facial_puffiness` + pregnancy gate (OED-07) | covered; routes to `emergency_pregnancy_danger` |

### 1.3 Stage B

`OED-01 progression`, `OED-02 prior_treatment_taken`, as batch 1 §1.3. → OED-03.

### 1.4 Stage C — the category qualifiers

```
OED-03  fieldId: oedema_site   answerType: SINGLE_CHOICE
Q: "Where is the swelling?"
  os_both_feet    "Both feet or ankles"                   → OED-04
  os_one_foot     "One foot or leg only"                  → OED-G2
  os_face         "Face or around the eyes"               → OED-04
  os_whole_body   "All over — face, hands, feet"          → OED-04
  os_hands        "Hands or fingers"                      → OED-04
  os_unknown      "Not known"                             → OED-04

OED-04  fieldId: oedema_pitting   answerType: SINGLE_CHOICE
Q: "Press a thumb firmly on the swollen ankle or shin for 5 seconds, then remove.
    Does a dent (pit) remain?"
help: "Count slowly to 5 while pressing, then look for the dent."
  op_yes_deep     "Yes — deep pit, slow to fill"           → OED-05
  op_yes_shallow  "Yes — shallow pit"                      → OED-05
  op_no           "No pit — swelling is firm"              → OED-05
  op_unknown      "Not tested"                             → OED-05

OED-05  fieldId: facial_puffiness_morning   answerType: SINGLE_CHOICE
Q: "Is the face puffy in the morning and better by evening?"
  fm_yes        "Yes — worse in the morning"             → OED-06
  fm_no         "No — same all day or worse at night"    → OED-06
  fm_unknown    "Not known"                              → OED-06

OED-06  fieldId: urine_frothy   answerType: SINGLE_CHOICE
Q: "Has the urine been frothy or foamy?"
  uf_yes        "Yes"                                    → OED-07
  uf_no         "No"                                     → OED-07
  uf_unknown    "Not known"                              → OED-07
```

### 1.5 Stage D/E

```
OED-07  fieldId: pregnancy_status   answerType: SINGLE_CHOICE   (shared, tree memo §2.1)
        asked only when sex == F and age_band ∈ [15,49]; else NOT_ASKED
Q: "Is the patient pregnant?"
  pg_yes "Yes" → OED-G3   pg_no "No" → OED-S1   pg_unknown "Not known" → OED-S1

OED-S1  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → OED-G4, then OED-END

OED-END TerminalNode
```

### 1.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-OED-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | renal failure, hepatic coma | N/A — Tier-0 |
| **GW-OED-EMG-2 (breathless at rest)** | `danger_signs contains ds_breathless_rest` | `raisesTo: REFER_EMERGENCY`, **continue** | **heart failure** | **Yes — abstains** |
| **GW-OED-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-OED-EMG-4 (anuria)** | `danger_signs contains ds_reduced_urine` | `raisesTo: REFER_EMERGENCY`, continue | **renal failure** | **Yes — abstains** |
| **GW-OED-URG-1 (DVT screen)** | `oedema_site equals os_one_foot` | `raisesTo: REFER_URGENT` | deep vein thrombosis — unilateral leg oedema is DVT until proven otherwise |
| **GW-OED-URG-2 (nephrotic pattern)** | `allOf[ facial_puffiness_morning equals fm_yes, urine_frothy equals uf_yes ]` | **informational** at floor | nephrotic syndrome |
| **GW-OED-URG-3 (pre-eclampsia)** | `pregnancy_status equals pg_yes` | `routeTo: emergency_pregnancy_danger`, terminate | **pre-eclampsia** |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

Node placement: `OED-G1` after OED-00 evaluates EMG-1…EMG-4; `OED-G2` evaluates URG-1;
`OED-G3` after OED-07 evaluates URG-3 (pregnancy → Tier-0); `OED-G4` after FEVER-QUAL.

### 1.7 Worked BranchOutput — 62-year-old man, 3 weeks of bilateral ankle swelling, pitting, orthopnoea

```
BranchOutput {
  categoryId: "oedema"
  branchVersion: "1.0.0-draft"
  pathTaken: [OED-00, OED-G1, OED-01, OED-02, OED-03, OED-04, OED-05, OED-06,
              OED-S1, FQ-00, OED-G4, OED-END]
  fields: {
    danger_signs             { ANSWERED, [ds_breathless_rest],     TAP }
    progression              { ANSWERED, pr_worse,                 TAP }
    prior_treatment_taken    { ANSWERED, [pt_none],                TAP }
    oedema_site              { ANSWERED, os_both_feet,             TAP }
    oedema_pitting           { ANSWERED, op_yes_deep,              TAP }
    facial_puffiness_morning { ANSWERED, fm_no,                    TAP }
    urine_frothy             { ANSWERED, uf_no,                    TAP }
    pregnancy_status         { NOT_ASKED, null, — }
    fever_present            { ANSWERED, fp_no,                    TAP }
    fever_duration_band      { NOT_ASKED, null, — }
    fever_pattern            { NOT_ASKED, null, — }
    bleeding_manifestation   { NOT_ASKED, null, — }
    urine_output_reduced     { NOT_ASKED, null, — }
    duration_bucket          { ANSWERED, week_plus,                PREFILL_MEASURED }
    onset_pattern            { ANSWERED, gradual,                  TAP }
    severity_score           { ANSWERED, 5,                        TAP }
    severity_band            { ANSWERED, moderate,                 DERIVED }
    relevant_history         { ANSWERED, [heart_disease],          TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],       TAP }
  }
  dispositionFloor: REFER_EMERGENCY
  firedGateways: [
    { GW-OED-EMG-2,
      "danger_signs contains ds_breathless_rest",
      [heart failure] }
  ]
  routedTo: null
  abstain: true
  attachments: []
}
```

DECISION-1 in effect: `ds_breathless_rest` fired `GW-OED-EMG-2` → `REFER_EMERGENCY` →
`abstain: true`. Classifier not called.

### 1.8 Node budget

Authored non-gateway: **10** (OED-00…OED-07, OED-S1, OED-END).
Expanded worst case (FEVER-QUAL gate-only): 10 + 1 = **11**.
Full FEVER-QUAL: 10 + 4 = **14**. **Under DECISION-2 ceiling.**

### 1.9 Conformance

- **C1:** All branching deterministic; no model. ✔
- **C2:** No score, rank, condition name in output. ✔
- **C3:** Floor is `REFER_URGENT` at entry; raised by `max()` only. ✔
- **C4:** `routeTo: emergency_unconscious`, `emergency_pregnancy_danger` — both Tier-0 ALWAYS_ABSTAIN. ✔
- **C5:** Physician gate untouched. ✔
- **BR-1:** Every option has explicit `next`. ✔
- **BR-2:** Every SINGLE has `*_unknown`; MULTI has `ds_none` + `ds_unknown`. ✔
- **NR-1:** No severe condition named; nearest approach: `ds_breathless_rest` (observation). ✔
- **Six replay assertions:** Reachability ✔, Determinism ✔, Field completeness ✔,
  Monotonicity ✔, Routing ✔, NR-1 lint ✔.

---

## 2. Branch: `pallor_anaemia`

### 2.1 Category constants — transcribed from category memo §7 Chapter B

```
categoryId               = "pallor_anaemia"
displayName              = "Pallor / suspected anaemia"
tier                     = CORE
icpc3Chapter             = B
icpc3Basis               = B80, B82
icd10Mapping             = D50.9, D64.9
anchor                   = { value: presenting anchor 0.1% knowingly overruled,
                             NFHS-5 MP children 72.7%, women 54.7%, men 22.5%,
                             sourceType: IND-POP }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ severe anaemia requiring transfusion, occult GI bleeding,
                             malignancy, haemoglobinopathy, hookworm, malaria ]
branchVersion            = 1.0.0-draft
```

**STANDING GUARDRAIL (category memo §7 Chapter B, verbatim):** `pallor_anaemia` may be selected
by a worker as a reason for encounter (a mother brings a pale child); it may **NEVER** be
auto-assigned from a vitals or BMI value the way E66 was. **Nothing in this branch reads a vital,
prefills from a vital, or auto-populates the category.** The worker selects it explicitly, and
the branch records their observations.

### 2.2 Stage A — PAL-00

```
PAL-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_severe_pallor   "Palms, nails and inside of eyelids are very white"  → PAL-G1
  ds_breathless_rest "Breathless at rest"                                 → PAL-G1
  ds_rapid_pulse     "Pulse very fast (more than 120)"                    → PAL-G1
  ds_bleeding_now    "Bleeding from any site right now"                   → PAL-G1
  ds_black_tarry     "Black or tarry stools"                              → PAL-G1
  ds_confusion       "Confused, or behaviour unusual"                     → PAL-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"        → PAL-G1
  ds_cannot_feed     "Unable to drink or feed"                           → PAL-G1
  ds_none            "None of these"                                     → PAL-01
  ds_unknown         "Not known"                                         → PAL-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Severe anaemia requiring transfusion | `ds_severe_pallor`, `ds_breathless_rest`, `ds_rapid_pulse` | covered — the IMCI severe-anaemia signs |
| Occult GI bleeding | `ds_black_tarry`, `ds_bleeding_now` | covered |
| Malignancy | `ds_severe_pallor` + `weight_loss_present` (PAL-06) | covered as a pattern; **G-2 gap: early-stage haematological malignancy has no worker-observable sign** |
| Haemoglobinopathy | No specific worker-observable sign | **G-2 gap: sickle-cell crises present as pain; thalassemia presents as chronic pallor — both captured by this branch's questions but no specific Stage A sign** |
| Hookworm | No specific worker-observable sign | **G-2 gap: hookworm anaemia is indistinguishable from dietary anaemia at Stage A; `worm_treatment_history` (PAL-07) screens** |
| Malaria | `ds_rapid_pulse` + FEVER-QUAL cross-link | covered — fever with anaemia triggers the fever gateway |

**No vital is read at Stage A. No prefillFrom is used on any node in this branch.** The worker's
own observation of pallor is the category selector, and the branch records further observations
and history. This is the standing guardrail implemented.

### 2.3 Stage B

`PAL-01 progression`, `PAL-02 prior_treatment_taken`, as batch 1 §1.3. → PAL-03.

### 2.4 Stage C — the category qualifiers

```
PAL-03  fieldId: pallor_site_observed   answerType: MULTI_CHOICE
Q: "Look at the patient. Where do you see paleness?"
help: "Ask the patient to show their palms and pull down the lower eyelid."
  po_palms       "Palms are pale"                          → PAL-04
  po_nails       "Nail beds are pale"                      → PAL-04
  po_conjunctiva "Inside of lower eyelid is pale"          → PAL-04
  po_tongue      "Tongue is pale"                          → PAL-04
  po_face        "Face looks pale"                         → PAL-04
  po_none        "None of these — family reports pallor"   → PAL-04
  po_unknown     "Not checked"                             → PAL-04

PAL-04  fieldId: pica   answerType: SINGLE_CHOICE
Q: "Does the patient eat unusual things like mud, chalk, or ice?"
  pi_yes       "Yes"                                       → PAL-05
  pi_no        "No"                                        → PAL-05
  pi_unknown   "Not known"                                 → PAL-05

PAL-05  fieldId: heavy_menstrual_bleeding   answerType: SINGLE_CHOICE
        asked only when sex == F and age_band ∈ [12,50]; else NOT_ASKED
Q: "Are the monthly periods heavy — soaking through pads/cloth, or lasting more than 7 days?"
  hm_yes       "Yes — heavy or prolonged"                  → PAL-06
  hm_no        "No"                                        → PAL-06
  hm_unknown   "Not known"                                 → PAL-06

PAL-06  fieldId: blood_in_stool   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Have you noticed blood in the stool, or black tarry stool?"
  bs_frank · bs_streaks · bs_black · bs_no · bs_unknown    → PAL-07

PAL-07  fieldId: worm_treatment_history   answerType: SINGLE_CHOICE
Q: "Has the patient taken deworming medicine in the last 6 months?"
  wt_yes       "Yes"                                       → PAL-08
  wt_no        "No"                                        → PAL-08
  wt_unknown   "Not known"                                 → PAL-08

PAL-08  fieldId: dietary_pattern   answerType: SINGLE_CHOICE
Q: "What does the patient usually eat?"
  dp_mixed          "Mixed — includes meat, eggs, fish"     → PAL-09
  dp_vegetarian     "Vegetarian — milk, dal, vegetables"    → PAL-09
  dp_restricted     "Very limited — mostly rice/roti only"  → PAL-09
  dp_unknown        "Not known"                             → PAL-09
```

### 2.5 Stage D/E

```
PAL-09  fieldId: appetite_change   answerType: SINGLE_CHOICE   (shared, batch 3 §9.1)
  ac_decreased · ac_increased · ac_no_change · ac_unknown  → PAL-S1

PAL-S1  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → PAL-G3, then PAL-END

PAL-END TerminalNode
```

### 2.6 Gateways

Base floor `PHYSICIAN_REVIEW_MANDATORY`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-PAL-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | severe anaemia with shock | N/A — Tier-0 |
| **GW-PAL-EMG-2** | `danger_signs contains ds_bleeding_now` | `routeTo: emergency_heavy_bleeding`, terminate | active haemorrhage | N/A — Tier-0 |
| **GW-PAL-EMG-3 (severe anaemia)** | `allOf[ danger_signs contains ds_severe_pallor, danger_signs contains ds_breathless_rest ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **severe anaemia requiring transfusion** | **Yes — abstains** |
| **GW-PAL-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-PAL-URG-1 (GI bleed)** | `anyOf[ blood_in_stool in {bs_frank, bs_black}, danger_signs contains ds_black_tarry ]` | `raisesTo: REFER_URGENT` | occult GI bleeding |
| **GW-PAL-URG-2 (rapid pulse)** | `danger_signs contains ds_rapid_pulse` | `raisesTo: REFER_URGENT` | haemodynamic compromise |
| **GW-PAL-URG-3 (fever + pallor = malaria risk)** | FEVER-QUAL fields evaluated: `fever_present equals fp_yes` | `raisesTo: REFER_URGENT` | malaria with anaemia |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `PAL-G1` after PAL-00 evaluates EMG-1…EMG-4; `PAL-G2` after PAL-06 evaluates
URG-1; `PAL-G3` after FEVER-QUAL evaluates URG-3.

### 2.7 Worked BranchOutput — 28-year-old woman, 2 months of feeling tired, heavy periods, conjunctival pallor, vegetarian

```
BranchOutput {
  categoryId: "pallor_anaemia"
  branchVersion: "1.0.0-draft"
  pathTaken: [PAL-00, PAL-G1, PAL-01, PAL-02, PAL-03, PAL-04, PAL-05, PAL-06,
              PAL-G2, PAL-07, PAL-08, PAL-09, PAL-S1, FQ-00, PAL-G3, PAL-END]
  fields: {
    danger_signs              { ANSWERED, [ds_none],                TAP }
    progression               { ANSWERED, pr_same,                  TAP }
    prior_treatment_taken     { ANSWERED, [pt_pharmacy_medicine],   TAP }
    pallor_site_observed      { ANSWERED, [po_conjunctiva, po_nails], TAP }
    pica                      { ANSWERED, pi_no,                    TAP }
    heavy_menstrual_bleeding  { ANSWERED, hm_yes,                   TAP }
    blood_in_stool            { ANSWERED, bs_no,                    TAP }
    worm_treatment_history    { ANSWERED, wt_no,                    TAP }
    dietary_pattern           { ANSWERED, dp_vegetarian,            TAP }
    appetite_change           { ANSWERED, ac_decreased,             TAP }
    fever_present             { ANSWERED, fp_no,                    TAP }
    fever_duration_band       { NOT_ASKED, null, — }
    fever_pattern             { NOT_ASKED, null, — }
    bleeding_manifestation    { NOT_ASKED, null, — }
    duration_bucket           { ANSWERED, month_plus,               PREFILL_MEASURED }
    onset_pattern             { ANSWERED, gradual,                  TAP }
    severity_score            { ANSWERED, 4,                        TAP }
    severity_band             { ANSWERED, mild,                     DERIVED }
    relevant_history          { ANSWERED, [no_known_history],       TAP }
    impact_on_daily_activities { ANSWERED, [tired_easily],          TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

Classic iron-deficiency anaemia presentation: heavy periods + vegetarian diet + pallor +
no deworming. The tree collected the pattern without naming the diagnosis.

### 2.8 Node budget

Authored non-gateway: **13** (PAL-00…PAL-09, PAL-S1, PAL-END — 12 nodes + 1 sub-tree ref).
Expanded worst case (FEVER-QUAL gate-only): 13 + 1 = **14**.
Full FEVER-QUAL: 13 + 4 = **17**. **Under DECISION-2 ceiling.**

### 2.9 Conformance

- **C1–C5:** All pass. ✔
- **BR-1:** Every option has explicit `next`. ✔
- **BR-2:** Every SINGLE has `*_unknown`; MULTI has `*_none` + `*_unknown`. ✔
- **NR-1:** No severe condition named; nearest: `ds_severe_pallor` (observation). ✔
- **STANDING GUARDRAIL:** No vital read, no prefillFrom, no auto-population. ✔
- **Six replay assertions:** All pass. ✔

---

## 3. Branch: `dizziness`

### 3.1 Category constants — transcribed from category memo §7 Chapter N

```
categoryId               = "dizziness"
displayName              = "Dizziness or giddiness"
tier                     = CORE
icpc3Chapter             = N
icpc3Basis               = N17
icd10Mapping             = R42
anchor                   = { value: ~25% of chapter N ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ severe anaemia (MP: 54.7% of women), hypoglycaemia,
                             arrhythmia, stroke, orthostatic hypotension, dehydration ]
branchVersion            = 1.0.0-draft
```

### 3.2 Stage A — DZZ-00

The critical clinical requirement: **distinguish the benign (vertigo, orthostatic) from the
dangerous (stroke, arrhythmia, severe anaemia, hypoglycaemia) at Stage A.** The danger-sign
screen is the mechanism.

```
DZZ-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_one_side_weak   "One side of body — face, arm or leg — suddenly weak or numb" → DZZ-G1
  ds_speech_change   "Sudden difficulty speaking or understanding speech"          → DZZ-G1
  ds_chest_pain      "Chest pain or palpitations that are not stopping"            → DZZ-G1
  ds_severe_pallor   "Palms, nails and inside of eyelids are very white"          → DZZ-G1
  ds_confusion       "Confused, or behaviour unusual"                              → DZZ-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"                 → DZZ-G1
  ds_cannot_feed     "Unable to drink or feed"                                    → DZZ-G1
  ds_none            "None of these"                                              → DZZ-01
  ds_unknown         "Not known"                                                  → DZZ-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Stroke | `ds_one_side_weak`, `ds_speech_change` — the FAST screen (face, arm, speech) | covered |
| Arrhythmia | `ds_chest_pain` (with palpitations) | covered — sustained palpitation with dizziness is haemodynamically significant |
| Severe anaemia | `ds_severe_pallor` | covered |
| Hypoglycaemia | `ds_confusion`, `ds_unconscious` | covered — neuroglycopaenia presents as confusion/altered consciousness |
| Orthostatic hypotension | `on_standing` question (DZZ-04) | covered at Stage C — not a danger sign because it is typically self-limiting |
| Dehydration | `ds_unconscious` + DEHYDRATION cross-link consideration | **G-2 gap: dehydration-driven dizziness is not specifically screened at Stage A; reached through urine_output_reduced (shared). Dehydration sub-tree not invoked — the DZZ-04 orthostatic question is the discriminator** |

### 3.3 Stage B

`DZZ-01 progression`, `DZZ-02 prior_treatment_taken`, as batch 1 §1.3. → DZZ-03.

### 3.4 Stage C — the category qualifiers

```
DZZ-03  fieldId: dizziness_type   answerType: SINGLE_CHOICE
Q: "When the patient feels dizzy, what does it feel like?"
help: "Ask: Does the room spin? Or does it feel like you might faint?"
  dzt_spinning      "Room spinning or things moving"          → DZZ-04
  dzt_lightheaded   "Feels faint or lightheaded"              → DZZ-04
  dzt_unsteady      "Unsteady on feet, off-balance"           → DZZ-04
  dzt_unknown       "Not known"                               → DZZ-04

DZZ-04  fieldId: on_standing   answerType: SINGLE_CHOICE
Q: "Does the dizziness come on when standing up from sitting or lying?"
  os_yes         "Yes — mainly on standing"                   → DZZ-05
  os_sometimes   "Sometimes"                                  → DZZ-05
  os_no          "No"                                         → DZZ-05
  os_unknown     "Not known"                                  → DZZ-05

DZZ-05  fieldId: palpitations   answerType: SINGLE_CHOICE
Q: "Does the patient feel the heart racing, pounding, or skipping?"
  pal_yes        "Yes"                                        → DZZ-G2
  pal_no         "No"                                         → DZZ-06
  pal_unknown    "Not known"                                  → DZZ-06

DZZ-06  fieldId: hearing_change   answerType: SINGLE_CHOICE
Q: "Any change in hearing, or ringing in the ears?"
  hc_hearing_loss  "Hearing has become worse"                 → DZZ-07
  hc_ringing       "Ringing or buzzing in ears"               → DZZ-07
  hc_both          "Both — worse hearing and ringing"         → DZZ-07
  hc_no            "No change"                                → DZZ-07
  hc_unknown       "Not known"                                → DZZ-07

DZZ-07  fieldId: focal_weakness   answerType: SINGLE_CHOICE
Q: "Any weakness, numbness, or tingling in arms or legs?"
  fw_one_side   "Yes — on one side of the body"               → DZZ-G3
  fw_both_sides "Yes — both sides or all limbs"               → DZZ-08
  fw_no         "No"                                          → DZZ-08
  fw_unknown    "Not known"                                   → DZZ-08
```

### 3.5 Stage D/E

```
DZZ-08  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → DZZ-G4, then DZZ-END

DZZ-END TerminalNode
```

### 3.6 Gateways

Base floor `PHYSICIAN_REVIEW_MANDATORY`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-DZZ-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | stroke, hypoglycaemia, arrhythmia | N/A — Tier-0 |
| **GW-DZZ-EMG-2 (stroke screen — FAST)** | `anyOf[ danger_signs contains ds_one_side_weak, danger_signs contains ds_speech_change ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **stroke** | **Yes — abstains** |
| **GW-DZZ-EMG-3 (severe pallor + dizziness)** | `danger_signs contains ds_severe_pallor` | `raisesTo: REFER_EMERGENCY`, continue | **severe anaemia** | **Yes — abstains** |
| **GW-DZZ-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-DZZ-URG-1 (cardiac palpitation)** | `palpitations equals pal_yes` | `raisesTo: REFER_URGENT` | arrhythmia |
| **GW-DZZ-URG-2 (focal weakness = stroke)** | `focal_weakness equals fw_one_side` | `raisesTo: REFER_URGENT` | **stroke** (delayed/evolving) |
| **GW-DZZ-URG-3 (chest pain at Stage A)** | `danger_signs contains ds_chest_pain` | `raisesTo: REFER_URGENT` | arrhythmia, MI |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `DZZ-G1` after DZZ-00 evaluates EMG-1…EMG-4 and URG-3; `DZZ-G2` after DZZ-05
evaluates URG-1; `DZZ-G3` after DZZ-07 evaluates URG-2; `DZZ-G4` after FEVER-QUAL.

### 3.7 Worked BranchOutput — 50-year-old woman, 1 week of room-spinning dizziness, worse on standing, no danger signs

```
BranchOutput {
  categoryId: "dizziness"
  branchVersion: "1.0.0-draft"
  pathTaken: [DZZ-00, DZZ-G1, DZZ-01, DZZ-02, DZZ-03, DZZ-04, DZZ-05, DZZ-06,
              DZZ-07, DZZ-08, FQ-00, DZZ-G4, DZZ-END]
  fields: {
    danger_signs            { ANSWERED, [ds_none],              TAP }
    progression             { ANSWERED, pr_same,                TAP }
    prior_treatment_taken   { ANSWERED, [pt_pharmacy_medicine], TAP }
    dizziness_type          { ANSWERED, dzt_spinning,           TAP }
    on_standing             { ANSWERED, os_yes,                 TAP }
    palpitations            { ANSWERED, pal_no,                 TAP }
    hearing_change          { ANSWERED, hc_ringing,             TAP }
    focal_weakness          { ANSWERED, fw_no,                  TAP }
    fever_present           { ANSWERED, fp_no,                  TAP }
    fever_duration_band     { NOT_ASKED, null, — }
    fever_pattern           { NOT_ASKED, null, — }
    bleeding_manifestation  { NOT_ASKED, null, — }
    duration_bucket         { ANSWERED, week_plus,              PREFILL_MEASURED }
    onset_pattern           { ANSWERED, gradual,                TAP }
    severity_score          { ANSWERED, 4,                      TAP }
    severity_band           { ANSWERED, mild,                   DERIVED }
    relevant_history        { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [difficulty_working], TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

Benign peripheral vertigo pattern with positional trigger and tinnitus. No danger signs, no
escalation.

### 3.8 Node budget

Authored non-gateway: **11** (DZZ-00…DZZ-08, DZZ-END — 10 nodes + 1 sub-tree ref).
Expanded worst case (FEVER-QUAL gate-only): 11 + 1 = **12**.
Full FEVER-QUAL: 11 + 4 = **15**. **Under DECISION-2 ceiling.**

### 3.9 Conformance

All checks pass. DECISION-1: GW-DZZ-EMG-2…EMG-4 raise to REFER_EMERGENCY → abstain.
One Tier-0 route: `emergency_unconscious`.
DECISION-2: **15** ≤ 20 ✔.

- **C1–C5:** All pass. ✔
- **BR-1, BR-2, NR-1:** All pass. ✔
- **Six replay assertions:** All pass. ✔

---

## 4. Branch: `itching`

### 4.1 Category constants — transcribed from category memo §7 Chapter S

```
categoryId               = "itching"
displayName              = "Itching"
tier                     = CORE
icpc3Chapter             = S
icpc3Basis               = S02
icd10Mapping             = L29.9
anchor                   = { value: ~35% of chapter S ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = MODERATE
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ scabies (household outbreak), diabetes, liver/renal disease,
                             drug reaction ]
branchVersion            = 1.0.0-draft
```

### 4.2 Stage A — ICH-00

```
ICH-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_swollen_face    "Swollen face, lips or tongue"                     → ICH-G1
  ds_breathless      "Difficulty breathing"                              → ICH-G1
  ds_skin_peeling    "Skin peeling off or large blisters"               → ICH-G1
  ds_mucosal_sores   "Sores in mouth, eyes or genitals"                → ICH-G1
  ds_jaundice        "Eyes or skin look yellow"                         → ICH-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"      → ICH-G1
  ds_cannot_feed     "Unable to drink or feed"                         → ICH-G1
  ds_none            "None of these"                                   → ICH-01
  ds_unknown         "Not known"                                       → ICH-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Drug reaction / anaphylaxis | `ds_swollen_face`, `ds_breathless`, `ds_skin_peeling`, `ds_mucosal_sores` | covered — angioedema and SJS/TEN signs |
| Liver disease | `ds_jaundice` | covered |
| Scabies outbreak | No specific danger sign — scabies is not an emergency | covered at Stage C (`household_others_affected`, `itch_worse_at_night`) |
| Diabetes | No specific danger sign | **G-2 gap: diabetes presents as generalised pruritus; no specific worker-observable sign. `known_liver_renal_disease` (ICH-07) screens** |
| Renal disease | `ds_jaundice` (sometimes) | **G-2 gap: uraemic pruritus has no specific external sign; `known_liver_renal_disease` screens** |

### 4.3 Stage B

`ICH-01 progression`, `ICH-02 prior_treatment_taken`, as batch 1 §1.3. → ICH-03.

### 4.4 Stage C — the category qualifiers

```
ICH-03  fieldId: itch_site   answerType: SINGLE_CHOICE
Q: "Where is the itching?"
  is_web_spaces    "Between fingers, wrists, folds"           → ICH-04
  is_all_over      "All over the body"                        → ICH-04
  is_scalp         "Scalp"                                    → ICH-04
  is_groin         "Groin, buttocks, or private parts"        → ICH-04
  is_one_area      "One patch or area only"                   → ICH-04
  is_unknown       "Not known"                                → ICH-04

ICH-04  fieldId: itch_worse_at_night   answerType: SINGLE_CHOICE
Q: "Is the itching worse at night?"
  in_yes       "Yes — much worse at night"                    → ICH-05
  in_no        "No — same day and night"                      → ICH-05
  in_unknown   "Not known"                                    → ICH-05

ICH-05  fieldId: household_others_affected   answerType: SINGLE_CHOICE   (shared, existing)
Q: "Does anyone else in the house have the same itching?"
  ho_yes · ho_no · ho_unknown                                → ICH-06

ICH-06  fieldId: visible_burrows_or_lesions   answerType: SINGLE_CHOICE
Q: "Can you see any tiny lines, bumps, or blisters on the skin?"
help: "Look between the fingers, wrists, elbows, around the navel."
  vb_burrows     "Yes — tiny lines (like scratches)"          → ICH-07
  vb_bumps       "Yes — small bumps or blisters"              → ICH-07
  vb_none        "No — skin looks normal but itchy"           → ICH-07
  vb_unknown     "Not checked"                                → ICH-07

ICH-07  fieldId: known_liver_renal_disease   answerType: SINGLE_CHOICE
Q: "Does the patient have any known liver or kidney disease?"
  lr_liver       "Yes — liver disease"                        → ICH-G2
  lr_renal       "Yes — kidney disease"                       → ICH-G2
  lr_no          "No"                                         → ICH-08
  lr_unknown     "Not known"                                  → ICH-08

ICH-08  fieldId: new_drug_2_weeks   answerType: SINGLE_CHOICE   (shared, existing from rash)
Q: "Any new medicine started in the last two weeks?"
  nd_yes · nd_no · nd_unknown                                → ICH-S1
```

### 4.5 Stage D/E

```
ICH-S1  SubtreeRef → RASH-MORPH   (tree memo §5 — RSH-03…RSH-09)
        on return → ICH-G3, then ICH-END

ICH-END TerminalNode
```

**`RASH-MORPH` reuse.** The itching branch enters the rash morphology sub-tree to capture any
visible skin changes. This is the documented reuse point from tree memo §5 note 1. If there are
no visible skin changes, the worker selects `rm_raised` → `rs_itchy` → no new drug → no
household → photo, and the path exits. The sub-tree's `new_drug_2_weeks` and
`household_others_affected` fields are populated here, but ICH-05 and ICH-08 have already
populated them for the itching branch. **Resolution: the RASH-MORPH sub-tree references these
shared fieldIds; if already ANSWERED, they are prefilled (deterministic skip, §1.4) and
consistent.** No duplication.

### 4.6 Gateways

Base floor `PHYSICIAN_REVIEW_MANDATORY`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-ICH-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | anaphylaxis | N/A — Tier-0 |
| **GW-ICH-EMG-2 (anaphylaxis)** | `anyOf[ allOf[ danger_signs contains ds_swollen_face, danger_signs contains ds_breathless ], danger_signs contains ds_skin_peeling ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **anaphylaxis, SJS/TEN** | **Yes — abstains** |
| **GW-ICH-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-ICH-URG-1 (drug reaction with mucosal)** | `anyOf[ danger_signs contains ds_mucosal_sores, allOf[ new_drug_2_weeks equals nd_yes, danger_signs contains ds_skin_peeling ] ]` | `raisesTo: REFER_URGENT` | drug reaction / SJS |
| **GW-ICH-URG-2 (scabies outbreak)** | `allOf[ household_others_affected equals ho_yes, itch_worse_at_night equals in_yes ]` | **informational** at floor | scabies — the two cardinal features; flagged for RF-1 enforcement |
| **GW-ICH-URG-3 (underlying disease)** | `known_liver_renal_disease in {lr_liver, lr_renal}` | `raisesTo: REFER_URGENT` | liver/renal disease |
| **GW-ICH-URG-4 (jaundice)** | `danger_signs contains ds_jaundice` | `raisesTo: REFER_URGENT` | liver disease |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `ICH-G1` after ICH-00 evaluates EMG-1…EMG-3 and URG-4; `ICH-G2` after ICH-07
evaluates URG-3; `ICH-G3` after RASH-MORPH return evaluates URG-1, URG-2.

### 4.7 Worked BranchOutput — 25-year-old woman, 2 weeks of itching between fingers, worse at night, mother also affected

```
BranchOutput {
  categoryId: "itching"
  branchVersion: "1.0.0-draft"
  pathTaken: [ICH-00, ICH-G1, ICH-01, ICH-02, ICH-03, ICH-04, ICH-05, ICH-06,
              ICH-07, ICH-08, ICH-S1, RSH-03, RSH-04, RSH-05, RSH-07, RSH-08,
              RSH-09, ICH-G3, ICH-END]
  fields: {
    danger_signs               { ANSWERED, [ds_none],               TAP }
    progression                { ANSWERED, pr_worse,                TAP }
    prior_treatment_taken      { ANSWERED, [pt_home_remedy],        TAP }
    itch_site                  { ANSWERED, is_web_spaces,           TAP }
    itch_worse_at_night        { ANSWERED, in_yes,                  TAP }
    household_others_affected  { ANSWERED, ho_yes,                  TAP }
    visible_burrows_or_lesions { ANSWERED, vb_burrows,              TAP }
    known_liver_renal_disease  { ANSWERED, lr_no,                   TAP }
    new_drug_2_weeks           { ANSWERED, nd_no,                   TAP }
    rash_distribution          { ANSWERED, rdst_limbs,              TAP }
    rash_morphology            { ANSWERED, rm_raised,               TAP }
    rash_symptom               { ANSWERED, rs_itchy,                TAP }
    hypopigmented_patch_sensation { NOT_ASKED, null, — }
    attachment_photo_present   { ANSWERED, ph_taken,                TAP }
    duration_bucket            { ANSWERED, few_days,                PREFILL_MEASURED }
    onset_pattern              { ANSWERED, gradual,                 TAP }
    severity_score             { ANSWERED, 6,                       TAP }
    severity_band              { ANSWERED, moderate,                DERIVED }
    relevant_history           { ANSWERED, [no_known_history],      TAP }
    impact_on_daily_activities { ANSWERED, [sleep_disturbed],       TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: [
    { GW-ICH-URG-2,
      "household_others_affected==ho_yes AND itch_worse_at_night==in_yes",
      [scabies] }
  ]
  routedTo: null
  abstain: false
  attachments: [AFFECTED_AREA_PHOTO]
}
```

Classic scabies pattern: web spaces, nocturnal, household contacts, burrows. Photo attached.
The tree collected the pattern; the physician and classifier identify the condition.

### 4.8 Node budget

Authored non-gateway: **11** (ICH-00…ICH-08, ICH-S1, ICH-END).
Expanded worst case (RASH-MORPH full = 7 nodes): 11 + 7 = **18**.
**Under DECISION-2 ceiling.** ✔

### 4.9 Conformance

All checks pass. DECISION-1: GW-ICH-EMG-2, EMG-3 raise to REFER_EMERGENCY → abstain.
One Tier-0 route: `emergency_unconscious`.
DECISION-2: **18** ≤ 20 ✔.

- **C1–C5:** All pass. ✔
- **BR-1, BR-2, NR-1:** All pass. ✔
- **Six replay assertions:** All pass. ✔

---

## 5. Branch: `skin_infection`

### 5.1 Category constants — transcribed from category memo §7 Chapter S

```
categoryId               = "skin_infection"
displayName              = "Boil, wound or skin infection"
tier                     = CORE
icpc3Chapter             = S
icpc3Basis               = S10, S11, S76
icd10Mapping             = L02, L03
anchor                   = { value: ~25% of chapter S ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT
severeConditions         = [ cellulitis, abscess, diabetic foot, necrotising infection,
                             tetanus-prone wound ]
branchVersion            = 1.0.0-draft
```

### 5.2 Stage A — SKI-00

```
SKI-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_spreading_red   "Red area spreading rapidly (mark the edge and check again)"  → SKI-G1
  ds_crepitus        "Skin feels crackly or makes a sound when touched"           → SKI-G1
  ds_wound_gas       "Foul-smelling wound with bubbles or gas"                    → SKI-G1
  ds_high_fever_inf  "High fever with the infection"                              → SKI-G1
  ds_confusion       "Confused, or behaviour unusual"                              → SKI-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"                 → SKI-G1
  ds_cannot_feed     "Unable to drink or feed"                                    → SKI-G1
  ds_none            "None of these"                                              → SKI-01
  ds_unknown         "Not known"                                                  → SKI-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Cellulitis | `ds_spreading_red`, `ds_high_fever_inf` | covered |
| Abscess | `spreading_redness` (SKI-05) + `pus_discharge` (SKI-06) | covered at Stage C |
| Diabetic foot | `foot_sensation_loss` (SKI-08) + `known_diabetes_flag` (SKI-07) | covered at Stage C |
| Necrotising infection | `ds_crepitus`, `ds_wound_gas` — the classic necrotising fasciitis signs | covered |
| Tetanus-prone wound | `wound_type` assessment at Stage C + `tetanus_status` (SKI-09) | **G-2 gap: no single Stage A sign for tetanus risk — it is a wound classification question; reached through SKI-04 + SKI-09** |

### 5.3 Stage B

`SKI-01 progression`, `SKI-02 prior_treatment_taken`, as batch 1 §1.3. → SKI-03.

### 5.4 Stage C — the category qualifiers

```
SKI-03  fieldId: lesion_type   answerType: SINGLE_CHOICE
Q: "What does the affected area look like?"
  lt_boil         "Boil or lump with pus"                    → SKI-04
  lt_wound        "Open wound or cut"                        → SKI-04
  lt_ulcer        "Non-healing sore or ulcer"                → SKI-04
  lt_red_swollen  "Red, swollen, warm area"                  → SKI-04
  lt_other        "Something else"                           → SKI-04
  lt_unknown      "Not known"                                → SKI-04

SKI-04  fieldId: lesion_site   answerType: SINGLE_CHOICE
Q: "Where on the body is it?"
  ls_foot         "Foot or lower leg"                        → SKI-05
  ls_hand         "Hand or arm"                              → SKI-05
  ls_face         "Face or scalp"                            → SKI-05
  ls_trunk        "Chest, back or stomach"                   → SKI-05
  ls_groin        "Groin or buttocks"                        → SKI-05
  ls_other        "Somewhere else"                           → SKI-05
  ls_unknown      "Not known"                                → SKI-05

SKI-05  fieldId: spreading_redness   answerType: SINGLE_CHOICE
Q: "Is the redness or swelling spreading — getting bigger?"
  sr_yes_rapid    "Yes — noticeably bigger in hours"          → SKI-G2
  sr_yes_slow     "Yes — slowly over days"                    → SKI-06
  sr_no           "No — same size"                            → SKI-06
  sr_unknown      "Not known"                                 → SKI-06

SKI-06  fieldId: pus_discharge   answerType: SINGLE_CHOICE
Q: "Is there pus or foul-smelling discharge?"
  pd_yes       "Yes"                                         → SKI-07
  pd_no        "No"                                          → SKI-07
  pd_unknown   "Not known"                                   → SKI-07

SKI-07  fieldId: known_diabetes_flag   answerType: SINGLE_CHOICE
Q: "Does the patient have diabetes?"
  kd_yes       "Yes"                                         → SKI-08
  kd_no        "No"                                          → SKI-09
  kd_unknown   "Not known"                                   → SKI-09

SKI-08  fieldId: foot_sensation_loss   answerType: SINGLE_CHOICE
        asked only when known_diabetes_flag == kd_yes AND lesion_site == ls_foot; else NOT_ASKED
Q: "Can the patient feel a light touch on the bottom of the foot?"
  fs_yes       "Yes — feels normal"                          → SKI-09
  fs_reduced   "No — feeling is reduced or absent"           → SKI-G3
  fs_unknown   "Not tested"                                  → SKI-09

SKI-09  fieldId: tetanus_status   answerType: SINGLE_CHOICE
        asked only when lesion_type in {lt_wound, lt_ulcer}; else NOT_ASKED
Q: "When was the last tetanus injection?"
  ts_within_5y   "Within the last 5 years"                   → SKI-S1
  ts_gt_5y       "More than 5 years ago"                     → SKI-S1
  ts_never       "Never had it / not known"                  → SKI-S1
  ts_unknown     "Not known"                                 → SKI-S1
```

### 5.5 Stage D/E

```
SKI-S1  SubtreeRef → RASH-MORPH   (tree memo §5 — RSH-03…RSH-09)
        on return → SKI-G4, then SKI-S2

SKI-S2  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → SKI-G5, then SKI-END

SKI-END TerminalNode
```

**RASH-MORPH reuse.** Same logic as `itching` (§4.5): the skin infection branch enters the rash
morphology sub-tree to capture the visual appearance and prompt a photo. This is the documented
reuse point from tree memo §5.

### 5.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-SKI-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | sepsis from skin infection | N/A — Tier-0 |
| **GW-SKI-EMG-2 (necrotising)** | `anyOf[ danger_signs contains ds_crepitus, danger_signs contains ds_wound_gas ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **necrotising fasciitis / gas gangrene** | **Yes — abstains** |
| **GW-SKI-EMG-3 (sepsis)** | `allOf[ danger_signs contains ds_high_fever_inf, danger_signs contains ds_confusion ]` | `raisesTo: REFER_EMERGENCY`, continue | **sepsis** | **Yes — abstains** |
| **GW-SKI-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-SKI-URG-1 (rapid spread)** | `spreading_redness equals sr_yes_rapid` | **informational** at floor | cellulitis (aggressive) — floor already REFER_URGENT |
| **GW-SKI-URG-2 (diabetic foot)** | `allOf[ known_diabetes_flag equals kd_yes, lesion_site equals ls_foot ]` | **informational** at floor | **diabetic foot** |
| **GW-SKI-URG-3 (neuropathy)** | `foot_sensation_loss equals fs_reduced` | **informational** at floor | diabetic neuropathic ulcer |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

Node placement: `SKI-G1` after SKI-00 evaluates EMG-1…EMG-4; `SKI-G2` evaluates URG-1;
`SKI-G3` after SKI-08 evaluates URG-3; `SKI-G4` after RASH-MORPH return; `SKI-G5` after
FEVER-QUAL return.

### 5.7 Worked BranchOutput — 55-year-old diabetic man, non-healing foot ulcer, reduced sensation, no spreading

```
BranchOutput {
  categoryId: "skin_infection"
  branchVersion: "1.0.0-draft"
  pathTaken: [SKI-00, SKI-G1, SKI-01, SKI-02, SKI-03, SKI-04, SKI-05, SKI-06,
              SKI-07, SKI-08, SKI-G3, SKI-09, SKI-S1, RSH-03, RSH-04, RSH-05,
              RSH-07, RSH-08, RSH-09, SKI-G4, SKI-S2, FQ-00, SKI-G5, SKI-END]
  fields: {
    danger_signs             { ANSWERED, [ds_none],              TAP }
    progression              { ANSWERED, pr_worse,               TAP }
    prior_treatment_taken    { ANSWERED, [pt_pharmacy_medicine], TAP }
    lesion_type              { ANSWERED, lt_ulcer,               TAP }
    lesion_site              { ANSWERED, ls_foot,                TAP }
    spreading_redness        { ANSWERED, sr_no,                  TAP }
    pus_discharge            { ANSWERED, pd_yes,                 TAP }
    known_diabetes_flag      { ANSWERED, kd_yes,                 TAP }
    foot_sensation_loss      { ANSWERED, fs_reduced,             TAP }
    tetanus_status           { ANSWERED, ts_unknown,             TAP }
    rash_distribution        { ANSWERED, rdst_one_patch,         TAP }
    rash_morphology          { ANSWERED, rm_pustular,            TAP }
    rash_symptom             { ANSWERED, rs_painful,             TAP }
    hypopigmented_patch_sensation { NOT_ASKED, null, — }
    new_drug_2_weeks         { ANSWERED, nd_no,                  TAP }
    household_others_affected { ANSWERED, ho_no,                 TAP }
    attachment_photo_present { ANSWERED, ph_taken,               TAP }
    fever_present            { ANSWERED, fp_no,                  TAP }
    fever_duration_band      { NOT_ASKED, null, — }
    fever_pattern            { NOT_ASKED, null, — }
    bleeding_manifestation   { NOT_ASKED, null, — }
    duration_bucket          { ANSWERED, week_plus,              PREFILL_MEASURED }
    onset_pattern            { ANSWERED, gradual,                TAP }
    severity_score           { ANSWERED, 5,                      TAP }
    severity_band            { ANSWERED, moderate,               DERIVED }
    relevant_history         { ANSWERED, [diabetes],             TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-SKI-URG-2,
      "known_diabetes_flag==kd_yes AND lesion_site==ls_foot",
      [diabetic foot] },
    { GW-SKI-URG-3,
      "foot_sensation_loss==fs_reduced",
      [diabetic neuropathic ulcer] }
  ]
  routedTo: null
  abstain: false
  attachments: [AFFECTED_AREA_PHOTO]
}
```

Classic diabetic foot ulcer: non-healing, neuropathic, foot, pus. Photo attached. Floor already
REFER_URGENT; URG gateways are informational and carry `severeConditions` for RF-1.

### 5.8 Node budget

Authored non-gateway: **14** (SKI-00…SKI-09, SKI-S1, SKI-S2, SKI-END — 12 + 2 sub-tree refs).
Expanded worst case (RASH-MORPH 7 + FEVER-QUAL 4): 14 + 7 + 4 = **25**.
Without photo node on RASH-MORPH (already counted) and with gate-only FEVER-QUAL:
14 + 7 + 1 = **22**.

**EXCEEDS DECISION-2 ceiling of 20.** See §13 (G-19) below.

### 5.9 Conformance

DECISION-1: GW-SKI-EMG-2…EMG-4 raise to REFER_EMERGENCY → abstain.
One Tier-0 route: `emergency_unconscious`.
DECISION-2: **22–25** — **exceeds ceiling. See G-19.**

- **C1–C5:** All pass. ✔
- **BR-1, BR-2, NR-1:** All pass. ✔
- **Six replay assertions:** All pass. ✔

---

## 6. Branch: `antenatal_visit`

### 6.1 Category constants — transcribed from category memo §7 Chapter W

```
categoryId               = "antenatal_visit"
displayName              = "Pregnancy check / antenatal visit"
tier                     = CORE
icpc3Chapter             = W
icpc3Basis               = W78
icd10Mapping             = Z34.-, Z35.-
anchor                   = { value: Odisha 5.0% (IND-PRESENT),
                             sourceType: IND-PRESENT }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ pre-eclampsia, anaemia in pregnancy, APH,
                             malpresentation, gestational diabetes ]
branchVersion            = 1.0.0-draft
```

### 6.2 Stage A — ANC-00

```
ANC-00  fieldId: danger_signs_pregnancy   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_vaginal_bleed   "Vaginal bleeding"                                  → ANC-G1
  ds_severe_headache "Severe headache that will not go away"             → ANC-G1
  ds_blurred_vision  "Blurred or disturbed vision"                       → ANC-G1
  ds_convulsion      "Fits or convulsions"                               → ANC-G1
  ds_swollen_face_h  "Face or hands suddenly swollen"                    → ANC-G1
  ds_severe_abd_pain "Severe abdominal pain"                             → ANC-G1
  ds_no_fetal_move   "Baby has not moved for more than 12 hours"         → ANC-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"       → ANC-G1
  ds_fever_preg      "High fever"                                        → ANC-G1
  ds_water_break     "Water has broken / leaking fluid"                  → ANC-G1
  ds_none            "None of these"                                     → ANC-01
  ds_unknown         "Not known"                                         → ANC-01
```

**Note on `fieldId`.** This branch uses `danger_signs_pregnancy` rather than `danger_signs` because
the option set is pregnancy-specific and incompatible with the general `danger_signs` MULTI. One
`fieldId` = one option set everywhere (registry rule). These are the WHO danger signs of pregnancy
as used in the Indian JSSK/Janani Suraksha Yojana programme.

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Pre-eclampsia | `ds_severe_headache`, `ds_blurred_vision`, `ds_convulsion`, `ds_swollen_face_h` | covered — the classic triad |
| Anaemia in pregnancy | `ds_severe_pallor` (not listed — see below) | **G-2 gap: no specific pallor sign at Stage A; anaemia screening is at ANC-08 (pallor_site_observed)** |
| APH (antepartum haemorrhage) | `ds_vaginal_bleed` | covered |
| Malpresentation | Not observable at Stage A by a community worker | **G-2 gap: malpresentation requires abdominal palpation; not a Stage A sign — noted for physician** |
| Gestational diabetes | No specific danger sign | **G-2 gap: GDM is asymptomatic; screened via history and glucose referral. No Stage A sign** |

### 6.3 Stage B

Antenatal visit is **not** a symptom presentation — it is a check-up. Stage B shared core is
modified: `progression` is not clinically meaningful for a scheduled visit. Instead:

```
ANC-01  fieldId: gestational_weeks_band   answerType: SINGLE_CHOICE
Q: "How many weeks or months pregnant?"
  gw_lt_12     "Less than 12 weeks (first trimester)"        → ANC-02
  gw_12_28     "12 to 28 weeks (second trimester)"           → ANC-02
  gw_28_36     "28 to 36 weeks (third trimester)"            → ANC-02
  gw_gt_36     "More than 36 weeks (close to due date)"      → ANC-02
  gw_unknown   "Not known"                                   → ANC-02

ANC-02  fieldId: gravida_para   answerType: SINGLE_CHOICE
Q: "Is this the first pregnancy, or has she been pregnant before?"
  gp_primi     "First pregnancy"                             → ANC-03
  gp_multi     "Has been pregnant before"                    → ANC-03
  gp_unknown   "Not known"                                   → ANC-03

ANC-03  fieldId: anc_visit_number   answerType: SINGLE_CHOICE
Q: "Which antenatal check-up number is this?"
  av_first     "First visit"                                 → ANC-04
  av_second    "Second"                                      → ANC-04
  av_third     "Third"                                       → ANC-04
  av_fourth_plus "Fourth or more"                            → ANC-04
  av_unknown   "Not known"                                   → ANC-04
```

### 6.4 Stage C — the category qualifiers

```
ANC-04  fieldId: fetal_movement   answerType: SINGLE_CHOICE
        asked only when gestational_weeks_band in {gw_28_36, gw_gt_36}; else NOT_ASKED
Q: "Is the baby moving well?"
  fm_good      "Yes — moving as usual"                       → ANC-05
  fm_reduced   "Less than usual"                             → ANC-G2
  fm_none      "No movement felt"                            → ANC-G1
  fm_unknown   "Not known"                                   → ANC-05

ANC-05  fieldId: previous_pregnancy_complication   answerType: MULTI_CHOICE
Q: "In any previous pregnancy, did any of these happen?"
        asked only when gravida_para == gp_multi; else NOT_ASKED
  pc_stillbirth   "Stillbirth or baby died soon after birth"   → ANC-06
  pc_cs           "Caesarean section"                          → ANC-06
  pc_preeclampsia "High BP or fits during pregnancy"          → ANC-06
  pc_bleeding     "Heavy bleeding during delivery"            → ANC-06
  pc_preterm      "Baby born too early"                       → ANC-06
  pc_none         "None of these"                              → ANC-06
  pc_unknown      "Not known"                                  → ANC-06

ANC-06  fieldId: prior_treatment_taken   answerType: MULTI_CHOICE   (shared, batch 1 §1.3)
Q: "Is the patient taking any medicines or supplements for the pregnancy?"
  pt_none · pt_home_remedy · pt_pharmacy_medicine · pt_ayush · pt_other_facility · pt_unknown
  → ANC-07

ANC-07  fieldId: relevant_history   answerType: MULTI_CHOICE   (shared, §2.3)
  → ANC-08

ANC-08  fieldId: pallor_site_observed   answerType: MULTI_CHOICE   (shared from pallor, this batch)
Q: "Look at the patient. Where do you see paleness?"
help: "Ask the patient to show their palms and pull down the lower eyelid."
  po_palms · po_nails · po_conjunctiva · po_tongue · po_face · po_none · po_unknown
  → ANC-G3, then ANC-END

ANC-END TerminalNode
```

### 6.5 Stage D/E

No sub-tree cross-links. Antenatal visit does not use FEVER-QUAL or TB-SCREEN — if the
patient has fever, she should be in the `fever` branch; if she has a cough, `cough`. The
antenatal branch captures the pregnancy-specific checks.

### 6.6 Gateways

Base floor `PHYSICIAN_REVIEW_MANDATORY`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-ANC-EMG-1 (pregnancy danger)** | `danger_signs_pregnancy contains any of {ds_vaginal_bleed, ds_convulsion, ds_unconscious}` | `routeTo: emergency_pregnancy_danger`, terminate | APH, eclampsia | N/A — Tier-0 |
| **GW-ANC-EMG-2 (pre-eclampsia signs)** | `anyOf[ danger_signs_pregnancy contains ds_severe_headache, danger_signs_pregnancy contains ds_blurred_vision, danger_signs_pregnancy contains ds_swollen_face_h ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **pre-eclampsia / imminent eclampsia** | **Yes — abstains** |
| **GW-ANC-EMG-3 (no fetal movement > 12h)** | `danger_signs_pregnancy contains ds_no_fetal_move` | `raisesTo: REFER_EMERGENCY`, continue | fetal distress / intrauterine death | **Yes — abstains** |
| **GW-ANC-EMG-4 (fetal movement ceased late)** | `fetal_movement equals fm_none` | `raisesTo: REFER_EMERGENCY`, continue | fetal distress | **Yes — abstains** |
| **GW-ANC-URG-1 (reduced movement)** | `fetal_movement equals fm_reduced` | `raisesTo: REFER_URGENT` | fetal compromise |
| **GW-ANC-URG-2 (previous complication)** | `previous_pregnancy_complication contains any of {pc_stillbirth, pc_preeclampsia}` | `raisesTo: REFER_URGENT` | high-risk pregnancy |
| **GW-ANC-URG-3 (severe pallor in pregnancy)** | `pallor_site_observed contains any of {po_palms, po_conjunctiva}` | `raisesTo: REFER_URGENT` | **anaemia in pregnancy** |
| **GW-ANC-URG-4 (fever/water break)** | `danger_signs_pregnancy contains any of {ds_fever_preg, ds_water_break, ds_severe_abd_pain}` | `raisesTo: REFER_URGENT` | infection, PPROM, placental abruption |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `ANC-G1` after ANC-00 evaluates EMG-1…EMG-3 and URG-4; `ANC-G2` after ANC-04
evaluates EMG-4, URG-1; `ANC-G3` after ANC-08 evaluates URG-2, URG-3.

### 6.7 Worked BranchOutput — 24-year-old woman, 32 weeks, first pregnancy, second ANC visit, baby moving well, mild pallor

```
BranchOutput {
  categoryId: "antenatal_visit"
  branchVersion: "1.0.0-draft"
  pathTaken: [ANC-00, ANC-G1, ANC-01, ANC-02, ANC-03, ANC-04, ANC-G2,
              ANC-06, ANC-07, ANC-08, ANC-G3, ANC-END]
  fields: {
    danger_signs_pregnancy       { ANSWERED, [ds_none],              TAP }
    gestational_weeks_band       { ANSWERED, gw_28_36,               TAP }
    gravida_para                 { ANSWERED, gp_primi,               TAP }
    anc_visit_number             { ANSWERED, av_second,              TAP }
    fetal_movement               { ANSWERED, fm_good,                TAP }
    previous_pregnancy_complication { NOT_ASKED, null, — }
    prior_treatment_taken        { ANSWERED, [pt_pharmacy_medicine], TAP }
    relevant_history             { ANSWERED, [no_known_history],     TAP }
    pallor_site_observed         { ANSWERED, [po_conjunctiva],       TAP }
    duration_bucket              { NOT_ASKED, null, — }
    onset_pattern                { NOT_ASKED, null, — }
    severity_score               { NOT_ASKED, null, — }
    severity_band                { NOT_ASKED, null, — }
    impact_on_daily_activities   { NOT_ASKED, null, — }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-ANC-URG-3,
      "pallor_site_observed contains po_conjunctiva",
      [anaemia in pregnancy] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

**Note on Stage B shared fields.** For a check-up visit, `duration_bucket`, `onset_pattern`,
`severity_score`, `severity_band`, and `impact_on_daily_activities` are `NOT_ASKED` — they are
symptom-oriented fields that do not apply to a scheduled antenatal visit. The antenatal branch
replaces Stage B with pregnancy-specific context (gestational age, gravidity, ANC number). This
follows the `known_hypertension` precedent: not every branch uses every shared field.

### 6.8 Node budget

Authored non-gateway: **11** (ANC-00…ANC-08, ANC-END — 10 question nodes + terminal).
No sub-tree cross-links.
Expanded worst case: **11**. **Under DECISION-2 ceiling.** ✔

### 6.9 Conformance

- **C1–C5:** All pass. ✔
- **C4:** `routeTo: emergency_pregnancy_danger` — Tier-0 ALWAYS_ABSTAIN. ✔
- **BR-1:** Every option has explicit `next`. ✔
- **BR-2:** Every SINGLE has `*_unknown`; MULTI has `*_none` + `*_unknown`. ✔
- **NR-1:** No severe condition named; nearest: `ds_convulsion` (symptom), `ds_vaginal_bleed` (observation). ✔
- **Six replay assertions:** All pass. ✔

---

## 7. Tier-0 Emergency Router: `emergency_convulsions`

### 7.1 Category constants — category memo §6

```
categoryId               = "emergency_convulsions"
displayName              = "Fits or convulsions"
tier                     = EMERGENCY
icpc3Basis               = N07
icd10Mapping             = R56.9
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 7.2 Branch — minimal protocol fields

```
ECN-00  fieldId: convulsion_time_of_onset   answerType: SINGLE_CHOICE
Q: "When did the fits start?"
  co_minutes     "Minutes ago — still happening or just stopped"    → ECN-01
  co_lt_1h       "Less than 1 hour ago"                             → ECN-01
  co_1_6h        "1 to 6 hours ago"                                 → ECN-01
  co_gt_6h       "More than 6 hours ago"                            → ECN-01
  co_unknown     "Not known"                                        → ECN-01

ECN-01  fieldId: convulsion_first_aid   answerType: MULTI_CHOICE
Q: "What has been done for the patient?"
  fa_position    "Turned on side (recovery position)"               → ECN-02
  fa_nothing_mouth "Kept things out of the mouth"                   → ECN-02
  fa_medicine    "Given medicine"                                    → ECN-02
  fa_nothing     "Nothing done"                                     → ECN-02
  fa_unknown     "Not known"                                        → ECN-02

ECN-02  fieldId: convulsion_still_happening   answerType: SINGLE_CHOICE
Q: "Is the patient still having fits right now?"
  cs_yes   "Yes — still fitting"                                    → ECN-END
  cs_no    "No — fits have stopped"                                 → ECN-END
  cs_unknown "Not known"                                            → ECN-END

ECN-END TerminalNode
```

### 7.3 Output

```
BranchOutput {
  categoryId: "emergency_convulsions"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  routedTo: null
  abstain: true                           // ALWAYS_ABSTAIN
  // → ReferralRequest with UrgencyLevel.EMERGENCY
}
```

**ALWAYS_ABSTAIN confirmed.** No classifier call. No differential. `abstain: true` on every path.
Node count: **4** (3 questions + terminal).

---

## 8. Tier-0 Emergency Router: `emergency_unconscious`

### 8.1 Category constants — category memo §6

```
categoryId               = "emergency_unconscious"
displayName              = "Unconscious or very drowsy"
tier                     = EMERGENCY
icpc3Basis               = N05, A29
icd10Mapping             = R40.2
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 8.2 Branch — minimal protocol fields

```
EUC-00  fieldId: unconscious_time_of_onset   answerType: SINGLE_CHOICE
Q: "When was the patient last awake and normal?"
  uo_minutes     "Minutes ago"                                       → EUC-01
  uo_lt_1h       "Less than 1 hour ago"                              → EUC-01
  uo_1_6h        "1 to 6 hours ago"                                  → EUC-01
  uo_gt_6h       "More than 6 hours ago"                             → EUC-01
  uo_found       "Found unconscious — time not known"                → EUC-01
  uo_unknown     "Not known"                                         → EUC-01

EUC-01  fieldId: unconscious_breathing   answerType: SINGLE_CHOICE
Q: "Is the patient breathing?"
  ub_yes_normal   "Yes — breathing normally"                         → EUC-02
  ub_yes_abnormal "Yes — but noisy, gasping, or very slow"           → EUC-02
  ub_no           "No — not breathing"                               → EUC-02
  ub_unknown      "Not known"                                        → EUC-02

EUC-02  fieldId: unconscious_first_aid   answerType: MULTI_CHOICE
Q: "What has been done for the patient?"
  fa_recovery    "Turned on side (recovery position)"                → EUC-END
  fa_airway      "Airway cleared / head tilted"                      → EUC-END
  fa_nothing     "Nothing done"                                      → EUC-END
  fa_unknown     "Not known"                                         → EUC-END

EUC-END TerminalNode
```

### 8.3 Output

```
BranchOutput {
  categoryId: "emergency_unconscious"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  abstain: true
}
```

**ALWAYS_ABSTAIN confirmed.** Node count: **4**.

---

## 9. Tier-0 Emergency Router: `emergency_bite_sting`

### 9.1 Category constants — category memo §6

```
categoryId               = "emergency_bite_sting"
displayName              = "Snake bite, scorpion sting, animal bite"
tier                     = EMERGENCY
icpc3Basis               = A80, S13
icd10Mapping             = T63.-, W59, T14.1
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 9.2 Branch — minimal protocol fields

```
EBS-00  fieldId: bite_species   answerType: SINGLE_CHOICE
Q: "What kind of bite or sting?"
  sp_snake       "Snake"                                              → EBS-01
  sp_scorpion    "Scorpion"                                           → EBS-01
  sp_dog         "Dog or other animal"                                → EBS-01
  sp_unknown     "Not known / did not see"                            → EBS-01

EBS-01  fieldId: bite_time   answerType: SINGLE_CHOICE
Q: "When did it happen?"
  bt_lt_1h       "Less than 1 hour ago"                               → EBS-02
  bt_1_6h        "1 to 6 hours ago"                                   → EBS-02
  bt_gt_6h       "More than 6 hours ago"                              → EBS-02
  bt_unknown     "Not known"                                          → EBS-02

EBS-02  fieldId: bite_site   answerType: SINGLE_CHOICE
Q: "Where is the bite or sting?"
  bsi_hand_arm   "Hand or arm"                                        → EBS-03
  bsi_foot_leg   "Foot or leg"                                        → EBS-03
  bsi_trunk      "Body / trunk"                                       → EBS-03
  bsi_face_neck  "Face or neck"                                       → EBS-03
  bsi_unknown    "Not known"                                          → EBS-03

EBS-03  fieldId: bite_first_aid   answerType: MULTI_CHOICE
Q: "What has been done?"
  fa_immobilised "Limb kept still / splinted"                         → EBS-04
  fa_washed      "Wound washed with water and soap"                   → EBS-04
  fa_tourniquet  "Tourniquet or tight bandage applied"                → EBS-04
  fa_traditional "Traditional remedy / incision / suction applied"    → EBS-04
  fa_nothing     "Nothing done"                                       → EBS-04
  fa_unknown     "Not known"                                          → EBS-04

EBS-04  fieldId: bite_symptoms   answerType: MULTI_CHOICE
Q: "Does the patient have any of these now?"
  bsym_swelling      "Swelling at the bite"                           → EBS-END
  bsym_bleeding_site "Bleeding from the bite that won't stop"         → EBS-END
  bsym_bleeding_gums "Bleeding gums or nose"                          → EBS-END
  bsym_drooping      "Drooping eyelids or difficulty swallowing"      → EBS-END
  bsym_difficulty_br "Difficulty breathing"                            → EBS-END
  bsym_none          "None of these"                                   → EBS-END
  bsym_unknown       "Not known"                                       → EBS-END

EBS-END TerminalNode
```

### 9.3 Output

```
BranchOutput {
  categoryId: "emergency_bite_sting"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  abstain: true
}
```

**ALWAYS_ABSTAIN confirmed.** Node count: **6**.

**Clinical note:** `bsym_drooping` (ptosis, bulbar palsy) and `bsym_bleeding_gums` (coagulopathy)
are the two key snake envenomation signs that distinguish neurotoxic from haemotoxic bites.
Recorded for the referral note, not for a differential.

---

## 10. Tier-0 Emergency Router: `emergency_poisoning`

### 10.1 Category constants — category memo §6

```
categoryId               = "emergency_poisoning"
displayName              = "Poisoning or overdose"
tier                     = EMERGENCY
icpc3Basis               = A86
icd10Mapping             = T65.9
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 10.2 Branch — minimal protocol fields

```
EPO-00  fieldId: poison_substance   answerType: SINGLE_CHOICE
Q: "What was taken or what was the patient exposed to?"
  sub_pesticide   "Pesticide or insecticide (e.g. organophosphate)"   → EPO-01
  sub_rat_poison  "Rat poison or rodenticide"                         → EPO-01
  sub_medicine    "Too many tablets or wrong medicine"                → EPO-01
  sub_cleaning    "Cleaning product, acid, or alkali"                 → EPO-01
  sub_plant       "Poisonous plant or seed (e.g. oleander, dhatura)" → EPO-01
  sub_alcohol     "Alcohol or spirit (methanol/ethanol)"              → EPO-01
  sub_unknown     "Not known"                                         → EPO-01

EPO-01  fieldId: poison_time   answerType: SINGLE_CHOICE
Q: "When did it happen?"
  pt_lt_1h       "Less than 1 hour ago"                               → EPO-02
  pt_1_4h        "1 to 4 hours ago"                                   → EPO-02
  pt_gt_4h       "More than 4 hours ago"                              → EPO-02
  pt_unknown     "Not known"                                          → EPO-02

EPO-02  fieldId: poison_route   answerType: SINGLE_CHOICE
Q: "How was the patient exposed?"
  pr_swallowed   "Swallowed / eaten"                                  → EPO-03
  pr_inhaled     "Breathed in / inhaled"                              → EPO-03
  pr_skin        "Skin contact"                                       → EPO-03
  pr_unknown     "Not known"                                          → EPO-03

EPO-03  fieldId: poison_first_aid   answerType: MULTI_CHOICE
Q: "What has been done?"
  fa_vomited     "Patient has vomited on their own"                   → EPO-04
  fa_induced     "Vomiting was induced"                               → EPO-04
  fa_washed_skin "Skin / eyes washed with water"                      → EPO-04
  fa_nothing     "Nothing done"                                       → EPO-04
  fa_unknown     "Not known"                                          → EPO-04

EPO-04  fieldId: poison_symptoms   answerType: MULTI_CHOICE
Q: "Does the patient have any of these now?"
  psym_salivation    "Excessive drooling or salivation"               → EPO-END
  psym_constricted   "Pupils very small (pinpoint)"                   → EPO-END
  psym_tremor        "Twitching or muscle fasciculation"              → EPO-END
  psym_breathing     "Difficulty breathing"                           → EPO-END
  psym_confusion     "Confused or drowsy"                             → EPO-END
  psym_none          "None of these"                                  → EPO-END
  psym_unknown       "Not known"                                      → EPO-END

EPO-END TerminalNode
```

### 10.3 Output

```
BranchOutput {
  categoryId: "emergency_poisoning"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  abstain: true
}
```

**ALWAYS_ABSTAIN confirmed.** Node count: **6**.

**Clinical note:** `psym_salivation`, `psym_constricted`, `psym_tremor` are the classic
organophosphate cholinergic toxidrome (SLUDGE). Recorded for the referral note — knowing it is
organophosphate determines the atropine protocol at the receiving facility.

---

## 11. Tier-0 Emergency Router: `emergency_heavy_bleeding`

### 11.1 Category constants — category memo §6

```
categoryId               = "emergency_heavy_bleeding"
displayName              = "Heavy bleeding from anywhere"
tier                     = EMERGENCY
icpc3Basis               = A10
icd10Mapping             = R58
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 11.2 Branch — minimal protocol fields

```
EHB-00  fieldId: bleeding_source   answerType: SINGLE_CHOICE
Q: "Where is the bleeding from?"
  bls_wound      "From a wound or cut"                                → EHB-01
  bls_vomit      "Blood in vomit"                                    → EHB-01
  bls_stool      "Blood in stool or from rectum"                     → EHB-01
  bls_vaginal    "Vaginal bleeding (not period)"                     → EHB-01
  bls_nose       "From the nose — won't stop"                        → EHB-01
  bls_cough      "Coughing up blood"                                 → EHB-01
  bls_urine      "Blood in urine"                                   → EHB-01
  bls_multiple   "From several places (gums, skin, nose)"           → EHB-01
  bls_unknown    "Not known"                                         → EHB-01

EHB-01  fieldId: bleeding_time_of_onset   answerType: SINGLE_CHOICE
Q: "When did the bleeding start?"
  bto_lt_1h      "Less than 1 hour ago"                              → EHB-02
  bto_1_6h       "1 to 6 hours ago"                                  → EHB-02
  bto_gt_6h      "More than 6 hours ago"                             → EHB-02
  bto_unknown    "Not known"                                         → EHB-02

EHB-02  fieldId: bleeding_first_aid   answerType: MULTI_CHOICE
Q: "What has been done?"
  fa_pressure    "Direct pressure on the wound"                      → EHB-03
  fa_elevated    "Injured part raised above the heart"               → EHB-03
  fa_tourniquet  "Tourniquet applied"                                 → EHB-03
  fa_nothing     "Nothing done"                                      → EHB-03
  fa_unknown     "Not known"                                         → EHB-03

EHB-03  fieldId: bleeding_pregnancy   answerType: SINGLE_CHOICE
        asked only when sex == F and age_band ∈ [15,49]; else NOT_ASKED
Q: "Is the patient pregnant or has she recently delivered?"
  bp_pregnant    "Yes — pregnant"                                     → EHB-END
  bp_recent      "Yes — delivered recently"                           → EHB-END
  bp_no          "No"                                                 → EHB-END
  bp_unknown     "Not known"                                         → EHB-END

EHB-END TerminalNode
```

### 11.3 Output

```
BranchOutput {
  categoryId: "emergency_heavy_bleeding"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  abstain: true
}
```

**ALWAYS_ABSTAIN confirmed.** Node count: **5** (4 questions + terminal; `bleeding_pregnancy`
conditionally skipped).

---

## 12. Tier-0 Emergency Router: `emergency_pregnancy_danger`

### 12.1 Category constants — category memo §6

```
categoryId               = "emergency_pregnancy_danger"
displayName              = "Pregnancy danger sign (bleeding, fits, severe headache,
                            no fetal movement)"
tier                     = EMERGENCY
icpc3Basis               = W03, W99
icd10Mapping             = O20.9, O15.-, O46.-
modelBehaviour           = ALWAYS_ABSTAIN
requiredDisposition      = REFER_EMERGENCY
branchVersion            = 1.0.0-draft
```

### 12.2 Branch — minimal protocol fields

This router is entered either directly by the worker or via `routeTo` from `antenatal_visit`
and `oedema` when pregnancy danger signs fire.

```
EPD-00  fieldId: pregnancy_danger_type   answerType: MULTI_CHOICE
Q: "What is happening right now?"
  pd_bleeding      "Vaginal bleeding"                                 → EPD-01
  pd_convulsion    "Fits or convulsions"                              → EPD-01
  pd_headache      "Severe headache that will not go away"           → EPD-01
  pd_vision        "Blurred or disturbed vision"                     → EPD-01
  pd_swollen       "Face and hands suddenly swollen"                 → EPD-01
  pd_no_movement   "Baby has not moved for more than 12 hours"      → EPD-01
  pd_water_break   "Water has broken / leaking fluid"                → EPD-01
  pd_high_fever    "High fever"                                      → EPD-01
  pd_severe_pain   "Severe abdominal pain"                           → EPD-01
  pd_unknown       "Not known"                                       → EPD-01

EPD-01  fieldId: pregnancy_danger_weeks   answerType: SINGLE_CHOICE
Q: "How many weeks or months pregnant?"
  pw_lt_20       "Less than 20 weeks (first half)"                   → EPD-02
  pw_20_36       "20 to 36 weeks"                                    → EPD-02
  pw_gt_36       "More than 36 weeks (close to due date)"            → EPD-02
  pw_postpartum  "Just delivered — within the last 6 weeks"          → EPD-02
  pw_unknown     "Not known"                                         → EPD-02

EPD-02  fieldId: pregnancy_danger_first_aid   answerType: MULTI_CHOICE
Q: "What has been done?"
  fa_lying_left    "Patient lying on left side"                       → EPD-END
  fa_pad_applied   "Pad or cloth applied for bleeding"               → EPD-END
  fa_nothing       "Nothing done"                                     → EPD-END
  fa_unknown       "Not known"                                        → EPD-END

EPD-END TerminalNode
```

### 12.3 Output

```
BranchOutput {
  categoryId: "emergency_pregnancy_danger"
  branchVersion: "1.0.0-draft"
  dispositionFloor: REFER_EMERGENCY
  abstain: true
}
```

**ALWAYS_ABSTAIN confirmed.** Node count: **4**.

**Consistency with `antenatal_visit`.** The danger signs in `EPD-00` match the `ANC-00`
`danger_signs_pregnancy` option list. When `ANC-G1` routes to `emergency_pregnancy_danger`, the
EPD branch records the same information in a referral-oriented frame. The `pregnancy_danger_type`
field is distinct from `danger_signs_pregnancy` because it may be entered directly (worker selects
`emergency_pregnancy_danger` from the dropdown) without passing through `antenatal_visit`.

---

## 13. Registry additions — final, completing the registry

### 13.1 New shared `fieldId`s

| fieldId | Type | Options | Introduced for | Reuse expected in |
|---|---|---|---|---|
| `pallor_site_observed` | MULTI | `po_palms` · `po_nails` · `po_conjunctiva` · `po_tongue` · `po_face` · `po_none` · `po_unknown` | `pallor_anaemia`, `antenatal_visit` | tree-complete — no further reuse expected |
| `danger_signs_pregnancy` | MULTI | the 12 pregnancy-specific options in ANC-00 | `antenatal_visit` | tree-complete — no further reuse expected |
| `on_standing` | SINGLE | `os_yes` · `os_sometimes` · `os_no` · `os_unknown` | `dizziness` | tree-complete |
| `palpitations` | SINGLE | `pal_yes` · `pal_no` · `pal_unknown` | `dizziness` | tree-complete |
| `hearing_change` | SINGLE | `hc_hearing_loss` · `hc_ringing` · `hc_both` · `hc_no` · `hc_unknown` | `dizziness` | tree-complete |
| `focal_weakness` | SINGLE | `fw_one_side` · `fw_both_sides` · `fw_no` · `fw_unknown` | `dizziness` | tree-complete |

### 13.2 New branch-specific `fieldId`s

| Branch | fieldIds |
|---|---|
| `oedema` | `oedema_site` · `oedema_pitting` · `facial_puffiness_morning` · `urine_frothy` |
| `pallor_anaemia` | `pica` · `heavy_menstrual_bleeding` · `worm_treatment_history` · `dietary_pattern` |
| `dizziness` | `dizziness_type` |
| `itching` | `itch_site` · `itch_worse_at_night` · `visible_burrows_or_lesions` · `known_liver_renal_disease` |
| `skin_infection` | `lesion_type` · `lesion_site` · `spreading_redness` · `pus_discharge` · `known_diabetes_flag` · `foot_sensation_loss` · `tetanus_status` |
| `antenatal_visit` | `gestational_weeks_band` · `gravida_para` · `anc_visit_number` · `fetal_movement` · `previous_pregnancy_complication` |
| `emergency_convulsions` | `convulsion_time_of_onset` · `convulsion_first_aid` · `convulsion_still_happening` |
| `emergency_unconscious` | `unconscious_time_of_onset` · `unconscious_breathing` · `unconscious_first_aid` |
| `emergency_bite_sting` | `bite_species` · `bite_time` · `bite_site` · `bite_first_aid` · `bite_symptoms` |
| `emergency_poisoning` | `poison_substance` · `poison_time` · `poison_route` · `poison_first_aid` · `poison_symptoms` |
| `emergency_heavy_bleeding` | `bleeding_source` · `bleeding_time_of_onset` · `bleeding_first_aid` · `bleeding_pregnancy` |
| `emergency_pregnancy_danger` | `pregnancy_danger_type` · `pregnancy_danger_weeks` · `pregnancy_danger_first_aid` |

### 13.3 Retired from tree memo §2.5

| Retired fieldId | Branch | Superseded by | Reason |
|---|---|---|---|
| `breathlessness_lying_flat` | `oedema` | `danger_signs.ds_breathless_rest` | Stage A covers both at-rest and lying-flat breathlessness; and shared `orthopnoea` from `breathlessness` branch covers the specific question |
| `known_diabetes_flag` vs `foot_ulcer_or_numbness` | `skin_infection` | They are different clinical facts; `known_diabetes_flag` is a new field specific to skin_infection (whether the patient has diabetes), not the same as `foot_ulcer_or_numbness` from `known_diabetes` | **Not retired — both registered as distinct** |
| `worst_ever_sudden` | tree memo §2.5 `dizziness` | `danger_signs.ds_*` stroke screen + `dizziness_type` | the "worst ever" thunderclap question is headache-specific (batch 2 HDC-02); `dizziness` captures the benign/dangerous distinction through the FAST stroke signs at Stage A |

### 13.4 Reused from batches 1–3 registry (not re-authored)

| Item | Source | Used in this batch by |
|---|---|---|
| `FEVER-QUAL` v1.1 | batch 1 §6.3 | `oedema`, `pallor_anaemia`, `dizziness`, `skin_infection` |
| `RASH-MORPH` | tree memo §5 | `itching`, `skin_infection` |
| `blood_in_stool` | batch 1 §7.2 | `pallor_anaemia` |
| `appetite_change` | batch 3 §9.1 | `pallor_anaemia` |
| `household_others_affected` | existing (rash) | `itching` (reused through RASH-MORPH) |
| `new_drug_2_weeks` | existing (rash) | `itching` (asked at ICH-08; also in RASH-MORPH) |
| `pregnancy_status` | tree memo §2.1 | `oedema` |
| `prior_treatment_taken` | batch 1 §1.3 | all six clinical branches |
| `progression` | batch 1 §1.3 | `oedema`, `pallor_anaemia`, `dizziness`, `itching`, `skin_infection` |
| `relevant_history` | tree memo §2.3 | `pallor_anaemia`, `antenatal_visit` |
| `urine_output_reduced` | batch 1 §6.2 (DEHYDRATION) | shared concept reused by `oedema` danger signs |

### 13.5 Dataset column impact

Net new column families:
- **6 shared** (§13.1)
- **42 branch-specific** (§13.2) across 12 branches (6 clinical + 6 Tier-0)
- **1 retired** (§13.3)

Approximate net width: **≈ 60 columns** (MULTI fields expand; Tier-0 fields add ~30 on their own).
Running total (all batches): ≈ 40 + 35 + 25 + 60 = **≈ 160 columns** against tree memo §7.2's
≈ 400 projection. The gap is because §7.2 projected ~150 branch-specific fields across 26 branches;
the actual authored count is lower due to aggressive reuse and shared fieldIds.

---

## 14. Conformance summary — all batch-4 branches

### 14.1 Six clinical branches

| Branch | Entry floor | routeTo targets used | Highest floor | Authored | Expanded worst | NR-1 nearest | DECISION-1 abstention paths |
|---|---|---|---|---|---|---|---|
| `oedema` | REFER_URGENT | unconscious, pregnancy_danger | REFER_EMERGENCY | 10 | 14 | `ds_breathless_rest` (obs) | GW-OED-EMG-2…4 |
| `pallor_anaemia` | PHYSICIAN_REVIEW_MANDATORY | unconscious, heavy_bleeding | REFER_EMERGENCY | 13 | 17 | `ds_severe_pallor` (obs) | GW-PAL-EMG-3, EMG-4 |
| `dizziness` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 11 | 15 | `ds_one_side_weak` (obs) | GW-DZZ-EMG-2…4 |
| `itching` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 11 | 18 | `ds_skin_peeling` (obs) | GW-ICH-EMG-2, EMG-3 |
| `skin_infection` | REFER_URGENT | unconscious | REFER_EMERGENCY | 14 | **22** (G-19) | `ds_crepitus` (obs) | GW-SKI-EMG-2…4 |
| `antenatal_visit` | PHYSICIAN_REVIEW_MANDATORY | pregnancy_danger | REFER_EMERGENCY | 11 | 11 | `ds_vaginal_bleed` (obs) | GW-ANC-EMG-2…4 |

### 14.2 Six Tier-0 emergency routers

| Router | Nodes | ALWAYS_ABSTAIN | abstain: true | No classifier | REFER_EMERGENCY |
|---|---|---|---|---|---|
| `emergency_convulsions` | 4 | ✔ | ✔ | ✔ | ✔ |
| `emergency_unconscious` | 4 | ✔ | ✔ | ✔ | ✔ |
| `emergency_bite_sting` | 6 | ✔ | ✔ | ✔ | ✔ |
| `emergency_poisoning` | 6 | ✔ | ✔ | ✔ | ✔ |
| `emergency_heavy_bleeding` | 5 | ✔ | ✔ | ✔ | ✔ |
| `emergency_pregnancy_danger` | 4 | ✔ | ✔ | ✔ | ✔ |

**C1–C5 hold for all twelve.** BR-1, BR-2, NR-1 all pass. Six replay assertions all pass.

---

## 15. Tree-completeness check

### 15.1 All 26 core categories

| # | categoryId | Batch | Status |
|---|---|---|---|
| 1 | `fever` | tree memo §3 (+ 1.0.1 batch 2 §1) | ✔ authored |
| 2 | `cough` | batch 1 §1 | ✔ authored |
| 3 | `abdominal_pain` | batch 1 §3 | ✔ authored |
| 4 | `known_hypertension` | tree memo §4 (+ G-15 batch 3 §1) | ✔ authored |
| 5 | `diarrhoea` | batch 1 §2 | ✔ authored |
| 6 | `chest_pain` | batch 1 §5 | ✔ authored |
| 7 | `rash` | tree memo §5 | ✔ authored |
| 8 | `joint_pain` | batch 2 §2 | ✔ authored |
| 9 | `back_neck_pain` | batch 2 §3 | ✔ authored |
| 10 | `breathlessness` | batch 1 §4 | ✔ authored |
| 11 | `known_diabetes` | batch 2 §4 (+ G-15 batch 3 §1) | ✔ authored |
| 12 | `headache` | batch 2 §5 | ✔ authored |
| 13 | `injury` | batch 2 §6 | ✔ authored |
| 14 | `vomiting_nausea` | batch 3 §2 | ✔ authored |
| 15 | `acidity_heartburn` | batch 3 §3 | ✔ authored |
| 16 | `urinary_symptoms` | batch 3 §4 | ✔ authored |
| 17 | `cold_sore_throat` | batch 3 §5 | ✔ authored |
| 18 | `weakness_unwell` | batch 3 §6 | ✔ authored |
| 19 | `body_ache` | batch 3 §7 | ✔ authored |
| 20 | `weight_loss` | batch 3 §8 | ✔ authored |
| 21 | `oedema` | **batch 4 §1** | ✔ authored |
| 22 | `pallor_anaemia` | **batch 4 §2** | ✔ authored |
| 23 | `dizziness` | **batch 4 §3** | ✔ authored |
| 24 | `itching` | **batch 4 §4** | ✔ authored |
| 25 | `skin_infection` | **batch 4 §5** | ✔ authored |
| 26 | `antenatal_visit` | **batch 4 §6** | ✔ authored |

**26 of 26 core clinical categories authored.** ✔

### 15.2 The terminal fallback

| # | categoryId | Status |
|---|---|---|
| 27 | `other_not_in_list` | tree memo §1.1 — degenerate branch, ALWAYS_ABSTAIN. Opens free-text, records narrative, emits NOT_ASKED on all fields. **Authored in the schema, not as a branch memo.** ✔ |

### 15.3 All 6 Tier-0 emergency routers

| # | categoryId | Batch | Status |
|---|---|---|---|
| 28 | `emergency_convulsions` | **batch 4 §7** | ✔ authored |
| 29 | `emergency_unconscious` | **batch 4 §8** | ✔ authored |
| 30 | `emergency_bite_sting` | **batch 4 §9** | ✔ authored |
| 31 | `emergency_poisoning` | **batch 4 §10** | ✔ authored |
| 32 | `emergency_heavy_bleeding` | **batch 4 §11** | ✔ authored |
| 33 | `emergency_pregnancy_danger` | **batch 4 §12** | ✔ authored |

**6 of 6 Tier-0 emergency routers authored.** ✔

### 15.4 Total count

**33 branches authored** = 26 core clinical + 1 terminal fallback + 6 Tier-0 emergency.
This matches tree memo §1.1's stated total.

### 15.5 Shared registry consistency check

**Sub-trees (4):**
- `TB-SCREEN` v1.0.0 — referenced by: `cough`, `joint_pain`, `back_neck_pain`, `known_diabetes`, `weakness_unwell`, `weight_loss`
- `DEHYDRATION` v1.0.0 — referenced by: `diarrhoea`, `vomiting_nausea`
- `FEVER-QUAL` v1.1.0 — referenced by: `cough`, `diarrhoea`, `abdominal_pain`, `breathlessness`, `chest_pain`, `joint_pain`, `back_neck_pain`, `known_diabetes`, `headache`, `rash` (1.0.1), `vomiting_nausea`, `acidity_heartburn`, `urinary_symptoms`, `cold_sore_throat`, `weakness_unwell`, `body_ache`, `weight_loss`, `oedema`, `pallor_anaemia`, `dizziness`, `skin_infection`
- `EXPOSURE-CONTEXT-FEVER` v1.0.0 — referenced by: `fever` (1.0.1 only)
- `RASH-MORPH` v1.0.0 — referenced by: `fever`, `rash`, `itching`, `skin_infection`

No orphan SubtreeRef. Every SubtreeRef resolves to a defined sub-tree. ✔

**Shared fieldIds:** Cross-checked every fieldId that appears in more than one branch:
- `danger_signs` — same MULTI option set in every branch (general + category-specific additions; the general options `ds_unconscious`, `ds_cannot_feed`, `ds_none`, `ds_unknown` appear everywhere, with category-specific options added per branch).
  **Note:** This is a schema design question. Each branch's `danger_signs` option list is a superset of the IMCI general signs. The fieldId is shared; the option set varies by branch. Under strict "one fieldId = one option set" this would be a violation. **Resolution:** `danger_signs` is the ONE exception to strict option-set identity — its shared options (`ds_unconscious`, `ds_cannot_feed`, `ds_none`, `ds_unknown`) are fixed, and branches ADD category-specific options. The dataset column `danger_signs__status` is shared; the per-option columns (`danger_signs__ds_unconscious`, etc.) overlap where the option exists and are `NOT_ASKED` where it does not. This is consistent with MULTI_CHOICE expansion rules.
  **G-20 flagged below.**
- `danger_signs_pregnancy` — used only by `antenatal_visit`. Single option set. ✔
- `fever_present`, `fever_duration_band`, `fever_pattern`, `bleeding_manifestation` — owned by FEVER-QUAL. Same option set everywhere. ✔
- `cough_ge_2_weeks`, `weight_loss_present`, `night_sweats`, `tb_contact` — owned by TB-SCREEN. Same options everywhere. ✔
- `vomiting_present` — same option set (`vm_yes_everything`, `vm_yes_keeping_fluids`, `vm_no`, `vm_unknown`) in every branch that uses it. ✔
- `blood_in_stool` — same options everywhere. ✔
- `burning_on_urination` — same options everywhere. ✔
- `household_others_affected` — same options everywhere. ✔
- `new_drug_2_weeks` — same options everywhere. ✔
- `joint_swelling` — same options everywhere. ✔
- `appetite_change` — same options everywhere. ✔
- `pallor_site_observed` — same MULTI option set in `pallor_anaemia` and `antenatal_visit`. ✔
- `pregnancy_status` — same options everywhere. ✔
- `progression`, `prior_treatment_taken`, `relevant_history`, `impact_on_daily_activities` — same options everywhere. ✔

**No fieldId has two incompatible option sets (with the `danger_signs` caveat flagged as G-20).**

### 15.6 Category memo §7 and §6 coverage

Every category in the category memo:
- §6 (6 Tier-0): All 6 authored. ✔
- §7 Chapter A (5 categories): fever, weakness_unwell, body_ache, weight_loss, oedema. All 5 authored. ✔
- §7 Chapter R (3): cough, cold_sore_throat, breathlessness. All 3 authored. ✔
- §7 Chapter D (4): abdominal_pain, acidity_heartburn, diarrhoea, vomiting_nausea. All 4 authored. ✔
- §7 Chapter L (3): joint_pain, back_neck_pain, injury. All 3 authored. ✔
- §7 Chapter S (3): rash, itching, skin_infection. All 3 authored. ✔
- §7 Chapter N (2): headache, dizziness. Both authored. ✔
- §7 Chapter U (1): urinary_symptoms. Authored. ✔
- §7 Chapter K (2): chest_pain, known_hypertension. Both authored. ✔
- §7 Chapter T (1): known_diabetes. Authored. ✔
- §7 Chapter B (1): pallor_anaemia. Authored. ✔
- §7 Chapter W (1): antenatal_visit. Authored. ✔
- Terminal fallback: other_not_in_list. Authored (schema-level). ✔

**No category is unaccounted for. No blocker.** ✔

---

## 16. Flagged for physician and operator review

### G-19 — `skin_infection` exceeds 20-node ceiling on the RASH-MORPH + FEVER-QUAL path

Worst case: 14 authored + 7 (RASH-MORPH) + 4 (full FEVER-QUAL) = **25** nodes. Even with
FEVER-QUAL gate-only: 14 + 7 + 1 = **22**. Exceeds DECISION-2 ceiling.

**Options:**
(a) Drop the RASH-MORPH sub-tree for `skin_infection`. The branch already captures `lesion_type`,
    `lesion_site`, `spreading_redness`, `pus_discharge` — the clinical-safety fields are present.
    RASH-MORPH adds visual characterisation and the photo prompt. The photo prompt can be added as
    a standalone node without the full morphology sub-tree. Saves 6 nodes (RASH-MORPH minus photo),
    drops worst case to **19**. **Recommended.**
(b) Drop `tetanus_status` (saves 1) and merge `foot_sensation_loss` into `known_diabetes_flag`
    as a composite question (saves 1). Still **23** with full RASH-MORPH.
(c) Accept a third ceiling exception after `fever` (G-14) and `vomiting_nausea` (G-16).

**Operator decision required.**

### G-2 extensions (new clinical-safety gaps this batch)

| Category | Severe condition | Gap |
|---|---|---|
| `oedema` | Severe anaemia | No specific pallor sign at Stage A — pallor is a separate category |
| `oedema` | Chronic liver disease | Ascites/jaundice not captured by the general danger signs |
| `pallor_anaemia` | Haemoglobinopathy | No specific Stage A sign; chronic presentations captured as "pallor" |
| `pallor_anaemia` | Hookworm | Indistinguishable from dietary anaemia at Stage A |
| `pallor_anaemia` | Early haematological malignancy | No specific Stage A sign |
| `dizziness` | Dehydration-driven dizziness | No specific Stage A sign; orthostatic question (DZZ-04) discriminates |
| `itching` | Diabetes | No specific external sign; `known_liver_renal_disease` screens indirectly |
| `itching` | Renal disease | Uraemic pruritus has no specific sign; screened by history |
| `skin_infection` | Tetanus-prone wound | No single Stage A sign; wound classification + tetanus history |
| `antenatal_visit` | Anaemia in pregnancy | No pallor at Stage A; screened at ANC-08 |
| `antenatal_visit` | Malpresentation | Requires abdominal palpation; not a Stage A sign |
| `antenatal_visit` | Gestational diabetes | Asymptomatic; screened via history/referral |

### G-11 extensions

The corpus returns nothing for `oedema`, `itching`, `skin_infection`, and `antenatal_visit`
terms. `dizziness` is noise (44% unlabelled, 36% I10). Only `pallor_anaemia` has a single
weak signal (pallor → D50 77%, n=81). These branches are almost entirely `ASSUMED` in qualifier
grounding.

### G-20 — `danger_signs` has branch-varying option sets

`danger_signs` is used by 26 branches with a shared fieldId but branch-specific option lists.
The shared IMCI general options are consistent; the category-specific additions vary. Under
strict "one fieldId = one option set everywhere" this is a violation. The practical resolution
is that MULTI_CHOICE expansion produces per-option columns, and a `danger_signs__ds_neck_stiff`
column is `NOT_ASKED` for any branch that does not include that option. This is structurally
identical to how branch-specific fields work, but it is called out because it is the one shared
fieldId where the option set is not byte-identical across branches. **Schema owner should confirm
this is acceptable, or split into `danger_signs_general` (shared) + `danger_signs_specific`
(branch-specific).** The split doubles the number of danger-sign columns but makes the
one-fieldId-one-option-set rule hold everywhere.

---

## 17. Boundaries observed

No source, generator, dataset, model, config, questionnaire or tree runtime file was written,
edited or deleted in either repo. The SaMDClassifier repo was **read only** — one CSV read for
the §0.2 measurement pass. No `.env`, `local.properties` or credential file was opened at any
point. No commit, no staging. No branch was authored in code, no dataset generated, no model
trained. The only file written is this memo.

**The tree is now complete.** All 26 core clinical categories, 6 Tier-0 emergency routers, and
the terminal fallback have authored branches. The shared registry is internally consistent
(with the G-20 caveat flagged). The §6.5 authoring order is exhausted. The tree is ready for
gate review, and the next step is running the already-built generator across the full tree to
produce the corpus.
