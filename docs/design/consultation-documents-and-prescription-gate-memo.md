# Design memo: consultation document upload + prescription visibility gate

**Status:** STEP 1 DESIGN MEMO. Read-only pass, no code written, no files mutated outside this
memo. All risk-file and controlled-document content below is drafted **PROPOSED, AWAITING
OPERATOR SIGN-OFF**, and is not approved by the act of writing it here.

**Date:** 2026-09-03
**Scope:** two features from the clinical advisor meeting, coupled priority.
Feature 2 = prescription visibility gate. Feature 1 = consultation document upload.
**Files skipped by instruction:** none required. `local.properties` and `.env` were never read;
`app/build.gradle.kts` was read only for its `buildConfigField` and `dependencies` blocks, which
reference `local.properties` keys by name but do not contain values.

---

## PART 0: REPO-VS-BRIEF CONFLICTS (read this before anything else)

The brief asked me to stop and report rather than design on a wrong assumption. Four assumptions
in the brief do not match the repo. None of them kill either feature, but two of them change what
Feature 2 actually gates, and one of them is a finding worth more attention than either feature.
Every downstream recommendation in this memo is written against the repo, not the brief.

### C-1. The prescription does not exist before the doctor decides. The AI drug recommendation does, and the worker can already see it.

The brief says "the report already produces a prescription after evaluation" and asks that the
worker not see it until approved.

What the repo actually does:

- `Prescription` (`app/src/main/java/com/example/samdapp/domain/model/Prescription.kt:41`) is
  **created by the decision**, not before it. `SubmitDoctorDecisionUseCase:108` constructs the
  `Prescription` object inside the AGREE/MODIFY/REJECT handler and immediately persists it. Before
  a decision is submitted, `prescriptions` has no row for the case, `AssembleReportUseCase` reads
  null, and `ReportFormatter:143` sets `isFinal = false` with an empty prescription list.
- So for the `Prescription` entity specifically, "worker cannot see an unapproved prescription" is
  **already structurally true**, and there is nothing to gate.

What is actually leaking, and is the real hazard the advisor is pointing at:

- `EvaluateReportOutput.nlemTreatment.recommendedDrug` and `EvaluateReportOutput.topIndianBrand`
  are rendered onto the **worker-facing** report by
  `presentation/report/ReportCanvasRenderer.kt:385-411`
  ("Brand (India, AI-suggested): ...").
- `ReportViewModel.kt:84` is the only place the report is ever assembled, and it always passes
  `ReportAudience.WORKER`. `ReportAudience.PHYSICIAN` exists in `ClinicalReport.kt` and is
  **never constructed anywhere in the app**.
- That section renders as soon as `/api/v1/evaluate` has returned, which is well before any doctor
  decision. A worker on the report screen sees a named drug and a named Indian brand attributed to
  the AI, with no physician having signed anything.

**Consequence for Feature 2:** the feature is real and worth building, but it gates the
**AI treatment recommendation block on the worker report**, not `ClinicalReport.prescription`. The
brief's own framing (A1: gate rendering, never existence) is exactly right; it is just pointed at
the wrong object. Recommendations in Part A are re-pointed accordingly.

### C-2. There is no device-side hash chain. The hash chain is backend-only.

The brief refers to "the existing insert-only hash-chain log" as if it lives on the device.

- Device: `AuditLogEntity` (`data/local/entity/AuditLogEntity.kt`) has columns
  `id, timestamp, userId, patientId, caseRecordId, action, payload` plus sync metadata. There is
  **no `entryHash`, no `previousHash`, no `sequence`**. `grep` for `prevHash|previousHash|hashChain`
  across `app/src/main/java` returns nothing. Insert-only is enforced only by the DAO having no
  update or delete method, which is a convention, not tamper evidence.
- Backend: `backend/core/app/models/audit.py` is the hash chain. One chain per facility,
  `entry_hash`/`previous_hash` with 64-char CHECK constraints, a DB-assigned monotonic `sequence`,
  and append-only enforced in three independent places including a database trigger.
- The risk file already knows this. H-07's open-actions column reads, verbatim: "Tamper-evidence
  (hash chain) + export for review."

**Consequence:** a device audit row becomes tamper-evident **only once it syncs into the server
chain**. Every audit specification in this memo (A5, B4) should say "insert-only device audit row
that enters the server hash chain on sync push," not "hash-chained audit node." B1's question
about whether the canonical filename timestamp is "the hash-chained audit-node time" therefore has
no on-device answer: see B1 for what I recommend instead.

### C-3. There are two AGREE/MODIFY/REJECT enums, neither of them an approval state, and the decision is taken inside the worker's app with no role check.

- `PhysicianDecision { AGREE, MODIFY, REJECT }` in `domain/model/DiagnosisFeedback.kt` is the UI
  input, mirroring `refine_diagnosis.py`'s Pydantic schema for a future training reimport.
- `KernelDecision { AGREE, MODIFY, REJECT }` in `domain/model/Prescription.kt:33` is what gets
  persisted, as `PrescriptionEntity.kernelDecision`.
- `SubmitDoctorDecisionUseCase:100-106` maps one to the other one-for-one.
- Neither is an approval state on a prescription. Both describe the reviewer's verdict on the
  **AI's diagnostic candidate**. `PrescriptionEntity` has no approval column of any kind.

And the finding that matters more than either feature:

- `DoctorReviewCard` lives in `presentation/patientsummary/PatientSummaryScreen.kt:252`, inside
  the PHC worker's own app. Its only visibility condition is
  `PatientSummaryUiState.canOpenDoctorReview`, which is
  `caseStatus == CaseStatus.SENT_TO_DOCTOR && !showDoctorReviewPicker`
  (`PatientSummaryViewModel.kt:90`).
- **There is no `UserRole` check anywhere on that path.** Grepping `UserRole.` across
  `presentation/` returns only the login screen's role picker and a display-label helper. Any
  signed-in user, including `UserRole.ASHA_WORKER`, can open that card and submit AGREE, which
  writes a prescription naming the AI's recommended drug and brand.
- The screen's own KDoc calls it an "Investor-demo capture point," and
  `DoctorPrescriptionInbox`'s KDoc says the real doctor review is "out of scope for this app (no
  doctor-facing prescription-entry UI lives here)." So this is understood to be demo scaffolding.
  It is nonetheless the live path today.

**Consequence:** Feature 2 as briefed hides a prescription from a worker who, on the current
build, can approve it themself thirty seconds earlier on a different screen. Gating the render
without gating the decision surface produces a control that looks like a control and is not one.
Part A5 and Part F both treat closing this as part of Feature 2's scope, not as a follow-up.

### C-4. Existing attachments are not encrypted at rest, and half of them are not stored by this app at all.

The brief's B3 asks me to confirm the "yesterday design" of encrypted bytes in `filesDir`. The
repo does not do that today for any attachment.

- `AttachmentEntity.uri` is a **URI string**, not bytes.
- Camera path: `ConsultationScreen.kt:385-389` writes the JPEG to
  `context.cacheDir/attachments/` and hands back a `FileProvider` content URI. `cacheDir` is
  app-private but **OS-evictable**, and the bytes are plaintext.
- Gallery path: `ConsultationScreen.kt:103-108` stores the raw `MediaStore` `content://` URI from
  `PickVisualMedia`. The bytes stay wherever the user's gallery keeps them. The app holds a
  reference it may not be able to resolve later, and the PHI sits in shared storage.
- `res/xml/file_paths.xml` exposes exactly two roots, both `cache-path`: `attachments/` and
  `reports/`. There is no `files-path` entry.
- SQLCipher covers the Room database only (`di/DatabaseModule.kt:93-97`, passphrase from
  `DatabasePassphraseProvider`, Keystore-wrapped AES-256-GCM, non-exportable key). It does not
  cover any file on disk.

