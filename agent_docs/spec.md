# Spec — data models and screens

Pulled out of root CLAUDE.md so it's loaded on demand, not every session. This is the source of truth for field lists — flag gaps rather than silently extending.

## Data models

### `Patient`
- `id: String` — locally generated UID, 10–12 alphanumeric, assigned at creation (offline-first, not server-assigned)
- `fullName: String` (required)
- `dateOfBirth: LocalDate?`, `age: Int?` (either/or — age-only entry is common in rural registration)
- `gender: String`
- `guardianOrSpouseName: String?`
- `mobileNumber: String?` (required: at least one contact method between this and address)
- `aadhaarNumber: String?`, `abhaNumber: String?` (both optional — capture only, no ABHA creation flow)
- `village/block/district/state/pincode: String?`
- `category/occupation/maritalStatus/bloodGroup: String?`
- `knownAllergies: String?`, `emergencyContact: String?`
- `createdAt: Instant`

Only `id`, `fullName`, one contact method required in UI.

### `VitalsReading`
`id`, `patientId` (FK), `recordedAt`, `pulseBpm`, `bpSystolic`, `bpDiastolic`, `spo2Percent`, `temperatureCelsius`, `respiratoryRate`, `weightKg`, `heightCm`, `bmi` (calculated), `bloodGlucoseMgDl`, `source` (`"manual"` | `"device"`).

### `Consultation`
`id`, `patientId` (FK), `chiefComplaint`, `durationBucket` (`today`/`few_days`/`week_plus`/`chronic`), `severity` (`mild`/`moderate`/`severe`), `relevantHistory`, `attachments: List<Attachment>`, `transcription: String?`, `createdAt`.

### `Attachment`
`id`, `consultationId` (FK), `type` (`image`/`video`/`audio`/`affected_area_photo`), `uri`.

### `Doctor`
Mock/static JSON asset. `id`, `name`, `specialty`, `available`, `facilityName?`.

### `CaseRecord`
`id`, `patientId`, `vitalsId?`, `consultationId?`, `status` (`draft`/`saved_locally`/`pending_sync`/`sent_to_doctor`/`prescription_received`/`abandoned`), `assignedDoctorId?`, `updatedAt`. `ABANDONED` added 2026-07-20 (bug fix — see PROGRESS.md): `StartCaseUseCase`/`CaseRecordRepositoryImpl.createDraft` marks any pre-existing `DRAFT` case for the same `patientId` as `ABANDONED` before inserting a new one, so an orphaned in-progress draft (worker backed out mid-flow, never reached Acknowledgement) can't resurface via `HomeViewModel`'s crash-recovery resume prompt and get confused with the visit actually in progress.

### `AuditLogEntity` (new — see agent_docs/hardening.md for why)
- `id: String`, `timestamp: Instant`
- `userId: String` (whoever is using the app — ASHA worker, nurse, etc.; can be a placeholder single-user ID until auth exists)
- `patientId: String?`, `caseRecordId: String?`
- `action: String` — e.g. `"encounter_started"`, `"audio_captured"`, `"kernel_response_received"`, `"consultation_locked"`
- `payload: String` — JSON blob of the relevant data at that moment
- No `UPDATE` or `DELETE` DAO methods on this entity's DAO. Insert-only, enforced at the DAO interface level, not just by convention.

### Overhaul models (added Phase 0 — SaMD demo overhaul)

- `AbhaProfile` — mock ABHA identity, precedes registration. `abhaId` (14-digit `XX-XXXX-XXXX-XXXX`),
  `abhaAddress?`, `name`, `dateOfBirth?`, `gender`, `address?/district?/state?/pincode?`,
  `mobileNumber?`, `emailAddress?`, `photoUrlMock?`, `kycVerified`, `createdAt`. Links to `Patient`
  via `Patient.abhaNumber` (no duplicate id). Autofill map: `docs/requirements/abha-field-mapping.md`.
- `AilmentEntry` — supersedes free-text Symptom (Phase 2 rename). `id`, `patientId`, `encounterId`,
  `description`, `measurementType` (MEASURABLE/NON_MEASURABLE), `visibility` (PUBLIC/PRIVATE, default
  PUBLIC), `measuredValue?/measuredUnit?`, `severity?/onset?/duration?/qualifiers?` (guided capture),
  `audioLocalUri?` (private, local-only), `capturedAtOffline`, `syncedToCloudAt?`, `deletedAt?`
  (soft-delete), `createdAt`. PRIVATE hides from worker UI only — kernel gets all entries.
