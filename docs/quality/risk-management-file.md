# Risk Management File — ISO 14971 (initial)

> **Initial risk file, not a completed analysis.** Seeded with hazards evident from the current
> design and the risk controls already implemented, so the team has a real starting register.
> Severity/probability estimates are **provisional placeholders** for a qualified clinical +
> risk team to review. To be maintained across the lifecycle (ISO 14971).

## 1. Scope & policy
Device: **PHC Patient Care** SaMD. Intended use: frontline capture of patient
registration/vitals/consultation at rural PHCs, with a real HTTP clinical kernel (`/v1/assess`,
`/api/v1/evaluate`) and **mandatory doctor review** (AGREE/MODIFY/REJECT) before any clinical
action. Provisional software safety class, **re-examined 2026-08 against CDSCO/MD/GD/MDSW/01/2026
Table 2** (full derivation: `docs/regulatory-foundation.md` §2.3): **B or C, genuinely
unresolved** — base case (Serious situation × Drive-clinical-management) is B; the guidance's
non-clinical-user note may escalate the situation to Critical (→ C) since the doctor's review is
asynchronous, not co-located with the frontline worker at the point the AI output is generated.
Risk acceptability policy, severity/probability scales, and residual-risk sign-off: **TODO** by
the risk team; **budget for C, do not assume B**, pending CDSCO confirmation.

## 2. Hazard register (initial)

