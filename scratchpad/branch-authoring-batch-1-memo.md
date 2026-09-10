# Branch authoring — Batch 1 (STEP 1, read-only design memo)

Run date: 2026-09-07. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af` — **read only; `git status --short` there is empty.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
generator, dataset, model, config or tree runtime file touched, in either repo. No branch authored
in code, no dataset generated, no model trained. No `.env`, `local.properties` or credential file
opened at any point. No commit, no staging.

**Inputs treated as binding and not re-derived:**
`scratchpad/questionnaire-tree-design-memo.md` (THE schema: §1 branch schema, §6 five-stage
template, §3/§4/§5 the three reference branches), `scratchpad/reason-for-encounter-category-system-memo.md`
(every category constant below is transcribed from its §7, not invented; RF-1…RF-4 from its §4.4),
`scratchpad/dataset-regeneration-design-memo.md` (these branches are what the generator walks).

**Also read on disk this session, read-only:** `ConsultationScreen.kt:87-107` (the shipped
`DURATION_BUCKETS`, `ONSET_OPTIONS`, `HISTORY_CHIPS`, `IMPACT_CHIPS`),
`SaMDClassifier/dataset/canonical_dataset.csv` (one measurement pass, §0.2).

**Scope.** Batch 1 is the first group of tree memo §6.5's authoring order: `cough`, `diarrhoea`,
`abdominal_pain`, `breathlessness`, `chest_pain`. Nothing beyond those five is authored here.

---

## 0. Two things established before the branches, because the branches rest on them

### 0.1 What this batch adds to the schema, and why each addition was forced

The schema is settled and this memo changes none of it. It does add to the **shared registry** —
three sub-trees and eleven shared `fieldId`s — because the alternative in every case was two
columns for one clinical fact, which tree memo §5 note 1 names as the failure mode sub-tree reuse
exists to prevent. Every addition is listed in §7 and every one is a dataset-column change under
R4.

### 0.2 The measurement pass, and the one place it returns nothing

The brief says to use a measured discriminative signal where the audit or literature gives one. One
read-only pass over `canonical_dataset.csv` (15,105 labelled rows, `symptom_string` split on the
trained `" | "` delimiter, top co-occurring `icd_candidate` per term) extends the
wire-format memo's §2.4 table to this batch's terms:

    term                             n      top label   purity
    chronic cough over 2 weeks      99      A15         86%
    productive cough                26      J22         35%
    loose stools                   221      A09         93%
    abdominal cramps               226      A09         94%
    abdominal pain                 580      A09         28%
    lower abdominal pain           227      N39.0       93%
    belly pain                      58      B54         29%
    constipation or diarrhoea      179      A01.0       90%
    vomiting                       362      A09         48%
    exertional dyspnoea           2689      E66        100%
    breathlessness on exertion      80      D50         84%
    breathlessness                  35      A91         40%
    severe breathlessness           26      A91         42%
    dehydration signs               20      E66         40%
    chest tightness                 21      E66         48%
    chest pain on breathing         34      E66         32%
    chest discomfort                22      M17         36%
    chest heaviness                  2      M17         50%

Two readings, and the second is a finding.

**First, the granularity claim replicates on this batch.** `abdominal pain` → A09 at 28% is noise;
`lower abdominal pain` → N39.0 at 93% is a signal. `loose stools` and `abdominal cramps` both clear
93%. `chronic cough over 2 weeks` → A15 at 86% is the TB screen showing through the corpus. The
qualifier, not the stem, is what carries the diagnosis — the same result the audit measured for
fever and body ache, now measured for cough and abdominal pain.

**Second — and this is flagged, not used — the corpus returns nothing for `chest_pain`, and cannot.**
Every chest-pain term resolves to the obesity or osteoarthritis prior at n ≤ 34
(`chest tightness` → E66 48%, `chest pain on breathing` → E66 32%, `chest discomfort` → M17 36%).
That is not a weak signal, it is the absence of a class: **the current 18-label space contains no
cardiac diagnosis of any kind**, so there is nothing for a cardiac token to resolve to. `dehydration
signs` → E66 40% on n=20 is the same emptiness for dehydration. See **G-11**.

> **Consequence, stated rather than buried.** Every qualifier node in the `chest_pain` branch, and
> the `DEHYDRATION` sub-tree in full, is grounded on clinical literature and protocol alone. Every
> answer-model entry the generator later declares for them is `ASSUMED` under dataset memo §2.3, and
> must be physician-signed before the corpus backs any claim about them. No number in those two
> places may be presented as measured.

### 0.3 Ground-truth note on the shared fields

`ConsultationScreen.kt:87` `DURATION_BUCKETS = [today, few_days, week_plus, month_plus, chronic]`
and `:89-96` `ONSET_OPTIONS` are on disk as tree memo §2.3 records them. `:98-101` `HISTORY_CHIPS`
carries **`"Asthma / COPD"`, `"TB (past or current)"`, `"Heart disease"`, `"Known drug allergy"`,
`"Diabetes"`, `"Hypertension"`, `"Pregnancy"`, `"Thyroid disorder"`, `"Tobacco / alcohol use"`,
`"No known history"`**. That matters twice below: it is why `breathlessness` needs no
"known asthma" node (the coded `relevant_history` already carries it, prefilled), and why
`chest_pain` needs no "known heart disease" node.

---

## 1. Branch: `cough`

### 1.1 Category constants — transcribed from category memo §7 Chapter R

```
categoryId               = "cough"
displayName              = "Cough"
tier                     = CORE
icpc3Chapter             = R
icpc3Basis               = R05
icd10Mapping             = R05
anchor                   = { value: POSEIDON cough 30%, sourceType: IND-PRESENT,
                             source: Lancet Glob Health 2015 POSEIDON, n=204,912 }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY        // the entry floor
severeConditions         = [ pulmonary tuberculosis, pneumonia, asthma, COPD,
                             lung malignancy ]
branchVersion            = 1.0.0-draft
```

### 1.2 Stage A — CGH-00, the danger-sign screen

One worker-observable sign per severe condition, plus the WHO IMCI general danger signs
(**WORLD**, named at point of use; IMCI is what Indian PHC staff are trained on).

```
CGH-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_cannot_speak_sentence "Too breathless to finish a sentence"            → CGH-G1
  ds_chest_indrawing       "Chest pulling in below the ribs when breathing"  → CGH-G1
  ds_fast_breathing        "Breathing fast at rest"                          → CGH-G1
  ds_blue_lips             "Blue lips or tongue"                             → CGH-G1
  ds_large_haemoptysis     "Coughing up a large amount of blood"             → CGH-G1
  ds_unconscious           "Unconscious, or very drowsy / hard to wake"      → CGH-G1
  ds_convulsion            "Fits or convulsions"                             → CGH-G1
  ds_cannot_feed           "Unable to drink or feed"                         → CGH-G1
  ds_none                  "None of these"                                   → CGH-01
  ds_unknown               "Not known"                                       → CGH-01
```

**Severe-condition → danger-sign derivation, and the two gaps in it.**

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Pneumonia | `ds_fast_breathing`, `ds_chest_indrawing`, `ds_blue_lips` | covered (IMCI pneumonia signs) |
| Asthma (severe) | `ds_cannot_speak_sentence`, `ds_blue_lips` | covered |
| COPD (exacerbation) | `ds_cannot_speak_sentence`, `ds_blue_lips` | covered, **not separable from asthma at Stage A** |
| Pulmonary TB | *none* | **by design** — TB has no acute danger sign. It is caught by GW-CGH-URG-1 off the TB-SCREEN sub-tree, not by Stage A. Not a gap |
| Lung malignancy | `ds_large_haemoptysis` only | **G-2 gap** — massive haemoptysis is a late sign. Early malignancy reaches the URGENT gateway only through the shared weight-loss/duration fields |

`ds_cannot_feed` is the IMCI general danger sign, on the list at every age; GW-CGH-EMG-5 keys the
under-5 escalation off it, mirroring GW-FEV-EMG-5.

### 1.3 Stage B — shared core, identical fieldIds and options across all five branches

`duration_bucket`, `onset_pattern`, `severity_score` are **prefilled** from the consultation screen
(`ConsultationScreen.kt:87`, `:89-96`, `:289-292`) and are not asked; `severity_band` is derived;
`relevant_history` and `impact_on_daily_activities` prefill from the chip rows. Two Stage-B fields
have no capture affordance on disk (tree memo §2.3 marks both **NEW**) and are therefore nodes:

```
CGH-01  fieldId: progression   answerType: SINGLE_CHOICE      (shared, §2.3)
Q: "Since it started, is the cough better, the same, or worse?"
  pr_better  "Better"     → CGH-02
  pr_same    "About the same" → CGH-02
  pr_worse   "Worse"      → CGH-02
  pr_unknown "Not known"  → CGH-02

CGH-02  fieldId: prior_treatment_taken   answerType: MULTI_CHOICE   (shared, §2.3)
Q: "Has the patient already taken anything for this?"
  pt_none · pt_home_remedy · pt_pharmacy_medicine · pt_ayush · pt_other_facility
  pt_unknown "Not known"
  (all options → CGH-03)
```

These two nodes, verbatim with these fieldIds and optionIds, are Stage B in all five branches of
this batch and are not restated per branch below.

### 1.4 Stage C — the category qualifiers

```
CGH-03  fieldId: cough_character   answerType: SINGLE_CHOICE      (shared — §7 registry)
Q: "What is the cough like?"
  cc_dry        "Dry, no phlegm"                                → CGH-04
                valueToken: "dry cough"
  cc_productive "Brings up phlegm"                              → CGH-04
                valueToken: "productive cough"
  cc_blood      "Blood in the phlegm, or coughing up blood"     → CGH-G2
                valueToken: "haemoptysis"
  cc_paroxysmal "Bouts of coughing that end in a whoop or vomiting" → CGH-04
                valueToken: "paroxysmal cough"
  cc_unknown    "Not known"                                     → CGH-04
                valueToken: "cough"

CGH-04  fieldId: wheeze   answerType: SINGLE_CHOICE              (shared — §7 registry)
Q: "Is there a whistling sound from the chest when the patient breathes?"
help: "Listen at the mouth without a stethoscope, and ask the family if they have heard it."
  wh_audible   "Yes — can be heard without a stethoscope"  → CGH-05
  wh_reported  "Patient or family reports it"              → CGH-05
  wh_no        "No"                                        → CGH-05
  wh_unknown   "Not known"                                 → CGH-05

CGH-05  fieldId: breathlessness_present   answerType: SINGLE_CHOICE   (shared, §2.4)
Q: "Is the patient short of breath?"
  bp_at_rest     "Yes — even at rest"                    → CGH-G3
  bp_on_exertion "Yes — on walking or on exertion"       → CGH-06
                 valueToken: "breathlessness on exertion"
  bp_no          "No"                                    → CGH-06
  bp_unknown     "Not known"                             → CGH-06

CGH-06  fieldId: chest_pain_present   answerType: SINGLE_CHOICE       (shared — §7 registry)
Q: "Is there pain in the chest?"
  cp_with_breathing "Yes — worse on breathing in or on coughing"  → CGH-G3
                    valueToken: "chest pain on breathing"
  cp_other          "Yes — not related to breathing"              → CGH-07
  cp_no             "No"                                          → CGH-07
  cp_unknown        "Not known"                                   → CGH-07
```

**Why `sputum_blood` (tree memo §2.5's cough list) is not a node.** `cough_character == cc_blood`
already records haemoptysis, and the *volume* distinction that would justify a second field is
carried by `danger_signs.ds_large_haemoptysis`. Two nodes for one clinical fact would produce two
dataset columns for it. `sputum_blood` is **retired** from the cough field list (§7.4).

### 1.5 Stage D — exposure and context

```
CGH-07  fieldId: smoking_biomass_exposure   answerType: MULTI_CHOICE  (shared — §7 registry)
Q: "Is the patient around any of these regularly?"
  sb_tobacco_current "Smokes tobacco — bidi or cigarette"     → CGH-S1
  sb_tobacco_past    "Smoked in the past, has stopped"        → CGH-S1
  sb_chulha          "Cooks on a chulha or biomass stove"     → CGH-S1
  sb_occupational    "Works with dust, stone or smoke"        → CGH-S1
  sb_secondhand      "Someone else smokes inside the house"   → CGH-S1
  sb_none            "None of these"                          → CGH-S1
  sb_unknown         "Not known"                              → CGH-S1
```

### 1.6 Stage E — cross-links

```
CGH-S1  SubtreeRef → TB-SCREEN   (§6.1, new shared sub-tree)
        on return → CGH-G2, then CGH-S2

CGH-S2  SubtreeRef → FEVER-QUAL v1.1   (§6.3, amended to gate on fever_present)
        on return → CGH-END

