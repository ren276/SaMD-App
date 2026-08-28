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
- **REQ-HAN-01** (DONE) Submit the case to a clinical kernel (real HTTP, see REQ-HAN-07/EVL-01;
  mock fallback only on the `/v1/assess` leg).
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
  (data minimisation; risk H-04). Clarifying note (BUILD 2 follow-up, 2026-08): the Patients
  tab's directory read (`PatientDao.observeRegisteredOrSeenBetween`) is a second, separate,
  still-bounded 7-day query — a registered patient with no encounter yet resolves on their
  registration time instead of being excluded, so a worker who registers someone and has not
  yet started their visit can find them again. It remains day-scoped and is not an "all
  patients" query. Home's `observePatientsWithEncounterBetween` (REQ-ROS-01) is untouched and
  stays the only encounter-required query; `scripts/check-single-inner-join-encounters.sh`
  enforces that mechanically in CI.

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
  offline that data is saved locally and will sync later. Real for the one place data crosses a
  boundary today (doctor assignment): offline confirm queues `CaseStatus.PENDING_SYNC` instead of
  sending, `SyncStatusImpl` (Phase 6b, replacing the earlier simulated `MockSyncStatus`) refuses
  to run offline, and auto-syncs every queued case the moment connectivity returns (real network
  or the manual toggle) without waiting for the button.
- **REQ-SYN-02** (DONE for the push side, 2026-08-18, Phase 6b) The generic `syncState`
  convention now has a live consumer: `SyncPushWorker`, a connectivity-constrained, backoff-
  retried `WorkManager` job, drains every syncable table's `PENDING` rows to a real backend
  (`POST /api/v1/sync/push`), batched under the 400-record/4.5 MB budget, with per-record acks
  mapped to `SYNCED`/`CONFLICT`/`FAILED` and crash-safe batch_id reuse on resume. `CaseStatus.
  PENDING_SYNC`/`SENT_TO_DOCTOR` (the doctor-assignment leg) is untouched and coexists with this
  generic transport state. Still open: conflict *field-level* merge, `RemoteMediator`/pull, and
  purge-on-sync minimisation (`docs/sync-design.md` §2 items 3-5; risk H-05).

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
  different session concept, no shared state (Phase 1). **2026-08-18, Phase 6c W2:** a masked ABHA
  mobile (the real ABDM `/profile` shape) is never autofilled into the submittable field and never
  satisfies the ≥1 contact-method rule on its own — see `abha-field-mapping.md`'s `mobileNumber`
  row.
- **Phase 6c infra (2026-08-18), not a requirement change:** `AbdmAbhaSource`/`RetrofitAbhaSource`/
  `AbhaApiService` wired for the real backend (`POST/GET /api/v1/abha/registration-sessions*`), but
  `CreateAbhaProfileUseCase`/`VerifyAbhaLoginUseCase` remain on the mock above — see the
  `abha-integration-plan.md` "Use cases" entry for why (Aadhaar/OTP UI gap; P1 login endpoint not
  built). REQ-ABH-01/02 stay DONE against the mock; the real cutover is a future session.
- Schema foundation (DONE): `AbhaProfile` model + `abha_profiles` table; ABHA↔registration field
  map in `docs/requirements/abha-field-mapping.md`. Link key is `Patient.abhaNumber` (no duplicate
  id column) — both hold the same canonical 14-digit string.
- Known mock limit (by design, not a bug): ABHA login only resolves an id previously created via
  `CreateAbhaProfileUseCase` **on this device** — there is no real ABDM directory to query offline.
- **REQ-ABH-03** (DONE, backend, `ABDM_MODE=stub` only) Real ABDM V3 M1 adapter, Create ABHA via
  Aadhaar OTP, the P0 vertical slice: session start (`POST /api/v1/abha/registration-sessions`),
  Aadhaar submission and RSA-OAEP-SHA1 encryption (`.../identity`), OTP verification and
  enrollment (`.../otp`), conditional communication-mobile verification (`.../mobile-otp`), state
  polling (`GET .../{id}`), and final verified identity retrieval (`GET .../{id}/profile`).
  `backend/abdm-adapter/` (sibling package, docs/backend/api-contract.md section 8,
  backend-prd.md section 4.3), mounted as a router into the same `backend/core` FastAPI process.
  State machine server-enforced against `abha_transactions` (Phase 1 schema, unchanged this
  session except the `external_token_encrypted` column becoming genuinely pgcrypto-encrypted).
  Not wired to Android; that is Phase 6.