| ID | Hazard / hazardous situation | Potential harm | Sev* | Prob* | Risk controls implemented | Residual / open work |
|----|------------------------------|----------------|------|-------|---------------------------|----------------------|
| H-01 | Wrong/mistyped vitals recorded | Clinician acts on wrong data | High | Med | Digit/decimal input filters (`NumericInputFilters`); review-before-send dialog; doctor review | Add range/plausibility validation; unit tests |
| H-02 | Mis-triage / automation bias on kernel output | Delayed or wrong care | High | Med | *(this is the hazard the Class C determination above is centred on — the risk control here is load-bearing for the whole classification argument, not just this row)* **Human-in-the-loop**: kernel not autonomous; review gates; doctor confirms. **2026-07:** the doctor-review step is now a real interactive AGREE/MODIFY/REJECT decision (`SubmitDoctorDecisionUseCase`, REQ-RX-01/RFN-01) rather than a randomly-simulated response — the reviewer sees the AI's confidence/differential/reasoning before deciding, and REJECT is structurally never eligible for a future training-dataset reimport (no fabricated ground truth). MODIFY requires a corrected diagnosis from a fixed 18-class list, not free text, closing one path for an unreviewable/untrainable correction to enter the loop silently. | Kernel validation + versioning (deferred); confidence/explainability UI; still no enforcement stopping a reviewer from picking AGREE without actually reading the evidence (a UX/training issue, not a code gate) |
| H-03 | Wrong-patient record mix-up | Wrong treatment | High | Low | Persistent patient **name + ID banner**; patient context derived from nav back stack; `Patient.id` now a 12-char alphanumeric UID matching `agent_docs/spec.md` (was a 36-char UUID) — closes the format gap flagged since early hardening | None open on this control; broader wrong-patient mitigations (e.g. barcode/photo confirmation) remain future work |
| H-04 | PHI breach on lost/stolen device | Privacy harm (DPDP) | High | Med | **SQLCipher** at rest + non-exportable **Keystore** key; **day-scoped cache** (minimisation) | Formal threat model; screen-lock/session policy; secure backend later |
| H-05 | Offline data loss before sync | Lost clinical record | Med | Med | Durable local Room writes (local ACID) | Real outbox/dirty-flag sync (`docs/sync-design.md`); currently sync is mocked |
| H-06 | Unauthorised access / no real auth | Wrong actor, accountability gap | Med | High | Audit log carries a real per-session identity (`AuthSession`/`MockAuthSession`, REQ-SEC-04) instead of the hardcoded `phc_field_worker` placeholder. `userId` is now deterministically derived from the entered name + role (SHA-256, truncated) instead of a fresh random id per sign-in, so the same worker signing in on different days maps to the same `userId` in the audit trail — closing the traceability gap where one person appeared as N different actors. **This still does not reduce unauthorised-access risk**: `signIn` has no credential check, so anyone typing an existing worker's name+role gets that worker's `userId`; it only makes accountability *consistent* once someone is in, not *verified*. | **Open**: real authentication + RBAC enforcement (REQ-SEC-03) |
| H-07 | Tampered or lost audit trail | Loss of accountability/traceability | Med | Low | **Insert-only** audit DAO (no update/delete at interface level) | Tamper-evidence (hash chain) + export for review |
| H-08 | Hurried / incomplete data entry | Incomplete clinical picture | Med | Med | **Review-before-submit** dialogs on Consultation & Medical background | Required-field enforcement per clinical rules |
| H-09 | Kernel unavailable/mocked mistaken for real | False confidence in assessment | High | Low | Kernel behind interface, clearly mocked; no real output shown as validated. `InferenceSource` (`REAL_INFERENCE`/`MOCK_FALLBACK`) traceability marker on every `/v1/assess`-derived result (REQ-HAN-08). **2026-07:** the newer `/api/v1/evaluate` leg (REQ-EVL-01) has deliberately **no mock fallback at all** — a failed call just omits the AI Clinical Evaluation section from the report/prescription rather than silently substituting fabricated treatment data, which removes this hazard's "mocked mistaken for real" failure mode entirely for that leg (there is nothing mocked to mistake). | Gate real kernel behind validation + version field (still applies to the `/v1/assess` leg's mock-fallback path) |
| H-11 | External third-party network call (Gemini API) for brand-name lookup | Availability/latency dependency on a service outside our control; data (generic drug name only, never patient-identifying) leaves the device to a third party | Low | Med | `BrandLookupSource`/`GeminiBrandLookupSource` never throws and never blocks the evaluate pipeline on failure (best-effort enrichment only); only the generic drug name is sent, never patient identity/vitals/symptom text; API key stored in git-ignored `local.properties`, never committed | Formal data-processing-agreement review for sending any data to Gemini (even non-identifying) before production; consider an on-device or India-hosted brand-lookup alternative if this needs to leave demo status |
| H-10 | Patient identity fields inadvertently included in kernel payload | Privacy harm (DPDP); expands the kernel's data-processing scope beyond clinical need | High | Low | **Structural constructor design**: `KernelPayload` has no field of type `Patient`, and `SendToKernelUseCase`'s signature only accepts `VitalsReading` + `Consultation` + an opaque case token — a `Patient` object cannot reach the kernel boundary even by mistake, not just "by convention." Whitelisted fields only (chief complaint, duration, severity, relevant history, transcription, attachments); `Patient.fullName`/`aadhaarNumber`/`abhaNumber`/`mobileNumber`/`guardianOrSpouseName`/address fields are excluded by construction. Verified on-device: constructed payload for a patient with full identity data contained none of it. **2026-07:** `RetrofitEvaluateSource` builds `EvaluateRequestDto` from the same `KernelPayload` + separately-passed age/sex (never a `Patient`) — same structural guarantee extends to the `/api/v1/evaluate` leg without new code review needed for this specific control. | Extend the same structural pattern when a real network-facing kernel is added; consider a separate opaque token (not the case PK) at that point |

\* Severity/Probability are **provisional placeholders** pending formal scales & clinical review.

## 3. Risk control traceability
Controls above map to implemented code (input filters, review dialogs, SQLCipher, insert-only
audit, day-scoped cache, patient banner) and to `docs/requirements/traceability-matrix.md`.
Open controls (auth/RBAC, real sync, kernel validation, threat model) are tracked in
`PROGRESS.md` and `docs/regulatory-foundation.md` §3/§5.

## 4. Residual risk & benefit-risk
TODO — after controls are complete and verified (blocker #4), evaluate residual risk against
clinical benefit per ISO 14971 and record sign-off in the DHF.
