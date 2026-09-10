# AUDIT — patient registered via app UI is invisible on Home and the Patients tab

- **Date:** 2026-08-27
- **Branch:** `master` (read-only investigation; no branch, no code, no commits)
- **Scope:** why a patient registered through the app UI does not appear on the Home dashboard or the Patients bottom-nav tab.
- **Out of scope:** BUILD 2 / ABHA enrolment. `abha_profiles` synced correctly; nothing found here implicates BUILD 2.

---

## Headline

**Diagnosis D — the patient row was created, enqueued, pushed, and accepted by the backend. Both Home and the Patients tab filter it out by design: the only patient-list query on the device is an `INNER JOIN encounters`, and this patient has no encounter.**

The backend has the row (`patients.id = zwXWZzoLx7hr`, `sync_log` status `applied`). `encounters` is empty (0 rows). No bug exists in the register flow, the outbox, or the backend contract. The design intent **is documented** (REQ-ROS-01/REQ-ROS-02, risk H-04) — so this is design, not a defect. The UX consequence is real and is a product decision, not a code fix.

---

## Q1 — Did a `patients` row actually get created on the device?

**Yes. Unconditionally, on the register button tap. No "only save if consultation started" conditional exists anywhere on the path.**

Trace of the tap:

| Step | File:line | What happens |
|---|---|---|
| 1. Tap | `presentation/register/RegisterScreen.kt` | Submit button → `RegisterActions.onSubmit()` |
| 2. ViewModel | `presentation/register/RegisterViewModel.kt:206-251` | `onSubmit()` — guard is `if (!current.canSubmit) return` only (name + one contact method + no field-length errors). Nothing about encounters/consultations. |
| 3. Use case | `domain/usecase/RegisterPatientUseCase.kt:28-86` | Validates name non-blank and phone-or-address; mints a 12-char id; builds `Patient`; calls `patientRepository.register(patient)` at **:85** |
| 4. Repository | `data/repository/PatientRepositoryImpl.kt:17-19` | `register()` = `asDataResult { patientDao.insert(patient.toEntity()) }` — a bare `@Insert`, no branching |
| 5. Room | `data/local/dao/PatientDao.kt:13-14` | `@Insert suspend fun insert(patient: PatientEntity)` |
| 6. Post-success | `RegisterViewModel.kt:234-243` | On success: audit `PATIENT_REGISTERED`, then emit `RegisterEffect.Registered(patient.id)` |
| 7. Navigation | `presentation/navigation/AppNavHost.kt:226-231` | `onRegistered = { patientId -> backStack.add(MedicalBackground(patientId)) }` |

The device DB was not dumped (SQLCipher-encrypted, `DatabasePassphraseProvider` + Keystore passphrase — `run-as` copy would yield an unreadable file). It did not need to be: **the backend proves the row existed on the device**, since the only way a `patients` row reaches the server is the device outbox push (Q2/Q3).

Corroborating: the same session also pushed `medical_history_items`, `family_history_entries` (×2), and `social_histories` for `zwXWZzoLx7hr` — so the flow continued past Register into `MedicalBackgroundScreen` and those rows saved too.

---

## Q2 — Did the `patients` row make it into the sync outbox?

**Yes. Enqueue is implicit — no separate call is needed, and none is missed.**

The outbox is **poll-based, not event-based**. There is no "enqueue" API to forget to call:

- `data/local/entity/PatientEntity.kt:34` — `val syncState: SyncState = SyncState.PENDING` (default). Every inserted row is born pending.
- `data/local/dao/PatientDao.kt:18-19` — `@Query("SELECT * FROM patients WHERE syncState = 'PENDING' ORDER BY localModifiedAt ASC") suspend fun getPendingForSync()`
- `data/sync/RoomSyncOutboxRepository.kt:54-58` — `collectPendingRecords()` calls `patientDao.getPendingForSync()` first, as one of the twenty syncable tables. Comment at `:55`: *"Table order here is arbitrary — the backend re-sorts every batch by its own apply-rank regardless of array order (api-contract.md §6.1)"*
- Ack path: `RoomSyncOutboxRepository.kt:84` maps `"patients"` → `patientDao.applySyncResult(...)`

