# AUDIT4: offline/online patient identity, sync, and classifier data flow

Read-only characterization. Run date 2026-09-09. No file changed except this one. Branch at time
of audit: `fix/sync-before-assess`.

Scope: what the code does today, for a design memo on offline-first identity with ABDM
reconciliation and eventual merge of offline-created patients onto server patients. Where a prior
description and the code disagree, the code is quoted and wins.

Findings marked **UNDEFINED** or **SILENT** are the ones the design has to answer.

---

## Part 1: patient account creation and identity

### 1.1 End to end creation

`RegisterPatientUseCase` (`app/src/main/java/com/example/samdapp/domain/usecase/RegisterPatientUseCase.kt`):

- Id minting: `generatePatientId()` at `:26`, 12 chars from a 62-char alphabet over `SecureRandom`
  (`:14-16`). Called once at `:60` (`id = generatePatientId()`).
- **No existing-patient lookup happens before creating.** Not by ABHA, not by Aadhaar, not by
  name plus DOB, not by mobile. The KDoc states the design intent explicitly at `:19-25`: "no
  central registry to check against offline", and the only collision defense named is the Room
  primary key.
- Validation before insert is two rules only (`:51-57`): non-blank `fullName`, and at least one of
  mobile or village/district. Nothing validates ABHA or Aadhaar shape on device.
- `patientRepository.register(patient)` at `:85`.

`PatientRepositoryImpl.register` (`app/src/main/java/com/example/samdapp/data/repository/PatientRepositoryImpl.kt:19-21`)
is a bare `patientDao.insert(patient.toEntity())`. `PatientDao.insert` is
`@Insert` with no `onConflict` (`app/src/main/java/com/example/samdapp/data/local/dao/PatientDao.kt:13-14`),
so Room's default `OnConflictStrategy.ABORT` applies. That aborts only on primary key, which is
the freshly minted random id, so in practice it never fires.

`toEntity()` at `PatientRepositoryImpl.kt:45-70` sets `localModifiedAt = updatedAt` and leaves
`syncState` at its entity default `PENDING` (`PatientEntity.kt:34`).

**Finding: on device, a patient row is insert-only.** `PatientDao` has exactly one `UPDATE`
statement, and it touches only sync bookkeeping columns
(`PatientDao.kt:23-29`: `syncState`, `serverVersion`, `syncErrorCode`, `lastSyncAttemptAt`).
There is no demographic update path, no delete, no merge, no reparent. Nothing on device can
change a patient's identity fields after registration.

### 1.2 Identity fields and uniqueness

Device, `PatientEntity` (`app/src/main/java/com/example/samdapp/data/local/entity/PatientEntity.kt`):

- `id` primary key `:11`
- `fullName :12`, `dateOfBirth :13`, `age :14`, `biologicalSex :15`
- `mobileNumber :18`, `aadhaarNumber :19`, `abhaNumber :20`
- address block `:21-25`, demographics `:26-28`, `emergencyContact :29`
- ABDM provenance block `:47-59`: `abhaAddress`, `kycVerified`, `verificationSource`, `verifiedAt`
- `@Entity(tableName = "patients")` at `:9` declares **no indices and no unique constraints at
  all**. Device-side, two patients with the same ABHA, the same Aadhaar, or identical
  demographics are all legal rows.

Backend, `Patient` (`backend/core/app/models/patient.py`):

- `Index("ix_patients_abha_number", "abha_number", unique=True)` at `:75`. This is the **only**
  uniqueness constraint on identity. The module docstring `:22-24` says it is what enforces
  SAMD-PAT-3004, the wrong-patient guard, hazard H-03.
- `aadhaar_blind_idx`, `name_blind_idx`, `mobile_blind_idx` are indexed but **not unique**
  (`:76-78`). Aadhaar duplicates are accepted server side.
- CHECK constraints: id format `^[A-Za-z0-9]{10,12}$` (`:53-56`), ABHA bare 14 digits
  (`:58-61`), at least one contact method (`:64-67`), age range (`:68-71`).
- Encryption split (`:11-14`): `full_name`, `aadhaar_number`, `mobile_number`,
  `emergency_contact`, `guardian_or_spouse_name` are `EncryptedText`; `abha_number` is
  deliberately plaintext so it can carry the unique constraint (`:20-24`).

Asymmetry the design must absorb: **the device has no uniqueness rule for any identity field, the
backend has exactly one, and it is on the field the device never validates.**

### 1.3 The two write paths, and only one of them has the identity guard

Backend `create_patient` (`backend/core/app/services/patient.py:113-152`) calls
`_assert_abha_unclaimed` at `:145`, which raises `ErrorCode.PAT_DUPLICATE_ABHA`
(`SAMD-PAT-3004`, HTTP 409, `backend/core/app/errors.py:54` and `:123`) with the detail
"This ABHA number is already linked to a different patient." The guard's docstring
(`patient.py:79-84`) states the policy directly: "Never auto-merge, never reassign. Two patient
records claiming one ABHA number is the wrong-patient hazard (H-03), and it is resolved by a
human, not by whichever write landed second."

`update_patient` calls the same guard at `:175` when `abha_number` is in the PATCH body.

