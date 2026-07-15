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
- **REQ-REG-03** (DONE) Assign a locally-generated unique patient ID at creation (offline-first).
  *Open: ID format deviates from spec (UUID vs 10–12 char) — see risk H-03.*

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
- **REQ-SEC-03** (PLANNED) Authenticate users and enforce role-based access (risk H-06).

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
