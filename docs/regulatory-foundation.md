# SaMD Regulatory & SDLC Foundation

> **Status:** engineering-side orientation, not regulatory/legal advice. Actual device
> classification, risk analysis, and submissions must be formalised by a qualified Regulatory
> Affairs professional under a real Quality Management System. This document exists so that
> anyone (human or AI assistant) picking the project up knows *what we are building, under
> which rules, and what is mockup vs. production.*
>
> `agent_docs/` (gitignored, local-only) holds working/agent notes. This `docs/` directory is
> tracked and is the beginning of the **controlled documentation** an SaMD requires.

---

## 1. What this project is

**PHC Patient Care** — an Android app for rural Indian Primary Health Centres. A frontline
worker (ASHA/nurse/compounder) registers a patient, records medical background and vitals,
captures a consultation (text/voice/attachments), the case is assessed by a clinical
"kernel" — **real HTTP inference against a local FastAPI + XGBoost backend as of 2026-07**
(`/v1/assess` with a mock fallback on failure, plus `/api/v1/evaluate` for NLEM treatment
recommendation with no mock fallback) — and is handed off to a doctor (also real/interactive as of
2026-07, not mocked — see `docs/requirements/software-requirements.md` REQ-RX/EVL/RFN). The app is
**offline-first** for field devices with intermittent connectivity. **Still not a validated medical
device**: the backend model itself has no clinical validation, dataset governance, or version-gating
yet — "real HTTP call" is not the same as "clinically validated," see §3 gap #5 below.

This is intended to become a **Software as a Medical Device (SaMD)** and go to production.
The current repository is a **hardened mockup / architectural foundation** — the real app is
built on top of it, not replacing it. Treat every architectural decision as load-bearing.

## 2. Regulatory frameworks in scope

### 2.1 IEC 62304 — medical device software life cycle
The lifecycle standard we develop against. Two things it forces early:

- **Software safety classification** (Class A / B / C by worst-case harm from a software
  failure, assuming external risk controls):
  - **A** — no injury possible
  - **B** — non-serious injury possible
  - **C** — death or serious injury possible
  - *Provisional working assumption, revised 2026-08 against the CDSCO 2026 guidance's Table 2
    (see §2.3 below for the full derivation): **B or C, genuinely unresolved** — the base case
    (Serious situation × Drive-clinical-management output) is **B**, but the guidance's
    non-clinical-user note may escalate this app's situation to Critical, which would make it
    **C**. The swing factor is a single judgment call (does the async doctor-review step count
    as "support from specialized professionals" for that note's purpose), not yet decided by
    CDSCO or a qualified risk team. Plan/budget for C; do not claim B or C as settled.*
- **Required processes** (must exist as process, with records): development planning,
  requirements, architectural + detailed design, implementation & unit verification,
  integration & integration testing, system testing, release; plus **risk management**
  (ISO 14971), **configuration management**, and **problem resolution**. Higher classes
  require more rigour and documentation, not different code.

### 2.2 ISO 14971 — risk management
Risk analysis, evaluation, control, and residual-risk review across the lifecycle. Our
current risk controls already in code: **human-in-the-loop review gates** (Consultation and
Medical-background confirmation dialogs) mitigating *automation bias*; **encryption at rest**;
**insert-only audit trail** for traceability/accountability; **data minimisation**.

### 2.3 CDSCO / Medical Device Rules 2017 (India)
SaMD is a regulated medical device under MDR 2017, risk-classified **A/B/C/D** (A lowest).
CDSCO aligns with the **IMDRF SaMD** framework. Production requires a **manufacturing
license** (Class A/B via State Licensing Authority; C/D via CDSCO Central), an **ISO 13485**
QMS, and conformity to the **Essential Principles** of safety & performance.

**Live framework — CDSCO Guidance Document on Medical Device Software (Doc No.
CDSCO/MD/GD/MDSW/01/2026 — supersedes the October-2025 draft and the older MD-5/MD-9
shorthand; full text: `docs/Guidance document on Medical Device Software under MDR-2017.md`):**
SaMD is classified A–D under Rule 4 / First Schedule of MDR-2017. For **standalone** MDSW
(§7.1, Table 2), classification is a 2-axis lookup:

| Healthcare situation | Treatment/diagnosis | Drive clinical management | Inform clinical management |
|---|---|---|---|
| Critical | D | C | B |
| Serious | C | B | A |
| Non-serious | B | A | A |

**Run this app's actual intended-use statement through the table, not an assumption:**
- **Information-significance axis — "Drive clinical management," not "Inform."** §7.1(ii)
  defines "drive" as: aid in diagnosis by analyzing information to help predict risk of a
  disease/condition, and/or triage. This app's `/api/v1/evaluate` leg does exactly that —
  NLEM-mapped diagnosis + treatment recommendation, ranked differentials with confidence, that
  the doctor Agrees/Modifies/Rejects (`SubmitDoctorDecisionUseCase`). Human-in-the-loop
  determines *how the risk is controlled*, not which column applies — the column is about what
  the software's *output* does, and this output aids diagnosis/triage. It is not merely
  "inform" (aggregating background information with no next-step guidance).
- **Situation axis — likely "Serious," and the guidance's own note pushes toward "Critical."**
  §7.1(b)(ii)/(iii): PHC presentations here (fever, respiratory, GI, headache, hypertension,
  diabetes, chest pain routed to Cardiology, stroke to Neurology) span non-serious to serious;
  the emergency-override path (SpO2<90, BP thresholds) exists precisely because some cases *are*
  time-critical. Critically, the guidance's explicit note under Table 2 states: *"Standalone
  MDSW intended to be used by non-clinical users in a 'serious situation or condition' … without
  the support from specialized professionals, may be considered as a MDSW used in a 'critical
  situation or condition.'"* This app's primary users are **non-clinical** (ASHA worker, nurse,
  compounder — `UserRole` in `AuthSession`), operating in the field without a specialist present
  at the point of AI output generation (the doctor reviews later, asynchronously, not
  co-located). That is exactly the scenario the note describes.
- **Table 2 arithmetic, stated precisely (columns: Treatment/diagnosis | Drive | Inform):**
  Critical row = D, C, B. Serious row = C, B, A. Non-serious row = B, A, A. So **Serious × Drive
  = B** — the base case, before any escalation, lands on the original B assumption, not C.
- **The B→C swing depends entirely on one thing: does the non-clinical-user note escalate this
  app's situation from Serious to Critical?** If it does, **Critical × Drive = C**. If it
  doesn't, it stays **Serious × Drive = B**. This is a single, identifiable judgment call, not a
  settled fact — argued both ways:
  - **For escalation (→ C):** the note's condition is "non-clinical users … without the support
    from specialized professionals." The AI output is generated and acted on for the *initial*
    care decision by a non-clinical worker in the field, with no specialist present at that
    moment — the doctor's review happens asynchronously, later, not at the point of output.
  - **Against escalation (→ stays B):** a regulator could reasonably read "support from
    specialized professionals" as satisfied by the mandatory doctor AGREE/MODIFY/REJECT step
    itself — the worker never finalizes a prescription alone; a specialist always confirms
    before the output becomes clinical action, just not in the same room at the same moment.
  - **Working assumption for planning purposes: treat as unresolved, budget for C, hope for B.**
    Do not state C as decided in investor or regulatory conversations — state the swing factor
    and that CDSCO confirmation is pending.
- Final classification is CDSCO's to confirm (§7.1: "risk class shall be confirmed by CDSCO
  (CLA) upon review") or via the published risk-classification list/CDSCO MD Online portal query
  (§7.1) — **do this lookup/query as an actual pre-production task**, don't keep re-deriving it
  from the table.
- **2026-07 status unchanged as a fact, reframed as a risk amplifier:** the doctor-review step is
  a real interactive decision (`SubmitDoctorDecisionUseCase`, `PatientSummaryScreen`), not a
  mocked placeholder — the reviewer sees confidence/differential/reasoning and picks
  AGREE/MODIFY/REJECT for real. That strengthens the *risk-control* story (mandatory
  verification genuinely exists) but does not lower the *classification*, because classification
  is driven by intended use/output significance, not by how good the mitigating control is —
  the control is what an ISO 14971 risk file leans on to justify residual risk at whatever class
  CDSCO confirms, not a lever on the class itself.

**Licensing consequence (§11.0, Table 5):** if Class C is confirmed, **manufacturing license
moves to the Central Licensing Authority (CLA)**, not the State Licensing Authority path Class
A/B would use. Test license, import license, and clinical-investigation permission are CLA
regardless of class. This changes the Phase 4 (regulatory & launch) roadmap step from "SLA
license" to "CLA license" — material for founder/investor timeline conversations, not a detail.

**New AI-specific QMS obligations named in the 2026 guidance (§9.0), not yet reflected in
`docs/quality/qms-overview.md`:** Algorithm Change Protocol (ACP) for any post-deployment model
update; continuous performance assurance / drift monitoring in production, not just at release;
IS/ISO/IEC 42001 (AI management system) and IS/ISO/IEC 23894 (AI risk management) alongside the
already-tracked ISO 14971/IEC 62304. See `docs/quality/qms-overview.md` for the tracking rows.

### 2.3a EU MDR — Rule 11 (for CE-marking / export framing)
Under **EU MDR Annex VIII Rule 11**, software intended to provide information used to take
diagnostic or therapeutic decisions is **Class IIa** at minimum, rising to **IIb** if it could
*drive* (not merely *inform*) decisions in a serious condition without full clinical context. The
report's legal language is kept consistent with **IIa** framing — *"AI-Assisted, Physician-Verified,
not a final diagnosis until physician approval"* — since that phrasing is itself part of what keeps
the product at IIa rather than IIb in a classification rationale. This is the exact
`ReportFormatter.DISCLAIMER` / `CONSENT_STATEMENT` wording rendered in the Phase 3.5 report footer
(`presentation/report/ReportCanvasRenderer`).

### 2.4 DPDP Act 2023 — data protection
Patient health data is sensitive personal data. Obligations: lawful **consent**, **purpose
limitation**, **data minimisation**, security safeguards, breach reporting, and **data
localisation** considerations (target infra: AWS Mumbai region — see `agent_docs/hardening.md`).
Our **day-scoped local cache** and **SQLCipher-at-rest** are direct DPDP data-minimisation and
security measures.

### 2.5 ISO 13485 — QMS  &  IEC 62366 — usability engineering
ISO 13485: the quality system wrapping all development, incl. **design controls** and the
**Design History File (DHF)**. IEC 62366: usability engineering — highly relevant for
low-literacy, high-pressure PHC use. Both are **organisational/process** artifacts that must
exist **before** production code is written; they cannot be retrofitted late without rework.

## 3. Are we on the right path? (assessment)

**Sound foundations already in place**
- Clean Architecture (presentation → domain → data) → gives the separability, testability and
  traceability IEC 62304 verification depends on.
- Mockable boundaries (`VitalsSource`, `TranscriptionService`, `SyncStatus`, mocked kernel)
  keep *real vs. simulated* honest — critical so a demo is never mistaken for a validated device.
- Hardening primitives are the right early bets: **SQLCipher** at rest (DPDP security),
  **insert-only audit log** (traceability), **day-scoped cache** (data minimisation),
  **review-before-submit gates** (ISO 14971 automation-bias control).

**Gaps that must close before production (and mostly before more code)**
1. **QMS + DHF (ISO 13485)** and a **risk management file (ISO 14971)** must be *established
   now*. Retrofitting compliance documentation after the code exists is the classic SaMD
   failure mode. **Flag to the founder as a pre-production blocker** — this is org/process
   work, not a coding task.
2. **Software safety classification** must be formally decided (drives everything else).
3. **Requirements + traceability** (requirement → design → code → test) — not yet present.
4. **Verification/test coverage** is currently near-zero (only throwaway checks). IEC 62304
   B/C require unit + integration + system testing with records. CI must run them.
5. **The clinical kernel** (AI/ML decision component) is now real HTTP inference (2026-07, both
   `/v1/assess` and `/api/v1/evaluate`, plus a Gemini API call for brand lookup) but still has none
   of its own validation, dataset governance, or **versioning** (the deferred `ai_kernel_version`
   field) — and may attract additional AI/ML-SaMD regulatory treatment. "Real inference" must not be
   mistaken for "validated" in any investor-facing or regulatory conversation; keep the report's
   "AI-Assisted, Physician-Verified" framing and the mandatory doctor-review step (REQ-RX/RFN)
   front and center until formal validation exists.
6. **Real authentication + RBAC** — today `userId` is a placeholder (`phc_field_worker`). Audit
   accountability and access control depend on real identity.
7. **Data-model deviation:** `spec.md` says `Patient.id` is a 10–12 char alphanumeric UID, but
   the code generates 36-char UUIDs. Reconcile the spec and the code (pick one, update the
   other) — small now, painful after data exists.
8. **Backend + sync + localisation** — none exist yet; see `docs/sync-design.md` for the
   deferred design.
9. ~~**Off-the-shelf/third-party (SOUP) component validation + SBOM.**~~ Closed — see
   `docs/sbom/README.md`. A CycloneDX SBOM is generated from the resolved Gradle dependency
   graph (`./gradlew :app:cyclonedxBom`) and committed under `docs/sbom/`. **Stays closed only
   if a new dated SBOM is actually generated and committed at every tagged release** — check
   this convention is followed, don't assume it from a past commit.

**Verdict:** the architecture is a correct and healthy foundation, and the early hardening
choices map cleanly onto real regulatory requirements. The main risk is *process lag* — the
QMS/DHF/risk-management wrapper needs to start in parallel now, and the mockup must never be
represented as a validated medical device.

## 4. Mockup vs. production — current boundaries

| Area | Now (mockup) | Production |
|------|--------------|-----------|
| Kernel / clinical assessment | Real HTTP inference (2026-07) behind a mockable interface — no clinical validation/versioning yet | Validated, versioned AI/ML SaMD component |
| Brand-name lookup | Real Gemini API call, best-effort, never blocks the pipeline | Same, or an India-hosted/on-device alternative if Gemini's data-processing terms don't clear production review |
| Vitals device | `MockVitalsSource` (random) | Real device integration behind same interface |
| Transcription | Real (Android `SpeechRecognizer`) | Same, possibly server ASR |
| Sync | `MockSyncStatus` (UI only, no transport) | WorkManager + backend, see `docs/sync-design.md` |
| Backend | None | AWS Mumbai (DPDP), managed Postgres, S3, auth |
| Auth / RBAC | Placeholder single user | Real identity (e.g. Cognito) + role-based views |
| Encryption at rest | SQLCipher + Keystore ✅ real | Same |
| Audit trail | Insert-only Room table ✅ real | Same, + tamper-evidence/export |

## 5. Roadmap

- **Phase 0 — Foundation (current):** investor demo, Clean Architecture, hardening primitives,
  field-facing UX (roster, sync status, patient-context banner, review gates).
- **Phase 1 — Pre-production process (start NOW, in parallel):** ISO 13485 QMS + DHF; software
  safety classification; ISO 14971 risk file; requirements + traceability; CDSCO regulatory
  strategy; DPDP compliance & consent design.
- **Phase 2 — Build real:** backend on AWS Mumbai (localisation); real auth + RBAC; real sync
  engine (`docs/sync-design.md`); real kernel + its validation pipeline; test coverage + CI.
- **Phase 3 — V&V:** system testing per IEC 62304; IEC 62366 usability; cybersecurity;
  clinical validation of the kernel.
- **Phase 4 — Regulatory & launch:** CDSCO licensing; controlled release; post-market
  surveillance + problem resolution loop.

## 6. Orientation for a fresh session

- **Read first:** `PROGRESS.md` (tracked), then `agent_docs/CLAUDE.md`, `agent_docs/spec.md`,
  `agent_docs/hardening.md` (local-only working notes), then this file + `docs/sync-design.md`.
- **Stack & conventions:** Kotlin + Compose (M3), MVVM + Clean Architecture, Hilt, Room 2 +
  SQLCipher, Coroutines/Flow. Versions pinned in `libs.versions.toml`. No real network/AWS yet.
- **Keep mocked, do not build real yet:** `VitalsSource`, sync transport, backend, auth. **The
  kernel is no longer mocked** (2026-07: real HTTP inference, `/v1/assess` + `/api/v1/evaluate` +
  Gemini brand lookup) — it still has no clinical validation, which is a different gap than "not
  built." Build the remaining mocked items only when their phase arrives.
- **Working conventions honoured in this repo's history:** one focused commit per unit of work;
  verify changes on a real emulator (not just compile); flag deprecated deps and spec gaps
  rather than silently proceeding; never sweep the user's unrelated uncommitted edits into a
  feature commit.