CGH-END TerminalNode
```

### 1.7 Gateways

| Gateway | Rule (fixed boolean over collected fieldIds) | Effect | severeConditions carried |
|---|---|---|---|
| **GW-CGH-EMG-1** | `danger_signs contains ds_convulsion` | `routeTo: emergency_convulsions`, terminate | severe illness in a child, hypoxic seizure |
| **GW-CGH-EMG-2** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | respiratory failure, severe pneumonia, sepsis |
| **GW-CGH-EMG-3** | `danger_signs contains ds_large_haemoptysis` | `routeTo: emergency_heavy_bleeding`, terminate | massive haemoptysis — TB cavity, bronchiectasis, lung malignancy |
| **GW-CGH-EMG-4** | `anyOf[ danger_signs contains ds_cannot_speak_sentence, danger_signs contains ds_chest_indrawing, danger_signs contains ds_fast_breathing, danger_signs contains ds_blue_lips ]` | `raisesTo: REFER_EMERGENCY`, **continue** — **no valid Tier-0 target exists (G-1)** | pneumonia, severe asthma, COPD exacerbation |
| **GW-CGH-EMG-5** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign — severe illness in a child |
| **GW-CGH-URG-1 (TB)** | `anyOf[ cough_ge_2_weeks equals c2_yes, cough_character equals cc_blood, weight_loss_present equals wl_yes, night_sweats equals ns_yes, tb_contact equals tbc_yes ]` | `raisesTo: REFER_URGENT` | **pulmonary tuberculosis** — the national screening trigger, sputum pathway |
| **GW-CGH-URG-2** | `anyOf[ breathlessness_present equals bp_at_rest, allOf[ chest_pain_present equals cp_with_breathing, fever_present equals fp_yes ] ]` | `raisesTo: REFER_URGENT` | pneumonia, pleural effusion, empyema |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` at entry | the category's full list |

Node placement: `CGH-G1` after CGH-00 evaluates EMG-1…5 in that order; `CGH-G2` after CGH-03 and
again after the TB-SCREEN return evaluates URG-1; `CGH-G3` after CGH-06 evaluates URG-2.

Vitals-side triggers (SpO2 < 92, RR ≥ 30, HR ≥ 130) are **not restated here.** They are dataset
memo §6.1 Path 1, which fires on the vitals channel for every branch and forces `REFER_EMERGENCY`
plus abstention. A branch gateway reads vitals only for a category-specific threshold Path 1 does
not cover — the GW-HTN-URG-1 precedent (BP ≥ 180/110). Cough has none.

### 1.8 Worked BranchOutput — 41-year-old man, 3 weeks of productive cough, chulha smoke, TB contact

Path: no danger signs → worse → pharmacy medicine → productive → no wheeze → breathless on
exertion → no chest pain → chulha + secondhand → TB-SCREEN (yes / yes / yes / yes) → no fever.

```
BranchOutput {
  categoryId: "cough"
  branchVersion: "1.0.0-draft"
  pathTaken: [CGH-00, CGH-01, CGH-02, CGH-03, CGH-04, CGH-05, CGH-06, CGH-07,
              CGH-S1, TBS-01, TBS-02, TBS-03, TBS-04, CGH-G2,
              CGH-S2, FQ-00, CGH-END]
  fields: {
    danger_signs               { ANSWERED, [ds_none],           TAP }
    progression                { ANSWERED, pr_worse,            TAP }
    prior_treatment_taken      { ANSWERED, [pt_pharmacy_medicine], TAP }
    cough_character            { ANSWERED, cc_productive,       VOICE_CONFIRMED }
    wheeze                     { ANSWERED, wh_no,               TAP }
    breathlessness_present     { ANSWERED, bp_on_exertion,      TAP }
    chest_pain_present         { ANSWERED, cp_no,               TAP }
    smoking_biomass_exposure   { ANSWERED, [sb_chulha, sb_secondhand], TAP }
    cough_ge_2_weeks           { ANSWERED, c2_yes,              TAP }   // TB-SCREEN
    weight_loss_present        { ANSWERED, wl_yes,              TAP }   // TB-SCREEN
    night_sweats               { ANSWERED, ns_yes,              TAP }   // TB-SCREEN
    tb_contact                 { ANSWERED, tbc_yes,             TAP }   // TB-SCREEN
    fever_present              { ANSWERED, fp_no,               TAP }   // FEVER-QUAL gate
    fever_duration_band        { NOT_ASKED, null, — }                   // gate closed
    fever_pattern              { NOT_ASKED, null, — }                   // gate closed
    bleeding_manifestation     { NOT_ASKED, null, — }                   // gate closed
    duration_bucket            { ANSWERED, week_plus,           PREFILL_MEASURED }
    onset_pattern              { ANSWERED, gradual,             TAP }
    severity_score             { ANSWERED, 5,                   TAP }
    severity_band              { ANSWERED, moderate,            DERIVED }
    relevant_history           { ANSWERED, [no_known_history],  TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],    VOICE_CONFIRMED }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [ { GW-CGH-URG-1,
                     "cough_ge_2_weeks==c2_yes OR weight_loss_present==wl_yes OR
                      night_sweats==ns_yes OR tb_contact==tbc_yes",
                     [pulmonary tuberculosis] } ]
  routedTo: null
  attachments: []
}
```

The branch fired the TB gateway four separate ways and terminated at `REFER_URGENT`. It named no
disease. `cc_productive` + `c2_yes` is the pair the corpus measures at
`chronic cough over 2 weeks` → A15 86%; the tree's whole job here was to make sure the duration
qualifier reached the model instead of being flattened into "cough".

### 1.9 Node budget

Authored non-gateway nodes: **11** (CGH-00…CGH-07, CGH-S1, CGH-S2, CGH-END). Target 6–9, ceiling
12 — within, with one node of headroom. Expanded worst case (both sub-trees walked): 11 + 4 + 3 =
**18**, which is over 12 on the expansion reading — see **G-6**, where the ambiguity is flagged
rather than resolved unilaterally.

---

## 2. Branch: `diarrhoea`

### 2.1 Category constants — category memo §7 Chapter D

```
categoryId               = "diarrhoea"
displayName              = "Loose motions / diarrhoea"
tier                     = CORE
icpc3Chapter             = D
icpc3Basis               = D11
icd10Mapping             = A09, K52.9
anchor                   = { value: ~20% of chapter D, sourceType: ASSUMED,
                             cross-check: AIIMS Bhopal diarrhoeal disease 7.9% (IND-PRESENT, MP) }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ dehydration (especially under-5), cholera, dysentery,
                             enteric fever ]
branchVersion            = 1.0.0-draft
```

### 2.2 Stage A — DIA-00

```
DIA-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_unconscious    "Unconscious, or very drowsy / hard to wake"   → DIA-G1
  ds_convulsion     "Fits or convulsions"                          → DIA-G1
  ds_cannot_drink   "Unable to drink, or drinking very poorly"     → DIA-G1
  ds_sunken_eyes    "Eyes look sunken"                             → DIA-G1
  ds_no_urine       "Has not passed urine since yesterday"         → DIA-G1
  ds_blood_stool    "Blood in the stool"                           → DIA-G1
  ds_abdomen_rigid  "Belly hard and tender to touch"               → DIA-G1
  ds_cannot_feed    "Unable to drink or feed"                      → DIA-G1
  ds_none           "None of these"                                → DIA-01
  ds_unknown        "Not known"                                    → DIA-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Dehydration (severe) | `ds_cannot_drink`, `ds_sunken_eyes`, `ds_no_urine`, `ds_unconscious` — the IMCI severe-dehydration set, graded in full by the DEHYDRATION sub-tree | covered |
| Dysentery | `ds_blood_stool` | covered |
| Enteric fever (perforation) | `ds_abdomen_rigid` | covered for the complication only |
| Cholera | *no sign distinct from severe dehydration* | **G-2 gap** — cholera is reached only through the rice-water stool character (DIA-04) plus the high-frequency and dehydration gateways. There is no worker-observable sign that separates it from any other severe secretory diarrhoea, and there should not be one invented |
| Enteric fever (uncomplicated) | *none* | **by design** — caught by GW-DIA-URG-4 off fever plus duration, not by Stage A |

`ds_cannot_drink` and `ds_cannot_feed` are both listed because IMCI separates the adult/older-child
report ("unable to drink") from the infant feeding sign; the gateway treats them together and the
under-5 amplifier keys off `ds_cannot_feed`.

### 2.3 Stage B

`DIA-01 progression` and `DIA-02 prior_treatment_taken`, verbatim as §1.3, `next` → DIA-03 and
DIA-04 respectively. `prior_treatment_taken` is load-bearing in this branch specifically: prior
antibiotic is the pretreatment confounder for dysentery and enteric fever (tree memo §2.3).

### 2.4 Stage C — the category qualifiers

```
DIA-03  fieldId: stool_frequency_band   answerType: SINGLE_CHOICE
Q: "How many times has the patient passed stool in the last 24 hours?"
  sf_lt_3    "Fewer than 3 times"      → DIA-04
  sf_3_5     "3 to 5 times"            → DIA-04
  sf_6_10    "6 to 10 times"           → DIA-04
  sf_gt_10   "More than 10 times"      → DIA-G2
  sf_unknown "Not known"               → DIA-04

DIA-04  fieldId: stool_consistency   answerType: SINGLE_CHOICE
Q: "What is the stool like?"
help: "Ask what it looks like in the pan — how thin it is, and what colour."
  sc_loose_semisolid "Loose but still formed"             → DIA-05
                     valueToken: "loose stools"
  sc_watery          "Watery, no form at all"             → DIA-05
                     valueToken: "watery stools"
  sc_rice_water      "Watery and pale, like rice washings" → DIA-G2
                     valueToken: "rice water stool"
  sc_greasy          "Greasy, floats, hard to flush"      → DIA-05
                     valueToken: "steatorrhoea"
  sc_unknown         "Not known"                          → DIA-05
                     valueToken: "loose stools"

DIA-05  fieldId: blood_in_stool   answerType: SINGLE_CHOICE       (shared, §2.5 cross-branch)
Q: "Is there blood in the stool?"
  bs_frank   "Yes — visible red blood"                    → DIA-G2
  bs_streaks "Yes — streaks on the surface only"          → DIA-G2
  bs_black   "Stool is black and tarry"                   → DIA-G2
  bs_no      "No blood seen"                              → DIA-06
  bs_unknown "Not known"                                  → DIA-06

DIA-06  fieldId: vomiting_present   answerType: SINGLE_CHOICE     (shared — §7 registry)
Q: "Is the patient vomiting?"
  vm_yes_keeping_fluids "Yes, but can still keep fluids down"  → DIA-07
  vm_yes_everything     "Yes — brings up everything, cannot keep fluids down" → DIA-G2
  vm_no                 "No"                                   → DIA-07
  vm_unknown            "Not known"                            → DIA-07
```

`sc_rice_water` is the one option in this batch that names an appearance a worker may never have
been taught to look for; the `help` text on DIA-04 is why it is on the same node rather than behind
a "do you think this is cholera" question, which NR-1 forbids outright. The option records what the
stool looks like. It does not record what it is.

**`mucus_in_stool` (tree memo §2.5's diarrhoea list) is deferred, not authored** — see **G-13**.

### 2.5 Stage D — exposure and context

```
DIA-07  fieldId: water_source   answerType: SINGLE_CHOICE
Q: "Where does the household get its drinking water?"
  ws_piped_treated    "Piped or treated supply"             → DIA-08
  ws_handpump         "Hand pump or borewell"               → DIA-08
  ws_open_well        "Open well"                           → DIA-08
  ws_surface          "Pond, river, canal or stored surface water" → DIA-G2
  ws_tanker           "Tanker or bought water"              → DIA-08
  ws_unknown          "Not known"                           → DIA-08

DIA-08  fieldId: household_others_affected   answerType: SINGLE_CHOICE   (shared, from `rash` RSH-08)
Q: "Is anyone else in the house or neighbourhood having the same loose motions?"
  ho_yes     "Yes"        → DIA-G2
  ho_no      "No"         → DIA-S1
  ho_unknown "Not known"  → DIA-S1
```

`household_others_affected` is **reused, not re-authored** — it is the same fieldId and the same
option set the `rash` branch already declares at RSH-08. Two option sets for one clinical fact is
what tree memo §5 note 1 forbids.

### 2.6 Stage E — cross-links

```
DIA-S1  SubtreeRef → DEHYDRATION   (§6.2, new shared sub-tree)
        on return → DIA-G3, then DIA-S2

DIA-S2  SubtreeRef → FEVER-QUAL v1.1
        on return → DIA-G4, then DIA-END

