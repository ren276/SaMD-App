# Reason-for-encounter category system (STEP 1, read-only design memo)

Run date: 2026-09-06. SaMDApp branch `master`, HEAD `cab78ae`.
SaMDClassifier `main`, HEAD `63e85af` — **read-only for this task; nothing written, staged or
committed there.**

**Read-only guarantee.** Nothing written, edited or deleted outside this one memo. No source,
dataset, model, config or questionnaire file touched, in either repo. No training, no generation,
no relabelling, no tree-building. No `.env`, `local.properties` or credential file opened at any
point. No commit, no staging.

**Inputs treated as established fact and not re-derived**:
`scratchpad/classifier-dataset-nlem-audit-memo.md`, `scratchpad/production-classifier-architecture-memo.md`.
Also read for continuity: `scratchpad/classifier-wire-format-investigation.md` (the 41-item
vocabulary measurement, which this memo supersedes — see §3.4).

---

## 0. Three reconciliations before the design, because the design rests on them

### 0.1 The 45.6% anchor: verified, and it means something more specific than assumed

The architecture memo's headline is *"General/unspecified 45.6% at primary facilities"*. I fetched
the source paper (Gupta P, Bharati B, Sahu KS, Mahapatra P, Pati S. *PLOS Glob Public Health*
2024;4(5):e0001835) and read its tables directly, because the whole scope instruction rests on that
number. Two of my three retrieval passes reported that 45.6% *did not appear in the paper*. Reading
the PDF text directly settles it: **it does appear, in Table 3, and the architecture memo
transcribed it correctly.** But the paper carries *two different* primary-level figures for the same
chapter, and the difference is not an error — it is a difference of denominator that matters
directly to this device:

| Source table | "Primary" denominator | General | Digestive | Musculoskeletal | Coverage |
|---|---|---|---|---|---|
| **Table 2** (full 15-chapter distribution) | **n = 701** | 250 (**35.7%**) | 98 (14.0%) | 96 (13.7%) | all chapters |
| **Table 3** (top-three chapters only) | **n = 485** | 221 (**45.6%**) | 86 (17.7%) | 58 (12.0%) | 75.3% of that n; the other 24.7% unallocated |

The methods section resolves it. Sampling was planned as *"nearly 160 patients from PHC, 240 from
CHC, 330 from DHH, 850 from MCH"* across three districts. 160 × 3 = 480 ≈ **485**, and
701 − 485 = 216 ≈ the planned 240 CHC patients. So:

> **Table 3's "Primary" (n=485) is PHC-only. Table 2's "Primary" (n=701) is PHC + CHC pooled.**

**Consequence for this design, and it strengthens the brief rather than weakening it.** This device
is a *PHC* device. The PHC-only figure is the correct anchor, and it is the higher one: **General is
45.6% of chief complaints at a rural Indian PHC**, against 35.7% once CHCs are pooled in. The
instruction to build general-first is anchored on the right number.

Both tables are used below, for different jobs: **Table 3 for the headline General share at PHC**,
**Table 2 for the shape of the long tail**, because Table 2 is the only place the smaller chapters
(respiratory, skin, neurological, pregnancy…) are reported at primary level at all.

**Caveat recorded honestly.** The paper has at least three internal defects: it states ICPC-3 has
*"19 chapters"* (it has **17** — verified against the ICPC-3 v1.80.00 frozen release and the WONCA
structure); Table 2 prints `28(91.1)` for neurological diagnoses, and Table 3 prints `117(2.1)` for
urban digestive, both plainly typographic. No single cell should be load-bearing on its own. The
design below therefore never rests on one cell — every category cross-checks the Odisha share
against at least one other Indian source.

### 0.2 The intended-use statement already records this artifact as a gap

`docs/requirements/intended-use-statement.md` §b:

> Scoped to the presentation types the kernel's training data and scenario tables currently cover…
> **Gap:** no formal, CDSCO-facing enumerated condition list exists yet — the scenario table is an
> engineering artifact, not a regulatory-reviewed scope statement.

and §f:

> Contraindications: None formally established. **Gap, flagged not fabricated**… The emergency
> red-flag override (SpO2<90%, BP thresholds) is a safety net, not a substitute for a
> contraindications analysis.

**The category system defined here is the artifact that closes both gaps**: the core set is the
enumerated indications list, and the deferred set is the documented limits-of-competence that a
contraindications analysis builds on. Writing it into those documents is a separate, operator-signed
docs change and is **not** done by this memo (§11).

### 0.3 H-20 has landed since the architecture memo was written

`docs/quality/risk-management-file.md` now carries H-20 (empty/token-free symptom input →
confident E66 at 74.95%), PROPOSED-pending-operator, with the guard implemented in
`GenerateEvaluateReportUseCase`. The architecture memo's §9.1 "do this now" item is done. This
matters here because H-20's residual explicitly names the re-opening condition:

> Any future change that can resolve the chief complaint to an empty token list (a collapsible
> pick-list, a normalization layer that drops all terms, an on-device port) re-opens this at the
> new seam.

A chief-complaint dropdown is exactly such a change. §4.3 makes non-collapsibility a schema
invariant rather than a UI convention.

---

## 1. The seed: the current 18 categories, enumerated from disk

Read from `SaMDClassifier/models/symptom_model_meta.json` (`labels`), name-mapped from
`SaMDClassifier/src/rag/rag_pipeline.py:16-35` (`ICD_TO_DISEASE_NAME`), and counted from
`SaMDClassifier/dataset/canonical_dataset.csv` (22,215 rows; 15,105 labelled). The app's mirror at
`app/src/main/java/com/example/samdapp/domain/model/TrainedIcdCandidate.kt:13-32` agrees on all 18.