The sync path does not. `app/services/sync.py`'s `_apply_generic` (`:250-345`) is schema-driven
and calls no patient-specific validator; the registry entry is a bare
`"patients": TableSpec(1, Patient, server_owned=_PATIENT_BLIND_INDEXES)` (`sync.py:150`). A
duplicate ABHA arriving through sync therefore hits the raw unique index, is caught as an
`IntegrityError` at `sync.py:510-511`, and is reported as the generic
`SAMD-SYNC-6003` with sqlstate 23505's message "this record already exists with different data."
(`sync.py:125`).

**Finding: `_assert_abha_unclaimed` is unreachable from the Android app.** Grepping
`app/src/main/java` for `api/v1/patients` returns nothing: the device never calls the patient REST
endpoints (`backend/core/app/api/v1/patients.py:60,98,155,175`). Every patient the device creates
reaches the server through `/sync/push` only. So the one clean, human-resolvable duplicate-identity
error code in the system is never produced for the only client that exists.

### 1.4 ABHA: capture, and whether verified is distinguishable from unverified

Three separate representations exist, and they are not linked by any foreign key.

1. `PatientEntity.abhaNumber` (`:20`), a nullable free string. This is what registration captures,
   including from the demo quick-fill. Nothing on device validates it against the backend's
   14-digit CHECK, so a malformed ABHA is a device-accepted, server-rejected row.

2. `PatientEntity`'s ABDM provenance columns `abhaAddress`, `kycVerified`, `verificationSource`,
   `verifiedAt` (`:47-59`, added in MIGRATION_13_14). The KDoc says these are
   "the final, verified identity fields from a real `AbhaIdentity` response ... distinct from
   `abhaNumber`". **Finding: nothing writes them.** Grep across `app/src/main/java` for those four
   names returns only `AbhaProfileEntity`/`AbhaProfile`/report and screen code; no writer touches
   the patients table, and `PatientRepositoryImpl.toEntity` (`:45-70`) omits all four, so they take
   their `null` defaults on every registration. `PatientDao` has no update statement that could set
   them later (see 1.1). **These four columns are dead today.**

3. `AbhaProfileEntity` (`app/src/main/java/com/example/samdapp/data/local/entity/AbhaProfileEntity.kt`),
   primary key `abhaId` (`:11`), carries `kycVerified: Boolean` (`:23`) non-null. It has **no
   `patientId` column**. The only link from a patient to a verified profile is a string lookup by
   ABHA number at `app/src/main/java/com/example/samdapp/domain/usecase/AssembleReportUseCase.kt:49`:
   `patient.abhaNumber?.takeIf { it.isNotBlank() }?.let { abhaProfileRepository.getProfile(it) }`,
   and `AbhaProfileRepositoryImpl.getProfile(abhaId)` (`:23`). `ReportFormatter.kt:103-104` derives
   the report's `abhaVerified` from `abhaProfile?.kycVerified == true`.

So: **verified versus unverified ABHA does exist as a concept, but only inside `abha_profiles`,
joined to the patient by the ABHA number string itself.** On `patients` the concept is columns
with no writer. A patient whose ABHA was typed by hand and a patient whose ABHA came from a real
Aadhaar OTP enrolment are indistinguishable on the patient row.

Backend divergence on the same idea: `backend/core/app/models/patient.py:112-117` declares
`abha_number`, `abha_address`, `abha_status`, `kyc_status`, `verification_source`, `verified_at`.
The device models `kycVerified` as a Boolean while the backend models `kyc_status` as a
`String(20)`, and the device has no `abha_status` at all (`PatientEntity.kt:50-55` explains that
omission deliberately). These columns are not on the sync wire either way (see next paragraph), so
the mismatch is latent, not currently firing.

**Finding, wire gap:** `PatientSyncPayloadDto`
(`app/src/main/java/com/example/samdapp/data/remote/dto/SyncPayloadDto.kt:24-47`) carries
`abha_number` (`:33`) and nothing else ABHA-related. `abha_address`, `kyc_verified`,
`verification_source`, `verified_at` are absent. Even once something starts writing them on
device, ABDM verification provenance **would not sync**. And if a future change adds
`kyc_verified` to that DTO without a matching backend column name, `_apply_generic`'s unknown-field
check (`sync.py:274-277`) rejects the whole patient record with `SAMD-SYNC-6003`
"kyc_verified: unexpected field."

M1 enrolment code, for reference: `AbdmAbhaSource`
(`app/src/main/java/com/example/samdapp/domain/abha/AbdmAbhaSource.kt`) defines
`AbhaApiResult` as a three-arm sealed interface at `:64-76`: `Success<T>`, `Failure(code, message)`,
and `ProtocolViolation(message)`, the third deliberately not folded into `Failure(code = null)`
because that null code is reserved for "backend unreachable" and callers branch on it to decide
whether falling back to a mock is safe (`:68-74`). The eleven-value `AbhaTransactionState`
(`:80-92`) mirrors the backend enum. `EnrolAbhaUseCase.kt:119` carries a
`TODO(POST-BUILD-2)` noting `AbhaIdentity.verificationSource` is dropped on purpose today.
`EnrolAbhaUseCase` writes an `AbhaProfile` (`:125-137`) and touches **no patient row**: grepping
that file for "patient" returns nothing.

