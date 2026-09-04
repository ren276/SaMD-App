# PHC workforce scope of practice

> **Status:** clinical-domain ground-truth reference, not a controlled quality document. This
> file is deliberately separate from `docs/quality/` (risk management file, intended-use
> statement, DHF, SOUP validation record) and carries no operator sign-off requirement of its
> own. It exists so the app's role model and any document-visibility gate are designed against
> the real legal scope-of-practice of the Indian PHC workforce, instead of being re-derived ad
> hoc or treating "worker" as one flat category. Where the risk file needs a binding decision
> that follows from the facts recorded here, that decision is a separate, operator-signed entry
> in `docs/quality/risk-management-file.md`; this document only supplies the clinical basis for
> it.

## Core finding

Non-physician PHC workers are not a flat "worker" category. The legal scope-of-practice line
runs through the middle of them: some cadres carry a defined, though limited, clinical decision
authority, some are licensed clinical staff without diagnostic-interpretation scope, and some are
a community-outreach tier that follows protocol and carries no independent diagnostic authority
at all. A binary "doctor sees raw data, worker sees an abstracted summary" gate is too coarse for
this structure. There are at least three tiers by clinical authority, summarised below and
detailed in the sections that follow.

| Tier | Cadre | Typical education / certification | Registration | Scope in one line |
|---|---|---|---|---|
| 1 | Physician (Medical Officer) | MBBS or higher | Medical Council | Full clinical decision authority |
| 1 | Community Health Officer (CHO) / Mid-Level Health Provider (MLHP) | B.Sc Community Health, B.Sc Nursing, Post-Basic B.Sc Nursing, or AYUSH practitioner background, plus the 6-month Certificate Program in Community Health (CPCH) | CPCH certification; Schedule K inclusion for medicine supply | Defined-scope mid-level practitioner: runs primary OPD, screens, manages chronic disease, triages, refers |
| 2 | Staff Nurse (Nurse Midwife) | 3.5-year GNM diploma or 4-year B.Sc Nursing | State Nursing Council | Emergency triage, minor procedures, deliveries, maternal wards |
| 2 | Pharmacist | 2-year D.Pharm or 4-year B.Pharm | State Pharmacy Council | Dispensing, dosage cross-check, cold-chain |
| 2 | Lab Technician | 2-year DMLT or B.Sc/BMLT | — | Runs analyzers and screening tests within protocol; not a diagnostic-authority role |
| 3 | ANM / MPHW (Female) | 10+2 plus 2-year ANM certificate (Indian Nursing Council recognized) | Indian Nursing Council | Village outreach, immunization, pregnancy tracking |
| 3 | MPHW (Male) | 10+2 (Biology/Science) plus 1-2 year Multipurpose Health Work or Sanitary Inspector diploma | — | Sanitation drives, vector-borne outbreak mapping, school health programs |
| 3 | ASHA (Accredited Social Health Activist) | ~30-module community course, not a clinical degree | — | Community volunteer: health education, service facilitation, protocol-driven tasks (for example malaria rapid-test-and-treat under WHO-aligned protocol); no independent diagnostic judgment |

## Tier 1: clinical decision authority

**Physician (Medical Officer).** Full clinical decision authority, the reference point the other
tiers are scoped against.

**Community Health Officer (CHO) / Mid-Level Health Provider (MLHP).** Entry is B.Sc in
Community Health, B.Sc Nursing, or Post-Basic B.Sc Nursing, or an AYUSH practitioner background;
in every case the CHO must have cleared the 6-month Certificate Program in Community Health
(CPCH), the credential the Ayushman Bharat Health and Wellness Centre program defines the cadre
around (per IPHS 2022 / commonly documented). Legally this is a defined-scope mid-level
practitioner: permitted to provide healthcare in fewer situations than a physician but more than
other health professionals in the facility. The CHO runs the primary clinical workflow at the
HWC-PHC, performs screening, manages chronic disease such as diabetes and hypertension, and
triages and refers. CHOs are included in Schedule K of the Drugs and Cosmetics Rules 1945, which
gives them a defined, prescribing-adjacent authority to supply certain medicines (per IPHS 2022 /
commonly documented; the CPCH cadre definition and the Schedule K inclusion are both widely
documented secondary claims about the HWC-PHC program rather than a primary legal citation
verified in this pass).

The scope edge worth naming explicitly: a CHO reviewing a lab report is within their trained
competency. A CHO interpreting a raw radiology image and acting on that reading unsupported is at
the edge of, or past, their defined scope.

## Tier 2: licensed clinical staff, scope-limited raw access

These cadres hold a real professional license and real clinical authority within their lane, but
that lane is not diagnostic interpretation of imaging or complex lab output.

**Staff Nurse (Nurse Midwife).** 3.5-year Diploma in General Nursing and Midwifery (GNM) or a
4-year B.Sc Nursing degree, with an active State Nursing Council registration. Handles emergency
triage, minor procedures, safe deliveries, and maternal wards.