**Consequence:** Feature 1 is the app's **first** encrypted-PHI-at-rest file surface, not an
extension of an existing one. It cannot be described as "same as the existing image upload." It
also means the existing image/video attachment path carries an unrecorded hazard of its own; see
B9's second proposed entry.

---

# FEATURE 2: PRESCRIPTION VISIBILITY GATE

## A1. Gate rendering and visibility, never existence

**Decision (recommended):** the gate is a render-time filter on the worker-facing report. No row
is deleted, no column is nulled, no write is suppressed.

Confirmed against the real data model:

- The `Prescription` row is written once, by `SubmitDoctorDecisionUseCase`, and never rewritten.
  Nothing in the gate touches `PrescriptionRepository`, `PrescriptionDao`, or either table.
- `EvaluateReportEntity` is written by `GenerateEvaluateReportUseCase` and is the row the AI
  treatment recommendation lives in. The gate does not touch it either. That row must survive
  intact, because the reviewer needs `nlemTreatment.recommendedDrug` and `topIndianBrand` on the
  `DoctorReviewCard` in order to have anything to AGREE to (`PatientSummaryScreen.kt:296-304`
  reads exactly those fields to render "Will prescribe: ..."). Suppressing the data would break
  the approval step outright, which is the failure mode the brief names and it is a real one here.
- Sync is unaffected. `EvaluateReportDao.getPendingForSync` and the prescription sync payload
  read rows, not rendered reports.

**Where the filter goes.** Two candidate seams, and the choice matters:

| Seam | What it is | Verdict |
|---|---|---|
| `ReportCanvasRenderer` | Skip the treatment block while drawing | **Reject.** Suppression at the last drawing step means the un-gated data is still inside the `ClinicalReport` object, reachable by any other consumer, including the PDF exporter if it ever diverges. |
| `ReportFormatter.format(...)` | Do not populate the field for a gated audience | **Recommend.** This is where `ReportAudience`-based redaction already lives: `ReportFormatter.kt:150` is `entry.visibility == Visibility.PRIVATE && audience == ReportAudience.WORKER`. The gate is the same shape as an existing, working control. |

**Recommended change shape:** `ClinicalReport.evaluateOutput` is nulled (or its treatment subtree
nulled) by `ReportFormatter` when the audience is `WORKER` and the case has no committed physician
decision. The PDF exporter, the Compose preview, and any future consumer all inherit the gate for
free because they all read the same object. This matches `ClinicalReport`'s own KDoc claim that
"Both the on-screen Compose preview and the exported PDF render from THIS object."

**Flag for operator:** the brief assumed the object to gate was `ClinicalReport.prescription`. It
is `ClinicalReport.evaluateOutput`'s treatment subtree. The `prescription` list needs no gate,
because it is empty until the decision. I recommend gating both anyway, so that the control does
not silently become a no-op if the write ordering ever changes.

## A2. Approval-state mapping

There is no approval state in the repo (C-3). The mapping below is therefore a **new derived
state**, computed from what does exist, not a rename of anything.

Available signals, all real:

- `PrescriptionEntity.kernelDecision: KernelDecision?` (AGREE / MODIFY / REJECT / null)
- The existence of a `prescriptions` row for the `caseRecordId`
- `CaseRecord.status`, which `SubmitDoctorDecisionUseCase` flips to
  `CaseStatus.PRESCRIPTION_RECEIVED` via `markPrescriptionReceived` immediately after the save

**Recommended mapping:**

| Physician decision | Repo state after commit | Worker-facing report renders |
|---|---|---|
| (none yet) | no `prescriptions` row; `caseStatus == SENT_TO_DOCTOR` | No AI treatment block, no brand, no prescription. A neutral "Awaiting physician review" line in place of the section. |
| AGREE | row exists, `kernelDecision = AGREE`, status `PRESCRIPTION_RECEIVED` | Full prescription block from `Prescription.medications`, signature block, `isFinal = true`. This is already the existing rendering; the gate simply stops suppressing. |
| MODIFY | row exists, `kernelDecision = MODIFY`, medications are the reviewer's manual drug/dosage/brand | Same as AGREE. The **modified** prescription is the approved one. The AI's original recommendation is not shown alongside it on the worker report. |
| REJECT | row exists, `kernelDecision = REJECT`, `diagnosis = "Clinical assessment pending further evaluation (AI suggestion not clinically supported)"` | See below. |

**Mid-modification (uncommitted) must not be worker-visible, and structurally is not.** The
MODIFY draft lives entirely in `PatientSummaryUiState` (`manualDrugName`, `manualDosage`,
`manualBrandName` fields, edited via `ManualPrescriptionFields`). Nothing is persisted until
`onConfirmDoctorDecision` calls the use case, which does a single `runCatching` block that saves
the prescription, flips the case status, saves the `DiagnosisFeedback`, and logs the audit row.
There is no partial-write path and no draft table. The report reads from Room, so an uncommitted
modification is invisible to it by construction, not by a check. **Recommendation: state this in
the memo and the risk entry as a structural property, and add one regression test asserting a
`ClinicalReport` built mid-edit carries no prescription lines, so that a future refactor
introducing a draft table cannot quietly break it.**

**REJECT: what the worker sees. Recommendation: a referral-oriented state, not blankness.**

Three options were considered:

1. Nothing at all. **Reject.** An absent section carries no signal, which is precisely the failure
   mode H-14 was opened and closed for on the `/api/v1/evaluate` leg. Repeating it here would
   reintroduce a hazard the file already treats as closed.