Server side there is one more link that the device cannot see: `AbhaTransaction.linked_patient_id`
`String(12)` (`backend/core/app/models/abha.py:95`). It stores a patient id, so it is part of any
future merge's rewrite surface, and it is not mirrored on device.

### 1.5 The demo quick-fill

`DemoPatientProfile` (`app/src/main/java/com/example/samdapp/data/mock/DemoPatientProfile.kt`),
an object in the **main** source set, not a flavor source set. Header at `:7-25`: five personas,
"DEMO ONLY, never shipped to production", `select()` sets an in-memory active persona that every
screen's fill button reads.

Each persona pins a fixed identity, including both national identifiers:

| Persona | line | `aadhaarNumber` | `abhaNumber` |
|---|---|---|---|
| persona 1 | `:139-140` | fixed | `91234500011122` |
| persona 2 | `:207-208` | fixed | `91234500022233` |
| Anita Kumari (typhoid) | `:283-284` | `748263910452` | `91234500033344` |
| persona 4 | `:352-353` | fixed | `91234500044455` |
| persona 5 | `:422-423` | fixed | `91234500055566` |

Why it collides: the ABHA is a per-persona constant while the patient id is minted fresh on every
registration (1.1). Register the same persona twice and you get two device patient rows claiming
one ABHA number. The first sync applies; the second hits `ix_patients_abha_number` and is
rejected. This is the exact mechanism confirmed against the live dev database in the
sync-before-assess diagnosis: `sync_log` shows `patients` id `oeouslKczEKN` rejected
`SAMD-SYNC-6003` "this record already exists with different data.", with
`91234500033344` already held by patient `Y2Y7bVTcZcZV`.

Consumers of the quick-fill: `RegisterScreen.kt`, `RegisterViewModel.kt`,
`CompounderViewModel.kt`, `ConsultationViewModel.kt`, `MedicalBackgroundViewModel.kt`.

**Finding, SILENT:** the demo persona dropdown and "Fill demo patient data" button in
`RegisterScreen.kt:129-150` are inside plain composable code with **no `BuildConfig` guard and no
flavor gate**. The file lives in `src/main`, so the comment "never shipped to production" at
`DemoPatientProfile.kt:11` is documentation, not a control. Five fixed Aadhaar numbers and five
fixed ABHA numbers are compiled into every flavor.

---

## Part 2: data flow and the parent/child graph

### 2.1 What hangs off a patient, and how

**Device (Room).** `EncounterEntity.kt:12` states the rule for the whole schema: relationships are
"via `@ForeignKey`, same posture as the rest of this schema (no FK constraints anywhere)". Grep
confirms: `ForeignKey` appears in exactly one comment line and zero declarations across
`data/local/entity/*.kt`. Every parent link below is a plain indexed string column with **no
referential integrity, no cascade, and no orphan detection**.

| Room table | file:line | parent columns |
|---|---|---|
| `encounters` | `EncounterEntity.kt:13-16` | `patientId` |
| `consultations` | `ConsultationEntity.kt:10-14` | `patientId`, `encounterId` |
| `attachments` | `AttachmentEntity.kt:10-13` | `consultationId` only |
| `observations` | `ObservationEntity.kt:12-16` | `patientId`, `encounterId` |
| `ailments` | `AilmentEntity.kt:11-15` | `patientId`, `encounterId` |
| `medical_history_items` | `MedicalHistoryItemEntity.kt:10-13` | `patientId` |
| `allergies` | `AllergyEntity.kt:10-13` | `patientId` |
| `family_history_entries` | `FamilyHistoryEntryEntity.kt:9-12` | `patientId` |
| `social_histories` | `SocialHistoryEntity.kt:8-10` | `patientId` **as the primary key** |
| `medication_entries` | `MedicationEntryEntity.kt:10-14` | `patientId`, `encounterId` nullable |
| `case_records` | `CaseRecordEntity.kt:10-14` | `patientId`, `encounterId` |
| `kernel_reports` | `KernelReportEntity.kt:16-19` | `caseRecordId`, unique index |
| `evaluate_reports` | `EvaluateReportEntity.kt:12-15` | `caseRecordId`, unique index |
| `diagnosis_feedback` | `DiagnosisFeedbackEntity.kt:10-13` | `caseRecordId` |
| `prescriptions` | `PrescriptionEntity.kt:10-15` | `patientId`, `encounterId`, `caseRecordId` |
| `medication_lines` | `PrescriptionEntity.kt:31-34` | `prescriptionId` |
| `referrals` | `ReferralEntity.kt:11-15` | **`patientUid`** and `caseRecordId` |
| `consultation_documents` | `ConsultationDocumentEntity.kt:15-22` | `consultationId`, `patientId` |
| `audit_log` | `AuditLogEntity.kt:9-15` | `patientId` nullable, `caseRecordId` nullable |
| `abha_profiles` | `AbhaProfileEntity.kt:9-11` | none, keyed by `abhaId` |

Two shape traps for a merge:

- `social_histories` uses `patientId` as its **primary key** (`SocialHistoryEntity.kt:10`), so
  reparenting two patients' social histories onto one id is a primary key collision, not a simple
  column rewrite.
- `referrals` names its patient column `patientUid`, not `patientId` (`ReferralEntity.kt:14`). Any
  merge written as a uniform "rewrite every `patientId` column" pass silently misses referrals.

