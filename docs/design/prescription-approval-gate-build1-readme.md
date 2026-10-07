# Build 1 developer README — prescription visibility gate + DOCTOR decision-surface gate

**Branch:** `feat/prescription-approval-gate`. **Status:** built, tested, NOT committed — awaiting
operator authorization. Read `scratchpad/consultation-documents-and-prescription-gate-memo.md`
Feature 2 (A1–A6) and Part F Build 1 first; this doc is orientation on top of that, not a
replacement for it.

## The four repo-vs-brief conflicts (C-1..C-4), in plain language

The design memo found four places where the original feature brief didn't match the actual repo.
Only C-1 and C-3 changed what Build 1 does; C-2 and C-4 are context for later builds.

- **C-1 — there was nothing to gate on `ClinicalReport.prescription`.** The brief assumed the
  report "already produces a prescription after evaluation" and asked to hide that until approved.
  In the real repo, `Prescription` doesn't exist until the doctor decides — `SubmitDoctorDecisionUseCase`
  creates it. What was actually leaking was the AI's **raw treatment recommendation**
  (`EvaluateReportOutput.nlemTreatment.recommendedDrug` + `topIndianBrand`), rendered on the worker
  report the moment `/api/v1/evaluate` returned, with no gate at all. Build 1 gates that object.
- **C-2 — there is no on-device hash chain.** The hash chain (tamper evidence) is backend-only
  (`backend/core/app/models/audit.py`). A device audit row is just an insert-only Room row until it
  syncs; it only becomes tamper-evident once it lands in the server chain. The two new audit
  actions in this build are worded that way in the risk-file entry — don't describe them as
  "hash-chained on-device audit."
- **C-3 — the decision surface had no role check at all, which made a render-only gate fake.**
  `DoctorReviewCard` (inside the worker's own app, `PatientSummaryScreen.kt`) only checked case
  status, never `UserRole`. Any signed-in worker — including `ASHA_WORKER` — could open it and tap
  AGREE, writing a real prescription naming the AI's drug and brand. Hiding the *render* without
  gating the *button* would have been a control that looks like a control and isn't one: the worker
  could just go tap AGREE thirty seconds earlier on the same screen. **This is the gap Build 1
  exists to close, as much as the render gate itself.**
- **C-4 — not touched by Build 1.** About attachment encryption-at-rest; relevant to Feature 1
  (document upload), a later build.

## What Feature 2 gates, and where

**Seam: `ReportFormatter.format(...)`** (`app/src/main/java/com/example/samdapp/domain/report/ReportFormatter.kt`),
the same place that already redacts `PRIVATE` ailments for `ReportAudience.WORKER`
(`ReportFormatter.kt:150`-ish, `entry.visibility == Visibility.PRIVATE && audience == ReportAudience.WORKER`).
**Not** `ReportCanvasRenderer` — gating there would leave the un-gated data sitting in the
`ClinicalReport` object, reachable by any other consumer (the PDF exporter, in particular). Both
the Compose preview and the PDF exporter read the same `ClinicalReport`, so gating at the formatter
covers both for free.

The gate is a plain `Boolean` parameter (`prescriptionApprovalGateEnabled`) on `format(...)`, not a
`FeatureFlags` import inside `ReportFormatter` itself — keeps the formatter a pure function of its
inputs (its own KDoc claims "No Android deps, no I/O") and keeps it trivially testable with both
flag states. The caller, `AssembleReportUseCase`, reads `FeatureFlags.PRESCRIPTION_APPROVAL_GATE_ENABLED`
and passes it in.

**What the gate actually does, when active (`gateActive = flag && audience == WORKER`) — PHYSICIAN
audience is never gated, unconditionally:**

| Signal | Gated behaviour |
|---|---|
| `EvaluateReportOutput` (the whole "AI Clinical Evaluation" section — treatment, brand, vitals triage, diagnosis) | Always hidden from `WORKER` when the gate is active, regardless of decision state. This is a deliberate simplification: it's what makes MODIFY's "AI's original not shown alongside it" requirement true unconditionally, without a second field-level gate. The reviewer still sees this data — `DoctorReviewCard` reads `EvaluateReportEntity` directly via `PatientSummaryViewModel.onOpenDoctorReviewPicker`, bypassing `ReportFormatter` entirely, so the approval step is unaffected. |
| No committed `Prescription.kernelDecision` yet | `diagnosis` is set to a synthetic `"Awaiting physician review"` line (this is what makes the Rx section render at all pre-decision — `ReportCanvasRenderer`'s `rxBlock` condition is `prescription.isNotEmpty() \|\| diagnosis != null`). Medication lines stay empty (already true structurally, gated explicitly anyway per the memo's "gate both anyway so a future write-ordering change can't make this a silent no-op"). |
| `kernelDecision == AGREE` | Medication lines render from `Prescription.medications` (unchanged from before — this is what "the gate stops suppressing" means). |
| `kernelDecision == MODIFY` | Same as AGREE — `Prescription.medications` for MODIFY is already the doctor's own manually-entered line, not the AI's, so nothing extra was needed here. |
| `kernelDecision == REJECT` | Medication lines forced empty even though `Prescription.medications` still has a (now-irrelevant) row in it — existence isn't touched, only rendering. `diagnosis` shows the doctor's own reject reasoning (see below). `kernelDecision` itself still renders ("Doctor's review of AI assessment: REJECT"), and `suggestsReferral`/`referralReasonSuggestion` are untouched (they were never audience-gated) — so REJECT is never a blank section, it's an honest one. |

Flag off (`PRESCRIPTION_APPROVAL_GATE_ENABLED = false`): every one of the above stays exactly as it
was before this build — `evaluateOutput` populated unconditionally, REJECT's medication line
renders like any other, no synthetic "awaiting review" line. `ReportFormatterTest` has explicit
tests pinning this (`flag off restores prior behaviour…`, `flag off, no decision yet…`).

## The reject-reason field (operator addition beyond the memo)

The memo's draft only proposed reusing the existing hardcoded REJECT diagnosis string
(`"Clinical assessment pending further evaluation (AI suggestion not clinically supported)"`). The
operator asked for more: a real free-text field the doctor types into, that the worker actually
sees.

- **UI:** a new `OutlinedTextField` in `PatientSummaryScreen.kt`'s REJECT branch (`DoctorReviewCard`),
  above the shared `ManualPrescriptionFields`.
- **State:** `PatientSummaryUiState.rejectReasonText`, wired through `onRejectReasonChange`.
  `canConfirmDecision` for REJECT now also requires it non-blank (in addition to the pre-existing
  `manualDrugName`/`manualDosage` requirement, which this build left alone — that requirement is a
  separate, pre-existing quirk not in this build's scope).
- **Persistence — no schema change.** `SubmitDoctorDecisionUseCase` gained a `rejectReason: String = ""`
  parameter. On REJECT, it's used as `Prescription.diagnosis` directly (falls back to the old fixed
  string only if left blank) instead of always writing the fixed string. `PrescriptionEntity.diagnosis`
  is an existing, already-written, non-nullable `String` column — this needed **zero** new columns
  and **zero** migrations, which is why this wasn't a STOP-and-report case. (`DiagnosisFeedbackEntity.clinicalNote`
  was the other existing free-text candidate; not used here because it's a different field with a
  documented different purpose — MODIFY-only audit note, explicitly never meant to reach the
  worker-facing report — and reusing it for REJECT-reasoning-that-IS-worker-facing would have
  muddied both meanings.)