So `patientDao.insert()` **is** the enqueue. Confirmed empirically — the row reached the server (Q3).

### TABLE_REGISTRY — Patient vs AbhaProfile side by side

`backend/core/app/services/sync.py`:

```python
TABLE_REGISTRY: dict[str, TableSpec] = {
    # Blind indexes are computed server side from the plaintext by apply_blind_indexes(), never
    # accepted from the wire: a client-supplied value would be meaningless without the server's
    # own HMAC key, and PatientEntity has no such properties to send in the first place.
    "patients": TableSpec(1, Patient, server_owned=_PATIENT_BLIND_INDEXES),
    ...
    "abha_profiles": TableSpec(
        19, AbhaProfile, pk_attr="abha_id", server_owned=frozenset({"mobile_blind_idx"})
    ),
}
```

| | `patients` | `abha_profiles` |
|---|---|---|
| rank | **1** (applied first) | **19** |
| model | `Patient` | `AbhaProfile` |
| `pk_attr` | `id` (default) | `abha_id` |
| `server_owned` | `_PATIENT_BLIND_INDEXES` = `{"name_blind_idx", "mobile_blind_idx", "aadhaar_blind_idx"}` | `{"mobile_blind_idx"}` |
| `forbidden` | — | — |
| `aliases` | — | — |

`Patient` is registered as a push target, at the **lowest** rank (applied before everything that FKs to it). Nothing about its registration is conditional on an encounter.

---

## Q3 — What the backend actually recorded

### Real column names (verbatim `\d`)

<details>
<summary><code>\d patients</code></summary>

```
                                          Table "public.patients"
          Column          |           Type           | Collation | Nullable |            Default
--------------------------+--------------------------+-----------+----------+-------------------------------
 id                       | character varying(12)    |           | not null |
 full_name                | bytea                    |           | not null |
 guardian_or_spouse_name  | bytea                    |           |          |
 mobile_number            | bytea                    |           |          |
 aadhaar_number           | bytea                    |           |          |
 emergency_contact        | bytea                    |           |          |
 name_blind_idx           | character varying(32)    |           |          |
 aadhaar_blind_idx        | character varying(32)    |           |          |
 mobile_blind_idx         | character varying(32)    |           |          |
 date_of_birth            | date                     |           |          |
 age                      | integer                  |           |          |
 biological_sex           | character varying(20)    |           | not null |
 guardian_relation        | character varying(50)    |           |          |
 village                  | character varying(120)   |           |          |
 block                    | character varying(120)   |           |          |
 district                 | character varying(120)   |           |          |
 state                    | character varying(120)   |           |          |
 pincode                  | character varying(6)     |           |          |
 category                 | character varying(50)    |           |          |
 marital_status           | character varying(50)    |           |          |
 blood_group              | character varying(10)    |           |          |
 primary_care_clinic_name | character varying(200)   |           |          |
 referring_physician_name | character varying(200)   |           |          |
 abha_number              | character varying(14)    |           |          |
 abha_address             | character varying(255)   |           |          |
 abha_status              | character varying(20)    |           |          |
 kyc_status               | character varying(20)    |           |          |
 verification_source      | character varying(40)    |           |          |
 verified_at              | timestamp with time zone |           |          |
 created_at               | timestamp with time zone |           | not null |
 updated_at               | timestamp with time zone |           | not null | now()
 facility_id              | character varying(32)    |           | not null |
 server_version           | integer                  |           | not null | 1
 received_at              | timestamp with time zone |           | not null | now()
 sync_state               | character varying(20)    |           | not null | 'RECEIVED'::character varying
Indexes:
    "pk_patients" PRIMARY KEY, btree (id)
    "ix_patients_aadhaar_blind_idx" btree (aadhaar_blind_idx)
    "ix_patients_abha_number" UNIQUE, btree (abha_number)
    "ix_patients_facility_id" btree (facility_id)
    "ix_patients_facility_updated" btree (facility_id, updated_at)
    "ix_patients_mobile_blind_idx" btree (mobile_blind_idx)
    "ix_patients_name_blind_idx" btree (name_blind_idx)
    "ix_patients_sync_state" btree (sync_state)
Check constraints:
    "ck_patients_ck_patients_abha_format" CHECK (abha_number IS NULL OR abha_number::text ~ '^[0-9]{14}$'::text)
    "ck_patients_ck_patients_age_range" CHECK (age IS NULL OR age >= 0 AND age <= 130)
    "ck_patients_ck_patients_contact_method" CHECK (mobile_blind_idx IS NOT NULL OR village IS NOT NULL OR district IS NOT NULL)
    "ck_patients_ck_patients_id_format" CHECK (id::text ~ '^[A-Za-z0-9]{10,12}$'::text)
    "ck_patients_patients_sync_state" CHECK (sync_state::text = ANY (ARRAY['RECEIVED'::character varying, 'CONFLICT'::character varying]::text[]))
Foreign-key constraints:
    "fk_patients_facility_id_facilities" FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE RESTRICT
Referenced by:
    TABLE "ailments" CONSTRAINT "fk_ailments_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "allergies" CONSTRAINT "fk_allergies_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "case_records" CONSTRAINT "fk_case_records_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "consultations" CONSTRAINT "fk_consultations_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "encounters" CONSTRAINT "fk_encounters_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "family_history_entries" CONSTRAINT "fk_family_history_entries_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "medical_history_items" CONSTRAINT "fk_medical_history_items_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "medication_entries" CONSTRAINT "fk_medication_entries_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "observations" CONSTRAINT "fk_observations_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "prescriptions" CONSTRAINT "fk_prescriptions_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "referrals" CONSTRAINT "fk_referrals_patient_uid_patients" FOREIGN KEY (patient_uid) REFERENCES patients(id) ON DELETE RESTRICT
    TABLE "social_histories" CONSTRAINT "fk_social_histories_patient_id_patients" FOREIGN KEY (patient_id) REFERENCES patients(id) ON DELETE RESTRICT
```
</details>