**Backend (Postgres).** Real foreign keys, all `ondelete="RESTRICT"`, none with `ON UPDATE CASCADE`:

- to `patients.id`: `encounter.py:35` (encounters), `encounter.py:61` (consultations),
  `clinical.py:51` (observations), `clinical.py:96` (ailments), `clinical.py:140` (case_records),
  `history.py:32,50,67,82,107` (medical history, allergies, family history, social history,
  medication entries), `prescription.py:36`, `referral.py:41`
- to `encounters.id`: `encounter.py:43`, `encounter.py:64`, `clinical.py:54,99,143`,
  `history.py:111`, `prescription.py:39`
- to `case_records.id`: `kernel.py:49,96,124`, `prescription.py:43`, `referral.py:45`,
  `sync.py:138,207`
- to `consultations.id`: `attachment.py:36`
- to `prescriptions.id`: `prescription.py:71`, the one `CASCADE` in the clinical graph
- every syncable table also carries `facility_id` to `facilities.id` via `SyncMixin`
  (`mixins.py:40`)

`RESTRICT` with no `ON UPDATE CASCADE` means a server-side merge cannot repoint children by
updating `patients.id`; it must rewrite every child row explicitly, in rank order, inside one
transaction. There is no server code that does this: grep for `merge` or `reparent` in
`backend/core/app/api/v1/patients.py` returns nothing. The endpoints are POST create, GET roster,
GET one, PATCH one (`patients.py:60,98,155,175`), and PATCH refuses `id` outright
(`IMMUTABLE_FIELDS = frozenset({"id", "created_at", "facility_id"})`, `services/patient.py:52`,
raising `PAT_IMMUTABLE_FIELD` at `:180-184`).

**Finding, does not exist:** `consultation_documents` has no backend model at all (grep for
`consultation_documents` across `backend/core/app` returns nothing) and no `TABLE_REGISTRY` entry
(`sync.py:147-176` lists nineteen tables plus `audit_log`; documents are not among them). Yet
`ConsultationDocumentEntity` carries the full sync bookkeeping block, `syncState` defaulting to
`PENDING` (`:43-46`). And `RoomSyncOutboxRepository.collectPendingRecords` (`:54-76`) has no
document DAO in it. **Consultation documents are permanently PENDING, device-only, and invisible to
the server. If the device is lost, they are gone.** This is a second silent data-retention gap,
independent of the FAILED-row one in Part 3.

### 2.2 Id minting, and where "the patient id never changes" is baked in

All clinical ids are device-minted and stable; the server never assigns one.

- patient id: `RegisterPatientUseCase.kt:26`, 12 chars, `SecureRandom`
- encounter id: `EncounterRepositoryImpl.kt:22`, `UUID.randomUUID()`
- case record id: `CaseRecordRepositoryImpl.kt:27`, `UUID.randomUUID()`
- and 20 more `UUID.randomUUID()` id mints across `data/repository` and `domain/usecase`
- backend `_apply_generic` uses the client's id as the primary key verbatim
  (`sync.py:334-337`: `model(**{spec.pk_attr: record_id}, ...)`)
- `POST /patients` is idempotent on the client-generated id (`patients.py:60`,
  `services/patient.py:113-143`)

Places that assume a patient id is permanent, all of which a merge violates:

1. **Nineteen denormalised copies.** Every table in the 2.1 map stores the patient id (directly or
   through encounter/case/consultation) with no FK on device and `RESTRICT` FKs on server. A merge
   is a nineteen-table rewrite on two sides at once.
2. **Backend immutability rule.** `services/patient.py:52` plus `:180-184`. Changing a patient id
   through the existing API is refused by design.
3. **`social_histories` primary key** is the patient id (`SocialHistoryEntity.kt:10`, backend
   `history.py:82` with `pk_attr="patient_id"` in `sync.py:158`). Merging two patients who both have
   a social history row is a primary key conflict on both sides.
4. **Navigation routes carry the id as the screen's identity.**
   `presentation/navigation/Routes.kt:41,42,54,62,73,104,119` define seven routes keyed by
   `patientId`, and `Routes.kt:168-181` resolves the "current patient" for the whole nav graph from
   whichever route is on top. A merge while a screen is open leaves that screen pointing at an id
   that no longer names a patient. Nothing revalidates it.
5. **Audit rows pin the id.** `AuditLogEntity.kt:14` stores `patientId` on device, and the backend's
   audit chain is append-only and hash-chained (`services/audit.py`, referenced by
   `sync.py:429-441`). Audit history for a merged-away patient cannot be rewritten without breaking
   the chain, so post-merge the audit trail necessarily refers to an id the patient table no longer
   has. **UNDEFINED today.**
6. **ABHA-number join.** The only patient to verified-profile link is the ABHA string
   (`AssembleReportUseCase.kt:49`). A merge that changes which row holds an ABHA silently
   re-points that join.
7. **Server-side ABDM link.** `AbhaTransaction.linked_patient_id` (`models/abha.py:95`) stores a
   patient id outside the clinical FK graph, so it is not protected by `RESTRICT` and would be
   silently stale after a merge.

Note what is **not** affected: the kernel case token is derived from the case record id, not the
patient id (`backend/core/app/adapters/kernel/pseudonym.py:27-34`, HMAC-SHA256 truncated), so a
patient merge does not invalidate existing case tokens.

