# Branch authoring — Batch 3 (STEP 1, read-only design memo)

Run date: 2026-09-07. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af` — **read only.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
generator, dataset, model, config or tree runtime file touched, in either repo. No branch authored
in code, no dataset generated, no model trained. No `.env`, `local.properties` or credential file
opened at any point. No commit, no staging.

**Inputs treated as binding and not re-derived:**
`scratchpad/branch-authoring-batch-1-memo.md` (the reference structure, shared registry §7,
sub-tree definitions §6, findings G-1…G-13), `scratchpad/branch-authoring-batch-2-memo.md`
(registry additions §8, findings G-14…G-15, fever 1.0.1 amendment, DECISION-1/DECISION-2),
`scratchpad/questionnaire-tree-design-memo.md` (the schema: §1 branch schema, §6 five-stage
template, §3/§4/§5 the three reference branches), `scratchpad/reason-for-encounter-category-system-memo.md`
(every category constant transcribed from its §7; RF-1…RF-4 from its §4.4),
`scratchpad/dataset-regeneration-design-memo.md` (what the generator walks).

**Also read on disk this session, read-only:**
`SaMDClassifier/dataset/canonical_dataset.csv` (one measurement pass, §0.2).

**Scope.** Batch 3 is the next seven from tree memo §6.5's authoring order: `vomiting_nausea`,
`acidity_heartburn`, `urinary_symptoms`, `cold_sore_throat`, `weakness_unwell`, `body_ache`,
`weight_loss`. Plus the G-15 amendment (`visit_reason_subtype` BR-2 fix). Nothing beyond these
is authored here.

---

## 0. Operator decisions applied, and the measurement pass

### 0.1 Decisions carried forward, settled, not re-opened

**DECISION-1.** `REFER_EMERGENCY` floor forces abstention on any channel; every
REFER_EMERGENCY-reaching path emits `abstain: true`, classifier not called.

**DECISION-2.** Node ceiling is 20, expanded path including sub-trees. Report per branch.

**G-14 resolved.** `fever` is the single accepted exception. Not re-raised.

**G-15 resolved.** Add `vr_unknown "Not known"` to `visit_reason_subtype`. Authored as §1 below.

### 0.2 The measurement pass

One read-only pass over `canonical_dataset.csv` (15,105 labelled rows), extending the batch-1
and batch-2 measurements to this batch's terms:

```
term                                       n      top label   purity   notes
vomiting                                  363      A09         48%     noise — A09/B54 split
nausea                                    987      I10         33%     noise — no nausea class
loose stools                              221      A09         93%     strong: gastroenteritis
body ache                                 174      B54         94%     strong: malaria
severe body ache                          174      A90         87%     strong: dengue
burning micturition                       246      N39.0       91%     strong: UTI
frequent urination                         87      E11         90%     strong: diabetes
weight loss                                92      A15         86%     strong: TB
unexplained weight loss                   177      E11         49%     noise — E11/E05.9 split
night sweats                              102      A15         82%     strong: TB
loss of appetite                          452      A09         35%     noise
fatigue                                  2802      E66         77%     noise — BMI prior artifact
weakness                                  165      E66         76%     noise
tiredness                                 189      E66         76%     noise
lethargy                                  209      E66         77%     noise
retro-orbital pain                        183      A90         90%     strong: dengue
```

**Five readings for this batch.**

**First: `vomiting_nausea` is noise in isolation.** Bare `vomiting` → A09 48% / B54 44%, bare
`nausea` → I10 33%. Neither separates. This branch depends entirely on its cross-links
(DEHYDRATION, FEVER-QUAL, `lmp_known`) and its danger signs for the clinical-safety path. The
discriminative signal lives in the co-occurring fever pattern, not in the vomiting itself.

**Second: `acidity_heartburn` has zero corpus coverage.** No term matching acidity, heartburn,
acid reflux, epigastric, dysphagia, or belch appears anywhere in the 118-term symptom pool or the
15,105 rows. This category is **entirely absent from the corpus** — same situation as `chest_pain`,
`back_neck_pain`, and `injury` (batch-1 G-11, batch-2 G-11 ext). Every qualifier is
protocol-grounded and `ASSUMED`.

**Third: `urinary_symptoms` has one strong signal.** `burning micturition` → N39.0 91% on 246
rows is a clear UTI signal. `frequent urination` → E11 90% on 87 rows is diabetes, not UTI.
No other urinary term appears. The branch's discriminative power rests on the `burning_on_urination`
qualifier.

**Fourth: `cold_sore_throat` has zero corpus coverage.** No throat, sore throat, nasal, sneezing,
runny nose, cold (as URTI), ear pain, or related term appears. Zero rows. Entirely `ASSUMED`.

**Fifth: `weakness_unwell` is noise by design, and `weight_loss` carries TB signal.** `weakness`,
`tiredness`, `fatigue`, `lethargy` all resolve to the E66/BMI prior at 76-77% — the same artifact
that dominates everywhere. `weakness_unwell` is the residual general bucket; its value is in its
cross-links and abstention discipline, not in its own terms. `weight_loss` → A15 86% on 92 rows
is a strong TB signal; `night_sweats` → A15 82% on 102 rows confirms. `body_ache` → B54 94% and
`severe body ache` → A90 87% are the strongest signals in this batch, carrying malaria and dengue
respectively.

---

## 1. G-15 amendment — `visit_reason_subtype` BR-2 fix

**BR-2 requires every SINGLE_CHOICE node to carry a `*_unknown` option.** `visit_reason_subtype`
(used by `known_hypertension` HTN-01 and `known_diabetes` DM-03) has four options and no unknown.

**Amendment.** Add:

```
vr_unknown  "Not known"  → (same next as other options)
```

This applies to `known_hypertension` HTN-01 (tree memo §4) and `known_diabetes` DM-03 (batch 2
§4.4). Both branches bump `branchVersion` from `1.0.0-draft` to `1.0.1-draft`.

**R4 impact.** One `optionId` added → one new dataset column (`visit_reason_subtype__vr_unknown`).
Requires regeneration of both `known_hypertension` and `known_diabetes` rows. No existing
`fieldId`, `optionId`, gateway rule, or routing changes — this is a pure addition.

---

## 2. Branch: `vomiting_nausea`

### 2.1 Category constants — transcribed from category memo §7 Chapter D

```
categoryId               = "vomiting_nausea"
displayName              = "Vomiting or nausea"
tier                     = CORE
icpc3Chapter             = D
icpc3Basis               = D09, D10
icd10Mapping             = R11
anchor                   = { value: ~12% of chapter D ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ dehydration, obstruction, raised intracranial pressure,
                             DKA, pregnancy (hyperemesis / ectopic), poisoning ]
branchVersion            = 1.0.0-draft
```

### 2.2 Stage A — VMT-00

```
VMT-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_vomiting_blood    "Vomiting blood or dark material like coffee grounds"  → VMT-G1
  ds_vomiting_every    "Cannot keep any fluids down at all"                   → VMT-G1
  ds_abdomen_rigid     "Abdomen is hard and very tender"                      → VMT-G1
  ds_confusion         "Confused, or behaviour unusual"                       → VMT-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake"          → VMT-G1
  ds_convulsion        "Fits or convulsions"                                  → VMT-G1
  ds_severe_headache   "Severe headache that came on suddenly"               → VMT-G1
  ds_cannot_feed       "Unable to drink or feed"                             → VMT-G1
  ds_none              "None of these"                                       → VMT-01
  ds_unknown           "Not known"                                           → VMT-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Dehydration | `ds_vomiting_every`, `ds_cannot_feed` + DEHYDRATION sub-tree (VMT-S1) | covered |
| Obstruction (bowel) | `ds_abdomen_rigid`, `ds_vomiting_every` + bowels_open (VMT-07) | covered as a pattern |
| Raised intracranial pressure | `ds_severe_headache`, `ds_confusion`, `ds_convulsion` | covered — the classic signs |
| DKA | `ds_vomiting_every`, `ds_confusion` + relevant_history (diabetes) | covered as a pattern; **no specific Stage A sign distinguishes DKA-driven vomiting from other causes — G-2 gap. Reached through GW-VMT-URG-2 when diabetes is in history** |
| Pregnancy (hyperemesis/ectopic) | `ds_vomiting_every` + lmp_known (VMT-08) | covered via lmp gate |
| Poisoning | *none specific in Stage A* | **G-2 gap. No worker-observable sign distinguishes poisoning-related vomiting at Stage A; the only route is the VMT-06 `poisoning_suspected` question. Physician review required** |

### 2.3 Stage B

`VMT-01 progression`, `VMT-02 prior_treatment_taken`, as batch 1 §1.3. → VMT-03, VMT-04.

### 2.4 Stage C — the category qualifiers

```
VMT-03  fieldId: vomit_frequency_band   answerType: SINGLE_CHOICE
Q: "How many times has the patient vomited today?"
  vf_1_3       "1 to 3 times"                  → VMT-04
  vf_4_10      "4 to 10 times"                 → VMT-04
  vf_gt_10     "More than 10 times"            → VMT-G2
  vf_unknown   "Not known"                     → VMT-04

VMT-04  fieldId: vomit_content   answerType: SINGLE_CHOICE
Q: "What does the vomit look like?"
  vc_food       "Food only"                           → VMT-05
  vc_bile       "Green or yellow (bile)"               → VMT-G2
  vc_blood      "Blood or dark material"               → VMT-G1
  vc_clear      "Clear fluid or water"                 → VMT-05
  vc_unknown    "Not known"                            → VMT-05

VMT-05  fieldId: vomiting_present   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
        **SKIP: auto-set to vm_yes_everything or vm_yes_keeping_fluids based on danger_signs**
        This is a sentinel node: its value is deterministically derived from ds_vomiting_every.
        If ds_vomiting_every was selected → vm_yes_everything.
        Else → vm_yes_keeping_fluids.
        The node is NOT shown to the worker; it exists to populate the shared fieldId for
        cross-branch consistency. All paths → VMT-06.

VMT-06  fieldId: poisoning_suspected   answerType: SINGLE_CHOICE
Q: "Could the patient have swallowed or been exposed to something harmful?"
help: "Ask about medicines, pesticides, cleaning products, or poisonous plants."
  ps_medicine   "Yes — too many tablets or wrong medicine"  → VMT-G1
  ps_pesticide  "Yes — pesticide, rat poison, or chemical"  → VMT-G1
  ps_other      "Yes — something else"                     → VMT-G1
  ps_no         "No"                                       → VMT-07
  ps_unknown    "Not known"                                → VMT-07

VMT-07  fieldId: bowels_open   answerType: SINGLE_CHOICE   (shared, batch 1 §7.3)
Q: "Has the patient passed stool today?"
  bo_normal    "Yes — normal stool"             → VMT-08
  bo_loose     "Yes — loose stool"              → VMT-08
  bo_nothing   "No — nothing passed"            → VMT-G2
  bo_unknown   "Not known"                      → VMT-08
```

`bowels_open` is reused from `abdominal_pain` (batch 1 §3.4 ABD-08). Same `fieldId`, same
`optionId`s. `bo_nothing` in combination with bile vomiting raises the obstruction gateway.