**Why the earlier guessed queries failed:** `patients` has **no** `name`/`created_by`/`worker_id` column, and `full_name` / `mobile_number` / `aadhaar_number` are `bytea` (encrypted at rest) — not text. The timestamp columns are `created_at` / `updated_at` / `received_at`.

<details>
<summary><code>\d sync_log</code></summary>

```
                                       Table "public.sync_log"
     Column     |           Type           | Collation | Nullable |             Default
----------------+--------------------------+-----------+----------+----------------------------------
 id             | bigint                   |           | not null | generated by default as identity
 batch_id       | character varying(36)    |           | not null |
 table_name     | character varying(60)    |           | not null |
 record_id      | character varying(36)    |           | not null |
 status         | character varying(20)    |           | not null |
 code           | character varying(20)    |           |          |
 message        | text                     |           |          |
 server_version | integer                  |           |          |
 applied_at     | timestamp with time zone |           | not null | now()
Indexes:
    "pk_sync_log" PRIMARY KEY, btree (id)
    "ix_sync_log_batch_id" btree (batch_id)
    "ix_sync_log_table_record" btree (table_name, record_id)
    "uq_sync_log_audit_log_record_id" UNIQUE, btree (table_name, record_id) WHERE table_name::text = 'audit_log'::text
Foreign-key constraints:
    "fk_sync_log_batch_id_sync_batches" FOREIGN KEY (batch_id) REFERENCES sync_batches(batch_id) ON DELETE CASCADE
```
</details>

Note: the column is **`status`**, not `outcome`; the timestamp is **`applied_at`**, not `created_at`; the table column is **`table_name`**, not `table`.

<details>
<summary><code>\d sync_batches</code></summary>

```
                        Table "public.sync_batches"
    Column     |           Type           | Collation | Nullable | Default
---------------+--------------------------+-----------+----------+---------
 batch_id      | character varying(36)    |           | not null |
 device_id     | character varying(64)    |           | not null |
 worker_id     | character varying(16)    |           | not null |
 facility_id   | character varying(32)    |           | not null |
 received_at   | timestamp with time zone |           | not null | now()
 record_count  | integer                  |           | not null | 0
 applied       | integer                  |           | not null | 0
 stale         | integer                  |           | not null | 0
 conflicted    | integer                  |           | not null | 0
 rejected      | integer                  |           | not null | 0
 response_json | jsonb                    |           |          |
Indexes:
    "pk_sync_batches" PRIMARY KEY, btree (batch_id)
    "ix_sync_batches_facility_received" btree (facility_id, received_at)
    "ix_sync_batches_device_id" btree (device_id)
Foreign-key constraints:
    "fk_sync_batches_facility_id_facilities" FOREIGN KEY (facility_id) REFERENCES facilities(id) ON DELETE RESTRICT
```
</details>