---

## Part 3: sync, online and offline

### 3.1 The full path

Collection: `RoomSyncOutboxRepository.collectPendingRecords`
(`app/src/main/java/com/example/samdapp/data/sync/RoomSyncOutboxRepository.kt:54-76`) calls
`getPendingForSync()` on twenty DAOs and maps each row through `toSyncRecord()`
(`SyncRecordMappers.kt:50` onward). Every one of those queries is
`WHERE syncState = 'PENDING'` (for example `PatientDao.kt:18`, `CaseRecordDao.kt:22`). The comment
at `:55-57` notes table order here is arbitrary because the backend re-sorts.

Packing: `SyncBatchPacker.pack` (`SyncBatchPacker.kt:39-64`), budgets 400 records / 4.5 MB
(`:67-68`), under the backend's 500 / 5 MB ceiling, with a `check()` guard at `:71-77`. Records
larger than the budget alone come back as `oversized`.

Ordering: **the device does not order the batch; the backend does.** `push()` sorts by fixed table
rank before applying (`sync.py:576`: `ordered = sorted(records, key=lambda r: ALL_TABLE_RANKS[r["table"]])`),
ranks defined at `sync.py:147-176` (patients 1, encounters 2, consultations 3, attachments 4,
observations 5, ailments 6, history tables 7 to 11, case_records 12, kernel_reports 13,
evaluate_reports 14, diagnosis_feedback 15, prescriptions 16, medication_lines 17, referrals 18,
abha_profiles 19, audit_log 20).

Sending and ack application: `SyncOutboxDrainer.drain` (`:48`) takes a process-wide mutex, resumes
any in-flight batch (`:51`, `:86-109`), then loops collect / pack / send (`:53-62`).
`sendAndApply` (`:111-146`) persists the batch to `InFlightBatchStore` before sending, and on
`SyncPushResult.Success` (`:131-140`) applies every per-record ack through
`repository.applyAck(...)` and clears the in-flight record. On `Failure` (`:141-144`) it
deliberately keeps the in-flight batch so the same `batch_id` is resent, which the backend's
24 hour idempotency store answers verbatim (`sync.py:544-552`).

`applyAck` (`RoomSyncOutboxRepository.kt:78-105`) is a twenty-arm `when` on `result.table` into each
DAO's `applySyncResult`, with `else -> error(...)` at `:104` for an unknown table.

State mapping: `SyncAckMapping.kt:11-16`.

```
"applied", "stale", "duplicate" -> SyncState.SYNCED
"conflict"                      -> SyncState.CONFLICT
"rejected"                      -> SyncState.FAILED
else                            -> error("Unknown sync ack status ...")
```

States: `SyncState.kt:7-27`, four values, `PENDING` / `SYNCED` / `CONFLICT` / `FAILED`.

Triggers: `SyncStatusImpl` (`SyncStatusImpl.kt`) schedules a 15 minute periodic worker at `:60`
(`WorkManagerSyncOutboxScheduler.kt:83`, `PERIODIC_INTERVAL_MINUTES = 15`), and auto-syncs only on
an offline to online **transition** (`:63-67`, `drop(1)` skips the startup state, and even then only
if `caseRecordRepository.observePendingSyncCount().first() > 0`). `syncNow()` (`:80-94`) refuses
while offline (`:81-83`), then runs the clinical case flip and the outbox drain.
`AssessmentRunner.run` calls `syncStatus.syncNow()` at `AssessmentRunner.kt:59` (the in-progress fix
on `fix/sync-before-assess`, commit `8a479c9`, not merged).

### 3.2 The rejected-row edge case, confirmed

What happens to a row the server rejects:

1. Ack maps to `SyncState.FAILED` (`SyncAckMapping.kt:14`).
2. `applySyncResult` writes `syncState = 'FAILED'` plus `syncErrorCode` and `lastSyncAttemptAt`,
   guarded by `AND localModifiedAt = :sentLocalModifiedAt` so a row edited during the round trip is
   not clobbered (`PatientDao.kt:23-29` and the same shape in every DAO).
