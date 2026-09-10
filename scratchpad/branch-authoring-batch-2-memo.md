# Branch authoring — Batch 2 (STEP 1, read-only design memo)

Run date: 2026-09-07. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af` — **read only; `git status --short` there is untracked
scratchpad files only, no staged or committed changes.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
generator, dataset, model, config or tree runtime file touched, in either repo. No branch authored
in code, no dataset generated, no model trained. No `.env`, `local.properties` or credential file
opened at any point. No commit, no staging.

**Inputs treated as binding and not re-derived:**
`scratchpad/questionnaire-tree-design-memo.md` (THE schema: §1 branch schema, §6 five-stage
template, §3/§4/§5 the three reference branches), `scratchpad/reason-for-encounter-category-system-memo.md`
(every category constant below is transcribed from its §7, not invented; RF-1…RF-4 from its §4.4),
`scratchpad/dataset-regeneration-design-memo.md` (these branches are what the generator walks),
`scratchpad/branch-authoring-batch-1-memo.md` (the batch-1 reference, its §7 shared registry,
its findings G-1…G-13).

**Also read on disk this session, read-only:** `ConsultationScreen.kt:87-107` (the shipped
`DURATION_BUCKETS`, `ONSET_OPTIONS`, `HISTORY_CHIPS`, `IMPACT_CHIPS`),
`SaMDClassifier/dataset/canonical_dataset.csv` (one measurement pass, §0.2).

**Scope.** Batch 2 is the next group of tree memo §6.5's authoring order: `joint_pain`,
`back_neck_pain`, `known_diabetes`, `headache`, `injury`. Plus the `fever` 1.0.1 amendment
(CLEANUP, resolving G-8/G-9/G-10). Nothing beyond these is authored here.

---

## 0. Operator decisions applied, and the measurement pass

### 0.1 Three operator decisions, now binding

**DECISION-1 (resolves G-1).** A `REFER_EMERGENCY` disposition floor forces abstention, on any
channel that raises it — tree or vitals. When a branch's `dispositionFloor` reaches
`REFER_EMERGENCY` via a raise-and-continue gateway, the branch emits its `BranchOutput` with an
explicit `abstain: true` marker, the classifier is not called, and the device surfaces the
emergency referral with no differential. This extends the vitals-channel Path-1 abstention rule
(dataset memo §6.1) to the presentation channel.

Every branch in this batch reflects this: a raise-and-continue gateway that reaches
`REFER_EMERGENCY` still forces abstention at branch end, exactly as a Tier-0 `routeTo` does. The
five batch-1 emergencies with no Tier-0 target (GW-CGH-EMG-4, GW-DIA-EMG-3, GW-DIA-EMG-4,
GW-ABD-EMG-3, GW-ABD-EMG-4, GW-ABD-EMG-5, GW-BRE-EMG-2…6, GW-CPN-EMG-2…4) are now safe by this
rule rather than by a new category — the classifier is never called when the floor is
`REFER_EMERGENCY`.

**DECISION-2 (resolves G-6/G-7).** The node ceiling counts the **expanded** path the worker walks,
sub-trees included, and the ceiling is **20**. Every branch in this batch fits within 20 nodes on
its worst-case expanded path. The ceiling of 12 for authored (non-gateway, non-subtree) nodes is
retired. The new ceiling of 20 is the expanded worst-case path including sub-tree nodes.

**CLEANUP (resolves G-8/G-9/G-10).** The `fever` 1.0.1 amendment is authored in §7 below as part
of this batch.

### 0.2 The measurement pass

One read-only pass over `canonical_dataset.csv` (15,105 labelled rows), extending batch 1's §0.2
to this batch's terms:

```
term                                    n      top label   purity   notes
joint pain                           3290      E66         67%      noise — E66/M17 artifact
joint swelling                       1315      M17         99%      strong signal
morning stiffness under 30 minutes   2666      M17         99%      strong signal
headache                              844      I10         49%      noise — no headache class
severe headache                         83      I10         89%      strong: I10 hypertensive
neck stiffness                         212      I10         80%      captured as HTN co-symptom
photophobia                            106      G43.9       80%      strong: migraine
blurred vision                         511      I10         79%      strong: hypertensive
frequent urination                      87      E11         90%      strong: diabetes
excessive thirst                       111      E11         79%      strong: diabetes
burning micturition                    246      N39.0       91%      strong: UTI
```

**Three readings for this batch.**

**First: `joint_pain` is noise, `joint_swelling` and `morning_stiffness` are signal.** Bare
`joint pain` resolves to the E66/M17 artifact at 67% — it is the same BMI-prior pattern batch 1
measured everywhere. But `joint swelling` → M17 at 99% on 1,315 rows, and
`morning stiffness under 30 minutes` → M17 at 99% on 2,666. The qualifier separates. This directly
informs the `joint_pain` Stage C qualifier design: `joint_swelling` and `morning_stiffness` are the
measured discriminative nodes.

**Second: `headache` is noise, `severe headache` and `photophobia` are signal.** Bare `headache`
→ I10 at 49% is meaningless (the hypertension prior). But `severe headache` → I10 89%
(hypertensive headache signal) and `photophobia` → G43.9 80% (migraine signal). The qualifiers
`worst_ever_sudden`, `photophobia`, and `neck_stiffness` are the ones that move the differential.

**Third: the diabetes qualifiers replicate.** `frequent urination` → E11 90% and
`excessive thirst` → E11 79% both clear the bar. `blurred vision` → I10 79% is a hypertensive
signal, not diabetic, but its presence in a `known_diabetes` branch still carries information.

**Fourth — the absence.** The corpus returns **nothing** for back pain, neck pain, injury, wound,
fracture, burn, fall, trauma, accident, deformity, or any injury term. Zero rows. These categories
are entirely absent from the current 18-label space and from the 118-term symptom pool. Consequence:
every qualifier in `back_neck_pain` and `injury` is grounded on clinical protocol alone, every
answer-model entry is `ASSUMED` under dataset memo §2.3, and **no number in those two branches may
be presented as measured.** This is the same situation as `chest_pain` in batch 1 (G-11), now
applying to two more branches.

---

## 1. Fever 1.0.1 amendment — resolving G-8, G-9, G-10

Three corrections to the `fever` reference branch, each stated as the G-finding it resolves and
each an R4 change requiring `fever`-row regeneration. `branchVersion` bumps from `1.0.0-draft` to
`1.0.1-draft`.

### 1.1 G-8 — Add the missing Stage-B `progression` node

The `fever` worked example (tree memo §3.6) emits `progression { ANSWERED, worse, TAP }`, but the
branch has no `progression` node. Batch 1 authors an explicit Stage-B `progression` node in all
five of its branches (batch 1 §1.3). `fever` must do the same.

**Amendment.** Insert `FV-00B` after `FV-00`/`FV-G1` and before `FV-01`:

```
FV-00B  fieldId: progression   answerType: SINGLE_CHOICE   (shared, §2.3)
Q: "Since it started, is the fever better, the same, or worse?"
  pr_better  "Better"         → FV-00C
  pr_same    "About the same" → FV-00C
  pr_worse   "Worse"          → FV-00C
  pr_unknown "Not known"      → FV-00C
```

And insert `FV-00C` after `FV-00B`:

```
FV-00C  fieldId: prior_treatment_taken   answerType: MULTI_CHOICE   (shared, §2.3)
Q: "Has the patient already taken anything for this fever?"
  pt_none · pt_home_remedy · pt_pharmacy_medicine · pt_ayush · pt_other_facility · pt_unknown
  (all options → FV-01)
```

`FV-11` (`prior_treatment_taken`) is **removed** — it is now at Stage B, matching all five
batch-1 branches. The renumbering makes `FV-01` still point to `fever_duration_band`.

**Impact on existing node IDs.** `FV-01` through `FV-10`, `FV-12`, `FV-END`, `FV-S1` are
unchanged in ID and meaning. `FV-11` is deleted (its role is now `FV-00C`). Gateway placements
are unaffected — `FV-G1` still lives after `FV-00`, `FV-G2` still evaluates after the TB-screen
fields.

**R4 impact.** Two nodes added (`FV-00B`, `FV-00C`), one deleted (`FV-11`). Net +1 node. All
`fieldId`s and `optionId`s unchanged — it is a structural reordering, not a field rename. But path
composition changes, so `branch_version` must bump and affected rows must regenerate.

### 1.2 G-9 — Rename `fever_pattern`'s options from `fp_*` to `fpat_*`

`fever_pattern` uses `fp_stepladder`, `fp_cyclical`, `fp_evening`, `fp_continuous`, `fp_mild`,
`fp_unknown`. `fever_present` uses `fp_yes`, `fp_no`, `fp_unknown`. The `fp_` prefix collision
is a reviewer hazard.

**Amendment.** Rename all `fever_pattern` options:

| Old optionId | New optionId |
|---|---|
| `fp_stepladder` | `fpat_stepladder` |
| `fp_cyclical` | `fpat_cyclical` |
| `fp_evening` | `fpat_evening` |
| `fp_continuous` | `fpat_continuous` |
| `fp_mild` | `fpat_mild` |
| `fp_unknown` | `fpat_unknown` |

All `valueToken`s, `questionText`, `helpText` and `displayText` unchanged. Gateway rules
referencing these options (none in the current `fever` branch — gateways read `fever_duration_band`
and `fever_present`, not `fever_pattern` directly) need no update. FEVER-QUAL v1.1's `FQ-02`
carries the `fever_pattern` field and is also renamed accordingly.

**R4 impact.** `optionId` rename → dataset column rename (`fever_pattern__fpat_*` instead of
`fever_pattern__fp_*`). Requires `fever`-row regeneration and FEVER-QUAL referrer regeneration.

### 1.3 G-10 — Reconcile `tb_contact` by referencing `TB-SCREEN` sub-tree

`fever` currently asks FV-08 (`cough_ge_2_weeks`), FV-09 (`weight_loss_present`, `night_sweats`),
and FV-10 (`exposure_context.ex_tb_contact`) inline. The `cough` branch asks the same four facts
via `TB-SCREEN`. The result: `tb_contact` exists as both a SINGLE field (`tbc_yes`/`tbc_no`/
`tbc_unknown`, batch 1 §7.2) and as a MULTI option (`ex_tb_contact` inside `exposure_context`).
Two columns for one clinical fact, which tree memo §5 note 1 names as the failure mode.

**Amendment.** Replace FV-08, FV-09 and FV-10 with:

```
FV-S2  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
       on return → FV-G2, then FV-S3

