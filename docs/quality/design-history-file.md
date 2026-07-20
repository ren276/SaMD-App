# Design History File (DHF) — index (scaffold)

> **Scaffold.** ISO 13485 §7.3 requires a DHF: the compiled record of the design & development
> of the device. This is the *index* pointing at each record. Records marked **TODO** don't
> exist yet. To be maintained by QA/RA.

| Design record | Location | Status |
|---------------|----------|--------|
| Regulatory strategy & classification | `docs/regulatory-foundation.md` | started (provisional Class B) |
| Software safety classification (formal) | TODO | **open** — must be decided by risk analysis |
| User needs / intended use | TODO | open — draft from `docs/regulatory-foundation.md` §1 |
| Software requirements (SRS) | `docs/requirements/software-requirements.md` | started |
| Requirements traceability matrix | `docs/requirements/traceability-matrix.md` | started |
| Architecture description | code (`presentation/` → `domain/` → `data/`); formal doc TODO | partial |
| Detailed design | code + KDoc; formal doc TODO | partial |
| Risk management file | `docs/quality/risk-management-file.md` | started |
| Verification & validation records | TODO (see roadmap #4: tests + CI) | **open** |
| Usability engineering file (IEC 62366) | TODO | open |
| Cybersecurity / threat model | partial (SQLCipher, Keystore); doc TODO | partial |
| Release records | TODO | open |
| Change history | git commit history of this repository | available |

## Notes
- The clean layered architecture and pinned dependency versions give the DHF a real
  configuration baseline today (git history + `libs.versions.toml`).
- The biggest open DHF gaps are **V&V records** (blocker #4) and the **formal safety
  classification** (blocker #2) — both gate a credible submission.

## Change log

| Date | Change | Files affected | Rationale |
|---|---|---|---|
| 2026-07-20 | **UI terminology: "Chief complaint" → "Main concern"** across all presentation-layer screens and report narrative strings. Internal code/DB field names (`chiefComplaint`) intentionally unchanged — no schema migration required. | `ConsultationScreen`, `CompounderScreen`, `DoctorListScreen`, `ConsultationChainScreen`, `PatientSummaryScreen`, `ReportFormatter`, `report-field-mapping.md` | Patient-facing language should be warm and accessible. "Chief complaint" is medical jargon; "Main concern" is plain English and more humane. |
| 2026-07-20 | **Investor demo auto-fill** — new `data/mock/DemoPatientProfile.kt` holds a clinically consistent patient persona (Priya Sharma, 34F, Shivpuri MP). Each screen gains a `fillDemoData()` action wired to a "👤 Fill demo patient data" button that pre-populates every field in one tap. Data is isolated to the demo path and never touches production storage. | `DemoPatientProfile.kt` (new), `RegisterViewModel`, `RegisterScreen`, `MedicalBackgroundViewModel`, `MedicalBackgroundScreen`, `CompounderViewModel`, `CompounderScreen`, `ConsultationViewModel`, `ConsultationScreen` | Eliminate manual data entry during investor presentations; all fields populated with realistic rural-India clinical data. |
