# Software Requirements Specification (SRS) — initial

> **Initial SRS**, reverse-derived from the current implementation to establish a requirements
> baseline and IDs for traceability (IEC 62304 §5.2). Forward requirements (kernel, auth,
> backend, sync) are marked **PLANNED**. To be reviewed against user needs / intended use by
> the clinical + product owners. IDs are stable references for `traceability-matrix.md`.

Conventions: `REQ-<AREA>-NN`. Status: **DONE** (implemented + manually verified), **PARTIAL**,
**PLANNED**.

## Registration (REG)
- **REQ-REG-01** (DONE) Register a patient with full name (required) and ≥1 contact method
  (mobile number or address).
- **REQ-REG-02** (DONE) Enforce fixed-length digit-only fields: mobile 10, emergency contact
  10, pincode 6, Aadhaar 12, ABHA 14; block submission while invalid.
- **REQ-REG-03** (DONE) Assign a locally-generated unique patient ID at creation (offline-first):
  a 12-character alphanumeric UID (`RegisterPatientUseCase.generatePatientId`, `SecureRandom`
  over a 62-char alphabet), matching `agent_docs/spec.md`'s 10–12 char format. No central
  registry to check against offline; the 62^12 keyspace makes collisions negligible at PHC
  patient volumes, and the Room primary-key constraint on `PatientEntity.id` is the backstop
  (insert aborts on collision, `register()` surfaces `Result.failure`, caller retries with a
  fresh id) (risk H-03).

## Medical background (MBG)
- **REQ-MBG-01** (DONE) Record medical/surgical history, medications, allergies, family history,
  and social history for a patient.
- **REQ-MBG-02** (DONE) Require deliberate review (summary dialog) before leaving the medical
  evaluation; allow an explicit "Skip for now".

## Vitals / initial assessment (VIT)
- **REQ-VIT-01** (DONE) Start a clinical encounter when initial assessment begins.
- **REQ-VIT-02** (DONE) Pre-fill vitals from a vitals source and allow manual edit; compute BMI.
- **REQ-VIT-03** (DONE) Capture chief complaint and symptoms.
- **REQ-VIT-04** (PLANNED) Validate vitals against plausible physiological ranges (risk H-01).

## Consultation (CON)
- **REQ-CON-01** (DONE) Capture chief complaint (text or voice), onset, duration, severity,
  factors, history, and attachments (image/video/affected-area photo/audio).
- **REQ-CON-02** (DONE) Require a review-summary confirmation before sending (risk H-08).

## Hand-off (HAN)
- **REQ-HAN-01** (DONE) Submit the case to a (currently mocked) clinical kernel.
- **REQ-HAN-02** (DONE) Transcribe captured audio (Android SpeechRecognizer).
- **REQ-HAN-03** (DONE) Persist the case locally with status `saved_locally`.
- **REQ-HAN-04** (DONE) Assign a doctor and set status `sent_to_doctor`.
- **REQ-HAN-05** (PLANNED) Kernel must be validated, versioned, and never presented as
  validated while mocked (risk H-02, H-09).
- **REQ-HAN-06** (DONE) The clinical-kernel payload boundary must structurally exclude patient
  identity — `KernelPayload` has no `Patient`-typed field, and `SendToKernelUseCase`'s signature
  accepts only `VitalsReading` + `Consultation` + an opaque case token, so a `Patient` object
  cannot reach the kernel even by mistake. Whitelisted fields only: vitals (device-pollable
  fields), chief complaint, duration bucket, severity score, relevant history, transcription,
  and unmodified attachments. Excludes fullName/aadhaarNumber/abhaNumber/mobileNumber/
  guardianOrSpouseName/address and also excludes onset/aggravatingFactors/relievingFactors/
  impactOnDailyActivities (not identifying, simply not in the whitelist — add deliberately if
  ever needed) (risk H-10).

## Roster / home (ROS)
- **REQ-ROS-01** (DONE) Show today's patients (those with an encounter today) on Home;
  tap to reopen.
- **REQ-ROS-02** (DONE) Never expose an "all patients" query — list access is day-scoped only
  (data minimisation; risk H-04).

## Patient identity (PID)
- **REQ-PID-01** (DONE) Display the current patient's name + unique ID persistently during an
  encounter to prevent record mix-ups (risk H-03).

## Security & data protection (SEC)
- **REQ-SEC-01** (DONE) Encrypt the database at rest (SQLCipher) with a key held in the Android
  Keystore, never stored in plaintext (risk H-04).
