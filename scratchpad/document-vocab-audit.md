# Document-naming controlled-vocabulary audit (read-only, Build 3 prep)

**Status:** READ-ONLY AUDIT. No code touched, no files mutated outside this note.
`.env`/`local.properties`/credentials were never read. Repos searched: `SaMDApp` (this
monorepo), `SaMDClassifier` (sibling, `/media/sandesh/extra-ssd/AndroidWork/SaMDClassifier`),
`dataset-make` (`/media/sandesh/extra-ssd/dataset/dataset-make`, Synthea-based synthetic
dataset generator).

**What this note is not.** This is a sourced candidate list, not a decision. Every code below
is either grounded in a real repo value (marked REPO-DERIVED) or explicitly not
(OPERATOR-MUST-DEFINE). The clinical sign-off on the actual vocabulary belongs to the
operator, per the brief.

---

## A. DepartmentCode candidates

### Strongest source: the union of doctor-routing + seeded doctor specialties

Two repo sources define specialty strings, and **they disagree** (see Conflict section below).
Both are unconstrained free-text `String` fields, not an enum — `Doctor.specialty`
(`app/src/main/java/com/example/samdapp/domain/model/Doctor.kt:6`) and backend
`doctors.specialty` (`backend/core/app/models/doctor.py:29`) have no type-level or
CHECK-constraint enforcement. So even the "solid" source here is convention, not schema.

| Code (proposed) | Label | Source |
|---|---|---|
| `CRIT_CARE` | Critical Care | `mapConditionToSpecialty`, `ResolveDoctorAssignmentUseCase.kt:80-137`; also seeded (`MIGRATION_11_12`) |
| `CARDIO` | Cardiology | same routing function; seeded `MIGRATION_11_12` |
| `NEURO` | Neurology | same routing function; seeded `MIGRATION_11_12` |
| `PULMO` | Pulmonology | same routing function; seeded `MIGRATION_11_12` |
| `ORTHO` | Orthopedics | same routing function; seeded `MIGRATION_6_7` |
| `GASTRO` | Gastroenterology | same routing function; seeded `MIGRATION_11_12` |
| `URO` | Urology | same routing function; seeded `MIGRATION_11_12` |
| `ENDO` | Endocrinology | same routing function; seeded `MIGRATION_11_12` |
| `PSYCH` | Psychiatry | same routing function; seeded `MIGRATION_6_7` |
| `GYNE` | Gynecology | same routing function; seeded `MIGRATION_6_7` (repo spells it "Gynecology", not "Obstetrics and Gynecology" — kept verbatim) |
| `DERM` | Dermatology | same routing function; seeded `MIGRATION_6_7` |
| `ENT` | ENT | same routing function; seeded `MIGRATION_6_7` (repo already uses this exact 3-letter form) |
| `OPHTHAL` | Ophthalmology | same routing function; seeded `MIGRATION_6_7` |
| `INT_MED` | Internal Medicine | same routing function; seeded `MIGRATION_11_12` |
| `GEN_PHYS` | General Physician | same routing function; seeded `MIGRATION_6_7` (x2 doctors) |
| `PEDS` | Pediatrics | **seeded only** (`MIGRATION_6_7`) — no routing keyword ever assigns a case here; see Conflict |
| `INFECT_DIS` | Infectious Disease | **seeded only** (`MIGRATION_11_12`) — no routing keyword ever assigns a case here; see Conflict |

All 17 marked **REPO-DERIVED**. The codes themselves (`CRIT_CARE`, `CARDIO`, ...) are my
abbreviation of the repo's exact free-text values — the repo does not define short codes,
only the full-text specialty strings. The provenance is the string, not the abbreviation.

### Alternate source, different shape: ICD chapter grouping (SaMDClassifier)