The primary key is **`batch_id`**, not `id`.

### Query results (verbatim)

**Last 5 `patients`** — PHI columns (`full_name`, `mobile_number`, `aadhaar_number`) deliberately omitted; they are `bytea` ciphertext and dumping them serves no purpose:

```sql
SELECT id, facility_id, abha_number, sync_state, server_version, created_at, updated_at, received_at
FROM patients ORDER BY received_at DESC LIMIT 5;
```
```
      id      | facility_id |  abha_number   | sync_state | server_version |         created_at         |         updated_at         |          received_at
--------------+-------------+----------------+------------+----------------+----------------------------+----------------------------+-------------------------------
 zwXWZzoLx7hr | PHC-RJ-0142 | 91756140880001 | RECEIVED   |              1 | 2026-08-27 05:55:52.684+00 | 2026-08-27 05:55:52.684+00 | 2026-08-27 05:56:42.011802+00
(1 row)
```

**The patient IS on the backend.** Correct facility, correct ABHA number, `sync_state = RECEIVED` — same batch and same `received_at` as the `abha_profiles` row.

**Last 15 `sync_log`:**

```sql
SELECT id, batch_id, table_name, record_id, status, code, message, server_version, applied_at
FROM sync_log ORDER BY applied_at DESC, id DESC LIMIT 15;
```
```
 id |               batch_id               |       table_name       |              record_id               | status  | code | message | server_version |          applied_at
----+--------------------------------------+------------------------+--------------------------------------+---------+------+---------+----------------+-------------------------------
 12 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | 96de6985-237e-4823-8259-d2b2e1234b05 | applied |      |         |                | 2026-08-27 05:56:42.011802+00
 11 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | 6a1d35c8-29b8-4633-aa24-5c127551c02e | applied |      |         |                | 2026-08-27 05:56:42.011802+00
 10 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | d5b592f4-989e-4cda-a5d6-109ac2db2492 | applied |      |         |                | 2026-08-27 05:56:42.011802+00
  9 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | 1ff3221d-4fc9-4927-ade4-a4afe91191ee | applied |      |         |                | 2026-08-27 05:56:42.011802+00
  8 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | 28a9d26d-a790-4df2-b29b-9e7faf8e7c84 | applied |      |         |                | 2026-08-27 05:56:42.011802+00
  7 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | audit_log              | 2334f553-8d6d-4f32-9355-75d47a7fc8af | applied |      |         |                | 2026-08-27 05:56:42.011802+00
  6 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | abha_profiles          | 91756140880001                       | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
  5 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | social_histories       | zwXWZzoLx7hr                         | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
  4 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | family_history_entries | f79c2c02-22e2-4886-bd7a-b015700dfaa4 | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
  3 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | family_history_entries | 71e7bb5e-5d67-447c-aa34-dac2e002d171 | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
  2 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | medical_history_items  | 4d4daa95-ccdc-4014-a49e-4b5c40937155 | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
  1 | f4d8973a-aa75-4a36-b53a-9293e8a3a048 | patients               | zwXWZzoLx7hr                         | applied |      |         |              1 | 2026-08-27 05:56:42.011802+00
(12 rows)
```

Twelve rows total — the entire sync history of this database. `sync_log id=1` is the patient: **`status = applied`, `code` NULL, `message` NULL.** Not rejected, not conflicted, not stale.

**Last 5 `sync_batches`:**

```sql
SELECT batch_id, device_id, worker_id, facility_id, received_at, record_count, applied, stale, conflicted, rejected
FROM sync_batches ORDER BY received_at DESC LIMIT 5;
```
```
               batch_id               |              device_id               |    worker_id     | facility_id |          received_at          | record_count | applied | stale | conflicted | rejected
--------------------------------------+--------------------------------------+------------------+-------------+-------------------------------+--------------+---------+-------+------------+----------
 f4d8973a-aa75-4a36-b53a-9293e8a3a048 | 528d83ad-2b0f-4ec5-929b-1bd0692a4a32 | 949ad656774570f6 | PHC-RJ-0142 | 2026-08-27 05:56:42.011802+00 |           12 |      12 |     0 |          0 |        0
(1 row)
```