- **REQ-ABH-04** (DONE, backend) Every ABDM response is classified by a parsed body field, never
  by HTTP status alone (D2): `enrollment/auth/byAbdm` returns HTTP 200 for both a correct and an
  incorrect/expired OTP, discriminated only by `authResult`. Proven by a test that feeds a 200
  with `authResult: "failed"` and asserts the session moves to a failed state, not forward.
- **REQ-ABH-05** (DONE, backend) Aadhaar numbers, OTP values, ABDM tokens, and inline base64
  identity photos never appear in a response, a log line, an audit payload, or (for the photo
  specifically) any persisted row; the X-token is encrypted at rest and cleared once a session
  reaches a terminal state. Proven by a dedicated test
  (`backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs`), not assumed from
  the redaction processor covering fields this feature happens to introduce.

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
  `AiKernelResponse` existed). `requiredHumanVerification` = `confidenceScore < 0.90` (existing
  convention).
  **Primary path (2026-07-21):** real HTTP call to a local FastAPI + XGBoost kernel via
  `RemoteKernelSource`/`RetrofitKernelSource` (`data/remote/`) — `POST /v1/assess`, base URL
  `http://10.16.4.182:8000/` (LAN IP of the host machine, for physical-device testing; requires
  `android:usesCleartextTraffic="true"`, plain HTTP not TLS — acceptable for this local dev/demo
  server, not production; IP updated 2026-07 when the dev host changed networks — see
  `di/NetworkModule.kt`, same host now also serves `/api/v1/evaluate`, REQ-EVL-01). **Fallback
  path:** any failure (IOException/HttpException/timeout/server offline) is caught in
  `GenerateKernelReportUseCase.tryRealApi` and falls back to the original Phase 4 mock —
  keyword-matched against `KernelPayload.chiefComplaint` against a small curated scenario table
  (fever/respiratory/GI/headache + a lower-confidence default), still explicitly a mock. The app
  never crashes when the ML server is unreachable. **This mock-fallback behaviour is unique to the
  `/v1/assess` leg — the newer `/api/v1/evaluate` leg (REQ-EVL-01) has deliberately NO mock
  fallback**, since fabricated treatment/brand data would be worse than an omitted section.
  **AI Assessment Panel** (`presentation/kernelassessment`) — confidence gauge, explainability
  (reasoning + evidence for/against), and a liability checkbox gating Continue — shown between
  Sending and Transcription/Acknowledgement. **Sourcing updated 2026-07:** now reads the real
  `/api/v1/evaluate` output as primary (`EvaluateReportRepository`, real confidence % + ICD +
  per-candidate reasoning from the backend), falling back to this `KernelReportOutput` path only
  when no evaluate output exists for the case (`KernelAssessmentViewModel.toDisplay()` on each
  type, unified into `AssessmentDisplay`). Extends the existing pseudonymization posture
  (REQ-HAN-05/06) — real-path request body carries only pseudonymized clinical signals (age/sex/
  vitals/BMI), no identity fields; never presented as fully validated regardless of path taken. The
  same `KernelReportOutput` feeds `ClinicalReport.kernelOutput` (Phase 3's report object)
  automatically once persisted — no second document. See REQ-HAN-08 for the per-record
  traceability marker of which path (real vs. mock) produced a given result.
- **REQ-HAN-08** (DONE) Every persisted `KernelReportOutput` records a non-nullable
  `inferenceSource: InferenceSource` (`REAL_INFERENCE` | `MOCK_FALLBACK`), stamped once in
  `GenerateKernelReportUseCase` at the same real-vs-mock branch point as REQ-HAN-07. Previously
  this distinction existed only as a Logcat line (`GenerateKernelReportUseCase.tryRealApi`'s
  `logger.warning(...)`) — unqueryable, absent in production. Surfaced in the AI Assessment
  Panel (`presentation/kernelassessment/KernelAssessmentScreen`) as a distinct fallback notice,
  independent of the confidence-driven verification warning, plus carried into the
  `kernel_response_received`/`kernel_assessment_acknowledged` audit log payloads. **2026-07:** the
  exported report's "Kernel AI Assessment" canvas block (`ReportCanvasRenderer.kernelBlock`) was
  removed — that mock-fallback-capable section is superseded on the report/prescription by the
  real `/api/v1/evaluate` "AI Clinical Evaluation" block (REQ-EVL-01), which has no mock fallback
  to disclose in the first place. `KernelReportOutput`/`inferenceSource` still exist and still
  drive the AI Assessment Panel fallback path above — only the printed-report block was removed.
  Strengthens the existing residual-risk control for H-09 ("gate real kernel behind validation +
  version field") — not a new hazard (risk H-02, H-09).

### Evaluate / NLEM treatment kernel (EVL — 2026-07)
> Adds a second, independent real-inference leg alongside `/v1/assess` (REQ-HAN-07): the same
> FastAPI backend's `/api/v1/evaluate` endpoint returns a diagnostic summary **and** an NLEM 2022
> treatment recommendation (drug, dosage, referral reason), which the old `/v1/assess` contract
> never provided. No mock fallback exists for this leg by design (see REQ-HAN-08 update above) —
> a failed call just omits the section rather than fabricating treatment data.
- **REQ-EVL-01** (DONE) `GenerateEvaluateReportUseCase` calls `/api/v1/evaluate` via
  `EvaluateKernelSource`/`RetrofitEvaluateSource` (`ClinicalApiService`), fired alongside the
  `/v1/assess` call in `SendingViewModel`. Persists `EvaluateReportOutput`
  (`EvaluateReportRepository`, Room migration 8→9) — diagnostic summary (primary candidate +
  ranked differential with per-candidate confidence/reasoning), NLEM treatment (drug, dosage
  forms, level of healthcare, referral reason), brand mapping, and safety/vitals-triage grading.
  Full raw response dumped to the audit log (`AuditAction.EVALUATE_RESPONSE_RECEIVED`/
  `EVALUATE_RESPONSE_FAILED`) — distinct from the curated subset shown on the report/prescription.
- **REQ-EVL-02** (DONE) India-brand lookup: `BrandLookupSource`/`GeminiBrandLookupSource` asks the
  Gemini API for the top-selling India-manufactured brand **and its manufacturer** for the
  recommended generic drug (`gemini-2.5-flash`, `thinkingBudget: 0` for latency — measured ~0.7s
  vs ~5.6s with thinking enabled). Result (`IndianBrandSuggestion`, brand + company) stored on
  `EvaluateReportOutput.topIndianBrand`, persisted alongside it. Best-effort only — never throws,
  never blocks the evaluate pipeline; a missing/blank `GEMINI_API_KEY` (`local.properties`,
  git-ignored, `BuildConfig.GEMINI_API_KEY`) or any lookup failure just leaves it null.
- **REQ-EVL-03** (DONE) Report/prescription rendering: `ReportCanvasRenderer.evaluateBlock`
  ("AI Clinical Evaluation" section) draws, in order, vitals triage → top diagnostic candidate only
  (not the full differential list) → recommended treatment (drug + dosage + brand) → overall
  urgency → inference time (small line, reference only). Diagnosis/drug/brand lines and any
  urgent/human-review/pediatric-referral flags render bold (urgent ones bold red) — the findings a
  reviewing physician needs to see first. See `docs/requirements/report-field-mapping.md`.

### Prescription (RX — Phase 5, revised 2026-07)
> **Scope correction superseded (2026-07):** the original plan deferred the doctor's own review to
> a separate out-of-app channel, with only a receiving boundary built here. That receiving boundary
> (`DoctorPrescriptionInbox`/`MockDoctorPrescriptionInbox`/`ReceiveDoctorPrescriptionUseCase`) still
> exists in code but is **no longer wired to any UI** — for the investor demo, the physician review
> is now a real interactive AGREE/MODIFY/REJECT decision made in-app on `PatientSummaryScreen`
> (`SubmitDoctorDecisionUseCase`), not a randomly-simulated async response. See REQ-RFN-01 for the
> training-dataset-reimport contract this decision also feeds.
- **REQ-RX-01** (DONE) Doctor prescription = diagnosis + ordered medication lines (generic, brand?,
  strength, dosage, frequency, route, duration, quantity, foodRelation?, instructions?).
  `Prescription` + child `medication_lines`. **Populated via `SubmitDoctorDecisionUseCase`**,
  triggered from `PatientSummaryScreen`'s "Review AI diagnosis (doctor)" button (renamed from
  "Check for doctor's response (mock)" — no longer mock), reachable only through the day-scoped
  roster (REQ-ROS-02 stays intact — no new all-patients query). `CaseStatus.PRESCRIPTION_RECEIVED`
  unchanged. AGREE prescribes exactly the `/api/v1/evaluate` recommendation (drug + dosage + the
  Gemini brand suggestion, REQ-EVL-02, no re-lookup); MODIFY/REJECT let the reviewer type their own
  drug/dosage/brand, with a one-tap "Get brand" re-lookup via `BrandLookupSource`.
- **REQ-RX-02** (HARD RULE, PARTIAL) No ambiguous Latin abbreviations (OD/BD/TDS/QID/SOS/HS) in any
  stored or displayed dosing text; frequencies written out in full (NMC/EU). Documented in
  `MedicationLine` KDoc, enforced at the report boundary (`ReportFormatter.formatMedicationLine`
  throws on a banned token) — the in-app manual-entry fields (REQ-RX-01) don't yet enforce this at
  entry time, so the report-boundary throw remains the real backstop.
- **REQ-RX-03** (DONE) Doctor's Agree/Modify/Reject on the AI diagnosis (`KernelDecision` enum,
  `Prescription.kernelDecision`, additive migration 4→5). **No longer mock** — `SubmitDoctorDecisionUseCase`
  persists whichever of AGREE/MODIFY/REJECT the reviewer actually picks (`PhysicianDecision`), and
  the decision renders on the final report's Rx/Advice block. See REQ-RFN-01 for what (if anything)
  each decision means for a future training-dataset reimport.

