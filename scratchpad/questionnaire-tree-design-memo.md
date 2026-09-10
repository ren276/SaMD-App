# Questionnaire tree design (STEP 1, read-only design memo)

Run date: 2026-09-06. SaMDApp branch `master`, HEAD `be99712`.
SaMDClassifier `main`, HEAD `63e85af`, `git status --short` **empty — read-only, nothing written,
staged or committed there.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
dataset, model, config or tree file touched, in either repo. No branch authored in code, no
dataset regenerated, no model trained. No `.env`, `local.properties` or credential file opened at
any point. No commit, no staging.

**Inputs treated as established fact and not re-derived:**
`scratchpad/reason-for-encounter-category-system-memo.md` (the direct input — every branch here
roots at a `categoryId` from it), `scratchpad/production-classifier-architecture-memo.md`,
`scratchpad/classifier-dataset-nlem-audit-memo.md`. Also read on disk for grounding:
`ConsultationScreen.kt`, `Consultation.kt`, `AilmentEntry.kt`, `KernelPayload.kt`,
`EvaluateRequestDto.kt`, `VitalsSnapshot.kt`, `Attachment.kt`, `ReferralRequest.kt`,
`AuthSession.kt`, `FieldProvenance.kt`, `GenerateEvaluateReportUseCase.kt`,
`dataset/canonical_dataset.csv` header, `docs/requirements/software-requirements.md`,
`docs/quality/risk-management-file.md`.

