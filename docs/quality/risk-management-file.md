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
| H-03 | Wrong-patient record mix-up | Wrong treatment | High | Low | Persistent patient **name + ID banner**; patient context derived from nav back stack; `Patient.id` now a 12-char alphanumeric UID matching `agent_docs/spec.md` (was a 36-char UUID) — closes the format gap flagged since early hardening. **2026-08 (ABDM M1 adapter, backend, `ABDM_MODE=stub`):** the backend's `GET .../registration-sessions/{id}/profile` returns a verified `AbhaIdentity` and writes **no patient association**; there is no code path anywhere in `backend/abdm-adapter/` that sets `Patient.abhaNumber` or any FK to a patient row. Linking a verified ABHA identity to a specific local patient record is explicit and worker-confirmed by design (api-contract.md section 8, `SAMD-PAT-3004`'s duplicate-ABHA guard), and is Android-side, Phase 6 work, not built this session. | None open on this control; broader wrong-patient mitigations (e.g. barcode/photo confirmation) remain future work. **Open, tracked for Phase 6:** the Android-side linkage flow itself (candidate match -> explicit worker confirmation -> link) does not exist yet; this row exists so the hazard is recorded against the real backend capability now, not discovered retroactively once linkage is built. **2026-08-18, Phase 6c Part 2: still open, status unchanged.** `CreateAbhaProfileUseCase`/`VerifyAbhaLoginUseCase` were deliberately NOT rewired to the real backend this session (blocked on a UI gap and a P1 backend dependency, see PROGRESS.md), so no new linkage code exists to introduce an auto-link risk. Re-verified: registration still requires explicit worker review/submission of the autofilled form (`RegisterViewModel`/`RegisterPatientUseCase`) before any `Patient` row is written or linked — no silent auto-link or fuzzy-match auto-pick was introduced by this session's `AbdmAbhaSource` infra work (unused by any use case yet) or by the W2 masked-mobile fix (form validation only, no linkage logic touched). |
| H-04 | PHI breach on lost/stolen device | Privacy harm (DPDP) | High | Med | **SQLCipher** at rest + non-exportable **Keystore** key; **day-scoped cache** (minimisation) | Formal threat model; screen-lock/session policy; secure backend later |
| H-05 | Offline data loss before sync | Lost clinical record | Med | Med | Durable local Room writes (local ACID) | Real outbox/dirty-flag sync (`docs/sync-design.md`); currently sync is mocked |
| H-06 | Unauthorised access / no real auth | Wrong actor, accountability gap | Med | High | Audit log carries a real per-session identity (`AuthSession`/`MockAuthSession`, REQ-SEC-04) instead of the hardcoded `phc_field_worker` placeholder. `userId` is now deterministically derived from the entered name + role (SHA-256, truncated) instead of a fresh random id per sign-in, so the same worker signing in on different days maps to the same `userId` in the audit trail — closing the traceability gap where one person appeared as N different actors. **This still does not reduce unauthorised-access risk**: `signIn` has no credential check, so anyone typing an existing worker's name+role gets that worker's `userId`; it only makes accountability *consistent* once someone is in, not *verified*. | **Open**: real authentication + RBAC enforcement (REQ-SEC-03) |
| H-07 | Tampered or lost audit trail | Loss of accountability/traceability | Med | Low | **Insert-only** audit DAO (no update/delete at interface level) | Tamper-evidence (hash chain) + export for review |
| H-08 | Hurried / incomplete data entry | Incomplete clinical picture | Med | Med | **Review-before-submit** dialogs on Consultation & Medical background | Required-field enforcement per clinical rules |
| H-09 | Kernel unavailable/mocked mistaken for real | False confidence in assessment | High | Low | Kernel behind interface, clearly mocked; no real output shown as validated. `InferenceSource` (`REAL_INFERENCE`/`MOCK_FALLBACK`/`UNAVAILABLE`) traceability marker on every `/v1/assess`-derived result (REQ-HAN-08). **2026-07:** the newer `/api/v1/evaluate` leg (REQ-EVL-01) has deliberately **no mock fallback at all** — a failed call just omits the AI Clinical Evaluation section from the report/prescription rather than silently substituting fabricated treatment data, which removes this hazard's "mocked mistaken for real" failure mode entirely for that leg (there is nothing mocked to mistake). **2026-08-20 (kernel-mock production safety fix):** the `/v1/assess` mock scenario table (`GenerateKernelReportUseCase`'s old `SCENARIOS`/`DEFAULT_SCENARIO`) moved out of the shared compilation unit entirely, into `src/dev/java/.../data/kernel/MockKernelFallbackSource.kt`, bound only by a dev-flavor-only Hilt module (`DevClinicalMockModule`). Staging/prod bind `NoFallbackKernelSource` instead (always returns null), so a real `/assess` failure in those flavors now produces an honest `InferenceSource.UNAVAILABLE` result — no fabricated diagnosis, `predictedCondition = "Assessment unavailable"`, `requiredHumanVerification = true` — with a retry affordance on `KernelAssessmentScreen`. The marker is now also rendered on the exported/printed clinical report (`ReportCanvasRenderer`'s demographic block, via the pure `assessmentMarkerLabel` function) and on the doctor's AGREE/MODIFY/REJECT review card (`PatientSummaryScreen`'s `DoctorReviewCard`), closing the prior gap where the tag existed in Room/the audit log but was never read back past one mid-flow acknowledgement screen. This closes the "mock reachable in a non-dev build" failure mode for this hazard. | Gate real kernel behind validation + version field (still open, unrelated to this fix). Confirm staging/prod builds are actually built from the `staging`/`prod` product flavors in CI/release config, not just `debug` build type on the `dev` flavor, since the flavor-gating only holds if the release pipeline selects the right flavor. **2026-08-21:** the retry affordance this row introduced was also the path that produced duplicate `kernel_reports` rows for one case (each attempt inserted a new row), so an unordered reader could serve the superseded `UNAVAILABLE` row instead of the successful retry — closed by `MIGRATION_15_16` + `KernelReportDao.getIdForCase`, recorded in full as RR-02 in §4.1 |
| H-12 | Backend-derived clinical values stored as if they were model output | Loss of traceability: an auditor, a reviewer, or a future model-change assessment cannot tell which fields the named model is answerable for, so an incorrect result cannot be attributed to a model change or to a rule change | High | Med | **Server-side storage of model output is raw-only.** `kernel_assessments` (backend, migration `0004`) holds the kernel's response body verbatim in `jsonb` plus provenance the server owns, and stores **zero** derived values: no `predicted_condition`, no `confidence_score`, no `risk_category`, no `required_human_verification`. A test over the table's mapped columns fails if a derived column is ever added, so the control does not decay. All derived clinical values are computed at **read time** by `app/domain/kernel_derivation.py`, a pure versioned rule module (`derivation_rule_version = "HAN-07/08-v1"`), persisted nowhere. Bumping that constant is mandatory for any threshold or rule change: the 0.90 human-verification threshold is a risk control (REQ-HAN-08, and load-bearing for H-02), and the Algorithm Change Protocol under CDSCO/MD/GD/MDSW/01/2026 must be able to separate a rule change from a model change. `kernel_reports` is the **device-owned** record of what was displayed to the clinician and is not written by the server; the Phase 3 proxy write that reshaped each response into that table is deleted. | Phase 4 sync push must preserve device ownership of `kernel_reports` (no server-side merge of its clinical columns). The equivalent proxy write into `evaluate_reports` still exists and carries the same hazard at lower severity, since it stores the tree unreshaped; recommended for removal in Phase 4 (D-10). Phase 7's dashboard and the report layer must call the derivation module rather than re-implementing the rules |
| H-11 | External third-party network call (Gemini API) for brand-name lookup | Availability/latency dependency on a service outside our control; data (generic drug name only, never patient-identifying) leaves the device to a third party | Low | Med | `BrandLookupSource`/`GeminiBrandLookupSource` never throws and never blocks the evaluate pipeline on failure (best-effort enrichment only); only the generic drug name is sent, never patient identity/vitals/symptom text; API key stored in git-ignored `local.properties`, never committed | Formal data-processing-agreement review for sending any data to Gemini (even non-identifying) before production; consider an on-device or India-hosted brand-lookup alternative if this needs to leave demo status |
| H-10 | Patient identity fields inadvertently included in kernel payload | Privacy harm (DPDP); expands the kernel's data-processing scope beyond clinical need | High | Low | **Structural constructor design**: `KernelPayload` has no field of type `Patient`, and `SendToKernelUseCase`'s signature only accepts `VitalsReading` + `Consultation` + an opaque case token — a `Patient` object cannot reach the kernel boundary even by mistake, not just "by convention." Whitelisted fields only (chief complaint, duration, severity, relevant history, transcription, attachments); `Patient.fullName`/`aadhaarNumber`/`abhaNumber`/`mobileNumber`/`guardianOrSpouseName`/address fields are excluded by construction. Verified on-device: constructed payload for a patient with full identity data contained none of it. **2026-07:** `RetrofitEvaluateSource` builds `EvaluateRequestDto` from the same `KernelPayload` + separately-passed age/sex (never a `Patient`) — same structural guarantee extends to the `/api/v1/evaluate` leg without new code review needed for this specific control. | Extend the same structural pattern when a real network-facing kernel is added; consider a separate opaque token (not the case PK) at that point |

| H-13 | Fabricated vitals reachable in a non-dev build | Clinician/kernel acts on plausible-but-fake vitals believing they came from a device | High | Low | **2026-08-20 (kernel-mock production safety fix):** `MockVitalsSource` (random pulse/BP/SpO2/temperature/etc, tagged `deviceId = "MOCK-VITALS-MONITOR-01"`) moved out of the shared compilation unit into `src/dev/java/.../data/mock/MockVitalsSource.kt`, bound only by the dev-flavor-only `DevClinicalMockModule`. Staging/prod bind `UnavailableVitalsSource` instead, which returns an all-null `VitalsReading()` — every field already nullable per `GetVitalsPrefillUseCase`'s existing contract ("the result is always user-editable before save"), so the worker fills every field in manually rather than getting a plausible fake number pre-filled. No behavior change in dev. | No real BLE/device vitals-monitor integration exists yet — `VitalsSource` is still a seam waiting for one (see its own KDoc); confirm staging/prod release builds actually select the `staging`/`prod` flavor |
| H-14 | `/api/v1/evaluate` failure indistinguishable from "nothing to report" | A report/reviewer cannot tell a missing AI Clinical Evaluation section apart from a failed call that was silently dropped — no fabricated data (H-09's failure mode does not apply here), but the *absence itself* carries no signal | Med | Med | `GenerateEvaluateReportUseCase` already audit-logs the failure honestly (`AuditAction.EVALUATE_RESPONSE_FAILED` with the error message, `SendingViewModel`) — the audit trail is not the gap. The gap is downstream: no Room row is written on failure, so `EvaluateReportRepository.getForCase()` returns null identically for "hasn't run yet" and "failed," and nothing user-facing (report screen, PDF, doctor review) can tell the two apart. **STATUS: CLOSED, 2026-08-20.** Diagnosed and scoped during the kernel-mock production safety fix; the persisted-failure-signal design was decided separately (`docs/quality/h-14-evaluate-failure-decision.md`, Option 2, operator-approved before implementation) because `EvaluateReportEntity` carries sync/audit semantics (`syncState`, `serverVersion`, wire mapping per its own KDoc) and needed a specific decision on whether a failure row should sync to the backend at all. Implemented as a nullable `failureCode` column on `EvaluateReportEntity` (`MIGRATION_14_15`, DB version 14 → 15), deliberately excluded from `EvaluateReportSyncPayloadDto` so it never crosses the sync wire; `EvaluateReportDao.getPendingForSync`'s `failureCode IS NULL` predicate is the actual enforcement point, proven against a real Room database in `EvaluateReportFailureSyncSafetyTest` (a failure row is asserted absent from the outbox's row set, not just assumed absent by design). `GenerateEvaluateReportUseCase`'s catch block now calls `EvaluateReportRepository.saveFailure()` instead of writing nothing, so `getFailureCodeForCase()` returns a distinguishable failure state instead of `getForCase()`'s `null` looking identical to "hasn't run yet." Reaches the same two surfaces H-09's `UNAVAILABLE` marker does: the exported/printed clinical report (`ReportCanvasRenderer`'s `evaluateFailureMarkerLabel` function, rendered via `urgentLineBlock`) and the doctor's AGREE/MODIFY/REJECT review card (`PatientSummaryScreen`'s `DoctorReviewCard`, gated on `PatientSummaryUiState.evaluateFailureCode`). `EvaluateReportRepositoryImpl.save`/`saveFailure` now resolve the existing row by `caseRecordId` (`EvaluateReportDao.getIdForCase`) before upserting, so a retry genuinely REPLACEs the same physical row and clears the marker rather than accumulating a second, orphaned row — this identity-resolution fix was a prerequisite the original `save()` didn't have, found and closed in the same pass. | None outstanding for this hazard. Unrelated follow-up found during this pass: the backend `inference_source` CHECK constraint for `kernel_reports` didn't originally accept H-09's `UNAVAILABLE` value, permanently rejecting every such sync push — closed by backend migration `0006`, guarded going forward by `test_inference_source_vocab.py`'s set-agreement tests |
| H-15 | ASR mis-transcription silently changes a clinical field value | A voice-derived value reaches a field a clinician or the `/api/v1/evaluate` model reads, without the worker having reliably verified it; severity is provisionally High for `chiefComplaint` (reaches the evaluate wire) and Medium for the other narrative fields (read by a human only) | High (chiefComplaint) / Med (other narrative fields) | Med | **Interim control, implemented this PR (`fix/asr-offdevice-exposure`):** `FeatureFlags.VOICE_INPUT_ENABLED = false` hides every voice affordance on the Consultation screen; the handlers (`ConsultationViewModel.onRecordChiefComplaintVoice`/`onRecordAudioAttachment`) return before `CaptureAudioAttachmentUseCase` is ever invoked, so `AndroidSpeechRecognizerService` cannot be reached from the UI in this build. `onRecordChiefComplaintVoice`'s `onSuccess` branch was additionally fixed defensively so a captured transcript is never written directly into `chiefComplaint` even if that branch were somehow reached. **Proposed, not yet built:** the confirmation-gate architecture (`VOICE_UNCONFIRMED`/`VOICE_CONFIRMED`/`VOICE_EDITED` field provenance, a repository-level write refusal for unconfirmed values, and `VOICE_FIELD_SUGGESTED`/`CONFIRMED`/`EDITED`/`REJECTED` audit breadcrumbs) described in `scratchpad/asr-usecase-research-memo.md` section 2.6 and `scratchpad/asr-field-audit-memo.md` Part B. **PROPOSED, AWAITING OPERATOR SIGN-OFF, update from PR 2 (`feat/asr-voice-audit-actions`):** the four `VOICE_FIELD_*` audit action enum values now exist on both the device `AuditAction` enum and the backend mirror, proven equal by `test_audit_actions_device.py`'s parse-equality test. Enum-level only: nothing on the device emits them yet, and the confirmation-gate architecture itself is still not built. **PROPOSED, AWAITING OPERATOR SIGN-OFF, update from PR 3a (`feat/asr-voice-gate-refusal`):** the repository-level write refusal named above now exists in code. `ConsultationRepositoryImpl.saveConsultation` returns `DataError.Refused` and performs no DAO write when a `Consultation` carries `impactOnDailyActivitiesProvenance = VOICE_UNCONFIRMED`, proven against a real Room database by `ConsultationVoiceUnconfirmedRefusalTest`, which asserts the absence of the persisted row rather than the returned value and was additionally verified non-vacuous by mutation. This is a **backstop only**: it is not reachable, because no code path constructs `VOICE_UNCONFIRMED` yet (the gate state model and UI are later sub-steps) and no feature flag exposes voice on this field. The residual risk to a user is therefore **unchanged** by PR 3a, and the gate control must not be read as functioning until the state model, the UI and the on-device engine land together. | Build the confirmation-gate architecture (deferred PR, not this one) before re-enabling `VOICE_INPUT_ENABLED`. Voice must remain excluded from every measurement and identity field regardless (see the field-audit memo's A.3 exclusion list) |
| **PROPOSED, AWAITING OPERATOR SIGN-OFF (direction accepted; final sign-off pending vocabulary research)** H-16 | Backend kernel-boundary PHI guard is key-name-only, not value-level | `backend/core/app/adapters/kernel/phi_guard.py:70`, `assert_no_identity_fields`, checks `DENYLIST & payload.keys()`, a set intersection against field names. It never inspects a field's *value*. This is sufficient today because `symptom_string` holds a terse, delimiter-separated symptom list (`"fever, body ache, dry cough"`, `docs/backend/api-contract.md:902`) that cannot structurally carry an identifier. It stops being sufficient if that field is ever populated from patient speech, since a patient's own words could contain a name or a phone number inside a value that passes both `KernelEvaluateRequest`'s `extra="forbid"` schema and the name-only denylist, and reach the upstream kernel host verbatim | High | Low (no code path populates `symptom_string` from narrative today; this hazard is preventive, tied to any future decision to send narrative on that field) | None implemented; the current control (H-10) is a type-level guarantee (`KernelPayload` has no `Patient`-typed field) and a key-name denylist, neither of which reads value content | **Resolution direction (operator-selected): option (a).** `chiefComplaint` becomes a human-tapped multi-select over a presenting-complaint vocabulary, so patient narrative never reaches `symptom_string`; narrative is captured in a separate human-read field only. This eliminates the value-level PHI path rather than mitigating it, and avoids a backend value-scanner and its false-positive hazard (option (b), not chosen). Gating next step before the control can be built: the presenting-complaint vocabulary must be grounded in rural-PHC presenting-complaint evidence (research owed), because a pick-list that does not fit the setting would be worse than free text. Tracked as PR 6 / PR 7a of the ASR + structured-capture track. |

\* Severity/Probability are **provisional placeholders** pending formal scales & clinical review.

**H-15 was added and operator-signed-off from this session's ASR read-only design work
(`scratchpad/asr-field-audit-memo.md`, `scratchpad/asr-usecase-research-memo.md`), drafted here as
part of PR 0b; it stays open (interim control implemented, confirmation-gate control not yet
built). H-16 is a proposed addition from the same design work; its resolution direction (option
(a)) is operator-selected but the row stays pending final sign-off until the presenting-complaint
vocabulary is grounded.**

## 3. Risk control traceability
Controls above map to implemented code (input filters, review dialogs, SQLCipher, insert-only
audit, day-scoped cache, patient banner) and to `docs/requirements/traceability-matrix.md`.
Open controls (auth/RBAC, real sync, kernel validation, threat model) are tracked in
`PROGRESS.md` and `docs/regulatory-foundation.md` §3/§5.

## 4. Residual risk & benefit-risk
TODO — after controls are complete and verified (blocker #4), evaluate residual risk against
clinical benefit per ISO 14971 and record sign-off in the DHF.

### 4.1 Accepted residual risks

Recorded individually as they are accepted, rather than waiting for the full benefit-risk
evaluation. An entry here means the risk is understood, the mitigation is named, and the decision
is to live with what remains. It does not mean the risk is zero.

**RR-01 — Quasi-identifier residual risk on `patients.village`, `patients.block`,
`patients.pincode`. Status: ACCEPTED.**

*The risk.* These three columns are stored in **plaintext** on the backend `patients` table, while
`full_name`, `guardian_or_spouse_name`, `mobile_number`, `aadhaar_number` and `emergency_contact`
are encrypted with `pgcrypto` (backend PRD §5.4). None of the three is a direct identifier on its
own. In combination they are re-identifying: at rural PHC scale, village plus age plus biological
sex is close to a unique quasi-identifier, and a village of a few hundred people narrows a record
far more than a district does. Anyone reading a database dump can therefore re-identify patients
from columns that were left in the clear precisely because they looked non-identifying. This is
the same class of risk as H-04, differing in that no device is lost: it is reachable from a
backup, a dump, or an over-broad database grant.

*Why the schema is deliberately unchanged.* Both of the things these columns exist for need them
queryable in the clear. The day-scoped roster (REQ-ROS-02) filters and groups on them, and the
epidemiology work is village-level aggregation by definition. Column encryption would force a
full-table decrypt on every aggregate query, so the cost is not a slower query but an unusable
one, and a blind index buys only equality lookup, which is not what an aggregate needs. Encrypting
them would trade a real capability for a partial privacy gain against an attacker who, in the
threat model that matters here, already has the application host and therefore the key.

*Mitigation, and its honest limit.* Database-level access control (least-privilege grants, no
shared superuser for the application role) plus the pending move to **AWS KMS envelope
encryption** at the deployment session, which is what actually separates key custody from host
compromise. Column encryption is explicitly **not** the mitigation. Layer 1 (RDS/volume
encryption) protects a stolen disk; nothing today protects against an authorised reader of the
`patients` table, which is why this is accepted rather than closed.

*Cross-references.* `docs/backend/backend-prd.md` §5.4 already records the plaintext decision for
`village`/`block`/`pincode` and its reasoning, but records it as a schema choice and does not
carry it into this file as a risk. This entry is that carry-over. Related: H-04 (PHI breach on a
lost device), H-10 (identity fields on the kernel boundary). The executable counterpart of this
entry is the kernel-boundary PHI denylist: `pincode`, `block` and `village` (plus
`date_of_birth`/`dob`/`birth_date` and `pin_code`/`address_line`) were added to
`app/adapters/kernel/phi_guard.py::DENYLIST` in the Phase 3 fix pass (B2), closing the executable
half of this acceptance. `age` deliberately stays allowed: it is a legitimate clinical signal the
vitals models take and is in the shipped `/assess` contract.

**RR-02 — One current assessment per case: pre-existing duplicate `kernel_reports` /
`evaluate_reports` rows are resolved newest-wins and the losers are deleted device-side, with
server-side duplicates left standing until the Phase 4 sync-push invariant lands.
Status: ACCEPTED.**

*The risk.* Both tables' entity KDoc has always claimed one row per `caseRecordId`, replaced
wholesale on retry, and every reader relies on it: `getForCase` takes `.first()` of the match set
with no `ORDER BY`. On `evaluate_reports` the claim became true only in the H-14 pass
(`EvaluateReportDao.getIdForCase`). On `kernel_reports` it was never true — `GenerateKernelReport-
UseCase` mints a fresh `UUID` per attempt, the primary key is that `id`, so the REPLACE-upsert
keyed on a value that never collided and **every re-assessment inserted an additional row**. The
clinical failure mode is specific and is a sibling of H-09: after an `InferenceSource.UNAVAILABLE`
assessment the worker retries, the retry succeeds, and both rows now sit in the table — an
unordered `.first()` can hand the reviewer, the printed report, or the doctor-assignment resolver
the **superseded failure row** while the successful result goes unread. Not fabricated data (H-09's
mock-mistaken-for-real mode does not apply), but a real path to a decision made on a stale
assessment.

*The control.* `MIGRATION_15_16` (DB 15 → 16) makes `caseRecordId` UNIQUE on both tables, and
`KernelReportRepositoryImpl.save` now resolves the existing row by `caseRecordId` before upserting
(`KernelReportDao.getIdForCase`), the same shape the H-14 pass gave the evaluate leg. The write
path and the schema constraint land together deliberately: the index alone would turn the latent
correctness bug into a crashing constraint violation on the next re-assessment.

*The de-dup rule, and why newest wins.* Rows already duplicated on a device in the field must be
collapsed before the unique index can be created. **The surviving row per case is the one with the
greatest `localModifiedAt`.** The reasoning is clinical, not mechanical: the common duplicate is a
failure row followed by a successful retry, and the retry is the assessment the clinician acted on
and the one the record should carry — the newest write is the one that supersedes, so it is the one
that survives. `localModifiedAt` is the ordering key rather than `inferenceEndedAt` because it is
defined as "when this row's bytes last changed on this device", whereas `inferenceEndedAt` is
clinical inference timing and is a **placeholder** on a failure row (`saveFailure` stamps both
inference times with `now`), which would make it order failure rows wrongly against the very
retries meant to beat them. It is safe to sort on: NOT NULL on both tables, and although
`MIGRATION_12_13` introduced it as `NOT NULL DEFAULT 0` it backfilled every existing row in the
same step from `COALESCE(inferenceEndedAt, inferenceStartedAt)`, both NOT NULL, so no row carries
a sentinel 0. Ties (two writes for one case inside one millisecond — a network round trip apart in
practice, but not provably impossible) break on `rowid DESC`, insertion order on these rowid
tables, so the result is deterministic rather than SQLite's choice.

*What remains, and is accepted.* Two things. **First, the discarded rows are not recoverable.**
The migration deletes them rather than archiving them; a superseded duplicate carries no clinical
information the surviving row lacks, and keeping a shadow copy of superseded AI output would create
a second, unreviewed record of what the model said — the opposite of what H-12 is protecting.
Nothing references them: no foreign key anywhere points at either table's `id` (checked against
schema 15), and the sync outbox is a query over `syncState`, not a separate table
(`RoomSyncOutboxRepository`), so deleting a PENDING row leaves no orphaned outbox entry.
**Second, duplicates already pushed to the backend are untouched.** The DELETE is device-local and
cannot reach a row that already synced. The server-side half of this invariant is recorded as a
Phase 4 sync-push requirement (`docs/backend/api-contract.md` §6.1: one current assessment per
`case_record_id` scoped to facility, newest-write-wins, and no DOCTOR bundle fetch returning more
than one) rather than as a standalone ticket, and is deliberately not mixed into the migration
commit — a backend change inside a Room-migration review surface is how this class of defect gets
missed. Until that lands, a server-side duplicate remains possible for cases that synced before
the upgrade.

*Cross-references.* H-09 (`UNAVAILABLE` retries are the duplicate-producing path), H-02
(mis-triage / acting on the wrong assessment), H-14 (the evaluate leg's half of this, already
closed). Deploy note on the one-time re-push this migration causes: `PROGRESS.md`.
