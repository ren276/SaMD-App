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
`id`, `patientId`, `vitalsId?`, `consultationId?`, `status` (`draft`/`saved_locally`/`sent_to_doctor`), `assignedDoctorId?`, `updatedAt`.

### `AuditLogEntity` (new — see agent_docs/hardening.md for why)
- `id: String`, `timestamp: Instant`
- `userId: String` (whoever is using the app — ASHA worker, nurse, etc.; can be a placeholder single-user ID until auth exists)
- `patientId: String?`, `caseRecordId: String?`
- `action: String` — e.g. `"encounter_started"`, `"audio_captured"`, `"kernel_response_received"`, `"consultation_locked"`
- `payload: String` — JSON blob of the relevant data at that moment
- No `UPDATE` or `DELETE` DAO methods on this entity's DAO. Insert-only, enforced at the DAO interface level, not just by convention.

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