| # | ICD-10 | Name (classifier repo) | Rows | % of all rows | % of labelled | val F1 |
|---|---|---|---|---|---|---|
| 1 | **E66** | Obesity | 6,317 | 28.44 | **41.82** | 0.945 |
| 2 | **M17** | Osteoarthritis of the knee | 3,499 | 15.75 | 23.16 | 0.932 |
| 3 | I10 | Essential hypertension | 1,283 | 5.78 | 8.49 | 0.928 |
| 4 | N39.0 | Urinary tract infection | 556 | 2.50 | 3.68 | 0.909 |
| 5 | A09 | Infectious gastroenteritis and colitis | 538 | 2.42 | 3.56 | 0.907 |
| 6 | A01.0 | Typhoid fever | 446 | 2.01 | 2.95 | 0.880 |
| 7 | B54 | Unspecified malaria | 445 | 2.00 | 2.95 | 0.899 |
| 8 | A90 | Dengue fever | 429 | 1.93 | 2.84 | 0.874 |
| 9 | A92.0 | Chikungunya fever | 315 | 1.42 | 2.09 | 0.917 |
| 10 | G43.9 | Migraine | 227 | 1.02 | 1.50 | 0.897 |
| 11 | E11 | Type 2 diabetes mellitus | 212 | 0.95 | 1.40 | 0.767 |
| 12 | A15 | Respiratory tuberculosis | 208 | 0.94 | 1.38 | 0.932 |
| 13 | F41.0 | Panic disorder | 182 | 0.82 | 1.20 | 0.841 |
| 14 | D50 | Iron deficiency anaemia | 178 | 0.80 | 1.18 | 0.841 |
| 15 | E05.9 | Thyrotoxicosis (hyperthyroidism) | 170 | 0.77 | 1.13 | 0.758 |
| 16 | **A91** | Dengue haemorrhagic fever | 54 | 0.24 | 0.36 | **0.381** |
| 17 | **J22** | Acute lower respiratory infection | 25 | 0.11 | 0.17 | **0.364** |
| 18 | **B50** | *P. falciparum* malaria | 21 | 0.09 | 0.14 | **0.400** |
| — | *(unlabelled)* | no disease-level label | 7,110 | 32.01 | — | — |

Three facts read straight off this table, all corroborating the audit:

1. **Two classes are 65% of the labelled corpus, and neither is a reason for encounter.** E66 is a
   finding recorded during an encounter; M17 is a specific joint diagnosis. Both hold their position
   through the US-BMI × WHO-Asian-cutoff artifact the audit measured (§D.4: `abnormal_params ==
   "bmi"` alone is 35.44% of the file, resolving 0.65 obesity / 0.35 osteoarthritis).
2. **The three worst-performing classes are exactly the three severe conditions.** A91 (severe
   dengue) F1 0.381, J22 (LRTI) 0.364, B50 (falciparum malaria) 0.400 — on 54, 25 and 21 rows. The
   device is weakest precisely where being wrong kills. This is the single strongest argument for
   the red-flag gateway design in §4: these must never be things the model is trusted to rule out.
3. **32.01% of the corpus has no disease-level label at all.** The dataset's own largest bucket is
   "no diagnosis" — the general/unspecified reality — and the model is trained with those rows
   excluded (`row_filter` in the meta file). The model is structurally incapable of expressing the
   largest thing a PHC sees.

---

## 2. Data-source ordering, and every source used

Ordering is Indian-first, as instructed. Each source is typed, because the type governs how the
number may be used.

| Type | Meaning | Legitimate use |
|---|---|---|
| **IND-PRESENT** | Indian, presenting/reason-for-encounter | Direct anchor for a category's expected share |
| **IND-POP** | Indian, population prevalence | Ordering and plausibility only; **never** a presenting share |
| **IND-PROG** | Indian programme/surveillance returns | Severity and seasonality, not denominators |
| **WORLD** | Non-Indian, used only where no Indian source exists | Must be named at point of use |
| **ASSUMED** | No source exists | Declared, never a silent weight |

**IND-PRESENT (primary calibration)**

- **Odisha ICPC-3 study** — Gupta P *et al.*, *PLOS Glob Public Health* 2024;4(5):e0001835.
  3,044 interviewed, **2,565 analysed**; 3 DH + 3 SDH + 6 CHC + 6 PHC + 2 tertiary across Cuttack,
  Sambalpur, Nabarangapur. **65% presented a symptom, 35% a disease.** PHC-only and PHC+CHC
  distributions per §0.1. *The only ICPC-3-coded Indian reason-for-encounter dataset in existence.*
  Proxy caveat: Odisha, not MP. Urban PHCs were excluded, so the primary tier is rural — which
  matches this device's setting.
- **POSEIDON** — *Lancet Glob Health* 2015; 1-day point prevalence, 1 Feb 2011, 7,400 practitioners
  across 880 cities/towns, **204,912 patients**. Fever **35.5%**, cough **30%**; respiratory >50%,
  digestive 25%, circulatory 12.5%, skin 9%, endocrine 6.6%. Caveat: private
  GP/physician/paediatrician practice in towns and cities — **not** a rural government PHC. Used as
  a cross-check on rank order, not as the share anchor.
- **AIIMS Bhopal CUHA** — Gupta A, Reddy BV, Nagar MK, Chandel A, Bali S. *Health Serv Res Manag
  Epidemiol* 2015;2:2333392815598291. **Madhya Pradesh**, Jan–Dec 2014, **6,685** new episodes.
  Respiratory 27.2%, Others 24.5%, Digestive 10.9%, Circulatory 9.9%, Musculoskeletal 8.8%,
  Endocrine 6.5%, Infectious/parasitic 7.4%, External cause 3.2%, Blood 1.6%. Top diagnoses: acute
  RTI 23.9%, hypertension 8.9%, oesophagus/stomach/duodenum disease 6.8%, diabetes 5.1%.
  **The only MP-specific presenting dataset found.** Caveat: *urban* health centre, Bhopal.
- **AUFI multicentre study** — *BMC Infect Dis* 2017;17:665. 1,564 patients, fever 2–14 days, seven
  community hospitals in six Indian states, 2011–12. Malaria **17%** (54% *falciparum*), dengue
  **16%**, scrub typhus **10%**, bacteraemia **8%** (35% *S. typhi/paratyphi*), leptospirosis 7%,
  chikungunya 6%. Caveat: hospitalised, so severe-shifted. Used for the **fever gateway's condition
  list**, not for its share.

**IND-POP (ordering and plausibility only)**

- **NFHS-5 (2019–21), Madhya Pradesh**: anaemia in children 6–59 months **72.7%**, women 15–49
  **54.7%**, men 15–49 **22.5%**.
- **ICMR-INDIAB-17** — *Lancet Diabetes Endocrinol* 2023;11(7):474-489. 113,043 participants
  (79,506 rural). National weighted: diabetes **11.4%**, prediabetes **15.3%**, hypertension
  **35.5%**, generalised obesity **28.6%**, abdominal obesity 39.5%, dyslipidaemia 81.2%. MP is
  named among the states with a diabetes:prediabetes ratio ≤ 1:2 — an earlier epidemic stage.
  *MP-specific INDIAB cells could not be retrieved in this session (publisher 403); recorded as a
  gap, not estimated.*
