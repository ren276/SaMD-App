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

## Not started
- [ ] Demo-theater additions from agent_docs/hardening.md (AI assessment panel, security shield sheet)
- [ ] Pre-production process blockers (flag to founder): ISO 13485 QMS + DHF, ISO 14971 risk file,
      software safety classification — see docs/regulatory-foundation.md §3
- [ ] Real authentication + RBAC enforcement (REQ-SEC-03) — mock login above does not satisfy this