- **Flow to the report:** `Prescription.diagnosis` already flowed unconditionally into
  `ClinicalReport.diagnosis` and `ReportCanvasRenderer`'s `rxBlock` ("Diagnosis: …" line) before this
  build. No new plumbing needed there either — the gate logic just stopped overwriting it with the
  "Awaiting physician review" synthetic line once a decision exists.

## `UserRole.DOCTOR` and every `when()` it touches

**`DOCTOR` already existed** on `UserRole` (`app/src/main/java/com/example/samdapp/domain/auth/AuthSession.kt:12`)
— added in Phase 6a per its own KDoc ("Adding the constant is in scope here; gating any screen on
it is not."). So there was no enum addition to make; the build brief's phrasing ("add DOCTOR as a
real UserRole value") was already satisfied by the repo. What was missing, and what this build
adds, is the gate itself.

**Change:** `PatientSummaryUiState.canOpenDoctorReview` (`PatientSummaryViewModel.kt`) —

```kotlin
val canOpenDoctorReview: Boolean
    get() = caseStatus == CaseStatus.SENT_TO_DOCTOR && !showDoctorReviewPicker &&
        (!FeatureFlags.PRESCRIPTION_APPROVAL_GATE_ENABLED || sessionRole == UserRole.DOCTOR)
```

`sessionRole` is a new field on `PatientSummaryUiState`, populated by a new `AuthSession` injection
into `PatientSummaryViewModel` (it didn't read the session before) via a `currentUser()` collector
in `init`, same pattern `HomeViewModel` already uses.

**Every `when()` over `UserRole` was checked for exhaustiveness** (grep for `UserRole` across
`presentation/`): the only branches over `UserRole` are `LoginScreen`'s role picker and
`UserRoleDisplay`'s label helper, both of which already handle `DOCTOR` (it's not a new enum value,
so nothing needed touching). No STOP was needed here.

**Where else `canOpenDoctorReview` is read:** only `PatientSummaryScreen.kt`, as the `enabled=`
flag on the "Review AI diagnosis (doctor)" button. No other call site.

## The two audit actions + backend mirror

`AuditAction.PRESCRIPTION_APPROVED` (`"prescription_approved"`) — emitted once, at the commit, from
`SubmitDoctorDecisionUseCase`, right after the existing `DIAGNOSIS_FEEDBACK_RECORDED` call. Payload:
`kernelDecision`, `prescriptionId`, `medicationCount`. Never the drug name.

`AuditAction.PRESCRIPTION_SURFACED_TO_WORKER` (`"prescription_surfaced_to_worker"`) — emitted once
per report load from `ReportViewModel`'s `init`, only when `report.kernelDecision != null` (i.e.
the gate actually resolved to "show" something — skipped on a pre-decision preliminary load).
Payload: `kernelDecision`, `viewerRole` (from `AuthSession.currentUser()`, newly injected into
`ReportViewModel`), `caseRecordId`. Never the drug name.