One batch, 12 records, **12 applied, 0 stale, 0 conflicted, 0 rejected.** Worker `949ad656774570f6`, facility `PHC-RJ-0142` — matches the tested session exactly.

**And the decisive negative:**

```sql
SELECT count(*) FROM encounters;   -- 0
SELECT count(*) FROM case_records; -- 0
```

The patient exists. The patient has **no encounter**.

---

## Q4 — What each screen reads, and the filter applied

Both surfaces resolve to **the same single DAO query**, which requires an encounter.

### Home dashboard

| Step | File:line |
|---|---|
| Screen | `presentation/home/HomeScreen.kt` renders `HomeUiState.todaysPatients` |
| ViewModel | `presentation/home/HomeViewModel.kt:53-57` — `getTodaysPatientsUseCase().collect { ... }`. Comment at `:52`: *"Day-scoped roster only — the repository never exposes the full patient table."* |
| Use case | `domain/usecase/GetTodaysPatientsUseCase.kt` → `patientRepository.observeTodaysPatients()` |
| Repository | `data/repository/PatientRepositoryImpl.kt:24-31` — resolves today's local-day bounds, calls `patientDao.observePatientsWithEncounterBetween(startMillis, endMillis)` |

### Patients bottom-nav tab

| Step | File:line |
|---|---|
| Screen | `presentation/patients/PatientsScreen.kt:52-56` — empty state reads **"No patients seen in the last 7 days."** |
| ViewModel | `presentation/patients/PatientsViewModel.kt:30-43` — `combine(getRecentPatientsUseCase(), _query)`; the query filter is client-side `fullName`/`id` substring only |
| Use case | `domain/usecase/GetRecentPatientsUseCase.kt` → `patientRepository.observeRecentPatients()` (default `days = 7`) |
| Repository | `data/repository/PatientRepositoryImpl.kt:33-40` — widens the window to `today - 6 .. today + 1`, calls **the same** `patientDao.observePatientsWithEncounterBetween(startMillis, endMillis)` |

So: it is **neither** `getAllForFacility()` **nor** a distinct "with encounters" variant. There is only one list query, and it is encounter-joined:

`data/local/dao/PatientDao.kt:45-52`:

```kotlin
@Query(
    "SELECT p.* FROM patients p " +
        "INNER JOIN encounters e ON e.patientId = p.id " +
        "WHERE e.startedAt >= :startMillis AND e.startedAt < :endMillis " +
        "GROUP BY p.id " +
        "ORDER BY MAX(e.startedAt) DESC",
)
fun observePatientsWithEncounterBetween(startMillis: Long, endMillis: Long): Flow<List<PatientEntity>>
```

**`INNER JOIN encounters` is the filter.** A patient with zero encounter rows produces zero result rows in both surfaces, on any window.

Its KDoc says so explicitly (`PatientDao.kt:37-44`):

> Patients with at least one encounter whose startedAt falls in [startMillis, endMillis).
> Deliberately no "all patients" query exists on this DAO — the only list surface is
> date-bounded, so no code path can pull the full patient table onto the device
> (data-minimization, see agent_docs/hardening.md).

And the repository interface repeats it (`domain/repository/PatientRepository.kt:16-23`):

> Today + the [days] before it (inclusive), by encounter start — a bounded window, not the
> full patient table. Same data-minimization constraint as [observeTodaysPatients]
> (agent_docs/hardening.md): this just widens the day-scoped query the DAO already exposes,
> it does not add an "all patients" query. Backs the Patients tab's "today's + recent" roster.

### Where an encounter is actually minted

`domain/usecase/StartCaseUseCase.kt:18` → `encounterRepository.startEncounter(...)` → `data/repository/EncounterRepositoryImpl.kt:29` `encounterDao.insert(...)`.