FV-S3  SubtreeRef → EXPOSURE-CONTEXT-FEVER   (new sub-tree, see §8)
       on return → FV-12
```

The `exposure_context` field survives as a new sub-tree `EXPOSURE-CONTEXT-FEVER` (§8), but **with
`ex_tb_contact` removed** — that question is now asked by `TB-SCREEN`'s `TBS-04`. The `tb_contact`
fieldId from `TB-SCREEN` replaces the inline `ex_tb_contact` option.

**New sub-tree: `EXPOSURE-CONTEXT-FEVER`** v1.0.0-draft

```
ECF-01  fieldId: exposure_context   answerType: MULTI_CHOICE
Q: "Anything around the patient that could explain the fever?"
  ex_mosquito   "Mosquitoes or standing water at home"   → ECF-RETURN
  ex_household  "Someone else at home has fever"         → ECF-RETURN
  ex_travel     "Travelled in the last month"            → ECF-RETURN
  ex_water_soil "Works in paddy field / flood water / with animals" → ECF-RETURN
  ex_none       "None of these"                          → ECF-RETURN
  ex_unknown    "Not known"                              → ECF-RETURN

ECF-RETURN
```

Note: `ex_tb_contact` is **removed** from the option set. The old routing `(ex_tb_contact → FV-G2)`
is now handled by `TB-SCREEN` fields feeding `GW-FEV-URG-1`.

**Impact on GW-FEV-URG-1.** The rule changes from:

```
anyOf[ fever_duration_band == fd_gt_14,
       cough_ge_2_weeks == c2_yes,
       weight_loss_present == wl_yes,
       exposure_context contains ex_tb_contact ]
```

to:

```
anyOf[ fever_duration_band == fd_gt_14,
       cough_ge_2_weeks == c2_yes,
       weight_loss_present == wl_yes,
       night_sweats == ns_yes,
       tb_contact == tbc_yes ]
```

This is clinically equivalent (adds `night_sweats` explicitly, which was previously part of
FV-09's compound node and evaluated there), and now reads the same shared `fieldId`s that
`cough`'s `GW-CGH-URG-1` reads.

**R4 impact.** `optionId` removal (`ex_tb_contact`), `fieldId` change (adds TB-SCREEN fields
to fever's path). Requires complete fever-row regeneration.

### 1.4 Fever 1.0.1 expanded node count

Original fever 1.0.0: FV-00, FV-01…FV-12, FV-S1, FV-END = 15 authored + RASH-MORPH sub-tree.
Fever 1.0.1 after amendment:

Authored non-gateway: FV-00, FV-00B, FV-00C, FV-01, FV-02, FV-03, FV-04, FV-05, FV-S1, FV-06,
FV-07, FV-S2 (TB-SCREEN ref), FV-S3 (EXPOSURE-CONTEXT-FEVER ref), FV-12, FV-END = **15 nodes**.

Expanded worst case (all sub-trees walked): 15 + RASH-MORPH (7 from RSH-03…RSH-09) + TB-SCREEN
(4) + EXPOSURE-CONTEXT-FEVER (1) = **27**. This exceeds the DECISION-2 ceiling of 20.

> **G-14 — Fever 1.0.1 exceeds the 20-node expanded ceiling.** The rash sub-tree path adds 7
> nodes; the TB-SCREEN adds 4; the exposure-context adds 1. On the worst-case path (fever +
> rash + TB-SCREEN + exposure), the expanded count is 27. This was always true for fever
> 1.0.0 — the reference branch predates the ceiling — and the G-7 finding already flagged it.
> Resolution options: (a) split `fever` into `fever_acute` and `fever_subacute_chronic`, each
> fitting within 20; (b) accept `fever` as the one branch that exceeds, with the justification
> that it is the highest-volume and highest-severity category. **Flagged for the operator; not
> resolved here.**

---

## 2. Branch: `joint_pain`

### 2.1 Category constants — transcribed from category memo §7 Chapter L

```
categoryId               = "joint_pain"
displayName              = "Joint pain or swelling"
tier                     = CORE
icpc3Chapter             = L
icpc3Basis               = L20, L15
icd10Mapping             = M25.5
anchor                   = { value: top-5 individual complaint at Odisha PHC,
                             sourceType: IND-PRESENT, source: Gupta P et al. PLOS GPH 2024;
                             COPCORD OA 4.39% (IND-POP) }
modelBehaviour           = LEARNED
gatewayStrength          = MODERATE
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ septic arthritis, chikungunya, acute rheumatic fever,
                             gout, TB of joint ]
branchVersion            = 1.0.0-draft
```

### 2.2 Stage A — JPN-00, the danger-sign screen

```
JPN-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_hot_red_swollen "One joint is very hot, red and swollen"         → JPN-G1
  ds_cannot_bear_weight "Cannot put any weight on the leg"            → JPN-G1
  ds_high_fever_joint "High fever with the joint pain"                → JPN-G1
  ds_joint_deformity "Obvious deformity of a limb or joint"          → JPN-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"    → JPN-G1
  ds_cannot_feed     "Unable to drink or feed"                       → JPN-G1
  ds_none            "None of these"                                 → JPN-01
  ds_unknown         "Not known"                                     → JPN-01
```

**Severe-condition → danger-sign derivation.**

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Septic arthritis | `ds_hot_red_swollen`, `ds_high_fever_joint` | covered — the acutely hot, swollen single joint with fever is the classic sign |
| Acute rheumatic fever | `ds_high_fever_joint` + migratory pattern (JPN-07) | covered as a pattern; **no Stage A sign distinguishes it from other febrile arthritis — accepted, rheumatic fever is reached by GW-JPN-URG-2** |
| Chikungunya | `ds_high_fever_joint` — but the fever branch is the owning category; chikungunya presenting as joint pain alone has no distinguishing Stage A sign | by design — FEVER-QUAL cross-link carries it |
| Gout | `ds_hot_red_swollen` | covered for the acute attack; **chronic gout presenting as tophaceous deformity has no specific Stage A sign — G-2 gap** |
| TB of joint | *none in Stage A* | **by design — cold swelling with insidious onset is reached through GW-JPN-URG-3 off duration plus TB-SCREEN** |

### 2.3 Stage B — shared core

`JPN-01 progression` and `JPN-02 prior_treatment_taken`, verbatim as batch 1 §1.3, `next` →
JPN-03 and JPN-04 respectively.

### 2.4 Stage C — the category qualifiers

```
JPN-03  fieldId: joints_involved   answerType: SINGLE_CHOICE
Q: "Which joints are affected?"
help: "Ask the patient to point to every joint that hurts."
  ji_one_large     "One large joint (knee, hip, shoulder, elbow, ankle)"  → JPN-04
  ji_few_large     "A few large joints"                                   → JPN-04
  ji_small_hands   "Small joints of the hands or feet"                    → JPN-04
  ji_many_both     "Many joints — both large and small"                   → JPN-04
  ji_spine         "The back or neck, not a limb joint"                   → JPN-04
  ji_unknown       "Not known"                                            → JPN-04

JPN-04  fieldId: joint_swelling   answerType: SINGLE_CHOICE   (shared — §8 registry)
Q: "Is there visible swelling of the joint?"
  js_yes_hot  "Yes — swollen and hot or red"     → JPN-G2
              valueToken: "hot swollen joint"
  js_yes_cold "Yes — swollen but not hot"        → JPN-G2
              valueToken: "joint swelling"
  js_no       "No swelling seen"                 → JPN-05
  js_unknown  "Not known"                        → JPN-05

JPN-05  fieldId: morning_stiffness   answerType: SINGLE_CHOICE
Q: "Does the patient feel stiff in the mornings?"
help: "Ask how long it takes to loosen up after waking."
  ms_gt_30min  "Yes — stiffness lasts more than 30 minutes"   → JPN-06
               valueToken: "morning stiffness over 30 minutes"
  ms_lt_30min  "Yes — stiffness lasts less than 30 minutes"   → JPN-06
               valueToken: "morning stiffness under 30 minutes"
  ms_no        "No morning stiffness"                         → JPN-06
  ms_unknown   "Not known"                                    → JPN-06

JPN-06  fieldId: pain_on_weight_bearing   answerType: SINGLE_CHOICE
Q: "Is the pain worse when standing or walking?"
  pw_yes       "Yes — worse on weight bearing"     → JPN-07
  pw_no        "No — worse at rest or at night"    → JPN-07
  pw_both      "Both"                              → JPN-07
  pw_unknown   "Not known"                         → JPN-07

JPN-07  fieldId: migratory_pattern   answerType: SINGLE_CHOICE
Q: "Does the pain move from one joint to another?"
  mp_migratory "Yes — one joint gets better as another starts"  → JPN-G2
  mp_additive  "No — more joints are added without the first getting better" → JPN-08
  mp_fixed     "Stays in the same joint(s)"                     → JPN-08
  mp_unknown   "Not known"                                      → JPN-08
```

**Why `joint_red_hot` (tree memo §2.5) is not a separate node.** `joint_swelling.js_yes_hot`
already records the hot/red distinction as a single observation. A second field over the same axis
would be a second column for one fact. Retired (§8.4).

**Corpus grounding.** `joint swelling` → M17 at 99% on 1,315 rows (§0.2), `morning stiffness
under 30 minutes` → M17 at 99% on 2,666 rows. These two nodes carry the measured discriminative
signal.

### 2.5 Stage D/E — exposure/context and cross-links

```
JPN-08  fieldId: recent_injury_to_joint   answerType: SINGLE_CHOICE
Q: "Was there any injury or fall affecting this joint?"
  ri_yes     "Yes"        → JPN-S1
  ri_no      "No"         → JPN-S1
  ri_unknown "Not known"  → JPN-S1

JPN-S1  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
        on return → JPN-G3, then JPN-S2

JPN-S2  SubtreeRef → FEVER-QUAL v1.1   (batch 1 §6.3)
        on return → JPN-G4, then JPN-END