Both landed on the Kotlin `AuditAction` enum (`domain/audit/AuditLogger.kt`) **and**
`backend/core/app/domain/audit_actions_device.py` in the same commit, with
`test_audit_actions_device.py`'s existing set-agreement tests (including the one that parses
`AuditLogger.kt` directly) proving them equal. Two new real-insert tests in `test_sync.py`
(`test_prescription_approved_audit_row_is_accepted_and_persisted`,
`test_prescription_surfaced_to_worker_audit_row_is_accepted_and_persisted`) push a record through
`/api/v1/sync/push` and assert the persisted `AuditEvent` row from a fresh DB query — the "real
insert, not set-membership" requirement.

**No ReportViewModel unit test.** `ReportViewModel` takes a `ReportPdfExporter`, which requires a
real/mocked Android `Context` (`@ApplicationContext`) — this project has no mocking library
(no MockK/Mockito in `build.gradle.kts`) and no existing `ReportViewModelTest` to follow a pattern
from. This is a pre-existing testability gap, not something this build introduced or was asked to
fix. The audit-emission call itself mirrors the existing `REPORT_EXPORTED` audit call already in
that file (same shape, same file, already un-unit-tested for the same reason).

## H-06 caveat — say this honestly, every time this control comes up

`UserRole` is **self-asserted at login**: `MockAuthSession.signIn` performs no credential check
(this is H-06, already open in the risk file before this build). So `UserRole.DOCTOR == session.role`
is an **accountability/intent gate**, not **access control** — it stops the *incidental* path (a
worker tapping AGREE on the screen they're already logged into) but not a *determined* one (someone
typing "Dr. X" + DOCTOR at login gets full access). Don't describe this control as closing H-06; it
doesn't, and the H-17 risk-file entry says so explicitly in its Open column.

## Flag

`FeatureFlags.PRESCRIPTION_APPROVAL_GATE_ENABLED`, default `true`. Off restores byte-identical
pre-gate behaviour in both places it matters — `ReportFormatter`'s render gate and
`canOpenDoctorReview`'s role check — because both are wrapped in the same single flag read, per the
memo's "ships inside Feature 2's flag... in one place" instruction.

## Schema

**No migration.** DB stays at version 17. The reject-reason field reused an existing non-nullable
`String` column (`PrescriptionEntity.diagnosis`) rather than adding one — this is what kept Build 1
schema-free as instructed.

## ID collision found while drafting the risk-file entry

The design memo drafted its proposed risk entry as **H-16**. By the time this build ran, `H-16` in
`docs/quality/risk-management-file.md` was already occupied by an unrelated, already-PROPOSED
backend PHI-guard hazard (kernel-boundary value-level PHI, `phi_guard.py`) — added to the file
sometime after the memo was drafted. This is exactly the kind of repo-vs-brief drift the build
instructions asked to be caught rather than built over silently. It doesn't touch any seam, enum,
or file path the memo relied on, so it didn't seem to warrant a full STOP — it's a mechanical
renumbering, not a design question. **Renumbered to H-17**, with a note inside the entry itself
recording the collision and why. Flagging it here too in case the operator wants it handled
differently (e.g., if H-16 is expected to be renumbered instead, or if this signals the memo's ID
scheme needs a re-sync against the current file before Build 2/3 draft their own entries).

## Test count

`testDevDebugUnitTest`: 322 passed (up from 307 baseline; +15 new: 8 in `ReportFormatterTest`, 3 in
`PatientSummaryViewModelTest`, 4 in the new `SubmitDoctorDecisionUseCaseTest`), 0 failed.
Backend: `test_audit_actions_device.py` + `test_sync.py`, 27 passed (up from 23 baseline; +4 new).
