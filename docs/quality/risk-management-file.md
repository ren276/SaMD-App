# Risk Management File — ISO 14971 (initial)

> **Initial risk file, not a completed analysis.** Seeded with hazards evident from the current
> design and the risk controls already implemented, so the team has a real starting register.
> Severity/probability estimates are **provisional placeholders** for a qualified clinical +
> risk team to review. To be maintained across the lifecycle (ISO 14971).

## 1. Scope & policy
Device: **PHC Patient Care** SaMD. Intended use: frontline capture of patient
registration/vitals/consultation at rural PHCs, with a (currently mocked) clinical kernel and
**mandatory doctor review** before any clinical action. Provisional software safety class:
**B** (see `docs/regulatory-foundation.md` §2.1 — becomes **C** if kernel output can drive an
unreviewed action). Risk acceptability policy, severity/probability scales, and residual-risk
sign-off: **TODO** by the risk team.

## 2. Hazard register (initial)

| ID | Hazard / hazardous situation | Potential harm | Sev* | Prob* | Risk controls implemented | Residual / open work |
|----|------------------------------|----------------|------|-------|---------------------------|----------------------|
| H-01 | Wrong/mistyped vitals recorded | Clinician acts on wrong data | High | Med | Digit/decimal input filters (`NumericInputFilters`); review-before-send dialog; doctor review | Add range/plausibility validation; unit tests |
| H-02 | Mis-triage / automation bias on kernel output | Delayed or wrong care | High | Med | **Human-in-the-loop**: kernel not autonomous; review gates; doctor confirms | Kernel validation + versioning (deferred); confidence/explainability UI |
| H-03 | Wrong-patient record mix-up | Wrong treatment | High | Low | Persistent patient **name + ID banner**; patient context derived from nav back stack | Reconcile `Patient.id` spec gap (UUID vs 10–12 char) |
| H-04 | PHI breach on lost/stolen device | Privacy harm (DPDP) | High | Med | **SQLCipher** at rest + non-exportable **Keystore** key; **day-scoped cache** (minimisation) | Formal threat model; screen-lock/session policy; secure backend later |
| H-05 | Offline data loss before sync | Lost clinical record | Med | Med | Durable local Room writes (local ACID) | Real outbox/dirty-flag sync (`docs/sync-design.md`); currently sync is mocked |
| H-06 | Unauthorised access / no real auth | Wrong actor, accountability gap | Med | High | Audit log records actions | **Open**: real authentication + RBAC (placeholder `userId` today) |
| H-07 | Tampered or lost audit trail | Loss of accountability/traceability | Med | Low | **Insert-only** audit DAO (no update/delete at interface level) | Tamper-evidence (hash chain) + export for review |
| H-08 | Hurried / incomplete data entry | Incomplete clinical picture | Med | Med | **Review-before-submit** dialogs on Consultation & Medical background | Required-field enforcement per clinical rules |
| H-09 | Kernel unavailable/mocked mistaken for real | False confidence in assessment | High | Low | Kernel behind interface, clearly mocked; no real output shown as validated | Gate real kernel behind validation + version field |

\* Severity/Probability are **provisional placeholders** pending formal scales & clinical review.

## 3. Risk control traceability
Controls above map to implemented code (input filters, review dialogs, SQLCipher, insert-only
audit, day-scoped cache, patient banner) and to `docs/requirements/traceability-matrix.md`.
Open controls (auth/RBAC, real sync, kernel validation, threat model) are tracked in
`PROGRESS.md` and `docs/regulatory-foundation.md` §3/§5.

## 4. Residual risk & benefit-risk
TODO — after controls are complete and verified (blocker #4), evaluate residual risk against
clinical benefit per ISO 14971 and record sign-off in the DHF.