- **COPCORD India (BJD)** — MSK pain **16.14%** overall, **rural 20% vs urban 10.3%**;
  osteoarthritis 4.39%. Community knee-OA prevalence in rural adults ≥40 measured at 34.6% in a
  Tamil Nadu study.
- **Rural Central India skin survey** — community skin-disease prevalence reported at **60%**
  (community screening, not presenting); other Indian community studies range 7.86%–55.5%.

**IND-PROG**

- **NCVBDC/NVBDCP**: national malaria TPR fell 3.50 (1995) → **0.14 (2024)**; *P. falciparum*
  1.14M → **0.15M** cases. MP remains a focus state with tribal high-burden districts.
  *District-level MP returns were not obtainable in this session — flagged as a gap; any
  seasonality weights are ASSUMED, not derived.*
- **Nikshay / India TB Report**: **2,630,021** TB cases notified in India in 2024; incidence 237 →
  **187 per lakh** (2015→2024). MP is a high-burden state, 4th by absolute case count.

**WORLD (named at point of use, only two)**

- **ICPC-3 itself** (WONCA/WICC, v1.80.00 frozen 2025-01-13) — 17 chapters, bi-axial, component 1 =
  symptoms/complaints (codes 01–29), component 7 = diagnoses/diseases (70–99). A worldwide
  instrument by construction; that is the point of anchoring on it.
- **WHO IMCI general danger signs** — the basis of the Tier-0 emergency entries (§6). No Indian
  substitute exists and none is needed; IMCI is the protocol Indian PHC staff are already trained on.

**ASSUMED** — declared inline in the tables as `ASSUMED`, always with the reasoning.

---

## 3. One artifact, three roles — the load-bearing statement

### 3.1 The claim

There is exactly one list. Call it `ReasonForEncounterCategory`. It is simultaneously:

1. **The classifier's output label space.** The model's classes are `categoryId` values. It does
   **not** emit ICD-10. ICD-10 is an *attribute* of a category, carried for the coding backbone
   (HMIS returns, ABDM, the NLEM lookup), never the model's output space. This is the actual
   relabelling the architecture memo §9.2 item 1 calls for.
2. **The chief-complaint dropdown's options at capture.** The same ids, the same order,
   `displayName` rendered. What persists to `Consultation.chiefComplaint` and crosses the wire is
   the id, not the display string.
3. **The questionnaire tree's entry points.** Each `categoryId` is the root of exactly one branch.

### 3.2 Why they cannot be allowed to diverge, stated as failure modes rather than principle

| Divergence | What actually happens | Evidence on disk |
|---|---|---|
| A dropdown option with **no label** | The worker selects it; the model has no class for it; it answers from its prior. This is H-20 with extra steps. | Measured: `'Toothache' → B54 malaria 0.437`; `'Child fever' → N39.0 UTI 0.416`; `'Diarrhoea / loose motions' → M17 knee OA 0.644` |
| A label with **no dropdown option** | The model can emit a class no worker could have asked about — an output nobody can trace to an input. Unfalsifiable in review. | J22, A91, B50 today: 25/54/21 rows, F1 0.36–0.40, and no capture affordance names them |
| A category with **no tree branch** | The model receives a bare token instead of a qualified one. | Measured: bare-token pick-list scores **0.580** top-1 vs **0.941** at full granularity — a **−30.6 point** regression. And **12 of 13** generic-vs-qualified term pairs flip the top-1 diagnosis (`body ache`→B54 94% vs `severe body ache`→A90 87%) |

**Therefore, three build-gating rules, not style preferences:**

- **R1.** No dropdown option may exist without a category. To add a capture affordance safely, add a
  category with `modelBehaviour = ALWAYS_ABSTAIN` (§4.2) — never a bare option.
- **R2.** No label may exist without a dropdown option.
- **R3.** A category ships to production only when its tree branch ships. A category with an entry
  point and no branch is the 0.580 measurement, shipped.

### 3.3 What "the same artifact" means concretely

One versioned file in the classifier repo — the category registry — is the single source. The
device's dropdown list and the model's label encoder are both **generated** from it, and the
questionnaire tree's root nodes are keyed by `categoryId` from it. A CI check that the three
derived artifacts have identical id sets is the mechanical enforcement of R1/R2. (Building that is
a later step; naming it here is what makes R1–R3 testable rather than aspirational.)

### 3.4 This supersedes the 41-item vocabulary

`scratchpad/classifier-wire-format-investigation.md` measured a drafted 41-item chief-complaint
vocabulary at **−30.6 points** and recorded finding X-4: the vocabulary and the classifier "are
answering two different questions", with roughly **17 of 41** items having no trained equivalent at
all. That measurement was correct and it is exactly the divergence R1 forbids. The resolution is not
a better vocabulary bolted onto the old labels — it is this memo: **the vocabulary and the label
space become the same object.** The 41-item list is retired as a separate artifact; its items are
absorbed into §7 (core), §8 (deferred) or §6 (emergency), and every one of the 17 orphans now has a
declared home.

---

## 4. The category schema, and the safety floor

### 4.1 Fields

```
ReasonForEncounterCategory {
  id                  // stable, snake_case, persisted, on the wire, the model's class
  displayName         // worker-facing; Hindi/Malwi strings are a later localisation step
  tier                // EMERGENCY | CORE | DEFERRED
  icpc3Chapter        // one of the 17 chapter letters
  icpc3Basis          // rubric-level ICPC-3 code(s)
  icd10Mapping        // for the coding backbone only, never the model's output
  anchor {
     value            // expected share of PHC encounters
     sourceType       // IND-PRESENT | IND-POP | IND-PROG | WORLD | ASSUMED
     source           // the citation
  }
  modelBehaviour      // LEARNED | ALWAYS_ABSTAIN
  redFlagGateway      // null, or:
      { severeConditions[]
        gatewayStrength         // HIGH | MODERATE
        requiredDisposition     // PHYSICIAN_REVIEW_MANDATORY | REFER_URGENT | REFER_EMERGENCY
      }
}
```

**Code-verification note.** ICPC-3 chapter letters and the component structure were verified
(17 chapters; symptoms/complaints 01–29, diagnoses/diseases 70–99). Individual rubric codes below
are given at ICPC-2/ICPC-3 rubric level and **must be confirmed cell-by-cell against the ICPC-3
v1.80.00 frozen browser at build time** — ICPC-3 renumbered some rubrics relative to ICPC-2, and
this memo does not assert per-code exactness it did not verify.