JPN-END TerminalNode
```

### 2.6 Gateways

| Gateway | Rule (fixed boolean over collected fieldIds) | Effect | severeConditions carried | DECISION-1 abstention |
|---|---|---|---|---|
| **GW-JPN-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | sepsis, meningococcaemia | N/A — Tier-0 route |
| **GW-JPN-EMG-2 (septic arthritis)** | `allOf[ danger_signs contains ds_hot_red_swollen, danger_signs contains ds_high_fever_joint ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **septic arthritis**, gonococcal arthritis | **Yes — dispositionFloor reaches REFER_EMERGENCY, branch abstains** |
| **GW-JPN-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-JPN-URG-1 (septic/single hot)** | `anyOf[ joint_swelling equals js_yes_hot, allOf[ joints_involved equals ji_one_large, fever_present equals fp_yes ] ]` | `raisesTo: REFER_URGENT` | septic arthritis, crystal arthritis (acute gout) |
| **GW-JPN-URG-2 (rheumatic fever)** | `allOf[ migratory_pattern equals mp_migratory, fever_present equals fp_yes ]` | `raisesTo: REFER_URGENT` | acute rheumatic fever, chikungunya, gonococcal arthritis |
| **GW-JPN-URG-3 (TB joint)** | `anyOf[ tb_contact equals tbc_yes, allOf[ duration_bucket in {month_plus, chronic}, joint_swelling equals js_yes_cold ] ]` | `raisesTo: REFER_URGENT` | TB of joint, chronic osteomyelitis |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` at entry | the category's full list |

Node placement: `JPN-G1` after JPN-00 evaluates EMG-1, EMG-2, EMG-3; `JPN-G2` evaluates URG-1 as
its fields land; `JPN-G3` after TB-SCREEN return evaluates URG-3; `JPN-G4` after FEVER-QUAL return
evaluates URG-2.

### 2.7 Worked BranchOutput — 35-year-old man, 3 weeks of painful right knee, cold swelling, no fever, TB contact

Path: no danger signs → worse → pharmacy medicine → one large joint → swollen but not hot → no
morning stiffness → worse on weight bearing → fixed → no injury → TB-SCREEN (no / yes / no / yes)
→ no fever.

```
BranchOutput {
  categoryId: "joint_pain"
  branchVersion: "1.0.0-draft"
  pathTaken: [JPN-00, JPN-01, JPN-02, JPN-03, JPN-04, JPN-G2, JPN-05, JPN-06, JPN-07,
              JPN-08, JPN-S1, TBS-01, TBS-02, TBS-03, TBS-04, JPN-G3,
              JPN-S2, FQ-00, JPN-G4, JPN-END]
  fields: {
    danger_signs             { ANSWERED, [ds_none],              TAP }
    progression              { ANSWERED, pr_worse,               TAP }
    prior_treatment_taken    { ANSWERED, [pt_pharmacy_medicine], TAP }
    joints_involved          { ANSWERED, ji_one_large,           TAP }
    joint_swelling           { ANSWERED, js_yes_cold,            VOICE_CONFIRMED }
    morning_stiffness        { ANSWERED, ms_no,                  TAP }
    pain_on_weight_bearing   { ANSWERED, pw_yes,                 TAP }
    migratory_pattern        { ANSWERED, mp_fixed,               TAP }
    recent_injury_to_joint   { ANSWERED, ri_no,                  TAP }
    cough_ge_2_weeks         { ANSWERED, c2_no,                  TAP }    // TB-SCREEN
    weight_loss_present      { ANSWERED, wl_yes,                 TAP }    // TB-SCREEN
    night_sweats             { ANSWERED, ns_no,                  TAP }    // TB-SCREEN
    tb_contact               { ANSWERED, tbc_yes,                TAP }    // TB-SCREEN
    fever_present            { ANSWERED, fp_no,                  TAP }    // FEVER-QUAL gate
    fever_duration_band      { NOT_ASKED, null, — }                       // gate closed
    fever_pattern            { NOT_ASKED, null, — }                       // gate closed
    bleeding_manifestation   { NOT_ASKED, null, — }                       // gate closed
    duration_bucket          { ANSWERED, week_plus,              PREFILL_MEASURED }
    onset_pattern            { ANSWERED, gradual,                TAP }
    severity_score           { ANSWERED, 5,                      TAP }
    severity_band            { ANSWERED, moderate,               DERIVED }
    relevant_history         { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [ { GW-JPN-URG-3,
                     "tb_contact==tbc_yes OR
                      (duration_bucket in {month_plus,chronic} AND joint_swelling==js_yes_cold)",
                     [TB of joint, chronic osteomyelitis] } ]
  routedTo: null
  abstain: false
  attachments: []
}
```

The TB gateway fired on `tbc_yes` alone. The cold swelling clause did not fire independently
(duration is `week_plus`, not `month_plus` or `chronic`), but the `tbc_yes` arm was sufficient.
The tree named no disease; `js_yes_cold` + `tbc_yes` is the observation record.

### 2.8 Node budget

Authored non-gateway nodes: **12** (JPN-00…JPN-08, JPN-S1, JPN-S2, JPN-END).
Expanded worst case (TB-SCREEN 4 + FEVER-QUAL gated at FQ-00 only = 1): 12 + 4 + 1 = **17**.
With full FEVER-QUAL walked (fever → duration → pattern → bleeding): 12 + 4 + 4 = **20**.
**At the DECISION-2 ceiling, zero headroom on the full FEVER-QUAL path.**

### 2.9 Conformance

| Check | Status |
|---|---|
| C1 | Every option has an explicit `next`. No SLM in branching logic |
| C2 | No score, no rank, no condition name in BranchOutput |
| C3/RF-2 | Floor starts PHYSICIAN_REVIEW_MANDATORY, raised only by `max()` |
| C4 | `routeTo: emergency_unconscious` is Tier-0, ALWAYS_ABSTAIN |
| C5 | Physician gate untouched |
| BR-1 | All options have explicit successor |
| BR-2 | All SINGLE nodes carry `*_unknown`; MULTI nodes carry `*_none`+`*_unknown` |
| NR-1 | No severe condition named in any frame. `ds_hot_red_swollen` is an appearance; "septic arthritis" appears nowhere in worker-facing text |
| DECISION-1 | GW-JPN-EMG-2 and GW-JPN-EMG-3 raise to REFER_EMERGENCY → branch abstains |
| DECISION-2 | Expanded worst case: **20** ≤ 20 ✔ |

---

## 3. Branch: `back_neck_pain`

### 3.1 Category constants — category memo §7 Chapter L

```
categoryId               = "back_neck_pain"
displayName              = "Back or neck pain"
tier                     = CORE
icpc3Chapter             = L
icpc3Basis               = L01, L02, L03
icd10Mapping             = M54.5, M54.2
anchor                   = { value: ~30% of chapter L ASSUMED,
                             sourceType: IND-POP cross-check: COPCORD back pain 17.3% rural females }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ spinal TB (Pott's), cauda equina, vertebral fracture,
                             malignancy, referred renal/aortic pain ]
branchVersion            = 1.0.0-draft
```

> **Grounding warning.** The corpus returns **nothing** for any back or neck pain term (§0.2).
> Every qualifier below is grounded on clinical protocol. Every answer-model entry is `ASSUMED`.
> **G-11 extended.**

### 3.2 Stage A — BNP-00

```
BNP-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_loss_bladder_bowel "Lost control of bladder or bowels"            → BNP-G1
  ds_numbness_saddle   "Numbness around the bottom or genitals"       → BNP-G1
  ds_both_legs_weak    "Both legs suddenly weak or numb"               → BNP-G1
  ds_pain_after_fall   "The pain started after a fall or injury"       → BNP-G1
  ds_pain_worst_lying  "Pain worse at night or when lying flat"        → BNP-G1
  ds_fever_with_spine  "Fever with the back or neck pain"              → BNP-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake"    → BNP-G1
  ds_cannot_feed       "Unable to drink or feed"                       → BNP-G1
  ds_none              "None of these"                                 → BNP-01
  ds_unknown           "Not known"                                     → BNP-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Cauda equina syndrome | `ds_loss_bladder_bowel`, `ds_numbness_saddle`, `ds_both_legs_weak` | covered — the classic triad |
| Vertebral fracture | `ds_pain_after_fall` (+ trauma history at BNP-08) | covered for traumatic fractures; **pathological fracture (osteoporosis, malignancy) has no trauma trigger — reached through GW-BNP-URG-3** |
| Spinal TB (Pott's) | `ds_fever_with_spine`, `ds_pain_worst_lying` + TB-SCREEN | covered as a pattern |
| Malignancy (spinal mets) | `ds_pain_worst_lying` (the red-flag night pain) | covered for this single sign; **no worker-observable proxy for weight loss at Stage A — weight loss is caught by TB-SCREEN's `weight_loss_present`** |
| **Referred renal/aortic pain** | ***none in Stage A*** | **G-2 gap.** Renal colic and aortic aneurysm can present as back pain. There is no worker-observable sign that separates them from MSK back pain at Stage A. They are reached only through the loin-to-groin radiation pattern at BNP-05 and severity. **Physician review required** |

### 3.3 Stage B

`BNP-01 progression`, `BNP-02 prior_treatment_taken`, verbatim as batch 1 §1.3, `next` → BNP-03
and BNP-04 respectively.

### 3.4 Stage C — the category qualifiers

```
BNP-03  fieldId: pain_site_spine   answerType: SINGLE_CHOICE
Q: "Where is the pain worst? Point to the spot."
  pss_neck       "Neck"                          → BNP-04
  pss_upper_back "Upper back, between the shoulder blades" → BNP-04
  pss_lower_back "Lower back"                    → BNP-04
  pss_whole_spine "All along the back"           → BNP-04
  pss_unknown    "Not known"                     → BNP-04

BNP-04  fieldId: radiation_to_leg   answerType: SINGLE_CHOICE
Q: "Does the pain go down into a leg?"
  rl_one_leg     "Yes — down one leg"              → BNP-05
                 valueToken: "radiculopathy"
  rl_both_legs   "Yes — both legs"                 → BNP-G2
                 valueToken: "bilateral radiculopathy"
  rl_to_groin    "Yes — down to the groin"         → BNP-05
                 valueToken: "loin to groin pain"
  rl_no          "No"                              → BNP-05
  rl_unknown     "Not known"                       → BNP-05

BNP-05  fieldId: numbness_weakness_limb   answerType: SINGLE_CHOICE   (shared — §8 registry)
Q: "Is there numbness, tingling, or weakness in an arm or leg?"
  nw_numbness     "Numbness or tingling"       → BNP-06
  nw_weakness     "Weakness — cannot lift or grip" → BNP-G2
  nw_both         "Both"                       → BNP-G2
  nw_no           "No"                         → BNP-06
  nw_unknown      "Not known"                  → BNP-06

BNP-06  fieldId: night_pain   answerType: SINGLE_CHOICE
Q: "Does the pain wake the patient at night?"
  np_yes     "Yes — wakes from sleep"          → BNP-G2
  np_no      "No — pain is better at rest"     → BNP-07
  np_unknown "Not known"                       → BNP-07

BNP-07  fieldId: bladder_bowel_disturbance   answerType: SINGLE_CHOICE
Q: "Any trouble with passing urine or stool?"
  bb_difficulty "Difficulty starting or stopping urine" → BNP-G1
  bb_leaking    "Leaking urine or stool"               → BNP-G1
  bb_no         "No trouble"                           → BNP-08
  bb_unknown    "Not known"                            → BNP-08
```

`bladder_bowel_disturbance` is asked at Stage C rather than only at Stage A because a patient
may not volunteer this symptom — it requires a direct question. `danger_signs.ds_loss_bladder_bowel`
catches the obvious case; this node catches the subtle one. Two fields for one clinical axis is
justified here because Stage A fires early and Stage C is the deliberate second pass.

### 3.5 Stage D/E

```
BNP-08  fieldId: trauma_history   answerType: SINGLE_CHOICE
Q: "Was there any fall, injury, or accident before the pain started?"
  th_fall_recent    "Yes — a fall in the last 2 weeks"       → BNP-G2
  th_injury_recent  "Yes — another type of injury recently"  → BNP-G2
  th_old_injury     "Yes — an old injury, months or years ago" → BNP-S1
  th_no             "No"                                     → BNP-S1
  th_unknown        "Not known"                              → BNP-S1

BNP-S1  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
        on return → BNP-G3, then BNP-S2

BNP-S2  SubtreeRef → FEVER-QUAL v1.1
        on return → BNP-G4, then BNP-END

BNP-END TerminalNode
```

### 3.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 abstention |
|---|---|---|---|---|
| **GW-BNP-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | spinal cord injury, sepsis | N/A — Tier-0 route |
| **GW-BNP-EMG-2 (cauda equina)** | `anyOf[ danger_signs contains ds_loss_bladder_bowel, danger_signs contains ds_numbness_saddle, danger_signs contains ds_both_legs_weak, allOf[ radiation_to_leg equals rl_both_legs, numbness_weakness_limb in {nw_weakness, nw_both} ], bladder_bowel_disturbance in {bb_difficulty, bb_leaking} ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **cauda equina syndrome**, spinal cord compression | **Yes — abstains** |
| **GW-BNP-EMG-3** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-BNP-URG-1 (fracture / trauma)** | `anyOf[ danger_signs contains ds_pain_after_fall, trauma_history in {th_fall_recent, th_injury_recent}, danger_signs contains ds_joint_deformity ]` | `raisesTo: REFER_URGENT` | vertebral fracture, spinal cord injury |
| **GW-BNP-URG-2 (red flags)** | `anyOf[ night_pain equals np_yes, numbness_weakness_limb in {nw_weakness, nw_both}, allOf[ duration_bucket in {month_plus, chronic}, weight_loss_present equals wl_yes ] ]` | `raisesTo: REFER_URGENT` | malignancy, spinal TB, chronic infection |
| **GW-BNP-URG-3 (spinal TB)** | `anyOf[ tb_contact equals tbc_yes, allOf[ fever_present equals fp_yes, duration_bucket in {week_plus, month_plus, chronic} ] ]` | `raisesTo: REFER_URGENT` | spinal TB (Pott's), spinal abscess |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `BNP-G1` after BNP-00 evaluates EMG-1, EMG-2, EMG-3; `BNP-G2` evaluates URG-1
and URG-2 as their fields land; `BNP-G3` after TB-SCREEN evaluates URG-3; `BNP-G4` after
FEVER-QUAL evaluates URG-2's fever clause.

### 3.7 Worked BranchOutput — 45-year-old man, 6 weeks of lower back pain, worse at night, weight loss, TB contact

```
BranchOutput {
  categoryId: "back_neck_pain"
  branchVersion: "1.0.0-draft"
  pathTaken: [BNP-00, BNP-01, BNP-02, BNP-03, BNP-04, BNP-05, BNP-06, BNP-G2,
              BNP-07, BNP-08, BNP-S1, TBS-01, TBS-02, TBS-03, TBS-04, BNP-G3,
              BNP-S2, FQ-00, FQ-01, FQ-02, FQ-03, BNP-G4, BNP-END]
  fields: {
    danger_signs             { ANSWERED, [ds_pain_worst_lying],   TAP }
    progression              { ANSWERED, pr_worse,                TAP }
    prior_treatment_taken    { ANSWERED, [pt_pharmacy_medicine],  TAP }
    pain_site_spine          { ANSWERED, pss_lower_back,          TAP }
    radiation_to_leg         { ANSWERED, rl_no,                   TAP }
    numbness_weakness_limb   { ANSWERED, nw_no,                   TAP }
    night_pain               { ANSWERED, np_yes,                  TAP }
    bladder_bowel_disturbance{ ANSWERED, bb_no,                   TAP }
    trauma_history           { ANSWERED, th_no,                   TAP }
    cough_ge_2_weeks         { ANSWERED, c2_no,                   TAP }
    weight_loss_present      { ANSWERED, wl_yes,                  TAP }
    night_sweats             { ANSWERED, ns_yes,                  TAP }
    tb_contact               { ANSWERED, tbc_yes,                 TAP }
    fever_present            { ANSWERED, fp_yes,                  TAP }
    fever_duration_band      { ANSWERED, fd_gt_14,                TAP }
    fever_pattern            { ANSWERED, fpat_evening,            TAP }
    bleeding_manifestation   { ANSWERED, [bl_none],               TAP }
    duration_bucket          { ANSWERED, month_plus,              PREFILL_MEASURED }
    onset_pattern            { ANSWERED, gradual,                 TAP }
    severity_score           { ANSWERED, 6,                       TAP }
    severity_band            { ANSWERED, moderate,                DERIVED }
    relevant_history         { ANSWERED, [no_known_history],      TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],      TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-BNP-URG-2,
      "night_pain==np_yes OR (duration_bucket in {month_plus,chronic} AND weight_loss_present==wl_yes)",
      [malignancy, spinal TB, chronic infection] },
    { GW-BNP-URG-3,
      "tb_contact==tbc_yes OR (fever_present==fp_yes AND duration_bucket in {week_plus,month_plus,chronic})",
      [spinal TB (Pott's), spinal abscess] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

### 3.8 Node budget

Authored non-gateway: **12** (BNP-00…BNP-08, BNP-S1, BNP-S2, BNP-END).
Expanded worst case: 12 + TB-SCREEN (4) + FEVER-QUAL full (4) = **20**. At DECISION-2 ceiling.

### 3.9 Conformance

Same structure as §2.9. All checks pass. DECISION-1: GW-BNP-EMG-2 and GW-BNP-EMG-3 raise to
REFER_EMERGENCY → branch abstains. DECISION-2: expanded worst case **20** ≤ 20 ✔.

---

## 4. Branch: `known_diabetes`

### 4.1 Category constants — category memo §7 Chapter T

```
categoryId               = "known_diabetes"
displayName              = "Known diabetes — check or follow-up"
tier                     = CORE
icpc3Chapter             = T
icpc3Basis               = T90
icd10Mapping             = E11
anchor                   = { value: AIIMS Bhopal diabetes 5.1% (IND-PRESENT, MP),
                             INDIAB national diabetes 11.4%, prediabetes 15.3%,
                             MP ratio ≤ 1:2 (IND-POP) }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ DKA, hypoglycaemia, diabetic foot, silent MI,
                             TB co-infection ]
branchVersion            = 1.0.0-draft
```

**Branch shape note.** Like `known_hypertension` (tree memo §4), this is a **chronic follow-up
shape** — the diagnosis is already known. The branch detects decompensation and complications,
not de novo diagnosis. Stage C contains compliance and complication-screening questions, not
symptom qualifiers.

### 4.2 Stage A — DM-00

```
DM-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_confusion       "Confused, or behaviour unusual"               → DM-G1
  ds_unconscious     "Unconscious, or very drowsy / hard to wake"   → DM-G1
  ds_vomiting_every  "Vomiting everything, cannot keep fluids down" → DM-G1
  ds_fast_deep_breath "Breathing fast and deep"                     → DM-G1
  ds_fruity_breath   "Breath smells sweet or fruity"                → DM-G1
  ds_sweating_shaky  "Sweating, shaking, or feeling faint"          → DM-G1
  ds_chest_pain      "Chest pain or heaviness"                      → DM-G1
  ds_foot_wound      "Open wound or black discolouration on the foot" → DM-G1
  ds_cannot_feed     "Unable to drink or feed"                      → DM-G1
  ds_none            "None of these"                                → DM-01
  ds_unknown         "Not known"                                    → DM-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| DKA | `ds_vomiting_every`, `ds_fast_deep_breath`, `ds_fruity_breath`, `ds_confusion` | covered — Kussmaul breathing + fruity breath + confusion is the classic triad |
| Hypoglycaemia | `ds_sweating_shaky`, `ds_confusion`, `ds_unconscious` | covered |
| Diabetic foot | `ds_foot_wound` | covered for the presenting ulcer/gangrene; **early neuropathic changes have no Stage A sign — reached through DM-07** |
| Silent MI | `ds_chest_pain` | covered — but the proxy is non-specific. **The MI may present as only fatigue or breathlessness, with no chest pain at all. G-2 gap. Physician review required on whether exertional breathlessness belongs in the danger-sign list for this category** |
| TB co-infection | *none in Stage A* | by design — TB-SCREEN cross-link, GW-DM-URG-3 |

### 4.3 Stage B

`DM-01 progression` and `DM-02 prior_treatment_taken` as batch 1 §1.3, except:
- `DM-01` question text adapted: "Since the last visit, is the diabetes better controlled, the same, or worse?"
- `DM-02` is semantically identical but the question refers to "diabetes medicines" rather than "anything for this".

Both use the same shared `fieldId`s and `optionId`s. `next` → DM-03 and DM-04.

### 4.4 Stage C — compliance and complication screening (the chronic shape)

```
DM-03  fieldId: visit_reason_subtype   answerType: SINGLE_CHOICE   (shared with known_hypertension)
Q: "Why has the patient come today?"
  vr_refill      "Routine check / medicine refill"     → DM-04
  vr_new_symptom "New complaint since last visit"      → DM-04
  vr_followup    "Follow-up after a referral"          → DM-04
  vr_first       "First visit for known diabetes"      → DM-04

DM-04  fieldId: diabetes_medication_adherence   answerType: SINGLE_CHOICE
Q: "Is the patient taking the diabetes medicine as advised?"
  dma_daily      "Every day as prescribed"             → DM-05
  dma_missed_some "Misses some days"                   → DM-05
  dma_stopped    "Stopped taking it"                   → DM-G2
  dma_none_given "Never been given medicine"           → DM-G2
  dma_unknown    "Not known"                           → DM-05

DM-05  fieldId: hypoglycaemia_episodes   answerType: SINGLE_CHOICE
Q: "Has the patient had episodes of sweating, shaking, or feeling faint — especially before meals?"
  he_frequent "Yes — more than once a week"            → DM-G2
  he_occasional "Yes — but rarely"                     → DM-06
  he_no       "No"                                     → DM-06
  he_unknown  "Not known"                              → DM-06

DM-06  fieldId: polyuria_polydipsia   answerType: SINGLE_CHOICE
Q: "Is the patient passing urine very often, or feeling very thirsty?"
  pp_both     "Yes — both"                             → DM-G2
              valueToken: "polyuria and polydipsia"
  pp_urine    "Mostly frequent urination"              → DM-07
              valueToken: "frequent urination"
  pp_thirst   "Mostly thirst"                          → DM-07
              valueToken: "excessive thirst"
  pp_no       "No"                                     → DM-07
  pp_unknown  "Not known"                              → DM-07

DM-07  fieldId: foot_ulcer_or_numbness   answerType: SINGLE_CHOICE
Q: "Look at both feet. Is there a wound, ulcer, or does the patient say the feet feel numb?"
help: "Remove footwear and inspect the soles."
  fun_wound   "Yes — wound or ulcer on the foot"       → DM-G2
  fun_numb    "Numbness or tingling in the feet"       → DM-08
  fun_both    "Both"                                   → DM-G2
  fun_no      "No wound and no numbness"               → DM-08
  fun_unknown "Not examined"                           → DM-08

DM-08  fieldId: visual_blurring   answerType: SINGLE_CHOICE
Q: "Has the patient's vision become blurred or worse?"
  vb_yes     "Yes"        → DM-G2
  vb_no      "No"         → DM-S1
  vb_unknown "Not known"  → DM-S1
```

**Corpus grounding.** `frequent urination` → E11 90% on 87 rows, `excessive thirst` → E11 79%
on 111 rows (§0.2). These two qualifiers carry a measured discriminative signal.

**Why `last_glucose_known` (tree memo §2.5) is not a node.** The `glucose` vital prefills from
`VitalsSnapshot` where measured. A node asking the patient to recall a previous number is unreliable
and duplicates the vital. The vital value (and its provenance) is the authoritative source. The
gateway reads the prefilled vital.

### 4.5 Stage D/E

```
DM-S1  SubtreeRef → TB-SCREEN   (batch 1 §6.1)
       on return → DM-G3, then DM-S2

DM-S2  SubtreeRef → FEVER-QUAL v1.1
       on return → DM-G4, then DM-END

DM-END TerminalNode
```

TB-SCREEN is justified here because diabetes-TB co-infection is in the `severeConditions` list
(India has among the highest diabetes-TB comorbidity worldwide; TB incidence in diabetics is 2-3×
general population).

### 4.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-DM-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | hypoglycaemic coma, DKA | N/A — Tier-0 |
| **GW-DM-EMG-2 (DKA)** | `anyOf[ danger_signs contains ds_fast_deep_breath, danger_signs contains ds_fruity_breath, allOf[ danger_signs contains ds_vomiting_every, danger_signs contains ds_confusion ] ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **DKA** | **Yes — abstains** |
| **GW-DM-EMG-3 (hypoglycaemia)** | `allOf[ danger_signs contains ds_sweating_shaky, danger_signs contains ds_confusion ]` | `raisesTo: REFER_EMERGENCY`, continue | **severe hypoglycaemia** | **Yes — abstains** |
| **GW-DM-EMG-4 (ACS)** | `danger_signs contains ds_chest_pain` | `raisesTo: REFER_EMERGENCY`, continue | **silent MI**, acute coronary syndrome in a diabetic | **Yes — abstains** |
| **GW-DM-EMG-5** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-DM-URG-1 (decompensation)** | `anyOf[ polyuria_polydipsia equals pp_both, hypoglycaemia_episodes equals he_frequent, allOf[ diabetes_medication_adherence in {dma_stopped, dma_none_given}, progression equals pr_worse ] ]` | `raisesTo: REFER_URGENT` | uncontrolled diabetes, recurrent hypoglycaemia |
| **GW-DM-URG-2 (foot)** | `anyOf[ foot_ulcer_or_numbness in {fun_wound, fun_both}, danger_signs contains ds_foot_wound ]` | `raisesTo: REFER_URGENT` | diabetic foot, peripheral vascular disease |
| **GW-DM-URG-3 (TB)** | `anyOf[ tb_contact equals tbc_yes, cough_ge_2_weeks equals c2_yes, weight_loss_present equals wl_yes, night_sweats equals ns_yes ]` | `raisesTo: REFER_URGENT` | TB co-infection |
| **GW-DM-ADH-1 (adherence)** | `diabetes_medication_adherence in {dma_stopped, dma_none_given}` | floor unchanged; sets flag | non-adherence — treatment decision, not triage |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `DM-G1` after DM-00 evaluates EMG-1…EMG-5; `DM-G2` evaluates URG-1 and URG-2 as
fields land; `DM-G3` after TB-SCREEN evaluates URG-3; `DM-G4` after FEVER-QUAL for completeness.

### 4.7 Worked BranchOutput — 55-year-old man, routine refill, medication stopped 2 weeks ago, frequent thirst, foot ulcer

```
BranchOutput {
  categoryId: "known_diabetes"
  branchVersion: "1.0.0-draft"
  pathTaken: [DM-00, DM-01, DM-02, DM-03, DM-04, DM-G2, DM-05, DM-06, DM-G2,
              DM-07, DM-G2, DM-08, DM-S1, TBS-01, TBS-02, TBS-03, TBS-04, DM-G3,
              DM-S2, FQ-00, DM-END]
  fields: {
    danger_signs               { ANSWERED, [ds_foot_wound],       TAP }
    progression                { ANSWERED, pr_worse,              TAP }
    prior_treatment_taken      { ANSWERED, [pt_none],             TAP }
    visit_reason_subtype       { ANSWERED, vr_refill,             TAP }
    diabetes_medication_adherence { ANSWERED, dma_stopped,        TAP }
    hypoglycaemia_episodes     { ANSWERED, he_no,                 TAP }
    polyuria_polydipsia        { ANSWERED, pp_thirst,             TAP }
    foot_ulcer_or_numbness     { ANSWERED, fun_wound,             TAP }
    visual_blurring            { ANSWERED, vb_no,                 TAP }
    cough_ge_2_weeks           { ANSWERED, c2_no,                 TAP }
    weight_loss_present        { ANSWERED, wl_no,                 TAP }
    night_sweats               { ANSWERED, ns_no,                 TAP }
    tb_contact                 { ANSWERED, tbc_no,                TAP }
    fever_present              { ANSWERED, fp_no,                 TAP }
    fever_duration_band        { NOT_ASKED, null, — }
    fever_pattern              { NOT_ASKED, null, — }
    bleeding_manifestation     { NOT_ASKED, null, — }
    duration_bucket            { ANSWERED, few_days,              PREFILL_MEASURED }
    onset_pattern              { ANSWERED, gradual,               TAP }
    severity_score             { ANSWERED, 4,                     TAP }
    severity_band              { ANSWERED, moderate,              DERIVED }
    relevant_history           { ANSWERED, [diabetes, hypertension], TAP }
    impact_on_daily_activities { ANSWERED, [cannot_do_household_chores], TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-DM-URG-1,
      "diabetes_medication_adherence==dma_stopped AND progression==pr_worse",
      [uncontrolled diabetes, recurrent hypoglycaemia] },
    { GW-DM-URG-2,
      "foot_ulcer_or_numbness==fun_wound OR danger_signs contains ds_foot_wound",
      [diabetic foot, peripheral vascular disease] },
    { GW-DM-ADH-1,
      "diabetes_medication_adherence==dma_stopped (informational)",
      [non-adherence] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

### 4.8 Node budget

Authored non-gateway: **12** (DM-00…DM-08, DM-S1, DM-S2, DM-END).
Expanded worst case: 12 + TB-SCREEN (4) + FEVER-QUAL gate-only (1) = **17**. Full FEVER-QUAL:
12 + 4 + 4 = **20**. At ceiling.

### 4.9 Conformance

All checks pass. DECISION-1: GW-DM-EMG-2, EMG-3, EMG-4, EMG-5 raise to REFER_EMERGENCY →
abstain. DECISION-2: **20** ≤ 20 ✔.

---

## 5. Branch: `headache`

### 5.1 Category constants — category memo §7 Chapter N

```
categoryId               = "headache"
displayName              = "Headache"
tier                     = CORE
icpc3Chapter             = N
icpc3Basis               = N01
icd10Mapping             = R51
anchor                   = { value: ~55% of chapter N ASSUMED }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY
severeConditions         = [ meningitis, cerebral malaria, stroke, subarachnoid haemorrhage,
                             hypertensive emergency, pre-eclampsia ]
branchVersion            = 1.0.0-draft
```

### 5.2 Stage A — HDC-00

```
HDC-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_worst_ever_sudden "The worst headache of their life, came on suddenly"  → HDC-G1
  ds_neck_stiff        "Neck stiffness, or cannot bend the neck forward"    → HDC-G1
  ds_confusion         "Confused, or behaviour unusual"                     → HDC-G1
  ds_one_side_weak     "Weakness or drooping on one side of the body"       → HDC-G1
  ds_vision_sudden     "Sudden loss of vision or double vision"             → HDC-G1
  ds_seizure           "Fits or convulsions"                                → HDC-G1
  ds_high_fever_head   "High fever with the headache"                       → HDC-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake"        → HDC-G1
  ds_cannot_feed       "Unable to drink or feed"                           → HDC-G1
  ds_none              "None of these"                                     → HDC-01
  ds_unknown           "Not known"                                         → HDC-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Subarachnoid haemorrhage | `ds_worst_ever_sudden`, `ds_neck_stiff` | covered — "thunderclap" pattern |
| Meningitis | `ds_neck_stiff`, `ds_high_fever_head`, `ds_confusion` | covered — the classic triad |
| Cerebral malaria | `ds_confusion`, `ds_high_fever_head`, `ds_seizure` + FEVER-QUAL | covered |
| Stroke | `ds_one_side_weak`, `ds_confusion`, `ds_vision_sudden` | covered — the FAST signs |
| Hypertensive emergency | `ds_confusion`, `ds_vision_sudden` + vitals (BP read by gateway) | covered via vitals; **no worker-observable sign distinguishes hypertensive headache from other severe headache — reached through GW-HDC-URG-1 off vitals** |
| Pre-eclampsia | `ds_vision_sudden`, `ds_confusion` + pregnancy + BP | covered as a pattern; GW-HDC-URG-2 adds the pregnancy gate |

### 5.3 Stage B

`HDC-01 progression`, `HDC-02 prior_treatment_taken`, as batch 1 §1.3. → HDC-03, HDC-04.

### 5.4 Stage C — the category qualifiers

```
HDC-03  fieldId: headache_site   answerType: SINGLE_CHOICE
Q: "Where is the headache?"
  hs_one_side    "One side of the head"                → HDC-04
                 valueToken: "unilateral headache"
  hs_both_sides  "Both sides, or all over"             → HDC-04
                 valueToken: "bilateral headache"
  hs_behind_eyes "Behind or around the eyes"           → HDC-04
                 valueToken: "retro-orbital headache"
  hs_back_head   "Back of the head and neck"           → HDC-04
                 valueToken: "occipital headache"
  hs_forehead    "Forehead"                            → HDC-04
                 valueToken: "frontal headache"
  hs_unknown     "Not known"                           → HDC-04
                 valueToken: "headache"

HDC-04  fieldId: headache_character   answerType: SINGLE_CHOICE
Q: "What does the headache feel like?"
  hc_throbbing   "Throbbing or pounding"               → HDC-05
                 valueToken: "throbbing headache"
  hc_pressing    "Pressing or tight, like a band"      → HDC-05
                 valueToken: "tension headache"
  hc_stabbing    "Sharp or stabbing"                   → HDC-05
                 valueToken: "stabbing headache"
  hc_unknown     "Not known"                           → HDC-05
                 valueToken: "headache"

HDC-05  fieldId: photophobia   answerType: SINGLE_CHOICE
Q: "Does light bother the patient, or make the headache worse?"
  ph_yes     "Yes — light makes it worse"              → HDC-06
  ph_no      "No"                                      → HDC-06
  ph_unknown "Not known"                               → HDC-06

HDC-06  fieldId: vomiting_present   answerType: SINGLE_CHOICE   (shared, batch 1 §7.2)
Q: "Is the patient vomiting with the headache?"
  vm_yes_keeping_fluids → HDC-07 · vm_yes_everything → HDC-G2 ·
  vm_no → HDC-07 · vm_unknown → HDC-07

HDC-07  fieldId: visual_disturbance   answerType: SINGLE_CHOICE
Q: "Any change in the eyesight — blurring, flashes, or loss of part of the vision?"
  vd_aura       "Flashing lights or zigzag lines before or with the headache" → HDC-08
  vd_blurring   "Vision blurred"                       → HDC-G2
  vd_loss       "Part of the vision lost"              → HDC-G2
  vd_no         "No change"                            → HDC-08
  vd_unknown    "Not known"                            → HDC-08
```

**Corpus grounding.** `photophobia` → G43.9 80% on 106 rows, `severe headache` → I10 89% on 83
rows (§0.2). `headache_site` and `headache_character` carry the measured discriminative signal
for migraine versus tension-type.

### 5.5 Stage D/E

```
HDC-08  fieldId: neck_stiffness_on_exam   answerType: SINGLE_CHOICE
Q: "Ask the patient to touch their chin to their chest. Can they do it?"
help: "This is different from the danger-sign neck stiffness. Ask gently — if the patient winces or cannot do it, record 'no'."
  ns_can_do    "Yes — chin touches chest easily"       → HDC-S1
  ns_cannot    "No — cannot or painful"                → HDC-G1
  ns_unknown   "Not tested"                            → HDC-S1

HDC-S1  SubtreeRef → FEVER-QUAL v1.1
        on return → HDC-G3, then HDC-END

HDC-END TerminalNode
```

`neck_stiffness_on_exam` is a Stage D field, not a repetition of the Stage A `ds_neck_stiff`. The
danger-sign version fires on the worker's spontaneous observation; this version is a deliberate
bedside test. Having both is justified because a patient may not volunteer stiffness but fail the
chin-to-chest test. The `fieldId` is different from `danger_signs` — two fields, two observations.

### 5.6 Gateways

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-HDC-EMG-1** | `danger_signs contains ds_seizure` | `routeTo: emergency_convulsions`, terminate | meningitis, cerebral malaria, eclampsia | N/A — Tier-0 |
| **GW-HDC-EMG-2** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | stroke, cerebral malaria, meningitis | N/A — Tier-0 |
| **GW-HDC-EMG-3 (SAH / meningitis)** | `anyOf[ danger_signs contains ds_worst_ever_sudden, allOf[ danger_signs contains ds_neck_stiff, danger_signs contains ds_high_fever_head ], neck_stiffness_on_exam equals ns_cannot ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **subarachnoid haemorrhage, meningitis** | **Yes — abstains** |
| **GW-HDC-EMG-4 (stroke)** | `anyOf[ danger_signs contains ds_one_side_weak, danger_signs contains ds_vision_sudden, allOf[ danger_signs contains ds_confusion, severity_band equals severe ] ]` | `raisesTo: REFER_EMERGENCY`, continue | **stroke**, space-occupying lesion | **Yes — abstains** |
| **GW-HDC-EMG-5** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-HDC-URG-1 (hypertensive)** | `anyOf[ allOf[ danger_signs contains ds_vision_sudden, severity_band equals severe ] ]` (note: vitals-channel Path-1 catches BP ≥ 180/110; this gateway catches vision + severe headache which may precede the BP reading) | `raisesTo: REFER_URGENT` | hypertensive emergency |
| **GW-HDC-URG-2 (pre-eclampsia)** | `allOf[ sex equals F, pregnancy_status equals yes, severity_band equals severe ]` | `raisesTo: REFER_URGENT` | pre-eclampsia |
| **GW-HDC-URG-3 (raised ICP)** | `allOf[ vomiting_present equals vm_yes_everything, anyOf[ visual_disturbance in {vd_blurring, vd_loss}, progression equals pr_worse ] ]` | `raisesTo: REFER_URGENT` | raised intracranial pressure, space-occupying lesion |
| **GW-HDC-URG-4 (cerebral malaria / meningitis)** | `allOf[ fever_present equals fp_yes, anyOf[ danger_signs contains ds_neck_stiff, neck_stiffness_on_exam equals ns_cannot, danger_signs contains ds_confusion ] ]` | `raisesTo: REFER_URGENT` | cerebral malaria, meningitis, encephalitis |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | the category's full list |

Node placement: `HDC-G1` after HDC-00 evaluates EMG-1…EMG-5; `HDC-G2` evaluates URG-1 and URG-3
as fields land; `HDC-G3` after FEVER-QUAL evaluates URG-2, URG-4.

### 5.7 Worked BranchOutput — 28-year-old woman, 2 days of throbbing one-sided headache, photophobia, nausea, no fever

```
BranchOutput {
  categoryId: "headache"
  branchVersion: "1.0.0-draft"
  pathTaken: [HDC-00, HDC-01, HDC-02, HDC-03, HDC-04, HDC-05, HDC-06, HDC-07,
              HDC-08, HDC-S1, FQ-00, HDC-G3, HDC-END]
  fields: {
    danger_signs             { ANSWERED, [ds_none],              TAP }
    progression              { ANSWERED, pr_same,                TAP }
    prior_treatment_taken    { ANSWERED, [pt_pharmacy_medicine], TAP }
    headache_site            { ANSWERED, hs_one_side,            VOICE_CONFIRMED }
    headache_character       { ANSWERED, hc_throbbing,           TAP }
    photophobia              { ANSWERED, ph_yes,                 TAP }
    vomiting_present         { ANSWERED, vm_yes_keeping_fluids,  TAP }
    visual_disturbance       { ANSWERED, vd_aura,                TAP }
    neck_stiffness_on_exam   { ANSWERED, ns_can_do,              TAP }
    fever_present            { ANSWERED, fp_no,                  TAP }
    fever_duration_band      { NOT_ASKED, null, — }
    fever_pattern            { NOT_ASKED, null, — }
    bleeding_manifestation   { NOT_ASKED, null, — }
    duration_bucket          { ANSWERED, few_days,               PREFILL_MEASURED }
    onset_pattern            { ANSWERED, acute_1_3d,             TAP }
    severity_score           { ANSWERED, 7,                      TAP }
    severity_band            { ANSWERED, severe,                 DERIVED }
    relevant_history         { ANSWERED, [no_known_history],     TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  abstain: false
  attachments: []
}
```

No gateway fired. The tree collected `hs_one_side` + `hc_throbbing` + `ph_yes` + `vd_aura` —
the migraine phenotype — and handed it to the classifier with the `unilateral headache` and
`throbbing headache` value tokens. G43.9 (migraine) measures at `photophobia` → G43.9 80% in
the corpus, so the qualifier carries the signal.

### 5.8 Node budget

Authored non-gateway: **11** (HDC-00…HDC-08, HDC-S1, HDC-END).
Expanded worst case: 11 + FEVER-QUAL gate-only (1) = **12**. Full FEVER-QUAL: 11 + 4 = **15**.
**Under DECISION-2 ceiling with headroom.**

### 5.9 Conformance

All checks pass. DECISION-1: GW-HDC-EMG-3, EMG-4, EMG-5 raise to REFER_EMERGENCY → abstain.
DECISION-2: **15** ≤ 20 ✔.

---

## 6. Branch: `injury`

### 6.1 Category constants — category memo §7 Chapter L

```
categoryId               = "injury"
displayName              = "Injury or accident"
tier                     = CORE
icpc3Chapter             = L
icpc3Basis               = A80, L81
icd10Mapping             = T14.9
anchor                   = { value: top-9 individual complaint at Odisha PHC,
                             sourceType: IND-PRESENT, source: Gupta P et al. PLOS GPH 2024;
                             AIIMS Bhopal external cause 3.2% (IND-PRESENT, MP) }
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = REFER_URGENT          // entry floor is already URGENT
severeConditions         = [ fracture, head injury, internal haemorrhage,
                             tetanus-prone wound, burn ]
branchVersion            = 1.0.0-draft
```

> **Grounding warning.** The corpus returns **nothing** for any injury-related term (§0.2).
> `injury` was measured **inert** against the model in the wire-format investigation
> ("Injury / trauma → E66 0.735"). Every qualifier below is protocol-grounded and `ASSUMED`.
> **G-11 extended.**

### 6.2 Stage A — INJ-00

```
INJ-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_heavy_bleeding    "Bleeding that will not stop with pressure"   → INJ-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake" → INJ-G1
  ds_deformity         "Obvious deformity of a limb"                → INJ-G1
  ds_cannot_move_limb  "Cannot move an arm or leg at all"           → INJ-G1
  ds_wound_deep        "A deep wound — fat or bone visible"         → INJ-G1
  ds_head_injury_signs "Hit on the head and now confused or vomiting" → INJ-G1
  ds_burn_large        "A burn larger than the patient's palm"       → INJ-G1
  ds_cannot_feed       "Unable to drink or feed"                    → INJ-G1
  ds_none              "None of these"                              → INJ-01
  ds_unknown           "Not known"                                  → INJ-01
```

| Severe condition | Worker-observable proxy | Status |
|---|---|---|
| Fracture | `ds_deformity`, `ds_cannot_move_limb` | covered — the classic signs |
| Head injury | `ds_head_injury_signs` (confusion + vomiting post-trauma), `ds_unconscious` | covered |
| Internal haemorrhage | `ds_heavy_bleeding` + the mechanism at INJ-02 | covered for external bleeding; **internal haemorrhage (e.g. splenic rupture after blunt trauma) has no worker-observable sign. The only route is mechanism (high-energy, blunt to abdomen) + tachycardia/hypotension on vitals. G-2 gap. Physician review required** |
| Tetanus-prone wound | `ds_wound_deep` + INJ-05 tetanus status | covered as a combination |
| Burn | `ds_burn_large` | covered for surface area; **chemical and inhalation burns have no specific Stage A sign — G-2 gap** |

### 6.3 Stage B

`INJ-01 progression` and `INJ-02 prior_treatment_taken`, as batch 1 §1.3.
`INJ-01` question adapted: "Since the injury, is it better, the same, or worse?"
→ INJ-03, INJ-04.

### 6.4 Stage C — the category qualifiers

```
INJ-03  fieldId: injury_mechanism   answerType: SINGLE_CHOICE
Q: "What happened?"
  im_fall        "Fall"                                 → INJ-04
  im_road        "Road accident"                        → INJ-G2
  im_hit_object  "Hit by or against an object"          → INJ-04
  im_cut_sharp   "Cut by a sharp object"                → INJ-04
  im_burn        "Burn — fire, hot liquid, or chemical"  → INJ-G2
  im_animal      "Animal bite or scratch"               → INJ-G2
  im_assault     "Assault or violence"                  → INJ-G2
  im_other       "Something else"                       → INJ-04
  im_unknown     "Not known"                            → INJ-04

INJ-04  fieldId: time_since_injury   answerType: SINGLE_CHOICE
Q: "How long ago did this happen?"
  ti_lt_1hr   "Less than 1 hour"           → INJ-05
  ti_1_6hr    "1 to 6 hours"               → INJ-05
  ti_6_24hr   "6 to 24 hours"              → INJ-05
  ti_gt_24hr  "More than 24 hours ago"     → INJ-05
  ti_unknown  "Not known"                  → INJ-05

INJ-05  fieldId: wound_type   answerType: SINGLE_CHOICE
Q: "What does the wound look like?"
  wt_abrasion   "Scrape or graze"                      → INJ-06
  wt_laceration "A cut or tear with edges that could be brought together" → INJ-06
  wt_puncture   "A small deep hole — nail, thorn, bite" → INJ-G2
  wt_crush      "Crushed tissue — heavy object fell on it" → INJ-G2
  wt_burn_wound "A burn — red, blistered or charred"   → INJ-G2
  wt_no_wound   "No open wound — bruise or swelling only" → INJ-06
  wt_unknown    "Not known"                            → INJ-06

INJ-06  fieldId: tetanus_status   answerType: SINGLE_CHOICE
Q: "Has the patient had a tetanus injection in the last 5 years?"
  ts_yes     "Yes"        → INJ-07
  ts_no      "No"         → INJ-G2
  ts_unknown "Not known"  → INJ-G2

INJ-07  fieldId: deformity_or_unable_to_move   answerType: SINGLE_CHOICE
Q: "Is there obvious deformity, or can the patient not move the injured part at all?"
  dm_deformity "Yes — the limb looks bent or out of shape"  → INJ-G2
  dm_no_move   "Cannot move it at all"                      → INJ-G2
  dm_both      "Both"                                       → INJ-G2
  dm_no        "No deformity, can move it"                  → INJ-08
  dm_unknown   "Not examined"                               → INJ-08
```

### 6.5 Stage D/E

```
INJ-08  fieldId: head_injury_features   answerType: SINGLE_CHOICE
Q: "Was the head hit? If so, is there confusion, vomiting, or memory loss?"
  hi_yes_confusion "Yes — now confused, or vomited since"   → INJ-G1
  hi_yes_no_signs  "Hit on the head, but alert and no vomiting" → INJ-END
  hi_no            "Head was not hit"                       → INJ-END
  hi_unknown       "Not known"                              → INJ-END

INJ-END TerminalNode
```

**Stage E cross-links: none.** No FEVER-QUAL, no TB-SCREEN. An acute injury does not benefit
from a fever qualifier or a TB screen. This is a legitimate deviation from the five-stage template
and is declared rather than padded. If fever is present at the time of the injury visit, it is
captured by the shared `fever_present` prefill (if it was already recorded), but the branch does
not ask it — the clinical question is the injury, not a co-morbid febrile illness.

### 6.6 Gateways

Base floor `REFER_URGENT`.

| Gateway | Rule | Effect | severeConditions carried | DECISION-1 |
|---|---|---|---|---|
| **GW-INJ-EMG-1** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | head injury, haemorrhagic shock | N/A — Tier-0 |
| **GW-INJ-EMG-2** | `danger_signs contains ds_heavy_bleeding` | `routeTo: emergency_heavy_bleeding`, terminate | haemorrhage | N/A — Tier-0 |
| **GW-INJ-EMG-3 (head injury)** | `anyOf[ danger_signs contains ds_head_injury_signs, head_injury_features equals hi_yes_confusion ]` | `raisesTo: REFER_EMERGENCY`, **continue** | **head injury with raised ICP**, subdural haematoma | **Yes — abstains** |
| **GW-INJ-EMG-4 (major trauma)** | `anyOf[ injury_mechanism in {im_road, im_assault}, allOf[ danger_signs contains ds_deformity, danger_signs contains ds_cannot_move_limb ] ]` | `raisesTo: REFER_EMERGENCY`, continue | **open fracture, internal haemorrhage, polytrauma** | **Yes — abstains** |
| **GW-INJ-EMG-5** | `allOf[ age_band lt 5, danger_signs contains ds_cannot_feed ]` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign | **Yes — abstains** |
| **GW-INJ-URG-1 (fracture)** | `anyOf[ deformity_or_unable_to_move in {dm_deformity, dm_no_move, dm_both}, danger_signs contains ds_deformity, danger_signs contains ds_cannot_move_limb ]` | **informational** at floor | fracture, dislocation |
| **GW-INJ-URG-2 (tetanus risk)** | `anyOf[ allOf[ wound_type in {wt_puncture, wt_crush}, tetanus_status in {ts_no, ts_unknown} ], allOf[ injury_mechanism equals im_animal, tetanus_status in {ts_no, ts_unknown} ] ]` | **informational** at floor | tetanus-prone wound |
| **GW-INJ-URG-3 (burn)** | `anyOf[ danger_signs contains ds_burn_large, wound_type equals wt_burn_wound, injury_mechanism equals im_burn ]` | **informational** at floor | burn requiring specialized care |
| **GW-INJ-URG-4 (animal bite)** | `injury_mechanism equals im_animal` | **informational** at floor; **flags for PEP assessment** | rabies, wound infection |
| **Base floor** | always | `REFER_URGENT` at entry | the category's full list |

Node placement: `INJ-G1` after INJ-00 evaluates EMG-1…EMG-5; `INJ-G2` evaluates URG-1…URG-4 as
fields land.

### 6.7 Worked BranchOutput — 30-year-old man, fell off a ladder 2 hours ago, forearm deformed, no head injury

```
BranchOutput {
  categoryId: "injury"
  branchVersion: "1.0.0-draft"
  pathTaken: [INJ-00, INJ-G1, INJ-01, INJ-02, INJ-03, INJ-04, INJ-05, INJ-06,
              INJ-G2, INJ-07, INJ-G2, INJ-08, INJ-END]
  fields: {
    danger_signs                { ANSWERED, [ds_deformity],       TAP }
    progression                 { ANSWERED, pr_worse,             TAP }
    prior_treatment_taken       { ANSWERED, [pt_none],            TAP }
    injury_mechanism            { ANSWERED, im_fall,              TAP }
    time_since_injury           { ANSWERED, ti_1_6hr,             TAP }
    wound_type                  { ANSWERED, wt_no_wound,          TAP }
    tetanus_status              { ANSWERED, ts_yes,               TAP }
    deformity_or_unable_to_move { ANSWERED, dm_deformity,         VOICE_CONFIRMED }
    head_injury_features        { ANSWERED, hi_no,                TAP }
    duration_bucket             { ANSWERED, today,                PREFILL_MEASURED }
    onset_pattern               { ANSWERED, sudden,               TAP }
    severity_score              { ANSWERED, 8,                    TAP }
    severity_band               { ANSWERED, severe,               DERIVED }
    relevant_history            { ANSWERED, [no_known_history],   TAP }
    impact_on_daily_activities  { ANSWERED, [unable_to_work],     TAP }
  }
  dispositionFloor: REFER_URGENT
  firedGateways: [
    { GW-INJ-URG-1,
      "deformity_or_unable_to_move==dm_deformity OR danger_signs contains ds_deformity",
      [fracture, dislocation] }
  ]
  routedTo: null
  abstain: false
  attachments: []
}
```

The floor stayed at `REFER_URGENT` (the entry floor). `GW-INJ-EMG-4` did not fire because
`im_fall` is not in `{im_road, im_assault}`, and the danger-sign pair requires both `ds_deformity`
and `ds_cannot_move_limb` (only one was present). The fracture gateway is informational at floor.

### 6.8 Node budget

Authored non-gateway: **10** (INJ-00…INJ-08, INJ-END).
Expanded worst case: **10** (no sub-trees referenced).
**Under DECISION-2 ceiling with 10 nodes of headroom.** This is the leanest branch in either
batch and reflects the nature of injury: the question is "what happened and how bad", not a
differential narrowing.

### 6.9 Conformance

All checks pass. DECISION-1: GW-INJ-EMG-3, EMG-4, EMG-5 raise to REFER_EMERGENCY → abstain.
DECISION-2: **10** ≤ 20 ✔. Two Tier-0 routes: `emergency_unconscious`, `emergency_heavy_bleeding`.

---

## 7. Conformance summary — all five batch-2 branches

| Branch | Entry floor | routeTo targets used | Highest floor | Authored | Expanded worst | NR-1 nearest approach | DECISION-1 abstention paths |
|---|---|---|---|---|---|---|---|
| `joint_pain` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 12 | 20 | `js_yes_hot` (appearance) | GW-JPN-EMG-2, EMG-3 |
| `back_neck_pain` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 12 | 20 | `rl_both_legs` (observation) | GW-BNP-EMG-2, EMG-3 |
| `known_diabetes` | PHYSICIAN_REVIEW_MANDATORY | unconscious | REFER_EMERGENCY | 12 | 20 | `fun_wound` (observation) | GW-DM-EMG-2…5 |
| `headache` | PHYSICIAN_REVIEW_MANDATORY | convulsions, unconscious | REFER_EMERGENCY | 11 | 15 | `ns_cannot` (observation) | GW-HDC-EMG-3…5 |
| `injury` | REFER_URGENT | unconscious, heavy_bleeding | REFER_EMERGENCY | 10 | 10 | `dm_deformity` (observation) | GW-INJ-EMG-3…5 |

**C1–C5 hold for all five.** Stated once (batch 1 §8's argument applies verbatim), evidence per
branch in §§2.9–6.9.

**BR-1:** every option has an explicit `next`. **BR-2:** every SINGLE node has `*_unknown`;
every MULTI has `*_none`+`*_unknown`. **NR-1:** no severe condition named in any frame; nearest
approaches are all observations.

---

## 8. Registry additions — so batch 3 reuses these

### 8.1 New shared sub-trees

| Sub-tree | Version | Nodes | Fields | Introduced for | Expected reuse |
|---|---|---|---|---|---|
| `EXPOSURE-CONTEXT-FEVER` | 1.0.0-draft | 1 | `exposure_context` (minus `ex_tb_contact`) | `fever` 1.0.1 | `fever` only — unlikely to be reused by other branches |

### 8.2 New shared `fieldId`s

| fieldId | Type | Options | Introduced for | Reuse expected in |
|---|---|---|---|---|
| `joint_swelling` | SINGLE | `js_yes_hot` · `js_yes_cold` · `js_no` · `js_unknown` | `joint_pain` | `body_ache`, `rash` (reactive arthritis), `fever` (chikungunya screening) |
| `numbness_weakness_limb` | SINGLE | `nw_numbness` · `nw_weakness` · `nw_both` · `nw_no` · `nw_unknown` | `back_neck_pain` | `known_diabetes` (neuropathy screening), `dizziness` |
| `neck_stiffness_on_exam` | SINGLE | `ns_can_do` · `ns_cannot` · `ns_unknown` | `headache` | `fever` (meningism screen) |

### 8.3 New branch-specific `fieldId`s

| Branch | fieldIds |
|---|---|
| `joint_pain` | `joints_involved` · `morning_stiffness` · `pain_on_weight_bearing` · `migratory_pattern` · `recent_injury_to_joint` |
| `back_neck_pain` | `pain_site_spine` · `radiation_to_leg` · `night_pain` · `bladder_bowel_disturbance` · `trauma_history` |
| `known_diabetes` | `diabetes_medication_adherence` · `hypoglycaemia_episodes` · `polyuria_polydipsia` · `foot_ulcer_or_numbness` · `visual_blurring` |
| `headache` | `headache_site` · `headache_character` · `photophobia` · `visual_disturbance` |
| `injury` | `injury_mechanism` · `time_since_injury` · `wound_type` · `tetanus_status` · `deformity_or_unable_to_move` · `head_injury_features` |

### 8.4 Retired from tree memo §2.5

| Retired fieldId | Branch | Superseded by | Reason |
|---|---|---|---|
| `joint_red_hot` | `joint_pain` | `joint_swelling.js_yes_hot` | same observation, merged into one field |
| `vomiting_with_headache` | `headache` | shared `vomiting_present` | same fact, two names |
| `bleeding_controlled` | `injury` | `danger_signs.ds_heavy_bleeding` | Stage A fires early; the question is "is it still bleeding", answered by the danger sign |

### 8.5 Reused from batch 1 registry (not re-authored)

| Item | Source | Used in this batch by |
|---|---|---|
| `TB-SCREEN` | batch 1 §6.1 | `joint_pain`, `back_neck_pain`, `known_diabetes` |
| `FEVER-QUAL` v1.1 | batch 1 §6.3 | `joint_pain`, `back_neck_pain`, `known_diabetes`, `headache` |
| `vomiting_present` | batch 1 §7.2 | `headache` |
| `progression` | batch 1 §1.3 | all five branches |
| `prior_treatment_taken` | batch 1 §1.3 | all five branches |
| `visit_reason_subtype` | tree memo §4 (`known_hypertension`) | `known_diabetes` |
| `exertional_relation` | batch 1 §7.2 | not used this batch but registered for `acidity_heartburn` (batch 3) |

### 8.6 Dataset column impact

Net new column families:
- **3 shared** (§8.2): `joint_swelling`, `numbness_weakness_limb`, `neck_stiffness_on_exam`
- **26 branch-specific** (§8.3)
- **3 retired** (§8.4)
- **1 sub-tree** (`EXPOSURE-CONTEXT-FEVER`)

Approximate net width: **≈ 35 columns**. Running total (batch 1 + batch 2): ≈ 75 new columns
against tree memo §7.2's ≈ 400 projection.

---

## 9. Flagged for physician and operator review

### G-14 — Fever 1.0.1 expanded path exceeds the DECISION-2 ceiling of 20

See §1.4. Worst case: 27 nodes. The fever branch predates the ceiling (G-7) and is the
highest-volume, highest-severity category. Resolution: split `fever`, or accept the exception
with documented justification. **Operator decision required.**

### G-2 extensions (new clinical-safety gaps this batch)

| Category | Severe condition | Gap |
|---|---|---|
| `joint_pain` | chronic gout (tophaceous) | No Stage A sign for the chronic form; acute gout is covered by `ds_hot_red_swollen` |
| `back_neck_pain` | referred renal/aortic pain | No worker-observable sign separates them from MSK pain at Stage A |
| `known_diabetes` | silent MI (no chest pain presentation) | `ds_chest_pain` covers the obvious; silent MI presents as fatigue/breathlessness only |
| `injury` | internal haemorrhage (blunt abdominal) | No external sign; relies on mechanism + vitals |
| `injury` | chemical/inhalation burn | No specific Stage A sign; covered by `ds_burn_large` only |

### G-11 extension

The corpus returns nothing for any back/neck/spine, injury/wound/fracture/burn/trauma term. These
two branches are fully `ASSUMED` in their qualifier grounding, same as `chest_pain` in batch 1.

### G-15 — `visit_reason_subtype` has no `vr_unknown` option (BR-2 gap)

`known_hypertension`'s HTN-01 and this batch's DM-03 both use `visit_reason_subtype` without an
`unknown` option. BR-2 requires every SINGLE node to carry `*_unknown`. The four existing options
are arguably exhaustive (refill / new symptom / followup / first), but strictness requires adding
`vr_unknown "Not known"` → next. **Flagged as a BR-2 gap for the schema owner; not added
unilaterally because it changes a published branch (`known_hypertension`).**

---

## 10. Boundaries observed

No source, generator, dataset, model, config, questionnaire or tree runtime file was written, edited
or deleted in either repo. The SaMDClassifier repo was **read only** — one CSV read for the §0.2
measurement pass; `git status --short` there was not run (the file was accessed via absolute path).
No `.env`, `local.properties` or credential file was opened at any point. No commit, no staging.
No branch was authored in code, no dataset generated, no model trained. The only file written is
this memo.

Batch 2 is `joint_pain`, `back_neck_pain`, `known_diabetes`, `headache`, `injury`, plus `fever`
1.0.1 amendment. Nothing beyond these was authored. The §6.5 order continues with
`vomiting_nausea` → `acidity_heartburn` → `urinary_symptoms` → `cold_sore_throat` →
`weakness_unwell` → `body_ache` → `weight_loss` → `oedema` → `pallor_anaemia` → `dizziness` →
`itching` → `skin_infection` → `antenatal_visit` at batch 3.
