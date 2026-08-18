# Progress

Read this first, every session. Continue from the first unchecked item unless told otherwise.

## Mockup (done)
- [x] Repo, package structure, version catalog
- [x] Core data models + Room entities
- [x] Home screen
- [x] Register screen
- [x] Vitals screen
- [x] Consultation screen
- [x] Sending screen
- [x] Transcription screen
- [x] Acknowledgement/save screen
- [x] Doctor list screen
- [x] Runs end to end on Pixel 9 Pro

## Hardening (in progress)
- [x] SQLCipher on Room DB (see agent_docs/hardening.md)
- [x] `AuditLogEntity` + insert-only DAO
- [x] Wire audit logging into the 8 existing screens (one commit per screen, don't do all 8 in one pass)
  — actual screens now differ from this list (MedicalBackground/PatientSummary/Compounder added since):
  wired Register, MedicalBackground, Compounder, Consultation, Sending, Transcription, Acknowledgement,
  DoctorList. Home and PatientSummary skipped — no clinical action, pure navigation/read-only.
- [x] Local cache scoped to current day's patients only
  — data layer only (no UI yet). PatientDao.observePatientsWithEncounterBetween(start,end) is the
  ONLY list query on the DAO — deliberately no "all patients" query, so no code path can pull the
  full table (data-minimization). Scoped by Encounter.startedAt (visit day, not registration).
  PatientRepository.observeTodaysPatients() resolves today from device zone; GetTodaysPatientsUseCase
  wraps it. Next: a "Today's patients" roster screen on Home to consume it (its own UI task).

## Field UX + foundation (done — added after hardening)
- [x] Today's patients roster on Home (consumes GetTodaysPatientsUseCase; tap row → PatientSummary)
- [x] Sync status + Sync now on Home (UI-only; mockable SyncStatus; real sync deferred, see docs/sync-design.md)
- [x] Persistent patient name + ID banner (AppNavHost, derived from back stack; hidden on Home/Register)
- [x] Review-before-submit dialog on Consultation (ISO 14971 human-in-the-loop)
- [x] Review-before-continue dialog on Medical background
- [x] Regulatory/SDLC foundation docs → docs/regulatory-foundation.md, docs/sync-design.md
      (agent_docs/ is gitignored/local; docs/ is tracked = start of controlled documentation)

## Verification & CI (blocker #4 — first pass done)
- [x] JVM unit test suite (32 tests): RegisterUiState/ViewModel, AuditPayload/RoomAuditLogger,
      MockSyncStatus, HomeViewModel + pre-existing use-case/repo tests (now actually run)
- [x] Restored permanent instrumented DAO test for the day-scoped roster query
- [x] GitHub Actions CI — matrix over Dev/Staging/Prod flavors (`assemble{Flavor}Debug` +
      `test{Flavor}DebugUnitTest`), on push to master/ABHA branches + PRs into master (see
      `.github/workflows/android-ci.yml`; passing on all 3 legs)
- [x] GitHub Actions release workflow — `v*.*.*` tag push only: `testProdReleaseUnitTest` →
      `assembleProdRelease`, signing via `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` +
      base64 `KEYSTORE_BASE64` GitHub Secrets, signed APK uploaded as a build artifact (see
      `.github/workflows/android-release.yml`). New `signingConfigs.release` in
      `app/build.gradle.kts`, reads env vars, only applied to the `release` build type.
      **Flagged, not built blind:** no `.jks`/`.keystore` file exists in this repo, and none was
      generated — a prod signing key must be created deliberately and backed up outside git (was
      also missing from `.gitignore` before this pass; added `*.jks`/`*.keystore`). Release
      workflow will fail at the decode step until `KEYSTORE_BASE64` + the three password/alias
      secrets are actually provisioned in GitHub Secrets.
- [ ] Compose UI tests (Register form, review dialogs); instrumented SEC-01/AUD-02 coverage
- [ ] Add instrumented tests to CI once suite is larger (needs emulator action)

## Kernel payload boundary (done)
- [x] `KernelPayload` domain model (domain/model/) — structurally excludes Patient: no field of
      type Patient, `SendToKernelUseCase` signature only accepts VitalsReading + Consultation +
      an opaque case token (reuses CaseRecord.id). Whitelisted fields only (chief complaint,
      duration, severity, relevant history, transcription, unmodified attachments); excludes all
      identity fields plus onset/aggravatingFactors/relievingFactors/impactOnDailyActivities
      (not identifying, just not whitelisted). SendingViewModel now fetches vitals/consultation
      by encounterId (threaded through SendingRoute) instead of the use case taking no args.
      New risk H-10 + REQ-HAN-06 + traceability row. Verified on-device with an identity-laden
      test patient — none of it appeared in the constructed payload. Unit test:
      SendToKernelUseCaseTest.

## Mock login / AuthSession (done)
- [x] `AuthSession` domain interface (currentUser/signIn/signOut) + `UserRole` (ASHA_WORKER, NURSE,
      COMPOUNDER — PHC field roles only, matching existing terminology, not invented). PHC-worker
      scope only; admin/CMO dashboard is a separate product, out of scope.
- [x] `MockAuthSession` (data/local/auth/) — no credential check; Preferences DataStore (not Room:
      one small key-value blob, no relational shape/queries); survives app restart. Fresh opaque
      userId minted per sign-in (no account system to resolve "same person again" against yet).
- [x] Login screen (name + role picker), styled to match Register/Home. First screen on cold start
      when no session exists, skipped thereafter until sign-out — gated in AppNavHost via a new
      AuthViewModel (mirrors ConnectivityViewModel's shape: one shared instance, drives both the
      gate and Home's signed-in display). Sign-out entry point on Home.
- [x] RoomAuditLogger now sources userId from the real session (placeholder kept only as a
      should-never-happen fallback). New REQ-SEC-04 (explicitly NOT REQ-SEC-03 — no auth/RBAC
      enforcement, still PLANNED) + traceability rows + H-06 refreshed.
      Verified on-device: cold start → Login → sign in as "AshaDevi" (ASHA worker) → registered a
      patient → captured via temporary debug log → audit row carried the real session userId, not
      "phc_field_worker" → session survived app restart → sign-out returned to Login.

## Stable audit userId + Patient.id spec reconciliation (done)
- [x] `MockAuthSession.signIn` now derives userId deterministically from name+role (SHA-256,
      truncated) instead of `UUID.randomUUID()` per sign-in — same worker signing in on different
      days now keeps the same audit-trail userId (H-06, H-07). Not identity verification, no
      credential check added. Verified on-device: same userId across two independent sign-in
      cycles for the same name+role.
- [x] `Patient.id` reconciled to spec: `RegisterPatientUseCase.generatePatientId()` now generates
      a 12-char alphanumeric UID (`SecureRandom`, 62-char alphabet) instead of a 36-char UUID,
      matching `agent_docs/spec.md`'s 10–12 char format (risk H-03). No migration — no existing
      real patient data, new patients get the new format going forward. Collision handling: no
      central registry offline, so relies on the 62^12 keyspace (negligible collision odds at PHC
      volumes) plus the existing Room primary-key constraint on `PatientEntity.id` as a backstop.

## SaMD demo overhaul (started 2026-07-16)

Multi-phase, schema-changing. Full brief in the 2026-07-16 build brief. Model-switch stops are
explicit: Phase 0 + Phase 4 report data-contract on Opus; most phases on Sonnet high; Haiku for the
repetitive Phase 1 autofill mapping and Phase 6 boilerplate. **Stop and ask the user to switch model
at each boundary.** DI stays Hilt (not Koin) regardless of skill defaults.

### Phase 0 — Domain & schema foundation (DONE, on Opus)
- [x] New domain models (zero Android deps): `AbhaProfile`, `AilmentEntry` (+`MeasurementType`,
      `Visibility`), `Prescription`+`MedicationLine`, `KernelReportOutput`, `ReferralRequest`
      (+`UrgencyLevel`,`ReferralStatus`).
- [x] Extended `Patient` (+`guardianRelation`) and `Observation` (+`captureMethod`,
      +`syncedToCloudAt`) — dual-timestamp + capture-method schema pulled forward from Phase 2.5.
- [x] Room: entities + insert-only-style DAOs for all; `abha_profiles`, `ailments`, `prescriptions`,
      `medication_lines`, `kernel_reports`, `referrals` tables; `MIGRATION_2_3` (additive) wired in
      `DatabaseModule`; DB version 2 → 3; registered in `AppDatabase`; converters for new enums +
      `List<String>` (JSON).
- [x] `AuditAction` constants for new events (ailment capture/visibility/delete, consent, emergency
      override, referral) — DAO stays insert-only (REQ-AUD-02 untouched).
- [x] Docs updated: `software-requirements.md` (PED/ABH/AIL/TRS/RPT/HAN-07/RX/REF REQ-IDs),
      `traceability-matrix.md` (rows + Complaints→Ailments rename note), `spec.md` (models),
      new `docs/requirements/abha-field-mapping.md`.
- [x] **Verified:** `./gradlew testDebugUnitTest` green (31 tests, 0 failures); Room schema `3.json`
      exported; `MIGRATION_2_3` DDL is character-identical to Room's generated `createSql` for all 6
      new tables + indices, and the 3 added columns match affinity/nullability. (Instrumented
      `MigrationTestHelper` run deferred — needs emulator; DDL identity is the guarantee.)
- Decisions: (1) ABHA link key = existing `Patient.abhaNumber`, no duplicate id column, ABHA-first
      flow. (2) `AilmentEntity` introduced additively now; `SymptomEntity` renamed/migrated in
      Phase 2. (3) Phase 2.5 dual-timestamp + capture-method schema pulled into the single 2→3
      migration. (4) `KernelReportOutput` is net-new — no prior `AiKernelResponse` existed in code
      (memory was stale); Phase 4 builds the AI panel, does not "extend."

### Phase 1 — ABHA mock auth (DONE, on Sonnet high)
- [x] `AbhaProfileRepository`/`AbhaProfileRepositoryImpl` (wraps Phase 0's `AbhaProfileDao`),
      bound in `RepositoryModule`.
- [x] `CreateAbhaProfileUseCase` (mock sign-up: validates, delays like `SendToKernelUseCase`, mints
      a canonical 14-digit id, persists `kycVerified=true`) and `VerifyAbhaLoginUseCase` (mock OTP:
      any 6-digit code, resolves only a profile created on this device — no real ABDM directory).
      `formatAbhaId()` added for display-only `XX-XXXX-XXXX-XXXX` formatting (storage stays raw
      digits, matching `Patient.abhaNumber`'s existing shape — corrected from Phase 0's KDoc, which
      had wrongly implied dashed storage).
- [x] New screens/ViewModels: `AbhaEntryScreen` (Create / Login / Skip), `AbhaSignUpScreen`+VM
      (form → `REDIRECTING` stage shown for the use case's simulated delay → done),
      `AbhaLoginScreen`+VM (enter ABHA id), `AbhaOtpScreen`+VM (mock OTP, prefilled `123456`,
      Assisted-injected like `ConsultationViewModel`).
- [x] `Register` route is now `data class Register(val abhaId: String? = null)`; Home's "Register
      new patient" now goes to `AbhaEntry` first, not straight to Register. `RegisterViewModel`
      gained `loadAbhaProfile(abhaId)` (plain `@Inject`, not Assisted — abhaId is optional and not
      needed before first frame, called once via `LaunchedEffect(abhaId)` from the screen).
      Autofills full name/mobile/village/district/state/pincode/ABHA number/DOB/biological sex;
      autofilled fields tagged "From ABHA" in the UI; a manual edit clears that field's tag.
- [x] Worker mock login (`AuthSession`/`MockAuthSession`) untouched — confirmed no shared state
      with the new ABHA (patient-identity) flow, per the brief's explicit separation requirement.
- [x] `AuditAction.ABHA_PROFILE_CREATED`/`ABHA_LOGIN_VERIFIED` added; both new ViewModels log.
- [x] Docs: REQ-ABH-01/02 flipped PLANNED → DONE in `software-requirements.md`; traceability rows
      updated with real design components + new automated tests.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **44 tests, 0 failures** (was 31 after
      Phase 0; +13 covering ABHA sign-up/login use cases and Register autofill/tag-clear behavior).

### Phase 2 — Ailments + Private/Public (DONE, on Sonnet high)
- [x] Identified the actual "Complaints" feature: the Compounder screen's free-text symptom list
      (`AddSymptomUseCase`/`Symptom`/`SymptomEntity`/`SymptomDao`), tied to `ConsultationRepository.
      addSymptom/observeSymptoms`. "Chief complaint" (`Consultation.chiefComplaint`) is a distinct
      clinical field and was deliberately NOT renamed.
- [x] Renamed by removal + replacement, not a mechanical find/replace: deleted `Symptom` domain
      model, `AddSymptomUseCase`, `SymptomEntity`, `SymptomDao`, and the `ConsultationRepository`/
      `ConsultationRepositoryImpl` Symptom wiring. Added `AilmentRepository`+impl,
      `AddAilmentUseCase`, `DeleteAilmentUseCase` (soft-delete via `AilmentDao.markDeleted`).
- [x] `MIGRATION_3_4` (DB v3→v4): backfills every `symptoms` row into `ailments`
      (`measurementType=NON_MEASURABLE`, `visibility=PUBLIC`, historical rows only — new rows don't
      default this way) then drops `symptoms`. Schema `4.json` exported.
- [x] Compounder screen's ailment section (`NewAilmentCard`): measurable/non-measurable toggle
      (measured value+unit vs. severity/duration/onset/qualifiers — flat fields; the "dynamically
      expand per ailment type" enhancement stays Phase 2.5, not built now), Public/Private switch
      (default Public).
- [x] Private handoff: toggling to Private shows `PrivateHandoffInterstitial`, a genuine full-screen
      `Dialog` (Hindi + English, "Hand the device to the patient" / "उपकरण मरीज़ को दें"), Cancel
      reverts to Public. This is a workflow/social cue, not a technical hiding mechanism — the real
      technical guarantee is downstream (next bullet).
- [x] **The core guarantee (REQ-AIL-02):** `CompounderViewModel.toListItem()` maps a PRIVATE
      `AilmentEntry` to a worker-facing `AilmentListItem` with description/severity/duration/onset
      all `null` — genuinely absent from the ViewModel's UI state, not a composable choosing not to
      render a field that's still sitting in memory. `AilmentRow` renders a locked "🔒 Private
      entry" card for these. Covered by `AilmentListItemMappingTest` (3 cases: public keeps detail,
      private drops it, private-with-audio exposes only a delete handle).
- [x] `AilmentRepository.observeForEncounter` is deliberately unfiltered (REQ-AIL-04) — the KDoc is
      explicit that no visibility-filtered query should ever be added to this interface, since the
      (future, Phase 4) kernel path reads from here directly and must never be starved of private
      entries it's required to receive.
- [x] Private-entry audio is real, not mocked: new `AilmentAudioRecorder` domain interface +
      `AndroidAilmentAudioRecorder` (`MediaRecorder`, files in app-private `filesDir/ailment_audio/`,
      never `FileProvider`-shared, no upload path). **No playback method exists anywhere in the
      interface or implementation** — satisfies "never expose a play button to the worker role" by
      construction, not by omitting a button from one screen. Delete-only, via `DeleteAilmentUseCase`.
- [x] Extracted `rememberPermissionAction` (was private/duplicated-in-spirit inside
      `ConsultationScreen`) to `presentation/common/PermissionAction.kt`; both screens now share it.
- [x] Docs: REQ-AIL-01–04 flipped PLANNED → DONE, REQ-TRS-04 → PARTIAL (flat fields shipped, dynamic
      per-ailment expansion deferred to 2.5), REQ-VIT-03's design column updated, rename note closed
      out — all in `software-requirements.md`/`traceability-matrix.md`.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **51 tests, 0 failures** (was 44; +7:
      `AilmentUseCasesTest` ×4, `AilmentListItemMappingTest` ×3). `assembleDebug` and
      `compileDebugAndroidTestKotlin` also clean; `CompounderScreenTest` (androidTest) updated for
      the renamed `CompounderActions` interface.

### Phase 2.5 — Trust & Safety features (DONE, on Sonnet high)
- [x] **Consent checkpoint** (REQ-TRS-01): new `ConsentRoute(patientId)` inserted between
      PatientSummary and Compounder (PatientSummary.onStartConsultation now goes to Consent, not
      straight to Compounder). Checkbox-gated, Hindi + English, logs `AuditAction.CONSENT_RECORDED`
      with `patientId` only (no `caseRecordId` — encounter doesn't exist yet at this point).
- [x] **Emergency red-flag override** (REQ-TRS-02): `CheckEmergencyThresholdsUseCase`
      (SpO2 < 90%, systolic BP outside 90–180 mmHg, diastolic ≥ 120 mmHg — hard-coded, conservative,
      flagged as a starting point for clinical review not a finished decision rule). Runs in
      `CompounderViewModel.onContinue()` right after vitals save; on trip, emits
      `CompounderEffect.EmergencyOverride` instead of `Continue` — new `EmergencyOverrideRoute`
      renders a full-screen high-contrast Hindi+English `EmergencyOverrideScreen` with **no path
      onward into Consultation/Sending**, only "Acknowledged" → `backStack.clear(); add(Home)`
      (same terminal pattern as DoctorList's `onDone`). Logs `AuditAction.EMERGENCY_OVERRIDE`
      distinctly from a normal referral.
- [x] **Expectation-management message** (REQ-TRS-03): added to the existing Acknowledgement screen
      (non-emergency path only, by construction — emergency short-circuits before reaching it, no
      extra guard needed). New `SyncWindowProvider`/`AndroidSyncWindowProvider` reads
      `R.integer.sync_window_hours` (`res/values/integers.xml`, default 24) — override per PHC
      deployment via a resource overlay; the composable never hardcodes the number.
- [x] **Vitals capture-method logging** (REQ-TRS-05): `VitalsCaptureMethod` enum (MANUAL_CUFF/
      DIGITAL_MONITOR/PULSE_OXIMETER/THERMOMETER/OTHER) replaces Phase 0's free-text placeholder on
      `Observation`/`ObservationEntity.captureMethod` — same TEXT column affinity, **no new
      migration** (verified: `4.json`'s captureMethod field is still `affinity: TEXT`, no `5.json`
      generated). Threaded through `VitalsSnapshot` → `VitalsRepositoryImpl` → one dropdown in
      `CompounderScreen`'s Vitals section.
      **Scoping decision:** one dropdown for the whole vitals snapshot, not one per vital row —
      mirrors the existing per-snapshot `ObservationSource` granularity already in the schema
      (brief's own wording said "next to each vital," but the data model was never shaped that way).
- [x] Dual timestamps (REQ-TRS-06) confirmed already correct from Phase 0/2: `recordedAt` (offline
      capture) vs `syncedToCloudAt` (null until a real sync exists) are genuinely distinct, never
      defaulted equal.
- [x] **Not built** (explicitly deferred, tracked as PARTIAL on REQ-TRS-04): dynamically expanding
      the guided-capture field set based on the selected ailment type. Phase 2 shipped the flat
      severity/duration/onset/qualifiers fields; the "changes per ailment type" behavior needs a
      curated ailment-type vocabulary that doesn't exist yet — flagging rather than inventing one.
- [x] Docs: REQ-TRS-01/02/03/05/06 flipped PLANNED → DONE (04 stays PARTIAL) in
      `software-requirements.md`/`traceability-matrix.md`.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **60 tests, 0 failures** (was 51; +9:
      `CheckEmergencyThresholdsUseCaseTest` ×7, `ConsentViewModelTest` ×2). `assembleDebug` and
      `compileDebugAndroidTestKotlin` clean; `CompounderScreenTest` updated for the new
      `onCaptureMethodChange` action.
- **Known coverage gap (flagged, not silently skipped):** `AcknowledgementViewModel`'s
  `hoursUntilReview` passthrough (REQ-TRS-03) has no dedicated unit test — `AcknowledgeCaseUseCase`
  needs a `CaseRecordRepository` fake that doesn't exist yet in `testutil/Fakes.kt`, and building
  one just for a one-line passthrough assertion wasn't worth the setup cost right now. Add
  `FakeCaseRecordRepository` if/when a real `AcknowledgementViewModel` test suite is warranted.

### Phase 3 + 3.5 — Report data contract + AIIMS-card template/PDF (DONE, on Opus)
Built together on Opus since 3.5 is the "report-assembly data contract" the brief flagged as
expensive-to-redo. Adopted the user's detailed AIIMS-OP-card layout constraints.
- [x] **Data contract** (`domain/report`): `ClinicalReport` — one progressively-assembled object
      (preliminary → +kernel Phase 4 → +prescription/signature Phase 5, `isFinal` flips). Sub-models
      + `ReportAudience` (WORKER redacts private ailments in the model itself; PHYSICIAN shows all —
      the privacy-flag propagation the brief called out). `ReportFormatter` is pure/no-Android;
      `AssembleReportUseCase` fetches one snapshot from each repo (`.first()`).
- [x] **Rendering** (`presentation/report`): `ReportCanvasRenderer` — ONE `android.graphics.Canvas`
      layout (A5, 420×595 pt), block-based pagination at section boundaries, footer pinned to last
      page. Same renderer drives the Compose preview (`ReportScreen` via `drawIntoCanvas`) AND the
      PDF (`ReportPdfExporter`, native `PdfDocument`) — no bitmap capture, no external lib/iText.
      `Code128` = self-written Code 128B barcode encoder (5 unit tests) for the UID header barcode;
      human-readable UID printed beneath as the authoritative fallback.
- [x] Layout matches the AIIMS-card spec: logo slot / centre title + PHC name + CR No / UID barcode
      header; two-column demographic matrix with divider + "✓ Verified via ABHA" tag; "Chief
      Complaints & Clinical Findings" with verbatim quoted complaint + measurable(◆)/non-measurable(•)
      ailments; "Rx / Advice" numbered medication list; consent + double-underlined physician
      signature ("Reg No: …") + "AI-Assisted, Physician-Verified" footer.
- [x] Every field binds to a real Phase 0 entity — `docs/requirements/report-field-mapping.md` is the
      per-element mapping (REQ-RPT-03). Only non-data elements: logo box + fixed labels.
- [x] Supporting: `Doctor.registrationNumber` (mock NMC reg no in `doctors.json`);
      `PrescriptionRepository`/`KernelReportRepository` read-side (write side feeds Phases 5/4);
      `ReportFormatter.formatMedicationLine` enforces REQ-RX-02 (throws on OD/BD/SOS…).
- [x] Entry point: "View preliminary report" on the Acknowledgement screen → `ReportRoute`
      (WORKER audience). No new Room migration (DB stays v4 — prescription/kernel tables already
      existed from Phase 0; `Doctor.registrationNumber` is asset-only, not a Room column).
- [x] Regulatory: `docs/regulatory-foundation.md` updated with CDSCO Oct-2025 draft classification
      argument (human-in-the-loop → Class B/C not D) + EU MDR Rule 11 (IIa framing = the footer
      disclaimer wording). Per the brief, a docs/framing exercise, no phase code change.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **76 tests, 0 failures** (was 60; +16:
      `ReportFormatterTest` ×11, `Code128Test` ×5). `assembleDebug` + `compileDebugAndroidTestKotlin`
      clean.
- **Known coverage gap (flagged):** `ReportCanvasRenderer` uses `android.graphics.Canvas`/`Paint`,
  which are stubbed in plain JVM unit tests — it's exercised only on-device (manual). The pure
  pieces it depends on (`ReportFormatter`, `Code128`) ARE unit-tested. A Robolectric or instrumented
  render/PDF smoke test is the right next coverage step; not added now (no Robolectric in the build).

### Phase 4 — Kernel output integration (DONE, on Sonnet high)
- [x] `GenerateKernelReportUseCase` — extends the mocked kernel handoff. Not pure random: matches
      `KernelPayload.chiefComplaint` (already-whitelisted, pseudonymized field) against a small
      curated scenario table (fever/respiratory/GI/headache, each with 3 differentials + a
      reasoning paragraph + evidence for/against + a confidence band) with a lower-confidence
      default fallback for anything unmatched — demo-credible mock data, not claimed real
      inference. `requiredHumanVerification = confidenceScore < 0.90` (existing convention).
      Persists via Phase 3's `KernelReportRepository` (write side was stubbed then, used now).
- [x] Wired into `SendingViewModel`: `SendToKernelUseCase`'s previously-discarded return value is
      now captured and fed into `GenerateKernelReportUseCase` right after the kernel handoff delay.
- [x] **AI Assessment Panel** (`presentation/kernelassessment`) — net-new UI (confirmed: no
      pre-existing panel/`AiKernelResponse` existed anywhere in code before this session; the brief's
      "keep the existing pattern" referred to a stale memory). Confidence gauge (`LinearProgressIndicator`,
      red when `requiredHumanVerification`), explainability card (reasoning + evidence for/against),
      red warning banner when verification required, liability checkbox gating Continue. Logs
      `AuditAction.KERNEL_ASSESSMENT_ACKNOWLEDGED`.
- [x] Nav: new `KernelAssessmentRoute` inserted between Sending and Transcription/Acknowledgement —
      Sending now always routes through the panel; the panel's Continue replicates the old
      audioUri-based branch (Transcription if present, else Acknowledgement).
- [x] Report integration confirmed free — `AssembleReportUseCase` (Phase 3) already reads
      `KernelReportRepository.getForCase()`, so once this phase persists a `KernelReportOutput` the
      preliminary report automatically gains its kernel section. Same object, no second document,
      no code change needed on the report side.
- [x] No Room schema change — `kernel_reports` table already existed from Phase 0 (DB stays v4,
      confirmed no new schema JSON generated).
- [x] Docs: REQ-HAN-07 flipped PLANNED → DONE with the "net-new, not extended" correction restated.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **80 tests, 0 failures** (was 76; +4:
      `GenerateKernelReportUseCaseTest` — scenario matching, fallback, confidence-bounds/threshold
      loop over 20 runs, save-failure propagation). `assembleDebug` +
      `compileDebugAndroidTestKotlin` clean.

### Phase 5 — Doctor-response intake foundation (DONE, on Sonnet high)
**Scope correction from the user, applied before building:** the brief's "Doctor Prescription
Screen" assumed the doctor uses this app. They don't — the doctor's review/prescription-authoring
UI runs on a **separate communication channel** (different app/system), out of scope for this
PHC-worker codebase. What actually got built: the **receiving boundary** on our side — mocked now,
architected so a real API/webhook client swaps in later without touching any call site.
- [x] `domain/doctor/DoctorPrescriptionInbox` — the intake interface (`fetchPrescription(caseRecordId)
      → IncomingPrescription?`), same "named mock boundary" pattern as `VitalsSource`/
      `TranscriptionService`. Returns `null` (success, not failure) when the doctor simply hasn't
      responded yet — that's the expected async state, not an error.
- [x] `data/doctor/MockDoctorPrescriptionInbox` — stands in for the real channel. Not a fixed
      canned response: reads the case's `KernelReportOutput` (if any) and picks a decision with a
      realistic distribution (65% AGREE with the kernel's predicted condition, 25% MODIFY to a
      listed differential, 10% REJECT) — demo-credible variety, explicitly still a mock. Returns
      `null` if no doctor is assigned yet (`CaseRecord.assignedDoctorId`).
- [x] `KernelDecision` enum (AGREE/MODIFY/REJECT) added to `Prescription.kernelDecision` —
      **`MIGRATION_4_5`** (DB v4→v5, additive `ALTER TABLE prescriptions ADD COLUMN kernelDecision
      TEXT`), verified schema `5.json`'s column affinity matches the migration exactly. New
      `CaseStatus.PRESCRIPTION_RECEIVED`.
- [x] `ReceiveDoctorPrescriptionUseCase` — fetches from the inbox, persists via Phase 3's
      `PrescriptionRepository` (write side finally used), flips case status. Same free report
      integration as Phase 4: `AssembleReportUseCase` already reads `PrescriptionRepository`, so the
      final report gains diagnosis/medications/signature with zero report-side code change.
      `ReportFormatter`/`ReportCanvasRenderer` extended to also show the doctor's decision line on
      the Rx/Advice block.
- [x] UI entry point: **`PatientSummaryScreen`**, not a new "doctor screen" — the PHC worker returns
      to the patient (via the day-scoped roster, REQ-ROS-02 intact — no new all-patients query) and
      taps "Check for doctor's response (mock)". `PatientSummaryViewModel` now also observes
      `CaseRecordRepository.observeLatestForPatient` (new query) so status updates reactively;
      "View report" appears once any case record exists.
- [x] **Closed a flagged gap along the way:** Phase 2.5 noted `AcknowledgementViewModel`'s
      sync-window test was skipped for lack of a `FakeCaseRecordRepository`. Built it now (needed
      for this phase's own tests anyway) — `testutil/Fakes.kt` gained `FakeCaseRecordRepository` +
      `FakePrescriptionRepository`. `AcknowledgementViewModel` still has no dedicated test (out of
      this phase's scope), but the blocking fake now exists for whoever adds it.
- [x] Docs: REQ-RX-01/03 flipped PLANNED → DONE with the scope-correction note; REQ-RX-02 unchanged
      (already PARTIAL from Phase 3); field-mapping table gained the kernel-decision row.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **87 tests, 0 failures** (was 80; +7:
      `ReceiveDoctorPrescriptionUseCaseTest` ×3, `MockDoctorPrescriptionInboxTest` ×4).
      `assembleDebug` + `compileDebugAndroidTestKotlin` clean.

### Phase 6 — Referral / Transfer flow (DONE, on Sonnet high)
- [x] **Visibility decision (brief required picking one, not leaving it ambiguous):** "Refer to
      Higher Facility" is **always visible, enabled only when eligible** — chosen over hiding it
      entirely, for discoverability. Eligibility = `ClinicalReport.suggestsReferral`: a
      non-measurable ailment's severity ≥ 8/10 (`ReportFormatter.REFERRAL_SEVERITY_THRESHOLD`), OR
      the doctor's `KernelDecision.REJECT` on the AI differential (Phase 5's mock intake feeds this
      for free — no new plumbing needed to wire REJECT into the referral trigger).
- [x] Button lives on **`ReportScreen`**, not a doctor screen — consistent with Phase 5's scope
      correction; the PHC worker is the one deciding to refer, from the same report view they
      already use for the preliminary/final report and PDF export.
- [x] `domain/repository/ReferralRepository`+impl (DAO/entity already existed from Phase 0, unused
      until now), `CreateReferralUseCase` (single PHC-side action — `ReferralStatus` never advances
      past `QUEUED`, no receiving-side system, per brief).
- [x] Confirm bottom sheet: urgency (`UrgencyLevel` `FilterChip`s: ROUTINE/URGENT/EMERGENCY, all
      three already existed as an enum from Phase 0), reason auto-filled from
      `ClinicalReport.referralReasonSuggestion` (diagnosis-based normally; severity- or
      rejection-based wording when those are what triggered eligibility) and editable. Confirm →
      logs `AuditAction.REFERRAL_CREATED` (constant already existed from Phase 0, unused until
      now) → `AlertDialog` confirmation: "Referral sent — Patient UID {uid} queued for CHC/District
      Hospital appointment."
- [x] `sendingPhcId` reuses `Patient.primaryCareClinicName` (no separate PHC-id system exists in
      this app) — a scoping decision, not an oversight.
- [x] No Room schema change — `ReferralEntity`/`referrals` table already existed from Phase 0 (DB
      stays v5).
- [x] Docs: REQ-REF-01 flipped PLANNED → DONE with the visibility decision restated verbatim (per
      the brief's explicit instruction not to leave it ambiguous); field-mapping doc gained a note
      that the referral fields are UI-only (not printed on the canvas/PDF).
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **94 tests, 0 failures** (was 87; +7:
      `CreateReferralUseCaseTest` ×3, `ReportFormatterTest` referral-eligibility ×4).
      `assembleDebug` + `compileDebugAndroidTestKotlin` clean.

**All six phases of the brief are now DONE.** Remaining brief items are explicitly out of this
pass's scope (see "What NOT to build" in the original brief): real ABDM/ABHA API, real backend,
receiving-hospital app, real digital signatures, UI polish.

## Post-brief additions (2026-07-16, user follow-up request)

Three asks after Phase 6 landed: report logo, attachments-in-report, and biometric sign-in.

- [x] **Report logo:** header slot now renders the real institutional logo
      (`res/drawable-nodpi/logo.png` — the same asset already on Home) instead of the "LOGO"
      placeholder box. `ReportCanvasRenderer` gained a `logoBitmap: Bitmap?` constructor param
      (falls back to the placeholder if null/decode-failed, never a blank gap); decoding happens
      in the caller (`ReportScreen` via `produceState`+`Dispatchers.IO`, `ReportPdfExporter` via a
      `by lazy` field) since the renderer deliberately holds no `Context`.
- [x] **Attachments in report:** every consultation attachment (photo/affected-area photo/audio/
      video) now appears in a new "Attachments" report section — same "pass through unmodified"
      posture `KernelPayload.attachments` already has for the kernel (REQ-HAN-06), extended to the
      report. `ClinicalReport.attachments: List<ReportAttachmentEntry>`; `ReportFormatter` labels
      each one per-type-numbered ("Photo 1", "Affected area photo 1", "Audio 1", …); images render
      inline via a caller-supplied `imageLoader: (String) -> Bitmap?` lambda (content-resolver
      decode, "Image unavailable" placeholder on failure); audio/video get a labeled line only — a
      static canvas/PDF page can't play either back.
- [x] **Biometric sign-in (REQ-SEC-03, PARTIAL — up from PLANNED):** tapping "Sign in" on the
      worker Login screen now requires `androidx.biometric.BiometricPrompt`
      (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) to succeed before `AuthSession.signIn` runs.
      `MainActivity` changed `ComponentActivity` → `FragmentActivity` (BiometricPrompt's
      requirement — verified this doesn't break app launch, see on-device check below).
      **Scope, confirmed with the user:** worker login only, not the ABHA patient flow; gate fires
      *after* name+role entry, on the Sign-in tap, not before the form.
      **Known, documented limit:** this verifies "the device owner unlocked the device," not "the
      typed name belongs to this person" — no per-account credential store exists, so it's a
      device-authorization gate, not real per-worker identity binding. Full REQ-SEC-03 (real
      accounts + RBAC) stays open.
      **Deliberate strictness:** a device with no biometric enrolled AND no screen lock at all is
      refused sign-in outright (clear error message), not silently waved through — flagging this
      as an operational requirement (every field device needs a configured screen lock), not
      hiding it.
- [x] **On-device verification** (emulator-5554, no lock screen configured): installed, launched
      cold — no crash from the `FragmentActivity` change, existing session restored normally.
      Signed out → entered name/role → tapped Sign in → got the exact designed "Can't sign in — no
      fingerprint/face/screen lock set up on this device" message, form stayed filled and usable,
      no crash. The BIOMETRIC_SUCCESS path (an emulator with a PIN configured) wasn't exercised on
      this pass.
- [x] Docs: REQ-SEC-03 flipped PLANNED → PARTIAL with the exact scope/limit language above;
      REQ-RPT-02/03 updated for the real logo + attachments; field-mapping table gained two rows.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **99 tests, 0 failures** (was 94; +5:
      `LoginViewModelTest` ×3, `ReportFormatterTest` attachment-mapping ×2). `assembleDebug` +
      `compileDebugAndroidTestKotlin` clean.

## Bottom navigation for PHC worker roles (done, standalone pass after the SaMD demo overhaul)
- [x] 4 bottom-nav tabs (`BottomNavBar.kt`, `BottomNavTab` enum): Home, Patients, Referrals,
      Profile. `PatientSummary` also shows the bar (landing/review screen, not an in-progress
      flow) but isn't itself a tab root, so no tab highlights there (`current = null`).
- [x] **Hide-during-consultation, structurally:** the bar is rendered inside each visible screen's
      own `Scaffold(bottomBar = ...)` slot — Register/ABHA/Consultation/Ailments/Compounder/
      Sending/Transcription/Acknowledgement/KernelAssessment/Consent/EmergencyOverride/Report/
      DoctorList screens were never given a `bottomBar` param, so the bar is absent there by
      construction, not a disabled/greyed state. No global visibility flag anywhere.
- [x] Tab switches reset to a single-tab-root back stack (`backStack.clear(); add(tabRoute)`) —
      same clear+add idiom EmergencyOverride/DoctorList already used to return to Home.
- [x] **Patients tab:** searchable/filterable list, but scoped to the last 7 days
      (`PatientRepository.observeRecentPatients`), not the full patient table — the brief's
      "today's + recent" wording collided with the existing hardening.md data-minimization
      anti-pattern ("no all-patients query exists"); resolved by widening the existing day-scoped
      DAO query's window rather than adding an unbounded one. No schema/DAO change needed — the
      DAO already took arbitrary start/end bounds.
- [x] **Referrals tab:** this device's own sent-referral outbox
      (`ReferralDao.observeAll`/`ReferralRepository.observeAll`, new — DAO previously only had
      `observeForCase`). Real data; status will read QUEUED for every row until a receiving-side
      system exists (Phase 6 already established referrals never advance past QUEUED in this mock).
- [x] **Profile tab:** session name/role, sign-out, offline/sync toggle (same shared
      `ConnectivityViewModel` instance the top status bar uses, not a second source of truth), and
      an audit-trail summary. New read-side `AuditLogRepository`/`AuditLogEntry` (the DAO's
      `observeAll`/`observeByPatientId` existed but were never wired past the DAO — insert side
      stays untouched, REQ-AUD-02 intact) + new `AuditLogDao.observeByUserId` query, filtered to
      the signed-in worker, capped at 20 rows.
      **Flagged, not fabricated:** no PHC identifier exists on the worker session (`UserSession`
      has no PHC field, unlike `Patient.primaryCareClinicName`) — Profile shows name/role only.
- [x] `TODO(nav-role-scoping)` left at `HomeScreen`'s entry point per the open decision flag —
      Compounder-role-specific Home content stays undecided, not silently shipped either way.
- [x] Extracted `PatientRosterRow` to `presentation/common/` (was private/duplicated-in-spirit
      inside `HomeScreen`) so Home's roster and the new Patients tab share one row composable.
- [x] Icons from `material-icons-core` only (already a dependency; no `material-icons-extended`
      added) — Home/List/Send/Person all exist in the core set.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **103 tests, 0 failures** (was 99; +4:
      `PatientsViewModelTest` ×2, `ReferralsViewModelTest` ×1, `ProfileViewModelTest` ×1).
      `assembleDebug` + `compileDebugAndroidTestKotlin` clean. **On-device walk** (emulator-5554):
      bar shows on Home/Patients/Referrals/Profile/PatientSummary (unhighlighted on the latter),
      absent on AbhaEntry; back from AbhaEntry returns to Patients (bar intact, correct tab still
      lit); Patients tab search/roster and Referrals/Profile empty states render real (not mock)
      data.

## Report-capture schema + doctor continuity + consultation history (done, three-part follow-up pass)

### Part A — kernel_reports report-capture addendum
- [x] `kernel_reports` gains `icdCode` (nullable — the mock kernel's structured suggestion, only
      genuinely null on the unmatched/default scenario), `deviceId`/`softwareVersion` (both
      `NOT NULL`, new `DeviceInfoProvider`/`AndroidDeviceInfoProvider` — `Settings.Secure.ANDROID_ID`
      + `BuildConfig.VERSION_NAME`, `buildFeatures.buildConfig = true` newly enabled), `dataQualityScore`
      (proportion of optional `KernelPayload` fields populated), `uncertaintyScore` (mock
      `1 - confidenceScore`), `riskCategory` (new enum, distinct from `UrgencyLevel` — risk vs.
      urgency are different axes), `urgencyLevel` (reuses the existing referral `UrgencyLevel`
      enum — same concept, no duplicate enum). `inferenceTimestamp` renamed to `inferenceEndedAt`
      (`ALTER TABLE ... RENAME COLUMN`) with a new `inferenceStartedAt` alongside it.
      `MIGRATION_5_6`, DB v5→v6. Explicitly out of scope, per the brief: no doctor-facing UI reads
      any of this — report-artifact-only.
- [x] `GenerateKernelReportUseCase` populates every new field per-scenario (plausible ICD-10 codes,
      risk/urgency bands) — not randomized, not left null by laziness.
- [x] `ReportCanvasRenderer`: risk category + urgency level + inference duration + ICD code added
      near the top of the kernel/AI section; device id + app version added as two more rows in the
      existing demographic block's right column (no separate "Encounter Information" section
      existed to put them in — extended the closest existing one instead of inventing a new block).

### Part B — doctor assignment: continuity-of-care, not a worker-driven picker
- [x] **Scope correction, confirmed with the user before building:** the brief described DoctorList
      as a flat, read-only, cross-patient status tracker — a different screen shape than the
      existing single-case doctor-*picker* (`DoctorListScreen` titled "Choose a doctor",
      radio-select + Send, the only call site of `AssignDoctorUseCase`, load-bearing for
      `CaseRecord.assignedDoctorId`/`CaseStatus.SENT_TO_DOCTOR` → Phase 5's doctor-response flow).
      Resolved as: auto-assign fresh/unrelated cases silently (no worker interaction at all);
      default to the same doctor on a worker-flagged follow-up visit (continuity), with a narrow
      same-specialty "switch" override; DoctorList itself becomes the read-only tracker.
- [x] **Grounded in real precedent, per the user's explicit ask** — researched EHR/telemedicine
      provider-continuity patterns before building. "Empanelment/attribution" (AHRQ, HealthTeamWorks,
      Safety Net Medical Home Initiative) is the named pattern matching "default to the same
      provider on a follow-up" — a patient is attributed to whoever coordinated their care last
      time. "Soonest available"/least-busy is AHRQ's documented default for new/unattributed
      patients. eSanjeevani (India's national telemedicine service) confirmed the domestic
      hub-and-spoke precedent for PHC-worker-initiated escalation. The same-specialty scoping on
      the override is this app's own reasonable extension, not a directly-cited industry rule —
      flagged as such rather than overclaiming a source for it.
- [x] `doctors` moved off the `doctors.json` asset into Room (`DoctorEntity`/`DoctorDao`,
      `MIGRATION_6_7` seeds the same 9 mock doctors) — needed for specialty/least-busy queries
      that were awkward against a cached in-memory list. `DoctorAssetDataSource` deleted.
      `encounters.followUpOfEncounterId` (nullable, self-referential, no FK constraint — matches
      the rest of this schema's posture) added in the same migration.
- [x] `ResolveDoctorAssignmentUseCase`: if the new encounter's `followUpOfEncounterId` resolves to
      a prior case whose `assignedDoctorId` is still active → continuity proposal. Otherwise →
      least-busy active doctor (fewest open `SENT_TO_DOCTOR` cases, `CaseRecordDao.observeOpenCaseCount`).
      `sameSpecialtyAlternatives()` backs the narrow override list.
- [x] `AcknowledgementViewModel.onContinue()` (was a bare nav callback with no ViewModel action at
      all) now resolves the assignment itself: continuity → `DoctorAssignmentConfirmRoute` (new
      screen — "Continue with Dr. X?", switch scoped to same specialty, Confirm calls the existing
      `AssignDoctorUseCase` unchanged); auto-assign → calls `AssignDoctorUseCase` directly and
      returns straight to Home, no screen at all, matching case 1 exactly.
- [x] `DoctorListRoute` is now a no-arg, cross-patient tracker (`CaseRecordDao.observeDoctorTrackerRows`
      — case_records × patients × consultations join), reachable from a new "Sent to doctor" button
      on Home (bottom nav is a fixed 4 tabs; this isn't one of them). Read-only: patient name/ID,
      chief complaint, status (`SENT_TO_DOCTOR`→"Awaiting Review", `PRESCRIPTION_RECEIVED`→"Reviewed"
      — collapsed from the brief's 3-word vocabulary since the schema only has 2 real states past
      DRAFT/SAVED_LOCALLY, and inventing a 3rd status value with no behavioral difference would be
      schema bloat, not signal). Tapping a Reviewed row reuses `ReportRoute` read-only — confirmed
      by reading `ReportScreen` that its only edit affordance is the separate referral sheet, not
      the report content itself. No `riskCategory`/`urgencyLevel` reference anywhere in
      `DoctorListScreen`/`DoctorListViewModel` (verified by grep, per the brief's success criterion).
      `GetAvailableDoctorsUseCase` deleted (only call site was the old picker).

### Part C — consultation history on PatientSummary
- [x] `EncounterDao.observeHistoryForPatient` (encounters × consultations × case_records join,
      scoped to one patientId — bounded by that patient's own visit count, not a cross-patient
      pull, so this doesn't reopen the data-minimization boundary the roster queries protect) backs
      a new "Consultation History" section: date, chief complaint, status, most recent first.
      Empty state ("No prior visits.") for a new patient — not a blank section.
      `ConsultationHistoryEntry` domain model.
- [x] Tapping a history row reuses `ReportRoute` (same read-only view Part B's tracker reuses) —
      only when a `caseRecordId` exists for that encounter; an abandoned encounter's row shows
      "Incomplete visit" and isn't clickable.
- [x] The same history list is the source for the new "mark as follow-up" picker: tapping
      "Consultation" on a patient with ≥1 prior visit shows a dialog (pick a prior visit, or "Not a
      follow-up") before proceeding — a new patient skips straight through, no dialog. The pick
      threads through as `followUpOfEncounterId`: `ConsentRoute`/`Compounder` routes →
      `CompounderViewModel` (new 2nd `@Assisted` param, needed a named `@Assisted("...")` identifier
      since both params are `String`) → `StartCaseUseCase` → `EncounterRepository.startEncounter`
      → stamped onto the new `EncounterEntity` row.
- [x] Past encounters stay non-editable by construction — there's no update path on any of the
      entities involved, same immutability posture as the audit log.

### Home
- [x] "Signed in as X (role)" → "Welcome, **X (role)**" (bold via `AnnotatedString`/`SpanStyle`).

### Verification
- [x] New Room migrations exercised for real via `androidx.room:room-testing`'s
      `MigrationTestHelper` (newly wired: dependency + `androidTest.assets.srcDirs` pointing at
      `app/schemas` — this project had no instrumented migration tests before now; earlier phases
      relied on eyeballing DDL-vs-schema-JSON identity). 3 tests, run on-device
      (emulator-5554): full 1→7 chain, `MIGRATION_5_6`'s rename+backfill, `MIGRATION_6_7`'s 9-doctor
      seed — all green.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **109 tests, 0 failures** (was 103; +6:
      `PatientSummaryViewModelTest` ×2 — including Part C's required empty-state case —
      `ResolveDoctorAssignmentUseCaseTest` ×4). `assembleDebug` + `compileDebugAndroidTestKotlin`
      clean. **On-device walk** (emulator-5554, real migration from an existing installed DB, not
      just a fresh schema): Home's "Welcome" text and "Sent to doctor" button confirmed; DoctorList
      tracker opens with the correct empty state. Full encounter→history round trip (register a
      patient, complete a visit, see it appear in Consultation History, mark a second visit as its
      follow-up) not manually walked this pass — no patients existed in the test device's roster at
      verification time; the ViewModel logic for both the empty and populated cases is covered by
      `PatientSummaryViewModelTest` instead. Flagged, not claimed.

## Post-verification fixes (2026-07-17, user found while manually testing)

- [x] **Bug: doctor auto-assignment silently did nothing on a fresh install.** Root cause:
      `MIGRATION_6_7`'s doctor-seed `INSERT`s only run on an *upgrade* — a fresh install creates
      every table straight from the entity schema at the current version and never executes
      migration bodies, so `doctors` was empty. `ResolveDoctorAssignmentUseCase` then failed ("no
      active doctors"), and the old code swallowed that failure and routed Home anyway — every
      case stayed stuck at `SAVED_LOCALLY` forever, with no error surfaced. Fixed with a
      `RoomDatabase.Callback.onCreate` in `DatabaseModule` that seeds the same 9 mock doctors on a
      fresh database, covering the path the migration can't. (Verifying this on an emulator is
      tricky: Android's Auto Backup restores the old app-private DB right after a plain
      uninstall/reinstall, masking the bug — `adb shell pm clear <pkg>` is the way to force a
      genuinely empty database without a restore.)
- [x] **Auto-assignment made visible, not silent.** Every "Send to doctor" tap now routes through
      `DoctorAssignmentConfirmScreen` (continuity or fresh case alike) so the mock-assigned doctor
      is always shown and always switchable (same-specialty scoped) — there's no more silent
      background path, and a resolution failure now shows an error with a way back instead of a
      dead end. `AcknowledgementViewModel` simplified accordingly (it no longer resolves/assigns
      itself — that's the confirm screen's job now).
- [x] ~~**Report "stapling" for returning patients.**~~ **Superseded — scrapped, see next entry.**
      A first attempt stapled every follow-up visit's full report into one scrolling
      preview/PDF (`AssembleReportChainUseCase`). It rendered wrong: stacking multiple full-size
      `Canvas` composables at the same call site inside nested `forEach`/`repeat` loops made
      Compose reuse composition slots, so one report drew as a blank "white gap" while the other
      rendered. The user then decided merged reports were the wrong model entirely.
- [x] **Follow-up visits are grouped, not merged (redesign, replaces the stapling above).**
      Decision (all confirmed with the user): (1) Consultation History collapses each follow-up
      chain to ONE row, represented by the chain's **latest** visit + a "N visits" badge;
      standalone consults stay as a normal single row. (2) Tapping a multi-visit row opens a new
      `ConsultationChainScreen` ("Follow-up history") listing every visit in that chain
      (newest-first, "Latest"/"Follow-up" labels), each opening its **own single-consult report**.
      (3) Reports are never merged; PDF export is per-consult only — the combined multi-visit PDF
      was dropped. `ReportScreen`/`ReportViewModel`/`ReportPdfExporter` reverted to single-report;
      `AssembleReportChainUseCase` deleted.
      - Grouping is a pure domain function `List<ConsultationHistoryEntry>.groupIntoChains()`
        (walks `followUpOfEncounterId` to a root, cycle-guarded), reused by both PatientSummary
        and the chain screen so they can't disagree. `ConsultationHistoryEntry`/`EncounterHistoryRow`
        + the history DAO query gained `followUpOfEncounterId` to make grouping possible.
      - New `ConsultationChainRoute(patientId, rootEncounterId)`; carries the patient banner.
      - **Verified on-device** (emulator-5554): 2-visit chain shows one "2 visits" row on
        PatientSummary → chain screen lists Latest + Follow-up → each opens its own full report,
        no white gap, no merge.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **113 tests, 0 failures** (+4 vs the
      109 after the doctor-continuity pass: `ConsultationChainTest` ×4 covering standalone chains,
      multi-visit collapse ordering, cross-chain sort, and the circular-link termination guard).
      `assembleDebug` + `compileDebugAndroidTestKotlin` clean.

## UX polish + Profile de-clutter + retention docs (2026-07-17, user follow-up)

- [x] **"Which doctor / which dept is in the loop" on every case row.** Consultation-history rows
      (PatientSummary + chain screen) and the doctor-tracker rows (DoctorList) now lead with the
      chief complaint, show the assigned doctor + specialty ("Dr. X · General Physician", or
      "Doctor not yet assigned" before send), and demote the date to a small `labelSmall` line.
      Plumbed via a `LEFT JOIN doctors` in the history + tracker DAO queries (doctors are a Room
      table since Part B), surfaced on `ConsultationHistoryEntry`/`DoctorTrackerEntry` as
      `doctorName`/`doctorSpecialty`.
- [x] **Removed the "Recent activity" audit list from Profile** — it was clutter. The audit log
      still records every clinical action and persists in `audit_log` (insert-only, never deleted);
      only the on-screen section went. `ProfileViewModel` (its sole job was loading that list) was
      deleted; `ProfileScreen` is now stateless. `AuditLogRepository` read-side is retained
      (documented) for a future audit-export surface rather than deleted.
- [x] **`docs/data-retention.md`** — per-table deletion posture (insert-only-locked / soft-delete /
      mutable-no-delete / reference-seed). Records that **no table hard-deletes** today, flags the
      ones that are append-only *by design* (`audit_log`, `ailments` soft-delete), so a future
      change to any of that is a deliberate decision. Referenced from `AuditLogDao`/`ProfileScreen`
      KDoc.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **113 tests, 0 failures** (fakes/history
      fixtures updated for the new doctor columns; no behavioral test change). `assembleDebug` +
      `compileDebugAndroidTestKotlin` clean. On-device re-verify of the new row layout pending
      (build installs clean; the change is presentational + a read-only JOIN).

## Privacy hardening + flow-UX pass (2026-07-17, user follow-up — shared/stolen-tablet + multi-screen worker flow)

Six additive items, no schema/architecture change. Explicitly excluded ID masking (Aadhaar/ABHA) —
considered and dropped by the user before this pass (ABHA isn't the secret, Aadhaar masking is an
input-time concern, not a display rule).

- [x] **FLAG_SECURE on patient-data screens (item 1).** Single-Activity app (Nav3, one backstack of
      route objects) — the flag is window-wide, not per-screen, so it's toggled reactively off the
      backstack's top route in a `LaunchedEffect(currentRoute)` in `AppNavHost`/`MainNavHost`,
      rather than set once per Activity. `Routes.kt` gained `requiresScreenSecurity(route)` +
      `SECURED_ROUTE_TYPES` — every screen with patient-identifying/clinical data (ABHA sign-up/
      login/OTP, Register, MedicalBackground, PatientSummary, the new PatientAudit, Consent,
      Compounder, EmergencyOverride, Consultation, Sending, KernelAssessment, Transcription,
      Acknowledgement, DoctorAssignmentConfirm, ConsultationChain, Report, DoctorList, Referrals).
      Excluded: Home/Login/Patients (names alone, lower sensitivity)/Profile/AbhaEntry (menu only,
      no data yet).
- [x] **Idle auto-lock with re-auth (item 2).** New `IdleLockViewModel` (Activity-scoped, same
      "one shared instance obtained outside any NavEntry" pattern as `ConnectivityViewModel`) —
      75s no-touch timeout flips `isLocked`. `MainActivity` now overrides `onUserInteraction()`
      (the standard Android idiom: fired on the touch-down that begins any gesture dispatched to
      the activity — no custom Compose pointer-input plumbing) and feeds the same instance via
      `by viewModels()`. `IdleLockScreen` (reuses `rememberBiometricAuthenticator`, same as Login's
      sign-in gate) is drawn **over** the still-mounted `NavDisplay` in a `Box`, never removing it
      from composition — this matters: recomposing `NavDisplay` from scratch would tear down and
      recreate every in-flight NavEntry ViewModel (e.g. `CompounderViewModel`), which would call
      `StartCaseUseCase` again and mint a duplicate encounter. `reset()` is called once per fresh
      sign-in so idling on Login before signing in never carries an already-tripped lock forward.
- [x] **Patient-facing audit view (item 3).** DPDP right-to-access gesture, reuses the existing
      audit trail — no new capture logic. `AuditLogRepository.observeForPatient` (new, backed by
      the DAO's pre-existing but previously-unused `observeByPatientId`). New
      `domain/audit/PatientFacingAudit.kt`: `toPatientFacingEntries()` maps every known action
      string (including the pre-existing free-text ones like `"patient_registered"`,
      `"vitals_recorded"`) to a plain sentence; anything unmapped falls back to a generic line,
      never the raw action code. New `PatientAuditRoute(patientId)` +
      `PatientAuditScreen`/`PatientAuditViewModel`, reached from a clearly labeled "Who has seen
      your file" button on PatientSummary (not buried in Profile/settings).
- [x] **Stepper across the worker flow (item 4).** New shared `StepProgressIndicator(current,
      total, label)` in `presentation/common/`. Real flow is 4 steps, not 5 — ABHA (AbhaEntry/
      SignUp/Login/Otp all "Step 1", since they're alternate paths to the same logical step) →
      Registration → Medical background → Ailments & vitals (Compounder combines both into one
      screen already, per the Phase 2.5 scoping decision — not re-split just to hit a step count).
- [x] **Crash-recovery resume (item 5).** Encounter+CaseRecord already persist immediately at
      Compounder's `StartCaseUseCase` call (not on final save), and ailments already persist as
      added — the real gap was only "detect + offer resume on relaunch," not persistence itself.
      New `CaseRecordDao.observeResumableDraftForUser(userId)`: joins `audit_log` (the
      `encounter_started` row's `userId`) to `case_records` (`status = 'DRAFT'`) — no `workerId`
      column exists on `case_records`/`encounters`, so "this worker's" is derived from the audit
      trail rather than a schema change. `HomeViewModel` surfaces it as `resumableEncounter`;
      Home shows a "Resume in-progress consultation?" dialog. `Compounder` route/ViewModel/Screen
      gained `resumeEncounterId`/`resumeCaseRecordId` (both null on a normal fresh start) — when
      set, the ViewModel skips `StartCaseUseCase` (which would mint a *second* encounter for the
      same visit) and re-enters the existing one directly, skipping Consent (already recorded for
      that encounter). New `AuditAction.ENCOUNTER_RESUMED`. Vitals fields, held only in ViewModel
      state until Continue, are honestly NOT recovered on resume (never persisted before Continue,
      unchanged by this pass) — only the encounter/case record and already-added ailments are.
- [x] **Low battery/storage nudge (item 6).** New `presentation/common/DeviceResourceCheck.kt` —
      plain functions reading `BatteryManager`/`StatFs` fresh each call (no new interface/DI
      wiring needed for a leaf platform read; mirrors the existing `rememberBiometricAuthenticator`/
      `isEmulator()` style already in this package). Non-blocking `LowResourceWarningDialog`
      ("Continue anyway" always proceeds) gates the two "start a new consultation" entry points:
      Home's "Register new patient" and PatientSummary's "Consultation" button — never mid-flow.
- [x] **Unrelated pre-existing bug found and fixed in passing:** `DoctorListScreen.kt:67` had a
      stray `Column(modifieris a = Modifier...)` typo (not caused by this pass — confirmed via
      `git diff` that it predated any edit here) breaking compilation; corrected to `modifier =`.
- [x] **Verified:** `./gradlew testDebugUnitTest` green — **125 tests, 0 failures** (was 112; +13:
      `IdleLockViewModelTest` ×5, `RoutesSecurityTest` ×3, `PatientFacingAuditTest` ×3,
      `HomeViewModelTest` resumable-encounter cases ×2). `assembleDebug` +
      `compileDebugAndroidTestKotlin` clean. **Not yet walked on-device this pass** (compile +
      unit-test verification only) — on-device checks still needed: screenshot attempt on a
      secured vs. unsecured screen, the 75s idle-lock → biometric unlock → in-progress Compounder
      state survives, resume prompt after a real process kill mid-consultation, and the stepper's
      visual placement across all four flow screens.

## Offline-first doctor-send fix + auto-sync (2026-07-19, user follow-up)

Bug report: toggling offline, registering a patient, and confirming a doctor still sent the case
immediately — the offline toggle was cosmetic (only gated the "Sync now" button's `enabled`), never
checked by the actual send path. Also covers the user's second stated scenario (manually toggling
offline to pre-empt a chaotic government-protocol-push window) with the same fix, and confirms
against `docs/data-retention.md`/`docs/sync-design.md` that no separate "DB snapshot" mechanism is
needed — Room/SQLite's existing durability + this app's no-hard-delete posture already satisfy the
"view a patient's full history no matter how long offline" requirement.

- [x] **`ConnectivityController`** (new `domain/connectivity` singleton) — single source of truth
      for online state (manual toggle && real `NetworkMonitor`), replacing duplicated logic that
      previously lived only in `ConnectivityViewModel` (now a thin wrapper delegating to it, so
      non-UI callers like use cases/other singletons can read the same state).
- [x] **`CaseStatus.PENDING_SYNC`** (new, between `SAVED_LOCALLY` and `SENT_TO_DOCTOR`) — the
      per-record queued state `docs/sync-design.md` recommends, scoped to the one place data
      crosses a boundary today (doctor assignment). `CaseRecordRepository.assignDoctor(caseRecordId,
      doctorId, isOnline)` now takes the connectivity signal and sets `PENDING_SYNC` instead of
      `SENT_TO_DOCTOR` when offline; `sendAllPendingCases()`/`observePendingSyncCount()` added
      (single conditional `UPDATE ... WHERE status='PENDING_SYNC'` — idempotent, no duplicate sends).
- [x] **`DoctorAssignmentConfirmViewModel.onConfirm()`** checks real connectivity before confirming.
      Offline → doctor assigned locally (`PENDING_SYNC`), screen shows "No network — case saved
      locally, will send when you Sync Up" instead of silently sending and auto-navigating away.
      `PatientSummaryScreen` shows the same queued state for a `PENDING_SYNC` case.
- [x] **Auto-sync on reconnect** — `MockSyncStatus` now also watches `ConnectivityController.isOnline`
      for the offline→online transition (real network back, or the worker flipping the manual
      toggle) and fires `syncNow()` automatically when anything is queued, instead of requiring the
      worker to remember to tap the button. The button still works for "send right now." `syncNow()`
      itself now refuses to run while offline (was previously always-succeeds UI theater); its
      `pendingCount` now reads the real queue instead of being hardcoded to 0.
- [x] Checked against the user's "database snapshot" question: no whole-DB snapshot exists or is
      needed. `docs/data-retention.md` confirms every clinical table is insert-only/soft-delete/
      mutable-no-delete — full patient history stays on-device indefinitely regardless of how long
      offline, satisfying that requirement already. `docs/sync-design.md` explicitly recommends
      against a whole-DB-image approach in favor of the per-record pattern above. The only real
      limit is the deliberate 7-day/today-only roster *list* window (REQ-ROS-02/H-04, privacy
      data-minimization, identical online or offline) — confirmed with the user to leave as-is.
- [x] Docs updated: `docs/sync-design.md` status block, `docs/requirements/software-requirements.md`
      REQ-SYN-01/02, `docs/requirements/traceability-matrix.md` REQ-SYN-01/02 rows,
      `agent_docs/hardening.md`'s offline-toggle line (was "doesn't need `NetworkMonitor` wired to
      anything real yet" — no longer true).
- [x] **Verified:** `./gradlew testDebugUnitTest` — **129 tests, 1 pre-existing unrelated failure**
      (`RoutesSecurityTest`, caused by this session's separately-uncommitted
      `FeatureFlags.SCREEN_SECURITY_ENABLED = false` dev toggle, not touched here). +6 vs the 125
      before this pass, all in `MockSyncStatusTest`: offline-refusal, sends-every-queued-case,
      auto-syncs-on-reconnect, does-not-auto-sync-from-cold-start-already-online (plus the 2
      pre-existing). `assembleDebug` clean.

## Orphaned-draft resume bug fix (2026-07-20, user bug report)

Bug report: sending one consultation ended up producing two case records for two different
doctors — the intended doctor's case sat empty/"awaiting review" forever, and an unrelated doctor
got the mock report. Traced root cause: `StartCaseUseCase`/`createDraft` always minted a brand-new
`DRAFT` case for a patient with no check for an existing unfinished draft; any earlier abandoned
attempt (worker backed out mid-flow) sat forever as `DRAFT`. `HomeViewModel.observeResumableDraftForUser`
watches for `DRAFT` rows continuously in the background regardless of whether Home is visible, so
the instant a *real, successful* send finishes and `DoctorAssignmentConfirmScreen.onDone` jumps
back to Home, that unrelated stale draft's resume prompt fires immediately — reading as if the
send itself got interrupted. Resuming it re-enters the flow on a *different* `caseRecordId` for the
same patient, and least-busy doctor auto-assignment (`ResolveDoctorAssignmentUseCase`) then picks a
different doctor since the first doctor's open-case count just went up — producing the second,
independent case record. Not an ID-matching or race-condition bug in the send/report path itself
(`MockDoctorPrescriptionInbox`/`ReceiveDoctorPrescriptionUseCase` are correctly scoped by an
explicit `caseRecordId`); the actual gap was the missing "abandon a stale draft" cleanup step.

- [x] **`CaseStatus.ABANDONED`** (new) — additive, Room stores the enum by name string so no
      migration needed.
- [x] **`CaseRecordDao.abandonDraftsForPatient(patientId, updatedAt)`** — single conditional
      `UPDATE ... WHERE patientId = :patientId AND status = 'DRAFT'`.
- [x] **`CaseRecordRepositoryImpl.createDraft`** now calls `abandonDraftsForPatient` before
      inserting the new draft, so a leftover `DRAFT` for that patient can never resurface via
      `observeResumableDraftForUser` (query is scoped to `status = 'DRAFT'`) once a genuinely new
      visit starts. `ABANDONED` rows stay invisible to the doctor-tracker query
      (`status IN ('SENT_TO_DOCTOR', 'PRESCRIPTION_RECEIVED')`) but still show honestly in
      consultation-history (`historyLabel()` → "Abandoned — restarted as a new visit"), not hidden.
- [x] Updated the two exhaustive `when` blocks on `CaseStatus` (`CaseStatusDisplay.kt`:
      `doctorTrackerLabel`/`historyLabel`) for the new case — compiler would otherwise refuse an
      unhandled branch.
- [x] Docs updated: `agent_docs/spec.md` `CaseRecord.status` list + note, this entry.
- [x] **Verified:** `./gradlew :app:compileDebugKotlin` clean. No existing unit tests cover
      `StartCaseUseCase`/`CaseRecordRepositoryImpl` yet.

## Investor demo auto-fill (done, 2026-07-20)
- [x] New `data/mock/DemoPatientProfile.kt` — single Kotlin `object` holding a complete,
      clinically plausible rural-India patient persona: **Priya Sharma, 34F, Shivpuri MP**,
      presenting with 3-day fever, productive cough, and mild breathlessness. Covers every
      field across all four workflow screens.
- [x] `RegisterActions.fillDemoData()` / `RegisterViewModel.fillDemoData()` — pre-fills all
      17 registration fields (name, DOB, sex, mobile, address, blood group, Aadhaar, ABHA,
      clinic name, referring physician) from `DemoPatientProfile` in one call.
- [x] `MedicalBackgroundActions.fillDemoData()` / `MedicalBackgroundViewModel.fillDemoData()`
      — bulk-inserts 2 medical history items, 2 medications (iron supplement + paracetamol),
      1 drug allergy (penicillin → rash), 2 family history entries, full social history.
- [x] `CompounderActions.fillDemoData()` / `CompounderViewModel.fillDemoData()` — fills main
      concern, all 9 vitals fields, capture method, and the new ailment form.
- [x] `ConsultationActions.fillDemoData()` / `ConsultationViewModel.fillDemoData()` — fills
      main concern, symptom onset, duration bucket, severity (6/10), aggravating/relieving
      factors, impact on daily activities, relevant history.
- [x] Each screen gains a **"👤 Fill demo patient data"** `OutlinedButton` right below the
      step-progress indicator; tap once, every field populates, tap Next/Continue to advance.
- [x] `DemoPatientProfile` imports only domain enum constants (`MedicalHistoryCategory`,
      `MedicationKind`, `AllergyCategory`) — verified all enum values exist in the schema
      (`PAST_ILLNESS` was wrong initially, corrected to `HOSPITALIZATION`).
- [x] **Verified:** `./gradlew :app:compileDebugKotlin` green (only pre-existing deprecation
      warnings; zero errors).

## UI terminology: "Chief complaint" → "Main concern" (done, 2026-07-20)
- [x] Replaced every **user-visible** occurrence of "Chief complaint" / "chief complaint"
      across the presentation layer with "Main concern". Internal code/DB field names
      (`chiefComplaint`, DB column) intentionally left unchanged — no schema migration needed.
- [x] Files updated: `ConsultationScreen` (section heading, field label, voice-button text,
      review dialog), `CompounderScreen` (field label), `DoctorListScreen` (fallback text),
      `ConsultationChainScreen` (fallback text), `PatientSummaryScreen` (follow-up picker
      dialog), `ReportFormatter` (report narrative strings).
- [x] Docs updated: `docs/requirements/report-field-mapping.md` — section heading corrected
      to the actual renderer value ("Primary Ailments & Clinical Findings"), chief-complaint
      row updated to "Main concern" with a note that the DB field name is unchanged.
      `docs/quality/design-history-file.md` — new Change log section with both entries.

## Real kernel API integration (2026-07-21)

`GenerateKernelReportUseCase` now calls a real local FastAPI + XGBoost kernel as its primary
path, with the Phase 4 mock as an automatic fallback — not a replacement of the mock, an addition
in front of it.

- [x] **`domain/kernel/RemoteKernelSource`** — new domain interface (`assess(payload, patientAge,
      patientSex) → KernelAssessmentResult`), same "named mock/real boundary" pattern as
      `VitalsSource`/`TranscriptionService`. `KernelAssessmentResult` is a plain domain model —
      `GenerateKernelReportUseCase` never imports Retrofit types.
- [x] **`data/remote/`** — `RetrofitKernelSource` (impl), `api/KernelApiService` (`POST /v1/assess`),
      `dto/KernelAssessmentRequestDto`+`KernelAssessmentResponseDto`+`DifferentialDto`+
      `ModelMetadataDto` (Gson `@SerializedName`, snake_case wire format). Request carries only
      pseudonymized clinical signals (case token, age, sex, vitals, computed BMI) — no identity
      fields, same posture as `KernelPayload`. BMI is computed from `vitals.weightKg`/`heightCm`
      when present, else defaults to 22.0 (normal); other missing vitals default to clinically
      unremarkable values (BP 120/80, HR 72, glucose 100, SpO2 98) rather than nulls, since the
      classifier requires all fields.
- [x] **`di/NetworkModule`** — Retrofit + OkHttp + Gson stack, base URL
      `http://10.203.3.29:8000/` (LAN IP of the host machine running the FastAPI server, for
      physical-device testing over Wi-Fi — not the emulator loopback). Conservative timeouts
      (connect 10s, read/write 30s) so real inference has time to complete while failures still
      surface quickly enough for the fallback to kick in. `AndroidManifest.xml` gained
      `INTERNET` permission + `usesCleartextTraffic="true"` (plain HTTP, acceptable for this local
      dev/demo server — production would need HTTPS).
- [x] **`GenerateKernelReportUseCase.tryRealApi`** — wraps the real call, catches any exception
      (IOException/HttpException/timeout/server offline/parse error), logs, returns null on
      failure. The `invoke()` entry point tries the real API first, falls back to the unchanged
      Phase 4 mock scenario table (`generateMock`) on any failure — the app never crashes when the
      ML server is unreachable. `triage_urgency` string maps to the existing `UrgencyLevel` enum;
      `riskCategory` derived from urgency + confidence (no separate risk field on the real
      response contract).
- [x] `invoke()` gained optional `patientAge`/`patientSex` params (both nullable, default to 30/"U"
      when absent) — required by the classifier but not part of `KernelPayload` (keeps
      `SendToKernelUseCase`'s pseudonymization boundary intact). `SendingViewModel` now fetches the
      patient record to pass these through.
- [x] `libs.versions.toml`/`app/build.gradle.kts` gained Retrofit 2.11.0 + OkHttp logging
      interceptor 4.12.0.
- [x] Docs updated: `docs/requirements/software-requirements.md` REQ-HAN-07 (primary/fallback
      path description), `docs/requirements/traceability-matrix.md` REQ-HAN-07 row (new files),
      `agent_docs/hardening.md` (AI Assessment Panel note + `ai_kernel_version` gap flag).
- **Known gap (flagged, not fabricated):** no per-record marker of whether a given
  `KernelReportOutput` came from the real API or the mock fallback — "mocked" is no longer
  all-or-nothing per case, but nothing on the persisted record or the report distinguishes the two
  paths. Relevant if/when `ai_kernel_version` (see `agent_docs/hardening.md`) gets built.
- [ ] Not yet verified against a real running FastAPI server this pass (no server available) — the
      fallback path is what's actually been exercised. On-device/emulator test against a live
      kernel server at `10.203.3.29:8000` is the next verification step.

## /api/v1/evaluate NLEM-treatment integration + physician AGREE/MODIFY/REJECT feedback (done, 2026-07-24)

New real backend call, separate concern from `/v1/assess` (kept, unchanged, still the confidence/
differential source). `/api/v1/evaluate` returns NLEM drug/dosage/brand-mapping/vitals-triage —
no mock fallback for this one by design (failure just means the report/prescription omits that
section). Feeds a new physician-review loop that mirrors SaMDClassifier's `refine_diagnosis.py`
`DiagnosisFeedback` schema, for a future model-retraining pipeline (capture-only today, no backend
reimport endpoint yet).

- [x] **Domain models:** `EvaluateReportOutput` (+`EvaluateDiagnosticSummary`/`EvaluateRankedCandidate`/
      `EvaluateNlemTreatment`/`EvaluateBrandMapping`/`EvaluateSafetyAndTriage`/`EvaluateVitalsTriage`/
      `IndianBrandSuggestion`), `DiagnosisFeedback`+`PhysicianDecision` (AGREE/MODIFY/REJECT).
      `Evaluate*` prefix throughout to avoid clashing with the pre-existing `Kernel*` (`/v1/assess`)
      types.
- [x] **Data layer:** `RetrofitEvaluateSource`/`EvaluateKernelSource` (domain boundary, same pattern
      as `RemoteKernelSource`), `ClinicalApiService` (`POST /api/v1/evaluate`), DTOs
      (`EvaluateRequestDto`/`EvaluateReportDto`/`EvaluateErrorDto`). `EvaluateReportEntity`/Dao
      (payload stored as one Gson JSON blob) + `DiagnosisFeedbackEntity`/Dao.
      `EvaluateReportRepository`/Impl, `DiagnosisFeedbackRepository`/Impl.
      `GenerateEvaluateReportUseCase` — fires alongside `GenerateKernelReportUseCase` in
      `SendingViewModel`, persists on success, logs `AuditAction.EVALUATE_RESPONSE_RECEIVED` (full
      raw response) or `EVALUATE_RESPONSE_FAILED` on failure.
- [x] **India-brand lookup:** `GeminiBrandLookupSource`/`BrandLookupSource` (`GeminiApiService`,
      `GeminiNetworkModule`) — best-effort AI suggestion of a top India-manufactured brand for the
      NLEM-recommended generic drug. `GEMINI_API_KEY` read from `local.properties` (git-ignored) via
      `providers.fileContents` in `app/build.gradle.kts` (chosen over `File.inputStream()` so the
      Gradle configuration cache tracks the file as a build input — otherwise editing the key alone
      wouldn't invalidate a stale cached `BuildConfig` value).
- [x] **Room v8→v10:** `MIGRATION_8_9` (`evaluate_reports` table, additive), `MIGRATION_9_10`
      (`diagnosis_feedback` table, additive). Schemas `9.json`/`10.json` exported.
- [x] **`RetrofitKernelSource` sex-field fix:** backend checks `sex.upper() == "M"` exactly;
      `Patient.biologicalSex` is a full word ("Male"/"Female"). Was passed through unnormalized —
      now `patientSex.take(1).uppercase()`, matching `RetrofitEvaluateSource`'s existing
      normalization.
- [x] **`KernelAssessmentViewModel`/Screen:** new unified `AssessmentDisplay` — sourced from
      `EvaluateReportOutput` first (real inference, per-candidate confidence/reasoning), falling
      back to the old `KernelReportOutput` (`/v1/assess`, has its own REAL_INFERENCE/MOCK_FALLBACK
      split) only when no evaluate output exists yet. Old direct `KernelReportOutput` UI state
      replaced; audit payload on continue now logs `isMockFallback`/`sourceLabel` instead of the
      old `inferenceSource` field.
- [x] **Doctor review redesign — `ReceiveDoctorPrescriptionUseCase` (async mock-inbox polling)
      replaced by `SubmitDoctorDecisionUseCase`** (synchronous, on-device): `PatientSummaryScreen`'s
      "Check for doctor's response" button is now "Review AI diagnosis" → opens a picker showing the
      `EvaluateReportOutput` top candidate, AGREE/MODIFY/REJECT buttons. MODIFY/REJECT require a
      manual drug name + dosage (with an optional Gemini brand-name lookup button); AGREE needs no
      extra input. Confirm calls `SubmitDoctorDecisionUseCase`, which persists a `DiagnosisFeedback`
      row (`AuditAction.DIAGNOSIS_FEEDBACK_RECORDED`) and builds the final `Prescription` from
      either the AI candidate (AGREE) or the manual entry (MODIFY/REJECT).
      `PhysicianDecision.outcomeExplanation()` — investor-demo-facing copy explaining what each
      decision means for the training pipeline (AGREE → confirmed training example, MODIFY → new
      training example from the physician's own entry, REJECT → discarded, no reliable ground
      truth).
      **`MockDoctorPrescriptionInbox` still exists** (Phase 5's original mock inbox path, used only
      as `IncomingPrescription` fallback plumbing) but its diagnosis/medication now source PRIMARILY
      from `EvaluateReportOutput` (NLEM drug/dose/brand) when present, falling back to the old
      `KernelReportOutput`/static-Paracetamol behavior otherwise.
- [x] **`RegisterScreen` demo-persona picker:** the single "Fill demo patient data" button is now a
      dropdown (`DemoPatientProfile.PERSONAS`) + button — `DemoPatientProfile.select(index)` before
      `fillDemoData()`. `DemoPatientProfile.kt` grew substantially (598-line diff) to hold multiple
      personas instead of one hardcoded patient.
- [x] `ClinicalReport`/`ReportFormatter`/`AssembleReportUseCase`/`ReportCanvasRenderer` all gained an
      `evaluateOutput: EvaluateReportOutput?` alongside the existing `kernelOutput` — same
      "progressively assembled, null until available" posture as every other report section.
- [x] **Verified against the live backend for real:** started the FastAPI + XGBoost server on the
      dev host (had to fix a real backend bug first — `SaMDClassifier/app.py` imported
      `xgboost`/`shap` before the torch-dependent RAG chain, corrupting OpenMP DLL state on Windows;
      reordered two import lines), curl-tested `/api/v1/evaluate` directly against 5 different
      symptom/vitals combos — diagnosis, NLEM treatment, and vitals-triage grading all confirmed
      correct against the real model. Base URL is `http://10.16.4.182:8000/` (confirmed, matches the
      current dev host's LAN IP).
- [x] **Removed the "Kernel AI Assessment" canvas block, added bolding + inference time.**
      `ReportCanvasRenderer.kernelBlock` (old `/v1/assess`-sourced, mock-fallback-capable) deleted
      entirely from `buildBlocks()` — superseded by the evaluate section, which has no mock path.
      `evaluateBlock` rewritten: diagnosis/drug/brand lines and urgent/human-review/pediatric flags
      now render bold (urgent ones bold red, new `boldBodyPaint`/`urgentPaint`) — the findings a
      reviewing physician needs to see first. Inference time moved to a single small line at the
      very bottom (reference only, not a metric to act on).
- [x] **Gemini brand lookup — two real bugs found and fixed, plus company name added.**
      (1) `gemini-2.0-flash` had zero quota on the provided key (`RESOURCE_EXHAUSTED`, `limit: 0`) —
      switched to `gemini-2.5-flash`. (2) `gemini-2.5-flash`'s "thinking" mode measured ~5.6s
      latency, right past the original 6s OkHttp timeout — silent timeouts caused "Not available"
      even with a valid key; added `generationConfig.thinkingConfig.thinkingBudget: 0`
      (`GeminiGenerationConfigDto`/`GeminiThinkingConfigDto`, new), dropping latency to ~0.7s, and
      raised timeouts to 10s/12s for margin anyway. Prompt now asks for brand **and** manufacturer
      (`"BrandName | CompanyName"`, biased toward real Indian pharma companies e.g. Cipla/Sun
      Pharma/Jagsonpal) — new `IndianBrandSuggestion(brandName, companyName)` replaces the old bare
      `String?` everywhere it's consumed (`EvaluateReportOutput.topIndianBrand`, canvas, prescription,
      `PatientSummaryScreen`'s brand-lookup button).
- [x] **Doctor-review placement corrected.** First built (wrongly) on `KernelAssessmentScreen`
      (worker-facing, pre-diagnosis) — reverted there in full, then rebuilt correctly on
      `PatientSummaryScreen`'s doctor-response flow (the "doc-sided" screen, per the user). Button
      renamed "Check for doctor's response (mock)" → "Review AI diagnosis (doctor)".
- [x] **Dataset-safety fix for the refinement feedback.** Checked `SaMDClassifier/train_model.py`/
      `train_symptom_classifier.py` directly: neither reads drug/brand/company columns at all.
      Found `DiagnosisFeedback.physicianFinalDiagnosis` was hardcoded null always — MODIFY and AGREE
      produced identical training-relevant records. Fixed: new `TRAINED_ICD_CANDIDATES` (the 18
      classes `symptom_model.json` actually trained on) dropdown, shown only on MODIFY, validated in
      `SubmitDoctorDecisionUseCase` to be one of the 18 or dropped to null; new `clinicalNote`
      free-text field (`MIGRATION_10_11`, DB v10→v11), explicitly separate, never reimportable.
      Confirmed drug/dosage/brand flow only into `Prescription`/`MedicationLine`, never
      `DiagnosisFeedback` — no code path crosses that boundary either direction.
- [x] `GEMINI_API_KEY` confirmed present in `local.properties` (user-provided) and confirmed reaching
      `BuildConfig.GEMINI_API_KEY` after fixing a Gradle config-cache bug (`local.properties` reads
      weren't tracked as configuration-cache inputs via plain `File.inputStream()` — switched to
      `providers.fileContents` so editing the key alone correctly invalidates the cache).
- [x] Test suite re-run repeatedly across this whole pass — **133 tests, 0 failures**, final state.
      `PatientSummaryViewModelTest`, `MockDoctorPrescriptionInboxTest`, `ReportFormatterTest`,
      `Fakes.kt` all updated for the new constructor/state shapes.
- [x] Docs updated: `software-requirements.md` (new REQ-EVL-01/02/03, REQ-RFN-01/02, rewrote
      REQ-RX-01/03), `traceability-matrix.md` (new rows, orphaned-but-passing note for the unused
      mock inbox, test count), `report-field-mapping.md` (removed Kernel AI Assessment row, added
      AI Clinical Evaluation section), `risk-management-file.md` (H-02/H-09 updated, new H-11 for
      the Gemini external dependency, H-10 extended), `data-retention.md` (DB v11, new tables),
      `regulatory-foundation.md` (kernel no longer "mocked"), `design-history-file.md` (6 new
      change-log entries).
- No physician-side auth/identity captured on `DiagnosisFeedback` (matches the rest of the app's
  mock-login posture — same worker device, no separate doctor account) — unchanged, still true.

## Doc corrections — Environments section + DB v12 propagation (2026-08-14)
- [x] `agent_docs/CLAUDE.md`: Networking section now says base URLs come from `BuildConfig`
      fields (flavor-scoped), not a hardcoded IP. New `## Environments` section after
      `## Connectivity` with per-flavor table (URLs, applicationId suffix, cleartext posture).
      "Build flavors" stub under Stack now points at it instead of duplicating.
- [x] `agent_docs/spec.md`: package structure updated to the real current `presentation/`
      module list (26 modules, checked against the directory, not the original 8). Screen flow
      section reframed as the original mockup shape with a note pointing to PROGRESS.md as the
      canonical current-status source.
- [x] `docs/data-retention.md`: "current schema version 11" → "version 12".
- [x] DB version (v12/`MIGRATION_12_13`) in CLAUDE.md/spec.md was already correct from the prior
      doc pass — no further change needed there.

## Gradle product flavors: dev/staging/prod (2026-08-14)
- [x] `flavorDimension "environment"` with `dev`/`staging`/`prod` flavors in `app/build.gradle.kts`.
      Flavor-scoped `buildConfigField`: `KERNEL_BASE_URL`, `BACKEND_BASE_URL`,
      `ABHA_BACKEND_BASE_URL`, `ENVIRONMENT`. Dev = current LAN IPs over HTTP. Staging/prod =
      HTTPS placeholder URLs (`staging.samd.example.com` / `api.samd.example.com`) pending real
      infra. `dev`/`staging` get `applicationIdSuffix` (`.dev`/`.staging`) so all three install
      side by side on one device; `prod` keeps the bare applicationId.
- [x] `android:usesCleartextTraffic="true"` moved off the main manifest into a new
      `src/dev/AndroidManifest.xml` manifest override — only the dev flavor gets cleartext,
      staging/prod inherit the platform default (blocked).
- [x] `KERNEL_BASE_URL` in `NetworkModule.kt` already read from `BuildConfig`, not hardcoded — no
      change needed there. `BACKEND_BASE_URL` has no consumer yet (no `backend/` Retrofit service
      exists) — left unwired until the backend is scaffolded, per YAGNI.
- [x] `GEMINI_API_KEY` was already a flavor-independent `buildConfigField` sourced from
      `local.properties` in `defaultConfig` — no change needed.
- [ ] Skipped: bumping compileSdk/targetSdk to 36 — both are already at 37 (hardcoded in
      `app/build.gradle.kts`, not in `libs.versions.toml`); 36 would be a downgrade. User
      confirmed: skip.
- [x] Verified: `assembleDevDebug`, `assembleStagingDebug`, `assembleProdDebug` all succeed.
      `testDevDebugUnitTest` — 133 tests ran, 1 pre-existing failure (`RoutesSecurityTest`,
      confirmed failing on baseline `master` too via `git stash` + `./gradlew test`, unrelated to
      this change) — not touched.

## Docs refresh — backend planning + DB v12 (2026-08-14)
- [x] Doc-only edits, no code touched. `agent_docs/CLAUDE.md`: DB version bumped v11→v12 (next
      migration `MIGRATION_12_13`), new "Backend (in progress)" and "Build flavors" stack
      subsections, `BACKEND_BASE_URL`/`ABHA_BACKEND_BASE_URL` noted in Networking, `backend/` row
      added to file placement table, new anti-pattern against hardcoded base URLs.
- [x] `agent_docs/spec.md`: DB version reference updated to v12, ABHA fields noted as landing in
      `MIGRATION_12_13` under `Patient`, backend Pydantic-model mirroring note added to Data models.
- [x] `agent_docs/hardening.md`: "Explicitly later" split into "Approaching (this quarter)"
      (RBAC, WorkManager sync, `ai_kernel_version`) and "Still deferred" (AWS infra, QMS). New
      "Backend security (planned)" section added.

## Investor Demo Bug Fixes (2026-07-24)
- [x] Fixed an issue where the Obesity mock persona resulted in "No drug recommendation" because its match (0.605) did not pass the ML backend's strict safety confidence threshold (< 0.6). Substituted the mock persona in `DemoPatientProfile` with "Type 2 Diabetes", which reliably returns an NLEM treatment (Glimepiride) and triggers Gemini brand mapping successfully for the investor demo.
- [x] Fixed an issue in `ResolveDoctorAssignmentUseCase` where the new Type 2 Diabetes persona was falling back to the default/least-busy doctor (Neurology) instead of being correctly assigned to Endocrinology. Updated the mapping logic to match against the plain text `primaryAilmentName` ("Type 2 diabetes mellitus") rather than the ICD-10 code ("E11").

## Backend Phase 1 scaffold (2026-08-16, FastAPI)

First backend implementation session. Contract and PRD from the planning session
(`docs/backend/api-contract.md`, `docs/backend/backend-prd.md`) are now partly built.

- [x] `backend/core/` FastAPI service: Python 3.12, SQLAlchemy 2.0 async + asyncpg, Pydantic v2,
      Alembic, python-jose, bcrypt, structlog. Versions pinned exactly in `pyproject.toml`, no
      ranges, mirroring the `libs.versions.toml` discipline.
- [x] `backend/docker-compose.yml` (FastAPI + PostgreSQL 16, no Redis) on port 8080, matching the
      dev flavor's `BACKEND_BASE_URL`. Multi-stage Dockerfile, non-root uid 10001.
- [x] Error envelope: RFC 9457 `application/problem+json`, the full `SAMD-{DOMAIN}-{NNNN}`
      registry from api-contract.md section 9.1 as an enum, handlers for SamdError, validation,
      HTTP, database, and unexpected. Validation details never echo the submitted value.
- [x] Request-ID middleware (adopts a valid inbound UUID4 or mints one, echoes the header,
      binds it to structlog), HTTPS-enforcement middleware (`/health` exempt), audit middleware.
- [x] structlog JSON to stdout with a PHI redaction processor; request and response bodies are
      never logged at any level in any environment.
- [x] Auth: login, refresh with rotation and reuse detection, logout, me, change-pin. HS256 (D-8),
      access 1 h, refresh 7 d. bcrypt cost 12. Login lockout (5 attempts, 15 min) as columns on
      `user_accounts`, not Redis.
- [x] `worker_id` provisioning reproduces `MockAuthSession.stableUserId` exactly
      (`sha256(name.trim().lowercase() + "|" + ROLE)`, first 16 hex chars) so audit identity is
      continuous across the mock-auth to real-auth cutover. Test asserts the derivation.
- [x] D-3 implemented: `app/scripts/seed_accounts.py` provisions facilities and workers, prints a
      one-time PIN, sets `must_change_pin`. Until changed, every endpoint except me/change-pin/
      logout returns `SAMD-AUTH-1008`.
- [x] Append-only hash-chained `audit_events`, one chain per facility over device-origin and
      server-origin rows. Three enforcement layers: no mutation method in the service, no mutation
      route, and a plpgsql trigger that raises on UPDATE/DELETE (a trigger, not only a GRANT,
      because a GRANT does not restrain the owner). Appends serialised by
      `pg_advisory_xact_lock` per facility. Closes the H-07 residual.
- [x] Alembic migration 0001: facilities, user_accounts, devices, refresh_tokens, audit_events,
      abha_transactions, plus the pgcrypto extension. `abha_transactions` is created now, in
      Phase 1, shaped from the real ABDM V3 bodies in `abha api docs/`, so Phase 5 needs no
      migration sequenced against Phases 2 to 4.
- [x] `.github/workflows/backend-ci.yml`: ruff check, ruff format, mypy strict, alembic upgrade,
      `alembic check` (a model change with no migration fails the build), pytest against a
      PostgreSQL 16 service container, docker build plus a non-root uid assertion. Path-filtered
      on `backend/**`; `android-ci.yml` and `android-release.yml` untouched.
- [x] 60 tests passing. Verified locally end to end against a real PostgreSQL and a live uvicorn:
      health, login, the forced PIN change, chain revocation on PIN change, refresh rotation,
      reuse detection revoking the whole chain, and the audit chain linking correctly.

Three real bugs found and fixed by running it rather than by reading it:

1. **Self-deadlock on refresh reuse.** `rotate_refresh` revoked the chain on the request session
   and then raised; the route opened a second session to redo the revocation durably and blocked
   forever on the first transaction's row locks. The service now only raises, and the route owns
   the out-of-band revocation.
2. **`verify_chain` could be satisfied by its own cache.** A tampered row was not detected when
   the verifying session had the ORM object in its identity map. Added `populate_existing`, so a
   verifier always reads what is on disk.
3. **Duplicate `X-Request-ID`.** The middleware appended while the error handlers also set it,
   producing one comma-joined header value. The middleware now replaces.

Also corrected: `refresh_reuse_detected` fired on a routine logout or PIN change, because any
revoked token read as reuse. Only a token revoked with reason `ROTATED` is reuse now; false alarms
at that severity are worse than none. And out-of-band audit rows (failed login, reuse) now resolve
the worker's real facility instead of landing on an `UNKNOWN` side chain.

Not in this session, by design: the 20 Room-mirrored clinical tables (Phase 2), kernel proxy
(Phase 3), sync push (Phase 4), ABDM adapter (Phase 5), Android wiring (Phase 6).

## Backend Phase 2: data foundation (2026-08-17)

All 20 Room entities mirrored into PostgreSQL, plus patient and encounter CRUD.

- [x] 20 device-mirrored tables with the same table names and the snake_case form of the same
      fields, so a sync push stays a dumb row operation rather than a translation layer. Plus
      three server-only operational tables (`sync_batches`, `sync_log`, `kernel_call_log`)
      created now so Phases 3 and 4 are route work, not migration work. 29 tables total.
- [x] Real foreign keys throughout, the deliberate divergence from Room. One documented
      exception: `case_records.assigned_doctor_id` and `prescriptions.doctor_id` are indexed but
      unconstrained, because `doctors` is reference data with an independent lifecycle and a
      stale seed must not cost a clinical record.
- [x] pgcrypto column encryption via a SQLAlchemy `TypeDecorator` (`app/db/types.py`): encryption
      and decryption are expressions in the query, the key is a bound parameter that never
      appears in statement text, and no call site outside that module ever handles ciphertext.
      Encrypted: `full_name`, `guardian_or_spouse_name`, `mobile_number`, `aadhaar_number`,
      `emergency_contact` on patients; `name`, `mobile_number`, `email_address` on abha_profiles.
- [x] HMAC-SHA256 blind indexes (`name_blind_idx`, `aadhaar_blind_idx`, `mobile_blind_idx`) so an
      encrypted column is still findable by exact match. Recomputed on every write.
- [x] Sync columns on every syncable table: `facility_id` (stamped from the token, never accepted
      from a body), `server_version`, `received_at`, `sync_state`.
- [x] Patients: create (idempotent on the client-generated id), get, patch with `base_version`
      optimistic concurrency, and a roster that **requires** both bounds and caps the window at
      31 days. REQ-ROS-02 and H-04 now survive the network boundary instead of only living on the
      device; there is no code path that returns every patient.
- [x] `SAMD-PAT-3004` ABHA collision guard on both create and patch, backed by a UNIQUE index.
      Late linking works (app UID stays primary, ABHA arrives later and never replaces it) and
      claiming another patient's ABHA is refused rather than merged (hazard H-03).
- [x] Encounters: create with FK and same-patient follow-up validation, DOCTOR-only bundle fetch
      returning every child row with absent children as null or [], and the case-record status
      state machine enforced server side.
- [x] Migration 0002 applies clean and `alembic check` reports no drift. 116 tests passing,
      ruff and mypy strict clean across 49 source files. Verified live against a real
      PostgreSQL and uvicorn, not only under the test transport.

Four bugs found by running it, all of which would have shipped:

1. **pgcrypto never worked as first written.** `bind_expression` let the parameter inherit the
   decorator's `bytea` impl, so asyncpg sent `$1::BYTEA` into `pgp_sym_encrypt(text, text)` and
   every insert failed with `UndefinedFunctionError`. Fixed with `type_coerce` to text.
2. **Timestamps shipped without milliseconds.** Python's `isoformat()` drops the fractional part
   when it is exactly zero, so a timestamp landing on the second went out in a shape the Android
   formatter cannot parse. The envelope now normalises every datetime in one place.
3. **A test helper swallowed a 422.** Encounter ids longer than the column allowed were rejected
   silently, so two roster tests asserted against an empty database and one of them passed for
   entirely the wrong reason. The helper now asserts its own response.
4. **The blind-index docstring claimed a normalisation it does not do** (stripping a country
   code). Corrected rather than implemented: REQ-REG-02 fixes mobiles at 10 digits, so a prefixed
   value is bad data, and silently folding the two together would let one patient match another
   patient's number.

Docs updated to match what was built: PRD section 5.4 (the encryption column list changed during
implementation, including the decision to leave `abha_number` plaintext so its UNIQUE constraint
can enforce the wrong-patient guard), section 4.7 (the doctor FK exception), and the phase table.

Not in this session, by design: kernel proxy (Phase 3), sync push and pull (Phase 4), ABDM
adapter (Phase 5), Android wiring (Phase 6).

## Backend Phase 3: kernel proxy (2026-08-17)

`/api/v1/assess` and `/api/v1/evaluate` now forward to the XGBoost kernel through the backend
instead of the Android app calling it directly.

- [x] `app/adapters/kernel/`: httpx client built once in the FastAPI lifespan (not per request),
      the ASSESS/EVALUATE path asymmetry encoded explicitly (`/v1/assess` has no `/api` prefix on
      the kernel side, `/api/v1/evaluate` does), no retry anywhere.
- [x] PHI boundary guard (H-10, REQ-HAN-06), two mechanisms: `extra="forbid"` on the request
      schemas, plus an explicit denylist (`app/adapters/kernel/phi_guard.py`) checked before every
      forward, in one named constant with a parametrised test per entry.
- [x] HMAC case pseudonym closing decision D-7: `case_token = HMAC-SHA256(case_record_id,
      CASE_TOKEN_KEY)[:16 hex]`, substituted outbound and restored on the response. Verified the
      derivation by hand against a live call.
- [x] Per-endpoint in-memory circuit breaker (assess and evaluate fail independently): opens after
      5 consecutive failures, fails fast with `SAMD-KERN-5006` while open, half-opens after 30s.
      A 4xx from the kernel does not count as a breaker failure (the kernel answered correctly
      that the payload was bad). Verified the full lifecycle live: closed, failures accumulate,
      opens, fast-fails without a network call, half-opens after cooldown, recovers on success.
- [x] `kernel_call_log` extended (migration 0003): `case_record_id` alongside the pseudonymous
      `case_token`, `kernel_base_url`, a typed `outcome` (SUCCESS/TIMEOUT/UNREACHABLE/KERNEL_ERROR/
      PAYLOAD_REJECTED/MALFORMED_RESPONSE/CIRCUIT_OPEN/PHI_REJECTED), `error_code`, and
      `completed_at` alongside a rename of `created_at` to `started_at`. Written for every call,
      success or failure, per api-contract.md section 5.3.
- [x] `evaluate_reports.payload_json` stores the kernel's tree exactly as returned, mixed casing
      included, verified by whole-tree equality in tests, not spot fields.
- [x] Facility isolation: a kernel call against another facility's `case_record_id` is 404, not
      403 (confirming existence would leak across the boundary), matching the pattern already used
      in patients.py/encounters.py.
- [x] Two new error codes added rather than reusing an existing assignment (the module's own rule:
      a code is never reused for a different meaning): `SAMD-KERN-5006` (circuit open, 503) and
      `SAMD-KERN-5007` (kernel-side 5xx, distinct from unreachable, 502). `SAMD-KERN-5001` through
      `5005` kept their Phase 1 meanings unchanged.
- [x] 164 tests passing (48 new). `ruff` and `mypy` strict clean across 58 source files. Verified
      live: real uvicorn process, real PostgreSQL, and a real (if minimal) second HTTP server
      standing in for the kernel, not just the mocked test transport.

One real transaction-boundary bug found before it ever ran, by reasoning through Phase 1's own
session semantics rather than guessing: `session_scope` commits the request's session on success
and rolls back the WHOLE transaction on any exception. A `kernel_call_log` row for a FAILED call
cannot be inserted into the request session and left there, because the `SamdError` about to
propagate would roll it back along with everything else. Fixed the same way Phase 1's
`_audit_out_of_band` fixed the identical problem for failed logins: failure-path writes (both
`kernel_call_log` and the `audit_events` row) happen in their own session, committed immediately,
before raising.

Two deliberate, flagged deviations from the brief's literal error-code table (documented as D-9
recorded in `docs/backend/backend-prd.md` section 9, not applied silently):

1. The brief's mapping would have reassigned `SAMD-KERN-5001`/`5002` to different meanings than
   Phase 1 already gave them (`5001` = unreachable/502, `5002` = timeout/504, both already
   documented and shipped in `errors.py`). Kept the existing assignments and added `5006`/`5007`
   for the two failure modes the brief wanted distinguished (circuit open, kernel-side 5xx)
   instead of renumbering anything.
2. `case_record_id` unknown or wrong facility uses `SAMD-ENC-4002` (`ENC_CASE_NOT_FOUND`), not the
   brief's suggested `SAMD-ENC-4001` (`ENC_ENCOUNTER_NOT_FOUND`, a different resource already
   assigned to encounters, not case records).

Also flagged rather than silently invented: `kernel_reports` persistence for `/v1/assess` requires
a mapping the kernel's raw response does not carry (predicted_condition/confidence_score/
reasoning_summary/risk_category have no direct source in `differential_diagnosis[]`). Reproduced
the documented Android rules (REQ-HAN-07/08) where they exist, substituted clearly-labelled
backend equivalents where they don't (`device_id`, `software_version`), and inserts a new row per
call rather than upserting per case like the device does (D-9, D-10 in the PRD).

Not in this session, by design: sync push/pull (Phase 4), ABDM adapter (Phase 5), Android wiring
(Phase 6, including the `KERNEL_BASE_URL` deletion; the app still calls the kernel directly
until then).

## Backend Phase 3 fix pass (2026-08-17, Part A on Opus)

A correctness and traceability pass over Phase 3, run before Phase 4 rather than after, because
sync push inherits both defects it fixes. Nothing in Phase 4 was started.

### A0 finding: `risk_category` provenance audit — Case 1, no safety escalation

Run first, before any code. Read `KernelReportEntity`, Room schema `12.json`,
`GenerateKernelReportUseCase`, `KernelAssessmentViewModel`/`AssessmentDisplay`,
`ReportCanvasRenderer`, REQ-HAN-07/08, and the Phase 3 backend code.

**Case 1: Android derives it by an explicit mapping** — with three qualifications that matter.

1. The mapping uses `triage_urgency` **and** `confidence_score`, not `triage_urgency` alone
   (`GenerateKernelReportUseCase.tryRealApi`): `EMERGENCY` maps to `HIGH` outright, otherwise
   `>= 0.85` is `LOW`, `>= 0.65` is `MODERATE`, below that is `HIGH`. The backend's Phase 3
   mapping was a **different rule** (`ROUTINE`→`LOW`, `URGENT`→`MODERATE`, `EMERGENCY`→`HIGH`),
   so device and server disagreed for the same kernel response: `URGENT` at 0.90 confidence was
   `LOW` on the device and `MODERATE` on the server. The Android mapping is now the only one.
2. On the MOCK_FALLBACK path it is a hardcoded per-scenario constant in the curated table, not
   model output at all. That path never reaches the proxy and is not reproduced server side.
3. `RiskCategory`'s KDoc on the device says the field grades "how serious the predicted condition
   could be"; outside the EMERGENCY branch the implementation grades **model uncertainty**, so
   higher confidence maps to lower risk. Documentation and implementation contradict each other
   on the device. Reproduced unchanged, because a server rule disagreeing with the device would
   make the stored record differ from the displayed one.

**Escalation check: no fabricated `risk_category` reaches a clinician.** `riskCategory` is
written by the use case, mapped through `KernelReportRepositoryImpl`, and persisted. It is read
by no composable, no `toDisplay()`, no `ReportCanvasRenderer` block, and no gating or branching
logic; a grep across `app/src/main` finds only entity, mapper, converter and migration
references. Had it been rendered, this would have been a safety finding and the pass would have
halted before writing code. Recorded as **D-11** in the PRD with two recommendations for the
founder: reconcile the KDoc with the rule (or change the rule and bump the derivation version),
and delete the field from the device entity if nothing is ever going to render it.

### A1 — the proxy no longer writes `kernel_reports` (D-9, D-10 resolved)

- [x] `_persist_kernel_report` deleted outright. No flag, no commented branch. `kernel_reports` is
      now **device-owned**, written only by sync push in Phase 4, and remains the record of what
      the clinician was actually shown.
      The defect: the proxy filled `predicted_condition`, `confidence_score`, `risk_category` and
      `required_human_verification` with backend-computed values and stored them beside
      `model_version`, which attributes backend arithmetic to a named model version. A docstring
      saying so does not help, because the docstring is not in the database. It also collided with
      the device, which upserts one row per case while the proxy inserted one per call, leaving
      Phase 4 a merge with no correct answer.
- [x] New server-owned `kernel_assessments`, **migration `0004`** (not `0003` as the brief said:
      `0003` already exists from Phase 3, extending `kernel_call_log` rather than creating it, so
      `0003` was taken and later renamed to `0003_extend_kernel_call_log.py` to say so). Raw response body
      verbatim in `jsonb`, plus `request_id` joining to `kernel_call_log`, real FKs to
      `case_records` and `facilities`, `facility_id` stamped from the token, and four columns
      copied out of the response (`model_version`, `inference_time_ms`, `safety_screen_passed`,
      `triage_urgency`), nullable because `/evaluate` does not carry them. Zero derived values, and
      a test over the mapped columns fails if one is ever added.
- [x] Only successful calls returning parseable JSON produce a row. Failures leave a
      `kernel_call_log` row and an `audit_events` row and nothing here, because there is no model
      output to store.
- [x] No data migration. Migration `0004` states that pre-existing proxy-written `kernel_reports`
      rows in a dev database are orphaned by design and the fix is to recreate the volume.
- [x] The success path now writes its `kernel_call_log`, `kernel_assessments` and `audit_events`
      rows in **one out-of-band transaction**, matching the failure path. A call therefore cannot
      be logged without its assessment or the reverse, and a successful kernel call (a network
      event that really happened) is recorded whatever the request transaction does afterwards.

### A2 — derivation in one named, versioned module

- [x] `app/domain/kernel_derivation.py` (new `app/domain` package for pure clinical rules: no IO,
      no ORM, callable from a unit test with a plain dict). One pure function returning a frozen
      dataclass with `predicted_condition`, `confidence_score`, `risk_category`,
      `requires_human_verification`, `derivation_rule_version`. Called on READ only, by the report
      layer and the Phase 7 dashboard. Nothing it returns is persisted.
- [x] `DERIVATION_RULE_VERSION = "HAN-07/08-v1"`. Its docstring states that any threshold or rule
      change here requires bumping it, because the 0.90 threshold is a risk control and the ACP
      under CDSCO/MD/GD/MDSW/01/2026 must distinguish a rule change from a model change.
- [x] 40 unit tests, direct: normal case, the 0.90 boundary, the full risk mapping including the
      unreachability of `CRITICAL`, tie on top probability, unsorted list, empty
      `differential_diagnosis`, malformed differentials, a boolean probability, clamping, and
      missing `model_metadata`.

Two deviations from the brief, both recorded in the module docstring and in D-11:

1. **Top differential is the kernel's first entry, not max-by-probability.**
   `RetrofitKernelSource.assess` takes `firstOrNull()` and does not sort. A max-by-probability
   rule here would name a different top condition than the device displayed if the kernel ever
   returns an unsorted list, and reconstructing what was displayed is the point of the module.
   The session rule "if the brief conflicts with the code, the code wins" decided this.
2. **Empty `differential_diagnosis` derives `None`.** Android substitutes the literal string
   "Non-specific presentation" and a confidence of 0.50. Reproducing that would fabricate a
   confidence the model never produced, which is the defect this pass exists to remove. The
   device does it for a UI that must render something; a read-time analytical function has no
   such obligation.

### A3 — documentation and the risk file

- [x] `docs/quality/risk-management-file.md`: new hazard **H-12** (backend-derived clinical values
      stored as model output) recording the raw-only storage control and the versioned read-time
      derivation, and new **RR-01** under a new section 4.1, accepting the quasi-identifier
      residual risk on plaintext `village`/`block`/`pincode` with the mitigation named as
      database-level access control plus the pending AWS KMS envelope encryption, explicitly not
      column encryption. Cross-referenced to PRD §5.4, which recorded the schema decision but
      never carried it into the risk file.
- [x] `docs/backend/backend-prd.md`: D-9 and D-10 marked resolved with the reasoning rather than
      just the outcome, D-11 added, §5.5 corrected (it said clinical content is durably stored in
      `kernel_reports` and `evaluate_reports`), phase table gains a `3-fix` row.
- [x] `docs/backend/api-contract.md` §5.3: `kernel_assessments` documented alongside
      `kernel_call_log` in a table making the split explicit — the response body **is** stored in
      `kernel_assessments`, `kernel_call_log` keeps hashes only, and neither stores the request
      body.

### Open item, flagged rather than folded in

`evaluate_reports` still takes a proxy write on every `/evaluate` call. It is a device-mirrored
table with the identical ownership collision D-10 describes, and its content is now duplicated in
`kernel_assessments.raw_response`. Removing that write was **not** in the fix pass brief, which
scoped the change to `kernel_reports`, so it was left in place and recorded under D-10 with a
recommendation to remove it in Phase 4.

### Commit status correction

Neither Phase 3 nor the Phase 3 fix pass above had been committed when this section was first
written. `git log` on `app/services/kernel.py`, `app/api/v1/kernel.py` and `app/domain/` returned
nothing until the pre-commit verification pass that follows landed them. The `[x]` markers above
describe work that was done and verified in the working tree, not work that had shipped to
master. Treat any `[x]` in this file as "done in the working tree" unless a commit sha is quoted
next to it.

## Backend Phase 3 pre-commit verification (2026-08-17)

A verification pass run before the first Phase 3 commit, checking the fix-pass report above
against actual git and database state rather than trusting it.

- [x] Confirmed Phase 3 and the fix pass were never committed (git log on the proxy files was
      empty). All of it, kernel proxy plus both fix-pass parts, lands in one commit together, the
      first Phase 3 commit rather than a retroactive split.
- [x] Confirmed `kernel_call_log` was genuinely created in migration `0002`, not `0003`. Migration
      `0003` only adds columns to it (`case_record_id`, `kernel_base_url`, `outcome`, `error_code`,
      `completed_at`, plus the `created_at` to `started_at` rename). Its own docstring already said
      "extend", only the filename implied otherwise, renamed
      `0003_kernel_call_log.py` to `0003_extend_kernel_call_log.py`, revision id and
      `down_revision` chain unchanged. `alembic history` still linear, `alembic upgrade head` and
      `alembic check` both still clean run against a fresh empty database.
- [x] Confirmed CI green on both prior commits is real, not a misconfigured drift check: `3f937fc`
      committed migration `0002` together with every model it depends on in the same commit, so
      `alembic check` had matching metadata at the time it ran.
- [x] Confirmed the A0 `risk_category` finding (D-11) was already fully recorded with case number
      and origin before this pass started, contrary to a claim that it was missing.
- [x] Confirmed `kernel_assessments` (migration `0004`) has zero derived columns, and that no
      write to `kernel_reports` remains anywhere in `app/services/` or `app/api/`, commented out or
      otherwise.
- [x] 214 tests passing (116 pre-existing plus 98 across the 5 kernel test files, all previously
      uncommitted). `ruff` and `mypy` strict clean. `alembic upgrade head` and `alembic check` clean
      from an empty database.
- [x] Root cause of the blocked pytest run from the previous session: not a dead docker container,
      a stray native `postgresql@14` system service already holding port 5432. Recorded in
      `agent_docs/CLAUDE.md` so it is checked first next time, alongside the separate `.venv`
      pointing at a dead session scratchpad python that also blocked this session's start.

## Android MIGRATION_12_13: per-record sync state (2026-08-17)

Schema foundation for Phase 6's outbox (REQ-SYN-02). Adds no behavior: nothing reads or writes
`sync_state`, no DAO queries over it, `MockSyncStatus`/`CaseStatus.PENDING_SYNC`/
`synced_to_cloud_at` untouched. Test-first: `MigrationTest12To13.kt` was written and run against
the migration before the migration's own KDoc was finalized.

### S1 audit findings (before any code was written)

- 20 of 21 Room entities are syncable per api-contract.md §6.1's table list, one-to-one, no
  unclassified entity found. `DoctorEntity` correctly excluded (server-owned reference data, no
  push path).
- The version-number premise handed to this session was wrong twice over, both corrected in
  `docs/backend/backend-prd.md` §5.2 and its Phase 6 prerequisite note: no `MIGRATION_12_13`
  existed anywhere in the repo (so this migration owns v12-to-v13 outright), and the ABHA columns
  that PRD table claimed would land in `MIGRATION_12_13` don't exist on `PatientEntity` at any
  version, so there was nothing to conflict with.
- Six entities were mutated after insert with no update-tracking column at all:
  `AilmentEntity` (soft delete via `AilmentDao.markDeleted`), `ReferralEntity` (status transition,
  currently unreachable, no caller), `AbhaProfileEntity`/`KernelReportEntity`/
  `EvaluateReportEntity`/`DiagnosisFeedbackEntity` (all `OnConflictStrategy.REPLACE` upserts).
  Backfill sources for each are documented in `MIGRATION_12_13`'s own KDoc.
- Found beyond the original audit, while wiring the fifth column: `CaseRecordDao` (4 queries:
  `updateStatus`, `assignDoctor`, `abandonDraftsForPatient`, `sendAllPendingSync`) and
  `ConsultationDao.updateTranscription` also mutate a syncable entity post-insert. They weren't in
  the original "no update-tracking at all" list because both entities already have a clinical
  `updatedAt` that those queries correctly bump. But `localModifiedAt` is a separate column with
  the same "last write" meaning, and would have gone stale on every status change or transcription
  edit if left alone. All 5 queries now set `localModifiedAt` from the same `updatedAt` bind
  parameter already in scope, zero new clock reads.
- `fallbackToDestructiveMigration()`: absent, confirmed by full-repo grep. Not a live bug.
- `13.json`: did not exist before this session (only `MIGRATION_12` and no version above it were
  ever present). Now exported and committed.

### `syncState = PENDING`, not `SYNCED`, for every pre-existing row

Deliberate: no device has ever pushed anything, so marking pre-upgrade rows `SYNCED` would
silently guarantee every record captured before this build never reaches the server. Consequence,
recorded in `MIGRATION_12_13`'s own KDoc so Phase 6 cannot be surprised by it: the first sync after
Phase 6 has to drain the full local history and will exceed the 500-record/5 MB batch limit
(api-contract.md §6.1), chunking that is the outbox's job, out of scope here. `serverVersion` is
`NULL`, not `0`, matching `base_version: null` for a record the server has never seen.

### `localModifiedAt`, the fifth column beyond the original brief

Sync metadata ("when this row's bytes last changed on this device"), added to all 20 entities
including the 5 that already have a clinical `updatedAt`, deliberately redundant there, so
Phase 6 reads one uniform column regardless of entity. Backfilled per entity in the migration
(`updatedAt` where it exists, `createdAt`/`timestamp` for confirmed insert-only entities,
`COALESCE(deletedAt, createdAt)` for `ailments`, `COALESCE(inferenceEndedAt, inferenceStartedAt)`
for `kernel_reports`/`evaluate_reports`, a correlated subquery to the parent `prescriptions.createdAt`
for `medication_lines` with a final `COALESCE(..., <migration run time>)` fallback for an orphaned
`prescriptionId` the FK-less schema doesn't prevent). Every backfilled value is an approximation,
acceptable because `serverVersion = NULL` on every migrated row means it applies unconditionally
on first push per api-contract.md §6.1, so the backfilled value is never the deciding input to a
conflict comparison.

### Flagged, not fixed

- `AbhaProfileDao.upsert()`'s `OnConflictStrategy.REPLACE` path: callers are inconsistent about
  whether `createdAt` is preserved or overwritten across a replace. A real defect, independent of
  sync, needs its own session. `localModifiedAt` for this entity is deliberately sourced from
  `Instant.now()` rather than `createdAt`, so it stays correct regardless of that inconsistency.
- `ReferralEntity` status-transition history predates any timestamp column and is unrecoverable
  for rows that already exist; `ReferralDao.updateStatus` is unreachable in this build (no caller),
  so this is dormant, not active, but its signature was still updated to carry `localModifiedAt`
  so a future caller can't leave it stale.

### Verification

- `MigrationTest12To13.kt`: **instrumented, on-device (`Pixel_9_Pro_3` emulator), SQLCipher-encrypted**
  end to end. `MigrationTestHelper` built with the same `net.zetetic.database.sqlcipher.
  SupportOpenHelperFactory` `DatabaseModule.provideAppDatabase()` uses at runtime, not the default
  plaintext framework factory, the exact gap that let the `DatabasePassphraseProvider` upgrade
  bug through once already. One row inserted per syncable table at v12 with realistic values
  (including two `ailments` rows, one soft-deleted, and two `medication_lines` rows, one with a
  real parent and one deliberately orphaned to exercise the migration-run-time fallback), migrated
  to v13, every row's survival and every new column's value asserted, including the one row that
  hits the fallback. A second test confirms a fresh install straight to v13 validates against the
  same `13.json`. Both pass. Pre-existing `MigrationTest.kt` (migrations 1 through 8, plaintext,
  not touched) re-run and still passes, unaffected.
- Full instrumented suite (`connectedDevDebugAndroidTest`): 28 tests, 1 pre-existing failure
  unrelated to this work (`CompounderScreenTest.typingChiefComplaintReportsTheChange`, a Compose
  semantics lookup for a "Chief complaint *" text field). Confirmed pre-existing by stashing this
  entire change set and re-running the same test against unmodified `master`: identical failure.
  Not investigated further, out of scope for a schema-only migration session.
- Unit tests (`testDevDebugUnitTest`): 532 passing, unaffected (nothing in this change touches
  unit-tested code paths).
- `assembleDevDebug` and `compileDevDebugAndroidTestKotlin`: both clean.
- One pre-existing test file needed a mechanical fix to keep compiling:
  `TodaysPatientsDaoTest.kt`'s two entity-construction helpers gained `localModifiedAt`.

## Backend Phase 4: sync push (2026-08-17)

`POST /api/v1/sync/push`, the last and hardest backend v1 endpoint. api-contract.md §6.1 and §7.
228 tests passing (214 pre-existing plus 14 new), ruff and mypy strict clean, `alembic check`
clean against the dev database (no new migration: `sync_batches`/`sync_log` already existed from
migration `0002`, so this phase is route and service work only, as that table's own docstring
already said it would be).

### Design: one schema-driven table registry, not nineteen hand-written validators

`app/services/sync.py`'s `TABLE_REGISTRY` reads each of the nineteen generic tables' client-writable
columns off the SQLAlchemy mapper at import time. A required field comes from the column's own
`NOT NULL`; a bad enum value comes from the column's own `CHECK` constraint. Both surface as
`IntegrityError`/`DataError` under that record's own `SAVEPOINT` (`session.begin_nested()`) and
become a generic `SAMD-SYNC-6003`, rather than being re-declared by hand per table. What genuinely
cannot come from the schema is written out explicitly and only there: the one renamed field
(`attachments.uri` → `local_uri`), the one forbidden field (`ailments.audio_local_uri`,
`SAMD-SYNC-6006`), the two server-stamped timestamps (`observations`/`ailments.synced_to_cloud_at`),
the one cross-check the database cannot express (`referrals.sending_phc_id` against the caller's
own `facility_id`), and patient blind-index recomputation (reused from `app/services/patient.py`,
made public as `apply_blind_indexes` rather than forked, since a patient row's `full_name` /
`mobile_number` / `aadhaar_number` can now change through sync push too and the blind indexes have
to track them or the row silently stops being findable by exact match).

Explicit type coercion (`_coerce_value`) converts JSON's ISO-8601 date/time strings to Python
`date`/`datetime` before binding, because asyncpg does not coerce these itself; without it, a
date/timestamp column would fail client-side during parameter encoding as a `StatementError`, which
neither `IntegrityError` nor `DataError` catches, and would have aborted the whole batch instead of
rejecting one record. This is not a hypothetical: it was caught and fixed during this session's own
test-writing, before any handler code shipped.

`audit_log` is not in `TABLE_REGISTRY`. It does not go through the generic upsert path: it calls
`app.services.audit.append`, the one Phase 1 chain appender, unchanged. **One appender, confirmed.**
Reusing it required one addition described below (idempotency), not a fork.

### The audit_log dedup problem, and how it was solved without touching the chain

REQ-AUD-02 needs "has this device-generated audit row already been applied" per row. But
`audit_events.id` is server-assigned (the chain rule's `id` field, `app/services/audit.py`
`compute_entry_hash`'s docstring), never the client's id, so the client's audit-log row id cannot be
looked up in `audit_events` directly. The fix reuses `sync_log`, which already exists and already
indexes `(table_name, record_id)`: a duplicate `audit_log` record id is detected by checking whether
`sync_log` already has a row for `('audit_log', <that id>)`, and if so it is acked `duplicate`
without calling `audit_service.append` at all. No new column, no second chain implementation.

### Idempotency and the two writes that had to happen in the right order

Per §6.1, `POST /sync/push` must return the stored response verbatim on a `batch_id` replay,
touching nothing else. `push()` in `app/services/sync.py` does the `sync_batches` lookup first,
before any other whole-batch precondition (device match, size, unknown table) is even checked — a
replay is not "processing that happens to be a no-op," it is a different code path taken before
processing begins.

`sync_log.batch_id` is a real, non-deferrable foreign key to `sync_batches.batch_id`. That means the
`sync_batches` row has to exist (flushed) *before* the first per-record `sync_log` insert, so a new
batch writes an initial `SyncBatch` row with zeroed counts immediately after the whole-batch
preconditions pass, then updates its `applied`/`stale`/`conflicted`/`rejected` counts and
`response_json` once every record has been processed, all inside the same transaction. This was
not obvious going in and is recorded here so the next session does not "simplify" it back into a
single insert and hit the FK violation.

### Which writes are in the request transaction, and which are out of band

**Everything is in the request transaction. Nothing calls `app.db.session.write_out_of_band`.**
This was reasoned through explicitly, per the brief's instruction not to pattern-match Phase 3's
kernel proxy: `write_out_of_band` exists because a kernel call is a network event that already
happened out in the world before the recording write runs, so whether it is recorded must not
depend on the request transaction surviving afterward. Applying a sync batch has no equivalent
external event — it is pure database work, sequenced entirely by this function, with no failure
mode where "the thing already happened but the transaction might still roll back" can arise. The
`sync_batches` row, every `sync_log` row, every applied clinical row, the one `sync_batch_received`
audit row, and every per-record `audit_log` append all commit together or roll back together, which
is also exactly what makes the idempotency guarantee sound: a stored response can never exist for a
batch whose rows did not really land.

### Per-record status counts vs. the `duplicate` status

The response's numeric counts are `applied`, `stale`, `conflicted`, `rejected` — api-contract.md's
own example response has no `duplicate` bucket. `duplicate` results are real per-record outcomes
(present in `results[]`, logged to `sync_log`) but are not tallied into any of the four counts.
`received` still equals the total record count; the four named counts plus the number of
`duplicate` results in `results[]` sum to `received`. Tested explicitly
(`test_counts_equal_results_tallies`).

### Deliberately skipped: per-rejected-record audit chain entries

`AuditAction.SYNC_RECORD_REJECTED` already existed in the vocabulary (Phase 1) and is not called.
Only one `sync_batch_received` summary audit row is written per batch, with the rejected count in
its payload. Appending one `audit_events` row per rejected record, inside the same per-facility
`pg_advisory_xact_lock` as everything else, would add up to 500 additional serialised appends to
the slowest, highest-stakes endpoint in the service for a granularity `sync_log` already provides
per record (`table_name`, `record_id`, `status`, `code`). Not in the brief's test list either. Add
it if Phase 7's admin surface later needs chain-level (not just `sync_log`-level) visibility into
individual rejections specifically.

### Contract-vs-repo divergences found and flagged, not silently resolved

- **api-contract.md §6.1's own example payload uses `"action": "encounter_started"`, which is not
  a member of `app.models.enums.AuditAction`.** The closest real value is `encounter_created`. The
  repo's enum wins (per this session's brief); the example in the contract is wrong and should be
  corrected by whoever owns that doc next. Caught because the test suite is written against the
  real enum, not the doc's own prose.
- **The device-vocabulary/server-only-vocabulary split for `action` is inferred, not verified
  against ground truth.** `_SERVER_ONLY_AUDIT_ACTIONS` in `app/services/sync.py` is backend-prd.md
  §6.2's own list of "actions with no device equivalent"; everything else in `AuditAction` (minus
  the generic `request_completed` fallback) is treated as device-submittable. The actual source of
  truth is `domain/audit/AuditLogger.kt` on the Android side, not read this session. If that enum
  ever adds a value with no backend counterpart, or the backend enum drifts from it, a legitimate
  device action could be wrongly rejected with `SAMD-SYNC-6003`. Flagged, not fixed, because fixing
  it means reading Android source this backend-only session didn't touch.
- **api-contract.md §8's phase table lists `GET /api/v1/audit/events` and `GET /api/v1/audit/verify`
  under Phase 4.** This session's brief scoped Phase 4 to `POST /sync/push` only and explicitly
  listed pull and later phases as out of scope; the two audit read endpoints were not built. Not an
  oversight — flagging the mismatch between the phase table and this session's actual brief so
  whoever picks up the audit read surface next knows it is still open, not silently done.
- **`abha_profiles.mobile_blind_idx` is not computed during sync push.** It is excluded from the
  client-writable set (same reasoning as `patients`' three blind indexes) but nothing calls an
  equivalent of `apply_blind_indexes` for it, so it stays `NULL` on every device-synced profile.
  No `CHECK` constraint requires it and Phase 5 (ABDM) owns this table's real usage, so this is a
  minor, flagged gap rather than a blocker.

### Verification run against a real `uvicorn` process, not only the test transport

Per the brief's instruction, in addition to the 14 new tests in `tests/test_sync.py` (real
PostgreSQL via `tests/conftest.py`, same as every other phase), this session started the actual
app with `uvicorn app.main:app` against the real dev database (`samd`, migrated to head), seeded a
real facility and worker with `app.scripts.seed_accounts`, and drove a real login, forced PIN
change, sync push happy path, idempotent replay, an unknown-table 422, and a device-mismatch 403,
all over real HTTP to `127.0.0.1:8123`. The replayed response came back with the identical
`request_id`/`timestamp`/`server_time` as the first response, byte-for-byte, confirming the stored
envelope is really what gets replayed rather than something regenerated that merely looks the same.
The smoke-test facility, worker, device, tokens, and patient row were deleted afterward; six
`sync_batch_received`/`worker_login_succeeded`/audit rows for the smoke facility remain in the dev
database and cannot be deleted (the append-only trigger correctly refused, `SAMD-AUDIT-7002`) —
expected and harmless, left in place rather than worked around.

### Test list

`tests/test_sync.py`, 14 tests: happy path (mixed batch across patients / encounters / case_records
/ observations / ailments / audit_log), reordered batch (child before parent, server reorders),
idempotent replay (verbatim response, no double write, chain does not grow), stale (acked success,
not applied), conflict (`base_version` mismatch, `server_state` returned, not applied), one rejected
record does not fail the batch, forbidden field rejected / private ailment accepted, device mismatch
(403, whole batch), unknown table (422, whole batch), oversize batch (413), audit chain intact under
real device-origin/server-origin contention (`asyncio.gather` against the shared advisory lock,
verified via the Phase 1 `verify_chain` path), duplicate audit id acked without growing the chain,
counts-equal-tallies invariant, one `sync_log` row per record.

### Blocking items before Phase 6, found in post-commit review of this phase

Both confirmed by direct investigation, not theoretical. Neither is fixed here.

- **Audit-action vocabulary is divergent: 27 of 28 real device actions are currently rejected.**
  Read `app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt` and every
  `auditLogger.log(...)` call site directly (28 distinct action strings actually emitted, 13 via
  the `AuditAction` object plus 15 as free-text literals at call sites; two `AuditAction` constants,
  `AILMENT_VISIBILITY_CHANGED` and `REFERRAL_STATUS_CHANGED`, are defined but never called). Diffed
  against `app/services/sync.py`'s `_DEVICE_AUDIT_ACTION_VALUES` (backend `AuditAction` enum minus
  backend-prd.md §6.2's server-only list minus `request_completed`, 7 values). Exactly one string
  overlaps: `patient_registered`. Every other real device action, including the one this session
  guessed was a doc typo (`encounter_started`, actually the real production literal at
  `CompounderViewModel.kt:227` — `encounter_created` is never emitted by Android at all), is rejected
  `SAMD-SYNC-6003`. A real device syncing its actual `audit_log` table today gets every row rejected
  except patient-registration ones. Fixing this is two sessions, Android first: the device side
  (`AuditLogger.kt`) is the root cause and the source of truth, so its vocabulary has to be settled
  before the backend's accepted-set can be corrected to match it, not the other way round.
- **`sync_log` audit_log dedup (REQ-AUD-02) is check-then-act, not DB-enforced.** The lookup in
  `_apply_audit_log` (`select(...).where(SyncLogEntry.table_name == table, SyncLogEntry.record_id
  == record_id)`) runs against `ix_sync_log_table_record`, which is a plain index, not a unique
  constraint. Proven correct for the tested case (a retried batch, sequential). Not proven, and not
  actually guaranteed, if two batches carrying the same client-generated `audit_log` record id can
  ever be in flight concurrently: both could `SELECT` "not found" before either `INSERT`s, and both
  would append. Needs `UniqueConstraint("table_name", "record_id")` on `sync_log` plus catching the
  resulting `IntegrityError` the same way every other table's constraint violation already is,
  rather than trusting the `SELECT` alone.

## Not started
- [ ] Demo-theater additions from agent_docs/hardening.md — complete, pending final review of the
      hardening doc to ensure no secondary "security-theatre" items remain (e.g. security-shield
      toggle/sheet).
- [ ] Pre-production process blockers (flag to founder): ISO 13485 QMS + DHF, ISO 14971 risk file,
      software safety classification — see docs/regulatory-foundation.md §3
- [ ] Real authentication + RBAC enforcement (REQ-SEC-03) — mock login above does not satisfy this

### Latest Session Updates

**1. SQLCipher Database Upgrade Bug Fix**
- **Issue**: Upgrading the app from an older, unencrypted database version to the new SQLCipher-encrypted version caused the app to crash with a `SQLiteNotADatabaseException` (or "stale nmae" / `sqlite_schema` read error) because it attempted to open the plaintext DB as if it were encrypted.
- **Fix**: Implemented a safe, non-destructive migration path in `DatabasePassphraseProvider`. 
  - The app now automatically detects the unencrypted DB, creates a temporary encrypted DB, and uses `sqlcipher_export` to safely copy all data over.
  - Added a crucial `PRAGMA integrity_check` verification step. The app now verifies the new encrypted DB is fully intact before deleting the old plaintext DB. If verification fails, it halts safely, preserving both files to prevent any accidental data loss.

**2. Comprehensive Doctor Specialty Mapping**
- **Issue**: Essential hypertension was incorrectly routing to Cardiology instead of a primary care physician.
- **Fix**: Re-wrote the `mapConditionToSpecialty` routing logic in `ResolveDoctorAssignmentUseCase`.
- Expanded the ailment routing list significantly to include a comprehensive set of patient presentations (e.g., chest pain -> Cardiology, stroke -> Neurology, asthma -> Pulmonology, hypertension/viral/headache -> General Physician).
- Ensured emergency cues ("severe", "haemorrhagic") take precedence for Critical Care routing before defaulting to symptom-specific departments.

## Android: AuditAction as the single source of truth for audit logging (2026-08-17)

Root cause fix for the backend Phase 4 finding: the backend rejected 27 of 28 real device audit
actions because the device had no enumerated vocabulary — 15 of 28 `auditLogger.log(...)` call
sites passed free-text string literals straight through, bypassing `AuditAction` entirely, and 2
`AuditAction` constants were dead. This session makes `AuditAction` the only way to log.

### S1: call-site audit

29 `auditLogger.log(...)` calls (confirmed by grep, matches the prior session's count), 28 distinct
action strings. 13 calls already used an `AuditAction` constant; 16 calls (15 distinct strings,
`audio_captured` used twice) passed a free-text literal. Every call site is a `ViewModel` or
`UseCase`; `RoomAuditLogger` is the only place anywhere in the app that constructs an
`AuditLogEntity`, so narrowing its input closes every call site at once (see S4).

### S2: literals converted to constants, values unchanged

Added one `AuditAction` entry per literal, string value byte-identical to what was already being
emitted (`encounter_started` stays `"encounter_started"`; it was confirmed last session as the real
production literal at `CompounderViewModel.kt:227`, not a typo for `encounter_created`, and this
session did not rename it or any other value). All 16 literal call sites, plus 4 more found in
`RoomAuditLoggerTest.kt` that this session's own grep for `auditLogger.log(` missed (it only matched
the interface method name, not `RoomAuditLogger(...).log(` called directly in tests), now pass an
`AuditAction` member.

### S3: the 2 dead constants

- **`AILMENT_VISIBILITY_CHANGED`: deleted.** `AilmentDao` has exactly two methods, `insert` and
  `markDeleted` (read directly). No method changes an ailment's `visibility` after creation; the
  field is set once at capture and is immutable in the current codebase. There is no real mutation
  path to wire a log call into, dormant or otherwise. Its one reference (a display-string branch in
  `PatientFacingAudit.kt`) was dead code and removed with it.
- **`REFERRAL_STATUS_CHANGED`: kept, still unused.** `ReferralDao.updateStatus` is a real DAO method
  but has zero callers anywhere in the codebase (confirmed by grep), independently matching this
  same finding already recorded by the `MIGRATION_12_13` session: "unreachable in this build, no
  caller... dormant, not active." Not deleted because the mutation path is real, deliberately
  retained schema/DAO capability, not a hypothetical one; not wired with a log call because doing
  that requires a live caller to attach the call to, and creating a referral-status-transition
  UI/business flow to give it one is a different feature, out of scope for an audit-logging-
  discipline session.

### S4: enforcement chosen — narrow the type, not a lint rule or a scan

`AuditLogger.log(action: String, ...)` became `AuditLogger.log(action: AuditAction, ...)`.
`AuditAction` itself changed from an `object` of `const val String` to an `enum class
AuditAction(val value: String)`, so it has a type the signature can narrow to. `value` is written
out explicitly per entry rather than derived from the Kotlin identifier (e.g. `name.lowercase()`),
even though every current entry would round-trip correctly that way: deriving it would silently
change the wire value the moment someone renames an enum constant for readability, which is exactly
what the string values must never do (they are already in shipped audit rows and are the wire
contract). This is the strongest of the three options the session brief offered (narrowed type,
lint rule, or a scanning unit test) and makes the other two redundant: a free-text literal at a
`.log()` call site is now a compile error, not a lint warning or a test failure. No lint rule or
scanning test was added on top, deliberately, to avoid three mechanisms enforcing the same thing.

Ripple, all necessary and all made: `RoomAuditLogger.log` now writes `action.value` into
`AuditLogEntity.action` (the Room column stays `String`, unaffected — only the logging API surface
narrowed, not persistence); `FakeAuditLogger` in test utilities takes `AuditAction` and stores
`action.value` in its `Entry` so every existing test assertion comparing `it.action == "..."`
against a literal string kept working unchanged; `PatientFacingAudit.kt`'s `when (this: String)`
branches, which used to match directly against `AuditAction.CONSENT_RECORDED` etc. back when that
expression was itself a `String`, now match against `AuditAction.CONSENT_RECORDED.value` (every
branch converted, not just the ones that would have failed to compile, so the file has one style
throughout); `PatientFacingAuditTest.kt`'s three `AuditAction`-typed test fixtures needed the same
`.value` treatment to keep compiling against the `entry(action: String)` test helper, which is
correct as `String` since it is standing in for `AuditLogEntry.action`, the persisted column.

### S5: the authoritative action list (29 entries, input to the next backend session)

```
ABHA_LOGIN_VERIFIED            abha_login_verified
ABHA_PROFILE_CREATED           abha_profile_created
AILMENT_CAPTURED               ailment_captured
AILMENT_DELETED                ailment_deleted
ALLERGY_ADDED                  allergy_added
ATTACHMENT_ADDED               attachment_added
AUDIO_CAPTURED                 audio_captured
CASE_QUEUED_FOR_SYNC           case_queued_for_sync
CASE_SENT_TO_DOCTOR            case_sent_to_doctor
CONSENT_RECORDED               consent_recorded
CONSULTATION_LOCKED            consultation_locked
CONSULTATION_SAVED             consultation_saved
DIAGNOSIS_FEEDBACK_RECORDED    diagnosis_feedback_recorded
EMERGENCY_OVERRIDE             emergency_override
ENCOUNTER_RESUMED              encounter_resumed
ENCOUNTER_STARTED              encounter_started
EVALUATE_RESPONSE_FAILED       evaluate_response_failed
EVALUATE_RESPONSE_RECEIVED     evaluate_response_received
FAMILY_HISTORY_ADDED           family_history_added
KERNEL_ASSESSMENT_ACKNOWLEDGED kernel_assessment_acknowledged
KERNEL_RESPONSE_RECEIVED       kernel_response_received
MEDICAL_HISTORY_ITEM_ADDED     medical_history_item_added
MEDICATION_ADDED               medication_added
PATIENT_REGISTERED             patient_registered
REFERRAL_CREATED               referral_created
REFERRAL_STATUS_CHANGED        referral_status_changed   (S3: real DAO method, no caller, kept)
REPORT_EXPORTED                report_exported
SOCIAL_HISTORY_SAVED           social_history_saved
TRANSCRIPTION_COMPLETED        transcription_completed
VITALS_RECORDED                vitals_recorded
```

28 of these 29 values are actually emitted today; `REFERRAL_STATUS_CHANGED` is the one exception
(S3). The next backend session replaces `_DEVICE_AUDIT_ACTION_VALUES` in `app/services/sync.py`
with this exact list (minus, or including, `REFERRAL_STATUS_CHANGED` is that session's call to
make, now that its status is documented rather than merely inferred) and adds a test asserting the
two sets match sourced from both sides, not hand-copied and left to drift again.

### S6: verify

- `testDevDebugUnitTest`: 133 tests before this session's changes, 133 after, 0 failures both times
  (measured directly via `git stash` / rerun / `git stash pop` / rerun, not assumed — this
  session's edits change call-site arguments, not test counts, and this confirms it rather than
  taking that on faith). This is a different number from the `532` this file's own `MIGRATION_12_13`
  entry reported for the same task; not reconciled this session, flagged here rather than silently
  overwritten. `app/build/test-results/testDevDebugUnitTest` has exactly one XML per one of the 32
  `*Test.kt` files under `app/src/test`, so `133` reflects this build's real, current, full test
  count for this variant, whatever the earlier `532` was counting.
- `assembleDevDebug`: clean.

### What this unblocks

The backend vocabulary-reconciliation session (`_DEVICE_AUDIT_ACTION_VALUES` in
`app/services/sync.py`, plus the two-sided assertion test) is now unblocked. The `sync_log` unique
constraint item logged alongside it is independent and still open.

Test-count reconciliation: MIGRATION_12_13's "532" was the summed test task across all four unit-test variants; this session's "133" is testDevDebugUnitTest alone. 133 x 4 variants ≈ 532. Same suite, different scope, nothing lost. Convention going forward: report testDevDebugUnitTest as the canonical Android unit count.

## Backend: audit-vocabulary reconciliation + sync_log dedup hardening (2026-08-17)

Both Phase 4 review findings, fixed. 231 tests passing (228 pre-existing plus 3 new), ruff and
mypy strict clean, `alembic upgrade head` clean from empty, `alembic check` clean.

**Correction to the prior Android session's own count**: that session's summary said "29 entries."
The actual `AuditAction` enum, confirmed by direct count in this session (`grep -c` on
`AuditLogger.kt`, and independently by the cross-repo parse test below), has **30** entries. The
underlying data (the grep output printed that session, the mirror file written this session) was
always correct at 30; only the prose summary undercounted by one. No functional gap, corrected
here for the record.

### R1: the accepted device set, sourced not retyped

`app/domain/audit_actions_device.py` is a new module holding `DEVICE_AUDIT_ACTIONS`, a frozenset
mirror of Android's `AuditAction` enum values, with a header comment naming its source and stating
it must be hand-regenerated when that enum changes. `app/services/sync.py`'s
`_DEVICE_AUDIT_ACTION_VALUES` is now `DEVICE_AUDIT_ACTIONS` directly, not a locally computed
complement of anything. The 7-value guess it replaces (`AuditAction` minus the 14 server-only
actions minus `REQUEST_COMPLETED`) was correct in shape at Phase 1 and never updated as the real
device vocabulary grew to 30 entries across four phases; that drift, not a design flaw, is what
produced the 27-of-28-rejected finding. `_SERVER_ONLY_AUDIT_ACTIONS` (backend-prd.md section 6.2's
14 server-only actions) stays a second, independently defined set, never derived from or subtracted
against the device set. A module-level check at import time (`app/services/sync.py`) raises if the
two sets ever overlap, so a device could never inject a server-attributed action through sync
merely because someone edited one list without checking the other.

`referral_status_changed` is accepted even though it is still dormant on the device (no live
caller, per the Android session's own S3 finding). Operator decision, recorded here as the
brief asked: the accepted set is a superset that never has to shrink, so accepting a
not-yet-emitted value now is cheap, and accepting it only once a caller exists later would be a
coordinated field migration instead.

### R2: the test that stops this rotting a second time

Two tests in `tests/test_audit_actions_device.py`:
- `test_backend_accepted_device_set_matches_the_checked_in_mirror`: asserts
  `sync_service._DEVICE_AUDIT_ACTION_VALUES == DEVICE_AUDIT_ACTIONS`, reading the backend's actual
  runtime set, not a literal typed into the test.
- `test_mirror_matches_the_android_source_when_reachable`: parses
  `app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt` directly (path resolved from
  the test file's own location, four `.parents[]` up to the repo root, then down into `app/`) and
  asserts the parsed set equals the mirror. **This runs for real in this environment, it does not
  skip**: the backend and Android trees are the same monorepo checkout, so the path exists and the
  assertion is a genuine cross-repo check, not a placebo. It only skips (with an explicit reason
  naming the manual regeneration step) if the Android source is absent, which is the honest
  fallback for a backend-only clone or CI job, not a way to avoid running the check here.

### R3: closing the check-then-act race, correctly scoped

The brief asked for `UniqueConstraint("table_name", "record_id")` on the whole `sync_log` table.
**This was not done, and the deviation is deliberate**: `sync_log` is a history log for every
table other than `audit_log`, and a record legitimately gets a second row there on a later
stale/conflict retry (`test_stale_is_acked_as_success_and_not_applied`,
`test_counts_equal_results_tallies`, both already-passing tests that push the same non-audit
record_id twice on purpose and assert a different-status row each time). A table-wide unique
constraint would have turned that second, correct push into an `IntegrityError`, breaking both
tests. Implemented instead as a **partial unique index**,
`uq_sync_log_audit_log_record_id` on `sync_log(table_name, record_id)` `WHERE table_name =
'audit_log'` (migration `0005`, mirrored in `app/models/sync.py`'s `SyncLogEntry.__table_args__`
so `Base.metadata.create_all` in tests creates the same constraint Alembic does in real
deployments). Only `audit_log` is constrained to one row per `record_id` ever; every other table's
history-logging behaviour is untouched. Checked before writing the migration: zero duplicate
`(table_name, record_id)` rows for `table_name = 'audit_log'` in the dev database.

The INSERT is the actual race-closer, per the brief, and per the existing `IntegrityError` catch
pattern in `app/services/sync.py`'s `_apply_one` (matched, not reinvented): `_apply_audit_log` now
reserves the dedup slot (its own `sync_log` row, under its own `session.begin_nested()` savepoint,
nested inside `_apply_one`'s) **before** calling `audit_service.append`, not after. This ordering
is what makes "the chain grows by exactly one" true under real contention: if the reservation
INSERT loses the race (a concurrent batch's row committed first, unique-violation), the function
returns `duplicate` immediately and never reaches the chain append at all, so the loser cannot
have appended anything for the winner to have "duplicated" retroactively. `push()`'s own
per-record `sync_log` insert is skipped for `audit_log` records whose status came back `applied`
or `duplicate`, since `_apply_audit_log` already wrote (or found) that row itself; a `rejected`
audit_log record (validation failed before the reservation point, e.g. an unknown action) still
gets `push()`'s normal row, and deliberately does not reserve the slot, so a client that resends
the same `record_id` with corrected data later is not permanently locked out by one bad attempt.

Test-before-fix, as the brief required: the concurrent test in R4 was written and run against the
pre-fix code first (`git stash push -- app/models/sync.py app/services/sync.py`, run, `git stash
pop`), and it failed exactly as predicted: both concurrent requests came back `applied`, proving
the race is real before the constraint existed. Re-run after restoring the fix, it passes.

### R4: the concurrent test

`tests/test_sync.py::test_concurrent_batches_racing_the_same_audit_id_append_exactly_once`. Two
different `batch_id`s, same `audit_log` `record_id`, pushed via `asyncio.gather` so the two HTTP
requests genuinely overlap (same pattern already established and proven working in
`test_audit_chain_intact_under_device_and_server_contention`). Asserts: both requests return `200`
(neither the whole batch nor the request itself fails, only the one record's per-row status
differs), the two results are exactly `["applied", "duplicate"]`, `audit_events` rows with
`origin = "DEVICE"` and this action grew by exactly `1` (not `0`, not `2`), and
`audit_service.verify_chain` reports `verified = True` with no broken sequence afterward.

Also confirmed against a real `uvicorn` process and the real dev database (not only the test
transport, per R5): two genuinely concurrent `curl` processes backgrounded against the same
running server, same `record_id`, different `batch_id`s. One came back `"status":"applied"`, the
other `"status":"duplicate"`, both `HTTP 200`, and `SELECT count(*) FROM audit_events WHERE
action = 'encounter_started' AND facility_id = '<smoke facility>'` returned exactly `1` afterward.
Smoke-test facility, worker, device, tokens, and sync rows deleted afterward; the one
`audit_events` row is append-only and was left in place, same as every prior session's smoke test.

### A real bug this session's own R2 tests caught immediately

`tests/test_sync.py`'s `audit_record()` helper (written last session, before `AuditAction` was
type-narrowed on the Android side) defaulted to `action="encounter_created"`, which was only ever
valid because the *old*, wrong 7-value backend set happened to include it. The real device
vocabulary has no `encounter_created`; it has `encounter_started`. The moment R1 landed the real
30-value set, three of this session's own new/existing test assertions started failing with
`SAMD-SYNC-6003 action: unknown audit action`, in the R4 concurrent test's own first run. Fixed by
changing the default (and the two other call sites still using the old string) to
`"encounter_started"`, the real value. Left here as a concrete demonstration of exactly what R2's
tests are for: this class of drift is now caught in seconds instead of surviving four phases.

### Divergences from this brief

- R3's `UniqueConstraint` instruction was not followed literally; a partial unique `Index` was
  used instead. Explained above. This is the one place this brief's literal wording would have
  broken already-passing, correct behaviour; the repo (and the passing tests) won, per this
  brief's own rule.
- The "29 device values" count in this brief (inherited from the prior Android session's own
  summary) is corrected to 30 here; the value *list* in the brief was already complete and
  correct (it has 30 entries when counted), only the stated total was off by one.

### api-contract.md and backend-prd.md

`api-contract.md` section 7.1 was not touched: its description ("accepted actions are the union of
the string literals in `AuditLogger.kt` plus the server-only actions") is still accurate; only the
*implementation*, not the contract, had drifted. `backend-prd.md` section 6.2 gained one line
noting the accepted device set is now enforced from the checked-in 30-entry mirror and that
`referral_status_changed` is accepted while dormant; the section's existing description of intent
was not rewritten, since it was already correct.

## Android Phase 6a: auth and network foundation (2026-08-18)

Branch `phase-6a`, off `master` (not `ABHA`: Phase 5's ABDM adapter sits on `ABHA`, unmerged;
the operator chose a fresh branch to keep the auth/network foundation separable). First of three
Phase 6 sessions: 6b (sync outbox) and 6c (ABHA wiring) both depend on this one. Scope deliberately
bounded to auth, network plumbing, the `DOCTOR` role, and the kernel rebase. No sync outbox, no
`WorkManager` worker, no ABHA wiring, per the brief.

### Auth service and session

New sibling classes to the existing `MockAuthSession`: `data/remote/api/AuthApiService.kt`
(Retrofit interface, all 5 endpoints from api-contract.md §2, each returning
`Response<ApiEnvelopeDto<T>>` so a non-2xx body's RFC 9457 shape isn't lost to a bare
`HttpException`), `data/remote/RetrofitAuthService.kt` (parses that into `AuthApiResult<T>`,
Success/Failure with a `SAMD-*` code), `data/local/auth/BackendAuthSession.kt` (implements the
`AuthSession` domain port, now bound in `di/RepositoryModule.kt` in place of `MockAuthSession`).

`worker_id` continuity: `MockAuthSession.stableUserId` was extracted verbatim into a shared
top-level function, `data/local/auth/WorkerIdDerivation.kt`, used by both `MockAuthSession`
(unbound but kept, see below) and `BackendAuthSession`. `WorkerIdDerivationTest` locks the exact
SHA-256 formula against an independently-computed hash, not derived from the function under test,
plus determinism/trim/role-sensitivity/format checks.

`MockAuthSession` is kept in the tree, not deleted. There is no existing flavor/DI seam in this
codebase that swaps `AuthSession` implementations per build variant (the three product flavors
only vary `BuildConfig` URLs, not bound classes), so there was nothing to "wire it behind"; it
just stopped being bound. Its own DataStore file name was changed
(`mock_auth_session_unused`, was `auth_session`). androidx DataStore throws
`IllegalStateException` if two `DataStore<Preferences>` instances ever open the same file in one
process, and `MockAuthSession` still has a live `@Inject constructor`, so an accidental future
injection of both must not be able to crash the app.

`AuthTokenStore` (tokens/device_id/worker session, Preferences DataStore, reusing the `auth_session`
file `MockAuthSession` used) was split into an interface + `DataStoreAuthTokenStore` impl, matching
the existing `AuditLogDao`/`FakeAuditLogDao` pattern in this codebase, not because a second
implementation was needed, but because `TokenAuthenticator`/`BearerInterceptor` needed something
fakeable in a plain JVM unit test, and this repo's convention (confirmed by reading
`RoomAuditLoggerTest`) is fakes-implementing-interfaces, not Robolectric, which isn't a dependency
here.

### The interceptor and Authenticator

`data/remote/BearerInterceptor.kt` attaches `Authorization: Bearer` to every call except
`/auth/login` and `/auth/refresh` (path-checked, not a second OkHttpClient). `data/remote/
TokenAuthenticator.kt` handles 401s: one refresh attempt per original request
(`response.priorResponse` chain length gate), never retries a failing `/auth/refresh` call itself
(same path check breaks the DI-cycle-shaped recursion risk), and serializes concurrent refreshes
with a `kotlinx.coroutines.sync.Mutex` plus a token-identity comparison inside the lock: a second
caller that was only waiting for the first refresh to land retries with the already-rotated token
instead of firing a second refresh call. `Provider<RetrofitAuthService>`, not a direct dependency,
breaks the real DI cycle (authenticated `OkHttpClient` → `TokenAuthenticator` →
`RetrofitAuthService` → `Retrofit` → that same `OkHttpClient`).

Both use `runBlocking` to call suspend `RetrofitAuthService` methods from OkHttp's synchronous
`Interceptor`/`Authenticator` contract. Documented inline as deliberate: these run on the calling
thread (for `.execute()`) or OkHttp's own dispatcher pool (for enqueued calls), not a
caller-owned fixed coroutine dispatcher, so there's no starvation risk from blocking there.

`TokenAuthenticatorTest` (4 tests) and `BearerInterceptorTest` (3 tests) run against a real
`MockWebServer`, not a hand-rolled `Interceptor.Chain` mock. `okhttp-mockwebserver` added as a
`testImplementation`, same pinned okhttp version already used for the production
logging-interceptor dependency. The single-flight test uses a custom `Dispatcher` (not a fixed
`enqueue()` sequence, which can't handle concurrent-arrival ordering) and fires two real requests
from `Dispatchers.IO` threads concurrently; it asserts the refresh endpoint was hit exactly once.
All three of the brief's hard behaviors are proven: one-refresh-then-give-up, single-flight, and
`SAMD-AUTH-1004`/any-refresh-failure clears the session (`tokenStore.clear()`) rather than
retrying, verified by asserting the cleared token snapshot directly, per this repo's own rule
(`CLAUDE.md`: a failure-path test must assert persisted state, not just the HTTP response; the
same rule that caught the backend's `_fail()` rollback bug applies here on the client side).

### Forced PIN change

`AuthSession` gained `mustChangePin(): Flow<Boolean>` and `changePin(...): ChangePinResult`.
`AuthUiState` gained a `MustChangePin(session)` variant, computed in `AuthViewModel` by combining
`currentUser()` and `mustChangePin()`, so app-restart-before-completing-the-change still routes
correctly, not just the immediately-after-login case. `AppNavHost`'s existing sign-in gate routes
to the new `PinChangeScreen`/`PinChangeViewModel` for that state instead of `MainNavHost`. On a
successful change, `BackendAuthSession.changePin` clears the local session (the backend revokes
every refresh chain for that worker on every device (api-contract.md §2.6): no new token pair
comes back), and the screen does no navigation itself: the session Flow it's indirectly driving
goes null, and the existing gate reacts. `LoginScreen` gained a PIN field
(`NumberPassword` keyboard, `PasswordVisualTransformation`); `AuthSession.signIn` gained a `pin`
parameter and now returns `SignInResult` (`Success(mustChangePin)` / `Failure(message)`) instead
of `Unit`, so `LoginViewModel` can route/display without throwing.

**Divergence from the brief, flagged not hidden:** `AuthApiService.me()` and
`RetrofitAuthService.me()` are implemented (contract §2.5, correct request/response DTOs), but no
call site wires them into `AuthViewModel` or anywhere else. The session is hydrated once, from the
login response, and stored locally; nothing re-fetches it from `/auth/me` on app resume. The brief
listed `me` among "build exactly those five" without a specific call site beyond
`BackendAuthSession.currentUser()`, and `currentUser()` is satisfied by the local store without a
network round trip. Wiring an actual resume-refresh flow felt like scope creep beyond "auth and
network foundation" and is left as an explicit follow-up rather than silently skipped.

### `DOCTOR` role (D-2)

Added to `domain/auth/UserRole` (4th constant). The only exhaustive `when` over `UserRole` in the
app (`UserRoleDisplay.kt`'s `displayLabel()`) got a 4th branch; nothing else broke, confirmed by a
full compile. `PatientSummaryScreen`'s review gate is explicitly NOT rewired to require it. Noted
as a follow-up in `backend-prd.md` D-2, matching the brief's stated boundary.

### Kernel and evaluate rebase (D-1, D-4)

`RetrofitKernelSource`/`RetrofitEvaluateSource` now go through `BACKEND_BASE_URL` instead of
`KERNEL_BASE_URL`. `KernelApiService.assess`'s path changed from `/v1/assess` (the kernel's own,
un-prefixed path) to `/api/v1/assess` (the backend's proxy path, api-contract.md §5.1's path
mapping table). Reusing the same relative path string for both would have silently kept hitting
the kernel directly if `BACKEND_BASE_URL` and `KERNEL_BASE_URL` had ever pointed at hosts serving
both, so this was verified against the contract table, not assumed. Both `KernelApiService.assess`
and `ClinicalApiService.evaluate` now return `ApiEnvelopeDto<T>` (the backend wraps every 2xx body,
api-contract.md §0.5) instead of the bare kernel DTO; `RetrofitKernelSource`/`RetrofitEvaluateSource`
unwrap `.data`.

`ClinicalApiService.evaluate` was simplified from `Response<EvaluateReportDto>` to a bare suspend
function. api-contract.md §5.4 explicitly sanctions this ("this removes the reason
`ClinicalApiService.evaluate` returns `Response<EvaluateReportDto>`... though changing that
signature is optional"): the backend now translates the kernel's differently-shaped upstream
failure body into the standard RFC 9457 envelope before Android ever sees it, so a non-2xx is a
normal `HttpException` like every other endpoint. `EvaluateErrorDto` (the old `{error, message,
case_token}` shape, now unreachable from Android) was deleted along with the one test that
exercised it in isolation; the two tests covering `EvaluateReportDto`'s actual success/edge-case
parsing were untouched.

`KERNEL_BASE_URL` and `ABHA_BACKEND_BASE_URL` deleted from all three product flavors (D-4: "a
second path to the kernel is a second path that bypasses the audit log"). `BACKEND_BASE_URL` in
the dev flavor was made overridable via `local.properties` (it wasn't before, only
`KERNEL_BASE_URL` was), preserving the physical-device-over-Wi-Fi testing workflow that
`KERNEL_BASE_URL`'s override used to provide, now that `BACKEND_BASE_URL` is the one LAN address
the device actually needs.

### Verification

- `testDevDebugUnitTest`: **133 → 144** (baseline 133, minus 1 deleted vacuous
  `EvaluateErrorDto` test, plus 12 new: `WorkerIdDerivationTest` ×5, `TokenAuthenticatorTest` ×4,
  `BearerInterceptorTest` ×3). All 144 pass. Baseline was captured by stashing this session's
  changes and running against unmodified `master`, not assumed from an earlier session's number.
- Three pre-existing `LoginViewModelTest` tests broke on the new PIN-required `canSubmit` gate
  (`onSubmit()` silently no-ops without a PIN now) and were updated to call `onPinChange(...)`
  before submitting; no assertions were weakened to make them pass.
- `assembleDevDebug`, `assembleStagingDebug`, `assembleProdDebug`: all three succeed, confirming
  the `KERNEL_BASE_URL`/`ABHA_BACKEND_BASE_URL` deletion left no dangling `BuildConfig` reference
  in any flavor.
- **No live backend reachable in this environment this session** (none was started). The kernel
  rebase's real assess call through the full auth stack is a **manual live-check**, not yet done.
  Everything else (Authenticator behavior, PIN gating, DTO parsing, envelope unwrapping) is proven
  against `MockWebServer`/unit tests, not a running backend.
- `graphify update .` not run mid-session (multiple hook-triggered background rebuilds already
  fired on branch switches); a final one should run before merge.

### An unrelated operational issue, resolved mid-session

The root filesystem (`/`, 234 GB, ext4) filled to 15 MB free partway through this session,
blocking every shell command including Gradle. Root cause was not fully resolved: `du` only
accounted for ~60 GB of the 218 GB in use, and the remainder is very likely inside other users'
home directories on this shared machine (`/home/pi`, `/home/lab`, `/home/saiyam`, `/home/lokesh`),
which are permission-denied without `sudo` and not this session's data to inspect or delete.
Freed ~2.5 GB from data this session's operator does own: two stale Claude Code update binaries,
the Trash, one stale Android Studio patch-update jar, enough headroom (3.7 GB) to keep working,
not enough to consider the underlying issue closed. Flagged for the operator, not silently worked
around.

### Files changed

New: `AuthTokenStore.kt` (interface), `DataStoreAuthTokenStore.kt`, `BackendAuthSession.kt`,
`WorkerIdDerivation.kt`, `AuthApiResult.kt`, `BearerInterceptor.kt`, `TokenAuthenticator.kt`,
`RetrofitAuthService.kt`, `data/remote/api/AuthApiService.kt`, `data/remote/dto/ApiEnvelopeDto.kt`,
`data/remote/dto/ProblemDetailDto.kt`, `data/remote/dto/AuthDtos.kt`, `PinChangeScreen.kt`,
`PinChangeViewModel.kt`, plus the four new test files above.

Modified: `domain/auth/AuthSession.kt` (DOCTOR, `SignInResult`, `ChangePinResult`,
`mustChangePin()`), `MockAuthSession.kt` (interface conformance, renamed DataStore file),
`di/NetworkModule.kt` (single `BACKEND_BASE_URL` Retrofit/OkHttpClient, bearer interceptor +
authenticator wired in), `di/RepositoryModule.kt` (`BackendAuthSession`/`DataStoreAuthTokenStore`
bindings), `KernelApiService.kt`/`ClinicalApiService.kt`/`RetrofitKernelSource.kt`/
`RetrofitEvaluateSource.kt` (envelope unwrap, path/signature changes), `UserRoleDisplay.kt`
(DOCTOR label), `LoginViewModel.kt`/`LoginScreen.kt` (PIN field, `SignInResult` handling),
`presentation/auth/AuthViewModel.kt` (`MustChangePin` state), `presentation/navigation/
AppNavHost.kt` (routes to `PinChangeScreen`), `app/build.gradle.kts` +
`gradle/libs.versions.toml` (mockwebserver test dependency, flavor URL cleanup), plus the fixed
`LoginViewModelTest.kt` and the `Fakes.kt` additions (`FakeAuthTokenStore`, `FakeAuthSession`
interface conformance).

### Divergences from this brief

1. `me()` implemented but not wired into any call site (see above, under "Forced PIN change").
2. No live-backend integration check performed: no backend was reachable in this environment.
3. `AuthTokenStore` became an interface + impl split, not a single concrete class, to make the
   Authenticator/Interceptor testable without Robolectric: a deliberate, necessary addition, not
   scope creep, since "test it: two concurrent 401s produce one refresh" was an explicit brief
   requirement that would otherwise have been unverifiable.

### STOP

```
================================================================
  CHECKPOINT: Phase 6a auth + network foundation COMPLETE
================================================================
  Branch: phase-6a, off master (fresh branch, not ABHA: operator's explicit choice;
    Phase 5 ABDM adapter remains on ABHA, unmerged, pushed to origin this session).

  Authenticator, all three PROVEN by TokenAuthenticatorTest against a real MockWebServer:
    - one-refresh-then-give-up: PROVEN (3 requests total on a second 401, no second refresh)
    - single-flight: PROVEN (two concurrent 401s -> exactly one refresh call)
    - SAMD-AUTH-1004 / any refresh failure clears session, does not retry: PROVEN
      (2 requests total, token snapshot asserted null after)

  stableUserId continuity mock->real: same shared function, WorkerIdDerivationTest (5 tests)
    locks the formula against an independently-computed SHA-256 hash.

  BearerInterceptor does NOT attach a token to login/refresh: PROVEN (BearerInterceptorTest,
    asserts the header is absent on those two paths, present on every other).

  KERNEL_BASE_URL and ABHA_BACKEND_BASE_URL: deleted from all 3 flavors.
    assembleDevDebug / assembleStagingDebug / assembleProdDebug: all 3 succeed.

  Kernel rebase: real assess call NOT run. No backend reachable in this environment.
    Marked a manual live-check, not silently skipped.

  testDevDebugUnitTest: 133 -> 144 (baseline captured by stashing and testing bare master,
    not assumed).

  Scope creep pulled in: AuthTokenStore interface/impl split (see Divergences, justified by
    an explicit brief requirement, not incidental).

  Divergences from this brief: me() unused, no live-backend check, AuthTokenStore split.
    All three listed above with reasoning, none silent.

  NEXT: Phase 6b sync outbox. DO NOT PROCEED. Wait for the operator.
================================================================
```
## Backend Phase 5: ABDM M1 adapter, stub mode (2026-08-17)

Branch `ABHA`. Create ABHA via Aadhaar OTP, the P0 vertical slice, built stub-only per the
brief: `ABDM_MODE=stub` throughout, no live ABDM call, no credential wiring. Phase A's contract
doc (`docs/requirements/abha-internal-contract.md`) was extracted and operator-reviewed in a
prior session; this session built against it and folded six operator-decided corrections (D1-D7,
including D6's packaging clarification and D7's audit-vocabulary addition) back into that same
document as resolved decisions, not left as open questions.

290 tests passing across the two packages this session touches (was 241 in `backend/core` alone
before this session; now 241 there plus 49 new pure-unit tests in `backend/abdm-adapter/tests/`
= 290 total; the two `tests` packages cannot be run in one `pytest` invocation together, both
literally named `tests`, colliding on import, so they are run as two separate invocations, matching
how they will run in CI). ruff and mypy strict clean across both packages. `alembic upgrade head`
clean from empty, `alembic check` clean; no new migration (checked directly against a fresh
database, not assumed).

### R0 / D6: packaging, confirmed built as the authoritative docs specify

`backend/abdm-adapter/` is a real sibling package: its own `pyproject.toml`, its own top-level
import name `abdm_adapter` (deliberately not `app`, which would collide with `backend/core`'s own
top-level package name in the same virtualenv), its own `tests/`. Installed editable into
`backend/core`'s venv (`uv pip install -e ../abdm-adapter`) and imported by `app/main.py`, which
mounts `abdm_adapter.router.router` the same way every other router in that file is mounted. One
FastAPI process, confirmed by a real `uvicorn` run in this session answering both `GET /health`
and all six `/api/v1/abha/*` routes from the same process. One PostgreSQL database, one Alembic
history (no new migration). One audit chain: every ABHA lifecycle event goes through
`app.services.audit.append`, the unchanged Phase 4 appender.

Mypy needed one addition to see across the package boundary: `backend/core/pyproject.toml` gained
`mypy_path = ["../abdm-adapter"]`, because `abdm_adapter` is installed via a PEP 660
finder-based editable install (the modern `uv`/setuptools mechanism), which mypy's own import
resolution does not execute; it needs the real source path directly. A `py.typed` marker was also
added to `abdm_adapter/` so the package is treated as typed at all once found.

### The full session STARTED -> COMPLETED walk

Both branches walked, in `backend/core/tests/test_abha.py` and by hand against real `uvicorn` +
the real dev database:

- No mobile verification needed (communication mobile matches the Aadhaar-linked mobile's visible
  suffix): `STARTED -> OTP_REQUESTED -> ENROLLED -> COMPLETED`, four HTTP calls
  (start, identity, otp, profile).
- Mobile verification needed: `STARTED -> OTP_REQUESTED -> MOBILE_VERIFICATION_REQUIRED ->
  MOBILE_VERIFIED -> COMPLETED`, five HTTP calls (adds mobile-otp).

Every illegal transition tested returns `409 SAMD-ABHA-2002`: skipping identity submission,
repeating identity submission, fetching the profile before enrollment, and (in
`backend/abdm-adapter/tests/test_state_machine.py`) every other non-adjacent or backward edge in
the table, checked exhaustively against `ALLOWED_TRANSITIONS`, not sampled.

### D2, proven end to end, not just at the classifier

`enrollment/auth/byAbdm` (mobile OTP verify) returns HTTP 200 for both a correct and an
incorrect/expired OTP; the discriminator is `body["authResult"]`. `abdm_adapter/errors.py`'s
`classify_otp_verify` reads the body first, HTTP status second. Proven with the operator's own
confirmed example body at the classifier level
(`test_error_mapping.py::test_d2_a_200_with_authresult_failed_is_not_success`) and end to end
through the real router and a real database
(`test_abha.py::test_d2_mobile_otp_expired_200_does_not_advance_state`, which asserts the
persisted transaction state is `FAILED`, not just that the HTTP response was an error).

### D5, proven, not assumed

`backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs` walks a full session,
then checks three places at once for the Aadhaar number used, the OTP used, the plaintext X-token
string, and a base64-JPEG magic-byte prefix (`/9j/`, proving no photo bytes leaked, not just that
Aadhaar/OTP didn't): a raw-SQL read of the `abha_transactions` row (deliberately bypassing the
ORM's `EncryptedText` decrypt hook, which would otherwise mask a plaintext-token bug by decrypting
it back to nothing-looks-wrong on the way out), every `audit_events.payload` and `sync_log.message`
row, and `capsys`-captured stdout/stderr for the whole test. `abdm_adapter/mapping.py`'s
`profile_to_abha_identity` never assigns the photo fields to anything it returns; `photo_url` is
always `null`.

**Correction, pre-commit review:** that test alone checks `external_token_encrypted` only after
the session reaches `COMPLETED`, where `service.py` has already cleared the column to `NULL`. A
column that is always `NULL` when checked is encrypted at rest vacuously; the test never actually
exercised the encryption boundary while a token was present. Added
`test_d5_token_is_encrypted_at_rest_while_present`, which stops mid-flow (after enrollment, before
the `/profile` call that clears the token), reads the raw column, and asserts both that it does
not contain the plaintext `stub-x-token-...` string and that the ORM's `EncryptedText` decrypt path
reads back exactly that plaintext, closing the loop from "different-looking bytes" to "genuinely
round-trippable pgcrypto ciphertext."

### Two real bugs this session's own tests caught before they shipped

- **The failure-path trap shipped a third time, caught before commit.** `_fail`'s first draft
  flushed `txn.state = FAILED` on the request session, then raised `SamdError`. `session_scope`
  rolls back the whole request transaction on any propagating exception, so that write silently
  vanished every time: a failed session stayed stuck at its prior state forever, never actually
  marked `FAILED`. This is the exact trap `app.db.session.write_out_of_band`'s own docstring names
  as having shipped twice already (Phase 1's failed-login row, Phase 3's kernel call-log rows).
  Caught by `test_invalid_otp_on_enrol_by_aadhaar_fails_the_session` and the D2 test above, both of
  which assert the *persisted* state, not only the HTTP response. Fixed by moving `_fail` onto
  `write_out_of_band` (re-fetching the row inside the out-of-band session, never reusing the
  request-session-bound ORM object) and by removing an early flush in `submit_identity` that would
  otherwise have deadlocked against it, per `write_out_of_band`'s own documented deadlock rule.
- **`dob.from_ddmmyyyy` crashed on a malformed value.** An unguarded `int()` call raised
  `ValueError` instead of returning `None`. Caught by
  `test_from_ddmmyyyy_malformed_is_none_not_a_crash`, written because D3's "represent honestly, do
  not fabricate" instruction implies "do not crash," which the first draft did not actually
  guarantee.

### Which writes are in the request transaction, and which are out of band

Every success path (session start, identity submission, OTP verification, enrollment, mobile
verification, profile retrieval, and the resulting `COMPLETED` transition) runs on the caller's
request-scoped session, matching `app/services/sync.py`'s own reasoning: none of these is an
external event that already happened before the recording write runs, so there is no failure-path
trap to guard against on the success side. Only the failure path (`_fail`) uses
`write_out_of_band`, and only because of the bug above: once a `SamdError` is about to propagate,
the row that must survive it cannot live in the session that is about to roll back.

### abha_transactions: used as-is, no migration, one column type corrected

Every column the brief asked for already existed from Phase 1 (`local_transaction_id`,
`external_txn_id`, `state`, `facility_id`, `worker_id`, `correlation_id`,
`external_token_encrypted`/`external_token_expires_at`, `abha_number`/`abha_address`/
`abha_status`/`abha_type`, `linked_patient_id`, `last_error_code`/`last_error_detail`,
`retry_count`/`otp_request_count`, `created_at`/`updated_at`/`expires_at`). No migration needed,
confirmed against a fresh database both before and after this session's one model change.

That one change: `external_token_encrypted` was `Mapped[bytes | None] = mapped_column(LargeBinary)`
with a comment saying "pgcrypto-encrypted when written," resting on whichever code writes to it
remembering to encrypt first, call-site discipline this codebase avoids everywhere else (see
`app/logging.py`'s redaction-processor docstring for the same argument made about logging). Changed
to `Mapped[str | None] = mapped_column(EncryptedText)`, the same decorator `Patient`'s identity
columns already use. The underlying Postgres column stays `bytea` either way
(`EncryptedText.impl` is `LargeBinary` too), so this was a Python-side type correction, not a
migration; confirmed by running `alembic check` clean against a fresh database both before and
after the change.

### Pre-commit review, two real gaps found and fixed before this landed

- **C1: the adapter's 49 tests were not gating.** `backend-ci.yml` only installed and tested
  `backend/core`; `backend/abdm-adapter` was never installed, never linted, never tested in CI,
  despite `app/main.py` importing it at runtime. Fixed: the Install step now also
  `pip install -e "../abdm-adapter[dev]"`, and two new steps (`Ruff lint (abdm-adapter)`,
  `Pytest (abdm-adapter)`) run from `backend/abdm-adapter`'s own directory, same as every other
  step in that job, no `continue-on-error`. Confirmed locally against the same venv CI would use:
  both the adapter's own `pytest -q` and `ruff check .` succeed run from its own directory with no
  extra flags.
- **C2: the D5 safety test's `external_token_encrypted` check was checking a column that is
  always `NULL` at the point it looks, which proves nothing about encryption.** See the correction
  in the D5 section above; `test_d5_token_is_encrypted_at_rest_while_present` now checks the
  column while a real value is present.

### Divergences from this brief

- None beyond what Phase A already flagged and this session resolved as D1-D7. R0's packaging
  question (sibling package, not literally inside `backend/core/app/`) was the one live ambiguity
  and is now built, tested, and confirmed against a real running process.

### Docs touched this session

`docs/requirements/abha-internal-contract.md` (D1-D7 resolved, plus the two bugs section above),
`docs/requirements/software-requirements.md` (REQ-ABH-03/04/05), `docs/requirements/
traceability-matrix.md` (same three rows), `docs/requirements/abha-field-mapping.md` (D4's Android
Phase 6 consequence, noted not fixed), `docs/quality/risk-management-file.md` (H-03's row extended
with the 2026-08 ABDM-adapter note: no patient linkage happens in this backend, linkage itself
stays open for Phase 6), `docs/backend/backend-prd.md` (section 6.2's five new actions, Phase 5
marked done in the phase table with the live-activation checklist inline).

### What Phase 6 inherits

Android wiring: a real `AbdmAbhaSource`/Retrofit implementation of the six internal endpoints, the
`mobileNumber` contact-method consequence noted in `abha-field-mapping.md` (D4), and the explicit,
worker-confirmed patient-linkage flow that H-03's risk-management-file row now tracks as open. Live
ABDM activation (separately gated on sandbox credentials) needs D1's cert URL and D4's masked-mobile
regex re-verified against real ABDM responses before being trusted, per `backend-prd.md`'s phase
table.

## Android Phase 6b: sync outbox (2026-08-18)

Branch `phase-6b`, off `master` at `d748ec7` (PR #5's merge commit) — `phase-6a` was already
merged to `master` before this session started (`git merge-base --is-ancestor phase-6a master`
confirmed), and `phase-6b` sat at the exact same commit as `master` with zero drift, so no
restacking onto `phase-6a` directly was needed; the auth/network foundation it depends on is
already present. Second of three Phase 6 sessions; 6c (ABHA wiring) is next.

### The outbox query layer

Per-entity, matching the repo's existing per-DAO-file convention (confirmed no unified/raw-SQL
query exists anywhere in this codebase before this session — genuinely new ground). Each of the
19 DAOs covering the 20 syncable tables (`PrescriptionDao` covers two: `prescriptions` and
`medication_lines`) gets three new methods: `getPendingForSync()` (`WHERE syncState = 'PENDING'
ORDER BY localModifiedAt ASC`), `applySyncResult(...)` (an `UPDATE` touching only `syncState`/
`serverVersion`/`syncErrorCode`/`lastSyncAttemptAt`, with `serverVersion = COALESCE(:new,
serverVersion)` so a `conflict`/`rejected` ack — which carries no fresh version — never wipes one
from a prior successful sync), and `observeFailedSyncCount()`. `RoomSyncOutboxRepository`
aggregates all 19 DAOs behind one `SyncOutboxRepository` seam (`collectPendingRecords`/
`applyAck`/`observeFailedCount`), deliberately a `data`-layer interface, not `domain/repository`:
its shape is the wire record, not a domain model.

Each entity gets a `toXyz().toSyncRecord(): SyncRecordDto` mapper (`SyncRecordMappers.kt`) into a
hand-written per-table payload DTO (`SyncPayloadDto.kt`, 20 classes, explicit `@SerializedName`
per field — matching this package's existing convention, `EvaluateReportDto.kt`'s own "do not
replace with a `FieldNamingPolicy`" warning is why a generic Gson-reflected-off-the-entity payload
was rejected as the lazier option). Every field name and exclusion was cross-checked directly
against `backend/core/app/services/sync.py`'s `TABLE_REGISTRY` and the SQLAlchemy models under
`backend/core/app/models/`, not just api-contract.md's one illustrative example — this is what
caught the divergence below.

### Divergence found: the brief's forbidden-field list was wrong about `attachments.uri`

The brief says `attachments.uri` "must NOT be sent; the backend rejects them (SAMD-SYNC-6006)",
same as `ailments.audio_local_uri`. Reading the actual backend boundary
(`backend/core/app/services/sync.py`'s `TABLE_REGISTRY`) shows this is false:
`"attachments": TableSpec(4, Attachment, aliases={"uri": "local_uri"})` — the backend *aliases*
the wire key `uri` to its own `local_uri` column and accepts it normally. Only
`ailments.audio_local_uri` is genuinely forbidden (`forbidden={"audio_local_uri":
ErrorCode.SYNC_FORBIDDEN_FIELD}`). api-contract.md §6.1 itself agrees with the code, not the
brief: its own forbidden-field table lists only `audio_local_uri`, and its attachments note
explains the `uri` -> `local_uri` alias directly. Built per the verified repo truth (api-contract
+ backend code): `AttachmentSyncPayloadDto.uri` is sent under the wire key `uri`, not excluded.
Flagging loudly per this brief's own instruction ("api-contract.md: only if implementation
revealed the contract wrong; if so, flag loudly") — except here it was the *session brief* that
was wrong, not api-contract.md, which was already correct. `SyncRecordMappersTest` locks this in
(`attachment uri is sent under the wire key uri, not renamed to local_uri`).

### The batch packer

`SyncBatchPacker` (pure, `@Inject`-able, no I/O): packs a `List<SyncRecordDto>` into
`SyncBatch(batchId, records)`, closing a batch and minting a fresh UUID whenever the next record
would push it past 400 records or 4.5 MB of *actual* serialized bytes (summed via the same
[Gson] instance — `SyncGson.create()` — the real Retrofit call uses, not an estimate; the 0.5 MB
gap to the backend's real 5 MB ceiling is deliberate headroom for the request envelope's own
`batch_id`/`device_id`/`client_time` overhead, which the per-record sum doesn't include). Headroom
under the backend's real 500-record/5 MB ceiling (`SyncBatchPacker.init` asserts this at class-load
time) makes the first-sync drain structurally incapable of tripping a 413 — no
retry-with-smaller-batch fallback exists anywhere, because none is needed.

### Crash-resume: the subtlest correctness point, built as designed

`InFlightBatchStore` (a new, separate Preferences DataStore file, `sync_outbox` — not sharing
`DataStoreAuthTokenStore`'s `auth_session` file, unrelated concern) persists a batch's `batch_id`
and its member `(table, id)` list *before* that batch is sent, and clears it only once that
batch's ack has been applied locally. `SyncOutboxDrainer.drain()` checks this first on every run:
if a batch is still marked in-flight, it re-fetches those specific rows (still `PENDING`, since
they're only marked otherwise after a recorded ack) and resends them under the *same* persisted
`batch_id` — never minting a fresh one on retry. The backend's idempotency store
(api-contract.md §6.1, unconditional in the actual code —
`backend/core/app/services/sync.py`'s `push()` returns `existing_batch.response_json` verbatim on
a `batch_id` hit with no time-bound check in the code path, despite the doc's "within 24 hours"
wording; worth the backend team re-confirming that's intentional) answers the replay with the
original stored response rather than re-applying, so the row ends up applied exactly once.

### Malformed rows (`rejected`) and the FAILED surface

`SyncAckMapping.kt`'s `toLocalSyncState()` is the one place api-contract.md §6.1's Android
handling rule lives, table-driven: `applied`/`stale`/`duplicate` -> `SYNCED` (serverVersion
updated when present); `conflict` -> a new `CONFLICT` `SyncState` value (surfaced, never silently
overwritten, never auto-retried — `collectPendingRecords()` only ever selects `PENDING`);
`rejected` -> `FAILED`, with the returned SAMD-SYNC-6xxx code stored in `syncErrorCode`. A
`FAILED` row is structurally excluded from the next `getPendingForSync()` call, which *is* "stop
retrying" — no separate retry-count column needed. `domain/sync/SyncStatus.kt`'s `SyncState` data
class gained one additive field, `failedCount: Int = 0` (default-valued, so this isn't a breaking
change to the two-member `SyncStatus` interface itself, which is unchanged), sourced from
`SyncOutboxRepository.observeFailedCount()` (a `combine` over all 19 DAOs' per-table FAILED
counts). No Phase 7 UI built — this only makes the count queryable, per the brief.

### The WorkManager worker and SyncStatusImpl

`androidx.work:work-runtime-ktx` + `androidx.hilt:hilt-work` added fresh (`gradle/libs.versions.
toml`, `app/build.gradle.kts`) — no `@HiltWorker` existed anywhere in this codebase before this
session, genuinely new plumbing. `SyncPushWorker` (`@HiltWorker` `CoroutineWorker`) is
deliberately thin: it calls `SyncOutboxDrainer.drain()` (which has no WorkManager/Android
dependency itself) and translates the `Result<Unit>` to WorkManager's own `Result` vocabulary.
`WorkManagerSyncOutboxScheduler` enqueues a connectivity-constrained, exponential-backoff periodic
job (`ensurePeriodicWork`, `KEEP` policy, called from `SyncStatusImpl.init`) and a one-time job for
"sync now" (`runNowAndAwait`) that awaits only the *first attempt's* outcome rather than
WorkManager's full backoff-retry schedule (`WorkInfo.State` has no bound on retry count, so a
naive await-until-terminal could block through several backoff cycles) — the underlying request
keeps retrying under WorkManager's own backoff afterward regardless of whether `syncNow()`'s
caller is still listening. `SaMDApplication` now implements `Configuration.Provider` (injects
`HiltWorkerFactory`), and `AndroidManifest.xml` removes WorkManager's default androidx-startup
self-initialization (which would otherwise construct WorkManager with the stock factory first and
crash on the first `@HiltWorker`).

`MockSyncStatus`/`MockSyncStatusTest` deleted outright, replaced by `SyncStatusImpl`/
`SyncStatusImplTest`. The six original behaviors are ported unchanged in substance (offline
refusal, auto-sync-on-online-transition with the same `drop(1)` startup guard, pendingCount
reading the real doctor-assignment queue) with a `FakeSyncOutboxScheduler`/
`FakeSyncOutboxRepository` standing in for the two new dependencies so these stay plain-JVM, not
instrumented. `syncNow()` now also triggers the generic outbox drain
(`syncOutboxScheduler.runNowAndAwait()`) alongside the untouched `caseRecordRepository.
sendAllPendingCases()` call — both run every time, and both are proven not to fight: draining a
`case_records` row's transport `syncState` (`RoomSyncOutboxRepository.applyAck` ->
`CaseRecordDao.applySyncResult`) touches only `syncState`/`serverVersion`/`syncErrorCode`/
`lastSyncAttemptAt`, never the clinical `status` column `assignDoctor`/`sendAllPendingCases`/
`updateStatus` own.

### First-sync drain and crash-resume, PROVEN (JVM, not instrumented — see below)

`SyncOutboxDrainerTest`'s three cases, run against `FakeSyncPushService` (models the backend's
own idempotency store closely enough to prove "exactly one applied copy" for real, not assert a
mock was called once) and `FakeSyncOutboxRepository`:
- **Large backlog:** 1200 synthetic records (well over the 400-record budget) drain in multiple
  HTTP calls, every one ends up in `syncedIds` (1200/1200, `toSet()` size also 1200 — none
  doubled, none dropped), every call's record count `<= MAX_RECORDS`, and the in-flight marker is
  cleared at the end. `SyncBatchPackerTest` separately proves the byte-budget boundary (a 100-
  record backlog padded past 4.5 MB splits purely on bytes, under 400 records) and the
  exactly-at-limit/one-over/single-oversized-record/empty edge cases the brief named explicitly.
- **Crash-resume:** first `SyncOutboxDrainer` instance sends a batch, the fake records it as
  backend-applied (so a same-`batch_id` replay is answered from its stored-response map) and then
  throws — simulating the process dying between "backend applied" and "ack recorded". A second,
  fresh `SyncOutboxDrainer` instance ("process restarted") resumes: `store.stored?.batchId` is
  asserted to match what the fake actually saw, `push()` is called twice for that one `batch_id`
  (crashed once, replayed once), and `repository.syncedIds.size == 3` — not 6 — proves the replay
  didn't double-apply.
- **Malformed row:** one of three records is configured to come back `rejected` (`SAMD-SYNC-
  6003`); the batch still succeeds overall (`result.isSuccess`), the bad row lands in
  `repository.failedIds` with its code in `failedCodes`, and a second `drain()` call with the
  push-call list cleared proves it is never resent.

### Testing: JVM only, no device or emulator in this environment

`adb devices` returns no attached device in this sandbox, so no `androidTest` (instrumented) run
was possible — the `SyncPushWorker`/`WorkManagerSyncOutboxScheduler`/`SaMDApplication.
Configuration.Provider` wiring is built and compiles (`hiltJavaCompileDevDebug` succeeded, which
validates the full Hilt dependency graph including `@HiltWorker`) but is not runtime-verified
against real WorkManager or a real device. Every other Phase 6b claim above — packer boundaries,
ack-status mapping, forbidden-field/wire-shape exclusion, large-backlog drain, crash-resume
batch_id reuse, malformed-row handling, and all six ported `SyncStatus` behavioral parities — runs
as a plain JVM test (`testDevDebugUnitTest`), deliberately designed that way (`SyncOutboxDrainer`
and `SyncStatusImpl` both take interface-typed dependencies — `SyncPushService`,
`SyncOutboxScheduler`, `SyncOutboxRepository`, `InFlightBatchStore` — precisely so the correctness
logic doesn't require Robolectric or a device to prove). No `MockWebServer`/real-backend
integration run either: no local backend was reachable in this environment (same limitation Phase
6a's kernel-rebase section hit). `testDevDebugUnitTest`: 144 -> 168 (30 new: 7 `SyncBatchPackerTest`
+ 6 `SyncAckMappingTest` + 5 `SyncRecordMappersTest` + 3 `SyncOutboxDrainerTest` + 9
`SyncStatusImplTest`, minus 6 deleted `MockSyncStatusTest`), 0 failures.

### Files changed

New: `data/remote/dto/SyncPushDto.kt`, `data/remote/dto/SyncPayloadDto.kt` (20 payload classes),
`data/remote/SyncGsonAdapters.kt` (`Instant`/`LocalDate` adapters + `SyncGson.create()`, the one
place this Gson config is built, shared by `NetworkModule.provideGson()` and every test that
needs to assert the real wire shape), `data/remote/SyncPushResult.kt`, `data/remote/
SyncPushService.kt` + `RetrofitSyncPushService.kt`, `data/remote/api/SyncPushApiService.kt`,
`data/sync/SyncRecordMappers.kt`, `data/sync/SyncBatchPacker.kt`, `data/sync/InFlightBatchStore.kt`,
`data/sync/SyncOutboxRepository.kt` + `RoomSyncOutboxRepository.kt`, `data/sync/
SyncAckMapping.kt`, `data/sync/SyncOutboxDrainer.kt`, `data/sync/SyncPushWorker.kt`, `data/sync/
SyncOutboxScheduler.kt` + `WorkManagerSyncOutboxScheduler.kt`, `data/sync/SyncStatusImpl.kt`
(replaces deleted `MockSyncStatus.kt`). Modified: `domain/sync/SyncStatus.kt` (`failedCount`
field), `di/NetworkModule.kt` (`provideGson`, `SyncPushApiService`, `SyncPushService` binding),
`di/RepositoryModule.kt` (`SyncStatusImpl`/`SyncOutboxScheduler`/`SyncOutboxRepository`/
`InFlightBatchStore` bindings, `MockSyncStatus` binding removed), `SaMDApplication.kt`
(`Configuration.Provider`), `AndroidManifest.xml` (WorkManager self-init removed), 19 DAO files
(3 new methods each, 20 tables), `gradle/libs.versions.toml` + `app/build.gradle.kts` (WorkManager/
hilt-work deps). Tests: `SyncBatchPackerTest.kt`, `SyncAckMappingTest.kt`,
`SyncRecordMappersTest.kt`, `SyncOutboxDrainerTest.kt`, `SyncStatusImplTest.kt`, `testutil/
SyncFakes.kt` (new); `testutil/Fakes.kt` + `VitalsRepositoryImplTest.kt`'s `FakeObservationDao`
(3 new interface methods each, to keep compiling against the widened DAO interfaces); deleted
`MockSyncStatus.kt` + `MockSyncStatusTest.kt`.

### Known gap found, not fixed (out of this brief's stated scope)

Several existing `UPDATE`-style DAO mutations across the app (`CaseRecordDao.updateStatus`/
`assignDoctor`/`sendAllPendingSync`, `ConsultationDao.updateTranscription`, `ReferralDao.
updateStatus`, and likely others) stamp `localModifiedAt` on a clinical mutation but do **not**
reset `syncState` back to `PENDING`. For a row that has never synced this is harmless (it's
already `PENDING`), but for a row that syncs once and is mutated again afterward, it would stay
`SYNCED` forever and never re-drain — silently stale server data, not corrupted data, but a real
gap. This predates Phase 6b (`MIGRATION_12_13`'s own KDoc already noted "nothing reads or writes
sync_state yet") and this brief's explicit scope is draining what is already `PENDING`, not
auditing every mutation path across the app for a missing reset. Flagging per "stop and report
rather than pulling it in" instead of silently fixing an unbounded number of call sites under an
already-large session.

### Divergences from this brief

1. `attachments.uri` forbidden-field claim was wrong (see the dedicated section above) — built
   per the verified backend/api-contract truth instead.
2. No instrumented/`androidTest`/`MockWebServer`/real-backend run: no device, emulator, or
   reachable local backend in this environment. All correctness claims proven as plain JVM tests
   against fakes designed to be behaviorally equivalent (see "Testing" above); the WorkManager/
   Hilt-worker wiring itself compiles and is DI-graph-validated but not runtime-exercised.
3. The pre-existing "clinical mutation doesn't reset syncState" gap (above) is reported, not
   fixed — genuinely out of this brief's stated scope.
4. No scope creep beyond what's listed above (the `AuthTokenStore`-shaped interface/impl splits
   for `SyncOutboxRepository`/`SyncOutboxScheduler`/`InFlightBatchStore`/`SyncPushService` mirror
   Phase 6a's own justified precedent: each exists because the drainer/SyncStatusImpl needed to
   be JVM-fakeable, not because a second production implementation was ever wanted.

### STOP

```text
================================================================
  CHECKPOINT: Phase 6b sync outbox COMPLETE
================================================================
  Branch: phase-6b, off master at d748ec7 (phase-6a already merged into master
    before this session started; phase-6b had zero drift from master, confirmed
    via merge-base, so no restack was needed).

  First-sync drain: 1200-record backlog (>400) drains in multiple correctly-bounded
    batches, all SYNCED, none dropped/doubled — PROVEN (SyncOutboxDrainerTest).
    Byte-budget boundary (>4.5MB via padded records) separately PROVEN
    (SyncBatchPackerTest), plus exactly-at-limit/one-over/oversized-single-record/
    empty edge cases.

  Crash-resume: batch_id reused on retry, backend verbatim-replay leaves exactly
    one applied copy (3, not 6, after a simulated crash+resume) — PROVEN
    (SyncOutboxDrainerTest). This is the subtle one; built as designed, no
    shortcuts taken.

  Malformed row -> FAILED, SAMD-SYNC-6003 code stored, not retried on next drain,
    count observable via SyncState.failedCount — PROVEN.

  Conflict -> new CONFLICT SyncState, kept for review, never auto-retried or
    silently clobbered — PROVEN (SyncAckMappingTest, table-driven).

  Forbidden fields: ailments.audio_local_uri excluded (PROVEN). attachments.uri is
    NOT excluded — the brief's claim was wrong, verified against backend code and
    api-contract.md itself; sent under wire key "uri", backend aliases it to
    local_uri. Flagged loudly (see Divergences).

  SyncStatus six behavioral parities: all PROVEN (SyncStatusImplTest, ported from
    MockSyncStatusTest with a fake outbox scheduler/repository substituted in).

  Worker uses the authenticated OkHttp/Retrofit client from 6a: PROVEN by
  construction (SyncPushApiService is created off the same shared Retrofit
    instance NetworkModule.provideRetrofit builds with bearerInterceptor +
    tokenAuthenticator attached — no second client exists).

  testDevDebugUnitTest: 144 -> 168 (30 new, 6 deleted MockSyncStatusTest), 0 failures.

  Tests: all JVM (testDevDebugUnitTest). No androidTest/instrumented run — no
    device or emulator available in this sandbox (adb devices: empty). No
    MockWebServer/real-backend integration run either — no local backend reachable.
    The WorkManager/@HiltWorker wiring compiles and is Hilt-DI-graph-validated
    (hiltJavaCompileDevDebug succeeded) but is not runtime-exercised.

  Scope creep: none beyond Phase 6a's own already-justified fakeable-interface
    pattern (see Divergences #4).

  Known gap found, NOT fixed (out of scope): several pre-existing DAO UPDATE
    methods don't reset syncState back to PENDING on a post-sync clinical
    mutation — a row that synced once and was edited again would never re-drain.
    Reported in PROGRESS.md's "Known gap" section above, not silently patched.

  Divergences from this brief: attachments.uri (wrong in the brief, fixed per
    verified truth), no instrumented/MockWebServer run (environment limitation),
    the syncState-reset gap (reported, not fixed). All three listed with reasoning,
    none silent.

  NEXT: Phase 6c ABHA wiring. DO NOT PROCEED. Wait for the operator.
================================================================
```