`SaMDClassifier/dataset/canonical_dataset.csv`'s `icd_chapter` column, tied to the same 18
trained ICD classes SaMDApp uses (`TRAINED_ICD_CANDIDATES`,
`app/src/main/java/com/example/samdapp/domain/model/TrainedIcdCandidate.kt:13-30` — confirmed
byte-identical to `SaMDClassifier/models/symptom_model_meta.json`'s `labels` array). 11 unique
values: *Certain infectious and parasitic diseases, Circulatory system, Diseases of the blood,
Diseases of the genitourinary system, Diseases of the musculoskeletal system, Diseases of the
nervous system, Endocrine/metabolic, General/nonspecific, Mental and behavioural disorders,
Nutritional/metabolic, Respiratory*.

**This is REPO-DERIVED but a different taxonomy shape than the specialty list above** — it
groups by WHO ICD-10 diagnosis chapter, not by clinical service line / doctor specialty. It
does not map cleanly 1:1 onto the specialty list (e.g. "Circulatory system" roughly implies
Cardiology, but "General/nonspecific" and "Nutritional/metabolic" have no clean specialty
counterpart). Flagging this as a real alternative, not folding it into the table above, because
picking between "department = who the document is routed to" (specialty list) vs "department =
what diagnosis category the document concerns" (ICD-chapter list) is a real modeling decision
the operator should make explicitly, not one this audit should silently resolve.

### Zero hits (searched, found nothing)

- No `Specialty` or `Department` enum anywhere in either repo (Kotlin or Python).
- No NLEM therapeutic-category grouping anywhere (grepped `nlem`/`NLEM` in both repos — one
  incidental non-taxonomy hit in `backend/core/app/api/v1/kernel.py`).
- No consultation-type / encounter-type enum (`CaseRecord` only has `CaseStatus`, a workflow
  state, not a type).
- Backend `Facility` (`models/facility.py`) is location-only (`id`, `name`, `district`,
  `state`, `hfr_id`) — no department field.
- `dataset-make`'s `synthea_india_run.properties` is exporter config only (CSV output,
  care-pathway/billing modules explicitly suppressed); no `modules/` directory, no
  specialty/module list found.

---

## B. RecordTypeCode candidates

**Honest headline: there is no authoritative source anywhere in either repo for a clinical
document-category vocabulary** (lab report / discharge summary / prescription / referral
letter / imaging report / vaccination record, etc.). I am not proposing a starter list of
those, because doing so would be exactly the fabrication the brief warned against — a plausible
clinical vocabulary invented and presented as grounded. It isn't; there is nothing to ground it
in.

What exists is two **adjacent but wrong-shaped** taxonomies:

| Code (proposed) | Label | Source | Caveat |
|---|---|---|---|
| `IMAGE` | Image | `AttachmentType` enum, `app/src/main/java/com/example/samdapp/domain/model/Attachment.kt:5`; mirrored `backend/core/app/models/enums.py:170-174` with a DB CHECK constraint | This is a **capture-medium** taxonomy (how the file was captured), not a document-content-category taxonomy (what kind of record it is). A lab report photographed on a phone and a discharge summary photographed on a phone are both `IMAGE` here — the enum can't distinguish them. |
| `VIDEO` | Video | same `AttachmentType` | same caveat |
| `AUDIO` | Audio | same `AttachmentType` | same caveat |
| `AFFECTED_AREA_PHOTO` | Affected area photo | same `AttachmentType` | same caveat, and this specific value is Consultation-attachment-specific (a photo of a symptom site), not a document category at all |

Marked **REPO-DERIVED but wrong-shaped** — usable only if RecordTypeCode is meant to answer
"how was this captured," not "what kind of clinical record is this." If the latter (the more
likely intent, given `<RecordTypeCode>` sits next to `<DepartmentCode>` in a clinical filename),
these do not answer the question.

One more weak, indirect source: `MedicalHistoryCategory`
(`app/src/main/java/com/example/samdapp/domain/model/MedicalHistoryItem.kt:5`, mirrored
`backend/core/app/models/enums.py:177-180`): `CHRONIC_CONDITION`, `SURGERY`,
`HOSPITALIZATION`. This is a worker-typed medical-history-entry category, not a document-upload
category — adjacent domain, not the same thing. Listed for completeness, not recommended as a
RecordTypeCode source.

**Verdict: RecordTypeCode is entirely OPERATOR-MUST-DEFINE.** No repo evidence exists for what
kinds of documents a PHC worker will actually upload (lab reports? old prescriptions from other
facilities? discharge summaries? referral letters? vaccination cards? something else specific
to the rural-PHC setting this app targets?). This is a real clinical/operational question, not
a naming-convention detail, and answering it by guessing plausible-sounding categories would be
worse than leaving it blank for operator input.

