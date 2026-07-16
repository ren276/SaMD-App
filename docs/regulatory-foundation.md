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
"kernel" (currently mocked), and is handed off to a doctor. The app is **offline-first** for
field devices with intermittent connectivity.

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
  - *Provisional working assumption: **Class B**, on the strength of mandatory
    human-in-the-loop review before any hand-off (a doctor confirms; the kernel does not act
    autonomously). If the kernel's output can drive an unreviewed clinical action, it becomes
    **Class C**. This must be decided by formal risk analysis, not assumed.*
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

**Live framework — CDSCO draft Medical Device Software guidance (October 2025):** supersedes the
older MD-5/MD-9 shorthand. SaMD is classified A–D by (1) the significance of the information the
software provides and (2) the severity of the healthcare situation. Class C/D get Central Licensing
Authority review; A/B go through State Licensing Authorities, with Class A non-measuring/non-sterile
software exempt from full licensing (self-registration via **SUGAM**). **Classification argument
for this product:** a human-in-the-loop clinical decision-support tool — the doctor reviews and can
**Agree / Modify / Reject** the AI output (Phase 5), never autonomous — plausibly targets **Class B
or C rather than D**, precisely because of the mandatory physician-verification step. The liability
checkbox and "Doctor Validation" flow are doing real classification work, not just UX; worth stating
explicitly in investor conversations and in the report's legal footer.

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
5. **The clinical kernel** (AI/ML decision component) will need its own validation, dataset
   governance, and **versioning** (the deferred `ai_kernel_version` field) — and may attract
   additional AI/ML-SaMD regulatory treatment. Keep it mocked and clearly bounded until then.
6. **Real authentication + RBAC** — today `userId` is a placeholder (`phc_field_worker`). Audit
   accountability and access control depend on real identity.
7. **Data-model deviation:** `spec.md` says `Patient.id` is a 10–12 char alphanumeric UID, but
   the code generates 36-char UUIDs. Reconcile the spec and the code (pick one, update the
   other) — small now, painful after data exists.
8. **Backend + sync + localisation** — none exist yet; see `docs/sync-design.md` for the
   deferred design.

**Verdict:** the architecture is a correct and healthy foundation, and the early hardening
choices map cleanly onto real regulatory requirements. The main risk is *process lag* — the
QMS/DHF/risk-management wrapper needs to start in parallel now, and the mockup must never be
represented as a validated medical device.

## 4. Mockup vs. production — current boundaries

| Area | Now (mockup) | Production |
|------|--------------|-----------|
| Kernel / clinical assessment | Mocked behind use case | Validated, versioned AI/ML SaMD component |
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
- **Keep mocked, do not build real yet:** the kernel, `VitalsSource`, sync transport, backend,
  auth. Build them only when their phase arrives.
- **Working conventions honoured in this repo's history:** one focused commit per unit of work;
  verify changes on a real emulator (not just compile); flag deprecated deps and spec gaps
  rather than silently proceeding; never sweep the user's unrelated uncommitted edits into a
  feature commit.