DIA-END TerminalNode
```

### 2.7 Gateways

| Gateway | Rule | Effect | severeConditions carried |
|---|---|---|---|
| **GW-DIA-EMG-1** | `danger_signs contains ds_convulsion` | `routeTo: emergency_convulsions`, terminate | severe dehydration with electrolyte disturbance, severe illness in a child |
| **GW-DIA-EMG-2** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | hypovolaemic shock, severe dehydration, sepsis |
| **GW-DIA-EMG-3 (severe dehydration, IMCI)** | `anyOf[ allOf[A,B], allOf[A,C], allOf[A,D], allOf[B,C], allOf[B,D], allOf[C,D] ]` where **A** = `general_condition equals gc_lethargic`, **B** = `dehydration_eyes equals de_yes`, **C** = `dehydration_thirst in {dt_unable, dt_poor}`, **D** = `skin_pinch equals sp_very_slow` | `raisesTo: REFER_EMERGENCY`, **continue** — no Tier-0 target (**G-1**) | severe dehydration, hypovolaemic shock, cholera |
| **GW-DIA-EMG-4** | `danger_signs contains ds_abdomen_rigid` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**) | enteric perforation, peritonitis, toxic megacolon |
| **GW-DIA-EMG-5** | `allOf[ age_band lt 5, anyOf[ danger_signs contains ds_cannot_feed, dehydration_thirst equals dt_unable ] ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign — severe illness in a child |
| **GW-DIA-URG-1 (some dehydration)** | `anyOf[ dehydration_thirst equals dt_eager, dehydration_eyes equals de_yes, skin_pinch equals sp_slow, urine_output_reduced in {uo_reduced, uo_none_since_yesterday}, general_condition equals gc_restless ]` | `raisesTo: REFER_URGENT` | dehydration |
| **GW-DIA-URG-2 (dysentery)** | `anyOf[ blood_in_stool in {bs_frank, bs_streaks, bs_black}, danger_signs contains ds_blood_stool ]` | `raisesTo: REFER_URGENT` | dysentery (shigellosis), amoebic colitis, enteric fever with bleeding |
| **GW-DIA-URG-3 (high-output)** | `anyOf[ stool_consistency equals sc_rice_water, stool_frequency_band equals sf_gt_10, vomiting_present equals vm_yes_everything, allOf[ water_source equals ws_surface, household_others_affected equals ho_yes ] ]` | `raisesTo: REFER_URGENT` | cholera, severe secretory diarrhoea, foodborne outbreak |
| **GW-DIA-URG-4 (prolonged / enteric)** | `allOf[ fever_present equals fp_yes, duration_bucket in {week_plus, month_plus, chronic} ]` | `raisesTo: REFER_URGENT` | enteric fever, abdominal tuberculosis, chronic parasitic infection |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `DIA-G1` after DIA-00 evaluates EMG-1, EMG-2, EMG-4, EMG-5; `DIA-G2` evaluates
URG-2 and URG-3 as their fields land; `DIA-G3` after the DEHYDRATION return evaluates EMG-3, EMG-5
and URG-1; `DIA-G4` after the FEVER-QUAL return evaluates URG-4.

**Two deliberate rule decisions, both stated.**

1. **GW-DIA-EMG-3 writes out IMCI's "two or more of four signs" as six explicit `allOf` pairs**,
   because the rule grammar in tree memo §1.6 has comparison operators only and no count operator.
   Six pairs is exact and enumerable; the alternative — approximating "two or more" with "any one"
   — would move the emergency threshold. See **G-4** for the schema request that would let this be
   written once.
2. **GW-DIA-URG-1 fires on any single sign, where IMCI's "some dehydration" classification requires
   two.** This is deliberately more conservative than IMCI. It is legitimate because
   `dispositionFloor` is a *floor*, not a classification (RF-2): raising a patient with one
   dehydration sign to `REFER_URGENT` over-refers, and the lattice permits only raising. The branch
   is not claiming the patient has some dehydration; it is declining to let a single sign pass at
   the base floor.

### 2.8 Worked BranchOutput — 3-year-old, 2 days of watery stools, drinking eagerly, sunken eyes

```
BranchOutput {
  categoryId: "diarrhoea"
  branchVersion: "1.0.0-draft"
  pathTaken: [DIA-00, DIA-G1, DIA-01, DIA-02, DIA-03, DIA-04, DIA-05, DIA-06, DIA-07, DIA-08,
              DIA-S1, DEH-01, DEH-02, DEH-03, DEH-04, DEH-05, DIA-G3,
              DIA-S2, FQ-00, FQ-01, FQ-02, FQ-03, DIA-G4, DIA-END]
  fields: {
    danger_signs             { ANSWERED, [ds_sunken_eyes],   TAP }
    progression              { ANSWERED, pr_worse,           TAP }
    prior_treatment_taken    { ANSWERED, [pt_home_remedy],   TAP }
    stool_frequency_band     { ANSWERED, sf_6_10,            TAP }
    stool_consistency        { ANSWERED, sc_watery,          TAP }
    blood_in_stool           { ANSWERED, bs_no,              TAP }
    vomiting_present         { ANSWERED, vm_yes_keeping_fluids, TAP }
    water_source             { ANSWERED, ws_handpump,        TAP }
    household_others_affected{ ANSWERED, ho_no,              TAP }
    dehydration_thirst       { ANSWERED, dt_eager,           TAP }   // DEHYDRATION
    dehydration_eyes         { ANSWERED, de_yes,             TAP }   // DEHYDRATION
    skin_pinch               { ANSWERED, sp_slow,            TAP }   // DEHYDRATION
    urine_output_reduced     { ANSWERED, uo_reduced,         TAP }   // DEHYDRATION
    general_condition        { ANSWERED, gc_restless,        TAP }   // DEHYDRATION
    fever_present            { ANSWERED, fp_yes,             TAP }   // FEVER-QUAL gate
    fever_duration_band      { ANSWERED, fd_1_3,             TAP }
    fever_pattern            { ANSWERED, fp_mild,            TAP }
    bleeding_manifestation   { ANSWERED, [bl_none],          TAP }
    duration_bucket          { ANSWERED, few_days,           PREFILL_MEASURED }
    onset_pattern            { ANSWERED, acute_1_3d,         TAP }
    severity_score           { ANSWERED, 6,                  TAP }
    severity_band            { ANSWERED, moderate,           DERIVED }
    relevant_history         { ANSWERED, [no_known_history], TAP }
    impact_on_daily_activities { ANSWERED, [reduced_appetite], TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [ { GW-DIA-URG-1,
                     "dehydration_thirst==dt_eager OR dehydration_eyes==de_yes OR
                      skin_pinch==sp_slow OR urine_output_reduced==uo_reduced OR
                      general_condition==gc_restless",
                     [dehydration] } ]
  routedTo: null
  attachments: []
}
```

GW-DIA-EMG-3 did **not** fire: only `de_yes` from its four-sign set is present (`dt_eager` is not in
`{dt_unable, dt_poor}`, the pinch is slow not very slow, the child is restless not lethargic), so no
`allOf` pair is satisfied. That is the six-pair encoding behaving exactly as IMCI's two-sign rule
does, and it is the single most important thing to unit-test in this branch.

### 2.9 Node budget

Authored non-gateway nodes: **12** (DIA-00…DIA-08, DIA-S1, DIA-S2, DIA-END). **At the ceiling,
zero headroom.** Any further diarrhoea qualifier — `mucus_in_stool` being the first candidate —
forces either a category split or the resolution of **G-6**. Expanded worst case: 12 + 5 + 4 = 21.

---

## 3. Branch: `abdominal_pain`

### 3.1 Category constants — category memo §7 Chapter D

```
categoryId               = "abdominal_pain"
displayName              = "Stomach or abdominal pain"
tier                     = CORE
icpc3Chapter             = D
icpc3Basis               = D01, D06
icd10Mapping             = R10.4
anchor                   = { value: top-3 individual complaint at Odisha PHC,
                             sourceType: IND-PRESENT, source: Gupta P et al. PLOS GPH 2024 }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT          // NOTE: the entry floor is already URGENT
severeConditions         = [ acute abdomen, appendicitis, perforation, ectopic pregnancy,
                             obstruction, pancreatitis, enteric fever ]
branchVersion            = 1.0.0-draft
```

**The entry floor is `REFER_URGENT`, not `PHYSICIAN_REVIEW_MANDATORY`.** Every abdominal-pain
encounter starts one step up the lattice, before a single question is asked. This has a consequence
that shows up in §3.6 and is worth stating here: most of this branch's narrowing gateways carry
`severeConditions` and set a physician-visible flag while writing nothing to the lattice, because
the lattice is already above them. That is not a defect — it is `GW-HTN-ADH-1`'s precedent (tree
memo §4: "a gateway may fire and write nothing to it") applied to a category whose floor starts high.

### 3.2 Stage A — ABD-00

```
ABD-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_abdomen_rigid       "Belly hard and tender to touch"                  → ABD-G1
  ds_pain_severe_sudden  "The pain came on suddenly and is very severe"    → ABD-G1
  ds_no_stool_no_wind    "No stool and no wind passed since yesterday"     → ABD-G1
  ds_vomiting_everything "Vomiting everything, cannot keep fluids down"    → ABD-G1
  ds_faint               "Fainted, or feels faint on standing"             → ABD-G1
  ds_blood_vomit_stool   "Blood in the vomit, or black or bloody stool"    → ABD-G1
  ds_unconscious         "Unconscious, or very drowsy / hard to wake"      → ABD-G1
  ds_cannot_feed         "Unable to drink or feed"                         → ABD-G1
  ds_none                "None of these"                                   → ABD-01
  ds_unknown             "Not known"                                       → ABD-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Acute abdomen / peritonitis | `ds_abdomen_rigid`, `ds_pain_severe_sudden` | covered |
| Perforation | `ds_abdomen_rigid`, `ds_pain_severe_sudden` | covered |
| Obstruction | `ds_no_stool_no_wind`, `ds_vomiting_everything` | covered |
| Ectopic pregnancy | `ds_faint` + the ABD-09 LMP node + `pregnancy_status` | covered, and the gateway is the branch's highest-value rule (GW-ABD-EMG-4) |
| Upper GI bleed / enteric bleed | `ds_blood_vomit_stool` | covered |
| Enteric fever | *none in Stage A* | by design — GW-ABD-URG-3 off fever plus duration |
| **Pancreatitis** | ***none*** | **G-2 gap.** Severe constant epigastric pain radiating to the back is captured at Stage C (ABD-03 + ABD-04), but there is no Stage A sign that fires early, and no PHC-available observation (no lipase, no imaging) that distinguishes it. It reaches `REFER_EMERGENCY` only via `ds_pain_severe_sudden` or `ds_vomiting_everything`, both of which it may lack. **Physician review required** |

### 3.3 Stage B

`ABD-01 progression` and `ABD-02 prior_treatment_taken`, verbatim as §1.3, `next` → ABD-03 and
ABD-04 respectively.

### 3.4 Stage C — the category qualifiers

```
ABD-03  fieldId: pain_site   answerType: SINGLE_CHOICE
Q: "Point to where the pain is worst. Where is it?"
help: "Ask the patient to put one finger on the worst spot."
  ps_upper_right  "Upper right, under the ribs"        → ABD-04
                  valueToken: "right upper quadrant pain"
  ps_upper_middle "Upper middle, below the breastbone" → ABD-04
                  valueToken: "epigastric pain"
  ps_upper_left   "Upper left, under the ribs"         → ABD-04
                  valueToken: "left upper quadrant pain"
  ps_around_navel "Around the navel"                   → ABD-04
                  valueToken: "periumbilical pain"
  ps_lower_right  "Lower right"                        → ABD-G2
                  valueToken: "right iliac fossa pain"
  ps_lower_middle "Lower middle, above the pubic bone" → ABD-G2
                  valueToken: "lower abdominal pain"
  ps_lower_left   "Lower left"                         → ABD-G2
                  valueToken: "left iliac fossa pain"
  ps_all_over     "All over the belly"                 → ABD-G2
                  valueToken: "generalised abdominal pain"
  ps_unknown      "Not known"                          → ABD-04
                  valueToken: "abdominal pain"

ABD-04  fieldId: pain_radiation   answerType: SINGLE_CHOICE
Q: "Does the pain go anywhere else from there?"
  pr_to_back      "Straight through to the back"          → ABD-05
  pr_to_shoulder  "Up to the right shoulder or shoulder tip" → ABD-05
  pr_to_groin     "Down to the groin or genitals"         → ABD-05
  pr_to_chest     "Up into the chest"                     → ABD-05
  pr_none         "Stays in one place"                    → ABD-05
  pr_unknown      "Not known"                             → ABD-05

ABD-05  fieldId: pain_migration   answerType: SINGLE_CHOICE
Q: "Did the pain start somewhere else and move to where it is now?"
  pm_navel_to_lower_right "Started near the navel and moved to the lower right" → ABD-G2
  pm_moved_other          "Started elsewhere and moved"     → ABD-06
  pm_no_move              "Has been in the same place throughout" → ABD-06
  pm_unknown              "Not known"                       → ABD-06

ABD-06  fieldId: vomiting_present   answerType: SINGLE_CHOICE     (shared, options as §2.4)
Q: "Is the patient vomiting?"
  vm_yes_keeping_fluids → ABD-07 · vm_yes_everything → ABD-G1 ·
  vm_no → ABD-07 · vm_unknown → ABD-07

ABD-07  fieldId: bowels_open   answerType: SINGLE_CHOICE
Q: "When did the patient last pass stool, and what was it like?"
  bo_normal_today      "Passed normal stool today"              → ABD-08
  bo_loose             "Loose motions"                          → ABD-08
  bo_constipated       "Constipated for two days or more"       → ABD-08
  bo_none_and_no_wind  "No stool and no wind passed"            → ABD-G1
  bo_unknown           "Not known"                              → ABD-08

ABD-08  fieldId: burning_on_urination   answerType: SINGLE_CHOICE   (shared with `urinary_symptoms`)
Q: "Is there burning or pain on passing urine?"
  bu_yes     "Yes"        → ABD-G2
  bu_no      "No"         → ABD-09
  bu_unknown "Not known"  → ABD-09