### 4.2 `modelBehaviour`, and why abstention is a category property

`ALWAYS_ABSTAIN` means the category is a full member of all three artifacts — it is in the dropdown,
it is in the label space, it roots a tree branch — but the model never produces a differential for
it. It resolves to `InferenceSource.UNAVAILABLE` plus the category's disposition.

This is what makes the brief's "the model knows what it does not cover" a mechanism rather than a
slogan, and it is what lets a dangerous presentation (convulsions, snake bite) have a safe capture
affordance without ever being something the model is trusted to reason about. It is the schema-level
expression of the architecture memo's Layer 3 abstention, decided at design time rather than at
inference time.

### 4.3 Non-collapsibility (H-20's re-opening condition, closed by construction)

**Invariant NC-1.** The dropdown has no null/empty selection that reaches the model. There is
always exactly one selected category, and `other_not_in_list` (§7) is the terminal fallback rather
than "nothing selected". `other_not_in_list` is `ALWAYS_ABSTAIN`.

This is what stops a pick-list from re-creating the E66-at-74.95% path that H-20 registers. It is a
schema invariant, not a UI convention, so a future screen cannot reintroduce it.

### 4.4 The clinical-safety floor — the non-negotiable part

Some general presentations are the early, benign-looking face of a dangerous disease. Fever is the
gateway to malaria, dengue, typhoid, scrub typhus, leptospirosis and TB. The device's own three
worst classes (A91 0.381, J22 0.364, B50 0.400) are exactly these. So:

> **RF-1 (no implied rule-out).** For any category with `redFlagGateway != null`, no output surface
> may render a reassuring or ruling-out statement about the listed severe conditions. The
> differential may rank a benign cause first. It may never render "not X", "unlikely X", or a
> normal-range framing that a reader takes as "X excluded". **The model is not diagnosing the severe
> disease; it is forbidden from implying its absence.**

> **RF-2 (disposition is a floor, not a default).** `requiredDisposition` is set by the *category*,
> not by the model's confidence. A high-confidence benign differential does not lower it. Vitals
> triage, the tree, or the physician may raise it. Nothing lowers it.

> **RF-3 (cadre-scoped, reusing the existing gate).** `PHYSICIAN_REVIEW_MANDATORY` for a user whose
> `CadreTier` is `COMMUNITY` or `LICENSED_CLINICAL` (`AuthSession.kt:20`, `toCadreTier()` at `:29`)
> means the case cannot be closed at the PHC without a physician decision through the existing
> `SubmitDoctorDecisionUseCase` AGREE/MODIFY/REJECT path. No second gate is invented.
> `REFER_URGENT` / `REFER_EMERGENCY` map onto the existing `UrgencyLevel.URGENT` / `.EMERGENCY` and
> `ReferralRequest` (`ReferralRequest.kt:6,16-25`).

> **RF-4 (the floor beats the instruction).** The operator's "treat small presentations as their own
> disease" is honoured — Fever *is* its own category and is never forced into a malaria/dengue/
> typhoid choice. The two only conflict if "Fever" is ever allowed to be a *terminal benign answer*.
> **It is not.** Where the instruction and the floor appear to conflict, the floor wins, and this
> sentence is the flag the brief asked for.

**Why this is stronger than the existing control.** `intended-use-statement.md` §f records that the
current red-flag override is vitals-based (SpO2 < 90%, BP thresholds) and explicitly "not a
substitute for a contraindications analysis". A gateway keyed to the *presentation* fires when the
vitals are still normal — which is precisely the early, benign-looking phase where dengue,
falciparum malaria and TB are missed.

---

## 5. Tier structure

| Tier | Count | In dropdown? | In label space? | Tree branch? | Model |
|---|---|---|---|---|---|
| **0 — Emergency** | 6 | yes | yes | yes (rule-based only) | `ALWAYS_ABSTAIN` |
| **1 — Core** | 27 | yes | yes | yes | `LEARNED` (except the fallback) |
| **2 — Deferred** | 16 named | **no** | no | no | reached via `other_not_in_list` → abstain |

Tier 0's branches are the locked IMCI/CBAC rule-based branching the architecture memo §4 Layer 1
keeps untouched. They are not ML and never become ML.

---

## 6. Tier 0 — emergency entry points (present everywhere, classified nowhere)

These exist so a worker facing an emergency has somewhere to put it that is *safe*, and so the
device cannot be asked to reason about it. All are `ALWAYS_ABSTAIN`, all `REFER_EMERGENCY`.
Basis: WHO IMCI general danger signs (**WORLD** — named, as required; no Indian substitute exists,
and IMCI is what Indian PHC staff are trained on).

| id | displayName | ICPC-3 | ICD-10 | Why it can never be `LEARNED` |
|---|---|---|---|---|
| `emergency_convulsions` | Fits or convulsions | N07 | R56.9 | Time-critical; a differential delays transport |
| `emergency_unconscious` | Unconscious or very drowsy | N05 / A29 | R40.2 | Same; also cerebral malaria / hypoglycaemia / meningitis |
| `emergency_bite_sting` | Snake bite, scorpion sting, animal bite | A80 / S13 | T63.-, W59, T14.1 | Antivenom/PEP is protocol, not inference. High rural-MP relevance |
| `emergency_poisoning` | Poisoning or overdose | A86 | T65.9 | Protocol; organophosphate is a major rural Indian presentation |
| `emergency_heavy_bleeding` | Heavy bleeding from anywhere | A10 | R58 | Includes severe dengue's presentation |
| `emergency_pregnancy_danger` | Pregnancy danger sign (bleeding, fits, severe headache, no fetal movement) | W03 / W99 | O20.9, O15.-, O46.- | Eclampsia/APH. The Odisha paper notes pregnancy is one of only three things primary facilities actually handle |

---

## 7. Tier 1 — the core category set

27 entries: 26 clinical categories plus the mandatory terminal fallback. Coverage against the Odisha
PHC-level anchor is ~93% of realised chapters. All `LEARNED` except `other_not_in_list`.

**How `anchor.value` was derived.** Chapter-level shares come from Odisha (Table 3 for General at
PHC-only; Table 2 for the rest at primary level). Within-chapter apportionment to individual
categories has **no Indian source at that granularity** — so every within-chapter split is marked
`ASSUMED`, with the chapter total and the reasoning stated. This is the brief's rule applied
literally: no silent weights.