### 2.5 Stage D/E

```
VMT-08  fieldId: lmp_known   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
        asked only when sex == F and age_band ∈ [15,49]; else NOT_ASKED
Q: "When was the last menstrual period?"
  lmp_within_4wk · lmp_late_or_missed · lmp_gt_3_months · lmp_not_applicable · lmp_unknown
  → VMT-S1

VMT-S1  SubtreeRef → DEHYDRATION   (batch 1 §6.2)
        on return → VMT-G3, then VMT-S2

VMT-S2  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → VMT-G4, then VMT-END

VMT-END TerminalNode
```

### 2.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-VMT-EMG-1** | `danger_signs contains ds_convulsion` | `routeTo: emergency_convulsions`, terminate | eclampsia, meningitis, cerebral malaria | N/A — Tier-0 |
| **GW-VMT-EMG-2** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | DKA, poisoning, raised ICP | N/A — Tier-0 |
| **GW-VMT-EMG-3 (poisoning)** | `poisoning_suspected in {ps_medicine, ps_pesticide, ps_other}` | `routeTo: emergency_poisoning`, terminate | **poisoning** | N/A — Tier-0 |
| **GW-VMT-EMG-4 (GI bleed)** | `anyOf[ danger_signs contains ds_vomiting_blood, vomit_content equals vc_blood ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **upper GI haemorrhage**, variceal bleeding | **Yes — abstains** |
| **GW-VMT-EMG-5 (raised ICP)** | `allOf[ danger_signs contains ds_severe_headache, danger_signs contains ds_confusion ]` | `raisesTo: REFER_EMERGENCY`, continue | **raised intracranial pressure**, stroke, meningitis | **Yes — abstains** |
| **GW-VMT-EMG-6** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-VMT-URG-1 (obstruction)** | `anyOf[ allOf[ vomit_content equals vc_bile, bowels_open equals bo_nothing ], allOf[ danger_signs contains ds_abdomen_rigid, danger_signs contains ds_vomiting_every ] ]` | `raisesTo: REFER_URGENT` | bowel obstruction, strangulation |
| **GW-VMT-URG-2 (DKA in known diabetic)** | `allOf[ relevant_history contains diabetes, danger_signs contains ds_vomiting_every ]` | `raisesTo: REFER_URGENT` | DKA |
| **GW-VMT-URG-3 (pregnancy)** | `allOf[ lmp_known in {lmp_late_or_missed, lmp_gt_3_months}, anyOf[ danger_signs contains ds_vomiting_every, vomit_frequency_band equals vf_gt_10 ] ]` | `raisesTo: REFER_URGENT` | hyperemesis gravidarum, ectopic pregnancy |
| **GW-VMT-URG-4 (dehydration)** | DEHYDRATION sub-tree raises internally; branch evaluates: `anyOf[ dehydration_thirst in {dt_poor, dt_unable}, skin_pinch equals sp_very_slow, urine_output_reduced equals uo_none_since_yesterday, general_condition equals gc_lethargic ]` | `raisesTo: REFER_URGENT` | dehydration |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `VMT-G1` after VMT-00 evaluates EMG-1…EMG-6; `VMT-G2` evaluates URG-1, URG-2
as fields land; `VMT-G3` after DEHYDRATION evaluates URG-4; `VMT-G4` after FEVER-QUAL evaluates
URG-3 (pregnancy gate depends on lmp_known from VMT-08).

### 2.7 Worked BranchOutput — 25-year-old woman, 2 days of persistent vomiting, missed period, dehydrated

Path: vomiting everything → worse → none → 4-10 times → food → (auto) vm_yes_everything →
no poisoning → normal stool → LMP late → DEHYDRATION (eager, no sunken eyes, slow pinch,
reduced urine, alert) → no fever.

```
BranchOutput {
  categoryId: "vomiting_nausea"
  branchVersion: "1.0.0-draft"
  pathTaken: [VMT-00, VMT-G1, VMT-01, VMT-02, VMT-03, VMT-04, VMT-05, VMT-06,
              VMT-07, VMT-08, VMT-S1, DEH-01, DEH-02, DEH-03, DEH-G1, DEH-04,
              DEH-05, DEH-RETURN, VMT-G3, VMT-S2, FQ-00, VMT-G4, VMT-END]
  fields: {
    danger_signs            { ANSWERED, [ds_vomiting_every],     TAP }
    progression             { ANSWERED, pr_worse,                TAP }
    prior_treatment_taken   { ANSWERED, [pt_none],               TAP }
    vomit_frequency_band    { ANSWERED, vf_4_10,                 TAP }
    vomit_content           { ANSWERED, vc_food,                 TAP }
    vomiting_present        { ANSWERED, vm_yes_everything,       DERIVED }
    poisoning_suspected     { ANSWERED, ps_no,                   TAP }
    bowels_open             { ANSWERED, bo_normal,               TAP }
    lmp_known               { ANSWERED, lmp_late_or_missed,      TAP }
    dehydration_thirst      { ANSWERED, dt_eager,                TAP }
    dehydration_eyes        { ANSWERED, de_no,                   TAP }
    skin_pinch              { ANSWERED, sp_slow,                 TAP }
    urine_output_reduced    { ANSWERED, uo_reduced,              TAP }
    general_condition       { ANSWERED, gc_alert,                TAP }
    fever_present           { ANSWERED, fp_no,                   TAP }
    fever_duration_band     { NOT_ASKED, null, — }
    fever_pattern           { NOT_ASKED, null, — }
    bleeding_manifestation  { NOT_ASKED, null, — }
    duration_bucket         { ANSWERED, few_days,                PREFILL_MEASURED }
    onset_pattern           { ANSWERED, acute_1_3d,              TAP }
    severity_score          { ANSWERED, 6,                       TAP }
    severity_band           { ANSWERED, moderate,                DERIVED }
    relevant_history        { ANSWERED, [no_known_history],      TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-VMT-URG-3,
      "lmp_known==lmp_late_or_missed AND danger_signs contains ds_vomiting_every",
      [hyperemesis gravidarum, ectopic pregnancy] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

### 2.8 Node budget

Authored non-gateway: **12** (VMT-00…VMT-08, VMT-S1, VMT-S2, VMT-END).
Expanded worst case (DEHYDRATION 5 + FEVER-QUAL gate-only 1): 12 + 5 + 1 = **18**.
Full FEVER-QUAL: 12 + 5 + 4 = **21**. **Exceeds the DECISION-2 ceiling of 20 by 1 on the
full FEVER-QUAL path.**

> **G-16 — `vomiting_nausea` exceeds 20 on the DEHYDRATION + full FEVER-QUAL path.** The combined
> worst case is 21. Resolution options: (a) drop one Stage C node (e.g. merge
> `poisoning_suspected` into danger signs); (b) accept the 21 given that both sub-trees firing
> on the same path is the low-frequency case (a vomiting patient who is dehydrated AND has
> fever with rash). **Flagged for operator; not resolved here.** All other paths fit within 20.

### 2.9 Conformance

| Check | Status |
|---|---|
| C1 | Every option has explicit `next`. No SLM in branching. `vomiting_present` is DERIVED, not model-driven |
| C2 | No score, rank, or condition name in BranchOutput |
| C3/RF-2 | Floor starts PHYSICIAN_REVIEW_MANDATORY, raised only by `max()` |
| C4 | `routeTo` targets: convulsions, unconscious, poisoning — all Tier-0, ALWAYS_ABSTAIN |
| C5 | Physician gate untouched |
| BR-1 | All options have explicit successor |
| BR-2 | All SINGLE nodes carry `*_unknown`; MULTI nodes carry `*_none`+`*_unknown` |
| NR-1 | No severe condition named in any frame. `ds_vomiting_blood` is an observation; `vc_blood` is an appearance. "GI haemorrhage" appears only in gateway metadata, never in worker-facing text |
| DECISION-1 | GW-VMT-EMG-4, EMG-5, EMG-6 raise to REFER_EMERGENCY → abstain |
| DECISION-2 | See G-16. Most paths: **18** ≤ 20 ✔. Full FEVER-QUAL + DEHYDRATION: **21** |

---

## 3. Branch: `acidity_heartburn`

### 3.1 Category constants — category memo §7 Chapter D

```
categoryId               = "acidity_heartburn"
displayName              = "Acidity, heartburn or gas"
tier                     = CORE
icpc3Chapter             = D
icpc3Basis               = D03, D08
icd10Mapping             = R12, K30
anchor                   = { value: AIIMS Bhopal oesophagus/stomach/duodenum disease 6.8%,
                             sourceType: IND-PRESENT, MP, 3rd-commonest diagnosis }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ cardiac pain presenting as acidity, peptic ulcer bleeding,
                             gastric malignancy ]
branchVersion            = 1.0.0-draft
```

> **Grounding warning.** The corpus returns **nothing** for any acidity, heartburn, acid reflux,
> epigastric, dysphagia, or belch term (§0.2). This category is entirely absent from the 118-term
> symptom pool. Every qualifier below is protocol-grounded and `ASSUMED`. **G-11 extended.**

> **SAFETY NOTE.** This is the RF-1 case the `chest_pain` branch flagged (batch 1 G-2): cardiac
> pain masquerades as "acidity" — epigastric burning or heaviness relieved by antacids is
> indistinguishable from angina at a community worker's assessment level. The Stage A danger signs
> and the `exertional_relation` qualifier (Stage C) must carry the cardiac-mimic gateway seriously.
> This branch's single most important safety question is "Does it come with exertion?"

### 3.2 Stage A — ACH-00

```
ACH-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_chest_pain_exertion "Pain or heaviness in the chest that comes with walking or effort"  → ACH-G1
  ds_sweating_with_pain  "Sweating with the chest pain or heaviness"                         → ACH-G1
  ds_vomiting_blood      "Vomiting blood or dark material like coffee grounds"               → ACH-G1
  ds_black_tarry_stool   "Black or tarry stool"                                              → ACH-G1
  ds_cannot_swallow      "Cannot swallow even liquids"                                       → ACH-G1
  ds_weight_loss_marked  "Noticed weight loss over weeks"                                    → ACH-G1
  ds_unconscious         "Unconscious, or very drowsy / hard to wake"                        → ACH-G1
  ds_cannot_feed         "Unable to drink or feed"                                           → ACH-G1
  ds_none                "None of these"                                                     → ACH-01
  ds_unknown             "Not known"                                                         → ACH-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| **Cardiac pain presenting as acidity** | `ds_chest_pain_exertion`, `ds_sweating_with_pain` | covered — the exertional trigger and diaphoresis are the two strongest bedside discriminators. **The cardiac mimic is the defining risk of this category, and these two danger signs are the gateway that manages it** |
| Peptic ulcer bleeding | `ds_vomiting_blood`, `ds_black_tarry_stool` | covered — haematemesis and melaena |
| Gastric malignancy | `ds_weight_loss_marked`, `ds_cannot_swallow` | covered for late-stage; **early gastric cancer has no worker-observable sign — G-2 gap. Reached only through duration + weight loss** |

### 3.3 Stage B

`ACH-01 progression`, `ACH-02 prior_treatment_taken`, as batch 1 §1.3. → ACH-03, ACH-04.

### 3.4 Stage C — the category qualifiers

```
ACH-03  fieldId: relation_to_food   answerType: SINGLE_CHOICE
Q: "Does the pain or burning change with eating?"
  rf_better_after_food  "Better after eating"                → ACH-04
  rf_worse_after_food   "Worse after eating"                 → ACH-04
  rf_empty_stomach      "Worse on empty stomach"             → ACH-04
  rf_no_relation        "No change with food"                → ACH-04
  rf_unknown            "Not known"                          → ACH-04

ACH-04  fieldId: exertional_relation   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Does the pain or heaviness come on with walking, climbing stairs, or physical effort?"
help: "This is the most important question in this branch. Pain that comes with effort and stops with rest may be cardiac."
  er_on_exertion  "Yes — comes with effort"          → ACH-G2
  er_at_rest      "Only at rest"                     → ACH-05
  er_no_relation  "No clear relation"                → ACH-05
  er_unknown      "Not known"                        → ACH-05

ACH-05  fieldId: night_symptoms   answerType: SINGLE_CHOICE
Q: "Does the burning or discomfort wake the patient at night?"
  ns_acid_yes   "Yes — acid or burning feeling at night"  → ACH-06
  ns_no         "No"                                       → ACH-06
  ns_unknown    "Not known"                                → ACH-06

ACH-06  fieldId: dysphagia   answerType: SINGLE_CHOICE
Q: "Does food get stuck going down?"
  dy_solids     "Yes — solid food gets stuck"         → ACH-G2
  dy_liquids    "Yes — even liquids are difficult"    → ACH-G1
  dy_no         "No difficulty swallowing"            → ACH-07
  dy_unknown    "Not known"                           → ACH-07

ACH-07  fieldId: nsaid_use   answerType: SINGLE_CHOICE
Q: "Has the patient been taking pain-killers regularly (like Diclofenac, Ibuprofen, Aspirin)?"
  nu_current    "Yes — currently taking"               → ACH-08
  nu_recent     "Took them recently but stopped"       → ACH-08
  nu_no         "No"                                   → ACH-08
  nu_unknown    "Not known"                            → ACH-08

ACH-08  fieldId: blood_in_stool   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Has there been any blood in the stool, or black stool?"
  bs_frank · bs_streaks · bs_black · bs_no · bs_unknown
  → ACH-S1
```

**Why `chest_pain_present` (shared) is not used here.** The cardiac-mimic question in this branch
is not "do you have chest pain" — the patient selected `acidity_heartburn` as their category, so
epigastric discomfort is assumed. The question is whether that discomfort is *exertional*, which
`exertional_relation` captures. `chest_pain_present` would ask "Is there chest pain?" to a patient
who came in for chest/epigastric burning, which is tautological. The cardiac signal lives in
`er_on_exertion`, not in `cp_yes`.

### 3.5 Stage D/E

```
ACH-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → ACH-G3, then ACH-END

ACH-END TerminalNode
```

No TB-SCREEN: TB does not present as acidity. No DEHYDRATION: vomiting is not the primary
complaint; if severe vomiting co-occurs, the worker should select `vomiting_nausea` instead.

### 3.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-ACH-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | MI, upper GI bleed with shock | N/A — Tier-0 |
| **GW-ACH-EMG-2 (cardiac)** | `anyOf[ allOf[ danger_signs contains ds_chest_pain_exertion, danger_signs contains ds_sweating_with_pain ], allOf[ exertional_relation equals er_on_exertion, danger_signs contains ds_sweating_with_pain ] ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **acute coronary syndrome** | **Yes — abstains** |
| **GW-ACH-EMG-3 (GI bleed)** | `anyOf[ danger_signs contains ds_vomiting_blood, blood_in_stool in {bs_frank, bs_black} ]` | `raisesTo: REFER_EMERGENCY`, continue | **upper GI haemorrhage**, variceal bleeding | **Yes — abstains** |
| **GW-ACH-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-ACH-URG-1 (cardiac mimic)** | `exertional_relation equals er_on_exertion` | `raisesTo: REFER_URGENT` | **cardiac pain presenting as acidity** — this is the RF-1 gateway |
| **GW-ACH-URG-2 (dysphagia)** | `dysphagia in {dy_solids, dy_liquids}` | `raisesTo: REFER_URGENT` | oesophageal stricture, gastric malignancy |
| **GW-ACH-URG-3 (alarm features)** | `anyOf[ danger_signs contains ds_weight_loss_marked, allOf[ nsaid_use equals nu_current, blood_in_stool in {bs_frank, bs_streaks, bs_black} ] ]` | `raisesTo: REFER_URGENT` | gastric malignancy, NSAID-related ulcer bleed |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `ACH-G1` after ACH-00 evaluates EMG-1…EMG-4; `ACH-G2` after ACH-04 evaluates
URG-1; further URG evaluations as fields land; `ACH-G3` after FEVER-QUAL for completeness.

**The cardiac gateway is deliberately aggressive.** `GW-ACH-URG-1` fires on `er_on_exertion`
alone — no second qualifier required. This is by design: in a category where the patient
self-selected "acidity", exertional symptoms are the single strongest red flag for misattributed
cardiac pain. The cost is over-referral; the alternative is a missed MI presenting as "gas".
**RF-1 makes the choice.** `GW-ACH-EMG-2` fires for the full ACS pattern (exertional +
diaphoresis) and forces abstention.

### 3.7 Worked BranchOutput — 50-year-old man, 1 week of epigastric burning, worse with walking, sweating

```
BranchOutput {
  categoryId: "acidity_heartburn"
  branchVersion: "1.0.0-draft"
  pathTaken: [ACH-00, ACH-G1, ACH-01, ACH-02, ACH-03, ACH-04, ACH-G2, ACH-05,
              ACH-06, ACH-07, ACH-08, ACH-S1, FQ-00, ACH-G3, ACH-END]
  fields: {
    danger_signs            { ANSWERED, [ds_chest_pain_exertion, ds_sweating_with_pain], TAP }
    progression             { ANSWERED, pr_worse,              TAP }
    prior_treatment_taken   { ANSWERED, [pt_pharmacy_medicine], TAP }
    relation_to_food        { ANSWERED, rf_no_relation,        TAP }
    exertional_relation     { ANSWERED, er_on_exertion,        TAP }
    night_symptoms          { ANSWERED, ns_no,                 TAP }
    dysphagia               { ANSWERED, dy_no,                 TAP }
    nsaid_use               { ANSWERED, nu_no,                 TAP }
    blood_in_stool          { ANSWERED, bs_no,                 TAP }
    fever_present           { ANSWERED, fp_no,                 TAP }
    fever_duration_band     { NOT_ASKED, null, — }
    fever_pattern           { NOT_ASKED, null, — }
    bleeding_manifestation  { NOT_ASKED, null, — }
    duration_bucket         { ANSWERED, week_plus,             PREFILL_MEASURED }
    onset_pattern           { ANSWERED, gradual,               TAP }
    severity_score          { ANSWERED, 6,                     TAP }
    severity_band           { ANSWERED, moderate,              DERIVED }
    relevant_history        { ANSWERED, [hypertension],        TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],   TAP }
  }
  dispositionFloor: REFER_EMERGENCY
  firedGateways: [
    { GW-ACH-EMG-2,
      "exertional_relation==er_on_exertion AND danger_signs contains ds_sweating_with_pain",
      [acute coronary syndrome] },
    { GW-ACH-URG-1,
      "exertional_relation==er_on_exertion",
      [cardiac pain presenting as acidity] }
  ]
  routedTo: null
  abstain: true
  attachments: []
}
```

Floor reached `REFER_EMERGENCY` via GW-ACH-EMG-2. **DECISION-1 applies: `abstain: true`, classifier
not called.** The device surfaces the emergency referral with no differential. The tree named no
disease; `ds_chest_pain_exertion` + `ds_sweating_with_pain` + `er_on_exertion` is the observation
record that triggers it.

### 3.8 Node budget

Authored non-gateway: **11** (ACH-00…ACH-08, ACH-S1, ACH-END).
Expanded worst case (FEVER-QUAL gate-only): 11 + 1 = **12**. Full FEVER-QUAL: 11 + 4 = **15**.
**Well under DECISION-2 ceiling.**

### 3.9 Conformance

| Check | Status |
|---|---|
| C1 | Every option has explicit `next`. No SLM in branching |
| C2 | No score, rank, or condition name in BranchOutput |
| C3/RF-2 | Floor starts PHYSICIAN_REVIEW_MANDATORY, raised only by `max()` |
| C4 | `routeTo: emergency_unconscious` is Tier-0, ALWAYS_ABSTAIN |
| C5 | Physician gate untouched |
| BR-1 | All options have explicit successor |
| BR-2 | All SINGLE nodes carry `*_unknown` |
| NR-1 | No severe condition named in any frame. `ds_chest_pain_exertion` is a symptom report. "ACS" and "cardiac" appear only in gateway metadata |
| DECISION-1 | GW-ACH-EMG-2, EMG-3, EMG-4 raise to REFER_EMERGENCY → abstain |
| DECISION-2 | Expanded worst case: **15** ≤ 20 ✔ |

---

## 4. Branch: `urinary_symptoms`

### 4.1 Category constants — category memo §7 Chapter U

```
categoryId               = "urinary_symptoms"
displayName              = "Burning or frequent urination"
tier                     = CORE
icpc3Chapter             = U
icpc3Basis               = U01, U02, U71
icd10Mapping             = R30.0, N39.0
anchor                   = { value: Odisha 0.4% (IND-PRESENT) / AIIMS Bhopal ~8.1% (IND-PRESENT, MP),
                             sourceType: IND-PRESENT, conflicting — reporting/stigma gap }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ pyelonephritis, urinary obstruction/stone, STI,
                             sepsis in the elderly or diabetic, UTI in pregnancy ]
branchVersion            = 1.0.0-draft
```

### 4.2 Stage A — URN-00

```
URN-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_cannot_pass_urine  "Has not been able to pass urine at all"             → URN-G1
  ds_high_fever_urine   "High fever with the urinary symptoms"               → URN-G1
  ds_severe_flank_pain  "Severe pain in the side or back going to the groin" → URN-G1
  ds_blood_in_urine     "Blood in the urine"                                → URN-G1
  ds_confusion          "Confused, or behaviour unusual"                     → URN-G1
  ds_unconscious        "Unconscious, or very drowsy / hard to wake"         → URN-G1
  ds_cannot_feed        "Unable to drink or feed"                            → URN-G1
  ds_none               "None of these"                                      → URN-01
  ds_unknown            "Not known"                                          → URN-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Pyelonephritis | `ds_high_fever_urine`, `ds_severe_flank_pain` | covered — loin pain + fever is the classic combination |
| Urinary obstruction/stone | `ds_cannot_pass_urine`, `ds_severe_flank_pain` | covered |
| STI | *none in Stage A* | **G-2 gap. STI has no single worker-observable sign at Stage A that distinguishes it from simple UTI. Reached through the urethral discharge question (URN-07). Physician review required** |
| Sepsis in elderly/diabetic | `ds_high_fever_urine`, `ds_confusion` + relevant_history (diabetes) | covered as a pattern |
| UTI in pregnancy | `ds_high_fever_urine` + pregnancy_status (URN-08) | covered via pregnancy gate |

### 4.3 Stage B

`URN-01 progression`, `URN-02 prior_treatment_taken`, as batch 1 §1.3. → URN-03, URN-04.

### 4.4 Stage C — the category qualifiers

```
URN-03  fieldId: burning_on_urination   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Does it burn or sting when passing urine?"
  bu_yes · bu_no · bu_unknown
  → URN-04

URN-04  fieldId: frequency_increased   answerType: SINGLE_CHOICE
Q: "Is the patient passing urine more often than usual?"
  fi_yes        "Yes — much more often"                → URN-05
                valueToken: "frequent urination"
  fi_slightly   "A little more often"                  → URN-05
  fi_no         "No"                                   → URN-05
  fi_unknown    "Not known"                            → URN-05

URN-05  fieldId: flank_pain   answerType: SINGLE_CHOICE
Q: "Is there pain in the side of the body, towards the back?"
help: "Press gently on the flanks to check for tenderness."
  fp_one_side   "Yes — one side"                       → URN-G2
                valueToken: "flank pain"
  fp_both       "Yes — both sides"                     → URN-G2
  fp_no         "No"                                   → URN-06
  fp_unknown    "Not known"                            → URN-06

URN-06  fieldId: haematuria   answerType: SINGLE_CHOICE
Q: "Has the urine been pink, red, or dark — like there might be blood?"
  hu_visible    "Yes — visible blood or colour"        → URN-G2
  hu_no         "No — normal colour"                   → URN-07
  hu_unknown    "Not known"                            → URN-07

URN-07  fieldId: urethral_discharge   answerType: SINGLE_CHOICE
Q: "Is there any discharge from the urinary opening?"
help: "Ask sensitively. This question screens for STI."
  ud_yes        "Yes"                                  → URN-G2
  ud_no         "No"                                   → URN-08
  ud_unknown    "Not known"                            → URN-08
```

**Corpus grounding.** `burning micturition` → N39.0 91% on 246 rows (§0.2) — the strongest
signal in this branch. `frequent urination` → E11 90% on 87 rows — note this points to diabetes,
not UTI, which is clinically correct (polyuria is a diabetes symptom). The qualifier separation is
measured.

### 4.5 Stage D/E

```
URN-08  fieldId: pregnancy_status   answerType: SINGLE_CHOICE   (shared, tree memo §2.1)
        asked only when sex == F and age_band ∈ [15,49]; else NOT_ASKED
Q: "Is the patient pregnant?"
  pg_yes "Yes" → URN-G2 · pg_no "No" → URN-S1 · pg_unknown "Not known" → URN-S1

URN-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → URN-G3, then URN-END

URN-END TerminalNode
```

No TB-SCREEN: TB does not present as urinary symptoms. No DEHYDRATION: dehydration is not the
clinical question for this presentation.

### 4.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-URN-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | urosepsis | N/A — Tier-0 |
| **GW-URN-EMG-2 (urosepsis)** | `allOf[ danger_signs contains ds_high_fever_urine, danger_signs contains ds_confusion ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **urosepsis** | **Yes — abstains** |
| **GW-URN-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-URN-URG-1 (pyelonephritis)** | `anyOf[ allOf[ fever_present equals fp_yes, flank_pain in {fp_one_side, fp_both} ], allOf[ danger_signs contains ds_high_fever_urine, flank_pain in {fp_one_side, fp_both} ] ]` | `raisesTo: REFER_URGENT` | pyelonephritis |
| **GW-URN-URG-2 (obstruction)** | `anyOf[ danger_signs contains ds_cannot_pass_urine, danger_signs contains ds_severe_flank_pain ]` | `raisesTo: REFER_URGENT` | urinary obstruction, renal colic |
| **GW-URN-URG-3 (haematuria)** | `anyOf[ haematuria equals hu_visible, danger_signs contains ds_blood_in_urine ]` | `raisesTo: REFER_URGENT` | renal stone, malignancy, glomerulonephritis |
| **GW-URN-URG-4 (pregnancy UTI)** | `allOf[ pregnancy_status equals pg_yes, burning_on_urination equals bu_yes ]` | `raisesTo: REFER_URGENT` | UTI in pregnancy (preterm risk) |
| **GW-URN-URG-5 (STI)** | `urethral_discharge equals ud_yes` | `raisesTo: REFER_URGENT` | STI — partner notification, syndromic management |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `URN-G1` after URN-00 evaluates EMG-1…EMG-3, URG-2; `URN-G2` evaluates URG-1,
URG-3, URG-5 as fields land; `URN-G3` after FEVER-QUAL evaluates URG-1 (fever_present clause),
URG-4.

### 4.7 Worked BranchOutput — 30-year-old woman, 3 days of burning urination, no fever, not pregnant

```
BranchOutput {
  categoryId: "urinary_symptoms"
  branchVersion: "1.0.0-draft"
  pathTaken: [URN-00, URN-01, URN-02, URN-03, URN-04, URN-05, URN-06,
              URN-07, URN-08, URN-S1, FQ-00, URN-G3, URN-END]
  fields: {
    danger_signs           { ANSWERED, [ds_none],               TAP }
    progression            { ANSWERED, pr_same,                 TAP }
    prior_treatment_taken  { ANSWERED, [pt_none],               TAP }
    burning_on_urination   { ANSWERED, bu_yes,                  TAP }
    frequency_increased    { ANSWERED, fi_yes,                  TAP }
    flank_pain             { ANSWERED, fp_no,                   TAP }
    haematuria             { ANSWERED, hu_no,                   TAP }
    urethral_discharge     { ANSWERED, ud_no,                   TAP }
    pregnancy_status       { ANSWERED, pg_no,                   TAP }
    fever_present          { ANSWERED, fp_no,                   TAP }
    fever_duration_band    { NOT_ASKED, null, — }
    fever_pattern          { NOT_ASKED, null, — }
    bleeding_manifestation { NOT_ASKED, null, — }
    duration_bucket        { ANSWERED, few_days,                PREFILL_MEASURED }
    onset_pattern          { ANSWERED, acute_1_3d,              TAP }
    severity_score         { ANSWERED, 4,                       TAP }
    severity_band          { ANSWERED, moderate,                DERIVED }
    relevant_history       { ANSWERED, [no_known_history],      TAP }
    impact_on_daily_activities { ANSWERED, [cannot_do_household_chores], TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

### 4.8 Node budget

Authored non-gateway: **11** (URN-00…URN-08, URN-S1, URN-END).
Expanded worst case (FEVER-QUAL gate-only): 11 + 1 = **12**. Full FEVER-QUAL: 11 + 4 = **15**.
**Well under DECISION-2 ceiling.**

### 4.9 Conformance

All checks pass. DECISION-1: GW-URN-EMG-2, EMG-3 raise to REFER_EMERGENCY → abstain.
DECISION-2: **15** ≤ 20 ✔.

---

## 5. Branch: `cold_sore_throat`

### 5.1 Category constants — category memo §7 Chapter R

```
categoryId               = "cold_sore_throat"
displayName              = "Cold, sore throat or blocked nose"
tier                     = CORE
icpc3Chapter             = R
icpc3Basis               = R07, R21, R74
icd10Mapping             = J00, J02.9, J06.9
anchor                   = { value: largest single AIIMS Bhopal diagnosis group —
                             acute RTI 23.9%, sourceType: IND-PRESENT, MP }
modelBehaviour           = LEARNED
gatewayStrength          = MODERATE
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ paediatric pneumonia presenting as "cold",
                             rheumatic fever after streptococcal sore throat ]
branchVersion            = 1.0.0-draft
```

> **Grounding warning.** The corpus returns **nothing** for any throat, nasal, sore throat,
> sneezing, cold (as URTI), ear, or related term (§0.2). Zero rows. This category is entirely
> absent from the 118-term symptom pool. Every qualifier below is protocol-grounded and `ASSUMED`.
> **G-11 extended.**

### 5.2 Stage A — CST-00

```
CST-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_stridor         "Noisy breathing when breathing in (stridor)"          → CST-G1
  ds_drooling        "Drooling, cannot swallow saliva"                     → CST-G1
  ds_fast_breathing  "Breathing fast — chest pulling in with each breath"  → CST-G1
  ds_high_fever_throat "High fever with the sore throat"                   → CST-G1
  ds_swelling_neck   "Swelling in the front of the neck, below the jaw"   → CST-G1
  ds_rash_with_fever "Rash with fever and sore throat"                    → CST-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"         → CST-G1
  ds_cannot_feed     "Unable to drink or feed"                            → CST-G1
  ds_none            "None of these"                                      → CST-01
  ds_unknown         "Not known"                                          → CST-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Paediatric pneumonia presenting as "cold" | `ds_fast_breathing` (the IMCI fast-breathing criterion), `ds_stridor` | covered — chest indrawing + tachypnoea are the IMCI signs. **IMCI classifies "fast breathing" with specific age-based cut-points (≥60 for <2mo, ≥50 for 2-12mo, ≥40 for 1-5yr). The danger sign text uses the visual sign (chest pulling in) rather than a counted rate, which the worker may not have access to. G-2 gap for rate-based classification** |
| Rheumatic fever after streptococcal sore throat | `ds_high_fever_throat`, `ds_rash_with_fever` + joint pain cross-link | covered as a pattern; **there is no bedside test for GAS pharyngitis. The danger sign captures the severe presentation; the 2-4 week latency of rheumatic fever means it presents AFTER the sore throat resolves, in the `joint_pain` branch. G-2 gap: this branch cannot detect rheumatic fever directly — it can only flag the precursor (high fever + sore throat). Physician review required** |

### 5.3 Stage B

`CST-01 progression`, `CST-02 prior_treatment_taken`, as batch 1 §1.3. → CST-03, CST-04.

### 5.4 Stage C — the category qualifiers

```
CST-03  fieldId: throat_pain_swallowing   answerType: SINGLE_CHOICE
Q: "Does it hurt to swallow?"
  ts_severe     "Yes — very painful, can barely swallow"    → CST-G2
  ts_mild       "Yes — a little painful"                    → CST-04
  ts_no         "No throat pain"                            → CST-04
  ts_unknown    "Not known"                                 → CST-04

CST-04  fieldId: cough_character   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Is the patient coughing? What kind?"
  cc_dry · cc_productive · cc_blood · cc_paroxysmal · cc_none · cc_unknown
  → CST-05

CST-05  fieldId: ear_pain_with_cold   answerType: SINGLE_CHOICE
Q: "Is there ear pain?"
help: "Ask about both ears. Pull the ear gently — if the child cries, record yes."
  ep_one_ear    "Yes — one ear"                        → CST-06
  ep_both_ears  "Yes — both ears"                      → CST-06
  ep_discharge  "Discharge coming from the ear"        → CST-G2
  ep_no         "No"                                   → CST-06
  ep_unknown    "Not known"                            → CST-06

CST-06  fieldId: fast_breathing_child   answerType: SINGLE_CHOICE
        asked only when age_band < 5; else NOT_ASKED
Q: "Count the breaths in one minute. Is it fast for the child's age?"
help: "Under 2 months: fast if ≥60. 2–12 months: fast if ≥50. 1–5 years: fast if ≥40."
  fb_fast       "Yes — breathing fast"                 → CST-G1
  fb_normal     "No — normal rate"                     → CST-07
  fb_not_counted "Could not count"                     → CST-07

CST-07  fieldId: rash_with_sore_throat   answerType: SINGLE_CHOICE
Q: "Is there a rash along with the sore throat and fever?"
  rt_yes        "Yes — fine sandpaper rash"            → CST-G2
  rt_no         "No rash"                              → CST-S1
  rt_unknown    "Not known"                            → CST-S1
```

### 5.5 Stage D/E

```
CST-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → CST-G3, then CST-END

CST-END TerminalNode
```

No TB-SCREEN: a cough with a cold is too short to trigger TB screening. If the cough persists
≥2 weeks, the worker should follow up with the `cough` branch at the next visit, which includes
TB-SCREEN.

### 5.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-CST-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | epiglottitis, meningitis | N/A — Tier-0 |
| **GW-CST-EMG-2 (airway)** | `anyOf[ danger_signs contains ds_stridor, danger_signs contains ds_drooling ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **epiglottitis, croup (severe), retropharyngeal abscess** | **Yes — abstains** |
| **GW-CST-EMG-3 (pneumonia child)** | `anyOf[ danger_signs contains ds_fast_breathing, fast_breathing_child equals fb_fast ]` | `raisesTo: REFER_EMERGENCY`, continue | **paediatric pneumonia** | **Yes — abstains** |
| **GW-CST-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-CST-URG-1 (peritonsillar)** | `anyOf[ throat_pain_swallowing equals ts_severe, allOf[ danger_signs contains ds_swelling_neck, danger_signs contains ds_high_fever_throat ] ]` | `raisesTo: REFER_URGENT` | peritonsillar abscess, deep neck infection |
| **GW-CST-URG-2 (scarlet fever / rheumatic)** | `allOf[ rash_with_sore_throat equals rt_yes, fever_present equals fp_yes ]` | `raisesTo: REFER_URGENT` | scarlet fever, rheumatic fever risk |
| **GW-CST-URG-3 (ear)** | `ear_pain_with_cold equals ep_discharge` | `raisesTo: REFER_URGENT` | chronic suppurative otitis media |
| **GW-CST-URG-4 (haemoptysis in cold)** | `cough_character equals cc_blood` | `raisesTo: REFER_URGENT` | TB, pneumonia, foreign body |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `CST-G1` after CST-00 evaluates EMG-1…EMG-4; `CST-G2` evaluates URG-1…URG-4
as fields land; `CST-G3` after FEVER-QUAL evaluates URG-2 (fever_present clause).

### 5.7 Worked BranchOutput — 3-year-old child, 2 days of runny nose and mild cough, no danger signs, no fever

```
BranchOutput {
  categoryId: "cold_sore_throat"
  branchVersion: "1.0.0-draft"
  pathTaken: [CST-00, CST-01, CST-02, CST-03, CST-04, CST-05, CST-06,
              CST-07, CST-S1, FQ-00, CST-G3, CST-END]
  fields: {
    danger_signs              { ANSWERED, [ds_none],              TAP }
    progression               { ANSWERED, pr_same,                TAP }
    prior_treatment_taken     { ANSWERED, [pt_home_remedy],       TAP }
    throat_pain_swallowing    { ANSWERED, ts_no,                  TAP }
    cough_character           { ANSWERED, cc_dry,                 TAP }
    ear_pain_with_cold        { ANSWERED, ep_no,                  TAP }
    fast_breathing_child      { ANSWERED, fb_normal,              TAP }
    rash_with_sore_throat     { ANSWERED, rt_no,                  TAP }
    fever_present             { ANSWERED, fp_no,                  TAP }
    fever_duration_band       { NOT_ASKED, null, — }
    fever_pattern             { NOT_ASKED, null, — }
    bleeding_manifestation    { NOT_ASKED, null, — }
    duration_bucket           { ANSWERED, few_days,               PREFILL_MEASURED }
    onset_pattern             { ANSWERED, acute_1_3d,             TAP }
    severity_score            { ANSWERED, 3,                      TAP }
    severity_band             { ANSWERED, mild,                   DERIVED }
    relevant_history          { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [cannot_go_to_school], TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

### 5.8 Node budget

Authored non-gateway: **11** (CST-00…CST-07, CST-S1, CST-END).
Expanded worst case (FEVER-QUAL gate-only): 11 + 1 = **12**. Full FEVER-QUAL: 11 + 4 = **15**.
But `fast_breathing_child` is age-gated (< 5 only), so the adult worst case is one node fewer:
**14**. **Well under DECISION-2 ceiling.**

### 5.9 Conformance

All checks pass. DECISION-1: GW-CST-EMG-2, EMG-3, EMG-4 raise to REFER_EMERGENCY → abstain.
DECISION-2: **15** ≤ 20 ✔.

---

## 6. Branch: `weakness_unwell`

### 6.1 Category constants — category memo §7 Chapter A

```
categoryId               = "weakness_unwell"
displayName              = "Weakness, tiredness, not feeling well"
tier                     = CORE
icpc3Chapter             = A
icpc3Basis               = A04, A05
icd10Mapping             = R53
anchor                   = { value: ~10% of chapter A ASSUMED — the residual "general"
                             presentation; MP anaemia (54.7% women, 72.7% children, NFHS-5)
                             makes it high-volume,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = MODERATE
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ anaemia (severe), TB, diabetes, hypothyroidism,
                             depression, occult malignancy, HIV ]
branchVersion            = 1.0.0-draft
```

> **Branch shape note.** This is the residual "general" bucket. A patient presenting with
> "weakness" or "not feeling well" could have any of seven severe conditions or a hundred benign
> ones. The branch's job is NOT to resolve a differential — it cannot. Its job is: (a) catch
> the emergencies through Stage A, (b) collect the cross-links (TB-SCREEN, FEVER-QUAL) that
> let the classifier work with qualified terms, (c) abstain gracefully on the many paths it
> cannot resolve. The worked example below shows the typical case: no gateway fires, the classifier
> gets the qualified terms, and the physician reviews.

> **Grounding warning.** `weakness`, `tiredness`, `fatigue`, `lethargy` all resolve to E66
> at 76-77% — the BMI prior artifact (§0.2). Zero discriminative signal in the bare terms.
> The branch depends entirely on its cross-links for whatever signal the classifier receives.

### 6.2 Stage A — WKN-00

```
WKN-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_severe_pallor    "Very pale — palms, conjunctivae, or tongue white"     → WKN-G1
  ds_breathless_rest  "Breathless even when sitting still"                   → WKN-G1
  ds_chest_pain       "Chest pain or heaviness"                              → WKN-G1
  ds_confusion        "Confused, or behaviour unusual"                       → WKN-G1
  ds_unconscious      "Unconscious, or very drowsy / hard to wake"           → WKN-G1
  ds_cannot_feed      "Unable to drink or feed"                              → WKN-G1
  ds_none             "None of these"                                        → WKN-01
  ds_unknown          "Not known"                                            → WKN-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Severe anaemia | `ds_severe_pallor`, `ds_breathless_rest` | covered — IMCI severe palmar pallor + at-rest breathlessness |
| TB | *none in Stage A* | by design — TB-SCREEN cross-link (WKN-S1) |
| Diabetes | *none in Stage A* | **G-2 gap. Undiagnosed diabetes presenting as weakness has no specific Stage A sign. The polyuria/polydipsia question (WKN-05) catches the symptomatic case; silent diabetes is a physician-level diagnosis. Physician review required** |
| Hypothyroidism | *none in Stage A* | **G-2 gap. No worker-observable proxy. Cold intolerance, weight gain, constipation are non-specific. The branch collects what it can and the physician reviews. Physician review required** |
| Depression | *none in Stage A* | **G-2 gap. Depression presenting as "weakness" is extremely common in Indian PHC but has no worker-observable proxy at Stage A. The mood_change question (WKN-06) screens for it. Physician review required** |
| Occult malignancy | *none specific in Stage A*; `ds_severe_pallor` may catch late-stage | covered only for the anaemic presentation; weight loss is caught by TB-SCREEN |
| HIV | *none in Stage A* | **G-2 gap. HIV presenting as weakness has no specific sign. TB-SCREEN catches the co-infection pattern. Physician review required** |

### 6.3 Stage B

`WKN-01 progression`, `WKN-02 prior_treatment_taken`, as batch 1 §1.3. → WKN-03, WKN-04.

### 6.4 Stage C — the category qualifiers

```
WKN-03  fieldId: weakness_generalised_or_focal   answerType: SINGLE_CHOICE
Q: "Is the weakness all over, or in one part of the body?"
  wg_generalised "All over — general tiredness"            → WKN-04
  wg_one_side    "One side of the body — arm or leg"       → WKN-G1
  wg_one_limb    "One arm or leg"                          → WKN-04
  wg_unknown     "Not known"                               → WKN-04

WKN-04  fieldId: appetite_change   answerType: SINGLE_CHOICE   (shared — §9 registry)
Q: "Has the appetite changed?"
  ac_decreased  "Eating much less than before"             → WKN-05
  ac_increased  "Eating more than before"                  → WKN-05
  ac_no_change  "No change"                                → WKN-05
  ac_unknown    "Not known"                                → WKN-05

WKN-05  fieldId: polyuria_polydipsia   answerType: SINGLE_CHOICE
        (shared with known_diabetes DM-06, batch 2 §4.4; same fieldId, same optionIds)
Q: "Is the patient passing urine very often, or feeling very thirsty?"
  pp_both · pp_urine · pp_thirst · pp_no · pp_unknown
  → WKN-06

WKN-06  fieldId: mood_change   answerType: SINGLE_CHOICE
Q: "Has the patient's mood changed — feeling sad, hopeless, or losing interest in things?"
help: "Ask gently. In India, depression often presents as 'weakness' or 'body ache'."
  mc_sad_hopeless "Yes — sad, hopeless, or crying"         → WKN-07
  mc_anxious      "Anxious, worried, or unable to relax"   → WKN-07
  mc_irritable    "Irritable"                              → WKN-07
  mc_no           "No change"                              → WKN-07
  mc_unknown      "Not known"                              → WKN-07

WKN-07  fieldId: sleep_change   answerType: SINGLE_CHOICE
Q: "Has sleep changed?"
  sc_insomnia   "Cannot sleep, or wakes too early"         → WKN-S1
  sc_excessive  "Sleeping much more than usual"            → WKN-S1
  sc_no_change  "No change"                                → WKN-S1
  sc_unknown    "Not known"                                → WKN-S1
```

### 6.5 Stage D/E

```
WKN-S1  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
        on return → WKN-G2, then WKN-S2

WKN-S2  SubtreeRef → FEVER-QUAL v1.1
        on return → WKN-G3, then WKN-END

WKN-END TerminalNode
```

### 6.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-WKN-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | hypoglycaemia, severe anaemia, sepsis | N/A — Tier-0 |
| **GW-WKN-EMG-2 (severe anaemia)** | `allOf[ danger_signs contains ds_severe_pallor, danger_signs contains ds_breathless_rest ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **severe anaemia requiring transfusion** | **Yes — abstains** |
| **GW-WKN-EMG-3 (stroke)** | `weakness_generalised_or_focal equals wg_one_side` | `raisesTo: REFER_EMERGENCY`, continue | **stroke**, space-occupying lesion | **Yes — abstains** |
| **GW-WKN-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-WKN-URG-1 (pallor alone)** | `danger_signs contains ds_severe_pallor` | `raisesTo: REFER_URGENT` | moderate-to-severe anaemia |
| **GW-WKN-URG-2 (TB)** | `anyOf[ tb_contact equals tbc_yes, cough_ge_2_weeks equals c2_yes, weight_loss_present equals wl_yes, night_sweats equals ns_yes ]` | `raisesTo: REFER_URGENT` | TB |
| **GW-WKN-URG-3 (diabetes screen)** | `polyuria_polydipsia equals pp_both` | `raisesTo: REFER_URGENT` | undiagnosed diabetes |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `WKN-G1` after WKN-00 evaluates EMG-1…EMG-4, URG-1; `WKN-G2` after TB-SCREEN
evaluates URG-2; `WKN-G3` after FEVER-QUAL evaluates URG-3.

### 6.7 Worked BranchOutput — 40-year-old woman, 3 months of tiredness, decreased appetite, no danger signs, no TB contacts, no fever

```
BranchOutput {
  categoryId: "weakness_unwell"
  branchVersion: "1.0.0-draft"
  pathTaken: [WKN-00, WKN-01, WKN-02, WKN-03, WKN-04, WKN-05, WKN-06, WKN-07,
              WKN-S1, TBS-01, TBS-02, TBS-03, TBS-04, WKN-G2,
              WKN-S2, FQ-00, WKN-G3, WKN-END]
  fields: {
    danger_signs               { ANSWERED, [ds_none],              TAP }
    progression                { ANSWERED, pr_worse,               TAP }
    prior_treatment_taken      { ANSWERED, [pt_none],              TAP }
    weakness_generalised_or_focal { ANSWERED, wg_generalised,      TAP }
    appetite_change            { ANSWERED, ac_decreased,           TAP }
    polyuria_polydipsia        { ANSWERED, pp_no,                  TAP }
    mood_change                { ANSWERED, mc_sad_hopeless,        TAP }
    sleep_change               { ANSWERED, sc_insomnia,            TAP }
    cough_ge_2_weeks           { ANSWERED, c2_no,                  TAP }
    weight_loss_present        { ANSWERED, wl_no,                  TAP }
    night_sweats               { ANSWERED, ns_no,                  TAP }
    tb_contact                 { ANSWERED, tbc_no,                 TAP }
    fever_present              { ANSWERED, fp_no,                  TAP }
    fever_duration_band        { NOT_ASKED, null, — }
    fever_pattern              { NOT_ASKED, null, — }
    bleeding_manifestation     { NOT_ASKED, null, — }
    duration_bucket            { ANSWERED, chronic,                PREFILL_MEASURED }
    onset_pattern              { ANSWERED, insidious,              TAP }
    severity_score             { ANSWERED, 5,                      TAP }
    severity_band              { ANSWERED, moderate,               DERIVED }
    relevant_history           { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [cannot_do_household_chores], TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

**No gateway fired.** This is the typical outcome for `weakness_unwell` — the presentation is
too non-specific for any red-flag pattern to match. The tree collected `wg_generalised` +
`ac_decreased` + `mc_sad_hopeless` + `sc_insomnia` — a pattern that could be anaemia, depression,
hypothyroidism, or early malignancy. The classifier receives qualified terms; the physician reviews.
This is the branch working as designed: collecting observations, making no claims.

### 6.8 Node budget

Authored non-gateway: **12** (WKN-00…WKN-07, WKN-S1, WKN-S2, WKN-END).
Expanded worst case (TB-SCREEN 4 + FEVER-QUAL gate-only 1): 12 + 4 + 1 = **17**.
Full FEVER-QUAL: 12 + 4 + 4 = **20**. **At DECISION-2 ceiling.**

### 6.9 Conformance

All checks pass. DECISION-1: GW-WKN-EMG-2, EMG-3, EMG-4 raise to REFER_EMERGENCY → abstain.
DECISION-2: **20** ≤ 20 ✔.

---

## 7. Branch: `body_ache`

### 7.1 Category constants — category memo §7 Chapter A

```
categoryId               = "body_ache"
displayName              = "Body ache or pain all over"
tier                     = CORE
icpc3Chapter             = A
icpc3Basis               = A01
icd10Mapping             = M79.1, M79.7
anchor                   = { value: ~7% of chapter A ASSUMED,
                             sourceType: ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = MODERATE
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ dengue, chikungunya, influenza-like illness,
                             enteric fever ]
branchVersion            = 1.0.0-draft
```

**Corpus grounding.** `body ache` → B54 (malaria) at 94% on 174 rows; `severe body ache` → A90
(dengue) at 87% on 174 rows; `retro-orbital pain` → A90 at 90% on 183 rows (§0.2). These are the
strongest measured signals in this batch. The discriminative qualifier is severity: mild body ache
points to malaria, severe body ache points to dengue.

### 7.2 Stage A — BAC-00

```
BAC-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_rash_petechial  "Rash — small red/purple spots that do not blanch"   → BAC-G1
  ds_bleeding        "Bleeding from gums, nose, or under the skin"       → BAC-G1
  ds_high_fever_ache "High fever with the body ache"                     → BAC-G1
  ds_severe_pain     "Pain so severe the patient cannot move"            → BAC-G1
  ds_swollen_face    "Face or throat swelling up"                        → BAC-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"        → BAC-G1
  ds_cannot_feed     "Unable to drink or feed"                           → BAC-G1
  ds_none            "None of these"                                     → BAC-01
  ds_unknown         "Not known"                                         → BAC-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Dengue (incl. severe) | `ds_rash_petechial`, `ds_bleeding`, `ds_high_fever_ache` | covered — the warning signs of severe dengue |
| Chikungunya | `ds_high_fever_ache` + joint swelling (BAC-05) | covered as a pattern |
| Influenza-like illness | `ds_high_fever_ache` | covered at Stage A; **there is no bedside sign that distinguishes ILI from other acute febrile body ache — by design, FEVER-QUAL carries the differential** |
| Enteric fever | `ds_high_fever_ache` | covered at Stage A; enteric fever's distinguishing features (stepladder fever, rose spots) are in the `fever` branch's fever_pattern qualifier |

### 7.3 Stage B

`BAC-01 progression`, `BAC-02 prior_treatment_taken`, as batch 1 §1.3. → BAC-03, BAC-04.

### 7.4 Stage C — the category qualifiers

```
BAC-03  fieldId: ache_distribution   answerType: SINGLE_CHOICE
Q: "Where is the pain?"
  ad_all_over    "All over the body"                      → BAC-04
                 valueToken: "body ache"
  ad_muscles     "Mainly in the muscles — arms, legs"     → BAC-04
                 valueToken: "myalgia"
  ad_bones       "Deep ache — feels like it is in the bones" → BAC-04
                 valueToken: "severe body ache"
  ad_behind_eyes "Pain behind or around the eyes"         → BAC-G2
                 valueToken: "retro-orbital pain"
  ad_unknown     "Not known"                              → BAC-04
                 valueToken: "body ache"

BAC-04  fieldId: rash_with_ache   answerType: SINGLE_CHOICE
Q: "Is there any rash or skin change with the body ache?"
  ra_yes     "Yes"          → BAC-G2
  ra_no      "No rash seen" → BAC-05
  ra_unknown "Not known"    → BAC-05

BAC-05  fieldId: joint_swelling   answerType: SINGLE_CHOICE   (shared, batch 2 §8.2)
Q: "Is there visible swelling of any joint?"
  js_yes_hot · js_yes_cold · js_no · js_unknown
  → BAC-S1
```

**Corpus grounding, stated at node level.** `ache_distribution.ad_behind_eyes` carries
`retro-orbital pain` → A90 90%, the single strongest discriminator in the branch.
`ache_distribution.ad_bones` carries `severe body ache` → A90 87%.
`ache_distribution.ad_all_over` carries `body ache` → B54 94%. The qualifier directly moves the
differential between dengue and malaria.

### 7.5 Stage D/E

```
BAC-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → BAC-G3, then BAC-END

BAC-END TerminalNode
```

FEVER-QUAL is the essential cross-link: body ache is almost always fever-associated, and the
fever pattern (cyclical/stepladder/evening) is the measured discriminator.

No TB-SCREEN: body ache is not a TB presentation. No DEHYDRATION: dehydration is not relevant.

### 7.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-BAC-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | cerebral malaria, severe dengue | N/A — Tier-0 |
| **GW-BAC-EMG-2 (severe dengue)** | `anyOf[ allOf[ danger_signs contains ds_rash_petechial, danger_signs contains ds_bleeding ], allOf[ danger_signs contains ds_bleeding, danger_signs contains ds_high_fever_ache ] ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **severe dengue**, DIC | **Yes — abstains** |
| **GW-BAC-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-BAC-URG-1 (dengue warning signs)** | `anyOf[ ache_distribution equals ad_behind_eyes, allOf[ rash_with_ache equals ra_yes, fever_present equals fp_yes ] ]` | `raisesTo: REFER_URGENT` | dengue (warning signs) |
| **GW-BAC-URG-2 (chikungunya)** | `allOf[ joint_swelling in {js_yes_hot, js_yes_cold}, fever_present equals fp_yes ]` | `raisesTo: REFER_URGENT` | chikungunya, reactive arthritis |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `BAC-G1` after BAC-00 evaluates EMG-1…EMG-3; `BAC-G2` evaluates URG-1 as
fields land; `BAC-G3` after FEVER-QUAL evaluates URG-1 (fever_present clause), URG-2.

### 7.7 Worked BranchOutput — 22-year-old man, 3 days of severe body ache, retro-orbital pain, high fever, cyclical pattern

```
BranchOutput {
  categoryId: "body_ache"
  branchVersion: "1.0.0-draft"
  pathTaken: [BAC-00, BAC-G1, BAC-01, BAC-02, BAC-03, BAC-G2, BAC-04, BAC-05,
              BAC-S1, FQ-00, FQ-01, FQ-02, FQ-03, BAC-G3, BAC-END]
  fields: {
    danger_signs              { ANSWERED, [ds_high_fever_ache],   TAP }
    progression               { ANSWERED, pr_worse,               TAP }
    prior_treatment_taken     { ANSWERED, [pt_pharmacy_medicine], TAP }
    ache_distribution         { ANSWERED, ad_behind_eyes,         TAP }
    rash_with_ache            { ANSWERED, ra_no,                  TAP }
    joint_swelling            { ANSWERED, js_no,                  TAP }
    fever_present             { ANSWERED, fp_yes,                 TAP }
    fever_duration_band       { ANSWERED, fd_1_3,                 TAP }
    fever_pattern             { ANSWERED, fpat_cyclical,          TAP }
    bleeding_manifestation    { ANSWERED, [bl_none],              TAP }
    duration_bucket           { ANSWERED, few_days,               PREFILL_MEASURED }
    onset_pattern             { ANSWERED, acute_1_3d,             TAP }
    severity_score            { ANSWERED, 8,                      TAP }
    severity_band             { ANSWERED, severe,                 DERIVED }
    relevant_history          { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],      TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-BAC-URG-1,
      "ache_distribution==ad_behind_eyes OR (rash_with_ache==ra_yes AND fever_present==fp_yes)",
      [dengue (warning signs)] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

The dengue warning-sign gateway fired on `ad_behind_eyes` alone. The tree collected `retro-orbital
pain` (→ A90 90%) and `cyclical` (→ B54 88%) — these value tokens give the classifier a strong
signal for the dengue/malaria differential.

### 7.8 Node budget

Authored non-gateway: **8** (BAC-00…BAC-05, BAC-S1, BAC-END).
Expanded worst case (FEVER-QUAL gate-only): 8 + 1 = **9**. Full FEVER-QUAL: 8 + 4 = **12**.
**Well under DECISION-2 ceiling with 8 nodes of headroom.**

### 7.9 Conformance

All checks pass. DECISION-1: GW-BAC-EMG-2, EMG-3 raise to REFER_EMERGENCY → abstain.
DECISION-2: **12** ≤ 20 ✔.

---

## 8. Branch: `weight_loss`

### 8.1 Category constants — category memo §7 Chapter A

```
categoryId               = "weight_loss"
displayName              = "Losing weight without trying"
tier                     = CORE
icpc3Chapter             = A (cross-chapter: T08 endocrine/metabolic)
icpc3Basis               = T08
icd10Mapping             = R63.4
anchor                   = { value: ~2% of chapter A ASSUMED; India TB incidence 187/lakh,
                             MP 4th-highest burden,
                             sourceType: IND-PROG, Nikshay }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT          // entry floor is already URGENT
severeConditions         = [ TB, diabetes, HIV, malignancy, hyperthyroidism ]
branchVersion            = 1.0.0-draft
```

**Corpus grounding.** `weight loss` → A15 (TB) at 86% on 92 rows; `night sweats` → A15 at 82%
on 102 rows (§0.2). `unexplained weight loss` → E11 49% / E05.9 34% — noise. The TB signal is
the measured discriminative anchor. `loss of appetite` → A09 35% — noise.

### 8.2 Stage A — WTL-00

```
WTL-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_severe_pallor    "Very pale — palms, conjunctivae, or tongue white"    → WTL-G1
  ds_breathless_rest  "Breathless even when sitting still"                  → WTL-G1
  ds_lump_swelling    "A lump or swelling that is growing"                  → WTL-G1
  ds_fever_gt_2wk     "Fever lasting more than 2 weeks"                     → WTL-G1
  ds_blood_in_cough   "Coughing up blood"                                   → WTL-G1
  ds_unconscious      "Unconscious, or very drowsy / hard to wake"          → WTL-G1
  ds_cannot_feed      "Unable to drink or feed"                             → WTL-G1
  ds_none             "None of these"                                       → WTL-01
  ds_unknown          "Not known"                                           → WTL-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| TB | `ds_fever_gt_2wk`, `ds_blood_in_cough` + TB-SCREEN (WTL-S1) | covered — the national screening trigger |
| Diabetes | *none in Stage A* | by design — `polyuria_polydipsia` at WTL-06 screens; known diabetics use `known_diabetes` |
| HIV | *none in Stage A* | **G-2 gap. HIV has no worker-observable Stage A sign. TB-SCREEN cross-link catches the co-infection pattern. Physician review required** |
| Malignancy | `ds_lump_swelling`, `ds_severe_pallor` | covered for late-stage; **early malignancy presenting as weight loss alone has no worker-observable sign — G-2 gap** |
| Hyperthyroidism | *none in Stage A* | **G-2 gap. No worker-observable proxy. Tachycardia is on vitals; tremor and exophthalmos are physician-level findings. Physician review required** |

### 8.3 Stage B

`WTL-01 progression`, `WTL-02 prior_treatment_taken`, as batch 1 §1.3. → WTL-03, WTL-04.

### 8.4 Stage C — the category qualifiers

```
WTL-03  fieldId: weight_loss_amount_band   answerType: SINGLE_CHOICE
Q: "How much weight has been lost?"
  wa_lt_5       "A little — less than 5 kg / clothes a bit loose"    → WTL-04
  wa_5_10       "5 to 10 kg — clothes noticeably loose"              → WTL-04
  wa_gt_10      "More than 10 kg — very visible change"              → WTL-G2
  wa_unknown    "Not known"                                          → WTL-04

WTL-04  fieldId: appetite_change   answerType: SINGLE_CHOICE   (shared — §9 registry)
Q: "Has the appetite changed?"
  ac_decreased · ac_increased · ac_no_change · ac_unknown
  → WTL-05

WTL-05  fieldId: cough_character   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Is the patient coughing? What kind?"
  cc_dry · cc_productive · cc_blood · cc_paroxysmal · cc_none · cc_unknown
  → WTL-06

WTL-06  fieldId: polyuria_polydipsia   answerType: SINGLE_CHOICE
        (shared with known_diabetes DM-06, weakness_unwell WKN-05)
Q: "Is the patient passing urine very often, or feeling very thirsty?"
  pp_both · pp_urine · pp_thirst · pp_no · pp_unknown
  → WTL-S1
```

### 8.5 Stage D/E

```
WTL-S1  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
        on return → WTL-G3, then WTL-S2

WTL-S2  SubtreeRef → FEVER-QUAL v1.1
        on return → WTL-G4, then WTL-END

WTL-END TerminalNode
```

### 8.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-WTL-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | cachexia, severe hypoglycaemia | N/A — Tier-0 |
| **GW-WTL-EMG-2** | `danger_signs contains ds_blood_in_cough` | `routeTo: emergency_heavy_bleeding`, terminate | pulmonary haemorrhage, TB | N/A — Tier-0 |
| **GW-WTL-EMG-3 (severe anaemia)** | `allOf[ danger_signs contains ds_severe_pallor, danger_signs contains ds_breathless_rest ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **severe anaemia, late-stage malignancy** | **Yes — abstains** |
| **GW-WTL-EMG-4** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-WTL-URG-1 (TB strong)** | `anyOf[ tb_contact equals tbc_yes, cough_ge_2_weeks equals c2_yes, allOf[ weight_loss_present equals wl_yes, night_sweats equals ns_yes ] ]` | **informational** at floor | TB — note: floor is already REFER_URGENT, the gateway is informational only and carries the severeConditions list for RF-1 |
| **GW-WTL-URG-2 (severe loss)** | `weight_loss_amount_band equals wa_gt_10` | **informational** at floor | malignancy, advanced TB, HIV |
| **GW-WTL-URG-3 (diabetes screen)** | `polyuria_polydipsia equals pp_both` | **informational** at floor | undiagnosed diabetes |
| **GW-WTL-URG-4 (haemoptysis)** | `cough_character equals cc_blood` | **informational** at floor | TB, lung malignancy |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

Node placement: `WTL-G1` after WTL-00 evaluates EMG-1…EMG-4; `WTL-G2` evaluates URG-2 as fields
land; `WTL-G3` after TB-SCREEN evaluates URG-1; `WTL-G4` after FEVER-QUAL.

**Note on gateway architecture.** Because the base floor is already `REFER_URGENT`, the URG
gateways are informational rather than floor-raising — their value is in carrying the
`severeConditions` list for RF-1 enforcement downstream, not in changing the disposition.

### 8.7 Worked BranchOutput — 35-year-old man, 2 months of weight loss, night sweats, productive cough ≥2 weeks, TB contact

```
BranchOutput {
  categoryId: "weight_loss"
  branchVersion: "1.0.0-draft"
  pathTaken: [WTL-00, WTL-G1, WTL-01, WTL-02, WTL-03, WTL-04, WTL-05, WTL-06,
              WTL-S1, TBS-01, TBS-02, TBS-03, TBS-04, WTL-G3,
              WTL-S2, FQ-00, FQ-01, FQ-02, FQ-03, WTL-G4, WTL-END]
  fields: {
    danger_signs              { ANSWERED, [ds_fever_gt_2wk],     TAP }
    progression               { ANSWERED, pr_worse,              TAP }
    prior_treatment_taken     { ANSWERED, [pt_none],             TAP }
    weight_loss_amount_band   { ANSWERED, wa_5_10,               TAP }
    appetite_change           { ANSWERED, ac_decreased,          TAP }
    cough_character           { ANSWERED, cc_productive,         TAP }
    polyuria_polydipsia       { ANSWERED, pp_no,                 TAP }
    cough_ge_2_weeks          { ANSWERED, c2_yes,                TAP }
    weight_loss_present       { ANSWERED, wl_yes,                TAP }
    night_sweats              { ANSWERED, ns_yes,                TAP }
    tb_contact                { ANSWERED, tbc_yes,               TAP }
    fever_present             { ANSWERED, fp_yes,                TAP }
    fever_duration_band       { ANSWERED, fd_gt_14,              TAP }
    fever_pattern             { ANSWERED, fpat_evening,          TAP }
    bleeding_manifestation    { ANSWERED, [bl_none],             TAP }
    duration_bucket           { ANSWERED, month_plus,            PREFILL_MEASURED }
    onset_pattern             { ANSWERED, insidious,             TAP }
    severity_score            { ANSWERED, 6,                     TAP }
    severity_band             { ANSWERED, moderate,              DERIVED }
    relevant_history          { ANSWERED, [no_known_history],    TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-WTL-URG-1,
      "tb_contact==tbc_yes OR cough_ge_2_weeks==c2_yes OR (weight_loss_present==wl_yes AND night_sweats==ns_yes)",
      [TB] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

The TB gateway fired on three separate clauses (tbc_yes, c2_yes, wl_yes+ns_yes). The tree
collected `cc_productive` + `fpat_evening` + `fd_gt_14` — the value tokens that carry the
TB signal (`evening fever` → A15 77%, `weight loss` → A15 86%, `night sweats` → A15 82%).

### 8.8 Node budget

Authored non-gateway: **10** (WTL-00…WTL-06, WTL-S1, WTL-S2, WTL-END).
Expanded worst case (TB-SCREEN 4 + FEVER-QUAL gate-only 1): 10 + 4 + 1 = **15**.
Full FEVER-QUAL: 10 + 4 + 4 = **18**. **Under DECISION-2 ceiling.**

### 8.9 Conformance

All checks pass. DECISION-1: GW-WTL-EMG-3, EMG-4 raise to REFER_EMERGENCY → abstain.
Two Tier-0 routes: `emergency_unconscious`, `emergency_heavy_bleeding`.
DECISION-2: **18** ≤ 20 ✔.

---

## 9. Registry additions — so batch 4 reuses these

### 9.1 New shared `fieldId`s

| fieldId | Type | Options | Introduced for | Reuse expected in |
|---|---|---|---|---|
| `appetite_change` | SINGLE | `ac_decreased` · `ac_increased` · `ac_no_change` · `ac_unknown` | `weakness_unwell`, `weight_loss` | `pallor_anaemia`, `known_diabetes` (follow-up screen) |
| `urethral_discharge` | SINGLE | `ud_yes` · `ud_no` · `ud_unknown` | `urinary_symptoms` | `skin_infection` (STI overlap) |
| `poisoning_suspected` | SINGLE | `ps_medicine` · `ps_pesticide` · `ps_other` · `ps_no` · `ps_unknown` | `vomiting_nausea` | unlikely reuse — category-specific |

### 9.2 New branch-specific `fieldId`s

| Branch | fieldIds |
|---|---|
| `vomiting_nausea` | `vomit_frequency_band` · `vomit_content` |
| `acidity_heartburn` | `relation_to_food` · `night_symptoms` · `dysphagia` · `nsaid_use` |
| `urinary_symptoms` | `frequency_increased` · `flank_pain` · `haematuria` |
| `cold_sore_throat` | `throat_pain_swallowing` · `ear_pain_with_cold` · `fast_breathing_child` · `rash_with_sore_throat` |
| `weakness_unwell` | `weakness_generalised_or_focal` · `mood_change` · `sleep_change` |
| `body_ache` | `ache_distribution` · `rash_with_ache` |
| `weight_loss` | `weight_loss_amount_band` |

### 9.3 Retired from tree memo §2.5

| Retired fieldId | Branch | Superseded by | Reason |
|---|---|---|---|
| `headache_with_vomiting` | `vomiting_nausea` | `danger_signs.ds_severe_headache` + FEVER-QUAL's `fever_present` | Stage A captures the emergency; headache is a separate branch, not a vomiting qualifier |
| `abdominal_pain_with_vomiting` | `vomiting_nausea` | `danger_signs.ds_abdomen_rigid` + shared `bowels_open` | same fact through different path |
| `black_stool` | `acidity_heartburn` | `danger_signs.ds_black_tarry_stool` + shared `blood_in_stool` | Stage A + shared field cover both |
| `rash_with_ache` (as a separate branch-specific field from `rash_with_fever`) | N/A | `rash_with_ache` **is** branch-specific to `body_ache` | not retired — registered as new |
| `fever_with_urinary` | `urinary_symptoms` | `danger_signs.ds_high_fever_urine` + FEVER-QUAL's `fever_present` | danger sign + cross-link cover both |

### 9.4 Reused from batches 1 and 2 registry (not re-authored)

| Item | Source | Used in this batch by |
|---|---|---|
| `TB-SCREEN` | batch 1 §6.1 | `weakness_unwell`, `weight_loss` |
| `DEHYDRATION` | batch 1 §6.2 | `vomiting_nausea` |
| `FEVER-QUAL` v1.1 | batch 1 §6.3 | all seven branches |
| `vomiting_present` | batch 1 §7.2 | `vomiting_nausea` (sentinel node) |
| `bowels_open` | batch 1 §7.3 | `vomiting_nausea` |
| `lmp_known` | batch 1 §7.2 | `vomiting_nausea` |
| `burning_on_urination` | batch 1 §7.2 | `urinary_symptoms` |
| `cough_character` | batch 1 §7.2 | `cold_sore_throat`, `weight_loss` |
| `wheeze` | batch 1 §7.2 | **not used** — `cold_sore_throat` does not need it (wheeze is a cough/breathlessness qualifier, not a cold qualifier) |
| `exertional_relation` | batch 1 §7.2 | `acidity_heartburn` |
| `blood_in_stool` | batch 1 §7.2 | `acidity_heartburn` |
| `joint_swelling` | batch 2 §8.2 | `body_ache` |
| `polyuria_polydipsia` | batch 2 §4.4 | `weakness_unwell`, `weight_loss` |
| `pregnancy_status` | tree memo §2.1 | `urinary_symptoms` |

**Note on `wheeze`.** The user's prompt listed `wheeze` as expected reuse for `cold_sore_throat`.
On authoring, `wheeze` is clinically a lower-respiratory sign (cough/breathlessness branches) —
an uncomplicated cold does not warrant a wheeze question. Adding it would be padding a node budget
for the sake of reuse. If a child with a "cold" has audible wheeze, the presentation is really
`cough` or `breathlessness` and the worker should select those categories. **Not used; decision
stated.**

### 9.5 Dataset column impact

Net new column families:
- **3 shared** (§9.1): `appetite_change`, `urethral_discharge`, `poisoning_suspected`
- **17 branch-specific** (§9.2)
- **3 retired** (§9.3)

Approximate net width: **≈ 25 columns**. Running total (batch 1 + batch 2 + batch 3):
≈ 40 + 35 + 25 = **≈ 100 columns** against tree memo §7.2's ≈ 400 projection.

---

## 10. Conformance summary — all seven batch-3 branches

| Branch | Entry floor | routeTo targets used | Highest floor | Authored | Expanded worst | NR-1 nearest approach | DECISION-1 abstention paths |
|---|---|---|---|---|---|---|---|
| `vomiting_nausea` | PHYSICIAN_REVIEW_MANDATORY | convulsions, unconscious, poisoning | REFER_EMERGENCY | 12 | 21 (G-16) | `vc_blood` (appearance) | GW-VMT-EMG-4…6 |
| `acidity_heartburn` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 11 | 15 | `ds_chest_pain_exertion` (symptom report) | GW-ACH-EMG-2…4 |
| `urinary_symptoms` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 11 | 15 | `hu_visible` (observation) | GW-URN-EMG-2, EMG-3 |
| `cold_sore_throat` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 11 | 15 | `fb_fast` (observation) | GW-CST-EMG-2…4 |
| `weakness_unwell` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 12 | 20 | `ds_severe_pallor` (observation) | GW-WKN-EMG-2…4 |
| `body_ache` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 8 | 12 | `ds_rash_petechial` (observation) | GW-BAC-EMG-2, EMG-3 |
| `weight_loss` | REFER_URGENT | unconscious, heavy_bleeding | REFER_EMERGENCY | 10 | 18 | `ds_blood_in_cough` (observation) | GW-WTL-EMG-3, EMG-4 |

**C1–C5 hold for all seven.** Stated once (batch 1 §8's argument applies verbatim), evidence per
branch in §§2.9–8.9.

**BR-1:** every option has an explicit `next`. **BR-2:** every SINGLE node has `*_unknown`;
every MULTI has `*_none`+`*_unknown`. **NR-1:** no severe condition named in any frame; nearest
approaches are all observations or symptom reports.

**The six replay assertions (batch 1 §8).**

| Assertion | Status this batch | Note |
|---|---|---|
| Reachability | All `next` resolve. Sub-tree references are to registered sub-trees | ✔ |
| Determinism | No model in branching. `vomiting_present` in VMT is DERIVED deterministically from danger_signs, not model-driven | ✔ |
| Field completeness | Conditional skips: `lmp_known` on sex+age_band; `fast_breathing_child` on age_band; `pregnancy_status` on sex+age_band — all deterministic gates | ✔ |
| Monotonicity | `max()` only; terminal floor ≥ category `requiredDisposition` on every path | ✔ |
| Routing | All `routeTo` targets are Tier-0 ALWAYS_ABSTAIN and terminate | ✔ |
| NR-1 lint | No authored string matches any `severeConditions[]` entry | ✔ |

---

## 11. Flagged for physician and operator review

### G-16 — `vomiting_nausea` exceeds 20 on the DEHYDRATION + full FEVER-QUAL path

See §2.8. Worst case: 21 nodes. Resolution: (a) merge `poisoning_suspected` into danger signs
(saves one node, drops to 20); (b) accept, since both sub-trees firing on the same path is
low-frequency. **Operator decision required.**

### G-2 extensions (new clinical-safety gaps this batch)

| Category | Severe condition | Gap |
|---|---|---|
| `vomiting_nausea` | DKA | No specific Stage A sign; reached only through `relevant_history contains diabetes` + `ds_vomiting_every` |
| `vomiting_nausea` | Poisoning | No worker-observable sign at Stage A; reached only through `poisoning_suspected` question |
| `urinary_symptoms` | STI | No Stage A sign; reached through `urethral_discharge` question |
| `cold_sore_throat` | Paediatric pneumonia (rate-based) | IMCI respiratory rate cut-points need counted rate, not visual sign; `fast_breathing_child` uses visual + count |
| `cold_sore_throat` | Rheumatic fever | Latency period means it presents 2-4 weeks after sore throat, in a different branch (`joint_pain`); this branch can only flag the precursor |
| `weakness_unwell` | Diabetes (undiagnosed) | No Stage A sign; `polyuria_polydipsia` screens |
| `weakness_unwell` | Hypothyroidism | No worker-observable proxy at all |
| `weakness_unwell` | Depression | No worker-observable proxy at Stage A; `mood_change` screens |
| `weakness_unwell` | HIV | No worker-observable proxy; TB-SCREEN catches co-infection pattern |
| `weight_loss` | HIV | Same as weakness_unwell |
| `weight_loss` | Hyperthyroidism | No worker-observable proxy |
| `weight_loss` | Early malignancy | No worker-observable sign for early stage |

### G-11 extensions

The corpus returns nothing for `acidity_heartburn` and `cold_sore_throat` terms. These two branches
are fully `ASSUMED` in qualifier grounding, same as `chest_pain` (batch 1), `back_neck_pain` and
`injury` (batch 2).

### G-17 — `vomiting_present` in `vomiting_nausea` is a sentinel (DERIVED) node

`VMT-05` auto-sets `vomiting_present` based on `danger_signs` rather than asking the worker. This
is a departure from the schema's assumption that every field is either ANSWERED or NOT_ASKED. The
DERIVED provenance is used, matching `severity_band`'s existing precedent. **Schema owner should
confirm that DERIVED is valid for a shared fieldId set by a sentinel node rather than by
computation over a numeric score.** If not, the alternative is to ask the worker the question
(tautological for this branch but conformant).

### G-18 — `poisoning_suspected` routes to `emergency_poisoning` from within a non-Tier-0 branch

This is the first case in any batch where a question deep inside a Tier-1 branch triggers a
Tier-0 route. The route is clinically correct (poisoning is a Tier-0 emergency), but it raises
a schema question: should any non-Stage-A node be able to route-and-terminate? Batch 1 §6.4's
conservative resolution says sub-tree gateways raise only, but this is a direct branch node, not
a sub-tree. **The route is authored as written; flagged for the schema owner.**

---

## 12. Boundaries observed

No source, generator, dataset, model, config, questionnaire or tree runtime file was written,
edited or deleted in either repo. The SaMDClassifier repo was **read only** — one CSV read for
the §0.2 measurement pass. No `.env`, `local.properties` or credential file was opened at any
point. No commit, no staging. No branch was authored in code, no dataset generated, no model
trained. The only file written is this memo.

Batch 3 is `vomiting_nausea`, `acidity_heartburn`, `urinary_symptoms`, `cold_sore_throat`,
`weakness_unwell`, `body_ache`, `weight_loss`, plus the G-15 amendment. Nothing beyond these was
authored. The §6.5 order continues with `oedema` → `pallor_anaemia` → `dizziness` → `itching` →
`skin_infection` → `antenatal_visit` at batch 4, which are the final six clinical branches plus
the six Tier-0 emergency routers.