```

**Why `burning_on_urination` is in an abdominal-pain branch.** It is the one qualifier in this
category with a **measured** discriminative signal: `lower abdominal pain` → N39.0 (UTI) at **93%
purity on 227 rows**, against bare `abdominal pain` → A09 at **28% on 580 rows** (§0.2). The
corpus's strongest abdominal-pain qualifier points at the urinary tract, and the branch would have
had no node capable of separating it. The fieldId is shared with the `urinary_symptoms` branch of a
later batch — one column, not two.

**Why `pain_character` (tree memo §2.5's abdominal_pain list) is not a node.** The
colicky-versus-constant distinction is already carried by the shared, prefilled `onset_pattern`,
whose option set (`ConsultationScreen.kt:89-96`) includes `intermittent / episodic`. A second node
would be a second dataset column for one clinical fact. Retired (§7.4).

**Why `abdomen_rigid` (§2.5) is not a separate field.** It is `danger_signs.ds_abdomen_rigid`,
asked at Stage A where it fires early, which is the whole reason Stage A comes first. Retired.

### 3.5 Stage D/E — context and cross-links

```
ABD-09  fieldId: lmp_known   answerType: SINGLE_CHOICE      (shared with `vomiting_nausea`)
        asked only when sex == F and age_band in [15,49]; else NOT_ASKED  (deterministic gate)
Q: "When was the last monthly period?"
  lmp_within_4wk    "Within the last 4 weeks"          → ABD-G3
  lmp_late_or_missed "Late, or missed altogether"      → ABD-G3
  lmp_gt_3_months   "More than 3 months ago"           → ABD-G3
  lmp_not_applicable "Does not apply"                  → ABD-G3
  lmp_unknown       "Not known"                        → ABD-G3

ABD-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → ABD-G4, then ABD-END

ABD-END TerminalNode
```

`pregnancy_status` (shared, tree memo §2.1) prefills from registration where present and is read by
GW-ABD-EMG-4; it is not a node in this branch.

### 3.6 Gateways

Base floor `REFER_URGENT`. `max()` is the only writer; a gateway whose `raisesTo` is at or below the
current floor writes nothing and is marked **informational**.

| Gateway | Rule | Effect | severeConditions carried |
|---|---|---|---|
| **GW-ABD-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | haemorrhagic shock, septic shock, ruptured ectopic |
| **GW-ABD-EMG-2** | `danger_signs contains ds_blood_vomit_stool` | `routeTo: emergency_heavy_bleeding`, terminate | upper GI bleed, enteric perforation with bleeding, variceal bleed |
| **GW-ABD-EMG-3 (acute abdomen)** | `anyOf[ danger_signs contains ds_abdomen_rigid, danger_signs contains ds_pain_severe_sudden, danger_signs contains ds_no_stool_no_wind, danger_signs contains ds_vomiting_everything, vomiting_present equals vm_yes_everything, bowels_open equals bo_none_and_no_wind ]` | `raisesTo: REFER_EMERGENCY`, **continue** — no Tier-0 target (**G-1**) | acute abdomen, perforation, obstruction, pancreatitis |
| **GW-ABD-EMG-4 (ectopic)** | `allOf[ sex equals F, age_band in [15,49], anyOf[ lmp_known equals lmp_late_or_missed, pregnancy_status equals yes ], anyOf[ pain_site in {ps_lower_right, ps_lower_left, ps_lower_middle}, danger_signs contains ds_faint ] ]` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**; `emergency_pregnancy_danger` is a candidate target, see the note below) | **ectopic pregnancy**, ruptured ovarian cyst, tubo-ovarian abscess |
| **GW-ABD-EMG-5** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign |
| **GW-ABD-URG-1 (appendicitis)** | `anyOf[ pain_migration equals pm_navel_to_lower_right, allOf[ pain_site equals ps_lower_right, fever_present equals fp_yes ] ]` | **informational** at floor | appendicitis, mesenteric adenitis, ileocaecal TB |
| **GW-ABD-URG-2 (pancreatic / biliary)** | `allOf[ pain_radiation in {pr_to_back, pr_to_shoulder}, severity_band equals severe ]` | **informational** at floor | pancreatitis, biliary colic, cholecystitis |
| **GW-ABD-URG-3 (enteric)** | `allOf[ fever_present equals fp_yes, duration_bucket in {week_plus, month_plus} ]` | **informational** at floor | enteric fever, abdominal tuberculosis |
| **GW-ABD-URG-4 (urinary)** | `allOf[ burning_on_urination equals bu_yes, fever_present equals fp_yes ]` | **informational** at floor | pyelonephritis, urinary obstruction, UTI in pregnancy |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

**Note on GW-ABD-EMG-4's routing target.** `emergency_pregnancy_danger` exists in Tier 0 and its
scope (category memo §6) is "bleeding, fits, severe headache, no fetal movement" — a *known*
pregnancy in danger. A suspected ectopic in a woman with a missed period who may not know she is
pregnant is a different presentation and routing it there would silently widen a Tier-0 category's
declared scope. **This memo does not widen it.** The gateway raises to `REFER_EMERGENCY` and
continues, and whether `emergency_pregnancy_danger` should be widened, or a new Tier-0 category
added, is part of **G-1** for physician and operator review.

**Note on `severity_band` in GW-ABD-URG-2.** `severity_band`'s cut-points over the 0–10
`severity_score` slider are **not defined anywhere in the schema**. See **G-3**. Until they are, this
rule is unevaluable; the memo proposes 0–3 / 4–6 / 7–10 as `ASSUMED` and does not treat it as settled.

### 3.7 Worked BranchOutput — 22-year-old woman, 1 day of lower-right pain that moved from the navel

```
BranchOutput {
  categoryId: "abdominal_pain"
  branchVersion: "1.0.0-draft"
  pathTaken: [ABD-00, ABD-01, ABD-02, ABD-03, ABD-G2, ABD-04, ABD-05, ABD-G2, ABD-06,
              ABD-07, ABD-08, ABD-09, ABD-G3, ABD-S1, FQ-00, FQ-01, FQ-02, FQ-03,
              ABD-G4, ABD-END]
  fields: {
    danger_signs           { ANSWERED, [ds_none],            TAP }
    progression            { ANSWERED, pr_worse,             TAP }
    prior_treatment_taken  { ANSWERED, [pt_pharmacy_medicine], TAP }
    pain_site              { ANSWERED, ps_lower_right,       TAP }
    pain_radiation         { ANSWERED, pr_none,              TAP }
    pain_migration         { ANSWERED, pm_navel_to_lower_right, VOICE_CONFIRMED }
    vomiting_present       { ANSWERED, vm_yes_keeping_fluids, TAP }
    bowels_open            { ANSWERED, bo_constipated,       TAP }
    burning_on_urination   { ANSWERED, bu_no,                TAP }
    lmp_known              { ANSWERED, lmp_within_4wk,       TAP }
    pregnancy_status       { ANSWERED, pg_no,                PREFILL_MEASURED }
    fever_present          { ANSWERED, fp_yes,               TAP }
    fever_duration_band    { ANSWERED, fd_today,             TAP }
    fever_pattern          { ANSWERED, fp_mild,              TAP }
    bleeding_manifestation { ANSWERED, [bl_none],            TAP }
    duration_bucket        { ANSWERED, today,                PREFILL_MEASURED }
    onset_pattern          { ANSWERED, gradual,              TAP }
    severity_score         { ANSWERED, 8,                    TAP }
    severity_band          { ANSWERED, severe,               DERIVED }
    relevant_history       { ANSWERED, [no_known_history],   TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work], TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [ { GW-ABD-URG-1,
                     "pain_migration==pm_navel_to_lower_right OR
                      (pain_site==ps_lower_right AND fever_present==fp_yes)",
                     [appendicitis, mesenteric adenitis, ileocaecal TB] } ]
  routedTo: null
  attachments: []
}
```

`GW-ABD-EMG-4` did not fire — the period was within four weeks and `pregnancy_status` is `no`, so
the `anyOf` on the pregnancy clause is unsatisfied. `GW-ABD-URG-1` fired both ways and wrote nothing
to the lattice, because `REFER_URGENT` was already the entry floor; what it produced is the
`firedGateways` entry the physician sees. The tree named no disease: it recorded that the pain
started at the navel and moved to the lower right, which is the observation, not the conclusion.

### 3.8 Node budget

Authored non-gateway nodes: **12** (ABD-00…ABD-09, ABD-S1, ABD-END). At the ceiling, zero headroom.
Expanded worst case: 12 + 4 = 16.

---

## 4. Branch: `breathlessness`

### 4.1 Category constants — category memo §7 Chapter R

```
categoryId               = "breathlessness"
displayName              = "Difficulty breathing / breathlessness"
tier                     = CORE
icpc3Chapter             = R
icpc3Basis               = R02
icd10Mapping             = R06.0
anchor                   = { value: ~15% of chapter R, sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT          // entry floor is already URGENT
severeConditions         = [ pneumonia, severe asthma, heart failure, severe anaemia,
                             severe dengue, severe malaria, anaphylaxis ]
branchVersion            = 1.0.0-draft
```

### 4.2 Stage A — BRE-00

```
BRE-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_cannot_speak_sentence "Too breathless to finish a sentence"           → BRE-G1
  ds_blue_lips             "Blue lips or tongue"                           → BRE-G1
  ds_chest_indrawing       "Chest pulling in below the ribs when breathing" → BRE-G1
  ds_cannot_lie_flat       "Cannot lie flat — has to sit up to breathe"    → BRE-G1
  ds_frothy_sputum         "Coughing up pink or frothy spit"               → BRE-G1
  ds_swollen_face          "Swollen face, lips or tongue"                  → BRE-G1
  ds_severe_pallor         "Palms or inner eyelids look very pale"         → BRE-G1
  ds_unconscious           "Unconscious, or very drowsy / hard to wake"    → BRE-G1
  ds_cannot_feed           "Unable to drink or feed"                       → BRE-G1
  ds_none                  "None of these"                                 → BRE-01
  ds_unknown               "Not known"                                     → BRE-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Pneumonia | `ds_chest_indrawing`, `ds_blue_lips`, plus FEVER-QUAL | covered (IMCI) |
| Severe asthma | `ds_cannot_speak_sentence`, `ds_blue_lips` | covered for the presenting form; **the "silent chest" of a near-fatal attack requires auscultation and is not worker-observable — G-2** |
| Heart failure | `ds_cannot_lie_flat`, `ds_frothy_sputum`, plus BRE-04 orthopnoea and BRE-07 ankle swelling | covered |
| Severe anaemia | `ds_severe_pallor` (IMCI severe palmar pallor) | covered at the severe end only; **moderate anaemia has no node in this branch — G-12** |
| Anaphylaxis | `ds_swollen_face` | covered as a sign; **no Tier-0 routing target for drug- or food-triggered anaphylaxis — `emergency_bite_sting` covers stings only. G-1/G-2** |
| Severe dengue / severe malaria | *no sign specific to this branch* | by design — the FEVER-QUAL cross-link carries `bleeding_manifestation`, and the `fever` branch is the category that owns them |

### 4.3 Stage B

`BRE-01 progression`, `BRE-02 prior_treatment_taken`, verbatim as §1.3, `next` → BRE-03 and BRE-04.

### 4.4 Stage C — the category qualifiers

```
BRE-03  fieldId: breathlessness_present   answerType: SINGLE_CHOICE   (shared, §2.4; options as §1.4)
Q: "When does the breathlessness come on?"
  bp_at_rest     "Even at rest"                     → BRE-G2
  bp_on_exertion "On walking or on exertion"        → BRE-04
                 valueToken: "breathlessness on exertion"
  bp_no          "Not at the moment"                → BRE-04
  bp_unknown     "Not known"                        → BRE-04

BRE-04  fieldId: orthopnoea   answerType: SINGLE_CHOICE
Q: "How does the patient sleep at night?"
help: "Ask how many pillows, and whether they wake up short of breath."
  op_needs_pillows  "Has to sit up or use extra pillows to breathe"  → BRE-G2
  op_wakes_gasping  "Wakes at night gasping for breath"              → BRE-G2
  op_both           "Both"                                          → BRE-G2
  op_sleeps_flat    "Sleeps flat without trouble"                    → BRE-05
  op_unknown        "Not known"                                      → BRE-05

BRE-05  fieldId: wheeze   answerType: SINGLE_CHOICE   (shared with `cough`; options as §1.4)
Q: "Is there a whistling sound from the chest when the patient breathes?"
  wh_audible → BRE-06 · wh_reported → BRE-06 · wh_no → BRE-06 · wh_unknown → BRE-06

BRE-06  fieldId: chest_pain_present   answerType: SINGLE_CHOICE   (shared; options as §1.4)
Q: "Is there pain in the chest?"
  cp_with_breathing → BRE-G2 · cp_other → BRE-07 · cp_no → BRE-07 · cp_unknown → BRE-07

BRE-07  fieldId: ankle_swelling   answerType: SINGLE_CHOICE     (shared — §7 registry)
Q: "Is there swelling of the feet or ankles?"
  as_both_legs "Yes — both legs"     → BRE-G2
  as_one_leg   "Yes — one leg only"  → BRE-G2
  as_no        "No"                  → BRE-08
  as_unknown   "Not known"           → BRE-08

BRE-08  fieldId: cough_character   answerType: SINGLE_CHOICE   (shared with `cough`; options as §1.4)
Q: "Is there a cough with it, and what is it like?"
  cc_dry → BRE-09 · cc_productive → BRE-09 · cc_blood → BRE-G2 ·
  cc_paroxysmal → BRE-09 · cc_unknown → BRE-09
  cc_none  "No cough"  → BRE-09
```

`ankle_swelling` separating **one leg** from **both legs** is the point of the node: bilateral is
the heart-failure/renal pattern, unilateral is the DVT pattern that makes an acute breathlessness a
pulmonary embolism. It is one extra option, and it is the difference between an urgent referral and
an emergency one.

**Why `breathlessness_trigger` (tree memo §2.5) is not a node.** Its declared axis
(rest / exertion / lying flat) is exactly the shared `breathlessness_present` option set plus
`orthopnoea`, both of which are asked. A third field over the same axis would be a third column for
one clinical fact. Retired (§7.4).

**Why there is no "known asthma" node.** `HISTORY_CHIPS` on disk (`ConsultationScreen.kt:98-101`)
carries `"Asthma / COPD"`, `"Heart disease"` and `"TB (past or current)"`; the shared coded
`relevant_history` field prefills from it. Reuse before authoring — tree memo §5 note 1.

### 4.5 Stage D/E

```
BRE-09  fieldId: smoking_biomass_exposure   answerType: MULTI_CHOICE   (shared with `cough`; options as §1.5)
Q: "Is the patient around any of these regularly?"
  (all options → BRE-S1)

BRE-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → BRE-G3, then BRE-END

BRE-END TerminalNode
```

### 4.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried |
|---|---|---|---|
| **GW-BRE-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | respiratory failure, severe pneumonia, severe anaemia, sepsis |
| **GW-BRE-EMG-2 (respiratory failure)** | `anyOf[ danger_signs contains ds_cannot_speak_sentence, danger_signs contains ds_blue_lips, danger_signs contains ds_chest_indrawing, breathlessness_present equals bp_at_rest ]` | `raisesTo: REFER_EMERGENCY`, **continue** — no Tier-0 target (**G-1**) | pneumonia, severe asthma, COPD exacerbation, respiratory failure |
| **GW-BRE-EMG-3 (anaphylaxis)** | `danger_signs contains ds_swollen_face` | `raisesTo: REFER_EMERGENCY`, continue — **no Tier-0 target for non-sting anaphylaxis (G-1)** | anaphylaxis, angio-oedema |
| **GW-BRE-EMG-4 (pulmonary oedema)** | `anyOf[ danger_signs contains ds_frothy_sputum, allOf[ danger_signs contains ds_cannot_lie_flat, ankle_swelling equals as_both_legs ] ]` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**) | acute heart failure, pulmonary oedema |
| **GW-BRE-EMG-5 (suspected embolism)** | `allOf[ ankle_swelling equals as_one_leg, anyOf[ breathlessness_present equals bp_at_rest, chest_pain_present equals cp_with_breathing ] ]` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**) | pulmonary embolism, deep vein thrombosis |
| **GW-BRE-EMG-6** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign |
| **GW-BRE-URG-1 (severe anaemia)** | `danger_signs contains ds_severe_pallor` | **informational** at floor; carries the flag to the physician | severe anaemia requiring transfusion |
| **GW-BRE-URG-2 (heart failure)** | `anyOf[ orthopnoea in {op_needs_pillows, op_wakes_gasping, op_both}, ankle_swelling equals as_both_legs ]` | **informational** at floor | heart failure, cor pulmonale, renal failure |
| **GW-BRE-URG-3 (TB / chronic)** | `anyOf[ cough_character equals cc_blood, allOf[ duration_bucket in {month_plus, chronic}, smoking_biomass_exposure contains sb_occupational ] ]` | **informational** at floor | pulmonary tuberculosis, silicosis, lung malignancy |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