- `KernelReportOutput` — kernel's assessment (net-new; no prior AiKernelResponse). `id`,
  `caseRecordId`, `predictedCondition`, `confidenceScore` (0–1), `differentials: List<String>`,
  `reasoningSummary`, `evidenceFor/evidenceAgainst: List<String>`, `modelVersion`,
  `inferenceTimestamp`, `requiredHumanVerification` (< 0.90 → true). Distinct from `KernelPayload`
  (outbound request).
- `Prescription` + `MedicationLine` — doctor block (Phase 5). Prescription: `id`, `patientId`,
  `encounterId`, `caseRecordId`, `doctorId`, `diagnosis`, `medications`, `kernelDecision?`,
  `createdAt`. MedicationLine: `genericName`, `brandName?`, `strength`, `dosage`, `frequency`,
  `route`, `duration`, `quantity`, `foodRelation?`, `instructions?`. **No OD/BD/SOS** — frequencies
  spelled out (NMC/EU). Persists as `prescriptions` + child `medication_lines` (ordered by
  `position`). `KernelDecision` = AGREE/MODIFY/REJECT (doctor's call on the kernel differential).
  **The doctor's own review/authoring UI is a separate app/channel, out of scope here** — this app
  only implements the receiving side: `domain/doctor/DoctorPrescriptionInbox` (mock impl
  `MockDoctorPrescriptionInbox`) → `ReceiveDoctorPrescriptionUseCase` → `PrescriptionRepository`,
  triggered from `PatientSummaryScreen`'s "Check for doctor's response" action. `CaseStatus` gained
  `PRESCRIPTION_RECEIVED`.
- `ReferralRequest` — higher-facility referral (Phase 6). `id`, `patientUid`, `caseRecordId`,
  `urgencyLevel` (ROUTINE/URGENT/EMERGENCY), `reason`, `sendingPhcId`, `status`
  (QUEUED/SENT/ACKNOWLEDGED/CANCELLED), `timestamp`. PHC-side only, no receiver.

### Report contract (Phase 3 / 3.5)
- `ClinicalReport` (domain/report) — one progressively-assembled report object; sub-models
  `ReportHeader`/`ReportPatientBlock`/`ReportAilmentLine`/`ReportVitalLine`/`ReportMedicationLine`/
  `ReportSignatureBlock` + `ReportAudience` (WORKER redacts private ailments, PHYSICIAN shows all).
  Built by `ReportFormatter` (pure) via `AssembleReportUseCase`. Preliminary = kernel/prescription/
  signature null; final = populated + `isFinal`. Field bindings: `docs/requirements/
  report-field-mapping.md`.
- Rendering: `ReportCanvasRenderer` (one `android.graphics.Canvas` layout, A5) → both Compose
  preview and `ReportPdfExporter` (native `PdfDocument`, no external lib). `Code128` = dependency-
  free barcode encoder for the Patient-UID header barcode.
- `Doctor` +`registrationNumber` (mock NMC reg no in `doctors.json`) for the report's signature line.
- New read repos: `PrescriptionRepository`/`KernelReportRepository` (write side used by Phases 5/4).

### Extended existing models (Phase 0)
- `Patient`/`PatientEntity` +`guardianRelation` (minors only).
- `Observation`/`ObservationEntity` +`captureMethod` (data-quality), +`syncedToCloudAt` (dual
  timestamp; `createdAt`/`recordedAt` = offline capture).
- Room DB version **2 → 3** (`MIGRATION_2_3`, additive). New action strings in `AuditAction`.

## Screen flow (built — see PROGRESS.md for actual status)

1. Home — big-button entry, "Register new patient"
2. Register — `Patient` form
3. Vitals — core four (pulse/BP/SpO2/temp) + secondary set, pre-filled from `VitalsSource` mock, editable
4. Consultation — chief complaint (voice/text toggle), duration, severity, history, attachments
5. Sending — 1–2s async delay simulating kernel handoff
6. Transcription — `SpeechRecognizer` on audio attachment if present
7. Acknowledgement/save — real Room write, `status = "saved_locally"`
8. Doctor list — static JSON, "send" flips `status` to `"sent_to_doctor"`

Prescription return flow: out of scope, model is forward-compatible via `status` only.

## Package structure

```
app/
  presentation/{home,register,vitals,consultation,sending,transcription,acknowledgement,doctorlist}/
  domain/{model,repository,usecase,vitalssource,transcription,audit}/
  data/{local,repository,mock}/
  di/
```