Its only caller is `presentation/compounder/CompounderViewModel.kt:222` — the **Compounder (vitals) screen**, reached only after Register → MedicalBackground → PatientSummary → *Start consultation* → Consent → Compounder.

Nav path (`AppNavHost.kt:226-244`): Register lands on `MedicalBackground`, then `PatientSummary`, whose `onStartConsultation` goes to `ConsentRoute`. The tester stopped at or before `PatientSummary` and backed out — so no encounter was ever minted, exactly as the code dictates.

---

## Q5 — Is there an encounter-centric-vs-patient-centric design assumption?

**Yes, and it is documented in four places. Home is a work queue, by requirement, not a patient directory.** No "durable finding: undocumented" applies here.

`docs/requirements/software-requirements.md:58-63` (verbatim):

> ## Roster / home (ROS)
> - **REQ-ROS-01** (DONE) Show today's patients (those with an encounter today) on Home;
>   tap to reopen.
> - **REQ-ROS-02** (DONE) Never expose an "all patients" query — list access is day-scoped only
>   (data minimisation; risk H-04).

`agent_docs/hardening.md:18-20` (verbatim):

> - **Local cache scope** ✓ — roster queries scoped to today (Home) / last 7 days (Patients
>   tab). No all-patients query exists anywhere on the DAO, by design (data minimisation,
>   REQ-ROS-02 / H-04). `docs/data-retention.md` is the canonical per-table posture record.

`PROGRESS.md:26-29` (verbatim):

> — data layer only (no UI yet). PatientDao.observePatientsWithEncounterBetween(start,end) is the
> ONLY list query on the DAO — deliberately no "all patients" query, so no code path can pull the
> full table (data-minimization). Scoped by Encounter.startedAt (visit day, not registration).

Note the parenthetical: **"visit day, not registration"** — the encounter-centric choice was made knowingly.

`PROGRESS.md:456-461` (verbatim) — the Patients tab decision:

> - [x] **Patients tab:** searchable/filterable list, but scoped to the last 7 days
>       (`PatientRepository.observeRecentPatients`), not the full patient table — the brief's
>       "today's + recent" wording collided with the existing hardening.md data-minimization
>       anti-pattern ("no all-patients query exists"); resolved by widening the existing day-scoped
>       DAO query's window rather than adding an unbounded one. No schema/DAO change needed — the
>       DAO already took arbitrary start/end bounds.

`docs/requirements/traceability-matrix.md:27-31`:

```
| REQ-ROS-01 | `HomeViewModel`, `GetTodaysPatientsUseCase` | — | Manual ✓ | ✓ HomeViewModelTest, TodaysPatientsDaoTest |
| REQ-ROS-02 | `PatientDao.observePatientsWithEncounterBetween` (no all-query) | H-04 | Manual ✓ | ✓ TodaysPatientsDaoTest (instrumented, permanent) |
| REQ-SEC-02 | day-scoped query (see REQ-ROS-02) | H-04 | Manual ✓ | ✓ TodaysPatientsDaoTest |
```

The behaviour is also under permanent instrumented test — `PROGRESS.md:2889` lists `TodaysPatientsDaoTest.kt` as asserting the query *"excludes out-of-window/**encounter-less patients**"*. **Any fix that makes encounter-less patients visible must reckon with that test and with REQ-ROS-02/H-04.**

Independently corroborating that this is known-and-unfixed rather than newly broken, `PROGRESS.md:590`:

> follow-up) not manually walked this pass — no patients existed in the test device's roster at

and `PROGRESS.md:773-774`:

> limit is the deliberate 7-day/today-only roster *list* window (REQ-ROS-02/H-04, privacy
> data-minimization, identical online or offline) — confirmed with the user to leave as-is.

---

## Diagnosis matrix