**Pharmacist.** 2-year Diploma in Pharmacy (D.Pharm) or 4-year Bachelor of Pharmacy (B.Pharm),
registered under the State Pharmacy Council. Dispenses medication, cross-checks dosages, and
manages cold-chain preservation of essential drugs.

**Lab Technician.** 2-year Diploma in Medical Laboratory Technology (DMLT) or a B.Sc in Medical
Laboratory Technology (BMLT). Operates blood analyzers, runs malaria and TB screening tests, and
manages biological waste disposal. Generates results within protocol; this is not a
diagnostic-authority role, and reading a generated result is different from interpreting a raw
image.

## Tier 3: community tier, community-health scope

These cadres carry real and important responsibility in the field, but their training is in
outreach, protocol-driven task delivery, and community mobilization, not clinical diagnosis.

**ANM (Auxiliary Nurse Midwife) / MPHW-Female.** 10+2 (Science or Arts) followed by a 2-year ANM
Certificate Course recognized by the Indian Nursing Council. Conducts village outreach,
administers routine childhood immunization, and tracks pregnant women in the community.

**MPHW-Male.** 10+2 (with Biology/Science) followed by a 1 to 2-year Diploma in Multipurpose
Health Work or a Sanitary Inspector course. Runs environmental sanitation drives, maps
vector-borne outbreaks such as dengue or malaria, and conducts health programs in village
schools.

**ASHA (Accredited Social Health Activist).** A community volunteer health activist and
promoter, not a clinician. Certification is a roughly 30-module course, not a clinical degree.
Core work is health education, facilitating community access to services, and maternal-health
mobilization, plus specific protocol-driven tasks such as malaria rapid-test-and-treat performed
under strict WHO-aligned protocols. An ASHA adheres to protocol; she does not exercise
independent diagnostic judgment, and nothing in her training or certification is meant to confer
that judgment.

## Why this matters for the app

This tier structure is not academic. It is the clinical basis for two live design decisions in
this app: the `UserRole` role model (currently `ASHA_WORKER, NURSE, COMPOUNDER, DOCTOR`, a flat
enum with no scope-of-practice structure behind it) and any future document-visibility gate over
interpretive clinical content such as imaging or lab reports.

CDSCO device classification escalates with the risk that a software output drives an unqualified
user to a wrong clinical action. Two failure directions follow directly from the tier structure
above:

- If a community-tier worker (ASHA or ANM/MPHW) can open a raw imaging study or radiology report
  and act on their own reading of it, the software has enabled clinical interpretation outside
  that worker's scope of practice — a Class-C-shaped hazard, not a Class-B one.
- If interpretive documents are gated so that only cadres with the training to read them can open
  them, the software supports in-scope practice at every tier and the classification stays where
  the rest of the design assumes it sits.

The document-visibility role gate is therefore the specific control that holds this classification
boundary in place, and it has to key off the cadre's actual scope of practice, not a coarse
doctor/not-doctor flag. A flat gate fails in one of two ways: either it exposes a community-tier
worker to raw radiology she has no training to interpret (the Class C direction above), or it
blocks a CHO from a lab report she is trained and legally positioned to read (clinically
obstructive, and a usability problem the field staff will route around).

**Current design lean, not a decision.** The working assumption is that imaging and lab reports
default to physician/CHO-visible, and that the community tier (ANM/MPHW/ASHA) gets an abstracted
or referral-oriented view of interpretive content rather than the raw artifact. This is a lean
recorded here for design continuity, not a binding decision. The binding decision, if and when
this gate is built, belongs in `docs/quality/risk-management-file.md` as a separate,
operator-signed entry; this document supplies the clinical facts that entry would reason from, not
the entry itself.

**Status update (Build 3c, 2026-09-04):** the document-visibility gate this section anticipates is
now built (`CadreTier`, `DocumentAccessAuthorizer`, H-18 in the risk file), but only against the
four roles the app actually has today — `DOCTOR → PHYSICIAN`, `NURSE`/`COMPOUNDER →
LICENSED_CLINICAL`, `ASHA_WORKER → COMMUNITY`. CHO is still not a `UserRole` value, so the tier-1
CHO nuance this document describes (full for lab reports, scope-advisory for raw imaging) is not
yet buildable or testable; a clearly-commented insertion point exists in `UserRole.toCadreTier()`
for the day CHO is added. The shipped gate is also tier-uniform (no per-`RecordTypeCode` split)
rather than the lab-report/imaging distinction this section's lean describes — a deliberate
narrower first cut, recorded as an open item on H-18, not a rejection of the distinction.

## Regulatory framing

This tier structure sits inside the app's existing IPHS 2022 / HWC-PHC operating context and the
CDSCO / Medical Device Rules 2017 SaMD classification work already tracked in
`docs/regulatory-foundation.md`. That document carries the current, genuinely unresolved Class
B/C classification discussion for the app as a whole; this file does not restate or attempt to
resolve that discussion. The point of recording the workforce tiers here is narrower: whatever the
app's overall class lands on, any role-gated feature inside it needs to reason about cadre scope
of practice at the resolution described above, not at the resolution of a single flat `UserRole`
enum.