### Diagnosis refinement feedback (RFN — 2026-07)
> Mirrors `refine_diagnosis.py`'s `DiagnosisFeedback` Pydantic schema in the SaMDClassifier repo —
> that schema is currently unwired on the backend (no live capture/reimport endpoint), so this is
> the demo-visible capture point for a future training-dataset reimport pipeline. **Verified against
> the actual training scripts** (`train_model.py`, `train_symptom_classifier.py`): neither reads
> drug/brand/company columns at all — Classifier A trains on
> `age, sex_encoded, systolic_bp, diastolic_bp, bmi, heart_rate, spo2, glucose` → `tier`; Classifier
> B trains on `symptom_string` → `icd_candidate`. Drug/brand/company are prescription-only concerns
> (REQ-RX-01/EVL-02) and must never leak into this record.
- **REQ-RFN-01** (DONE) `SubmitDoctorDecisionUseCase` persists a `DiagnosisFeedback` row
  (`DiagnosisFeedbackRepository`, migration 9→10, `clinicalNote` column added 10→11) alongside the
  prescription: `icdCandidate` (the AI's original top candidate, for traceability),
  `physicianDecision` (AGREE/MODIFY/REJECT), `physicianFinalDiagnosis` (the corrected diagnosis —
  **only** ever set on MODIFY, and only ever one of the 18 classes `symptom_model.json` was
  actually trained on, `TRAINED_ICD_CANDIDATES`; validated in the use case, silently dropped to
  null otherwise), and `clinicalNote` (free-text audit note, explicitly never reimported — no
  dataset column or reimport contract exists for free text). AGREE and MODIFY are the only
  decisions ever eligible for a future reimport; REJECT never is — "no reliable ground truth to
  trust once the AI's candidate is rejected outright" (schema docstring). Audit log
  (`AuditAction.DIAGNOSIS_FEEDBACK_RECORDED`) carries an explicit `reimportable` flag.
- **REQ-RFN-02** (DONE) UI: `PatientSummaryScreen`'s doctor-review card shows a corrected-diagnosis
  dropdown (`TRAINED_ICD_CANDIDATES`, 18 entries) and an optional clinical-note field **only when
  MODIFY is selected**, entirely separate Compose fields from the drug/dosage/brand prescription
  fields shared by MODIFY and REJECT (`ManualPrescriptionFields`) — confirmed no code path lets
  drug/brand/company reach `DiagnosisFeedback`, and no code path lets the corrected diagnosis reach
  `MedicationLine`.

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