3. **Nothing ever re-collects it.** Every `getPendingForSync` is `WHERE syncState = 'PENDING'`.
   There is no requeue, no backoff retry, no manual "retry failed rows" path anywhere in
   `data/sync`. This is intentional per `SyncState.kt:24-26` ("Retrying a malformed row forever
   drains the battery for nothing") and `SyncAckMapping.kt:8-10` ("`rejected` means malformed, stop
   retrying forever (the locked Phase 6b decision)").
4. **Nothing surfaces it in the UI.** `observeFailedCount()` (`RoomSyncOutboxRepository.kt:108-120`)
   sums FAILED across twenty tables and reaches `SyncStatusImpl.state` at `:75-77`, landing in
   `SyncState.failedCount` (`domain/sync/SyncStatus.kt:13`). Grepping `presentation/` for
   `failedCount` returns **zero hits**. `SyncStatusImpl.kt:37-39` says so itself: "Phase 7's admin
   view is out of scope; this only makes the count queryable."
5. The device's user-visible pending count is a different thing entirely:
   `SyncStatusImpl.kt:73` reads `caseRecordRepository.observePendingSyncCount()`, the clinical
   doctor-assignment queue (`CaseRecordRepositoryImpl.kt:48-52`, a pure local status flip that
   "doesn't touch the network"). **Outbox pending and outbox failed are both invisible to the
   worker.**

**Finding, SILENT and permanent.** A rejected clinical row is dead on the device, never retried,
never displayed, and never reaches the server. In the confirmed live case, one rejected patient
cascaded to 17 rejected children in a single batch (encounters, consultations, case_records,
11 observations, social_histories, medication_entries, family_history_entries), all now permanently
FAILED. The screen showed success throughout. Same class of defect as the batch-envelope-200 trap,
with data loss instead of a 404.

`CONFLICT` has the same dead end: `getPendingForSync` excludes it too, and no screen renders it.
`SyncState.kt:20-22` says a conflict "stays queued, surfaced for review", but **neither is true in
code**: it is not re-collected and nothing surfaces it. **UNDEFINED.**

### 3.3 Offline and online edge cases

1. **Register offline, then come online.** Row sits `PENDING`. Sync fires on the connectivity
   transition (`SyncStatusImpl.kt:63-67`) only if the clinical case queue is non-empty, otherwise it
   waits for the 15 minute periodic worker. Backend applies parent before child by rank
   (`sync.py:576`). Works, as long as no identity constraint is violated. Verified in the live
   database: batch `a060d4c3` applied 38 of 38 including patient, encounter and case record.

2. **Same patient registered on two devices.** Each device mints its own 12-char id
   (`RegisterPatientUseCase.kt:26`), so the server receives two rows for one human. If both carry
   the same ABHA, the second is rejected with `SAMD-SYNC-6003` and dies as in 3.2. If ABHA is null
   on both (the common field case, since ABHA is optional), **both are accepted as separate
   patients**: Aadhaar has no unique constraint (`models/patient.py:76`), name and mobile blind
   indexes are non-unique, and no dedup logic exists on either side. **Duplicate patients are the
   defined behavior today, silently.**

3. **Rejected parent cascading to rejected children.** Confirmed live and by code. Children fail
   with sqlstate 23503 to the same generic `SAMD-SYNC-6003` "a referenced record does not exist
   yet." (`sync.py:124`, `:510-511`), because each record applies under its own savepoint
   (`sync.py:485-509`) so one bad parent poisons no other record's transaction, but every child
   that references it still fails its FK. All of them land FAILED and dead per 3.2.

4. **Partially applied batch.** Structurally normal, not an error: `push()` returns HTTP 200 with
   per-record counts (`sync.py:592-596`, `:645-655`). The device applies each ack individually
   (`SyncOutboxDrainer.kt:132-138`) and treats the batch as a success regardless of how many
   records were rejected. **The child-applied-parent-rejected direction cannot occur server side**
   (FKs are `RESTRICT` and ranks order parents first), but the reverse, parent applied and child
   rejected for its own reason, leaves a server-side patient with missing clinical rows and no
   signal anywhere. **SILENT.**

5. **`/assess` before the case is server-side.** `_resolve_case_record`
   (`backend/core/app/services/kernel.py:86-94`) 404s with `SAMD-ENC-4002` when the case row is
   absent or belongs to another facility. On the device that exception is caught by
   `GenerateKernelReportUseCase.tryRealApi`'s blanket `catch (e: Exception)` at `:250-254`, which
   logs "Kernel API unavailable" and returns null, so the case falls to `kernelFallbackSource`
   (mock in dev, null in staging/prod) and then to `buildUnavailableOutput` (`:109-111`). See
   Part 4.

6. **Oversized record.** Marked FAILED locally with a synthetic ack, code
   `SAMD-SYNC-RECORD-TOO-LARGE`, without a network call (`SyncOutboxDrainer.kt:68-82`). Then dead
   per 3.2.

7. **Crash between backend apply and local ack.** Handled correctly. `InFlightBatchStore` persists
   the batch id and members before send, and the same `batch_id` is resent on the next drain,
   which the backend answers from its idempotency store verbatim
   (`SyncOutboxDrainer.kt:22-31`, `:86-109`; `sync.py:544-552`).

8. **Unreadable in-flight store.** Fails the whole drain rather than minting a fresh batch id
   (`SyncOutboxDrainer.kt:87-92`). Correct, and it means one corrupt file blocks all sync with no
   UI signal. **SILENT.**

9. **Two patients, one ABHA, offline.** No device-side check exists (1.1, 1.2), so both are created
   locally and the conflict is only discovered at sync time, as a generic reject. There is no
   representation on device for "this patient could not be accepted because someone else holds this
   ABHA": `syncErrorCode` holds the string `SAMD-SYNC-6003`, which conflates it with malformed
   payloads, unknown fields, and every other integrity failure. **UNDEFINED.**

10. **Facility transfer.** `_apply_generic` rejects an upsert whose stored row belongs to another
    facility (`sync.py:277-279`, "id: belongs to another facility."). A patient moving between PHCs
    has no path. **Does not exist.**

---

## Part 4: classifier data flow

### 4.1 How it is invoked today

Device to backend:

- `KernelApiService.assess` (`app/src/main/java/com/example/samdapp/data/remote/api/KernelApiService.kt:22-24`),
  `@POST("api/v1/assess")`, body `KernelAssessmentRequestDto`, response
  `ApiEnvelopeDto<KernelAssessmentResponseDto>`, base URL `BuildConfig.BACKEND_BASE_URL`.
- `RetrofitKernelSource.assess` (`data/remote/RetrofitKernelSource.kt:26-80`) builds the request:
  `case_token` = the case record id (`payload.caseToken`), `age`, `sex` normalised to one uppercase
  letter (`:45`), and six vitals each with a hardcoded default when absent: systolic 120, diastolic
  80, BMI 22.0 computed or defaulted (`:33-38`), heart rate 72, random glucose 100, SpO2 98
  (`:44-52`). **These defaults are indistinguishable downstream from measured values.**
- Response mapping at `:66-80`: top differential's `condition_tier` to `predictedCondition`,
  `probability` to `confidenceScore`, `evidence_for` / `evidence_against`, plus `triage_urgency` and
  `safety_screen_passed`. An absent or empty `differential_diagnosis` collapses to
  `predictedCondition = null` (`:65-67`).

Backend to classifier:

- Route `backend/core/app/api/v1/kernel.py:30-45`.
- `services/kernel.py assess` (`:439-470`): reads `case_token` from the body as the real case record
  id (`:448`), calls `_resolve_case_record` (`:449`, 404 `SAMD-ENC-4002` if missing), swaps in the
  HMAC pseudonym (`:450-452`, `adapters/kernel/pseudonym.py:27-34`), forwards via `_forward`
  (`:453-464`), and restores the real id on the way out (`:470`).
- `_forward` (`services/kernel.py:162+`) applies the PHI guard and circuit breaker, logs
  `kernel_call_log` and audit rows out of band, and posts to `settings.kernel_base_url`
  (`config.py:71`, default `http://10.16.4.182:8000`).
- No `kernel_reports` write happens here any more, deliberately (`services/kernel.py:465-467`).

Classifier container:

- `../SaMDClassifier/src/app.py:54` `@app.post("/v1/assess")`. Deterministic red-flag gate first
  (`:56-71`: SpO2 below 90, systolic at or above 180, diastolic at or above 110 return
  `EMERGENCY_REFERRAL` with a synthetic `critical_vitals_flag` differential), then an eight-feature
  XGBoost `predict_proba` (`:74-89`), then SHAP evidence extraction (`:91-110`).
- Features per `models/model_meta.json`: age, sex_encoded, systolic_bp, diastolic_bp, bmi,
  heart_rate, spo2, glucose. Labels: low_risk, moderate_risk, high_risk. Model version
  `toy-v0.6-observed-glucose-4tier`, training source `dataset/canonical_dataset.csv`.
- A second endpoint `@app.post("/api/v1/evaluate")` at `:164` and `@app.get("/health")` at `:209`.

### 4.2 The 404 collapse, confirmed

`GenerateKernelReportUseCase.invoke` (`:101-113`):

```
val output = tryRealApi(...)                                    // :109
    ?: kernelFallbackSource.fallback(...)                       // :110
    ?: buildUnavailableOutput(...)                              // :111
```

`tryRealApi`'s catch (`:250-254`):

```
} catch (e: Exception) {
    // Any failure (network down, timeout, HTTP error, parse error, server offline) is
    // logged here and returns null ...
    logger.warning("Kernel API unavailable — trying fallback source. Reason: ${e.message}")
    null
}
```

Confirmed: an HTTP 404 carrying `SAMD-ENC-4002` (case not found, a client-side sync defect) and a
genuine classifier outage take the same branch, produce the same log line, and reach the same mock
or unavailable output. The class-level KDoc states this scope explicitly at `:39-46`
("if the real call fails for ANY reason ... the exception is caught"). The one failure mode that
**is** separated is the empty-200 differential, which routes to `buildUnavailableOutput` at `:196`
and writes a distinct `KERNEL_EMPTY_DIFFERENTIAL` audit breadcrumb (`:180-195`), deliberately
without consulting the mock fallback.

So the observable clinical state is three-way ambiguous today: sync bug, kernel down, and network
down all render identically. Only the empty-differential case is distinguishable, and only in the
audit trail, not to the user.

### 4.3 On-device classifier: gap characterization

What exists:

- The classifier is a **Python service**, not a portable artifact. Runtime dependencies
  (`../SaMDClassifier/requirements.txt`): numpy 1.26.4, pandas 2.2.2, scikit-learn 1.5.0,
  xgboost 2.0.3, shap 0.45.1, fastapi, uvicorn, pydantic, requests, PyMuPDF,
  sentence-transformers 5.6.1, chromadb 1.5.9.
- Artifacts in `../SaMDClassifier/models/`: `model.json` and `symptom_model.json` (XGBoost native
  JSON boosters), `symptom_vectorizer_char.joblib`, `symptom_vectorizer_word.joblib`,
  `symptom_label_encoder.joblib` (pickled scikit-learn objects), plus three `*_meta.json`.
  **There is no ONNX, TFLite, or LiteRT export of any model in the repository.**
- The inference path is not the booster alone. `/v1/assess` builds a pandas DataFrame (`app.py:75-84`),
  calls `predict_proba` and `predict` (`:87-88`), then runs a **SHAP explainer** (`:91-110`) whose
  output is the `evidence_for` / `evidence_against` the app displays as clinical reasoning. The
  symptom path additionally needs the two scikit-learn vectorizers and the label encoder, and the
  requirements list `sentence-transformers` and `chromadb`, which imply an embedding plus vector
  store step in the wider pipeline.
- On-device inference scaffolding that does exist in the Android app: **only ASR.**
  `app/build.gradle.kts:181-185` vendors `libs/sherpa-onnx-1.13.7.aar` for on-device speech
  recognition, with `noCompress += listOf("onnx")` at `:120` and ABI handling at `:39`. That AAR
  bundles ONNX Runtime natively for sherpa's own use. There is **no `onnxruntime-android`
  dependency, no TensorFlow Lite, no LiteRT, no MediaPipe, no PyTorch Mobile** anywhere in
  `app/build.gradle.kts` or `gradle/libs.versions.toml`.

The gap, stated plainly and without proposing a route across it: there is no on-device path for the
classifier today, not a partial one. There is no exported model artifact an Android process could
load, no inference runtime wired for tabular models, no on-device equivalent of the SHAP
explanation the UI treats as clinical evidence, no on-device implementation of the deterministic
red-flag gate that currently lives in `app.py:56-71`, and no scikit-learn vectorizer equivalent for
the symptom model. The one genuinely present asset is an ONNX Runtime shipped inside an ASR AAR,
which is not a general model-serving surface. Running this classifier on device would be a
from-scratch build of the serving layer, plus a model export step that does not exist yet, plus a
decision about what replaces SHAP.

Not fictional in the same way the SLM audit found: unlike a claimed on-device SLM, the classifier
genuinely runs and genuinely produces real predictions. It simply runs only in a container, reached
over the network, and nothing about it is portable to the handset as it stands.

---

## Cross-cutting: undefined, silent, or absent

1. No device-side duplicate-identity detection of any kind, offline or online (1.1).
2. `PatientEntity`'s four ABDM provenance columns have no writer (1.4).
3. ABDM verification provenance is not on the sync wire (1.4).
4. `_assert_abha_unclaimed` and its `SAMD-PAT-3004` code are unreachable from the app, which uses
   only `/sync/push` (1.3).
5. Duplicate ABHA through sync degrades to the generic `SAMD-SYNC-6003`, indistinguishable from a
   malformed record (1.3, 3.3 case 9).
6. Two devices registering one human with no ABHA produce two accepted server patients, by design
   and silently (3.3 case 2).
7. Rejected rows are never retried and never surfaced. Permanent silent data loss (3.2).
8. Conflicted rows have the same dead end, contradicting `SyncState`'s own KDoc (3.2).
9. `consultation_documents` carries sync bookkeeping but has no server table and no outbox entry.
   Device-only forever (2.1).
10. Room has no foreign keys at all, so device-side orphans are structurally possible and
    undetected (2.1).
11. `referrals.patientUid` and `social_histories`' patient-id primary key both break the naive
    shape of a merge rewrite (2.1).
12. Audit rows and `AbhaTransaction.linked_patient_id` pin patient ids outside the FK graph;
    post-merge behavior is undefined (2.2).
13. No merge, reparent, or facility-transfer path exists on either side (2.2, 3.3 case 10).
14. `/assess` 404, kernel outage, and network loss are one indistinguishable user-visible state
    (4.2).
15. Six vitals defaults are substituted device-side with no marker, so the classifier cannot tell
    measured from assumed (4.1).
16. The demo quick-fill, with five fixed Aadhaar and five fixed ABHA numbers, is in `src/main` with
    no build guard despite a comment saying it never ships (1.5).

## Files read

Device: `RegisterPatientUseCase.kt`, `PatientRepositoryImpl.kt`, `PatientDao.kt`, `PatientEntity.kt`,
all twenty entity files under `data/local/entity/`, `SyncPayloadDto.kt`, `SyncPushDto.kt`,
`SyncRecordMappers.kt`, `RoomSyncOutboxRepository.kt`, `SyncBatchPacker.kt`, `SyncOutboxDrainer.kt`,
`SyncAckMapping.kt`, `SyncState.kt`, `SyncStatusImpl.kt`, `WorkManagerSyncOutboxScheduler.kt`,
`GenerateKernelReportUseCase.kt`, `RetrofitKernelSource.kt`, `KernelApiService.kt`,
`AssessmentRunner.kt`, `DemoPatientProfile.kt`, `RegisterScreen.kt`, `Routes.kt`,
`AbdmAbhaSource.kt`, `AbhaProfileEntity.kt`, `AssembleReportUseCase.kt`, `ReportFormatter.kt`,
`app/build.gradle.kts`, `gradle/libs.versions.toml`.

Backend: `services/sync.py`, `services/patient.py`, `services/kernel.py`, `api/v1/patients.py`,
`api/v1/kernel.py`, `models/patient.py`, `models/clinical.py`, `models/encounter.py`,
`models/history.py`, `models/kernel.py`, `models/prescription.py`, `models/referral.py`,
`models/attachment.py`, `models/abha.py`, `models/sync.py`, `models/mixins.py`, `errors.py`,
`config.py`, `adapters/kernel/pseudonym.py`.

Classifier: `../SaMDClassifier/src/app.py`, `requirements.txt`, `models/model_meta.json`,
`models/` listing.

No `.env` or credential file was opened, searched for, or read at any point in this audit.