Note that this branch has **six** emergency gateways and **five of them have no Tier-0 routing
target.** `breathlessness` is the branch where **G-1** bites hardest: a patient too breathless to
finish a sentence gets `REFER_EMERGENCY` and still has a differential ranked for them, which is
precisely the reading RF-1 exists to prevent.

### 4.7 Worked BranchOutput — 58-year-old woman, breathless on exertion for 2 weeks, orthopnoea, both ankles swollen

```
BranchOutput {
  categoryId: "breathlessness"
  branchVersion: "1.0.0-draft"
  pathTaken: [BRE-00, BRE-01, BRE-02, BRE-03, BRE-04, BRE-G2, BRE-05, BRE-06, BRE-07,
              BRE-G2, BRE-08, BRE-09, BRE-S1, FQ-00, BRE-G3, BRE-END]
  fields: {
    danger_signs             { ANSWERED, [ds_cannot_lie_flat],  TAP }
    progression              { ANSWERED, pr_worse,              TAP }
    prior_treatment_taken    { ANSWERED, [pt_none],             TAP }
    breathlessness_present   { ANSWERED, bp_on_exertion,        VOICE_CONFIRMED }
    orthopnoea               { ANSWERED, op_both,               TAP }
    wheeze                   { ANSWERED, wh_no,                 TAP }
    chest_pain_present       { ANSWERED, cp_no,                 TAP }
    ankle_swelling           { ANSWERED, as_both_legs,          TAP }
    cough_character          { ANSWERED, cc_dry,                TAP }
    smoking_biomass_exposure { ANSWERED, [sb_chulha],           TAP }
    fever_present            { ANSWERED, fp_no,                 TAP }
    fever_duration_band      { NOT_ASKED, null, — }
    fever_pattern            { NOT_ASKED, null, — }
    bleeding_manifestation   { NOT_ASKED, null, — }
    duration_bucket          { ANSWERED, week_plus,             PREFILL_MEASURED }
    onset_pattern            { ANSWERED, gradual,               TAP }
    severity_score           { ANSWERED, 6,                     TAP }
    severity_band            { ANSWERED, moderate,              DERIVED }
    relevant_history         { ANSWERED, [hypertension],        TAP }
    impact_on_daily_activities { ANSWERED, [cannot_do_household_chores], TAP }
  }
  dispositionFloor: REFER_EMERGENCY
  firedGateways: [
    { GW-BRE-EMG-4,
      "danger_signs contains ds_cannot_lie_flat AND ankle_swelling==as_both_legs",
      [acute heart failure, pulmonary oedema] },
    { GW-BRE-URG-2,
      "orthopnoea in {op_needs_pillows,op_wakes_gasping,op_both} OR ankle_swelling==as_both_legs",
      [heart failure, cor pulmonale, renal failure] }
  ]
  routedTo: null
  attachments: []
}
```

The floor went `REFER_URGENT` → `REFER_EMERGENCY` by `max()` and nothing afterwards — not the
classifier, not a confident benign ranking — can bring it back down. `GW-BRE-URG-2` fired below the
floor and wrote nothing, contributing only its `firedGateways` entry. **And this is the row that
makes G-1 concrete: `routedTo` is null, so the classifier will be called and will rank a
differential for a patient the branch has already flagged as an emergency.**

### 4.8 Node budget

Authored non-gateway nodes: **12** (BRE-00…BRE-09, BRE-S1, BRE-END). At the ceiling. Expanded worst
case: 12 + 4 = 16.

---

## 5. Branch: `chest_pain`

### 5.1 Category constants — category memo §7 Chapter K

```
categoryId               = "chest_pain"
displayName              = "Chest pain"
tier                     = CORE
icpc3Chapter             = K
icpc3Basis               = K01, K02
icd10Mapping             = R07.4
anchor                   = { value: top-4 individual complaint at Odisha PHC,
                             sourceType: IND-PRESENT, source: Gupta P et al. PLOS GPH 2024 }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT          // entry floor is already URGENT
severeConditions         = [ myocardial infarction, unstable angina, pulmonary embolism,
                             pneumothorax, aortic dissection, pericarditis ]
branchVersion            = 1.0.0-draft
```

> **Grounding warning, repeated here because it applies to this branch in full.** §0.2 measured
> every chest-pain term in the corpus against the current label space and found no signal, because
> that label space contains no cardiac class at all. Every qualifier below is authored from clinical
> protocol, not from a measurement, and every answer-model entry the generator declares against this
> branch is `ASSUMED` until a physician signs it. **G-11.**

### 5.2 Stage A — CPN-00

```
CPN-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_sweating_with_pain "Cold sweat with the pain"                        → CPN-G1
  ds_radiating          "Pain spreading to the arm, neck or jaw"          → CPN-G1
  ds_pain_at_rest       "The pain started at rest and is still there now" → CPN-G1
  ds_breathless_at_rest "Breathless at rest along with the pain"          → CPN-G1
  ds_collapse           "Collapsed or fainted"                            → CPN-G1
  ds_tearing_to_back    "Sudden tearing pain going through to the back"   → CPN-G1
  ds_vomiting_with_pain "Vomiting with the pain"                          → CPN-G1
  ds_unconscious        "Unconscious, or very drowsy / hard to wake"      → CPN-G1
  ds_none               "None of these"                                   → CPN-01
  ds_unknown            "Not known"                                       → CPN-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Myocardial infarction | `ds_sweating_with_pain`, `ds_radiating`, `ds_vomiting_with_pain`, `ds_pain_at_rest`, plus CPN-04 duration | covered |
| Unstable angina | `ds_pain_at_rest`, plus CPN-05/CPN-06 | covered |
| Pulmonary embolism | `ds_breathless_at_rest`, plus CPN-07 pleuritic and CPN-09 unilateral ankle swelling | covered |
| Pneumothorax | `ds_breathless_at_rest` + `pain_character_chest == pc_sharp_on_breathing` | covered as a pattern; **not separable from PE at PHC without imaging — accepted, both route the same way** |
| Pericarditis | CPN-07 postural relation + FEVER-QUAL | covered |
| **Aortic dissection** | `ds_tearing_to_back` **only, and it is a symptom report, not an observation** | **G-2 gap.** The reliable bedside signs are an inter-arm blood-pressure difference and unequal pulses, neither of which the vitals path measures and neither of which a community worker is trained to elicit. **Physician review required** on whether an inter-arm BP check belongs in the vitals protocol for this category |

`ds_sweating_with_pain` and `ds_vomiting_with_pain` are Stage A options rather than Stage C fields
deliberately: they are part of the ACS pattern and their whole value is firing before the worker
answers five characterisation questions. Tree memo §2.5's `sweating_with_pain` is therefore retired
as a separate field (§7.4).

### 5.3 Stage B

`CPN-01 progression`, `CPN-02 prior_treatment_taken`, verbatim as §1.3, `next` → CPN-03, CPN-04.

### 5.4 Stage C — the category qualifiers

```
CPN-03  fieldId: pain_character_chest   answerType: SINGLE_CHOICE
Q: "What does the pain feel like?"
  pc_heavy_pressing  "Heavy, pressing or squeezing"        → CPN-04
                     valueToken: "chest heaviness"
  pc_tight_band      "Tight, like a band around the chest" → CPN-04
                     valueToken: "chest tightness"
  pc_sharp_on_breathing "Sharp, and worse on breathing in" → CPN-G2
                     valueToken: "chest pain on breathing"
  pc_burning         "Burning"                             → CPN-04
                     valueToken: "burning chest pain"
  pc_tender_to_press "Sore, and tender when pressed"       → CPN-04
                     valueToken: "chest wall tenderness"
  pc_unknown         "Not known"                           → CPN-04
                     valueToken: "chest discomfort"

CPN-04  fieldId: duration_minutes_band   answerType: SINGLE_CHOICE
Q: "How long does one episode of the pain last?"
  dm_lt_2min        "Less than 2 minutes"                          → CPN-05
  dm_2_20min        "2 to 20 minutes, then settles"                → CPN-05
  dm_gt_20_ongoing  "More than 20 minutes, and it is still there"  → CPN-G1
  dm_hours_days     "Hours or days, more or less constant"         → CPN-05
  dm_unknown        "Not known"                                    → CPN-05

CPN-05  fieldId: exertional_relation   answerType: SINGLE_CHOICE   (shared with `acidity_heartburn`)
Q: "What brings the pain on?"
  er_on_exertion   "Walking, climbing or working brings it on"   → CPN-06
  er_at_rest       "It comes on at rest, with nothing to bring it on" → CPN-G1
  er_no_relation   "No pattern to it"                            → CPN-06
  er_unknown       "Not known"                                   → CPN-06

CPN-06  fieldId: relieved_by_rest   answerType: SINGLE_CHOICE
Q: "Does the pain settle if the patient stops and rests?"
  rr_within_minutes "Yes — settles within a few minutes of stopping" → CPN-07
  rr_partly         "Settles a little, but does not go"             → CPN-07
  rr_no             "No — resting makes no difference"              → CPN-G1
  rr_unknown        "Not known"                                     → CPN-07

CPN-07  fieldId: postural_relation   answerType: SINGLE_CHOICE
Q: "Does the pain change with position or with breathing?"
  po_worse_lying_flat  "Worse lying flat"                      → CPN-G2
  po_better_sitting_forward "Better sitting up and leaning forward" → CPN-G2
  po_worse_on_breathing "Worse on taking a deep breath"        → CPN-G2
  po_worse_on_movement "Worse on moving or turning"            → CPN-08
  po_no_change         "No change with position or breathing"  → CPN-08
  po_unknown           "Not known"                             → CPN-08

CPN-08  fieldId: breathlessness_present   answerType: SINGLE_CHOICE   (shared; options as §1.4)
Q: "Is the patient short of breath with the pain?"
  bp_at_rest → CPN-G2 · bp_on_exertion → CPN-09 · bp_no → CPN-09 · bp_unknown → CPN-09

CPN-09  fieldId: ankle_swelling   answerType: SINGLE_CHOICE   (shared with `breathlessness`; options as §4.4)
Q: "Is there swelling of the feet or ankles?"
  as_both_legs → CPN-G2 · as_one_leg → CPN-G2 · as_no → CPN-S1 · as_unknown → CPN-S1