### Zero hits (searched, found nothing)

- No `type`/`category` field on `KernelReportOutput`, `EvaluateReportOutput`, `ClinicalReport`,
  `Prescription`, or `DiagnosisFeedback`.
- No document/attachment table in the backend beyond `attachments` itself (same shape as
  `AttachmentType` above, same capture-medium caveat).

---

## C. Taxonomy conflict found

**`mapConditionToSpecialty`'s 15-value routing target list disagrees with the 17-value seeded
`doctors.specialty` set.** `Pediatrics` and `Infectious Disease` doctors exist in the seed data
(`MIGRATION_6_7`, `MIGRATION_11_12`) but no symptom keyword in
`ResolveDoctorAssignmentUseCase.kt` ever routes a case to them — they're only reachable via the
"no specialty match → full active pool" fallback path. Any DepartmentCode vocabulary built from
only one of these two sources will silently omit the two values present in the other. The table
in section A already uses the union (17) and flags the two orphaned ones individually.

No other conflicts found. The 18-class ICD list is byte-identical between SaMDApp and
SaMDClassifier — no drift there.

---

## D. The no-ABHA fallback for the UHID slot (added per operator request)

**Confirmed: `Patient.id` should be the UHID, not a fallback for a missing ABHA number — it is
already, uniformly, what the rest of the app treats as the patient identifier.**

Evidence:

- `Patient.id` is a **12-character alphanumeric UID**, generated unconditionally at
  registration (`RegisterPatientUseCase.kt:26-27`, `generatePatientId()`, per
  `agent_docs/spec.md`'s 10-12 char UID spec). Every registered patient has one — registration
  only requires `fullName` plus (phone number OR address); `abhaNumber` is not required
  (`RegisterPatientUseCase.kt:38`, `abhaNumber: String? = null`).
- `Patient.abhaNumber` is nullable (`Patient.kt:18`) and ABHA enrollment is an entirely separate,
  optional, later flow (the ABDM M1 adapter / ABHA creation work referenced elsewhere in this
  repo's history) — a patient can be registered and seen for months before ABHA linkage happens,
  if it ever does.
- The existing report pipeline **already made this exact choice**, uniformly, with no ABHA
  fallback logic anywhere: `ClinicalReport.header.patientUid = patient.id`
  (`ReportFormatter.kt:90`), and that `patientUid` is what gets drawn as the barcode and
  human-readable ID on every printed/exported report (`ReportCanvasRenderer`'s `headerBlock`,
  `drawBarcode(c, report.header.patientUid, ...)`). ABHA number is never used as a display,
  barcode, or filename identifier anywhere in the current codebase — it only appears
  separately, masked, as its own field (`patient.abhaNumber?.let(::maskAbhaId)` →
  `abhaNumberFormatted`).

So this isn't really a fallback design question — it's recognizing that the app has one
UID concept already (`Patient.id`), it's always present, and ABHA has never been used as the
thing that goes in a UID slot. Making documents use `Patient.id` for `<UHID>` is consistent
with the report/barcode precedent, not a new decision.

**Recommendation: use `Patient.id` as `<UHID>` unconditionally (not conditionally on ABHA
absence).** Restricting document upload to ABHA-linked patients only would be new, more
restrictive behavior than anything else in the app currently enforces, and would block the
same field-tool use case the report/barcode design already chose not to block on.

---

## Summary of what's solid vs what's a gap

- **DepartmentCode: 17 REPO-DERIVED candidates** (specialty union), with one flagged internal
  conflict (2 of the 17 are seeded but unroutable) and one alternate 11-value REPO-DERIVED
  taxonomy (ICD chapter) that answers a different question ("what diagnosis category" vs "which
  service line") — operator should pick which shape `DepartmentCode` is actually meant to
  answer before either list is finalized.
- **RecordTypeCode: 0 genuinely-fitting REPO-DERIVED candidates.** 4 wrong-shaped candidates
  exist (`AttachmentType`, capture-medium not document-category) and are listed with that
  caveat; no starter clinical-document-category list is proposed, deliberately, per the honesty
  requirement.
- **UHID fallback: resolved by existing precedent**, not a gap — `Patient.id`, always, no ABHA
  dependency.