### Chapter A — General and unspecified · **45.6% of PHC complaints** (IND-PRESENT, Odisha T3)

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway → severe conditions | Disposition |
|---|---|---|---|---|---|---|
| `fever` | Fever | A03 | R50.9 | POSEIDON fever **35.5%** of all primary-care patients (IND-PRESENT); Odisha names fever the commonest reason **at every facility level** | **HIGH** — malaria (incl. *falciparum*), dengue (incl. severe), typhoid, scrub typhus, leptospirosis, TB, sepsis, meningitis. AUFI: malaria 17%, dengue 16%, scrub typhus 10%, enteric 8%×35%, lepto 7%, chik 6% | **PHYSICIAN_REVIEW_MANDATORY** |
| `weakness_unwell` | Weakness, tiredness, not feeling well | A04, A05 | R53 | ~10% of chapter A `ASSUMED` — no Indian source splits chapter A; it is the residual "general" presentation and MP anaemia (54.7% women, 72.7% children, NFHS-5) makes it high-volume | **MODERATE** — anaemia, TB, diabetes, hypothyroidism, depression, occult malignancy, HIV | PHYSICIAN_REVIEW_MANDATORY |
| `body_ache` | Body ache or pain all over | A01 | M79.1, M79.7 | ~7% of chapter A `ASSUMED` | **MODERATE** — dengue, chikungunya, influenza-like illness, enteric fever | PHYSICIAN_REVIEW_MANDATORY |
| `weight_loss` | Losing weight without trying | T08 | R63.4 | ~2% of chapter A `ASSUMED`; India TB incidence **187/lakh**, MP 4th-highest burden (IND-PROG, Nikshay) | **HIGH** — TB, diabetes, HIV, malignancy, hyperthyroidism | REFER_URGENT |
| `oedema` | Swelling of feet, face or body | K07 | R60.9 | ~2% of chapter A `ASSUMED` | **HIGH** — heart failure, nephrotic syndrome/renal failure, severe anaemia, chronic liver disease, pre-eclampsia | REFER_URGENT |

*Note on chapter A.* These five plus the fever gateway are the whole point of the relabelling. The
current model has **no class for any of them**, and 32.01% of its own corpus is unlabelled precisely
because this bucket has nowhere to go.

### Chapter R — Respiratory · **10.1%** at primary (Odisha T2); MP cross-check: AIIMS Bhopal respiratory **27.2%**, acute RTI **23.9%**

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `cough` | Cough | R05 | R05 | POSEIDON cough **30%** (IND-PRESENT) | **HIGH** — **pulmonary TB** (cough ≥2 weeks is the national screening trigger), pneumonia, asthma, COPD, lung malignancy | **PHYSICIAN_REVIEW_MANDATORY** |
| `cold_sore_throat` | Cold, sore throat or blocked nose | R07, R21, R74 | J00, J02.9, J06.9 | Largest single AIIMS Bhopal diagnosis group (acute RTI 23.9%, IND-PRESENT, MP) | **MODERATE** — paediatric pneumonia presenting as "cold"; rheumatic fever after streptococcal sore throat | PHYSICIAN_REVIEW_MANDATORY |
| `breathlessness` | Difficulty breathing / breathlessness | R02 | R06.0 | ~15% of chapter R `ASSUMED` | **HIGH** — pneumonia, severe asthma, heart failure, severe anaemia, severe dengue, severe malaria, anaphylaxis | REFER_URGENT |

*Retires three OOV orphans from the 41-item list:* sore throat, nasal congestion/sneezing, and
"cough / difficult breathing (child)" all now have homes.

### Chapter D — Digestive · **17.7%** PHC-only (Odisha T3) / 14.0% primary (T2); POSEIDON digestive **25%**

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `abdominal_pain` | Stomach or abdominal pain | D01, D06 | R10.4 | Odisha names abdominal pain a **top-3 individual complaint** (IND-PRESENT) | **HIGH** — acute abdomen, appendicitis, perforation, **ectopic pregnancy**, obstruction, pancreatitis, enteric fever | REFER_URGENT |
| `acidity_heartburn` | Acidity, heartburn or gas | D03, D08 | R12, K30 | AIIMS Bhopal: oesophagus/stomach/duodenum disease **6.8%**, its 3rd-commonest diagnosis (IND-PRESENT, MP) | **HIGH** — **cardiac pain presenting as acidity**; peptic ulcer bleeding; gastric malignancy | PHYSICIAN_REVIEW_MANDATORY |
| `diarrhoea` | Loose motions / diarrhoea | D11 | A09, K52.9 | ~20% of chapter D `ASSUMED`; AIIMS Bhopal diarrhoeal disease 7.9% (IND-PRESENT, MP) | **HIGH** — dehydration (**especially under-5**), cholera, dysentery, enteric fever | PHYSICIAN_REVIEW_MANDATORY |
| `vomiting_nausea` | Vomiting or nausea | D09, D10 | R11 | ~12% of chapter D `ASSUMED` | **HIGH** — dehydration, obstruction, raised intracranial pressure, DKA, pregnancy, poisoning | PHYSICIAN_REVIEW_MANDATORY |

*`acidity_heartburn` was the single flat "OOV, no overlap" item in the 41-item table — "none. Model
has never seen it" — and it is the third-commonest diagnosis group in the one MP dataset available.
That gap is the clearest single illustration of the label-space defect.*