```

`exertional_relation` and `relieved_by_rest` are **two fields, not one**, because they are two axes:
what brings the pain on, and what makes it go. `er_on_exertion` + `rr_within_minutes` is the stable
pattern; `er_at_rest` + `rr_no` is the unstable one. Collapsing them would lose the distinction that
separates a referral from an emergency.

**Why `radiation_arm_jaw` (tree memo §2.5) is not a Stage C node.** Radiation to arm or jaw is
already `danger_signs.ds_radiating`, asked at Stage A where it fires the ACS gateway immediately
rather than five nodes later. Retaining a second Stage C field for the *site* of radiation would be
a second column for the same fact, and the site does not change the disposition. Retired (§7.4).

**Why `relation_to_food` is not asked in this branch.** It is tree memo §2.5's field for
`acidity_heartburn` and it stays there. Asking a chest-pain patient whether the pain relates to food
invites the answer to be read as a benign explanation for a presentation whose whole safety posture
is that the device may never imply a cardiac cause is absent (RF-1 / NR-1). Reflux-versus-cardiac is
settled at physician review, and this branch's floor is `REFER_URGENT` either way. Not retired —
scoped to `acidity_heartburn` (batch 2).

### 5.5 Stage E — cross-links

```
CPN-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → CPN-G3, then CPN-END

CPN-END TerminalNode
```

Stage D is **empty** for this category. There is no exposure or occupational field that moves the
cardiac differential at PHC; `relevant_history` (prefilled, carrying `"Heart disease"`,
`"Diabetes"`, `"Hypertension"`, `"Tobacco / alcohol use"` from `HISTORY_CHIPS` on disk) is the risk-
factor channel and needs no node. §6.1 permits Stage D at 1–2 nodes; zero is a deviation and it is
declared here rather than padded.

### 5.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried |
|---|---|---|---|
| **GW-CPN-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | cardiac arrest, cardiogenic shock, massive PE |
| **GW-CPN-EMG-2 (ACS)** | `anyOf[ danger_signs contains ds_sweating_with_pain, danger_signs contains ds_radiating, danger_signs contains ds_pain_at_rest, danger_signs contains ds_collapse, danger_signs contains ds_vomiting_with_pain, allOf[ duration_minutes_band equals dm_gt_20_ongoing, relieved_by_rest equals rr_no ], allOf[ exertional_relation equals er_at_rest, relieved_by_rest equals rr_no ] ]` | `raisesTo: REFER_EMERGENCY`, **continue** — no Tier-0 target (**G-1**) | **myocardial infarction, unstable angina** |
| **GW-CPN-EMG-3 (dissection)** | `danger_signs contains ds_tearing_to_back` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**) | aortic dissection |
| **GW-CPN-EMG-4 (embolism / pneumothorax)** | `anyOf[ danger_signs contains ds_breathless_at_rest, breathlessness_present equals bp_at_rest, allOf[ pain_character_chest equals pc_sharp_on_breathing, ankle_swelling equals as_one_leg ] ]` | `raisesTo: REFER_EMERGENCY`, continue — no Tier-0 target (**G-1**) | pulmonary embolism, pneumothorax |
| **GW-CPN-URG-1 (stable pattern)** | `allOf[ exertional_relation equals er_on_exertion, relieved_by_rest equals rr_within_minutes ]` | **informational** at floor | stable angina, ischaemic heart disease |
| **GW-CPN-URG-2 (pericarditis)** | `allOf[ postural_relation in {po_worse_lying_flat, po_better_sitting_forward}, fever_present equals fp_yes ]` | **informational** at floor | pericarditis, myocarditis |
| **GW-CPN-URG-3 (heart failure)** | `ankle_swelling equals as_both_legs` | **informational** at floor | heart failure |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

**GW-CPN-URG-1 is the gateway most at risk of being misread, and it is deliberately informational.**
A stable exertional pattern relieved by rest is the *least* alarming chest pain in the set — and it
still leaves the floor at `REFER_URGENT`, still carries `[stable angina, ischaemic heart disease]`
into `firedGateways`, and still renders nothing reassuring anywhere. There is no rule in this branch
that can lower a floor, and there is no option text that says a benign thing about a severe
condition. That is RF-1 and RF-2 in the one place the temptation to violate them is strongest.

### 5.7 Worked BranchOutput — 54-year-old man, 40 minutes of central pressing pain, sweating, not settling

```
BranchOutput {
  categoryId: "chest_pain"
  branchVersion: "1.0.0-draft"
  pathTaken: [CPN-00, CPN-G1, CPN-01, CPN-02, CPN-03, CPN-04, CPN-G1, CPN-05, CPN-06,
              CPN-G1, CPN-07, CPN-08, CPN-09, CPN-S1, FQ-00, CPN-G3, CPN-END]
  fields: {
    danger_signs           { ANSWERED, [ds_sweating_with_pain, ds_radiating], TAP }
    progression            { ANSWERED, pr_worse,             TAP }
    prior_treatment_taken  { ANSWERED, [pt_none],            TAP }
    pain_character_chest   { ANSWERED, pc_heavy_pressing,    VOICE_CONFIRMED }
    duration_minutes_band  { ANSWERED, dm_gt_20_ongoing,     TAP }
    exertional_relation    { ANSWERED, er_at_rest,           TAP }
    relieved_by_rest       { ANSWERED, rr_no,                TAP }
    postural_relation      { ANSWERED, po_no_change,         TAP }
    breathlessness_present { ANSWERED, bp_at_rest,           TAP }
    ankle_swelling         { ANSWERED, as_no,                TAP }
    fever_present          { ANSWERED, fp_no,                TAP }
    fever_duration_band    { NOT_ASKED, null, — }
    fever_pattern          { NOT_ASKED, null, — }
    bleeding_manifestation { NOT_ASKED, null, — }
    duration_bucket        { ANSWERED, today,                PREFILL_MEASURED }
    onset_pattern          { ANSWERED, sudden,               TAP }
    severity_score         { ANSWERED, 9,                    TAP }
    severity_band          { ANSWERED, severe,               DERIVED }
    relevant_history       { ANSWERED, [hypertension, tobacco_alcohol_use], TAP }
    impact_on_daily_activities { ANSWERED, [bedridden],      TAP }
  }
  dispositionFloor: REFER_EMERGENCY
  firedGateways: [
    { GW-CPN-EMG-2,
      "danger_signs contains ds_sweating_with_pain OR ds_radiating OR
       (duration_minutes_band==dm_gt_20_ongoing AND relieved_by_rest==rr_no) OR
       (exertional_relation==er_at_rest AND relieved_by_rest==rr_no)",
      [myocardial infarction, unstable angina] },
    { GW-CPN-EMG-4,
      "breathlessness_present==bp_at_rest",
      [pulmonary embolism, pneumothorax] }
  ]
  routedTo: null
  attachments: []
}
```

This is the single clearest case for **G-1**. Four independent clauses of the ACS gateway fired, the
floor is `REFER_EMERGENCY`, and `routedTo` is still null — so the classifier is called, and a device
whose label space contains no cardiac class at all will rank *something* for this patient. Either a
Tier-0 cardiac emergency category has to exist, or a `REFER_EMERGENCY` floor has to force abstention.
Batch 1 should not ship until one of the two is decided.

### 5.8 Node budget

Authored non-gateway nodes: **12** (CPN-00…CPN-09, CPN-S1, CPN-END). At the ceiling. Expanded worst
case: 12 + 4 = 16.

---

## 6. The shared sub-trees this batch introduces

Authored **once**, referenced by `SubtreeRef`, never copied (tree memo §5 note 1). Each is a fixed
DAG with one entry and one return edge, so determinism is unaffected.

### 6.1 `TB-SCREEN` — new, v1.0.0-draft

The four national TB screening questions as one reusable unit. Referenced by `cough` (this batch),
and by `weight_loss`, `weakness_unwell` and `fever` in later batches — `fever` currently asks all
four inline (FV-08, FV-09, FV-10's `ex_tb_contact`), which is the duplication **G-10** flags for
reconciliation at `fever` 1.0.1.

```
TBS-01  fieldId: cough_ge_2_weeks   SINGLE   (shared, §2.4)
Q: "Has the cough been there for two weeks or more?"
  c2_yes "Yes" → TBS-02 · c2_no "No" → TBS-02 · c2_unknown "Not known" → TBS-02

TBS-02  fieldId: weight_loss_present   SINGLE   (shared, §2.4)
Q: "Has the patient lost weight without trying to?"
  wl_yes "Yes" → TBS-03 · wl_no "No" → TBS-03 · wl_unknown "Not known" → TBS-03

TBS-03  fieldId: night_sweats   SINGLE   (shared, §2.4)
Q: "Does the patient sweat at night, enough to wet the clothes or the bedding?"
  ns_yes "Yes" → TBS-04 · ns_no "No" → TBS-04 · ns_unknown "Not known" → TBS-04

TBS-04  fieldId: tb_contact   SINGLE   (shared)
Q: "Is anyone in the house or at work being treated for TB?"
  tbc_yes "Yes" → TBS-RETURN · tbc_no "No" → TBS-RETURN · tbc_unknown "Not known" → TBS-RETURN

TBS-RETURN
```

No gateway inside — see §6.4. The referencing branch evaluates the TB gateway after return.

### 6.2 `DEHYDRATION` — new, v1.0.0-draft

The four WHO IMCI dehydration assessment signs plus urine output. Basis: **WORLD** (WHO IMCI), named
at point of use per the category memo §2 discipline; no Indian substitute exists and IMCI is what
Indian PHC staff are trained on. Referenced by `diarrhoea` (this batch) and by `vomiting_nausea` in
a later batch.

```
DEH-01  fieldId: dehydration_thirst   SINGLE
Q: "Offer the patient water. How do they drink?"
  dt_normal  "Drinks normally"                    → DEH-02
  dt_eager   "Thirsty, drinks eagerly"            → DEH-02
  dt_poor    "Drinks poorly"                      → DEH-G1
  dt_unable  "Not able to drink at all"           → DEH-G1
  dt_unknown "Not known"                          → DEH-02

DEH-02  fieldId: dehydration_eyes   SINGLE
Q: "Do the eyes look sunken?"
help: "Ask the mother or family whether the eyes look different from usual."
  de_yes "Yes" → DEH-03 · de_no "No" → DEH-03 · de_unknown "Not known" → DEH-03

DEH-03  fieldId: skin_pinch   SINGLE
Q: "Pinch the skin of the abdomen between thumb and finger, then let go. How fast does it go back?"
  sp_immediate "Goes back at once"                 → DEH-04
  sp_slow      "Goes back slowly"                  → DEH-04
  sp_very_slow "Takes more than 2 seconds"         → DEH-G1
  sp_unknown   "Not tested"                        → DEH-04

DEH-04  fieldId: urine_output_reduced   SINGLE   (shared, §2.4)
Q: "When did the patient last pass urine, and how much?"
  uo_normal               "Passing urine as usual"          → DEH-05
  uo_reduced              "Less than usual"                 → DEH-05
  uo_none_since_yesterday "Has not passed urine since yesterday" → DEH-G1
  uo_unknown              "Not known"                       → DEH-05

DEH-05  fieldId: general_condition   SINGLE
Q: "How is the patient behaving?"
  gc_alert     "Alert and behaving normally"       → DEH-RETURN
  gc_restless  "Restless or irritable"             → DEH-RETURN
  gc_lethargic "Sleepy, difficult to wake"         → DEH-G1
  gc_unknown   "Not known"                         → DEH-RETURN

DEH-G1  GatewayNode  raisesTo: REFER_URGENT, continue     (see §6.4 — sub-tree gateways raise only)
        severeConditions: [dehydration]
        next → whichever DEH node the entering option declared

DEH-RETURN
```

**This sub-tree supersedes tree memo §2.5's single `dehydration_signs` field for `diarrhoea`, and
the reason is NR-1.** "Dehydration signs" as a multi-select asks a worker to record a *classification*.
Each of the five fields above records an *observation* — how the patient drinks, how the skin
behaves, when they last passed urine. The corpus agrees that the collapsed form carries nothing:
`dehydration signs` measures at n=20, top label E66, 40% (§0.2). `dehydration_signs` is retired (§7.4).

### 6.3 `FEVER-QUAL` v1.1 — amendment to an existing sub-tree

Tree memo §5 defines `FEVER-QUAL` as FV-01, FV-02, FV-06 (fever duration, fever pattern, bleeding),
with the referencing branch asking `fever_present` separately as its own node (`rash` RSH-02).

**Amendment: `FEVER-QUAL` v1.1 absorbs `fever_present` as its gate node.**

```
FQ-00  fieldId: fever_present   SINGLE   (shared, §2.4)
Q: "Is there fever with this?"
  fp_yes     "Yes"       → FQ-01
  fp_no      "No"        → FQ-RETURN     // FQ-01..03 remain NOT_ASKED
  fp_unknown "Not known" → FQ-RETURN     // FQ-01..03 remain NOT_ASKED