2. A "no prescription" line. Better, but it tells the worker only what is missing.
3. **Recommended: a referral note.** The repo already computes this. `ReportFormatter.kt:112` sets
   `doctorRejectedKernel = prescription?.kernelDecision == KernelDecision.REJECT` and feeds it
   into `suggestsReferral`, which enables the referral button on the report screen
   (REQ-REF-01). `referralReasonSuggestion` is already populated and is documented as "never null
   so the sheet is never blank." So on REJECT the worker should see: no medication lines, an
   explicit "The physician did not endorse the AI assessment. No prescription was issued." line,
   the existing `Prescription.diagnosis` text (which on REJECT is already the honest "Clinical
   assessment pending further evaluation" string, not a fabricated diagnosis), and the
   already-enabled referral affordance.

   This is the lowest-code option of the three, because the referral wiring exists and is tested.
   The only new thing is the explicit "no prescription was issued" line.

**Note on `PhysicianDecision` versus `KernelDecision`.** The gate should read
`PrescriptionEntity.kernelDecision`, the persisted value, not `PhysicianDecision`, which only ever
exists in UI state. Reading the persisted enum keeps the gate correct for a case restored from
Room after process death, and keeps it correct if the real out-of-app doctor channel
(`DoctorPrescriptionInbox` / `ReceiveDoctorPrescriptionUseCase`) is ever wired up, since that path
also writes `kernelDecision` and never touches `PhysicianDecision`.

## A3. Cadre interaction

**Recommended position, matching the brief's default lean:** for the prescription and the AI
treatment recommendation specifically, visibility is **doctor-approval-gated for every
non-physician cadre**. Cadre creates no exception. Nobody below physician sees an unapproved
prescription, CHO included.

Rationale, grounded in `docs/domain/phc-workforce-scope.md`:

- The scope document's own tier table gives the CHO a "defined, prescribing-adjacent authority to
  supply certain medicines" under Schedule K. That is authority to **supply** within a defined
  list, not authority to **validate an AI drug recommendation for a specific patient**. Those are
  different acts, and only the second is what an ungated AI treatment block invites.
- The document names the scope edge explicitly: a CHO reading a lab report is in scope; a CHO
  interpreting a raw radiology image unsupported is at or past the edge. An unapproved,
  patient-specific AI drug and brand recommendation is closer to the second than the first.
- The asymmetry of error favours gating. Wrongly gating a CHO for the interval between "case sent"
  and "decision committed" costs a short wait on a case that is by definition already routed to a
  physician. Wrongly exposing an unapproved recommendation is the H-02 automation-bias hazard,
  which the risk file explicitly identifies as the row "the Class C determination above is centred
  on."
- Note this is the **opposite** direction from B7. There, cadre does create distinctions, because
  the content is diagnostic material a CHO is trained to read. Here the content is an unvalidated
  machine treatment output, and no cadre below physician has scope to validate it. The two
  positions are consistent: both key off what the cadre is trained and licensed to do with the
  specific content in front of them, which is what the scope document asks for.

**PROPOSED, AWAITING OPERATOR CONFIRMATION.** This is a scope-of-practice judgement, not a
technical one, and per the scope document's own instruction the binding version belongs in
`docs/quality/risk-management-file.md` as an operator-signed entry.

**Implementation consequence:** the gate needs no `UserRole` read at all in its v1, because the
answer is the same for all four current roles when the case lacks a committed decision, and the
same for all four when it has one. That is a genuine simplification and it should be taken. What
it does **not** excuse is C-3: the decision surface itself needs a role gate, and that is separate
work described in A5 and Part F.

## A4. The feature flag

Existing convention (`app/src/main/java/com/example/samdapp/config/FeatureFlags.kt`): a single
`object FeatureFlags` holding `const val <NAME>_ENABLED`, each with a KDoc stating what "off"
means behaviourally and naming the screen or use case the flag governs. Only
`SCREEN_SECURITY_ENABLED` is flavor-gated through `buildConfigField`; everything else is a
compile-time `const val`.

**Recommended name and default:**

```
/** Hides the AI treatment recommendation and any prescription from the worker-facing report
 *  until a physician decision has been committed for the case (AGREE / MODIFY / REJECT via
 *  SubmitDoctorDecisionUseCase). Off = prior behaviour: the /api/v1/evaluate treatment block
 *  and brand suggestion render on the worker report as soon as evaluate returns.
 *  See [com.example.samdapp.domain.report.ReportFormatter]. */
const val PRESCRIPTION_APPROVAL_GATE_ENABLED = true
```

**Default: `true`, gate ON.** Hidden-until-approved is the safe default, and the flag exists so
the operator can turn the control **off** for a demo, not so the control has to be turned on to
exist. This matches how `VOICE_INPUT_ENABLED = false` is used: the safe state is the default and
the flag documents what flipping it would expose.

**Clean revert confirmed.** With the gate implemented as a single conditional inside
`ReportFormatter.format(...)` that decides whether to populate `evaluateOutput` and
`prescription`, flipping the flag `false` restores the exact current behaviour: the formatter
populates both unconditionally, `ReportCanvasRenderer` draws the treatment block as it does today,
and the PDF is byte-identical to today's for the same input. No schema change, no migration, no
persisted state, nothing to roll back. This is the property that makes Feature 2 the safe first
build.

## A5. Audit

Convention: `AuditAction` is a closed enum in `domain/audit/AuditLogger.kt` whose KDoc states the
enforcement rationale explicitly (`log()` takes `AuditAction`, not `String`, so a free-text literal
cannot reach a call site). Each entry carries an explicit wire `value` string that must never be
renamed. Payloads are built with `auditPayload(vararg Pair<String, String?>)`.

**Critical process constraint, from the `VOICE_FIELD_*` KDoc:** a device action the backend mirror
does not accept is "a silent, permanent sync rejection of every row the device sends." So any new
`AuditAction` must land on the device enum **and** in
`backend/core/app/domain/audit_actions_device.py` **in the same commit**, with the existing
set-agreement test (`test_audit_actions_device.py`) proving them equal.

**Recommended new actions (2):**

```
/** The physician's decision was committed and the prescription became worker-visible. Emitted
 *  once, at the commit, not on every subsequent render. */
PRESCRIPTION_APPROVED("prescription_approved"),

/** A worker-facing report was assembled with the approval gate satisfied, i.e. an approved
 *  prescription was actually surfaced to a non-physician. Payload carries the decision and the
 *  viewer's role, never the drug name. */
PRESCRIPTION_SURFACED_TO_WORKER("prescription_surfaced_to_worker"),
```

Payload shapes:

- `PRESCRIPTION_APPROVED`: `auditPayload("kernelDecision" to decision.name, "prescriptionId" to
  prescription.id, "medicationCount" to lines.size.toString())`. Emitted from
  `SubmitDoctorDecisionUseCase`, immediately after the existing
  `DIAGNOSIS_FEEDBACK_RECORDED` call. Note that `DIAGNOSIS_FEEDBACK_RECORDED` already fires there
  and already carries `physicianDecision` and `medicationGenericName`, so this second row is
  arguably redundant. **Recommendation: emit it anyway.** `DIAGNOSIS_FEEDBACK_RECORDED` is
  semantically about the training-reimport record, and the risk file will cite the approval event
  as a risk control. A control should not depend on reading a row whose documented purpose is
  something else.
- `PRESCRIPTION_SURFACED_TO_WORKER`: `auditPayload("kernelDecision" to ..., "viewerRole" to
  session.role.name, "caseRecordId" to ...)`. Emitted from `ReportViewModel`'s init block, once
  per report load, only when the gate resolved to "show." **Never carries the drug name**, matching
  the `VOICE_FIELD_*` precedent of logging measured metadata and provenance rather than clinical
  content.

**Both are device rows with no tamper evidence until they sync** (C-2). Say so in the risk entry;
do not claim hash-chained on-device audit.

**Also required, and this is the part that makes the control real:** a `UserRole` gate on
`canOpenDoctorReview` so that a worker cannot commit the decision they are being shielded from.
Recommended condition: `caseStatus == SENT_TO_DOCTOR && session.role == UserRole.DOCTOR`. This is
one boolean in `PatientSummaryViewModel.kt:90` plus a session read the ViewModel does not currently
take. It should ship inside Feature 2's flag, so that flipping the flag off restores the demo
behaviour in one place rather than two.

**Caveat the operator must weigh:** `UserRole` is self-asserted at login.
`MockAuthSession.signIn` performs no credential check (H-06, open: "anyone typing an existing
worker's name+role gets that worker's `userId`"). So the role gate is an accountability and
intent control, not an access control, and the risk entry must say that rather than overclaim.
It still meaningfully removes the "any worker can tap AGREE on the screen they are already on"
path, which is the realistic failure, and it makes a bypass a deliberate act that leaves a
`userId` in the audit trail.

## A6. PROPOSED risk-file entry, Feature 2

> **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-03 by a design pass; no
> operator has reviewed, accepted, or signed this entry, and its presence in a scratchpad memo
> confers no status in the risk management file.**

| ID | Hazard | Harm | Sev | Prob | Controls (PROPOSED) | Open |
|---|---|---|---|---|---|---|
| H-16 (proposed) | A PHC worker acts on an AI-generated drug and brand recommendation that no physician has reviewed | The worker administers, dispenses, or advises a medicine chosen by a model, outside their scope of practice and without the human-in-the-loop step that H-02's Class determination depends on. Direct patient harm from a wrong or contraindicated drug; a second, structural harm in that the software has enabled prescribing-shaped action by an unqualified user, which is the exact escalation direction `docs/domain/phc-workforce-scope.md` names | High | **Not established.** Reachable on the current build (`ReportCanvasRenderer.kt:385-411` renders `nlemTreatment.recommendedDrug` and `topIndianBrand` on the only audience the app constructs, `ReportAudience.WORKER`, as soon as `/api/v1/evaluate` returns). Whether a worker acts on it is a field-behaviour question no evidence in this repo speaks to, and this row declines to assert a probability | (i) **Render gate at the formatter.** `ReportFormatter` does not populate `ClinicalReport.evaluateOutput`'s treatment subtree or `prescription` for a `WORKER` audience until a `prescriptions` row with a non-null `kernelDecision` exists for the case. Applied at the single object both the Compose preview and the PDF exporter read, so the two surfaces cannot diverge. Behind `FeatureFlags.PRESCRIPTION_APPROVAL_GATE_ENABLED`, default `true`. (ii) **Data is gated, never suppressed.** No row is deleted or nulled; the reviewer's `DoctorReviewCard` continues to read the same `EvaluateReportEntity`, so the approval step it exists to enable is not broken by its own control. (iii) **`UserRole.DOCTOR` gate on the decision surface**, so the person shielded from the recommendation is not also the person who can approve it. (iv) **Honest REJECT state.** A rejected case shows an explicit "no prescription was issued" line plus the existing referral affordance, not an absent section, so absence carries signal (the failure mode H-14 exists to prevent). (v) **Two audit actions**, `prescription_approved` at the commit and `prescription_surfaced_to_worker` at the render, device rows entering the server hash chain on sync push | **Open: the role gate is not an access control.** `MockAuthSession.signIn` has no credential check (H-06), so `UserRole.DOCTOR` is self-asserted. The gate removes the incidental path, not a determined one, and does not close H-06. **Open: no evidence of field behaviour.** No basis exists in this repo for a probability, and none is asserted. **Open: the out-of-app doctor channel does not exist.** `DoctorPrescriptionInbox` is an interface with a mock implementation; the real physician review is still `DoctorReviewCard` inside the worker's own app. Until a genuine second-party review channel exists, this control constrains **when** the in-app reviewer's verdict becomes visible, and does not establish **who** the reviewer was. That limitation belongs to H-02 and H-06, is not closed here, and this entry must not be read as closing it |

---

# FEATURE 1: CONSULTATION DOCUMENT UPLOAD

## Linkage: consultation-primary. CONFIRMED against the repo.

`AttachmentEntity` (`data/local/entity/AttachmentEntity.kt`) has `consultationId` as its only
clinical foreign key, indexed with `@Entity(..., indices = [Index("consultationId")])`. It carries
no `patientId`. `AddAttachmentUseCase` takes `(consultationId, type, uri)`. The patient is derived
through `Consultation.patientId`, and the case through `Consultation.encounterId`.

So consultation-primary with derived patient linkage is not a new decision, it is the existing
shape, and the brief is right that this reverses an earlier patient-primary assumption. **The new
document table should mirror `AttachmentEntity` exactly on this point: `consultationId` mandatory
and indexed, `patientId` denormalised and nullable-never but explicitly documented as derived.**

One caveat worth recording. Denormalising `patientId` onto the document row is what makes
DPDP erasure propagation tractable later (you can find every artifact for a patient without a
join through consultations), and it is what makes a wrong-attachment audit query cheap. The cost
is that it can drift from `Consultation.patientId`. **Recommendation: store it, write it once at
insert from the consultation, never update it, and add a test asserting the two agree.** The
consultation is the source of truth; the column is a lookup key.

## B1. Naming: two names, kept separate

**Decision (recommended):**

- **`label: String`** is the worker's free-text human name. Metadata column. Rendered in the UI
  and on the report. **Never** touches the filesystem, never touches a storage key, never
  interpolated into SQL or a path.
- **`canonicalName: String`** is device-derived and is the storage key.

**Recommended pattern:**

```
<sanitizedLabel>_<epochMillis>_<uuid>.<ext>
```

- `sanitizedLabel`: `label.take(32).replace(Regex("[^A-Za-z0-9_-]"), "_")`. This is the exact
  sanitiser already used in `ReportPdfExporter.export` for the report filename
  (`report.header.consultationRecordNo.replace(Regex("[^A-Za-z0-9_-]"), "_")`). Reusing it is the
  right call: one sanitiser, already in the codebase, already exercised.
- `epochMillis`: `Instant.now().toEpochMilli()`.
- `uuid`: `UUID.randomUUID().toString()`. This alone makes collision impossible, which is why the
  sanitised label can be truncated aggressively without risk.
- `ext`: derived from the **validated** MIME type (B6), never from the user's filename.

Worked example: label `"Chest X-ray 12/08 (Dr. Rao)"` becomes
`Chest_X-ray_12_08__Dr__Rao__1756876543210_9f3c1a70-....pdf`.

**On the timestamp question (B1 asks whether it is the hash-chained audit-node time).** It cannot
be, on device: there is no device hash chain (C-2), so there is no audit-node time to reference. It
also should not be, even once one exists. Recommended split:

- The **filename** timestamp is a plain `epochMillis`, present only for human sortability in a
  file listing during debugging. It carries no evidentiary weight and should not be described as
  if it does.
- The **evidentiary** timestamp is `uploadedAt` on the metadata row, mirrored into the
  `document_uploaded` audit row's `timestamp`, which becomes tamper-evident when that row syncs
  into the server chain and receives its `sequence`, `entry_hash`, and `recorded_at`. The server
  model's KDoc makes exactly this point: without both `occurred_at` and `recorded_at`, "an offline
  capture synced two days later is indistinguishable from a backdated record."

Rationale for keeping the two names apart, stated for the record: a worker-typed string used as a
storage key is a path-traversal surface (`../`), a filesystem-illegal-character surface, a length
surface, a collision surface when two workers both type "xray", and a log-injection surface. None
of that is hypothetical for free text typed on a phone in a field setting.

## B2. Camera-to-PDF consolidation

**Library: `android.graphics.pdf.PdfDocument`. No new dependency.**

The repo already builds real PDFs with it (`ReportPdfExporter`), and that file's KDoc records the
reason explicitly: "no external PDF library, no iText/AGPL exposure (REQ-RPT-02)." Pulling in
PdfBox-Android or iText for this feature would contradict a recorded requirement and add an AGPL
question the project deliberately avoided. `PdfDocument` writes multi-page PDFs, one
`startPage`/`finishPage` per image, which is precisely the shape needed here.

### Encrypt-when, and the ordering decision

**Decision: pages are encrypted individually as captured, then decrypted one at a time during
assembly, and the assembled PDF is encrypted before the page files are securely deleted.**

Assembled-then-encrypted is simpler and it is wrong here, because it leaves N plaintext JPEGs
sitting on disk for the entire duration of a multi-page capture. That window is not short: it
spans every "add another page?" loop iteration, plus whatever happens if the worker is
interrupted, plus the app being backgrounded or killed mid-capture. Plaintext PHI persisting
across a process death is the specific failure this feature exists to avoid, and it is exactly the
posture the existing camera path already has (C-4). Repeating it for documents would ship the
known problem into the new surface.

The cost of per-page encryption is real and should be stated: assembly must decrypt each page to
draw it, so plaintext bytes exist in memory during assembly. That is unavoidable for any on-device
PDF assembly and is a much smaller window than plaintext on disk.

### Memory, honestly

This is the part most likely to fail in the field, so it gets stated plainly rather than waved at.

A modern phone camera produces 12 to 50 megapixel JPEGs. Decoded to an `ARGB_8888` `Bitmap`, a
12 MP image is roughly 48 MB in memory. Five pages fully decoded at once is 240 MB and will
`OutOfMemoryError` on the low-end hardware a PHC actually has.

**Recommended approach, and it is the only one I would ship:**

1. **Downscale at decode.** Use `BitmapFactory.Options.inSampleSize`, computed from the page
   dimensions against the target PDF page size, so the full-resolution bitmap is never allocated.
   A document page rendered at roughly 200 dpi onto an A4-shaped page is legible for a scanned lab
   report and is a small fraction of the memory.
2. **One page in memory at a time.** Decrypt page N, decode downscaled, `startPage`, draw,
   `finishPage`, `bitmap.recycle()`, then page N+1. Peak memory is one downscaled bitmap plus
   `PdfDocument`'s own page buffer, not N of anything.
3. **Stream the output.** `document.writeTo(encryptingOutputStream)` rather than materialising the
   PDF in a `ByteArray`. `ReportPdfExporter` already does `file.outputStream().use {
   document.writeTo(it) }`; the same shape with a `CipherOutputStream` wrapper.
4. **Cap page count.** Recommend **20 pages**. Past that the assembled PDF becomes a sync and
   storage problem regardless of memory.

**Failure modes I expect and would not hide:**

- `PdfDocument` holds finished pages in memory until `writeTo`. For 20 downscaled pages this is
  acceptable; for 20 full-resolution pages it is not. This is a second, independent reason
  downscaling is mandatory rather than an optimisation.
- Assembly is not instant. Twenty pages of decrypt, decode, draw is seconds, not milliseconds. It
  must run off the main thread with visible progress, and it must be cancellable.
- If assembly fails midway, the partially-written output must be deleted, not left. Follow
  `ReportPdfExporter`'s `try/finally { document.close() }`, and add explicit deletion of the
  partial output on the failure path.
- **A page whose bytes fail to decrypt or decode must abort the whole assembly with a clear error,
  never skip silently.** A lab report missing page 3 with no indication is a clinical hazard and
  is called out in the proposed risk entry (B9).

### Mid-capture abandon

**Decision: discard all pages. No draft is kept.**

Rationale: a partially captured document is not a clinical artifact, it is an incomplete one, and
keeping it means keeping encrypted PHI on disk with no metadata row explaining what it is, no
audit entry, and no owner. That is a worse posture than losing three photos. It also matches how
the rest of the app behaves; consultation attachments are held in
`PatientSummaryUiState.pendingAttachments` and only committed on save
(`ConsultationViewModel.kt:556-565`).

Implementation: the in-progress page list lives in ViewModel state referencing encrypted temp
files under a per-session directory. On abandon (back navigation, cancel, or process death
recovery) the directory is deleted. Add a startup sweep that deletes any orphaned capture-session
directory, because process death will not run the cancel path.

**Confirm before backing out.** A worker who has taken four pages and taps back should get "Discard
4 pages?" and not silently lose the work. That is the difference between a safe default and a
hostile one.

### Reorder and delete before finalising

**Yes, both. This is required, not optional.** Page order is clinical meaning in a multi-page lab
report, and a mis-ordered or duplicate-page document is one of the hazards in B9. A capture loop
that cannot fix a fumbled page forces the worker to abandon and start over, which in practice means
they will keep the bad document instead.

Minimum viable surface: a thumbnail strip showing captured pages in order, with per-page delete
and drag-to-reorder, plus "add another page" and "done". Reordering is a list operation on the
ViewModel's page list; the assembly loop reads the list in its final order. No extra storage
mechanics.

## B3. Storage mechanics

### Recommendation: encrypted bytes in `filesDir`, metadata row in the SQLCipher Room database. Not a BLOB.

| | Encrypted file in `filesDir` | SQLCipher BLOB |
|---|---|---|
| Encryption at rest | AES-256-GCM via a Keystore-wrapped key, same trust root as the DB passphrase | SQLCipher, already there |
| Multi-MB payloads | Streamable; memory stays bounded | Room loads a BLOB fully into memory on read; a 10 MB PDF is a 10 MB allocation per read |
| DB size | Unaffected | Grows unboundedly, and every `sqlcipher_export` migration path in `DatabasePassphraseProvider.migratePlaintextToEncrypted` has to copy it |
| Backup and sync | Bytes are a separate artifact, which is what an eventual S3-shaped push wants | Bytes are entangled with the clinical DB |
| Secure delete for retract | Delete or overwrite one file | `DELETE` leaves the page in the SQLite freelist until vacuum |

The retract requirement (B5) alone settles it: deleting a file is a real deletion, deleting a BLOB
row is not, and `VACUUM` on an encrypted multi-hundred-MB database on a field phone is not a
plan.

**Concrete storage design:**

- Directory: `context.filesDir/documents/<consultationId>/`. **`filesDir`, not `cacheDir`**, because
  `cacheDir` is OS-evictable and losing a lab report because the phone needed space is data loss of
  a clinical record. Note this is a deliberate divergence from the existing camera attachment path,
  which uses `cacheDir` (C-4).
- **Never** external storage, `MediaStore`, or a shared-storage `Uri`. No `MANAGE_EXTERNAL_STORAGE`,
  no `WRITE_EXTERNAL_STORAGE`.
- **Do not add a `files-path` entry to `res/xml/file_paths.xml` for this directory.** The existing
  file only exposes two `cache-path` roots. Adding a `FileProvider` path for documents would make
  encrypted PHI grantable to other apps, which is the opposite of the intent. Viewing is in-app
  (B6).
- Key: a **separate** AES-256-GCM Keystore key, alias `samd_document_key`, generated exactly as
  `DatabasePassphraseProvider.getOrCreateSecretKey` does (`KeyGenParameterSpec`, `BLOCK_MODE_GCM`,
  `ENCRYPTION_PADDING_NONE`, 256-bit, non-exportable). Separate from `samd_db_passphrase_key` so
  that a document-key rotation or loss does not take the database with it.
- Per-file random IV, stored as the first 12 bytes of the file, matching how
  `DatabasePassphraseProvider` stores `cipher.iv` alongside its ciphertext. GCM gives
  authenticated encryption, so a corrupted or tampered file fails to decrypt rather than
  silently producing garbage that gets rendered as a clinical document.

**Inherited failure mode that must be handled explicitly.** `DatabasePassphraseProvider` already
documents the case where the Keystore key is gone but the stored ciphertext remains, after a
device transfer or backup restore, and its answer is to clear and start clean because the old data
is unrecoverable. The same will happen to documents. **Recommendation: a decrypt failure on a
document must surface as an explicit "this document cannot be opened on this device" error with an
audit row, never as a silent empty view.** A clinician seeing a blank document viewer must not be
able to mistake it for a document with nothing on it.

### Metadata row: `consultation_documents`

Columns, following existing entity conventions exactly (`@PrimaryKey val id: String`, `Instant`
timestamps via the existing `Converters`, and the four-field sync block plus `localModifiedAt`
that every syncable entity in this schema carries):

| Column | Type | Notes |
|---|---|---|
| `id` | `String` PK | UUID |
| `consultationId` | `String`, indexed | **Primary linkage.** Mirrors `AttachmentEntity` |
| `patientId` | `String`, indexed | Derived at insert from the consultation. Never updated |
| `label` | `String` | Worker free text. Metadata only, never a path |
| `canonicalName` | `String` | The storage key (B1) |
| `mimeType` | `String` | The **validated** type (B6), not the picker's claim |
| `sizeBytes` | `Long` | Of the plaintext |
| `sha256` | `String` | Of the **plaintext** bytes, hex. Integrity witness independent of the cipher |
| `source` | `String` | `DIRECT_FILE` or `CAMERA_ASSEMBLED`. Provenance, same spirit as `FieldProvenance` and `InferenceSource` |
| `pageCount` | `Int?` | Non-null for `CAMERA_ASSEMBLED` |
| `uploadedAt` | `Instant` | Evidentiary timestamp (B1) |
| `uploaderUserId` | `String` | From `AuthSession.currentUser()`, the opaque id, matching how `RoomAuditLogger` does it |
| `uploaderRole` | `String` | `UserRole` at upload. Needed for B7 and for the audit story |
| `retractedAt` | `Instant?` | Non-null means retracted (B5). The row itself is never deleted |
| `retractionReason` | `String?` | Optional |
| `syncState`, `serverVersion`, `syncErrorCode`, `lastSyncAttemptAt`, `localModifiedAt` | | Verbatim from the existing entity pattern |

**On `sha256` of plaintext versus ciphertext.** Plaintext. The hash is there to answer "are these
the bytes that were uploaded," which must survive a re-encryption or a key rotation. GCM's own auth
tag already covers ciphertext integrity.

### Schema version and migration

**Current DB version is 17.** `AppDatabase.kt:73` declares `version = 17`; `Migrations.kt:468` is
`MIGRATION_16_17` (adds `impactOnDailyActivitiesProvenance` to `consultations` and backfills
`'TYPED'`); `DatabaseModule.kt:97` registers the full chain through `MIGRATION_16_17`.

**Feature 1 adds `MIGRATION_17_18`**, bumping `AppDatabase` to `version = 18`. Purely additive: one
`CREATE TABLE IF NOT EXISTS consultation_documents (...)` plus two `CREATE INDEX IF NOT EXISTS`
statements on `consultationId` and `patientId`. No existing table is altered and no data is
rewritten, so it is the same low-risk shape as `MIGRATION_2_3`'s new-table work. Register it in
`DatabaseModule.addMigrations(...)` in the same commit; `exportSchema = true` means the JSON schema
file must be committed too.

## B4. Audit, including VIEW

Three device audit actions, all insert-only rows entering the server hash chain on sync push
(C-2), all added to the device enum and
`backend/core/app/domain/audit_actions_device.py` in the same commit:

```
DOCUMENT_UPLOADED("document_uploaded"),
DOCUMENT_VIEWED("document_viewed"),
DOCUMENT_RETRACTED("document_retracted"),
```

- `DOCUMENT_UPLOADED`: `auditPayload("documentId" to id, "source" to source, "mimeType" to
  validatedMime, "sizeBytes" to size.toString(), "pageCount" to pageCount?.toString(), "sha256" to
  sha256, "uploaderRole" to role.name)`. **Never the label**, which is worker free text and may
  contain a patient name; the same reasoning that keeps transcripts out of the `VOICE_FIELD_*`
  payloads.
- `DOCUMENT_VIEWED`: `auditPayload("documentId" to id, "viewerRole" to role.name, "visibilityTier"
  to tier)`. Emitted when the viewer actually renders the decrypted content, **not** when a list
  row is drawn. A view audit that fires on scroll is noise and makes the real signal unfindable.
  This is the DPDP and HIPAA access-logging requirement, and it is the reason B7's role gate has
  anything to show for itself after the fact.
- `DOCUMENT_RETRACTED`: `auditPayload("documentId" to id, "reason" to reason, "actorRole" to
  role.name, "bytesDeleted" to deleted.toString())`.

The timestamp is `AuditLogEntity.timestamp`, written by `RoomAuditLogger` from `Instant.now()`,
never a mutable column on the document row. `uploadedAt` on the document row is a convenience
denormalisation of the upload event's audit timestamp, and the audit row is the record of truth.

## B5. Retract

**Decision: in scope now. Insert-only semantics preserved.**

- The metadata row is **never deleted**. `retractedAt` is set, and optionally `retractionReason`.
- A `DOCUMENT_RETRACTED` audit row is inserted. The `DOCUMENT_UPLOADED` row it supersedes stays
  exactly where it is. The chain records that a document existed, who attached it, and that it was
  retracted, by whom and when.
- The encrypted bytes **may** be securely deleted. Recommend: delete them. The clinical reason to
  retract is nearly always wrong-patient or wrong-case attachment, and in that case the bytes are
  PHI sitting against the wrong record, which is the harm. Keeping them "for the record" would
  preserve the exact thing that made retraction necessary.
- Deletion is `File.delete()`. On the flash storage these devices use, that does not physically
  erase, and the memo should not pretend otherwise. What makes it adequate is that the bytes were
  never plaintext on disk: what remains recoverable is ciphertext for a key that a subsequent key
  rotation can invalidate. **State this limitation in the risk entry rather than claiming secure
  erasure.**
- A retracted document does not render on any report and does not sync its bytes. `DocumentDao`
  read queries filter `retractedAt IS NULL` by default, with an explicit
  `observeIncludingRetracted` for the audit or admin view. Follow the `AuditLogDao` precedent: the
  DAO exposes no update or delete for the retraction, only an `UPDATE ... SET retractedAt` that is
  the retraction itself.

**Who can retract.** Recommendation: the uploader, plus any physician-tier user, and nobody else.
`uploaderUserId` is on the row for exactly this. **PROPOSED, awaiting operator confirmation**, and
subject to the same H-06 caveat as A5: role is self-asserted.

## B6. File type and content risk

**Whitelist: PDF, JPEG, PNG. Nothing else.** Rejected explicitly: Office documents, HTML, SVG (an
XML format with script and external-entity surface), TIFF, HEIC, ZIP-shaped containers.

**Validation is by magic bytes, not extension and not the picker's claimed MIME type.**
`ContentResolver.getType()` returns what the providing app asserts, and the existing image path
already trusts it (`ConsultationScreen.kt:105`: `if (mimeType.startsWith("video"))`). For documents
that is not sufficient.

Read the first bytes of the stream and require an exact match:

| Type | Magic | Notes |
|---|---|---|
| PDF | `25 50 44 46 2D` (`%PDF-`) at offset 0 | |
| JPEG | `FF D8 FF` at offset 0 | |
| PNG | `89 50 4E 47 0D 0A 1A 0A` at offset 0 | |

The validated type is what gets stored in `mimeType` and what determines the file extension. A
mismatch between the claimed and the detected type is a rejection with a clear message, and worth
an audit row, since a systematic mismatch is a signal about either a broken picker or something
worse.

**Size cap: 20 MB per document.** Rationale: a 20-page camera-assembled PDF at the downscaled
resolution from B2 lands well under this; a scanned multi-page lab report from a hospital PDF is
typically a few MB; and 20 MB is a payload an eventual sync push can carry over a rural
connection without a resumable-upload design that does not exist yet. The cap is enforced before
any bytes are written, by checking the source length first and then enforcing again while
streaming, because a content provider can lie about length.

**Viewing is a safe in-app render. No external handler, ever.**

- **PDF: `android.graphics.pdf.PdfRenderer`**, the platform API, which rasterises a page to a
  `Bitmap`. It executes no embedded JavaScript, follows no embedded links, loads no external
  resources, and submits no forms. It is the counterpart to the `PdfDocument` writer already in
  the codebase, so both directions of the PDF path are platform code with no third-party parser.
- Images: `BitmapFactory` with `inSampleSize`, same as the assembly path.
- **Never** `Intent.ACTION_VIEW` on a document, and no `FileProvider` grant for the documents
  directory (B3). Handing an encrypted PHI file to whatever PDF app the worker has installed
  defeats the storage design and every access audit built on top of it.
- `PdfRenderer` needs a `ParcelFileDescriptor` on a real, seekable file, so viewing requires
  decrypting to a temp file. **That temp file must live in `context.cacheDir`, be deleted in a
  `finally` block, and be swept on app start.** This is the one place plaintext PHI touches disk in
  the whole design, and it should be called out as such rather than buried. `FLAG_SECURE` already
  applies to patient-data screens through `FeatureFlags.SCREEN_SECURITY_ENABLED` in
  staging and prod, so the viewer inherits screenshot protection if it is registered in
  `requiresScreenSecurity`, and **it must be**.

## B7. Role visibility over uploaded clinical documents

This is the content `docs/domain/phc-workforce-scope.md` was written for, and it says so: "any
future document-visibility gate over interpretive clinical content."

**PROPOSED position, awaiting operator sign-off, matching the document's own recorded lean:**

| Tier | Cadre (current `UserRole`) | Proposed access to an uploaded imaging or lab document |
|---|---|---|
| 1 | Physician (`DOCTOR`) | Full: open and read the raw artifact |
| 1 | CHO / MLHP (**no current enum value**) | Full for **lab reports**. For **raw imaging**, open with a scope advisory, per the document's own scope-edge finding that a CHO reading a lab report is in competency and interpreting a raw radiology image unsupported is at or past the edge |
| 2 | `NURSE`, `COMPOUNDER` (Staff Nurse, Pharmacist, Lab Technician) | Metadata and the fact of attachment. Not the interpretive content. A lab technician generating a result is a different act from interpreting one, per the document |
| 3 | `ASHA_WORKER` (ANM / MPHW / ASHA) | Abstracted or referral-oriented view only: that a document exists, its label, that a physician has it. Never the raw artifact |

**This cannot be implemented correctly against the current `UserRole` enum, and that is the
finding.** `UserRole { ASHA_WORKER, NURSE, COMPOUNDER, DOCTOR }` has no CHO value, and CHO is the
tier-1 cadre the whole distinction turns on. It also collapses Staff Nurse, Pharmacist and Lab
Technician into two values that do not map onto the tier structure. The scope document names this
directly: the enum is "a flat enum with no scope-of-practice structure behind it."

**Recommendation: do not silently code a gate against the wrong enum.** Two acceptable paths, and I
recommend the second:

1. Ship documents with a conservative interim gate: raw content visible to `DOCTOR` only,
   everyone else gets label and existence. Safe, and wrong in the direction the scope document
   warns about, since it blocks a CHO from a lab report she is trained to read, which is
   "clinically obstructive, and a usability problem the field staff will route around."
2. **Recommended: model the cadre scope first.** Introduce a `CadreScope` concept (or extend
   `UserRole` with the missing values and a scope mapping) as a small, separate, testable piece of
   work, then build the document gate against it. This is a bounded change, it is the thing the
   scope document was written to enable, and both features want it. Feature 2's `UserRole.DOCTOR`
   check in A5 also becomes more honest under it.

Either way the binding decision is an operator-signed entry in
`docs/quality/risk-management-file.md`, not a lean in a memo and not a quietly-shipped constant.
That is the scope document's own stated rule.

## B8. Build now versus defer to production, explicit

**"AWS will handle it" is false for every row in the BUILD NOW column.** These are device-side
properties. There is no server involved at the moment a worker photographs a lab report onto a
phone that then goes offline for two days.

| Item | Verdict | Why |
|---|---|---|
| AES-256-GCM encryption at rest under a Keystore key | **BUILD NOW** | The device is the threat surface (H-04, lost or stolen device). A server cannot encrypt bytes that never left the phone |
| `filesDir` only, never external, `MediaStore`, or a `FileProvider` grant | **BUILD NOW** | Storage location is a device decision made at write time and cannot be retrofitted for bytes already written |
| Magic-byte type validation and the size cap | **BUILD NOW** | The rejection has to happen before the bytes are stored, on device |
| Safe in-app viewer (`PdfRenderer`, no external handler) | **BUILD NOW** | Viewing is entirely on-device |
| Upload, VIEW and RETRACT audit rows | **BUILD NOW** | The event happens on device, frequently offline. An unlogged view is unrecoverable later |
| Retract with insert-only semantics | **BUILD NOW** | Wrong-patient attachment is a day-one field reality |
| Role-visibility gate (subject to B7's enum blocker) | **BUILD NOW** in shape, sequenced after cadre modelling | It is a classification-boundary control per the scope document |
| Consultation-primary linkage and the derived patient key | **BUILD NOW** | Schema |
| SHA-256 integrity witness | **BUILD NOW** | Computed over the bytes at upload; cannot be reconstructed later |
| Push of document bytes to AWS or S3 | **PRE-PRODUCTION GATE** | No transport exists. `DoctorPrescriptionInbox` and the sync layer show the established pattern: an interface with a mock, swapped at one binding |
| DPDP erasure propagation to server-held copies | **PRE-PRODUCTION GATE** | Needs a server that holds copies. The device half (retract plus local deletion) ships now, and `patientId` on the row is what makes the propagation query tractable later |
| Formal retention and disposal schedule | **PRE-PRODUCTION GATE** | A policy decision with an operator owner, not a code decision |
| Server-side malware or content scanning | **PRE-PRODUCTION GATE** | Genuinely a server capability. The device-side whitelist and magic-byte check are not a substitute for it and are not claimed to be |
| Key rotation and re-encryption procedure | **PRE-PRODUCTION GATE** | Note the interaction with B5: retraction's deletion guarantee is strengthened by rotation, so this gate has a control depending on it and the risk entry should say so |

## B9. PROPOSED risk-file entries, Feature 1

> **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-03 by a design pass; no
> operator has reviewed, accepted, or signed these entries.**

### H-17 (proposed): new PHI-at-rest surface, uploaded consultation documents

| Field | Content |
|---|---|
| **Hazard** | Uploaded clinical documents (lab reports, imaging, discharge summaries) introduce the app's first encrypted-file-at-rest PHI surface, with six distinct failure directions: (a) PHI written to a location that survives uninstall or is readable by other apps or by ADB; (b) a document attached to the wrong patient or the wrong case; (c) a document opened by someone whose access is never recorded; (d) a malicious or malformed file processed by a parser; (e) a document whose interpretive content is opened by a cadre without scope of practice to read it; (f) a page silently lost, duplicated, or mis-ordered in a camera-assembled PDF |
| **Harm** | (a) and (c): privacy harm under DPDP, plus an accountability gap. (b): a clinician reads another patient's imaging against this patient's case, the wrong-patient harm of H-03 through a new route. (d): device compromise, or a document that renders as something other than what was uploaded. (e): clinical interpretation outside scope of practice, which `docs/domain/phc-workforce-scope.md` identifies as a Class-C-shaped escalation rather than Class B. (f): a physician reads an incomplete lab report believing it complete, and the absence carries no signal |
| **Severity** | High. (b), (e) and (f) each reach a wrong clinical action |
| **Probability** | **Not established.** The feature does not exist yet and no field data bears on it. This row declines to assert one, following H-15's precedent |
| **Proposed controls** | (1) **AES-256-GCM at rest** under a dedicated non-exportable Keystore key (`samd_document_key`), separate from the SQLCipher passphrase key, per-file random IV, authenticated so tampering fails closed. (2) **`context.filesDir/documents/<consultationId>/` only.** Never external storage, never `MediaStore`, no `FileProvider` path entry, and deliberately **not** `cacheDir`, whose OS-evictability would make a lab report silently disappear. (3) **Consultation-primary linkage** mirroring `AttachmentEntity`: `consultationId` mandatory and indexed, `patientId` derived at insert and never updated, with a test asserting agreement with the parent consultation. (4) **Magic-byte validation** against a PDF/JPEG/PNG whitelist, with the validated type (never the picker's claim) determining both the stored MIME type and the file extension; 20 MB cap enforced before any write. (5) **Safe in-app viewer only:** platform `PdfRenderer`, no embedded execution, no `ACTION_VIEW`, no external handler, decrypt-to-`cacheDir` temp deleted in `finally` and swept at startup, screen registered in `requiresScreenSecurity` so `FLAG_SECURE` applies in staging and prod. (6) **Three audit actions** (`document_uploaded`, `document_viewed`, `document_retracted`), view-auditing at actual render and not at list draw, payloads carrying provenance and measured metadata but never the worker's free-text label. (7) **Insert-only retract:** `retractedAt` set, metadata row never deleted, both audit rows preserved, encrypted bytes deleted. (8) **SHA-256 of plaintext** stored at upload as an integrity witness independent of the cipher. (9) **Camera assembly integrity:** page-count and per-page decrypt verified before assembly; a page that fails to decrypt or decode **aborts the assembly with an explicit error** and never produces a short document; reorder and per-page delete available before finalisation; mid-capture abandon discards all pages with a confirmation prompt. (10) **Cadre role-visibility gate** per B7, sequenced after cadre modelling |
| **Open** | **Open: `UserRole` cannot express the gate.** The enum has no CHO value, which is the cadre the tier-1 distinction turns on, and collapses tier 2 into two values that do not map to the scope structure. Control (10) is not implementable as specified until the cadre scope is modelled, and shipping a `DOCTOR`-only interim gate is clinically obstructive in the direction the scope document warns about. **Open: deletion is not physical erasure.** `File.delete()` on flash leaves recoverable ciphertext; adequate only because plaintext never reached disk, and fully closed only by a key-rotation procedure that is a pre-production gate. **Open: one plaintext window on disk remains,** the `PdfRenderer` decrypt-to-temp path, mitigated by `cacheDir` placement, `finally` deletion and a startup sweep, not eliminated. **Open: no server-side content scanning.** The device whitelist and magic-byte check are not a substitute and are not claimed to be. **Open: no erasure propagation and no retention schedule** (both pre-production gates, B8). **Open: no probability established** for any of the six failure directions |

### H-18 (proposed): existing image and video attachments are not encrypted at rest

Surfaced by this pass and recorded separately, because it is a live gap in shipped code and not a
property of the new feature.

| Field | Content |
|---|---|
| **Hazard** | Consultation image and video attachments are stored unencrypted. The camera path writes plaintext JPEGs to `context.cacheDir/attachments/` (`ConsultationScreen.kt:385-389`); the gallery path stores a raw `MediaStore` `content://` URI (`ConsultationScreen.kt:103-108`) and never copies the bytes into app-private storage at all. `AttachmentEntity.uri` is a URI string, so SQLCipher protects the reference and not the content |
| **Harm** | H-04's lost-or-stolen-device PHI breach, through a route H-04's stated control does not cover. H-04 currently credits "**SQLCipher** at rest + non-exportable **Keystore** key," which is true of the database and not of these files. A second harm: `cacheDir` is OS-evictable, so a clinical photo can vanish without any record that it did |
| **Severity** | High (PHI, clinical imagery of patients) |
| **Probability** | Med, matching H-04's own rating for the same threat |
| **Proposed controls** | None yet. Recorded so the gap is on the register rather than discovered later |
| **Open** | **Open: whether to retrofit.** Feature 1 builds exactly the mechanism this needs (Keystore-wrapped AES-GCM files in `filesDir`), so the marginal cost of extending it to attachments after Feature 1 ships is small, and a migration for already-captured attachments is the harder half. **Open: H-04's control text should be narrowed** to say the SQLCipher and Keystore control covers the database, since as written it reads as covering data at rest generally, which this row shows it does not. That is a wording correction to an existing operator-signed entry and is the operator's call, not a design decision |

---

# PART F: BUILD ORDER AND SPLIT

## Recommended sequence

### Build 1: Feature 2, the prescription visibility gate. Do this first.

Confirmed as the safest change shape in this repo, for the reasons the brief anticipated and one it
did not:

- **No new data surface.** No table, no migration, no file, no key. The DB stays at version 17.
- **Gates rendering of data that already exists**, at a seam (`ReportFormatter`) that already
  performs audience-conditional redaction, so the control is the same shape as a working one.
- **Reverts cleanly.** One `const val` to `false` restores byte-identical prior behaviour.
- **And the reason it should be first that the brief did not anticipate:** it is the smaller
  containing box for C-3, the ungated in-app AGREE button. That is the sharpest finding in this
  pass and it should not wait behind a schema-and-crypto feature.

Scope: the `ReportFormatter` gate, the flag, the REJECT referral state, the two audit actions plus
their backend mirror entries, the `UserRole.DOCTOR` check on `canOpenDoctorReview`, the proposed
H-16 entry for operator review, and a mid-modification regression test.

**Model routing: Sonnet.** The design is settled here, the seam is identified, the pattern to copy
(`ReportFormatter.kt:150`'s existing audience redaction) is named. This is mechanical against a
settled design.

**One thing that must not be skipped:** the new `AuditAction` values and the
`audit_actions_device.py` mirror entries go in the **same commit**, with the set-agreement test
green. A device action the mirror does not accept is a permanent silent sync rejection.

### Build 2: cadre scope model. Small, and it unblocks the rest.

Not in the brief, and B7 makes it unavoidable. `UserRole { ASHA_WORKER, NURSE, COMPOUNDER, DOCTOR }`
cannot express the gate the scope document requires, because it has no CHO. Doing this between the
two features means Feature 1's role gate is built against the right model on the first pass instead
of being retrofitted, and it makes Feature 2's `DOCTOR` check honest rather than approximate.

Scope: extend or replace `UserRole` with cadre values and a scope mapping, migrate the persisted
session, and get the operator-signed risk entry the scope document asks for. Deliberately no
document code.

**Model routing: Sonnet**, with the operator in the loop on the cadre-to-scope mapping, which is a
clinical decision and not a coding one.

### Build 3: Feature 1, documents. Splits into three.

**3a. Storage and audit core.** Schema (`consultation_documents`, `MIGRATION_17_18`, version 18),
the Keystore document key, encrypt-and-write, `DocumentDao` and repository, PATH A direct-file
upload with magic-byte validation and the size cap, the three audit actions plus backend mirrors,
retract, and the safe `PdfRenderer` viewer. Delivers a complete, shippable feature: a worker can
attach an existing PDF to a consultation, view it safely, and retract it, with everything audited.

**Model routing: Sonnet.** Every piece has a pattern in the repo to follow: `DatabasePassphraseProvider`
for the Keystore work, `AttachmentEntity` for the entity shape, `MIGRATION_16_17` for the migration,
`RoomAuditLogger` for the audit, `ReportPdfExporter` for the file and stream handling.

**3b. Camera multi-capture to PDF.** The capture loop, page reorder and delete, per-page
encryption, `PdfDocument` assembly with `inSampleSize` downscaling and one bitmap in memory at a
time, streamed encrypted output, abandon and process-death cleanup, the startup sweep for orphaned
sessions, and honest failure on a page that will not decode.

**Model routing: Opus. I lean Opus, and here is the specific reason rather than a general appeal to
complexity.** The failure modes interact in ways a mechanical pass will get individually right and
collectively wrong: per-page encryption interacts with abandon cleanup, which interacts with
process-death recovery, which interacts with the orphan sweep, which interacts with the page
ordering the worker edited. Memory management interacts with page count, which interacts with the
size cap enforced on the assembled output, which interacts with whether downscaling was aggressive
enough. And the honest-failure requirement (a page that fails to decode aborts the whole assembly
rather than producing a short document) is the kind of requirement that gets quietly implemented as
a skip, because a skip is what the surrounding loop structure invites. That last one is a clinical
hazard, it is listed in H-17, and it is worth the routing.

**3c. Cadre role-visibility gate over documents.** Built against Build 2's model. Filters the
document list and the viewer by cadre scope, with the `document_viewed` audit carrying the
visibility tier.

**Model routing: Sonnet**, provided Build 2 landed. The gate is a filter; the hard part was the
model.

## Sequence summary

| Order | Work | Schema change | Flag-reversible | Model |
|---|---|---|---|---|
| 1 | Prescription visibility gate (+ role gate on the decision surface) | None | Yes, fully | Sonnet |
| 2 | Cadre scope model | Session or enum migration | Partly | Sonnet + operator |
| 3a | Documents: storage, audit, retract, direct-file path, viewer | 17 to 18 | Feature flag hides the entry point; the table stays | Sonnet |
| 3b | Documents: camera multi-capture to PDF | None beyond 3a | Yes | **Opus** |
| 3c | Documents: cadre visibility gate | None | Yes | Sonnet |

## What needs operator decision before Build 1 starts

1. **C-3 confirmation.** Is the in-app `DoctorReviewCard` intended to remain the physician review
   path for now, with a `UserRole.DOCTOR` gate on it, or is it demo scaffolding to be removed once
   a real channel exists? The answer changes whether Build 1 gates it or deletes it.
2. **A3 sign-off.** Prescription visibility is doctor-approval-gated for all non-physician cadres,
   CHO included, with no cadre exception. This is the position I recommend and it is a
   scope-of-practice call.
3. **A2 REJECT behaviour.** Confirm the referral-oriented state over a bare "no prescription" line.
4. **B7 direction.** Interim `DOCTOR`-only gate on documents, or model the cadre scope first. I
   recommend modelling first (Build 2).
5. **H-18.** Whether the unencrypted existing-attachment gap gets a retrofit after Feature 1, and
   whether H-04's control wording is narrowed to say it covers the database rather than data at
   rest generally.