- **REQ-SEC-02** (DONE) Minimise on-device data to the current day's scope (risk H-04).
- **REQ-SEC-03** (PARTIAL) Authenticate users and enforce role-based access (risk H-06).
  **Biometric gate added** (2026-07): tapping "Sign in" on the worker Login screen now requires a
  real device biometric/credential check (`androidx.biometric.BiometricPrompt`, `BIOMETRIC_STRONG
  or DEVICE_CREDENTIAL`, `presentation/common/BiometricAuth.kt`) before `AuthSession.signIn` runs —
  no biometric match, no session, full stop. `MainActivity` is now a `FragmentActivity`
  (BiometricPrompt's requirement). **Still not full REQ-SEC-03:** this verifies "the device owner
  unlocked the device," not "the typed name belongs to this specific person" — there is no
  per-account credential store, so any device owner can still type any name/role and pass the
  biometric check as themselves. Real per-worker account binding (and RBAC) remains PLANNED.
  **Operational note:** a device with no biometric enrolled AND no screen lock at all cannot sign
  in (deliberately strict, not silently bypassed) — field deployment requires a screen lock be
  configured on every device.
- **REQ-SEC-04** (DONE) Capture a real per-session user identity (name + PHC field role — ASHA
  worker/Nurse/Compounder) via a local mock sign-in (`AuthSession`/`MockAuthSession`, Preferences
  DataStore), shown first on cold start when no session exists and skipped thereafter until
  sign-out. Exists solely to give the audit trail (REQ-AUD-01) a real per-session userId instead
  of the "phc_field_worker" placeholder. The biometric gate above (REQ-SEC-03) now sits in front of
  this same flow — sign-in still isn't full authentication (no per-person identity binding), but
  it's no longer "just typing a name," either (risk H-06).

## Audit (AUD)
- **REQ-AUD-01** (DONE) Log every clinical action (registration, encounter start, vitals,
  symptoms, audio capture, consultation save, kernel response, lock, doctor send) with
  timestamp, user, patient/case IDs, and payload.
- **REQ-AUD-02** (DONE) Audit records are insert-only — no update/delete at the DAO interface
  (risk H-07).

## Connectivity & sync (SYN / NET)
- **REQ-NET-01** (DONE) Detect and display real online/offline status.
- **REQ-SYN-01** (DONE) Show last-sync status and a manual Sync action on Home; indicate
  offline that data is saved locally and will sync later (UI-only for now).
- **REQ-SYN-02** (PLANNED) Real background sync with per-record state, conflict resolution, and
  purge-on-sync minimisation (`docs/sync-design.md`; risk H-05).

---

## Overhaul requirements (Phase 0+ — SaMD demo overhaul)

> Added by the 2026-07 overhaul. Phase 0 establishes the domain/schema foundation for all of these;
> the flows/UI are built in later phases and stay **PLANNED** until then. Status **DONE (schema)**
> means the model + Room table/columns + migration exist and compile (v3), not that the feature is
> wired to UI. All ABHA/kernel/backend items stay MOCK per the demo scope.

### Pediatric / guardian (PED)
- **REQ-PED-01** (DONE schema / PLANNED UI) Capture guardian name + relation for minors (age < 18).
  Model `Patient.guardianRelation`, `PatientEntity.guardianRelation` (nullable, populated only when
  minor).

### ABHA identity (ABH) — mock, precedes registration
- **REQ-ABH-01** (DONE) Mock ABHA sign-up: collect name/DOB/gender/mobile
  (`AbhaSignUpScreen`/`AbhaSignUpViewModel`), simulated portal-redirect delay shown as its own
  screen state (`AbhaSignUpStage.REDIRECTING`), mints a canonical 14-digit ABHA id
  (`CreateAbhaProfileUseCase`), persists as `AbhaProfile`. Display-formatted `XX-XXXX-XXXX-XXXX`
  via `formatAbhaId()` — never stored dashed (Phase 1).
- **REQ-ABH-02** (DONE) Mock ABHA login: enter ABHA id (`AbhaLoginScreen`) → mock OTP
  (`AbhaOtpScreen`, prefilled `123456`, any 6 digits accepted, `VerifyAbhaLoginUseCase`) →
  `RegisterViewModel.loadAbhaProfile` autofills registration from the stored `AbhaProfile`;
  autofilled fields tagged "From ABHA" (text fields) / "(from ABHA)" (biological sex), tag clears
  on manual edit. Fully separate from the worker mock login (REQ-SEC-04) — different route graph,
  different session concept, no shared state (Phase 1).
- Schema foundation (DONE): `AbhaProfile` model + `abha_profiles` table; ABHA↔registration field
  map in `docs/requirements/abha-field-mapping.md`. Link key is `Patient.abhaNumber` (no duplicate
  id column) — both hold the same canonical 14-digit string.
- Known mock limit (by design, not a bug): ABHA login only resolves an id previously created via
  `CreateAbhaProfileUseCase` **on this device** — there is no real ABDM directory to query offline.

### Ailments (AIL) — supersedes free-text Complaints/Symptom
- **REQ-AIL-01** (DONE) Ailment entries typed measurable vs non-measurable, captured on the
  Compounder screen (`NewAilmentCard`, `AddAilmentUseCase`). `AilmentEntry.measurementType`.
- **REQ-AIL-02** (DONE) Per-entry visibility Private/Public toggle, default Public; toggling to
  Private shows the full-screen "hand the device to the patient" interstitial
  (`PrivateHandoffInterstitial`, Hindi + English). A saved PRIVATE entry's clinical text/severity/
  duration/onset are absent (not just hidden) from `CompounderViewModel`'s worker-facing
  `AilmentListItem` — see `toListItem()` and `AilmentListItemMappingTest`. `AilmentEntry.visibility`.
- **REQ-AIL-03** (DONE) Private-entry audio recorded via `AilmentAudioRecorder`
  (`AndroidAilmentAudioRecorder`, real `MediaRecorder`, app-private internal storage, never
  uploaded — no upload path exists for this boundary). No playback method exists anywhere in the
  interface or its implementation; worker gets record/stop + delete only.
  `AilmentEntry.audioLocalUri` + soft-delete `deletedAt` (`DeleteAilmentUseCase`).
- **REQ-AIL-04** (HARD RULE, DONE) `AilmentRepository.observeForEncounter` returns every entry
  regardless of visibility — the (future, Phase 4) kernel path reads from here directly, bypassing
  the worker-facing `AilmentListItem` projection entirely. Documented in `AilmentRepository`/
  `Visibility`/`AilmentEntry` KDoc so it cannot be got backwards.
- Migration: `MIGRATION_3_4` backfilled every `symptoms` row into `ailments`
  (`measurementType=NON_MEASURABLE`, `visibility=PUBLIC`) and dropped `symptoms`. DB v3→v4.
  `AddSymptomUseCase`/`SymptomEntity`/`SymptomDao`/`Symptom` domain model removed — "chief
  complaint" (`Consultation.chiefComplaint`/`onset`) is a distinct, unrenamed clinical field.

### Trust & safety (TRS — Phase 2.5)
- **REQ-TRS-01** (DONE) Digital consent checkpoint (Hindi + English), `ConsentScreen`/
  `ConsentViewModel`, shown once before Compounder (ailment capture). Checkbox-gated Continue;
  logs `AuditAction.CONSENT_RECORDED` with `patientId` + timestamp (no `caseRecordId` yet — the
  encounter doesn't exist until Compounder starts it).
- **REQ-TRS-02** (DONE) `CheckEmergencyThresholdsUseCase` (SpO2 < 90%, systolic BP outside
  90–180 mmHg, diastolic BP ≥ 120 mmHg — hard-coded, conservative starting thresholds for clinical
  review, not a finished decision rule) runs in `CompounderViewModel.onContinue()` right after
  vitals save, **before** Consultation/Sending exist. On trip: logs `AuditAction.EMERGENCY_OVERRIDE`
  distinctly, then `EmergencyOverrideScreen` (full-screen, high-contrast, Hindi + English) is a
  terminal state — "Acknowledged" clears the back stack to Home. No path from there into
  Consultation/Sending; store-and-forward is disallowed for acute emergencies by design.
- **REQ-TRS-03** (DONE) Expectation-management message added to the existing Acknowledgement
  screen (non-emergency path only, by construction — emergency short-circuits before this screen
  is ever reached). `SyncWindowProvider`/`AndroidSyncWindowProvider` reads `R.integer.
  sync_window_hours` (`res/values/integers.xml`, default 24) — override per PHC deployment via a
  resource overlay, never hardcoded in the composable.
- **REQ-TRS-04** (PARTIAL) Guided structured non-measurable capture: flat severity/duration/onset/
  qualifiers fields shipped in Phase 2's `NewAilmentCard` (`AilmentEntry.severity/duration/
  qualifiers/onset`). Still PLANNED: dynamically expanding the field set based on the selected
  ailment type — that per-ailment-type logic is Phase 2.5's addition, not built yet.
- **REQ-TRS-05** (DONE) Vitals capture-method logging. `VitalsCaptureMethod` enum (MANUAL_CUFF/
  DIGITAL_MONITOR/PULSE_OXIMETER/THERMOMETER/OTHER) replaces the free-text placeholder;
  `Observation.captureMethod`/`ObservationEntity.captureMethod` typed accordingly (same TEXT column
  affinity — no new migration needed). **Scoping decision:** one dropdown per vitals snapshot, not
  one per individual vital row — mirrors the existing per-snapshot `ObservationSource` granularity
  already in the schema, rather than fragmenting into eight pickers.
- **REQ-TRS-06** (DONE) Dual timestamps `capturedAtOffline`/`recordedAt` + `syncedToCloudAt` on
  ailments and observations; `syncedToCloudAt` stays null until a future real sync — correctly
  distinct from the capture time, never defaulted equal.

### Reporting (RPT — Phases 3 / 3.5)
- **REQ-RPT-01** (DONE) `ReportFormatter` (`domain/report`) assembles a structured [ClinicalReport]
  from Phase 0 entities — header, verbatim chief complaint, ailments (measurable → non-measurable),
  vitals table, SI units, medication frequencies spelled out. One object, sections appended
  progressively: preliminary = kernel/prescription/signature null; Phase 4 appends kernel; Phase 5
  appends prescription + flips `isFinal`. `AssembleReportUseCase` fetches; the formatter is pure.
- **REQ-RPT-02** (DONE) `ReportCanvasRenderer` draws the report onto a plain `android.graphics.
  Canvas` at A5 (420×595 pt), block-paginated at section boundaries with the footer pinned to the
  last page. The SAME renderer drives the Compose preview (`drawIntoCanvas`) and the
  `android.graphics.pdf.PdfDocument` export (`ReportPdfExporter`) — no external PDF library, no
  iText/AGPL. Layout emulates an AIIMS outpatient card (logo / centre title + CR No / UID
  Code-128 barcode header; two-column demographic matrix; clinical summary + Rx/Advice; consent +
  physician-verification footer). Barcode is a dependency-free Code 128B encoder (`Code128`).
  **Logo (2026-07):** the header slot renders the real institutional logo
  (`res/drawable-nodpi/logo.png`, the same asset already shown on Home) via `decodeReportLogo()` —
  falls back to the bordered "LOGO" placeholder only if decoding fails, never a blank gap.
  **Attachments (2026-07):** every consultation attachment (photo/affected-area photo/audio/video)
  is carried into the report unmodified — same "pass through, don't filter" posture as
  `KernelPayload.attachments` already has for the kernel (REQ-HAN-06). Image types render inline
  in a new "Attachments" section (`decodeAttachmentBitmap()`, content-resolver based, falls back to
  "Image unavailable" on decode failure); audio/video are listed by label — a static canvas/PDF
  page can't play either back. `ReportCanvasRenderer` takes the decoded bitmaps via an
  `imageLoader` lambda supplied by the caller, so the renderer itself never touches `Context`.
- **REQ-RPT-03** (DONE) Field-mapping table `docs/requirements/report-field-mapping.md` binds every
  rendered element to its `entity.field` source; the only non-data elements are fixed section
  labels (the logo is now a real asset, not a placeholder — see REQ-RPT-02).
- Supporting schema: `Doctor.registrationNumber` (NMC/State-council reg no, mock reference data in
  `doctors.json`) added for the footer's physician signature line.

### Kernel output (extends Hand-off — Phase 4)
- **REQ-HAN-07** (DONE) Kernel returns `KernelReportOutput` (predictedCondition, confidenceScore,
  differentials, reasoningSummary, evidenceFor/Against, modelVersion, inferenceTimestamp,
  requiredHumanVerification) via `GenerateKernelReportUseCase`, called from `SendingViewModel` right
  after `SendToKernelUseCase`, persisted through `KernelReportRepository` (net-new object — no prior
  `AiKernelResponse` existed). Mock data is keyword-matched against `KernelPayload.chiefComplaint`
  against a small curated scenario table (fever/respiratory/GI/headache + a lower-confidence
  default) rather than pure random text, for demo credibility — still explicitly a mock, not real
  inference. `requiredHumanVerification` = `confidenceScore < 0.90` (existing convention).
  **AI Assessment Panel** (`presentation/kernelassessment`) — confidence gauge, explainability
  (reasoning + evidence for/against), and a liability checkbox gating Continue — shown between
  Sending and Transcription/Acknowledgement. This is net-new UI (memory of a pre-existing panel
  was stale; nothing matching it existed in code before this phase). Extends the existing
  pseudonymization posture (REQ-HAN-05/06); still MOCK, never presented as validated. The same
  `KernelReportOutput` feeds `ClinicalReport.kernelOutput` (Phase 3's report object) automatically
  once persisted — no second document.

### Prescription (RX — Phase 5)
> **Scope correction (2026-07):** the doctor's own review/prescription-authoring UI is built and
> run via a **separate communication channel** — a different app/portal, not this codebase — so
> "Doctor Prescription Screen" is not built here. What Phase 5 delivers instead is the **receiving
> boundary**: a swappable intake interface that accepts whatever that channel eventually sends and
> feeds it into the same `ClinicalReport`. Mocked now (no real transport exists yet), but the
> interface/data-contract shape is the real foundation — a future real API/webhook client only
> needs to replace one class ([MockDoctorPrescriptionInbox]), not any call site.
- **REQ-RX-01** (DONE) Doctor prescription = diagnosis + ordered medication lines (generic, brand?,
  strength, dosage, frequency, route, duration, quantity, foodRelation?, instructions?).
  `Prescription` + child `medication_lines`. Populated via `domain/doctor/DoctorPrescriptionInbox`
  (mock impl: `data/doctor/MockDoctorPrescriptionInbox`) → `ReceiveDoctorPrescriptionUseCase` →
  `PrescriptionRepository` — the PHC worker triggers the check from `PatientSummaryScreen`
  ("Check for doctor's response (mock)"), reachable only through the day-scoped roster
  (REQ-ROS-02 stays intact — no new all-patients query). New `CaseStatus.PRESCRIPTION_RECEIVED`.
- **REQ-RX-02** (HARD RULE, PARTIAL) No ambiguous Latin abbreviations (OD/BD/TDS/QID/SOS/HS) in any
  stored or displayed dosing text; frequencies written out in full (NMC/EU). Documented in
  `MedicationLine` KDoc, enforced at the report boundary (`ReportFormatter.formatMedicationLine`
  throws on a banned token). The doctor's own entry-form guard is out of scope here (that form lives
  in the separate doctor-channel app) — this app's mock intake never generates a banned token, and
  the report-boundary throw is the backstop if a real future intake ever did.
- **REQ-RX-03** (DONE, mock) Doctor's Agree/Modify/Reject on the kernel differential
  (`KernelDecision` enum, `Prescription.kernelDecision`, additive migration 4→5). The mock intake
  (`MockDoctorPrescriptionInbox`) simulates a realistic decision distribution (weighted toward
  Agree) rather than fabricating a fixed value, and the decision renders on the final report's
  Rx/Advice block. The doctor's real decision-making UI is out of scope (separate channel).

### Referral (REF — Phase 6)
- **REQ-REF-01** (DONE) Refer a case to a higher facility (CHC/District). "Refer to Higher
  Facility" button lives on `ReportScreen` (the PHC worker's report view — not a doctor screen,
  consistent with the Phase 5 scope correction). **Decision (always-visible-but-disabled, chosen
  over hidden):** the button is always rendered, enabled only when `ClinicalReport.
  suggestsReferral` is true — a non-measurable ailment's severity ≥ 8/10
  (`ReportFormatter.REFERRAL_SEVERITY_THRESHOLD`), or the doctor's `KernelDecision.REJECT` on the
  AI differential. Chosen for discoverability (the worker can see the feature exists) over hiding
  it entirely. Tap opens a bottom sheet: urgency (`UrgencyLevel` chips) + reason (auto-filled from
  `ClinicalReport.referralReasonSuggestion` — diagnosis-based, or the severity/rejection rationale
  when those triggered it — always editable). Confirm creates a `ReferralRequest`
  (`CreateReferralUseCase` → `ReferralRepository`), logs `AuditAction.REFERRAL_CREATED`, and shows
  "Referral sent — Patient UID {uid} queued for CHC/District Hospital appointment." PHC-side only —
  no receiving-side system; `ReferralStatus` never advances past `QUEUED` in this app.