FQ-01  fieldId: fever_duration_band   SINGLE   (FV-01's node and option set, unchanged)
FQ-02  fieldId: fever_pattern         SINGLE   (FV-02's node and option set, unchanged)
FQ-03  fieldId: bleeding_manifestation MULTI   (FV-06's node and option set, unchanged)
FQ-RETURN
```

**Why.** Every branch in this batch needs `fever_present`, and under the v1.0 definition each would
spend two of its twelve nodes (the field, then the SubtreeRef) on one clinical question. Absorbing
the gate saves a node in every referencing branch and puts one fact in one place.

**Impact on `rash`, stated because it is a change to an authored branch.** `rash` 1.0.1 replaces
RSH-02 (`fever_present`) plus RSH-S1 with a single `SubtreeRef → FEVER-QUAL v1.1`, losing one node
and changing no `fieldId` and no `optionId`. Under R4 that is a `branchVersion` bump **without**
corpus regeneration, because the model's input contract is byte-identical. It is a change to a
physician-reviewed artifact and needs the physician's signature, not just this memo's.

### 6.4 One schema question this batch had to answer, and how

**May a gateway inside a shared sub-tree route-and-terminate the referencing branch?** The schema
(tree memo §1.2, §1.6) does not say. It matters immediately: `DEH-05`'s `gc_lethargic` is an IMCI
general danger sign, and the natural rule would route to `emergency_unconscious`.

**Resolved conservatively, and the resolution is a proposal, not a fact.** In this batch, a gateway
inside a shared sub-tree may only **raise-and-continue**. Every route-and-terminate gateway lives in
the referencing branch and reads the sub-tree's fields after return — which is why `GW-DIA-EMG-3`
and `GW-DIA-EMG-5` sit at `DIA-G3` rather than inside `DEHYDRATION`.

Reason: a sub-tree is referenced by several branches, and a routing gateway inside it would
terminate a branch whose author never saw the rule. Making the referencing branch own its routing
keeps every `routeTo` visible to the physician reviewing that branch. Registered as **G-5** for the
schema owner to confirm or overrule.

---

## 7. Registry additions — so batch 2 reuses these rather than duplicating them

Everything in this section is an R4 change: it adds, renames or retires a `fieldId` or an
`optionId`, so it bumps `branch_version`, changes the dataset column list, and is a design change
under normal change control.

### 7.1 New shared sub-trees

| Sub-tree | Version | Nodes | Fields | Referenced by (this batch) | Expected later reuse |
|---|---|---|---|---|---|
| `TB-SCREEN` | 1.0.0-draft | 4 | `cough_ge_2_weeks`, `weight_loss_present`, `night_sweats`, `tb_contact` | `cough` | `weight_loss`, `weakness_unwell`, `fever` (1.0.1 reconciliation) |
| `DEHYDRATION` | 1.0.0-draft | 5 | `dehydration_thirst`, `dehydration_eyes`, `skin_pinch`, `urine_output_reduced`, `general_condition` | `diarrhoea` | `vomiting_nausea`, `fever` (under-5 paths) |
| `FEVER-QUAL` | **1.1.0-draft** (amended) | 4 | + `fever_present` as gate; FV-01/02/06 unchanged | `cough`, `diarrhoea`, `abdominal_pain`, `breathlessness`, `chest_pain` | `joint_pain`, `urinary_symptoms`, `body_ache`, `rash` (1.0.1) |

### 7.2 New shared `fieldId`s — one clinical fact, one column, every branch

| fieldId | Type | Options | Introduced for | Reuse expected in |
|---|---|---|---|---|
| `chest_pain_present` | SINGLE | `cp_with_breathing` · `cp_other` · `cp_no` · `cp_unknown` | `cough`, `breathlessness` | `acidity_heartburn`, `oedema`, `known_diabetes` |
| `vomiting_present` | SINGLE | `vm_yes_keeping_fluids` · `vm_yes_everything` · `vm_no` · `vm_unknown` | `diarrhoea`, `abdominal_pain` | `vomiting_nausea`, `headache`, `fever` (1.0.1) |
| `cough_character` | SINGLE | `cc_dry` · `cc_productive` · `cc_blood` · `cc_paroxysmal` · `cc_none` · `cc_unknown` | `cough`, `breathlessness` | `cold_sore_throat`, `weight_loss` |
| `wheeze` | SINGLE | `wh_audible` · `wh_reported` · `wh_no` · `wh_unknown` | `cough`, `breathlessness` | `cold_sore_throat` |
| `smoking_biomass_exposure` | MULTI | `sb_tobacco_current` · `sb_tobacco_past` · `sb_chulha` · `sb_occupational` · `sb_secondhand` · `sb_none` · `sb_unknown` | `cough`, `breathlessness` | `chest_pain` (deferred), `weight_loss` |
| `ankle_swelling` | SINGLE | `as_both_legs` · `as_one_leg` · `as_no` · `as_unknown` | `breathlessness`, `chest_pain` | `oedema`, `known_hypertension` |
| `blood_in_stool` | SINGLE | `bs_frank` · `bs_streaks` · `bs_black` · `bs_no` · `bs_unknown` | `diarrhoea` | `pallor_anaemia`, `abdominal_pain`, `acidity_heartburn` |
| `burning_on_urination` | SINGLE | `bu_yes` · `bu_no` · `bu_unknown` | `abdominal_pain` | `urinary_symptoms`, `fever` |
| `exertional_relation` | SINGLE | `er_on_exertion` · `er_at_rest` · `er_no_relation` · `er_unknown` | `chest_pain` | `acidity_heartburn` |
| `lmp_known` | SINGLE | `lmp_within_4wk` · `lmp_late_or_missed` · `lmp_gt_3_months` · `lmp_not_applicable` · `lmp_unknown` | `abdominal_pain` | `vomiting_nausea`, `antenatal_visit` |
| `tb_contact` | SINGLE | `tbc_yes` · `tbc_no` · `tbc_unknown` | TB-SCREEN | every TB-SCREEN referrer |
| `general_condition` | SINGLE | `gc_alert` · `gc_restless` · `gc_lethargic` · `gc_unknown` | DEHYDRATION | `vomiting_nausea`, `fever` |
| `dehydration_thirst` | SINGLE | `dt_normal` · `dt_eager` · `dt_poor` · `dt_unable` · `dt_unknown` | DEHYDRATION | as above |
| `dehydration_eyes` | SINGLE | `de_yes` · `de_no` · `de_unknown` | DEHYDRATION | as above |
| `skin_pinch` | SINGLE | `sp_immediate` · `sp_slow` · `sp_very_slow` · `sp_unknown` | DEHYDRATION | as above |
| `household_others_affected` | SINGLE | `ho_yes` · `ho_no` · `ho_unknown` | **existing** (`rash` RSH-08) — reused unchanged by `diarrhoea` | `itching`, `skin_infection` |

`breathlessness_present`'s option set is **pinned** here as `bp_at_rest` · `bp_on_exertion` · `bp_no`
· `bp_unknown`. Tree memo §2.4 types it SINGLE without enumerating; the three-level form is
required because "at rest" is a gateway trigger in three branches of this batch and because
`breathlessness on exertion` is a distinct trained token (§0.2).

### 7.3 New branch-specific `fieldId`s

| Branch | fieldIds |
|---|---|
| `cough` | none — every field is shared or sub-tree |
| `diarrhoea` | `stool_frequency_band` · `stool_consistency` · `water_source` |
| `abdominal_pain` | `pain_site` · `pain_radiation` · `pain_migration` · `bowels_open` |
| `breathlessness` | `orthopnoea` |
| `chest_pain` | `pain_character_chest` · `duration_minutes_band` · `relieved_by_rest` · `postural_relation` |

### 7.4 Retired from tree memo §2.5, each with its reason

| Retired fieldId | Branch | Superseded by | Reason |
|---|---|---|---|
| `sputum_blood` | `cough` | `cough_character.cc_blood` + `danger_signs.ds_large_haemoptysis` | two columns for one fact; volume is the danger sign |
| `chest_pain_with_cough` | `cough` | shared `chest_pain_present` | same fact, two names |
| `chest_pain_with_breathlessness` | `breathlessness` | shared `chest_pain_present` | same fact, two names |
| `breathlessness_trigger` | `breathlessness` | shared `breathlessness_present` + `orthopnoea` | same axis, third column |
| `vomiting_with_diarrhoea` | `diarrhoea` | shared `vomiting_present` | same fact, two names |
| `vomiting_with_pain` | `abdominal_pain` | shared `vomiting_present` | same fact, two names |
| `dehydration_signs` | `diarrhoea` | `DEHYDRATION` sub-tree (5 observation fields) | a classification, not an observation — NR-1; and measures at 40% purity on n=20 |
| `abdomen_rigid` | `abdominal_pain` | `danger_signs.ds_abdomen_rigid` | belongs at Stage A where it fires early |
| `pain_character` | `abdominal_pain` | shared `onset_pattern` (`intermittent / episodic`) | colicky-versus-constant already captured on disk |
| `radiation_arm_jaw` | `chest_pain` | `danger_signs.ds_radiating` | Stage A fires early; radiation *site* does not change disposition |
| `sweating_with_pain` | `chest_pain` | `danger_signs.ds_sweating_with_pain` | part of the ACS pattern; belongs at Stage A |
| `mucus_in_stool` | `diarrhoea` | **deferred, not superseded** | node budget; see **G-13** |

### 7.5 Dataset column impact

Net new column families for the dataset step (each `<fieldId>` + `<fieldId>__status`; MULTI expands
to one boolean per `optionId` plus one `__status`):

- **16 shared** (§7.2), of which one (`household_others_affected`) already exists from `rash`
- **12 branch-specific** (§7.3)
- **12 retired** (§7.4), which never reach the corpus because they were never generated

Two of the new shared fields are MULTI and expand: `smoking_biomass_exposure` (7 booleans + status),
`prior_treatment_taken` (already counted in tree memo §7.2). Approximate net width added by batch 1:
**≈ 40 columns**, against tree memo §7.2's ≈ 400-column projection for the full tree. The five
branches also emit `path_taken`, `fired_gateways`, `disposition_floor`, `routed_to` — audit columns,
excluded from the feature set per §7.4 rule 3.

---

## 8. Conformance — C1–C5, BR-1, BR-2, NR-1, and the six replay assertions

The argument is the same for all five branches, so it is made once and then evidenced per branch.

**C1 (deterministic, rule-based).** Every option in every node above carries an explicit `next` to a
named nodeId. No node's successor is computed, no option list is generated, no ordering is chosen at
runtime, and the SLM appears nowhere in the branching logic — it is confined to tree memo §1.7's two
adapters (render a fixed string; map a spoken answer onto this node's fixed `optionId` set by
constrained decoding). No branch in this batch asks the SLM to choose a next node or to generate a
question.

**C2 (the tree narrows; it does not diagnose).** No `BranchOutput` in §1.8, §2.8, §3.7, §4.7 or §5.7
contains a score, a probability, a rank or a condition name. `severeConditions[]` appears only inside
`firedGateways`, and only as the list the *category* declared — carried forward for RF-1 enforcement
downstream, never as a claim about this patient.

**C3 / RF-2 (floor, never lowered).** Every branch initialises `dispositionFloor` to its category's
`requiredDisposition` (`PHYSICIAN_REVIEW_MANDATORY` for `cough` and `diarrhoea`; `REFER_URGENT` for
`abdominal_pain`, `breathlessness` and `chest_pain`) and every gateway effect above is either
`raisesTo` under `max()` or `routeTo`. No rule in this batch lowers a floor; no lowering operator is
used because the schema exposes none.

**C4 (Tier-0 routing).** Every `routeTo` in this batch names one of `emergency_convulsions`,
`emergency_unconscious`, `emergency_heavy_bleeding` — all Tier-0 `ALWAYS_ABSTAIN` (category memo §6)
— and every one terminates its branch. **No gateway routes to a category that does not exist.** The
cost of that discipline is **G-1**: five emergency conditions in this batch have no valid Tier-0
target, so they raise-and-continue rather than routing, and this memo declines to invent a target.

**C5 (the physician gate stays terminal).** Nothing in this memo touches
`SubmitDoctorDecisionUseCase` or the AGREE/MODIFY/REJECT path.

**BR-1 (explicit successor).** Every option above states its `next`, including the uniform cases
written as `(all options → X)`.

**BR-2 (no absent field).** Every SINGLE_CHOICE node above carries a terminal `*_unknown` option;
every MULTI_CHOICE node carries both `*_none` / `*_no*` and `*_unknown`. Checked node by node:
`danger_signs` (5 branches), `cough_character`, `wheeze`, `breathlessness_present`,
`chest_pain_present`, `smoking_biomass_exposure`, `progression`, `prior_treatment_taken`,
`stool_frequency_band`, `stool_consistency`, `blood_in_stool`, `vomiting_present`, `water_source`,
`household_others_affected`, `pain_site`, `pain_radiation`, `pain_migration`, `bowels_open`,
`burning_on_urination`, `lmp_known`, `orthopnoea`, `ankle_swelling`, `pain_character_chest`,
`duration_minutes_band`, `exertional_relation`, `relieved_by_rest`, `postural_relation`, and all
nine sub-tree nodes. **All 37 conform.**

**NR-1 (no severe condition in an excluding frame).** No `questionText`, `helpText` or `displayText`
in this batch names any listed severe condition at all — not in a positive frame, not in a negative
one. The nearest approaches are `"No rash seen"`-shaped observations: `bs_no` "No blood seen",
`cp_no` "No", `as_no` "No", `po_no_change` "No change with position or breathing". Each records what
was observed; none records what was excluded. Three deliberate authoring decisions exist only to
protect this invariant: `sc_rice_water` is worded as an appearance with `help` text rather than as a
cholera question; the `DEHYDRATION` sub-tree replaces a "dehydration signs" checklist with five
observations; and `relation_to_food` is kept out of `chest_pain` so no answer can read as a benign
explanation for a cardiac presentation.

**The six replay assertions (tree memo §1.9).**

| Assertion | How each branch satisfies it | Where it could fail |
|---|---|---|
| 1. Reachability | Every `next` above names a node declared in the same branch or a registered sub-tree; every node is on at least one path | `FEVER-QUAL` v1.1 must exist before any of these five load (§6.3) |
| 2. Determinism | No model in the branching loop; identical answer sequence yields byte-identical `BranchOutput` including `pathTaken` | intact |
| 3. Field completeness | Every conditional skip above is a deterministic function of prior answers: `FQ-01..03` on `fever_present`, `ABD-09` on `sex`+`age_band`, `DEH-*` reached only via `DIA-S1` | intact |
| 4. Monotonicity | `max()` only; terminal floor ≥ category `requiredDisposition` on every path, and for the three `REFER_URGENT` categories that lower bound is the entry value | intact |
| 5. Routing | The three `routeTo` targets used are Tier-0 `ALWAYS_ABSTAIN` and terminate | intact — **but see G-1 for the emergencies that could not route at all** |
| 6. NR-1 lint | No authored string matches any `severeConditions[]` entry of its category | intact; the lint itself is still deferred (tree memo §1.8) |

**Per-branch evidence cell.**

| Branch | Entry floor | routeTo targets used | Highest floor reachable | Nodes | NR-1 nearest approach |
|---|---|---|---|---|---|
| `cough` | PHYSICIAN_REVIEW_MANDATORY | convulsions, unconscious, heavy_bleeding | REFER_EMERGENCY | 11 | `cc_blood` (observation) |
| `diarrhoea` | PHYSICIAN_REVIEW_MANDATORY | convulsions, unconscious | REFER_EMERGENCY | 12 | `sc_rice_water` (appearance) |
| `abdominal_pain` | REFER_URGENT | unconscious, heavy_bleeding | REFER_EMERGENCY | 12 | `bs_no` "No blood seen" |
| `breathlessness` | REFER_URGENT | unconscious | REFER_EMERGENCY | 12 | `as_no` "No" |
| `chest_pain` | REFER_URGENT | unconscious | REFER_EMERGENCY | 12 | `po_no_change` |

---

## 9. Flagged for physician and operator review — findings, not guesses

Ordered by consequence. **G-1 blocks the batch.**

### G-1 — SAFETY, BLOCKING. Five emergency presentations in this batch have no Tier-0 routing target, so they raise `REFER_EMERGENCY` and are still sent to the classifier

Tier 0 is fixed at six categories (category memo §6): convulsions, unconscious, bite/sting,
poisoning, heavy bleeding, pregnancy danger. This batch produces `REFER_EMERGENCY` for five
presentations that match none of them:

| Presentation | Branches | Gateways | Current behaviour |
|---|---|---|---|
| Respiratory failure / severe pneumonia / severe asthma | `cough`, `breathlessness` | GW-CGH-EMG-4, GW-BRE-EMG-2 | raise, continue, **classifier still called** |
| Acute abdomen / perforation / obstruction | `abdominal_pain`, `diarrhoea` | GW-ABD-EMG-3, GW-DIA-EMG-4 | same |
| Acute coronary syndrome / aortic dissection | `chest_pain` | GW-CPN-EMG-2, GW-CPN-EMG-3 | same |
| Pulmonary embolism / pneumothorax | `chest_pain`, `breathlessness` | GW-CPN-EMG-4, GW-BRE-EMG-5 | same |
| Anaphylaxis (drug- or food-triggered) | `breathlessness` | GW-BRE-EMG-3 | same — `emergency_bite_sting` covers stings only |
| Severe dehydration / hypovolaemic shock | `diarrhoea` | GW-DIA-EMG-3 | same |

This is tree memo §3.5's open question arriving with consequences. §5.7's worked example is the
clearest statement of it: four ACS gateway clauses fire, the floor is `REFER_EMERGENCY`, `routedTo`
is null, and a model whose label space contains no cardiac class ranks a differential anyway.

Two resolutions, and the choice is the operator's:

- **(a) Add Tier-0 categories.** Candidates: `emergency_breathing_difficulty`,
  `emergency_severe_abdominal_pain`, `emergency_chest_pain`. Each would be `ALWAYS_ABSTAIN` with the
  §6.4 minimal-protocol shape. Cost: three new categories in the registry, the dropdown, the label
  space and the target marginal.
- **(b) Resolve tree memo §3.5 by making a `REFER_EMERGENCY` floor force abstention**, in the tree
  as it already does on the vitals channel (dataset memo §6.1 Path 1 forces abstention). Cost: a
  suppression rule inside the tree, which §3.5 argues belongs to Layer 3.

**This memo recommends (b) as the smaller change and the one already precedented on the vitals side,
but does not decide it.** Batch 1 should not ship until one is chosen.

### G-2 — Severe conditions with no clean worker-observable danger sign

Each is a category-level gap, not an authoring omission. None was papered over with an invented sign.

| Category | Severe condition | Gap |
|---|---|---|
| `chest_pain` | **aortic dissection** | The reliable bedside signs are an inter-arm BP difference and unequal pulses. Neither is in the vitals protocol, neither is a community-worker skill. `ds_tearing_to_back` is a symptom report, not an observation. **Ask: should an inter-arm BP check enter the vitals protocol for this category?** |
| `abdominal_pain` | **pancreatitis** | No Stage A sign. Reached only through `ds_pain_severe_sudden` / `ds_vomiting_everything`, both of which it may lack, plus the Stage C radiation-to-back qualifier |
| `cough` | **lung malignancy** | Only `ds_large_haemoptysis`, a late sign. Early disease reaches URGENT only through duration and weight loss |
| `cough` | **COPD** vs asthma | Not separable at Stage A; both present as `ds_cannot_speak_sentence` |
| `breathlessness` | **severe asthma (silent chest)** | Requires auscultation. The near-fatal attack is the one with *less* audible wheeze, and `wh_no` cannot distinguish it from no bronchospasm |
| `breathlessness` | **anaphylaxis** | `ds_swollen_face` is a good sign, but there is no Tier-0 target (see G-1) |
| `diarrhoea` | **cholera** | No sign distinct from severe dehydration; reached only via `sc_rice_water` plus frequency plus the dehydration gateways |

### G-3 — `severity_band` cut-points are undefined anywhere in the schema

Tree memo §2.3 declares `severity_band` as a "deterministic banding of `severity_score`" with values
`mild` / `moderate` / `severe`, and never states the cut-points. `GW-ABD-URG-2` references it and is
therefore unevaluable as written. Proposed **`ASSUMED`**: 0–3 mild, 4–6 moderate, 7–10 severe.
Needs a physician's signature before any gateway keys off it, and once signed it belongs in the
schema, not in a branch.

### G-4 — The gateway rule grammar cannot express "N or more of these signs"

Tree memo §1.6's grammar is `anyOf` / `allOf` over comparison clauses. WHO IMCI's severe-dehydration
classification is "two or more of four signs", which this batch encodes as six explicit `allOf`
pairs under one `anyOf` (GW-DIA-EMG-3). That is exact and enumerable, and it is verbose enough that
the next protocol rule of this shape will be written wrong. **Schema request:** add
`atLeastNOf { n, clauses[] }`. It stays deterministic, stays enumerable, and stays readable by the
physician who authored the rule — which is the stated design goal of the grammar.

### G-5 — The schema does not say whether a sub-tree gateway may route-and-terminate the parent

Resolved conservatively for this batch (§6.4): sub-tree gateways raise only; routing lives in the
referencing branch. Needs the schema owner's confirmation or overrule before batch 2 authors more
sub-trees.

### G-6 — The node ceiling is ambiguous: authored nodes, or the expanded path?

§6.1 sets a hard ceiling of 12 excluding gateway nodes. Counting **authored** nodes, all five
branches conform (11, 12, 12, 12, 12). Counting the **expanded** path a worker actually walks —
sub-trees included — they are 18, 21, 16, 16, 16, and so is the `fever` reference branch at ~20 on
the rash path. Under the expansion reading, `SubtreeRef` is a ceiling-evasion mechanism. Under the
authored reading, the ceiling does not measure what it was written to protect (the worker's third
screen). **Both numbers are stated per branch above so the choice can be made on evidence.** This
memo does not choose.

### G-7 — The `fever` reference branch exceeds its own stated ceiling

`fever` (tree memo §3) authors FV-00…FV-12 plus FV-S1 plus FV-END = **15 nodes**, against §6.1's
ceiling of 12 excluding gateways. Either the ceiling or the reference branch needs correcting. Batch
1 held to 12 on the authored count; batch 2 needs the answer before it starts.

### G-8 — The `fever` worked example emits `progression` from a node that does not exist

Tree memo §3.6's `BranchOutput` shows `progression { ANSWERED, worse, TAP }`, but the `fever` branch
has no `progression` node and §2.3 marks the field **NEW** (nothing on disk captures it). Batch 1
authors an explicit Stage-B `progression` node in all five branches (§1.3). `fever` 1.0.1 should
either add one or drop the field from its output.

### G-9 — `optionId` prefix collision: `fp_*` means two different things

`fever_pattern` uses `fp_stepladder`/`fp_cyclical`/… (tree memo §3.3); `fever_present` uses
`fp_yes`/`fp_no`/`fp_unknown` (§5, RSH-02, and this batch's FQ-00). `optionId`s are scoped per
`fieldId` and dataset columns are `<fieldId>__<optionId>`, so there is **no column collision** — but
a gateway rule reading `fp_yes` next to one reading `fp_cyclical` is exactly the kind of thing a
reviewer misreads. Recommend renaming `fever_pattern`'s options to `fpat_*` at `fever` 1.0.1. An
`optionId`-only rename is an R4 change requiring regeneration of `fever` rows.

### G-10 — Same clinical fact, different fieldId, across §2.5's per-category lists

Resolved for this batch by promotion to shared fields (§7.2, §7.4): `chest_pain_with_cough` and
`chest_pain_with_breathlessness` → `chest_pain_present`; `vomiting_with_diarrhoea` and
`vomiting_with_pain` → `vomiting_present`. **One is left unresolved:** `fever`'s
`exposure_context.ex_tb_contact` and this batch's `tb_contact` record the same fact in two
incompatible shapes (a MULTI option versus a SINGLE field). `GW-FEV-URG-1` and `GW-CGH-URG-1` read
different columns for one question. Reconcile at `fever` 1.0.1, ideally by having `fever` reference
`TB-SCREEN` instead of asking FV-08/FV-09/FV-10 inline.

### G-11 — There is no measured grounding for `chest_pain` or for dehydration, and there cannot be

§0.2. The current label space contains **no cardiac class of any kind**, so every chest-pain token
resolves to the obesity or osteoarthritis prior at n ≤ 34. `dehydration signs` measures 40% purity
on n=20. Consequence: every qualifier in the `chest_pain` branch and every field in the
`DEHYDRATION` sub-tree is grounded on clinical protocol alone, every answer-model entry the
generator declares against them is `ASSUMED` under dataset memo §2.3, and **no number in those two
places may be presented as measured.** They also cannot be validated against the existing corpus —
only against the regenerated one, and only once a physician has signed the answer model.

### G-12 — Moderate anaemia has no node in `breathlessness`

Severe anaemia is in the category's `severeConditions` and MP's NFHS-5 rates are 54.7% of women and
72.7% of under-5s. The branch carries `ds_severe_pallor` at Stage A (IMCI severe palmar pallor) and
nothing for the moderate case, which presents exactly as exertional breathlessness. **Proposal for
batch 2:** promote `pallor_site_observed` (currently `pallor_anaemia`-specific, tree memo §2.5) to a
shared field asked in `breathlessness`, `dizziness` and `weakness_unwell`. Not done here — it is a
registry decision that belongs with the `pallor_anaemia` branch.

### G-13 — `mucus_in_stool` deferred from `diarrhoea` for node budget

Tree memo §2.5 lists it; §2.9 has zero headroom at the ceiling. `blood_in_stool` carries the
dysentery gateway and covers the blood-and-mucus combination of amoebic colitis, but mucus without
blood is unrecorded. **Physician review:** is mucus-alone worth a node, and if so which of
`water_source` or `household_others_affected` yields it? Resolving **G-6** may make the question moot.

---

## 10. Boundaries observed

No source, generator, dataset, model, config, questionnaire or tree runtime file was written, edited
or deleted in either repo. The SaMDClassifier repo was **read only** — one CSV read for the §0.2
measurement pass; `git status --short` there is **empty** and HEAD is unchanged at `63e85af`. No
`.env`, `local.properties` or credential file was opened at any point. No commit, no staging. No
branch was authored in code, no dataset generated, no model trained. The only file written is this
memo.

Batch 1 is `cough`, `diarrhoea`, `abdominal_pain`, `breathlessness`, `chest_pain`. Nothing beyond
those five was authored, and the §6.5 order continues with `joint_pain` → `back_neck_pain` →
`known_diabetes` → `headache` → `injury` at batch 2, which should not start until **G-1**, **G-6**
and **G-7** are decided.