### Chapter L — Musculoskeletal · **12.0%** PHC-only (Odisha T3) / 13.7% (T2); COPCORD rural MSK pain **20%** (IND-POP)

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `joint_pain` | Joint pain or swelling | L20, L15 | M25.5 | Odisha names arthritis a top-5 individual complaint (IND-PRESENT); COPCORD OA 4.39% (IND-POP) | **MODERATE** — septic arthritis, chikungunya, acute rheumatic fever, gout, TB of joint | PHYSICIAN_REVIEW_MANDATORY |
| `back_neck_pain` | Back or neck pain | L01, L02, L03 | M54.5, M54.2 | ~30% of chapter L `ASSUMED`; COPCORD reports back pain 17.3% in rural females (IND-POP) | **HIGH** — **spinal TB (Pott's)**, cauda equina, vertebral fracture, malignancy, referred renal/aortic pain | PHYSICIAN_REVIEW_MANDATORY |
| `injury` | Injury or accident | A80, L81 | T14.9 | Odisha names injury a **top-9 individual complaint**; AIIMS Bhopal external cause **3.2%** (IND-PRESENT, MP) | **HIGH** — fracture, head injury, internal haemorrhage, tetanus-prone wound, burn | REFER_URGENT |

*`back_neck_pain` and `injury` are both entirely absent from the current 18. Injury was measured
**inert** against the model ("Injury / trauma → E66 0.735", i.e. the obesity prior).*

### Chapter S — Skin · **5.7%** at primary (Odisha T2); POSEIDON skin **9%**

The Odisha authors specifically flag skin as a high-burden, low-capability area: *"There is a high
burden of skin diseases in India with very limited facilities for primary health care… most common
skin disease diagnoses are based on visual inspection."* Visual inspection is what this device's
photo-attachment path already captures, so three categories are justified rather than one.

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `rash` | Rash or skin patch | S06, S07 | R21 | ~40% of chapter S `ASSUMED` | **HIGH** — **dengue rash**, measles, drug reaction / SJS-TEN, **leprosy** (hypopigmented anaesthetic patch), meningococcaemia | PHYSICIAN_REVIEW_MANDATORY |
| `itching` | Itching | S02 | L29.9 | ~35% of chapter S `ASSUMED`; rural Central India community skin prevalence 60%, fungal/scabies dominant (IND-POP) | **MODERATE** — scabies (household outbreak), diabetes, liver/renal disease, drug reaction | PHYSICIAN_REVIEW_MANDATORY |
| `skin_infection` | Boil, wound or skin infection | S10, S11, S76 | L02, L03 | ~25% of chapter S `ASSUMED` | **HIGH** — cellulitis, abscess, **diabetic foot**, necrotising infection, tetanus-prone wound | REFER_URGENT |

### Chapter N — Neurological · **3.6%** at primary (Odisha T2)

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `headache` | Headache | N01 | R51 | ~55% of chapter N `ASSUMED` | **HIGH** — meningitis, **cerebral malaria**, stroke, subarachnoid haemorrhage, hypertensive emergency, pre-eclampsia | PHYSICIAN_REVIEW_MANDATORY |
| `dizziness` | Dizziness or giddiness | N17 | R42 | ~25% of chapter N `ASSUMED` | **HIGH** — severe anaemia (MP: 54.7% of women), hypoglycaemia, arrhythmia, stroke, orthostatic hypotension, dehydration | PHYSICIAN_REVIEW_MANDATORY |

### Chapter U — Urinary · 0.4% at primary (Odisha T2), **but** AIIMS Bhopal records UTI ~8.1%

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `urinary_symptoms` | Burning or frequent urination | U01, U02, U71 | R30.0, N39.0 | **Conflicting anchors, both retained**: Odisha 0.4% (IND-PRESENT), AIIMS Bhopal ~8.1% (IND-PRESENT, MP). Discrepancy is almost certainly reporting/stigma, not incidence — resolve with field data, do not average | **HIGH** — pyelonephritis, urinary obstruction/stone, STI, sepsis in the elderly or diabetic, UTI in pregnancy | PHYSICIAN_REVIEW_MANDATORY |

### Chapter K — Circulatory · **6.6%** at primary (Odisha T2); AIIMS Bhopal **9.9%**

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `chest_pain` | Chest pain | K01, K02 | R07.4 | Odisha names chest pain a **top-4 individual complaint** (IND-PRESENT) | **HIGH** — myocardial infarction, unstable angina, pulmonary embolism, pneumothorax, aortic dissection, pericarditis | **REFER_URGENT** |
| `known_hypertension` | Known high BP — check or follow-up | K86 | I10 | Odisha: hypertension is the **2nd commonest reason overall** and one of only **three** things primary facilities actually handle. AIIMS Bhopal hypertension 8.9%; INDIAB national 35.5% (IND-POP) | **MODERATE** — hypertensive emergency, stroke, heart failure, renal disease, pre-eclampsia | PHYSICIAN_REVIEW_MANDATORY |

### Chapter T — Endocrine/metabolic/nutritional · 1.3% at primary (Odisha T2); AIIMS Bhopal 6.5%

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `known_diabetes` | Known diabetes — check or follow-up | T90 | E11 | AIIMS Bhopal diabetes 5.1% (IND-PRESENT, MP); INDIAB national diabetes 11.4% / prediabetes 15.3%, MP ratio ≤1:2 (IND-POP) | **HIGH** — DKA, hypoglycaemia, diabetic foot, silent MI, TB co-infection | PHYSICIAN_REVIEW_MANDATORY |

### Chapter B — Blood · 0.1% at primary (Odisha T2) — **the one place the presenting anchor is deliberately overruled**

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `pallor_anaemia` | Pallor / suspected anaemia | B80, B82 | D50.9, D64.9 | **Presenting anchor 0.1% is knowingly overruled.** NFHS-5 **MP**: children 6–59mo **72.7%**, women 15–49 **54.7%**, men **22.5%** (IND-POP), and Anaemia Mukt Bharat makes anaemia an active PHC screening programme. This is a *screening/finding* category, not a complaint, and is retained on programme grounds | **HIGH** — severe anaemia requiring transfusion, occult GI bleeding, malignancy, haemoglobinopathy, hookworm, malaria | PHYSICIAN_REVIEW_MANDATORY |

*This is the one category where the design deliberately departs from the presenting-share ordering,
and the departure is stated rather than buried. It is also a **standing risk**: a screening category
in a reason-for-encounter label space is exactly the structural error that produced E66. Guardrail:
`pallor_anaemia` may be selected by a worker as a reason for encounter (a mother brings a pale
child); it may **never** be auto-assigned from a vitals or BMI value the way E66 was.*

### Chapter W — Pregnancy and childbearing · **5.0%** at primary (Odisha T2)

| id | displayName | ICPC-3 | ICD-10 | Anchor | Red-flag gateway | Disposition |
|---|---|---|---|---|---|---|
| `antenatal_visit` | Pregnancy check / antenatal visit | W78 | Z34.-, Z35.- | Odisha 5.0% (IND-PRESENT), and the authors note pregnancy is one of only three reasons primary facilities are actually accessed for | **HIGH** — pre-eclampsia, anaemia in pregnancy, APH, malpresentation, gestational diabetes | PHYSICIAN_REVIEW_MANDATORY (danger signs escalate to `emergency_pregnancy_danger`) |

### The terminal fallback

| id | displayName | ICPC-3 | ICD-10 | Anchor | Model | Disposition |
|---|---|---|---|---|---|---|
| `other_not_in_list` | Something else — describe it | A29 | R69 | `ASSUMED` residual ~7% of PHC encounters, being the Odisha chapters not covered above (eye 1.4, ear 1.4, genital 0.9, psychological 0.1) plus within-chapter remainder | **ALWAYS_ABSTAIN** | PHYSICIAN_REVIEW_MANDATORY |

`other_not_in_list` is the mechanism that makes "broad coverage" mean *the model knows what it does
not cover*. Selecting it opens the free-text/voice field, records the text for the OOD eval corpus
the architecture memo §6.3 says must be built from real encounters, and produces **no differential
at all**. Every Tier-2 category below is reached through it.

---

## 8. Tier 2 — named but deferred (real categories, deliberately not implemented)

These are in the taxonomy and in the epidemiology. They are **not** in the dropdown and **not** in
the label space, so the model cannot guess at them; a worker meeting one selects
`other_not_in_list` and the device abstains.

| Category | ICPC-3 | Why deferred |
|---|---|---|
| Ear pain / discharge | H01, H71 | 1.4% at primary. Needs otoscopy; no PHC cadre below physician is scoped to interpret a drum. Deferring is a scope-of-practice fact, not a data gap |
| Eye complaint / red eye / vision | F chapter | 1.4% at primary. Needs visual acuity and anterior-segment assessment; the dominant real cause (cataract) is a referral, not a PHC diagnosis |
| Toothache / dental | D19 | Measured **fully OOV and spuriously wrong** (`'Toothache' → B54 malaria 0.437`). No dental capability at a PHC — pure referral, so a category adds risk and no decision |
| Menstrual problems, white discharge | X chapter | 0.9% genital at primary, and known under-reported. Needs a privacy-protected capture and consent design that does not exist yet. **Deferring is itself an equity problem** — flagged, not accepted |
| Anxiety, depression, sleep problems | P chapter | Odisha records **0.1%**, and the authors state plainly this *"does not reflect the true burden"* (stigma + recognition gap). **The only available Indian anchor is known-wrong.** Training a category on 0.1% teaches the model that mental illness does not exist at a PHC. Deferred until a defensible anchor exists — this also removes **F41.0 Panic disorder** from the label space (§9) |
| Tingling / numbness | N06 | Needs the diabetes and leprosy branches first; diabetic neuropathy vs B12 vs leprosy is not separable at entry-point granularity |
| Child malnutrition / not growing | T05, T91 | Needs anthropometry (MUAC, weight-for-height z-scores) the app does not capture. Belongs to the rule-based IMCI/CBAC branch, not the classifier |
| Jaundice | D13 | High Indian burden (hepatitis A/E, malaria, obstruction) but needs a visual/lab capture path not yet built |
| Constipation | D12 | Low volume, low risk, low decision yield. Deferred on cost, and said so plainly |
| Localised lump or swelling | S04, A08 | Needs an examination pathway; overlaps TB lymphadenitis and malignancy |
| Fainting / brief loss of consciousness | A06 | Overlaps `emergency_unconscious`; separating recovered syncope from ongoing altered consciousness needs a branch design that does not exist |
| Male genital complaints | Y chapter | Volume too low in the anchor; privacy design not built |
| Sexual health / STI | X / Y | Stigma-driven under-reporting makes any anchor unreliable; needs the same consent design as menstrual |
| Substance use (tobacco, alcohol) | P15–P19 | Very high real burden; a screening category, and this design has already spent its one screening-category exception on anaemia |
| Social problems | Z chapter | ICPC-3 has the chapter; Odisha realised **none**. No device pathway exists to act on one |
| Immunisation / well-child visit | A98, W/A44 | A programme workflow, not a diagnostic reason for encounter. Would belong to a separate non-diagnostic flow |

**The honesty property, stated as a testable claim:** the union of Tier 0, Tier 1 and Tier 2 must
cover every ICPC-3 chapter realised in the Odisha data. It does — all 15. Nothing in the observed
Indian PHC distribution is unaccounted for; each item is either implemented, or named as deferred
with a reason.

---

## 9. Mapping the old 18 onto the new set

**No label survives in its current role.** Three carry over re-sited, eleven become downstream
diagnoses behind a gateway, two are dropped as measurement artifacts, two are deferred.

| Old | Fate | Where it goes | Why |
|---|---|---|---|
| **E66** Obesity | **DROPPED from the label space** | Becomes a *derived finding* on the vitals path only — `thresholds.classify_bmi` already emits `obese` | It is not a reason for encounter at any level. Its 41.82% is the US-BMI × WHO-Asian-cutoff artifact (audit §D.4: `abnormal_params=="bmi"` alone is 35.44% of the file, resolving 0.65 obesity). It is also the empty-input prior at **74.95%** (H-20). Removing it from the label space is what structurally retires that hazard rather than guarding it |
| **M17** Knee OA | **DEMOTED** | Downstream diagnosis under `joint_pain` | Same artifact (0.35 of the same BMI bucket → 23.16%). Also mis-sited: it is *knee* OA specifically, while the musculoskeletal chapter's real presenting mix includes back pain, which has **no class at all** today. Splits into `joint_pain` + `back_neck_pain` |
| I10 Hypertension | **CARRIES OVER, re-sited** | `known_hypertension` (K86) | Genuinely a top-2 PHC reason for encounter in the Odisha data. Stays a vitals-path output too |
| E11 Type 2 diabetes | **CARRIES OVER, re-sited** | `known_diabetes` (T90) | Real chronic-care follow-up reason |
| D50 Iron-deficiency anaemia | **CARRIES OVER, re-sited** | `pallor_anaemia` (B80/B82) | Strongest MP anchor of any category (NFHS-5) |
| A01.0 Typhoid | **MERGED** | Severe condition behind `fever` | A patient presents with fever, not with typhoid |
| A90 Dengue | **MERGED** | behind `fever` (+ `rash`, `body_ache`) | " |
| **A91** Dengue haemorrhagic | **MERGED + escalated** | behind `fever`; bleeding presentation routes to `emergency_heavy_bleeding` | F1 **0.381** on 54 rows. This is the clearest case for RF-1: the device must never imply severe dengue is absent |
| A92.0 Chikungunya | **MERGED** | behind `fever`, `joint_pain` | " |
| **B50** Falciparum malaria | **MERGED + escalated** | behind `fever`; altered consciousness routes to `emergency_unconscious` | F1 **0.400** on 21 rows |
| B54 Malaria unspecified | **MERGED** | behind `fever` | AUFI: malaria 17% of Indian AUFI |
| A15 Pulmonary TB | **MERGED, three gateways** | behind `cough`, `fever`, `weight_loss` | India 187/lakh, MP 4th-highest. Never a first-line label; always a thing the device refuses to exclude |
| A09 Gastroenteritis | **MERGED** | behind `diarrhoea` | |
| **J22** Acute LRTI | **MERGED** | behind `cough`, `breathlessness` | F1 **0.364** on **25 rows** — unusable as a label by any standard |
| N39.0 UTI | **MERGED** | behind `urinary_symptoms` | |
| G43.9 Migraine | **MERGED** | one downstream cause behind `headache` | Migraine is a diagnosis of exclusion; making it a top-level label inverts the safety logic |
| F41.0 Panic disorder | **DEFERRED** | Tier 2, psychological | The chapter's only Indian anchor is known-wrong (§8) |
| E05.9 Hyperthyroidism | **DEFERRED** | downstream cause behind `weakness_unwell`, `weight_loss` | 1.3% endocrine at primary; a specialist diagnosis, not a PHC reason for encounter |

**Counts:** 3 carry over re-sited · 11 merged as downstream diagnoses · 2 dropped/demoted as
artifacts · 2 deferred = 18. ✔

**What this costs, said plainly.** Every published accuracy figure for the current model — 0.9212
accuracy, 0.7983 macro-F1 — becomes meaningless, because it was measured on a label space that
answers a different question. That is not a regression; it is the correction. The architecture memo
§2.3 already states it: *"Any architecture measured on the current label space will report a number
that means nothing about field performance."*

---

## 10. What this does to the three artifacts, restated concretely

- **Label space**: 6 emergency (never predicted) + 26 core learned + 1 fallback (never predicted) =
  **26 predictable classes**, against 18 today, but answering the right question. Class balance is
  anchored to a real Indian PHC distribution instead of a uniform quota over vital-sign combinations
  (audit §D.1(1)).
- **Dropdown**: the same 33 entries (6 + 27), in chapter order with `fever` first. `displayName` is
  worker-facing; `id` persists. No empty state (NC-1).
- **Tree entry points**: 33 branches. Tier 0's six are rule-based IMCI and never ML. Each Tier 1
  branch's job is to add the qualifiers the audit proved decisive — for `fever`, that is pattern
  (stepladder / cyclical-with-chills / evening / continuous), duration, chills/rigors, rash,
  bleeding, and the vitals already captured. **A bare `fever` token is the 0.580 measurement;
  `fever` plus its branch is what reaches the model.**

---

## 11. Dependencies — the next STEP-1s, which are not this one

This memo defines the categories. It builds nothing. In dependency order (architecture memo §9.2):

1. **Dataset regeneration** against this label space — its own STEP-1. Must carry the §2
   distribution, measured-vs-imputed vitals flags, an encounter-date window, a seeded reproducible
   run, and per-row provenance. `canonical_dataset.csv` cannot be relabelled in place: its rows were
   *generated* to separate 18 disease labels from a synthetic 118-term pool, so it has no rows for
   `acidity_heartburn`, `back_neck_pain`, `injury`, `itching` or `other_not_in_list` at all.
2. **Questionnaire tree design** — its own STEP-1, one branch per `categoryId`, R3-gated.
3. **The normalization lexicon and controlled vocabulary** (Layer 1) — its own versioned artifact.
4. **Abstention calibration** (Layer 3) wired to `InferenceSource.UNAVAILABLE`.
5. **Docs**: writing the core set into `intended-use-statement.md` §b and the deferred set into §f
   is an operator-signed docs change, and the red-flag gateway warrants its own risk-file row
   (next free id **H-21**), PROPOSED-pending-operator, separate commit from any code.

Nothing above was started.

---

## 12. Boundaries observed

No source, dataset, model, config, questionnaire or tree file was written, edited or deleted in
either repo. The classifier repo was read only. No `.env` or credential file was opened. No commit,
no staging. No relabelling was performed, no dataset was regenerated, no tree was built, no model
was trained or evaluated.

---

## Sources

- Gupta P, Bharati B, Sahu KS, Mahapatra P, Pati S. *Self-reported symptom burden among patients attending public health care facilities in India: Looking through ICPC-3 lens.* PLOS Glob Public Health 2024;4(5):e0001835 — https://pmc.ncbi.nlm.nih.gov/articles/PMC11073677/
- *Symptoms and medical conditions in 204 912 patients visiting primary health-care practitioners in India: a 1-day point prevalence study (POSEIDON).* Lancet Glob Health 2015 — https://www.thelancet.com/journals/langlo/article/PIIS2214-109X(15)00152-7/fulltext
- Gupta A, Reddy BV, Nagar MK, Chandel A, Bali S. *Portfolio of Outpatients Attending Centre for Urban Health, Madhya Pradesh, Central India.* Health Serv Res Manag Epidemiol 2015;2:2333392815598291 — https://pmc.ncbi.nlm.nih.gov/articles/PMC5266472/
- *Acute undifferentiated fever in India: a multicentre study of aetiology and diagnostic accuracy.* BMC Infect Dis 2017;17:665 — https://bmcinfectdis.biomedcentral.com/articles/10.1186/s12879-017-2764-3
- *Metabolic non-communicable disease health report of India: the ICMR-INDIAB national cross-sectional study (ICMR-INDIAB-17).* Lancet Diabetes Endocrinol 2023 — https://www.thelancet.com/journals/landia/article/PIIS2213-8587(23)00119-5/fulltext
- NFHS-5 2019-21, Madhya Pradesh — https://ruralindiaonline.org/en/library/resource/national-family-health-survey-nfhs-5-2019-21-madhya-pradesh/
- Chopra A *et al.* *Burden of Musculoskeletal (MSK) Pain and Arthritis in India: COPCORD–BJD India Project.* Int J Rheum Dis 2025 — https://onlinelibrary.wiley.com/doi/10.1111/1756-185X.70163
- *Prevalence of skin diseases in rural Central India: a community-based, cross-sectional, observational study.* J Mahatma Gandhi Inst Med Sci 2016 — https://journals.lww.com/mgim/fulltext/2016/21020/prevalence_of_skin_diseases_in_rural_central.6.aspx
- NCVBDC (NVBDCP) — malaria, magnitude of the problem — https://ncvbdc.mohfw.gov.in/index4.php?lang=1&level=0&linkid=420&lid=3699
- India TB Report 2024 / Nikshay notifications — https://www.drishtiias.com/daily-updates/daily-news-analysis/india-tb-report-2024
- ICPC-3 browser, frozen version 1.80.00 (WONCA/WICC) — https://icpc-3.info/browser/
