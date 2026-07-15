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
- [x] GitHub Actions CI — unit tests + assembleDebug on every push/PR (see .github/workflows/android-ci.yml; passing)
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

## Not started
- [ ] Demo-theater additions from agent_docs/hardening.md (AI assessment panel, security shield sheet)
- [ ] Pre-production process blockers (flag to founder): ISO 13485 QMS + DHF, ISO 14971 risk file,
      software safety classification — see docs/regulatory-foundation.md §3
- [ ] Reconcile Patient.id spec gap (spec.md says 10–12 char UID; code generates 36-char UUID)