**One drift note, not a conflict.** The category memo records SaMDApp HEAD `cab78ae`; disk is now
`be99712` (merges #40 and #41 landed after it). Nothing that memo asserts is invalidated — the two
merges added the empty-symptom guard and the constrained consultation inputs, both of which this
memo consumes as *new capture-layer facts* in §2.4. Disk is treated as ground truth throughout.

---

## 0. What this artifact is, and the one sentence that governs it

Between "the worker picked a `categoryId`" and "the classifier ranks a differential" there is
today **nothing**. The audit measured what that nothing costs: a bare-token pick-list scores
**0.580** top-1 against **0.941** at full qualifier granularity — a **−30.6 point** regression —
and **12 of 13** generic-versus-qualified term pairs *flip* the top-1 diagnosis
(`fever`→A09 48% versus `stepladder fever`→A01.0 88%; `body ache`→B54 94% versus
`severe body ache`→A90 87%).

The questionnaire tree is the structure that fills that gap:

> **The tree narrows. The classifier ranks. Neither does the other's job.**

The tree is a fixed, physician-authored decision tree that asks a bounded set of follow-up
questions per category and emits a structured record. It does not produce a differential, does not
score, does not rank, and contains no model anywhere in its branching logic. The classifier
consumes the tree's structured output plus vitals and produces the ranked differential. The
physician AGREE/MODIFY/REJECT gate (`SubmitDoctorDecisionUseCase`) remains terminal and is
untouched by anything here.

This memo is upstream of dataset regeneration on purpose. **The fields a branch collects ARE the
dataset's feature columns and the model's input contract** (§6). Regenerating the dataset before
the tree exists would be authoring a schema before knowing its columns — which is how
`canonical_dataset.csv` acquired 30 columns that carry no duration and no severity while the app
captures both.

### 0.1 The locked constraints, restated as this memo's acceptance criteria

| # | Constraint | Where it is enforced below |
|---|---|---|
| **C1** | Branching is deterministic and rule-based. Never an adaptive model that generates or reorders its own questions. | §1.2 (every option carries an explicit `next`), §1.7 (the SLM's two bounded adapters), §1.9 (the replay test) |
| **C2** | The tree narrows; the classifier ranks. The tree does not diagnose. | §1.5 (`BranchOutput` carries no score, no rank, no condition name) |
| **C3** | Red-flag gateways RF-1…RF-4 (category memo §4.4) are enforced in the tree. `requiredDisposition` is a floor the tree raises and never lowers. | §1.6 (monotone disposition lattice), §1.8 (RF-1 as a text invariant), §3.5 |
| **C4** | Tier 0 emergency categories are `ALWAYS_ABSTAIN`; their branches are rule-based routing to referral only, never classification. | §1.6 (`routeTo` hands off and terminates), §3.5 GW-FEV-EMG-1…3 |
| **C5** | The physician AGREE/MODIFY/REJECT gate stays terminal and unchanged. | Nothing here touches it; §7 |

**No design choice in this memo touches any of the five.** Two places came close enough to be worth
flagging loudly rather than burying — §1.7 (the SLM adapters, which is where C1 would be lost if
anyone widened them) and §3.5 note (whether a `REFER_EMERGENCY` floor should *force* abstention,
which is an open operator question this memo does not answer by itself). Both are called out at
the point they arise.

---

## 1. The branch schema

### 1.1 The one-branch-per-category rule

Exactly one `QuestionnaireBranch` per `categoryId`, keyed by it. 33 branches total: 6 Tier-0
emergency (rule-based routing only), 26 Tier-1 core clinical, 1 terminal fallback
(`other_not_in_list`). This is category memo R3 made structural: *a category ships to production
only when its branch ships*, and the key relation makes a category-without-branch a build failure
rather than a silent 0.580 regression.

`other_not_in_list` has a branch too — a degenerate one: it opens the free-text/voice field,
records the text for the OOD eval corpus, sets `dispositionFloor = PHYSICIAN_REVIEW_MANDATORY`,
and emits a `BranchOutput` whose every clinical field is `NOT_ASKED`. It never reaches the
classifier. A branch that abstains is still a branch; that is what makes NC-1 (no empty selection)
hold end to end.

### 1.2 Node

Three node types, and no fourth.

```
QuestionNode {
  nodeId          // stable within the branch, e.g. "FV-04"
  fieldId         // exactly one field this node populates (§2). 1 node : 1 field.
  questionText    // FIXED English clinical text, authored by a physician, versioned in the branch
  helpText?       // FIXED, optional, worker-facing clarification
  answerType      // SINGLE_CHOICE | MULTI_CHOICE | INTEGER_SCALE | DURATION_BUCKET
  options[]       // FIXED, ordered, closed. Never generated, never reordered at runtime.
  prefillFrom?    // a fieldId already populated by the vitals or registration path (§1.4)
  next            // successor when the answerType carries no per-option routing
}

GatewayNode {
  nodeId
  gatewayId       // stable, e.g. "GW-FEV-EMG-3"
  rule            // a fixed boolean expression over already-collected fieldIds (§1.6)
  raisesTo        // a disposition; applied by max() only
  routeTo?        // a Tier-0 categoryId; when set, the branch terminates and hands off
  severeConditions[]  // carried into BranchOutput for RF-1 enforcement downstream
  next            // successor when the rule does not fire
}

TerminalNode {
  nodeId          // conventionally "<PREFIX>-END"
  emitsBranchOutput = true
}
```

A `GatewayNode` asks nothing. It is a pure function of state, evaluated at a fixed position in the
path. It exists as a node rather than as a side effect so that gateway firing appears in the
enumerated path set and is therefore testable (§1.9).

### 1.3 Answer option

```
AnswerOption {
  optionId        // stable, snake_case, persisted, on the wire — the thing the dataset stores
  displayText     // FIXED English; the SLM may render it in vernacular (§1.7), never rewrite it
  valueToken      // the controlled term this option resolves to, or null
  next            // MANDATORY, explicit successor nodeId. No fallthrough. No computed successor.
  raisesTo?       // optional disposition raise carried by this option alone
  firesGateway?   // optional gatewayId to evaluate immediately on selection
}
```

**Invariant BR-1 (explicit successor).** Every option carries an explicit `next`. There is no
default edge, no "continue", no computed next-node. Selecting an option is a table lookup. This
single rule is what makes C1 mechanically checkable rather than a promise: a branch with a missing
edge fails to load.

**Invariant BR-2 (no absent field).** Every `SINGLE_CHOICE` and `MULTI_CHOICE` node includes a
terminal `unknown` option ("Not known" / "Not sure"), and MULTI_CHOICE includes an explicit
`none_of_these`. There is no way to leave a reached node unanswered.

BR-2 is not politeness — it is the fix for the audit's §C.2 finding, that **missingness leaked the
label** (glucose present-rate 1.000 for E11, 0.000 for A91/B50/J22). A field that is sometimes
absent is a channel through which the *fact of asking* carries diagnostic information the model can
shortcut on. Under BR-2 the only reason a field is absent is that the path did not reach its node,
which is a deterministic function of prior answers and therefore carries no extra information.
See §1.5 (`NOT_ASKED` versus `UNKNOWN`) and §6.4 (the dataset rule that keeps this true).

### 1.4 Prefill, and why it is deterministic

`prefillFrom` lets a node skip itself when the field is already populated from the vitals or
registration path — measured temperature, age band, sex. The skip rule is fixed: *if the referenced
field's status is `ANSWERED`, take that value and follow `next`; otherwise ask.* No model, no
heuristic, no confidence. The prefilled value keeps its original provenance
(`FieldProvenance.TYPED` / device capture), so the dataset can tell a measured 38.9 °C from a
worker's banded self-report — which is the same discipline the architecture memo §5 demands for
vitals and which `canonical_dataset.csv` already carries as `*_observed` columns.

### 1.5 Branch output — the shape that becomes the dataset schema

```
BranchOutput {
  categoryId              // the label. The classifier's class, per category memo §3.1
  branchVersion           // semver of the authored branch. Every row records it (§6.5)
  pathTaken[]             // ordered nodeIds actually visited — the audit trail
  fields: Map<fieldId, FieldValue>
  dispositionFloor        // PHYSICIAN_REVIEW_MANDATORY | REFER_URGENT | REFER_EMERGENCY
  firedGateways[]         // { gatewayId, ruleAsEvaluated, severeConditions[] }
  routedTo?               // a Tier-0 categoryId, when a gateway routed
  attachments[]           // AttachmentType.AFFECTED_AREA_PHOTO refs, when the branch prompted one
}

FieldValue {
  fieldId
  status     // ANSWERED | UNKNOWN | NOT_ASKED
  value      // optionId | Set<optionId> | Int | durationBucket | null when status != ANSWERED
  provenance // TAP | PREFILL_MEASURED | VOICE_CONFIRMED | VOICE_EDITED   (§1.7)
}
```

**The three-state `status` is the load-bearing part of this shape.**

- `ANSWERED` — the node was reached and the worker chose a real option.
- `UNKNOWN` — the node was reached and the worker chose "Not known". *Clinical information*: the
  worker was standing in front of the patient and could not say.
- `NOT_ASKED` — the path never reached the node. *Structural information*, fully determined by the
  answers already given.

Collapsing these three into "null" is precisely the mistake that produced the glucose-missingness
leak. They are three different facts and the schema keeps them three.

**What `BranchOutput` deliberately does not contain (C2):** no score, no probability, no rank, no
condition name, no differential, no "likely"/"unlikely" anything. `severeConditions[]` appears only
inside `firedGateways` and only as the list the *category* declared, carried forward so downstream
rendering can enforce RF-1 — never as a claim about this patient.

### 1.6 How a red-flag gateway fires

Disposition is a three-element lattice with a single permitted operation:

```
PHYSICIAN_REVIEW_MANDATORY  <  REFER_URGENT  <  REFER_EMERGENCY

dispositionFloor := max(dispositionFloor, raisesTo)      // the ONLY writer
```

Initialised to `category.redFlagGateway.requiredDisposition` at branch entry. `max()` is the only
operation the branch runtime exposes. **There is no lowering operator in the API**, which is RF-2
("disposition is a floor, not a default") implemented rather than documented. A high-confidence
benign differential cannot lower it because the classifier runs *after* the branch has already
emitted the floor and has no write access to it.

A gateway rule is a fixed boolean over already-collected fields:

```
rule := anyOf[ clause... ] | allOf[ clause... ]
clause := fieldId  op  literal      // op ∈ { equals, notEquals, in, contains, gte, lte }
```

No arithmetic beyond comparison, no free expressions, no lookups outside the branch's own field
set. Enumerable, unit-testable, readable by the physician who authored it.

Two firing behaviours:

1. **Raise-and-continue** (`routeTo` unset). The floor rises; the branch keeps collecting. Used
   where the extra structure is still clinically worth having — a patient being referred urgently
   for suspected TB still benefits from the cough and weight-loss fields being on the record.
2. **Route-and-terminate** (`routeTo` set, always a Tier-0 categoryId). The branch stops
   immediately, emits `BranchOutput` with `routedTo` set, and control passes to the Tier-0
   category's rule-based branch. Because every Tier-0 category is `ALWAYS_ABSTAIN`, **no
   differential is produced at all** — C4 satisfied by construction, not by a downstream check.

### 1.7 Where the SLM is allowed to be, and where it is forbidden

The SLM touches the tree through exactly two adapters, both of which sit *outside* the branching
logic and neither of which can affect which node comes next.

| Adapter | Signature | Bound |
|---|---|---|
| **Render** | `render(questionText \| displayText, locale) → String` | Input is a fixed authored string; output is a display string in Hindi/Malwi. It cannot invent a question, cannot add an option, cannot reorder options, and its output is never read back into state. A render failure falls back to the authored English string. |
| **Map** | `map(spokenAnswer, node.options[]) → optionId \| UNMAPPED` | **Constrained decoding over the node's fixed option set.** The output alphabet is that node's `optionId`s plus `UNMAPPED`. It cannot return an id the node does not declare. `UNMAPPED` falls back to tap-select; it is never silently coerced to the nearest option. |

Everything else is forbidden and worth naming so a later PR cannot drift into it: the SLM does
**not** choose the next node, does **not** decide which questions to ask or skip, does **not**
generate a clinical question, does **not** read the narrative for salience, does **not** evaluate a
gateway rule, and does **not** write `dispositionFloor`. **If any of those six ever becomes true,
C1 is broken and this is no longer a deterministic tree.** That sentence is the flag the brief
asked for.

A mapped answer is stamped `FieldProvenance.VOICE_UNCONFIRMED` until the existing voice
confirmation gate (H-15) confirms it to `VOICE_CONFIRMED`, exactly as the shipped
`impactOnDailyActivitiesProvenance` field already works (`Consultation.kt:16-20`,
`FieldProvenance.kt:28`). No new gate is invented.

### 1.8 RF-1 inside the branch: the tree may not imply a rule-out

RF-1 forbids any output surface rendering a reassuring or ruling-out statement about a category's
listed severe conditions. Inside the tree that becomes a text invariant on authored content:

**Invariant NR-1.** No `questionText`, `helpText` or `displayText` in any branch may name a severe
condition in a negative or excluding frame. Options record **observations**, never **conclusions**.

- Allowed: `"No rash seen"`, `"Bleeding: none"`, `"Not known"`.
- Forbidden: `"No rash — dengue unlikely"`, `"Rules out malaria"`, `"Normal, no TB"`.

The distinction is real and it is the whole of RF-1: "no rash seen" is a finding the patient may
still have dengue with; "dengue unlikely" is a claim the device is forbidden from making. NR-1 is
checkable by a lint over the authored branch files against each category's `severeConditions[]`
list, and that lint is the mechanical enforcement — deferred with the rest of the implementation,
named here so it is not forgotten.

### 1.9 Why the branch stays deterministic — the proof obligation

The branch graph is a finite DAG with fixed fan-out, so its path set is finite and enumerable. One
replay test per branch, asserting:

1. **Reachability** — every node is on at least one path; every option's `next` resolves.
2. **Determinism** — replaying an identical answer sequence yields a byte-identical
   `BranchOutput` (including `pathTaken`), with no model in the loop.
3. **Field completeness** — the emitted field set equals the set the path structurally determines;
   every reached field is `ANSWERED` or `UNKNOWN`, every unreached one is `NOT_ASKED`.
4. **Monotonicity** — `dispositionFloor` never decreases along any path, and its terminal value is
   ≥ the category's `requiredDisposition` on every path.
5. **Routing** — every `routeTo` names a Tier-0 `ALWAYS_ABSTAIN` category and terminates the branch.
6. **NR-1** — no authored string in the branch matches the forbidden-frame lint.

That is one test file per branch, generated from the branch definition, not hand-written per path.

---

## 2. The field set

This enumeration is what dataset regeneration STEP-1 consumes (§6). It is complete at the field
level for every core category, even where individual branch depth is deferred to the fill-in step.

Legend for the **Capture** column, verified on disk this session:
**EXISTS** = a field with this meaning is already captured; **EXISTS-FREE** = captured but as free
text where the tree needs a coded value; **NEW** = no capture affordance today.

### 2.1 Identity and context (shared, prefilled, never asked by the tree)

| fieldId | Type | Capture | Source on disk |
|---|---|---|---|
| `category_id` | categoryId | NEW (§7) | The dropdown, per category memo §3.1. Not a tree field — the tree's key |
| `age_band` | band | EXISTS | Registration; `EvaluateRequestDto.age` |
| `sex` | M/F/O | EXISTS | Registration; `EvaluateRequestDto.sex` |
| `pregnancy_status` | coded | EXISTS-FREE | Only as `HISTORY_CHIPS` "Pregnancy" appended to free-text `relevantHistory` (`ConsultationScreen.kt:98-101`). Needs to become a coded field — several gateways key off it |

### 2.2 Vitals (shared, prefilled from the vitals path, never re-asked)

All EXISTS in `VitalsSnapshot` / `VitalsReading` (`VitalsSnapshot.kt:7-40`). All already have
observed-versus-imputed columns in `canonical_dataset.csv`
(`bp_systolic_observed`, `bp_diastolic_observed`, `pulse_observed`, `spo2_observed`, `bmi_observed`,
`glucose_observed`), which is the discipline the architecture memo §5 requires.

`bp_systolic` · `bp_diastolic` · `pulse` · `spo2` · `bmi` · `glucose` · `temperature` ·
`respiratory_rate` — each paired with its `_observed` flag.

**Standing defect carried forward, not fixed here:** `RetrofitEvaluateSource.kt:38-43,59-66`
defaults `bmi=22.0`, `120/80`, `hr=72`, `spo2=98` when real values are absent, and nothing
persisted distinguishes a measured 120/80 from a defaulted one. The tree's `prefillFrom` must read
the *observed* value, never the defaulted one, or a fabricated normal will be prefilled into a
clinical question. Named here because the tree is the first consumer that would be poisoned by it.

### 2.3 Shared clinical fields — asked in most or all core branches

| fieldId | Type | Options / range | Capture | Note |
|---|---|---|---|---|
| `duration_bucket` | SINGLE | `today` · `few_days` · `week_plus` · `month_plus` · `chronic` | **EXISTS** | `ConsultationScreen.kt:87`, widened to include `month_plus` in commit `5a3a9f6`. Kept verbatim for cross-branch comparability; branches needing finer resolution add their own field (§3.2, `fever_duration_band`) rather than mutating this one |
| `onset_pattern` | SINGLE | `sudden` · `acute_1_3d` · `gradual` · `insidious` · `intermittent` · `unknown` | **EXISTS** | `ConsultationScreen.kt:89-96`, already a constrained dropdown as of `5a3a9f6` |
| `severity_score` | INT 0–10 | slider | **EXISTS** | `ConsultationScreen.kt:289-292`. The tree reads it; it does not re-ask |
| `severity_band` | SINGLE | `mild` · `moderate` · `severe` | NEW (derived) | Deterministic banding of `severity_score`. Exists because the measured qualifier flips are lexical-severity driven (`severe body ache`→A90 87% vs `body ache`→B54 94%), and a band is what the controlled vocabulary can carry |
| `progression` | SINGLE | `better` · `same` · `worse` · `unknown` | **NEW** | Nothing on disk captures trajectory |
| `prior_treatment_taken` | MULTI | `none` · `home_remedy` · `pharmacy_medicine` · `ayush` · `other_facility` · `unknown` | **NEW** | Materially changes fever and diarrhoea interpretation; also the antibiotic-pretreatment confounder |
| `relevant_history` | MULTI (coded) | the 10 `HISTORY_CHIPS` values | **EXISTS-FREE** | `ConsultationScreen.kt:98-101` — chips exist but append into a free-text field. The tree needs the coded set; the free text stays alongside as narrative |
| `impact_on_daily_activities` | MULTI (coded) | the 7 `IMPACT_CHIPS` values | **EXISTS-FREE** | `ConsultationScreen.kt:104-107`, same shape. **Do not disturb the voice-confirmation gate on this field** (`ConsultationScreen.kt:299-345`, `FeatureFlags.VOICE_FIELD_IMPACT_ENABLED`) |
| `aggravating_factors` | MULTI | branch-specific option sets | **EXISTS-FREE** | `Consultation.aggravatingFactors` free text |
| `relieving_factors` | MULTI | branch-specific option sets | **EXISTS-FREE** | `Consultation.relievingFactors` free text |
| `attachment_photo_present` | BOOL | — | **EXISTS** | `AttachmentType.AFFECTED_AREA_PHOTO` (`Attachment.kt:5`), captured at `ConsultationScreen.kt:170` |
| `narrative_terms` | Set\<controlled term\> | Layer 1 output | **EXISTS upstream** | `Consultation.transcription` + `chiefComplaint`; today concatenated raw into `symptom_string`. Becomes Layer 1's normalized term list — separate artifact (§7) |

### 2.4 Shared red-flag / cross-cutting fields — asked in several branches, same fieldId everywhere

These are shared deliberately: the same fact means the same thing in every branch, and one fieldId
means one dataset column rather than fourteen near-duplicates.

| fieldId | Type | Capture | Asked in |
|---|---|---|---|
| `danger_signs` | MULTI | **NEW** | Every core branch, Stage A. Options are the WHO IMCI general danger signs plus category-specific additions |
| `fever_present` | SINGLE (`yes`/`no`/`unknown`) | **NEW** | Every non-fever branch — fever is a co-symptom of most presentations |
| `bleeding_manifestation` | MULTI | **NEW** | `fever`, `rash`, `abdominal_pain`, `vomiting_nausea`, `urinary_symptoms` |
| `breathlessness_present` | SINGLE | **NEW** | `fever`, `cough`, `chest_pain`, `oedema`, `pallor_anaemia`, `known_diabetes` |
| `altered_consciousness` | SINGLE | **NEW** | `fever`, `headache`, `dizziness`, `known_diabetes` |
| `weight_loss_present` | SINGLE | **NEW** | `fever`, `cough`, `weakness_unwell`, `known_diabetes` — the TB/malignancy triad |
| `night_sweats` | SINGLE | **NEW** | `fever`, `cough` |
| `cough_ge_2_weeks` | SINGLE | **NEW** | `cough`, `fever`, `weight_loss`. The national TB screening trigger; it earns its own field |
| `unable_to_drink_or_feed` | SINGLE | **NEW** | Every branch where `age_band` < 5 — IMCI |
| `urine_output_reduced` | SINGLE | **NEW** | `fever`, `diarrhoea`, `vomiting_nausea`, `oedema` |

### 2.5 Branch-specific fields, by category

Fully specified branches are §3 (`fever`), §4 (`known_hypertension`), §5 (`rash`). The remaining
core categories list their fields here — this is the field-level completeness the dataset step
needs; node-level authoring is the deferred fill-in job (§6 pattern, §7).

| Category | Branch-specific fieldIds |
|---|---|
| `fever` | `fever_duration_band` · `fever_pattern` · `fever_max_band` · `chills_rigors` · `rash_with_fever` · `retro_orbital_pain` · `severe_body_ache` · `joint_pain_with_fever` · `vomiting_with_fever` · `abdominal_pain_with_fever` · `neck_stiffness` · `convulsion_with_fever` · `exposure_context` |
| `cough` | `cough_character` (dry/productive/blood) · `sputum_blood` · `wheeze` · `chest_pain_with_cough` · `smoking_biomass_exposure` · `tb_contact` |
| `cold_sore_throat` | `throat_pain_swallowing` · `ear_pain_with_cold` · `fast_breathing_child` · `rash_with_sore_throat` |
| `breathlessness` | `breathlessness_trigger` (rest/exertion/lying flat) · `orthopnoea` · `wheeze` · `chest_pain_with_breathlessness` · `ankle_swelling` |
| `abdominal_pain` | `pain_site` · `pain_character` · `pain_radiation` · `vomiting_with_pain` · `bowels_open` · `abdomen_rigid` · `lmp_known` (F, 15–49) |
| `acidity_heartburn` | `relation_to_food` · `night_symptoms` · `exertional_relation` (the cardiac mimic) · `black_stool` · `dysphagia` · `nsaid_use` |
| `diarrhoea` | `stool_frequency_band` · `blood_in_stool` · `mucus_in_stool` · `dehydration_signs` · `vomiting_with_diarrhoea` · `water_source` |
| `vomiting_nausea` | `vomit_frequency_band` · `vomit_content` (bile/blood/food) · `headache_with_vomiting` · `abdominal_pain_with_vomiting` · `lmp_known` · `poisoning_suspected` |
| `joint_pain` | `joints_involved` · `joint_swelling` · `joint_red_hot` · `morning_stiffness` · `pain_on_weight_bearing` · `migratory_pattern` |
| `back_neck_pain` | `pain_site_spine` · `radiation_to_leg` · `night_pain` · `bladder_bowel_disturbance` · `numbness_weakness_limb` · `trauma_history` |
| `injury` | `injury_mechanism` · `time_since_injury` · `wound_type` · `bleeding_controlled` · `head_injury_features` · `tetanus_status` · `deformity_or_unable_to_move` |
| `rash` | §5 |
| `itching` | `itch_site` · `itch_worse_at_night` · `household_others_affected` · `visible_burrows_or_lesions` · `new_drug_2_weeks` · `known_liver_renal_disease` |
| `skin_infection` | `lesion_type` · `lesion_site` · `spreading_redness` · `pus_discharge` · `known_diabetes_flag` · `foot_sensation_loss` |
| `headache` | `headache_site` · `headache_character` · `worst_ever_sudden` · `photophobia` · `vomiting_with_headache` · `visual_disturbance` · `neck_stiffness` |
| `dizziness` | `dizziness_type` (spinning/lightheaded) · `on_standing` · `palpitations` · `hearing_change` · `focal_weakness` |
| `urinary_symptoms` | `burning_on_urination` · `frequency_increased` · `flank_pain` · `haematuria` · `fever_with_urinary` · `pregnancy_status` (shared) |
| `chest_pain` | `pain_character_chest` · `radiation_arm_jaw` · `exertional_relation` · `sweating_with_pain` · `duration_minutes_band` · `relieved_by_rest` |
| `known_hypertension` | §4 |
| `known_diabetes` | `diabetes_medication_adherence` · `last_glucose_known` · `hypoglycaemia_episodes` · `polyuria_polydipsia` · `foot_ulcer_or_numbness` · `visual_blurring` · `visit_reason_subtype` |
| `pallor_anaemia` | `pallor_site_observed` · `pica` · `heavy_menstrual_bleeding` · `blood_in_stool` · `worm_treatment_history` · `dietary_pattern` |
| `weakness_unwell` | `weakness_generalised_or_focal` · `appetite_change` · `sleep_change` · `mood_change` · `fever_present` (shared) · `weight_loss_present` (shared) |
| `body_ache` | `ache_distribution` · `fever_present` (shared) · `rash_with_ache` · `joint_swelling` |
| `weight_loss` | `weight_loss_amount_band` · `appetite_change` · `cough_ge_2_weeks` (shared) · `night_sweats` (shared) · `polyuria_polydipsia` |
| `oedema` | `oedema_site` · `oedema_pitting` · `facial_puffiness_morning` · `breathlessness_lying_flat` · `urine_output_reduced` (shared) · `urine_frothy` |
| `antenatal_visit` | `gestational_weeks_band` · `gravida_para` · `anc_visit_number` · `danger_signs_pregnancy` · `fetal_movement` · `previous_pregnancy_complication` |
| `other_not_in_list` | none — free text only, `ALWAYS_ABSTAIN` (§1.1) |

**Count for the dataset step:** 12 identity/vitals · 12 shared clinical · 10 shared cross-cutting ·
approximately 150 branch-specific across 26 core branches. §6 turns this into the column list.

---

## 3. The `fever` branch — the reference implementation

Fully specified because it is the hardest and most safety-critical: the highest-volume PHC
presentation (POSEIDON fever **35.5%** of all primary-care patients; Odisha names fever the
commonest reason **at every facility level**), the gateway to the most severe conditions
(AUFI: malaria **17%** with 54% *falciparum*, dengue **16%**, scrub typhus **10%**, bacteraemia
**8%** of which 35% enteric, leptospirosis 7%, chikungunya 6%), and the category where the audit
proved the qualifiers are decisive — **four of the thirteen measured top-1 flips are fever
qualifiers alone.**

**Category constants** (category memo §7, Chapter A):

```
categoryId               = "fever"
tier                     = CORE
modelBehaviour           = LEARNED
gatewayStrength          = HIGH
requiredDisposition      = PHYSICIAN_REVIEW_MANDATORY      // the entry floor
severeConditions         = [ malaria (incl. falciparum), dengue (incl. severe), typhoid,
                             scrub typhus, leptospirosis, tuberculosis, sepsis, meningitis ]
branchVersion            = 1.0.0-draft
```

### 3.1 FV-00 — danger-sign screen (Stage A, always first)

Safety before narrowing. This node runs before any qualifier question, so a routing gateway fires
before the worker spends time on a differential that will not be produced.

```
FV-00  fieldId: danger_signs   answerType: MULTI_CHOICE
Q: "Right now, does the patient have any of these?"
  ds_convulsion        "Fits or convulsions"                        → FV-G1
  ds_unconscious       "Unconscious, or very drowsy / hard to wake" → FV-G1
  ds_bleeding          "Bleeding from anywhere"                     → FV-G1
  ds_breathing         "Difficulty breathing, or breathing fast"    → FV-G1
  ds_no_urine          "Has not passed urine since yesterday"       → FV-G1
  ds_neck_stiff        "Neck stiffness, or cannot bend the neck"    → FV-G1
  ds_cannot_feed       "Unable to drink or feed"                    → FV-G1
  ds_none              "None of these"                              → FV-01
  ds_unknown           "Not known"                                  → FV-01
```

`ds_cannot_feed` is the IMCI general danger sign; it is on the list for every age, and §3.5
GW-FEV-EMG-5 keys the under-5 escalation off it.

### 3.2 FV-01 — fever duration

```
FV-01  fieldId: fever_duration_band   answerType: SINGLE_CHOICE
Q: "How many days has the fever been there?"
  fd_today       "Today only"                → FV-02
  fd_1_3         "1 to 3 days"               → FV-02
  fd_4_7         "4 to 7 days"               → FV-02
  fd_8_14        "8 to 14 days"              → FV-02
  fd_gt_14       "More than 14 days"         → FV-G2
  fd_unknown     "Not known"                 → FV-02
```

**Why this field exists alongside the shared `duration_bucket`.** The shared bucket
(`today`/`few_days`/`week_plus`/`month_plus`/`chronic`, `ConsultationScreen.kt:87`) cannot separate
day 5 from day 12, and that separation is the enteric-fever second-week distinction and the >14-day
TB screening trigger. The shared field stays untouched and is still emitted for cross-branch
comparability; `fever_duration_band` is the branch-specific refinement. **This is the general rule
for the whole tree: refine with a new branch-specific field, never mutate a shared one.**

### 3.3 FV-02 — fever pattern (the single highest-value node in the tree)

```
FV-02  fieldId: fever_pattern   answerType: SINGLE_CHOICE
Q: "What is the pattern of the fever through the day?"
help: "Ask how the fever behaves — does it climb, come and go, or rise at a fixed time."
  fp_stepladder  "Rising a little more each day, does not fully settle"   → FV-03
                 valueToken: "stepladder fever"
  fp_cyclical    "Comes and goes, with shaking chills"                    → FV-03
                 valueToken: "cyclical high fever with chills"
  fp_evening     "Rises in the evening or at night"                       → FV-03
                 valueToken: "evening fever"
  fp_continuous  "High and continuous, no clear pattern"                  → FV-03
                 valueToken: "high fever"
  fp_mild        "Low-grade throughout"                                   → FV-03
                 valueToken: "mild fever"
  fp_unknown     "Not known"                                              → FV-03
                 valueToken: "fever"
```

The `valueToken` column is not decoration. Those are the classifier's **measured** discriminative
tokens: `fever`→A09 48%, but `stepladder fever`→A01.0 88%, `cyclical high fever with chills`→B54
88%, `evening fever`→A15 77%, `high fever`→A90 54%. **This one node is the difference between
0.580 and 0.941, and it is why the tree exists at all.**

NR-1 note: no option here names a disease. The worker is asked what the fever *does*, never what it
*is*. The mapping from pattern to condition lives in the model and the physician, never in the
question text.

### 3.4 FV-03 … FV-11 — the rest of the branch

```
FV-03  fieldId: fever_max_band   answerType: SINGLE_CHOICE
       prefillFrom: vitals.temperature   (skip if observed==true; carry PREFILL_MEASURED)
Q: "Has the temperature been measured? What was the highest reading?"
  ft_lt_38     "Below 38 °C / 100.4 °F"     → FV-04
  ft_38_39     "38 to 39 °C"                → FV-04
  ft_gt_39     "Above 39 °C / 102 °F"       → FV-04
  ft_not_meas  "Not measured — feels hot"   → FV-04
  ft_unknown   "Not known"                  → FV-04

FV-04  fieldId: chills_rigors   answerType: SINGLE_CHOICE
Q: "Does the patient get shaking chills with the fever?"
  ch_rigors    "Yes — shaking, teeth chattering"   → FV-05
  ch_cold_only "Feels cold, but no shaking"        → FV-05
  ch_no        "No"                                → FV-05
  ch_unknown   "Not known"                         → FV-05

FV-05  fieldId: rash_with_fever   answerType: SINGLE_CHOICE
Q: "Is there any rash or skin change with the fever?"
  rf_yes       "Yes"        → FV-S1   (subtree: RASH-MORPH, §5.4)
  rf_no        "No rash seen"  → FV-06
  rf_unknown   "Not sure"   → FV-06

FV-S1  SubtreeRef → RASH-MORPH (rash morphology + distribution + photo prompt)
       on return → FV-06

FV-06  fieldId: bleeding_manifestation   answerType: MULTI_CHOICE
Q: "Any bleeding anywhere?"
  bl_gums      "Bleeding gums"                    → FV-G3
  bl_nose      "Nose bleed"                       → FV-G3
  bl_skin      "Red or purple spots under skin"   → FV-G3
  bl_vomit     "Blood in vomit"                   → FV-G3
  bl_stool     "Black or bloody stool"            → FV-G3
  bl_urine     "Blood in urine"                   → FV-G3
  bl_none      "None"                             → FV-07
  bl_unknown   "Not known"                        → FV-07

FV-07  fieldId: fever_associated_symptoms   answerType: MULTI_CHOICE
Q: "What else does the patient have along with the fever?"
  as_body_ache_severe  "Severe body ache all over"   valueToken "severe body ache"
  as_retro_orbital     "Pain behind the eyes"        valueToken "retro-orbital pain"
  as_joint_pain        "Joint pain"                  valueToken "joint pain"
  as_headache          "Headache"                    valueToken "headache"
  as_vomiting          "Vomiting"
  as_loose_motions     "Loose motions"
  as_abdominal_pain    "Stomach pain"
  as_burning_urine     "Burning urination"
  as_cough             "Cough"
  as_none              "None of these"
  as_unknown           "Not known"
  (all options → FV-08)

FV-08  fieldId: cough_ge_2_weeks   answerType: SINGLE_CHOICE
       asked only when FV-07 includes as_cough, else NOT_ASKED
Q: "Has the cough been there for two weeks or more?"
  c2_yes "Yes" → FV-G2      c2_no "No" → FV-09      c2_unknown "Not known" → FV-09

FV-09  fieldId: weight_loss_present + night_sweats   (two nodes, one screen)
Q: "Has the patient lost weight without trying, or had night sweats?"
  wl_weight "Losing weight"  wl_sweats "Night sweats"  wl_both "Both"
  wl_none "Neither" → FV-10 ;  wl_weight|wl_sweats|wl_both → FV-G2 ;  wl_unknown → FV-10

FV-10  fieldId: exposure_context   answerType: MULTI_CHOICE
Q: "Anything around the patient that could explain the fever?"
  ex_mosquito   "Mosquitoes or standing water at home"
  ex_household  "Someone else at home has fever"
  ex_travel     "Travelled in the last month"
  ex_tb_contact "Close contact with a TB patient"
  ex_water_soil "Works in paddy field / flood water / with animals"
  ex_none       "None of these"
  ex_unknown    "Not known"
  (ex_tb_contact → FV-G2 ; all others → FV-11)

FV-11  fieldId: prior_treatment_taken   answerType: MULTI_CHOICE   (shared, §2.3)
Q: "Has the patient already taken anything for this fever?"
  pt_none · pt_home_remedy · pt_pharmacy_medicine · pt_ayush · pt_other_facility · pt_unknown
  → FV-12

FV-12  fieldId: pregnancy_status   answerType: SINGLE_CHOICE
       asked only when sex == F and age_band ∈ [15,49]; else NOT_ASKED   (deterministic gate)
Q: "Is the patient pregnant?"
  pg_yes "Yes" → FV-G4 ;  pg_no "No" → FV-END ;  pg_unknown "Not known" → FV-END

FV-END TerminalNode
```

`FV-09` is written as two fieldIds behind one worker-facing screen. The screen is a presentation
concern; the branch stores two fields, because the dataset needs them separable and because
`night_sweats` and `weight_loss_present` are shared fields reused by `cough` and `weight_loss`.

### 3.5 Gateways in the fever branch

| Gateway | Rule | Effect | Severe conditions carried |
|---|---|---|---|
| **GW-FEV-EMG-1** | `danger_signs contains ds_convulsion` | `routeTo: emergency_convulsions`, terminate | cerebral malaria, meningitis, febrile seizure |
| **GW-FEV-EMG-2** | `danger_signs contains ds_unconscious` | `routeTo: emergency_unconscious`, terminate | cerebral malaria (B50), meningitis, sepsis, hypoglycaemia |
| **GW-FEV-EMG-3** | `danger_signs contains ds_bleeding` **or** `bleeding_manifestation ∉ {bl_none, bl_unknown}` | `routeTo: emergency_heavy_bleeding`, terminate | **severe dengue (A91)**, sepsis with DIC, enteric perforation |
| **GW-FEV-EMG-4** | `danger_signs contains any of {ds_breathing, ds_no_urine, ds_neck_stiff}` | `raisesTo: REFER_EMERGENCY`, continue | sepsis, meningitis, severe malaria, severe dengue |
| **GW-FEV-EMG-5** | `age_band < 5` **and** `danger_signs contains ds_cannot_feed` | `raisesTo: REFER_EMERGENCY`, continue | IMCI general danger sign — severe illness in a child |
| **GW-FEV-URG-1 (TB)** | `fever_duration_band == fd_gt_14` **or** `cough_ge_2_weeks == yes` **or** `weight_loss_present == yes` **or** `exposure_context contains ex_tb_contact` | `raisesTo: REFER_URGENT` | **pulmonary TB (A15)** — the national screening trigger, sputum pathway |
| **GW-FEV-URG-2** | `pregnancy_status == pg_yes` | `raisesTo: REFER_URGENT` | malaria in pregnancy, sepsis, pre-eclampsia mimicking fever |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` at entry | the category's full list |

Node placement: `FV-G1` after FV-00 evaluates EMG-1…5 in order; `FV-G2` evaluates URG-1;
`FV-G3` after FV-06 evaluates EMG-3; `FV-G4` after FV-12 evaluates URG-2.

**Disposition escalation worked through, one path:** a 34-year-old with 16 days of evening fever,
night sweats and a TB contact enters at `PHYSICIAN_REVIEW_MANDATORY`, hits GW-FEV-URG-1 three
separate ways, and terminates at `REFER_URGENT`. Nothing in the branch, and nothing in the
classifier afterwards, can bring that back down to `PHYSICIAN_REVIEW_MANDATORY` — `max()` is the
only writer (§1.6). If the same patient also reports bleeding gums, GW-FEV-EMG-3 routes to
`emergency_heavy_bleeding`, the branch terminates immediately, and **no differential is produced at
all** because that category is `ALWAYS_ABSTAIN`.

**RF-1 in this branch, concretely.** The device may rank a benign cause first. It may never render
"no rash, so dengue is unlikely", "afebrile pattern, malaria excluded", or a normal-vitals framing
a reader takes as "TB ruled out". `bl_none` records *no bleeding was seen*; it does not record *no
severe dengue*. Both matter because the device's three weakest classes are exactly these
conditions — A91 F1 **0.381** on 54 rows, J22 **0.364** on 25, B50 **0.400** on 21.

**Open operator question, stated rather than answered.** When `dispositionFloor` reaches
`REFER_EMERGENCY` via a raise-and-continue gateway (EMG-4, EMG-5), the branch still emits a
`BranchOutput` that the classifier will rank. Should a `REFER_EMERGENCY` floor **force** abstention
the way a Tier-0 route does? The argument for: a patient being referred as an emergency does not
benefit from a differential, and a benign-looking ranking next to an emergency referral is exactly
the reading RF-1 exists to prevent. The argument against: abstention is Layer 3's calibrated
decision, and hard-wiring it here puts a suppression rule in the tree. **This memo does not decide
it.** It is the one design question in the fever branch that a physician-and-operator review must
settle, and it is flagged rather than resolved by default.

### 3.6 What the fever branch emits

A worked example — 26-year-old woman, day 6, cyclical fever with rigors, severe body ache, no
bleeding, mosquitoes at home, no prior treatment, not pregnant:

```
BranchOutput {
  categoryId: "fever"
  branchVersion: "1.0.0-draft"
  pathTaken: [FV-00, FV-01, FV-02, FV-03, FV-04, FV-05, FV-06, FV-G3, FV-07, FV-09, FV-10, FV-11, FV-12, FV-G4, FV-END]
  fields: {
    danger_signs               { ANSWERED, [ds_none],                TAP }
    fever_duration_band        { ANSWERED, fd_4_7,                   TAP }
    fever_pattern              { ANSWERED, fp_cyclical,              TAP }   // "cyclical high fever with chills"
    fever_max_band             { ANSWERED, ft_gt_39,                 PREFILL_MEASURED }
    chills_rigors              { ANSWERED, ch_rigors,                TAP }
    rash_with_fever            { ANSWERED, rf_no,                    TAP }
    bleeding_manifestation     { ANSWERED, [bl_none],                TAP }
    fever_associated_symptoms  { ANSWERED, [as_body_ache_severe],    VOICE_CONFIRMED }
    cough_ge_2_weeks           { NOT_ASKED, null,                    — }     // as_cough not selected
    weight_loss_present        { ANSWERED, no,                       TAP }
    night_sweats               { ANSWERED, no,                       TAP }
    exposure_context           { ANSWERED, [ex_mosquito],            TAP }
    prior_treatment_taken      { ANSWERED, [pt_none],                TAP }
    pregnancy_status           { ANSWERED, pg_no,                    TAP }
    duration_bucket            { ANSWERED, few_days,                 TAP }   // shared
    onset_pattern              { ANSWERED, acute_1_3d,               TAP }   // shared
    severity_score             { ANSWERED, 7,                        TAP }   // shared
    severity_band              { ANSWERED, severe,                   DERIVED }
    progression                { ANSWERED, worse,                    TAP }
    relevant_history           { ANSWERED, [no_known_history],       TAP }
    impact_on_daily_activities { ANSWERED, [unable_to_work],         VOICE_CONFIRMED }
  }
  dispositionFloor: PHYSICIAN_REVIEW_MANDATORY
  firedGateways: []
  routedTo: null
  attachments: []
}
```

Note what the tree did **not** do: it did not say malaria, did not say dengue, did not rank
anything, did not attach a probability. It collected `cyclical high fever with chills` +
`severe body ache` + rigors + day 6 + mosquito exposure + severity 7, and handed that to the
classifier. `severe body ache` alone was measured to move the top-1 from B54 to A90 at 87%; the
tree's job is to make sure that qualifier reaches the model instead of being flattened into
"fever".

---

## 4. `known_hypertension` — the chronic / follow-up shape

Proves the schema against a branch that is **not** symptom narrowing. The patient's diagnosis is
already known; the encounter is a status check. The branch's job is to detect decompensation and
non-adherence, not to characterise a complaint.

**Category constants** (category memo §7, Chapter K): `tier = CORE`, `modelBehaviour = LEARNED`,
`gatewayStrength = MODERATE`, `requiredDisposition = PHYSICIAN_REVIEW_MANDATORY`,
`severeConditions = [hypertensive emergency, stroke, heart failure, renal disease, pre-eclampsia]`.
Anchor: Odisha records hypertension as the **2nd commonest reason overall** and one of only three
things primary facilities actually handle.

```
HTN-00  fieldId: danger_signs   MULTI     (Stage A, category-specific list)
Q: "Right now, does the patient have any of these?"
  ds_chest_pain   "Chest pain or heaviness"                  → HTN-G1
  ds_breathless   "Breathless at rest or lying flat"          → HTN-G1
  ds_weakness     "Sudden weakness or drooping on one side"   → HTN-G1
  ds_speech       "Sudden difficulty speaking"                → HTN-G1
  ds_vision       "Sudden vision loss or blurring"            → HTN-G1
  ds_severe_head  "Severe headache, worst ever"               → HTN-G1
  ds_none         "None of these"                             → HTN-01
  ds_unknown      "Not known"                                 → HTN-01

HTN-01  fieldId: visit_reason_subtype   SINGLE
Q: "Why has the patient come today?"
  vr_refill      "Routine check / medicine refill"    → HTN-02
  vr_new_symptom "New complaint since last visit"     → HTN-02
  vr_followup    "Follow-up after a referral"         → HTN-02
  vr_first       "First visit for known high BP"      → HTN-02

HTN-02  fieldId: bp_medication_adherence   SINGLE
Q: "Is the patient taking the BP medicine as advised?"
  ad_daily       "Every day"                          → HTN-03
  ad_missed_some "Misses some days"                   → HTN-03
  ad_stopped     "Stopped taking it"                  → HTN-G2
  ad_none_given  "Never been given any medicine"      → HTN-G2
  ad_unknown     "Not known"                          → HTN-03

HTN-03  fieldId: medication_names_known   SINGLE
Q: "Does the patient know or carry the names of the medicines?"
  mn_carried "Carrying the strip or card" · mn_named "Knows the names" ·
  mn_unknown "Does not know"             → HTN-04

HTN-04  fieldId: last_bp_reading_known   SINGLE
Q: "Is the BP reading from the last visit known?"
  lb_yes_normal "Yes — was in range" · lb_yes_high "Yes — was high" ·
  lb_no "Not known"                      → HTN-05

HTN-05  fieldId: symptoms_since_last_visit   MULTI
Q: "Any of these since the last visit?"
  sl_headache "Headaches"           sl_giddiness "Giddiness"
  sl_palpitation "Palpitations"     sl_ankle_swelling "Ankle swelling"
  sl_breathless_exertion "Breathless on walking"   sl_nosebleed "Nose bleeds"
  sl_none "None"                    sl_unknown "Not known"
                                        → HTN-06

HTN-06  fieldId: home_bp_monitoring   SINGLE
  hb_yes "Yes, checks at home" · hb_no "No" · hb_unknown "Not known"   → HTN-07

HTN-07  fieldId: relevant_history   MULTI   (shared, §2.3 — the 10 HISTORY_CHIPS values)
                                        → HTN-G3
HTN-G3  gateway, then → HTN-END
```

**Gateways**

| Gateway | Rule | Effect |
|---|---|---|
| **GW-HTN-EMG-1** | `danger_signs ∌ {ds_none, ds_unknown}` | `raisesTo: REFER_EMERGENCY`, continue. Stroke / ACS / hypertensive emergency features |
| **GW-HTN-URG-1** | `vitals.bp_systolic ≥ 180` **or** `vitals.bp_diastolic ≥ 110` (observed only) | `raisesTo: REFER_URGENT` |
| **GW-HTN-URG-2** | `pregnancy_status == yes` **and** (`bp_systolic ≥ 140` **or** `bp_diastolic ≥ 90`) | `raisesTo: REFER_URGENT` — pre-eclampsia; also cross-links `antenatal_visit` |
| **GW-HTN-ADH-1** | `bp_medication_adherence ∈ {ad_stopped, ad_none_given}` | floor unchanged; sets a flag the physician sees. **Not** a disposition raise — non-adherence is a treatment decision, not a triage one |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` |

**What this branch proves about the schema.** Three things generalise from it:

1. **`prefillFrom` carries the whole clinical payload here.** GW-HTN-URG-1 reads measured BP, and
   the branch never asks a BP question — the vitals path already has it. A branch can be
   gateway-heavy and question-light.
2. **Not every gateway raises disposition.** GW-HTN-ADH-1 records a fact without escalating. The
   lattice has one writer, but a gateway may fire and write nothing to it.
3. **The chronic shape is Stage A + status nodes**, with no qualifier stage at all. The template in
   §6 accommodates it by making Stage C optional.

---

## 5. `rash` — the skin / visual shape, and the photo path

Proves the schema against a branch whose most informative signal is an **image**, and against
sub-tree reuse (`FV-S1` in §3.4 enters this branch's morphology sub-tree without duplicating it).

**Category constants** (category memo §7, Chapter S): `tier = CORE`, `modelBehaviour = LEARNED`,
`gatewayStrength = HIGH`, `requiredDisposition = PHYSICIAN_REVIEW_MANDATORY`,
`severeConditions = [dengue rash, measles, drug reaction / SJS-TEN, leprosy, meningococcaemia]`.
The Odisha authors flag skin as high-burden and low-capability, and note that *"most common skin
disease diagnoses are based on visual inspection"* — which is exactly what
`AttachmentType.AFFECTED_AREA_PHOTO` already captures (`Attachment.kt:5`,
`ConsultationScreen.kt:170`).

```
RSH-00  fieldId: danger_signs   MULTI      (Stage A)
  ds_mucosal      "Sores in mouth, eyes or genitals"       → RSH-G1
  ds_skin_peeling "Skin peeling or blistering"             → RSH-G1
  ds_purpura      "Purple spots that do not fade on press" → RSH-G1
  ds_fever_high   "High fever with the rash"               → RSH-G1
  ds_swollen_face "Swollen face, lips or tongue"           → RSH-G1
  ds_none "None of these" · ds_unknown "Not known"         → RSH-01

RSH-01  fieldId: rash_duration_band   SINGLE
  rd_today · rd_1_3 · rd_4_7 · rd_gt_7 · rd_months · rd_unknown    → RSH-02

RSH-02  fieldId: fever_present   SINGLE   (shared, §2.4)
  fp_yes → RSH-S1 (subtree: FEVER-QUAL — FV-01, FV-02, FV-06 only)
  fp_no · fp_unknown → RSH-03

   ── RASH-MORPH sub-tree starts here; entered directly by FV-S1 from the fever branch ──

RSH-03  fieldId: rash_distribution   SINGLE
Q: "Where is the rash?"
  rdst_face_first "Started on the face, spread down"      → RSH-04
  rdst_trunk      "Mainly on the chest, back or stomach"  → RSH-04
  rdst_limbs      "Mainly on the arms or legs"            → RSH-04
  rdst_palms_soles "Includes palms or soles"              → RSH-04
  rdst_whole_body "All over the body"                     → RSH-04
  rdst_one_patch  "One patch or a few patches only"       → RSH-04
  rdst_unknown    "Not known"                             → RSH-04

RSH-04  fieldId: rash_morphology   SINGLE
Q: "What does the rash look like?"
  rm_flat_red     "Flat red spots"                          → RSH-05
  rm_raised       "Raised bumps"                            → RSH-05
  rm_fluid_blister "Fluid-filled blisters"                  → RSH-G1
  rm_pustular     "Pus-filled spots"                        → RSH-05
  rm_scaly_patch  "Dry, scaly patch"                        → RSH-05
  rm_pale_patch   "Pale / lighter-coloured patch"           → RSH-06
  rm_purple_spots "Purple or red spots that do not fade"    → RSH-G1
  rm_unknown      "Not known"                               → RSH-05

RSH-05  fieldId: rash_symptom   SINGLE
  rs_itchy "Itchy" · rs_painful "Painful" · rs_burning "Burning" ·
  rs_numb "No feeling in it" · rs_none "Neither" · rs_unknown "Not known"
  (rs_numb → RSH-G2 ; others → RSH-07)

RSH-06  fieldId: hypopigmented_patch_sensation   SINGLE
       asked only when rash_morphology == rm_pale_patch
Q: "Touch the pale patch lightly — can the patient feel it the same as normal skin?"
  hp_normal_sensation "Feels the same" → RSH-07
  hp_reduced          "Feels less, or nothing"  → RSH-G2
  hp_unknown          "Not tested"     → RSH-07

RSH-07  fieldId: new_drug_2_weeks   SINGLE
Q: "Any new medicine started in the last two weeks?"
  nd_yes "Yes" → RSH-G1 · nd_no "No" → RSH-08 · nd_unknown "Not known" → RSH-08

RSH-08  fieldId: household_others_affected   SINGLE
  ho_yes · ho_no · ho_unknown        → RSH-09

RSH-09  fieldId: attachment_photo_present   SINGLE     (the photo prompt)
Q: "Take a photo of the affected area for the doctor."
  ph_taken    "Photo taken"                     → RSH-END   [attaches AFFECTED_AREA_PHOTO]
  ph_declined "Patient did not consent"         → RSH-END
  ph_not_possible "Cannot take a photo now"     → RSH-END

   ── RASH-MORPH sub-tree ends; FV-S1 returns to FV-06 here ──

RSH-END TerminalNode
```

**Gateways**

| Gateway | Rule | Effect | Severe conditions |
|---|---|---|---|
| **GW-RSH-EMG-1** | `danger_signs contains ds_purpura` **or** `rash_morphology == rm_purple_spots` | `raisesTo: REFER_EMERGENCY` | meningococcaemia, **severe dengue** |
| **GW-RSH-EMG-2** | `danger_signs contains any of {ds_mucosal, ds_skin_peeling}` **or** `rash_morphology == rm_fluid_blister` **and** `new_drug_2_weeks == yes` | `raisesTo: REFER_EMERGENCY` | **SJS / TEN** |
| **GW-RSH-URG-1** | `rash_morphology == rm_pale_patch` **and** `hypopigmented_patch_sensation == hp_reduced`, **or** `rash_symptom == rs_numb` | `raisesTo: REFER_URGENT` | **leprosy** — the hypopigmented anaesthetic patch, a national programme notification |
| **GW-RSH-URG-2** | `fever_present == yes` **and** `rash_duration_band ∈ {rd_1_3, rd_4_7}` | `raisesTo: REFER_URGENT` | dengue, measles, meningococcaemia |
| **Base floor** | always | `PHYSICIAN_REVIEW_MANDATORY` | full list |

**What this branch proves about the schema.**

1. **Sub-tree reuse is the mechanism that bounds the fill-in job.** `RASH-MORPH` (RSH-03…RSH-09) is
   authored once and referenced by `fever` (FV-S1), and will be referenced by `itching` and
   `skin_infection`. Likewise `FEVER-QUAL` (FV-01, FV-02, FV-06) is referenced from `rash` (RSH-S1),
   and will be from `cough`, `diarrhoea`, `joint_pain`, `urinary_symptoms`, `body_ache`. A
   `SubtreeRef` is a node with one entry and one return edge; it changes nothing about determinism
   because the sub-tree is itself a fixed DAG. **Cross-links are references, never copies** — a
   copied sub-tree would drift, and two branches asking the same question with different option
   sets would produce two incompatible dataset columns for one clinical fact.
2. **The photo is a field, not a payload.** The branch records `attachment_photo_present` — taken /
   declined / not possible — as a coded field. The image itself rides the existing
   `AttachmentType.AFFECTED_AREA_PHOTO` path to the physician. **No image model is introduced, no
   image reaches the classifier, and nothing here touches the parked imaging workstream**
   (architecture memo §7.3). The declined case is recorded distinctly from the not-possible case,
   because consent-declined is a consent fact and not-possible is a device fact.
3. **NR-1 survives contact with a visual branch.** RSH-04's options describe appearance only.
   "Purple spots that do not fade" is an observation; "meningococcaemia" is a conclusion the tree
   never renders, and the leprosy gateway fires on *reduced sensation in a pale patch*, never on the
   word leprosy appearing anywhere a worker can read.

---

## 6. The pattern every remaining branch follows

Fully authoring all 27 Tier-1 branches is **deferred**. It is downstream mechanical work against
this schema, physician-reviewed per branch, and it is deliberately not attempted here. What follows
is the template that makes it a bounded fill-in job rather than 27 fresh design problems.

### 6.1 The five-stage template

| Stage | Purpose | Rule | Node budget |
|---|---|---|---|
| **A — Danger-sign screen** | Safety first, before any narrowing | Mandatory in every core branch. Its option list is derived from the category's `severeConditions[]`, one worker-observable sign per condition, plus the WHO IMCI general danger signs. Always the first node. | 1 (MULTI) |
| **B — Shared core** | Cross-branch comparability | `duration_bucket`, `onset_pattern`, `severity_score`, `progression`, `prior_treatment_taken`. Same fieldIds, same options, every branch. Prefilled from the consultation screen where already captured. | 3–5 |
| **C — Category qualifiers** | The narrowing that earns the tree | **Must include every qualifier the audit proved decisive for this category.** Optional for chronic/follow-up shapes (§4). | 3–6 |
| **D — Exposure and context** | Epidemiological signal | Category-appropriate: vector exposure, contact history, water/food source, occupation, drug history. | 1–2 |
| **E — Cross-links** | Reuse, not duplication | `SubtreeRef` to `FEVER-QUAL`, `RASH-MORPH`, `DEHYDRATION`, `PREGNANCY-DANGER` where the category interacts with them. | 0–2 |
| **Terminal** | Emit | — | 1 |

Target depth **6–9 nodes**; hard ceiling **12** excluding gateway nodes. A branch that needs more
than 12 is a category that should have been split, and the ceiling exists to surface that at design
time rather than after the worker's third screen.

### 6.2 What is already decided by this schema, per branch

Everything except the clinical content. The author of branch *n* does not decide: the node types,
the option shape, `next` explicitness (BR-1), the `unknown`/`none_of_these` requirement (BR-2), the
three-state `status`, the disposition lattice and its single `max()` writer, the base floor (it is
the category's `requiredDisposition`), the SLM's two adapters, NR-1, `BranchOutput`'s shape, or the
six replay assertions.

### 6.3 What the per-branch physician review must decide

Exactly four things, and they are the four that need a clinician:

1. **The Stage A danger-sign list** — which worker-observable signs stand in for this category's
   severe conditions.
2. **The Stage C qualifier nodes** — which 3–6 questions actually move the differential, and the
   exact fixed option text for each.
3. **The gateway rules** — the boolean over collected fields, and what each raises to.
4. **The cross-links** — which sub-trees this category shares.

Everything else is filled in mechanically. That is the whole point of spending this memo on the
schema.

### 6.4 Tier 0 branches are a different, smaller template

The six emergency branches (`emergency_convulsions`, `emergency_unconscious`,
`emergency_bite_sting`, `emergency_poisoning`, `emergency_heavy_bleeding`,
`emergency_pregnancy_danger`) do **not** use the five-stage template. They are the locked IMCI/CBAC
rule-based branching, unchanged. Their shape: entry → the minimum protocol fields needed for the
referral note (time of onset, substance/species where relevant, first aid given) → `REFER_EMERGENCY`
→ `ReferralRequest` with `UrgencyLevel.EMERGENCY` (`ReferralRequest.kt:6,16-25`). They are
`ALWAYS_ABSTAIN`: no classifier call is made, no differential is produced, and they never become ML.

### 6.5 Authoring order for the deferred fill-in step

Not alphabetical. Highest-volume and highest-severity first, so that a partial rollout is still a
safe rollout: `fever` (done, §3) → `cough` → `abdominal_pain` → `known_hypertension` (done, §4) →
`diarrhoea` → `chest_pain` → `rash` (done, §5) → `joint_pain` → `back_neck_pain` →
`breathlessness` → `known_diabetes` → `headache` → `injury` → `vomiting_nausea` →
`acidity_heartburn` → `urinary_symptoms` → `cold_sore_throat` → `weakness_unwell` → `body_ache` →
`weight_loss` → `oedema` → `pallor_anaemia` → `dizziness` → `itching` → `skin_infection` →
`antenatal_visit`. Plus the six Tier-0 branches (§6.4) and the degenerate `other_not_in_list` (§1.1).

---

## 7. Dataset contract handoff

This is the tree → dataset dependency made concrete. Dataset regeneration STEP-1 must produce a
corpus whose columns are exactly what the tree emits, or the two artifacts diverge and the model's
input contract stops matching what the device can actually collect.

### 7.1 The rule

> **One `fieldId` in the tree ⇒ exactly one column family in the dataset. One `optionId` in a node
> ⇒ exactly one permitted value in that column. Nothing else is a legal value.**

A column family is two columns: `<fieldId>` (the value) and `<fieldId>__status`
(`ANSWERED`/`UNKNOWN`/`NOT_ASKED`), because §1.5's three states cannot be encoded in one column
without re-creating the missingness leak.

`MULTI_CHOICE` fields expand to one boolean column per `optionId`
(`<fieldId>__<optionId>`) plus the shared `<fieldId>__status` — a set does not fit one cell, and
one-hot per option is what the boosted head consumes anyway.

### 7.2 The exact column list

| Group | Columns | Source |
|---|---|---|
| **Identity / provenance** | `row_id`, `patient_id`, `encounter_date`, `synthetic`, `source`, `generator_seed`, `branch_version`, `category_registry_version` | mostly EXISTS in `canonical_dataset.csv`; `branch_version` and `category_registry_version` are **NEW and mandatory** (§7.5) |
| **Label** | `category_id` | **NEW** — replaces `icd_candidate`. Per category memo §3.1 the model's class is the categoryId, not an ICD-10 code |
| **Coding backbone** | `icd10_mapping`, `icpc3_basis` | derived attributes of the category, carried for HMIS/ABDM. **Never model input, never model output** |
| **Demographics** | `age_at_encounter`, `age_band`, `sex`, `pregnancy_status`, `pregnancy_status__status` | EXISTS except `pregnancy_status` |
| **Vitals** | `bp_systolic`, `bp_diastolic`, `pulse`, `spo2`, `bmi`, `glucose`, `temperature`, `respiratory_rate` — each with its `_observed` flag | EXISTS (all six `_observed` columns already in the header); `temperature_observed` and `respiratory_rate_observed` are **NEW** |
| **Shared clinical** | `duration_bucket`, `onset_pattern`, `severity_score`, `severity_band`, `progression`, `prior_treatment_taken__*`, `relevant_history__*`, `impact_on_daily_activities__*`, `aggravating_factors__*`, `relieving_factors__*`, `attachment_photo_present` — each with `__status` | **NEW as columns.** `canonical_dataset.csv` has **no duration and no severity column at all**; the architecture memo §5 named that as the reason structured fusion is blocked |
| **Shared cross-cutting** | `danger_signs__*`, `fever_present`, `bleeding_manifestation__*`, `breathlessness_present`, `altered_consciousness`, `weight_loss_present`, `night_sweats`, `cough_ge_2_weeks`, `unable_to_drink_or_feed`, `urine_output_reduced` — each with `__status` | **NEW** |
| **Branch-specific** | the ~150 fieldIds of §2.5, each with `__status`; sparse by construction — `NOT_ASKED` on every row whose `category_id` does not root that branch | **NEW** |
| **Narrative** | `narrative_terms` (Layer 1 controlled-term list), `narrative_raw_present` (bool) | replaces `symptom_string` as model input; the raw string stays record content, not a feature |
| **Tree audit** | `path_taken`, `fired_gateways`, `disposition_floor`, `routed_to` | **NEW.** `disposition_floor` is a *target-adjacent* column and must be excluded from the feature set — see §7.4 |

Approximate width: **~40 identity/demographic/vitals + ~60 shared (value + status + multi-choice
expansion) + ~300 branch-specific** ≈ **400 columns**, against the current 30. Wide and sparse is
the right shape here: the model input is a fixed-width vector, the head is a boosted model over an
interpretable feature basis (architecture memo §4 Layer 2), and `NOT_ASKED` is an explicit token
rather than a hole.

### 7.3 Retired columns

`icd_candidate` (→ `category_id`) · `symptom_string` (→ `narrative_terms`, and the pipe-joined
118-term pool retires with it) · `symptom_signal_strength` (a generator artifact with no capture
affordance — no worker ever enters it) · `differential_candidates` (a generator artifact) ·
`fever_pattern_flag` (a boolean that the tree replaces with the six-valued `fever_pattern`, and
whose documented 8%/22% rate never matched its realized 28.13% — audit conflict L-4).

### 7.4 Four generation rules the dataset step must honour, or the tree's guarantees are lost

1. **Generate by walking the tree, not by sampling columns.** Each synthetic row must be produced by
   an actual traversal of the branch, so its `NOT_ASKED` pattern is path-determined. Sampling
   fields independently and then blanking some re-creates the audit's §C.2 missingness leak — the
   one that gave glucose a present-rate of 1.000 for E11 and 0.000 for A91/B50/J22. **This is the
   single most important constraint in this section.**
2. **`__status` must never correlate with the label beyond what the path forces.** A testable
   claim: for every field, conditional on `category_id` **and** on the answers that gate that
   field's node, `__status` must be independent of the label. That is a check the generation run
   can assert on its own output.
3. **`disposition_floor`, `fired_gateways` and `routed_to` are excluded from the feature set.**
   They are deterministic functions of the other fields *and* they encode the safety answer. A
   model that learns them learns to predict the rule, not the patient, and its accuracy would be a
   measurement of the tree.
4. **Class balance follows the category memo's §7 anchors**, not a uniform quota over vital-sign
   combinations. The current file's tier mix matching `GLOBAL_TIER_TARGET_RATIOS` to two decimal
   places (34.94/57.03/6.53/1.50 against 0.35/0.57/0.065/0.015) is the signature of an *imposed*
   distribution, and it is what the regeneration exists to replace.

### 7.5 Version pinning — how the two artifacts are stopped from diverging

Every dataset row carries `branch_version` and `category_registry_version`. Training refuses to run
when a corpus contains more than one `branch_version` for the same `category_id`, or when the
corpus's `category_registry_version` differs from the registry the label encoder was built from.
That, plus the CI check the category memo §3.3 already specifies (identical id sets across
registry, dropdown and label encoder), is the mechanical guarantee. It extends R1/R2/R3 by one
rule:

> **R4.** A branch change that adds, removes or renames a `fieldId` or an `optionId` is a dataset
> change and a model-input-contract change. It bumps `branch_version`, requires regeneration of the
> affected rows, and is a design change under normal change control — the same posture the model
> SOUP record already applies to shipped weights.

---

## 8. Dependencies, and what is deliberately NOT in this memo

| Item | Status |
|---|---|
| **The SLM's role** | Bounded to the two adapters in §1.7 — render a fixed question into vernacular, map a spoken answer onto a fixed option set by constrained decoding. Nothing else. The on-device SLM assistant workstream (MedGemma / Gemma-3-4B-IT) remains separate and excluded from the diagnose and prescribe path (architecture memo §7.2) |
| **The normalization lexicon (Layer 1)** | **Separate artifact, not designed here.** The tree produces coded options; Layer 1 produces controlled terms from free narrative. Both feed the classifier; they are different pipelines. Architecture memo §6.3: no Indic clinical corpus with usable provenance exists, so the lexicon must be built, not downloaded |
| **Abstention calibration (Layer 3)** | Not designed here. The tree emits a disposition floor; the conformal/selective gate that decides `InferenceSource.UNAVAILABLE` is Layer 3's. The one place they touch — whether a `REFER_EMERGENCY` floor should force abstention — is flagged open in §3.5 |
| **Authoring the remaining 24 branches** | **Deferred.** Downstream mechanical work against §6's template, physician-reviewed per branch, in §6.5's order |
| **Dataset regeneration** | Its own STEP-1, consuming §7. Not started |
| **Model training / retraining** | Not started. Every published figure for the current model (0.9212 accuracy, 0.7983 macro-F1) becomes meaningless on the new label space — the correction, not a regression |
| **The tree implementation** | No code. No Kotlin, no JSON, no branch file, no runtime |
| **UI / screen design for the tree** | Not in scope. §3–§5 specify content and structure; how many questions share a screen is a presentation decision |
| **Risk-file registration** | The red-flag gateway warrants its own row at the next free id **H-21** (H-20 is the highest on disk, confirmed this session), and the tree's own hazards (a mis-authored gateway, a branch that fails NR-1) plausibly warrant one more. Operator-signed docs change, separate commit, **not done here** |
| **Intended-use statement** | §b/§f updates remain the operator-signed docs change the category memo §11.5 names. Untouched |

---

## 9. Boundaries observed

No source, dataset, model, config, questionnaire or tree file was written, edited or deleted in
either repo. The SaMDClassifier repo was **read only** — one CSV header line and `git status` /
`git rev-parse`; `git status --short` there is **empty** and HEAD is unchanged at `63e85af`. No
`.env`, `local.properties` or credential file was opened at any point. No commit, no staging. No
branch was authored in code, no dataset regenerated, no model trained or evaluated.