| | Verdict | Evidence |
|---|---|---|
| **A.** Patient row never created on device | ❌ Ruled out | `RegisterViewModel.onSubmit()` → `RegisterPatientUseCase:85` → `PatientRepositoryImpl:18` → `patientDao.insert()`, no conditional. Backend holds the row, which is only reachable via a device insert. |
| **B.** Created but not enqueued | ❌ Ruled out | Enqueue is implicit: `PatientEntity.syncState` defaults to `PENDING`; `PatientDao.getPendingForSync()` is called first in `RoomSyncOutboxRepository.collectPendingRecords()`. And it demonstrably shipped. |
| **C.** Pushed but rejected by backend | ❌ Ruled out | `sync_log id=1`: `table_name=patients, record_id=zwXWZzoLx7hr, status=applied, code=NULL`. Batch totals: `record_count=12, applied=12, rejected=0, conflicted=0, stale=0`. |
| **D.** On backend, but the Home/Patients filter excludes it — design-as-intended | ✅ **THIS** | Both surfaces route to the single `INNER JOIN encounters` query (`PatientDao.kt:45-52`). `SELECT count(*) FROM encounters` = **0**. Intent documented at REQ-ROS-01/02, `hardening.md:18-20`, `PROGRESS.md:26-29`, and locked by `TodaysPatientsDaoTest`. |
| **E.** Something else | ❌ | No unexplained residue. Every observed fact is accounted for. |

### **Pick: D.**

One-sentence evidence: the patient row (`zwXWZzoLx7hr`) synced and applied cleanly (`sync_log.status = applied`, batch `rejected=0`), but `encounters` is empty and the *only* patient-list query on the device is `SELECT p.* FROM patients p INNER JOIN encounters e ON e.patientId = p.id ...` — so an encounter-less patient is invisible to Home and the Patients tab by construction, exactly as REQ-ROS-01/02 and `hardening.md` specify.

**Not a bug. But the UX hole is real:** a worker who registers a patient and backs out before starting a consultation has created a permanent, un-findable record. On the device it is unreachable through any UI surface — no list shows it, and the only way back to `PatientSummary(patientId)` is a nav argument the worker no longer has. Re-registering the same person hits `ix_patients_abha_number UNIQUE` server-side, so the second attempt would come back rejected. This is a genuine dead-end that will be hit in the field.

---

## Proposed follow-up

**Product decision needed first — do not open a fix PR until Sandesh rules.** Three options, in ascending cost:

1. **Block the back-out (smallest change, keeps REQ-ROS-02 untouched).** On the Register → MedicalBackground → PatientSummary path, either require the worker to start a consultation or offer an explicit "discard this registration" that deletes the local row. Contains the dead-end without touching the data-minimization boundary or `TodaysPatientsDaoTest`. Does not help a patient already stranded.
2. **Mint an encounter at registration time.** Registering *is* a visit — the person is standing in front of the worker. This makes the patient appear on Home/Patients immediately with **zero change to the roster query** and zero change to REQ-ROS-02. Cost: semantics of `Encounter` widen from "consultation" to "visit"; `encounters` rows now exist without a `case_record`, so every downstream consumer of `encounters` needs a look. This is likely the right answer, but it is a clinical-data-model decision, not a UI one.
3. **Add a bounded by-registration-date roster query** (`patients.created_at` window, same 7-day cap, no join). Keeps the window bound and so does not literally violate "no all-patients query", but it *does* widen H-04's exposure surface — the device would now list people who were never seen. Requires a REQ-ROS-02 amendment, a traceability-matrix update, an H-04 risk re-assessment, and a change to `TodaysPatientsDaoTest`'s "excludes encounter-less patients" assertion. **Highest regulatory cost — do not take this route casually; REQ-ROS-02 is a DPDP data-minimisation control per `docs/regulatory-foundation.md:155`.**

Recommendation, if asked: **option 2, with option 1's discard affordance as a companion.** It fixes the visibility symptom at its actual cause (a registration is a visit and should mint an encounter) without reopening a regulatory control that took a documented argument to establish.

Whichever is chosen, the fix is a separate branch with its own STEP 1 memo.

---

## Notes for the record

- BUILD 2 / ABHA is **not** implicated. `abha_profiles` synced at rank 19 in the same batch, `status = applied`. Nothing in this investigation touched or blamed it.
- No code written, no file edited outside this memo, no branch created, no commit. `.env`, `docker-compose.yml`, and credentials files untouched — the Postgres user/db (`samd`/`samd`) was read from the running container's environment.
- PHI note: `patients.full_name`, `mobile_number`, `aadhaar_number`, `guardian_or_spouse_name`, `emergency_contact` are `bytea` (encrypted at rest) and were deliberately excluded from every query in this memo.
