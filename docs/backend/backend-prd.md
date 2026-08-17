# SaMD Backend: Product Requirements Document

> **Status:** Phases 1 through 3 implemented (2026-08-17). Phases 4 through 8 remain planning. See §8.
> **Owner:** solo developer / founder.
> **Controlled document** under the IEC 62304 documentation set. Lives in `docs/` (tracked), not
> `agent_docs/` (gitignored).
>
> Companion: `docs/backend/api-contract.md` (every endpoint, every schema, every error code).
> This document covers why the backend exists, how it is shaped, what it stores, how it is secured,
> and in what order it gets built.

---

## 1. Context

### 1.1 What exists today

The Android app (`app/`) is a hardened offline-first mockup that is closer to production than the
word "mockup" suggests. It has real SQLCipher encryption at rest, a real insert-only audit trail, a
real day-scoped data-minimisation posture, real biometric gating on sign-in, three Gradle
environment flavors, and a real HTTP clinical kernel. Room is at schema **v12** with 20 entities.

What it does not have is a server. Concretely:

| Capability | Current state | Consequence |
|---|---|---|
| Authentication | `MockAuthSession`, no credential check | H-06 open, REQ-SEC-03 PARTIAL. Anyone typing an existing worker's name and role inherits that worker's audit identity. |
| Sync | `MockSyncStatus`, no transport | H-05 open, REQ-SYN-02 PARTIAL. Every clinical record ever captured exists on exactly one device and nowhere else. |
| Kernel access | Device calls the XGBoost FastAPI server directly over the LAN | The ML server is exposed to the client network. No server-side record of what was inferred, from what input, by which model version. |
| ABDM / ABHA | Fully mocked and on-device | M1 compliance is impossible. ABDM V3 requires a server that holds the `client_secret` and performs RSA encryption. |
| Audit tamper evidence | Insert-only, no hash chain | H-07 residual open. |

Everything above resolves to the same missing component.

### 1.2 Why now

Three forcing functions have arrived at once.

1. **Data loss is a live clinical risk, not a hypothetical.** A lost, stolen, wiped, or
   factory-reset field tablet destroys every patient record on it. `docs/sync-design.md` has
   specified the fix since 2026-07 and has been blocked on "no backend to sync to."
2. **ABDM M1 needs a server before it can start.** `ABHA planning/abha-integration-plan.md` is a
   complete plan whose first prerequisite does not exist.
3. **Real authentication is the gate on the regulatory story.** Audit accountability under IEC
   62304 and DPDP depends on identity being verified, not asserted. `docs/regulatory-foundation.md`
   §3 lists this as gap 6.

### 1.3 What this backend is not

It is not a platform. It is not multi-tenant. It has no web frontend for clinical users. It serves
exactly one Android client, is maintained by exactly one engineer, and every line of it is a line
that engineer will debug alone at some point. That constraint drives every decision below more than
any architectural preference does.

---

## 2. Goals and non-goals

### 2.1 Goals

| ID | Goal | Closes |
|---|---|---|
| G-1 | Real per-worker authentication with server-verified credentials and role-based authorization | REQ-SEC-03, H-06 |
| G-2 | Durable off-device persistence of every clinical record, reached by an idempotent batch push that never blocks the field worker | REQ-SYN-02, H-05 |
| G-3 | All kernel inference routed through the backend, with an audit record of every call: input hash, output hash, model version, timing | REQ-HAN-05, H-02, H-09 |
| G-4 | A tamper-evident, append-only, hash-chained server audit log covering device-origin and server-origin events in one ordered chain | REQ-AUD-01, REQ-AUD-02, H-07 |
| G-5 | A single stable internal API contract that survives ABDM protocol changes and kernel refactors without an Android release | (architecture) |
| G-6 | Preserve every existing on-device safety property across the new network boundary, rather than assuming it carries over | H-03, H-04, H-10, REQ-ROS-02, REQ-AIL-03 |
| G-7 | Basic read-only visibility into backend state for the founder: sessions, sync batches, audit log, kernel call history | (operations) |

### 2.2 Non-goals

Stated so they are not built by accident, and so that a future session that wants one of them has
to reopen this document deliberately.

- **No Django, Flask, Celery, Kubernetes, GraphQL, or message broker.**
- **No Redis in v1.** Caching is on-device by design (offline-first). The two plausible Redis
  needs, a JWT blacklist and ABDM rate limiting, are both solved without it (§5.6, §5.7). Redis
  gets added when something is measured, not when something is imagined.
- **No admin panel with user management.** Account provisioning is `psql` plus a seed script.
- **No model training, retraining, or feedback reimport endpoint.** `DiagnosisFeedback` rows sync
  and are stored; nothing consumes them (REQ-RFN-01).
- **No DICOM, PACS, or imaging pipeline.** This is not radiology.
- **No WebSockets.** Sync is batch, not real-time.
- **No multi-tenancy.** One PHC network, `facility_id` scoping only.
- **No public API, no partner API, no API keys.** One Android client.
- **No binary attachment upload in v1.** Attachment rows sync as metadata.
- **No microservices.** One process, one image, one container.
- **No server-side rendering of anything clinical.**

### 2.3 Success criteria

The backend is done for its purpose when all of these hold:

1. A field worker signs in against a real credential and every audit row carries a
   server-verified `actor_id`.
2. A tablet can be destroyed after a day of work and no clinical record is lost.
3. `KERNEL_BASE_URL` has been deleted from all three Gradle flavors and the app builds.
4. `GET /api/v1/audit/verify` returns `verified: true` over the full chain, and its failure mode has
   been tested by deliberately corrupting a row in a staging database.
5. An ABHA registration completes end to end against the ABDM sandbox.

---

## 3. Architecture

### 3.1 Decisions and their rationale

**Framework: FastAPI.** Not Django, not Flask, not Ktor, not Spring Boot.

Qure.ai runs Django with PostgreSQL, verified from their public job postings. That is the right
call for their context: hundreds of engineers, deployments in 14 or more countries, enterprise
admin dashboards, a multi-tenant platform. Almost none of those conditions hold here. Django's
value is concentrated in the ORM, the admin, and the batteries; we would use the ORM, actively not
want the admin, and pay for the rest in cognitive weight.

What actually decides it for a regulated SaMD is the Pydantic model. In FastAPI the schema *is* the
specification: the request model, the OpenAPI document, the validation, and the API contract are
one artifact rather than four that drift apart. Under IEC 62304, "the specification and the
implementation cannot disagree" is worth more than any feature comparison. Add to that: async-first
matters for ABDM V3's chained HTTP calls, and ML inference eventually wants to live in the same
Python process as the code that calls it.

**Monolith, not microservices.** One process, one Docker image, one container. Internal module
boundaries are drawn so that `abdm-adapter` *could* be extracted later, and that is exactly as far
as that idea goes.

NIRAMAI, an Indian company with real CDSCO experience, started monolithic and moved to
microservices on GKE only after they had a team and traffic that justified it. A solo developer
running microservices has multiplied their operational burden and divided nothing.

**The backend is the only API surface.** No client-to-kernel calls. No client-to-ABDM calls. The
XGBoost kernel becomes an internal implementation detail behind a proxy. This is the same shape
Aidoc uses (17 or more FDA clearances, 2000 or more hospitals): a Python gateway service between
the client and the inference pipeline, with their aiOS platform orchestrating multiple narrow
algorithms per study. Today our proxy forwards to one kernel. The same seam is where an ensemble
orchestrator goes if we ever have more than one model, at zero cost to the client.

**Monorepo.** `backend/` sits beside `app/` in the SaMD-App repository. One repository means one
IEC 62304 traceability set: a requirement, its Android implementation, its backend implementation,
and its tests are all reachable from one commit hash. The CI pipelines are separate; the history is
not.

### 3.2 System shape

```
Android app (Kotlin, offline-first, SQLCipher)
        |
        |  HTTPS, JWT Bearer, snake_case JSON
        v
+--------------------------------------------------+
|  SaMD Backend (one FastAPI process)              |
|                                                  |
|  middleware:  request-id -> auth -> audit        |
|                                                  |
|  api/v1/  health  auth  patients  encounters     |
|           kernel  sync   audit    abha           |
|                                                  |
|  services/  auth  sync  audit_chain  kernel      |
|                                                  |
|  adapters/  kernel_client (httpx)                |
|             abdm/ (the abdm-adapter package)     |
|                                                  |
|  db/  SQLAlchemy 2.0 async + Alembic             |
+--------------------------------------------------+
        |                          |
        v                          v
  PostgreSQL 16            XGBoost kernel (FastAPI, existing)
                           /v1/assess, /api/v1/evaluate
                                   |
                                   v
                           ABDM V3 (abhasbx.abdm.gov.in)
                           reached only from adapters/abdm
```

Request flow through the middleware stack, in order, for every request:

1. **Request-ID middleware.** Adopts a valid inbound UUID4 `X-Request-ID` or mints one. Binds it to
   the structlog context so every log line in the request carries it. Echoes it on the response.
2. **Auth middleware.** Validates the Bearer token, resolves `worker_id`, `role`, `facility_id`,
   `device_id` onto the request state. Skipped for the public routes.
3. **Audit middleware.** After the handler returns, writes an audit event for every mutating
   request and for the reads that are themselves sensitive (patient reads, audit reads).

### 3.3 Directory layout

```
backend/
  core/
    app/
      main.py                 FastAPI app, middleware, router mounts, lifespan
      config.py               pydantic-settings, every value from env
      deps.py                 shared dependencies (db session, current worker, role guards)
      errors.py               SamdError base, the code registry, RFC 9457 handlers
      api/v1/
        health.py  auth.py  patients.py  encounters.py
        kernel.py  sync.py  audit.py
      schemas/                Pydantic v2 request and response models, extra="forbid"
      models/                 SQLAlchemy 2.0 declarative models
      services/
        auth.py               bcrypt, JWT mint and verify, refresh rotation, reuse detection
        sync.py               batch ordering, per-record apply, ack building
        audit.py              hash chain append and verify
        kernel.py             proxy, PHI guard, call logging
      db/
        session.py            async engine and sessionmaker
        migrations/           Alembic
      logging.py              structlog JSON config, PHI redaction filter
    tests/
    pyproject.toml
    Dockerfile
    .env.example
  abdm-adapter/               Phase 5; a package, mounted as a router by core
    app/
      api/v1/registration.py  verification.py
      adapter/  client.py  session.py  crypto.py  request_context.py
                abha_identity.py  abha_address.py  errors.py
      domain/   transaction.py  models.py
    tests/
  docker-compose.yml
  README.md
```

The `abdm-adapter` layout is taken from `ABHA planning/abha-integration-plan.md` unchanged, with one
deviation recorded in §4.3: its transaction store is the shared PostgreSQL database, not a separate
SQLite file.

### 3.4 Tech stack (locked)

| Concern | Choice | Note |
|---|---|---|
| Language | Python 3.12 | |
| Web framework | FastAPI, latest stable | |
| ORM | SQLAlchemy 2.0, async, `asyncpg` | |
| Migrations | Alembic | Every schema change is a migration. No autocreate in any environment. |
| Schemas | Pydantic v2 | `extra="forbid"` on every request model, without exception |
| JWT | `python-jose[cryptography]` | HS256 in v1 |
| Password hashing | `bcrypt`, cost 12 | |
| HTTP client | `httpx`, async | Kernel proxy and ABDM |
| Logging | `structlog`, JSON to stdout | |
| Database | PostgreSQL 16 | |
| Container | Docker, `python:3.12-slim`, non-root user | |
| Tests | `pytest` + `httpx.AsyncClient` | |
| Config | `pydantic-settings` | Every value from the environment |

Versions are pinned exactly in `pyproject.toml`. No ranges, no `^`, no `~`, mirroring the
`libs.versions.toml` discipline on the Android side. The lockfile is committed and is SBOM input.

---

## 4. Data model

### 4.1 Principle

The PostgreSQL schema mirrors the Room schema **closely enough that a sync push is a dumb row
operation**, and diverges only where the server can enforce something the device cannot. Every
divergence below is deliberate and listed.

### 4.2 Room entity to backend table map

All 20 Room entities in `app/src/main/java/com/example/samdapp/data/local/entity/` map one to one.
Table names are identical. Column names are the `snake_case` form of the Kotlin property.

| Room entity | Backend table | Sync direction | Notes and divergences |
|---|---|---|---|
| `PatientEntity` | `patients` | push | Identity columns encrypted at rest (§5.4). Adds `facility_id`, `server_version`, `synced_at`. ABHA columns (`abha_address`, `abha_status`, `kyc_status`, `verification_source`, `verified_at`) exist server side from day one; on the device they still need a migration, and it is **not** `MIGRATION_12_13` (that version now belongs to the per-record sync-state columns below, done 2026-08-17). Whichever session adds them must claim `v13` to `v14` or later. |
| `EncounterEntity` | `encounters` | push | FK to `patients`. `follow_up_of_encounter_id` self-FK. |
| `ConsultationEntity` | `consultations` | push | FK to `patients`, `encounters`. |
| `AttachmentEntity` | `attachments` | push | `uri` stored as `local_uri`, plus `blob_status` (`NOT_UPLOADED` always in v1) and a nullable `object_key` for the future. Renamed deliberately: a `content://` URI means nothing off the device that wrote it, and calling the column `uri` would imply the server can fetch something it cannot. Binaries do not move in v1. |
| `ObservationEntity` | `observations` | push | Vitals. `synced_to_cloud_at` is stamped by the server on apply and returned, closing the dual-timestamp contract of REQ-TRS-06 honestly instead of the device guessing. |
| `AilmentEntity` | `ailments` | push | **`audio_local_uri` is not a column here and is rejected on the wire** (`SAMD-SYNC-6006`, REQ-AIL-03). `visibility` including `PRIVATE` does sync (REQ-AIL-04). Soft-delete via `deleted_at`. |
| `CaseRecordEntity` | `case_records` | push, pull | Status transitions enforced server side. `assigned_doctor_id` carries **no** foreign key; see the note below §4.7. |
| `KernelReportEntity` | `kernel_reports` | push | Includes `inference_source` (`REAL_INFERENCE` / `MOCK_FALLBACK`, REQ-HAN-08), which makes the mock-fallback rate a queryable server-side metric for the first time. |
| `EvaluateReportEntity` | `evaluate_reports` | push | `payload_json` stored as `jsonb`, not `text`, so the founder can query inside it without an application. |
| `DiagnosisFeedbackEntity` | `diagnosis_feedback` | push | Stored, not consumed (REQ-RFN-01). |
| `PrescriptionEntity` | `prescriptions` | push | |
| `MedicationLineEntity` | `medication_lines` | push | FK to `prescriptions`, ordered by `position`. |
| `MedicalHistoryItemEntity` | `medical_history_items` | push | |
| `AllergyEntity` | `allergies` | push | |
| `FamilyHistoryEntryEntity` | `family_history_entries` | push | |
| `SocialHistoryEntity` | `social_histories` | push | PK is `patient_id`. |
| `MedicationEntryEntity` | `medication_entries` | push | |
| `ReferralEntity` | `referrals` | push | `sending_phc_id` is reconciled against the token's `facility_id`; a mismatch is rejected. |
| `AbhaProfileEntity` | `abha_profiles` | push, and server-written in Phase 5 | PK `abha_number` (14 digits, never dashed). |
| `DoctorEntity` | `doctors` | server-owned | The device seeds 9 mock doctors locally. The server is authoritative once Phase 3 pull exists. No push. |
| `AuditLogEntity` | `audit_events` | push | Renamed, and extended. See §4.5. |

### 4.3 Backend-only tables

| Table | Purpose |
|---|---|
| `facilities` | `facility_id`, name, district, state, `phc_code`. Scoping root for every query. Seeded, not self-service. |
| `user_accounts` | `worker_id` (16 hex, PK), `display_name`, `role`, `facility_id`, `pin_hash` (bcrypt), `is_active`, `failed_attempts`, `locked_until`, `created_at`, `updated_at`. Replaces `MockAuthSession`. |
| `devices` | `device_id`, `worker_id`, `first_seen_at`, `last_seen_at`, `app_version`, `environment`. Binds a token to an install. |
| `refresh_tokens` | `jti` (PK), `worker_id`, `device_id`, `issued_at`, `expires_at`, `revoked_at`, `replaced_by`. Rotation plus reuse detection without Redis. |
| `sync_batches` | `batch_id` (PK), `device_id`, `worker_id`, `received_at`, `record_count`, `applied`, `stale`, `conflicted`, `rejected`, `response_json`. The 24 h idempotency store and the operational view of sync health. |
| `sync_log` | one row per record in a batch: `batch_id`, `table_name`, `record_id`, `status`, `code`, `server_version`, `applied_at`. What actually happened to each row. |
| `kernel_call_log` | `request_id`, `worker_id`, `facility_id`, `case_token`, `endpoint`, `input_sha256`, `output_sha256`, `model_version`, `http_status`, `duration_ms`, `created_at`. Hashes only, never payloads (§5.5). |
| `abha_transactions` | Phase 5. `local_transaction_id` (PK), `external_txn_id`, `state`, `created_at`, `expires_at`, `correlation_id`, `last_error`, `retry_state`, per `abha-integration-plan.md`. |

**Divergence from the ABHA plan, recorded here rather than silently.** That plan specifies
`store/transactions.py` backed by "sqlite for dev; pg-ready." Since PostgreSQL exists from Phase 1
of this backend, the ABHA transaction store uses the same database and the same Alembic history.
Running a second database engine in one process to save one connection string is a cost, not a
saving.

**Second divergence: the mounting decision.** The ABHA plan and the Gradle flavors imply a separate
service on port 8081 (`ABHA_BACKEND_BASE_URL`). This document mounts the adapter as a router in the
same process under `/api/v1/abha/`, which is what the locked "one process, one container" decision
requires. The `backend/abdm-adapter/` directory still exists as a separate package with its own
tests and its own ABDM-facing code. Consequence: `ABHA_BACKEND_BASE_URL` collapses into
`BACKEND_BASE_URL` and is removed from all three flavors in Phase 6, alongside `KERNEL_BASE_URL`.

### 4.4 Roles and identity

`user_accounts.role` accepts `ASHA_WORKER`, `NURSE`, `COMPOUNDER`, `DOCTOR`.

**Open item.** `DOCTOR` does not exist in the Android `UserRole` enum today
(`domain/auth/AuthSession.kt` declares exactly three constants: `ASHA_WORKER`, `NURSE`,
`COMPOUNDER`). The in-app physician review on `PatientSummaryScreen` currently runs under whatever
field role is signed in, which means the AGREE/MODIFY/REJECT decision that carries the entire
Class B or C risk-control argument (H-02) is not attributable to a doctor in the audit trail.
Adding `DOCTOR` to the enum is an Android change, is a prerequisite for Phase 6 wiring, and is
worth doing regardless of the backend because the traceability gap exists today.

`worker_id` is 16 lowercase hex characters, computed identically to `MockAuthSession.stableUserId`:
`sha256(name.trim().lowercase() + "|" + role.name)` hex encoded, truncated to 16 characters.
Accounts are provisioned with the value the device already derives, so the audit trail's actor
identifier is continuous across the cutover from mock auth to real auth. Historical device rows and
new server rows for the same person share one identifier, which is the whole point of the stable
derivation that already shipped.

There is no `facility_id` concept anywhere on the device today. The nearest thing is
`ReferralEntity.sending_phc_id`, which is a free string. Facility comes from the account, is
carried in the token, is stamped by the server on every row it writes, and is not client-supplied.

### 4.5 Audit table

Room's `AuditLogEntity` has `id`, `timestamp`, `user_id`, `patient_id`, `case_record_id`, `action`,
`payload`. The server table `audit_events` keeps all of those and adds:

| Column | Why |
|---|---|
| `sequence` (bigserial) | Chain ordering that does not depend on any clock |
| `occurred_at` / `recorded_at` | When it happened (device clock for device-origin rows) versus when the server wrote it. Without both, an offline capture synced two days later is indistinguishable from a backdated record. |
| `origin` (`DEVICE` / `SERVER`) | |
| `device_id`, `facility_id`, `actor_role`, `request_id` | Correlation |
| `payload_sha256` | Hashed into the chain so verification never loads clinical blobs |
| `previous_hash`, `entry_hash` | The chain itself (§5.5) |

One chain per facility, covering both origins, so "what happened to this patient" is one ordered
query rather than a merge of two logs.

The append-only property is enforced in three independent places, because a convention that lives
in one place is a convention that gets broken:

1. No `UPDATE` or `DELETE` method exists on the audit service.
2. No route exposes mutation of an audit record.
3. A database trigger raises on any `UPDATE` or `DELETE` against `audit_events`. A trigger rather
   than only a `GRANT`, because a `GRANT` does not restrain the table owner and in dev the
   application connects as the owner. Deployment additionally runs the application under a role
   holding only `SELECT, INSERT` on that table; see `backend/README.md`. Migrations run as the
   owner, deliberately and visibly.

### 4.6 Identifier strategy

Row identifiers are **client-generated**, always. The device mints `Patient.id` as a 12-character
alphanumeric UID from `SecureRandom` over a 62-character alphabet (REQ-REG-03), and mints UUIDs for
other rows, because registration must work with no network. The server never reassigns an id.

This is what makes sync push idempotent for free: a retried create carries the same primary key, so
the second attempt is an upsert rather than a duplicate. The alternative, server-assigned ids, would
require a client-side correlation table for a system whose defining constraint is that the client is
frequently offline.

`server_version` is a monotonic integer per row, incremented on every applied write, and is the
optimistic-concurrency token.

### 4.7 Foreign keys, a deliberate divergence

The Room schema has **no foreign key constraints anywhere**, by choice (see `EncounterEntity`'s
KDoc). The backend schema **does** have them.

The device's reason for omitting them is sound: arbitrary insert order during offline capture and no
second writer to disagree with. The server has neither excuse. It is the durable copy of a clinical
record, referential integrity is the last line of defence against a wrong-patient linkage (H-03),
and PostgreSQL enforces it for free. The cost is that sync push must apply records in dependency
order, which is why §6.1 of the API contract fixes a table rank rather than trusting the client's
array order.

**One deliberate exception, decided in Phase 2.** `case_records.assigned_doctor_id` and
`prescriptions.doctor_id` are indexed but unconstrained. `doctors` is reference data with a
lifecycle independent of any clinical record, and a device can legitimately hold a doctor row the
server has not been seeded with. An FK there converts a reference-data seeding gap into refused
clinical writes, which is the wrong failure mode: losing a patient's record because a doctor list
is stale is worse than an orphaned doctor reference. This is the only unconstrained relationship
in the schema and it is documented in place, in `app/models/clinical.py`.

---

## 5. Security model

### 5.1 Transport

HTTPS only in staging and production. TLS terminates at the load balancer or reverse proxy;
the container speaks plain HTTP on the internal network only.

Cleartext is permitted in dev only, and only because the dev kernel already runs over plain HTTP on
the LAN and the Android side has already scoped that exception correctly:
`android:usesCleartextTraffic="true"` lives in `src/dev/AndroidManifest.xml` and nowhere else
(REQ-SEC-05). The backend mirrors that scoping. Staging and production set
`REQUIRE_HTTPS=true`, which makes the app reject a request arriving without
`X-Forwarded-Proto: https`, so a misconfigured proxy fails loudly rather than serving PHI in the
clear.

PostgreSQL connections use TLS in staging and production (`sslmode=require`).

### 5.2 Authentication

JWT, HS256, signing key from `JWT_SECRET_KEY` in the environment. Access token 1 hour, refresh
token 7 days.

Access token claims:

```json
{
  "sub": "a3f5c9d21b8e4470",
  "role": "ASHA_WORKER",
  "facility_id": "PHC-RJ-0142",
  "device_id": "9f8b1c2d3e4a5b6c",
  "jti": "b1c2d3e4-...",
  "typ": "access",
  "iat": 1786953600,
  "exp": 1786957200,
  "iss": "samd-backend",
  "aud": "samd-android"
}
```

`typ` is checked on every verification. An access token presented to the refresh endpoint, or the
reverse, is rejected. Without that check the two token types are interchangeable and the 1 hour
access lifetime becomes decorative.

Refresh flow: rotation on every use, with reuse detection. Presenting an already-revoked refresh
token revokes the whole chain for that `(worker_id, device_id)` pair and forces a full re-login.
This is what makes a stolen refresh token survivable without a Redis blacklist.

**The `pin` field is not optional.** A login that accepts only `worker_id` and `device_id` would
carry the H-06 hole across to the server and dress it in server-side authority. See
`api-contract.md` §2.1.

### 5.3 Authorization

Role-based, enforced per route by a FastAPI dependency, never by the client. The full matrix is in
`api-contract.md` §9.3, including the open question about which roles may submit to the kernel.

Facility scoping applies on top of the role matrix, on every route, for every role: the token's
`facility_id` bounds every read and every write. It is never accepted from a request body.

### 5.4 PHI at rest

Three layers, listed in the order they actually stop something:

1. **Volume and instance encryption.** RDS encryption in staging and production, encrypted volume
   in dev.
2. **Column-level encryption** on the identity subset, via `pgcrypto`
   (`pgp_sym_encrypt` / `pgp_sym_decrypt`), key from `PHI_ENCRYPTION_KEY` in the environment.
   Implemented in Phase 2 as a SQLAlchemy `TypeDecorator` (`app/db/types.py`), so encryption and
   decryption are expressions in the query and no call site outside that module handles
   ciphertext. Encrypted on `patients`: `full_name`, `guardian_or_spouse_name`, `mobile_number`,
   `aadhaar_number`, `emergency_contact`. Encrypted on `abha_profiles`: `name`, `mobile_number`,
   `email_address`.
3. **Blind indexes** for the columns that must remain searchable, since an encrypted column
   cannot be indexed usefully: `name_blind_idx`, `aadhaar_blind_idx`, `mobile_blind_idx`, each
   `HMAC-SHA256(normalised value, BLIND_INDEX_KEY)` truncated to 32 hex characters. HMAC rather
   than a bare hash: a plain SHA-256 of a 10-digit mobile number is brute forceable in seconds.
   Equality lookup works; range and prefix search do not, and are not needed.

**Two column decisions that changed during implementation, recorded rather than quietly
adjusted.** `abha_number` is **not** encrypted: it is a pseudonymous government identifier rather
than a direct one, and it needs exact-match lookup plus a `UNIQUE` constraint, which ciphertext
cannot provide. That constraint is what enforces `SAMD-PAT-3004`, the wrong-patient guard
(hazard H-03), so encrypting the column would weaken a safety control to strengthen a privacy
one. And `village`, `block`, `pincode` stay plaintext alongside `district` and `state`: at PHC
granularity an address line is not identifying on its own, and encrypting it would make every
roster and aggregate query decrypt the table.

**Stated honestly:** with the key in the application environment, layer 2 protects against a stolen
database dump or a backup file, not against an attacker who already has the application host. The
real answer is AWS KMS with envelope encryption, and that lands with the AWS deployment session.
Column-level encryption without KMS is still worth having, and pretending it is more than it is
would be worse than not documenting it.

`district` and `state` are deliberately left in plaintext: they are needed for facility-level
aggregate queries and are not identifying at PHC granularity.

### 5.5 The kernel boundary (H-10, REQ-HAN-06)

The Android app guarantees structurally that no `Patient` object can reach the kernel:
`KernelPayload` has no `Patient`-typed field, and `SendToKernelUseCase` accepts only
`VitalsReading` + `Consultation` + an opaque case token. On-device verification confirmed a
constructed payload for an identity-laden test patient contained none of it.

The backend is a **more dangerous** place for this than the device, because the backend holds the
full patient row in the same process, and a careless ORM relationship traversal could put a name
into an outbound payload. The guarantee is therefore rebuilt, not inherited:

- The Pydantic request models for `/api/v1/assess` and `/api/v1/evaluate` contain only the
  pseudonymized clinical fields, with `extra="forbid"`.
- The outbound kernel client accepts only those models. It has no signature that takes a patient
  row, an ORM entity, or a free dictionary.
- A test asserts the exact field set of both outbound models and fails on any addition. That test is
  the executable form of REQ-HAN-06 and is the reason this control does not decay.

What `kernel_call_log` records per call: `input_sha256`, `output_sha256`, `model_version`,
`endpoint`, `http_status`, `duration_ms`, `case_token`, `worker_id`, `facility_id`, `request_id`.
What it does not record: the request body, the response body, any vital value, any complaint text.
Hashes prove which input produced which output for IEC 62304 traceability without putting the
clinical record in a log table.

**Where the clinical content actually lives, corrected in the Phase 3 fix pass.** This section
previously said the content is durably stored in `kernel_reports` and `evaluate_reports`. That is
no longer true of `kernel_reports` and was never a safe thing for the server to do:

- `kernel_assessments` (server-owned, migration `0004`) holds the **response body verbatim** in
  `jsonb`, one row per successful call, joined to `kernel_call_log` by `request_id` and sharing
  its `output_sha256`. It stores raw model output and nothing derived: no `predicted_condition`,
  no `confidence_score`, no `risk_category`, no `required_human_verification`. The request body is
  still not stored anywhere, only its hash.
- `kernel_reports` is **device-owned**. The proxy does not write it. It arrives through sync push
  (Phase 4), one row per case, and is the record of what the clinician was actually shown,
  including the device's own `device_id`, `software_version` and `inference_source`.
- `evaluate_reports` still takes a proxy write per `/evaluate` call, unchanged. It is a
  device-mirrored table with the same ownership question, now recorded as an open item under D-10.
- Derived clinical values are computed at **read time** by `app/domain/kernel_derivation.py`,
  which carries a `derivation_rule_version`. Nothing it returns is persisted. Bumping that
  constant is mandatory for any threshold or rule change, because the 0.90 human-verification
  threshold is a risk control and the ACP has to distinguish a rule change from a model change.

The rule behind all of it: a column that sits next to `model_version` must contain something the
model said. Backend arithmetic stored there is attributed to a model version that did not produce
it, and no docstring in the source tree corrects a row in the database.

The risk file's open item on this row ("consider a separate opaque token, not the case PK") is
closed in Phase 3 by substituting `HMAC-SHA256(case_id, CASE_TOKEN_KEY)` truncated to 16 hex
characters on the outbound call and restoring the original on the way back. No contract change is
needed when it lands, which is why it can wait.

### 5.6 Audit and tamper evidence (H-07)

Hash chain, exact rule in `api-contract.md` §7. `SHA-256` over `previous_hash`, the identifying
fields, and `SHA256(payload)`, joined by `|`. Genesis entry uses 64 zeros.

`GET /api/v1/audit/verify` recomputes a range and reports `verified` plus
`first_broken_sequence`. A broken chain is a `200` with `verified: false`, not a `500`, so a
monitor cannot mistake tampering for a transient fault.

**Residual risk, stated:** the chain is tamper-*evident*, not tamper-*proof*. An attacker with write
access to the database and the application code can recompute the whole chain. Making that harder
means anchoring the head hash somewhere the application cannot reach, for example a daily head-hash
write to an append-only external store. That is deferred; the residual is recorded here and belongs
in the H-07 row of `docs/quality/risk-management-file.md` when this ships.

**Second residual, stated:** access tokens cannot be revoked before expiry, because there is no
blacklist and no Redis. The mitigation is the 1 hour lifetime plus refresh-chain revocation on
logout. If a measured need for immediate revocation arrives, that is the moment Redis earns its
place, and not before.

### 5.7 Rate limiting

No general-purpose rate limiter. This is not a public API.

Two targeted limits, both implemented as columns rather than infrastructure:

- **Login:** 5 failed attempts per `worker_id` per 15 minutes, then a 15 minute lockout, tracked in
  `user_accounts.failed_attempts` and `locked_until`. Returns `SAMD-SYS-9003`.
- **ABDM OTP requests (Phase 5):** limited per session in `abha_transactions`, because ABDM
  enforces its own limits upstream and burning them costs real sandbox access.

### 5.8 Secrets

Every secret comes from an environment variable. `.env.example` documents each one with a
description and a dummy value; `.env` is gitignored. No secret is ever committed, and no secret
appears in a log line, an audit payload, or an error `detail`.

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | PostgreSQL connection string, includes the password |
| `JWT_SECRET_KEY` | HS256 signing key, 32 bytes minimum |
| `PHI_ENCRYPTION_KEY` | pgcrypto symmetric key |
| `BLIND_INDEX_KEY` | HMAC key for the searchable hashes |
| `CASE_TOKEN_KEY` | HMAC key for the kernel pseudonym (Phase 3) |
| `KERNEL_BASE_URL` | Internal address of the XGBoost kernel |
| `ABDM_CLIENT_ID`, `ABDM_CLIENT_SECRET` | Phase 5 |
| `ABDM_MODE` | `stub` or `live` |
| `ABDM_HIP_ID`, `ABDM_CM_ID` | Phase 5 |
| `ENVIRONMENT` | `dev` / `staging` / `prod` |
| `REQUIRE_HTTPS` | `false` in dev, `true` elsewhere |

Startup validation refuses to boot if `ENVIRONMENT` is not `dev` and any of `JWT_SECRET_KEY`,
`PHI_ENCRYPTION_KEY`, or `BLIND_INDEX_KEY` is missing, short, or equal to its `.env.example` dummy
value. A production service that starts with a default signing key is worse than one that does not
start.

### 5.9 Logging

`structlog`, JSON to stdout, one line per event, `request_id` bound to every line in a request.

A redaction processor drops a fixed key list before any line is emitted: `pin`, `password`,
`pin_hash`, `access_token`, `refresh_token`, `authorization`, `aadhaar_number`, `abha_number`,
`otp`, `client_secret`, `full_name`, `mobile_number`. Redaction is a processor rather than a
convention at call sites, so a new call site cannot forget it.

Request bodies are never logged, at any log level, in any environment. The dev-only body logging on
the Android side is already gated behind `BuildConfig.ENABLE_NETWORK_LOGGING` for exactly this
reason; the backend does not offer the equivalent switch at all.

### 5.10 Container

`python:3.12-slim` base. Non-root user. No build toolchain in the runtime layer (multi-stage build).
Read-only root filesystem where the runtime permits. No `latest` tags anywhere. The image is
identical across dev, staging, and production; only environment variables differ.

---

## 6. Error taxonomy

### 6.1 Shape

RFC 9457 Problem Details, `application/problem+json`, plus the extension members `code`,
`request_id`, `timestamp`, and `errors`. Full shape and the complete code registry are in
`api-contract.md` §0.6 and §9.1.

Domain prefixes: `AUTH-1xxx`, `ABHA-2xxx`, `PAT-3xxx`, `ENC-4xxx`, `KERN-5xxx`, `SYNC-6xxx`,
`AUDIT-7xxx`, `SYS-9xxx`. Codes are permanent: never reused for a different meaning, never
renumbered. Android branches on the string, and old app builds stay in the field for years.

Three rules that make the taxonomy worth having:

1. **Every error response carries a `request_id`**, and that same `request_id` appears on the audit
   row and on every log line for the request. A worker reporting "it failed" plus a screenshot is
   enough to find the exact request.
2. **`detail` never contains PHI.** It describes the failure, not the data that caused it.
   A validation error says `"Required."`, never the submitted value.
3. **Upstream errors are translated, not leaked.** An ABDM failure becomes `SAMD-ABHA-2006` with the
   external code preserved inside `detail`. A raw upstream body never reaches the Android client.

### 6.2 Server-only audit actions

The device's `AuditAction` vocabulary (`domain/audit/AuditLogger.kt`) is the accepted set for
device-origin rows; an unrecognised action arriving through sync is rejected rather than silently
stored, so the vocabulary cannot rot. The server adds its own actions for events that have no
device equivalent:

`worker_login_succeeded`, `worker_login_failed`, `worker_logout`, `token_refreshed`,
`refresh_reuse_detected`, `patient_record_read`, `audit_log_read`, `kernel_call_forwarded`,
`kernel_call_failed`, `sync_batch_received`, `sync_record_rejected`, `abha_session_started`,
`abha_session_failed`, `abha_identity_linked`, `abha_identity_submitted`, `abha_otp_verified`,
`abha_enrolled`, `abha_mobile_verified`, `abha_profile_retrieved`.

The last five (2026-08-17, ABDM M1 adapter Phase B, D7) close a gap the intermediate state
machine transitions left in the chain: previously only session start, terminal failure, and
patient-linkage were audited, with nothing recording that identity was submitted, an OTP was
verified, enrollment completed, mobile verification completed, or a profile was retrieved. Added
to this list first, then to `app/models/enums.py`'s `AuditAction`, per this codebase's own rule
that a new server-only action is defined in the vocabulary before it is used, never invented ad
hoc at a call site.

As of 2026-08-17 the accepted device set is enforced from `app/domain/audit_actions_device.py`, a
checked-in mirror of the 30-entry `AuditAction` enum, not a hand-typed guess; `referral_status_changed`
is included and accepted even while it is still dormant on the device (PROGRESS.md, "Backend:
audit-vocabulary reconciliation + sync_log dedup hardening").

---

## 7. Deployment model

### 7.1 Environments

| Environment | Backend | Database | Kernel | ABDM |
|---|---|---|---|---|
| dev | docker compose on localhost, port 8080 | PostgreSQL 16 in Docker | XGBoost server on the LAN (existing) | `ABDM_MODE=stub` |
| staging | AWS Mumbai (ECS or EC2) | RDS PostgreSQL | same image, internal address | ABDM sandbox |
| prod | AWS Mumbai (ECS or EC2) | RDS PostgreSQL | same image, internal address | ABDM production |

AWS Mumbai for both non-dev environments, for DPDP data-localisation reasons already recorded in
`agent_docs/hardening.md`. The Docker image is byte-identical across all three. Only environment
variables change. A staging-specific image is a staging-specific bug.

### 7.2 Dev compose file

The exact shape for `backend/docker-compose.yml`:

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: samd
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-samd_dev_only}
      POSTGRES_DB: samd
    ports:
      - "5432:5432"
    volumes:
      - samd_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U samd -d samd"]
      interval: 5s
      timeout: 3s
      retries: 10

  api:
    build:
      context: ./core
    env_file:
      - .env
    environment:
      DATABASE_URL: postgresql+asyncpg://samd:${POSTGRES_PASSWORD:-samd_dev_only}@db:5432/samd
      ENVIRONMENT: dev
      REQUIRE_HTTPS: "false"
      ABDM_MODE: stub
    ports:
      - "8080:8080"
    depends_on:
      db:
        condition: service_healthy
    command: >
      sh -c "alembic upgrade head &&
             uvicorn app.main:app --host 0.0.0.0 --port 8080"

volumes:
  samd_pgdata:
```

Notes on choices that look arbitrary and are not:

- Port **8080** matches the dev flavor's `BACKEND_BASE_URL` (`http://10.16.4.182:8080/`), so no
  Gradle edit is needed to point a device at a locally running backend.
- The kernel is **not** a compose service. It is an existing separate process on the LAN and stays
  that way in dev; the backend reaches it through `KERNEL_BASE_URL` in `.env`.
- `alembic upgrade head` runs in the container command, not in an entrypoint script, so the
  migration output lands in the container logs where it can be read after a failure.
- No Redis service. There is no Redis.
- The dev password default is inline and obviously named `samd_dev_only`. Staging and production
  supply `POSTGRES_PASSWORD` from the secret store, and startup validation refuses the default
  outside dev.

### 7.3 CI

A new workflow, `.github/workflows/backend-ci.yml`, separate from the existing Android pipelines
(`android-ci.yml`, `android-release.yml`) and path-filtered on `backend/**` so an Android-only
commit does not run it and a backend-only commit does not run the three-flavor Android matrix.

Steps: `ruff check`, `ruff format --check`, `mypy`, `pytest` against a PostgreSQL 16 service
container, `docker build`, and a check that Alembic has no pending autogenerate diff (a schema
change without a migration fails the build).

The existing Android CI matrix, JDK 17, and release-signing workflow are untouched.

### 7.4 Observability

Structured JSON logs to stdout, collected by whatever the host provides (compose logs in dev,
CloudWatch later). `GET /health` for liveness. `kernel_call_log`, `sync_batches`, and `audit_events`
are the operational tables; the Phase 7 admin surface is a read-only view over exactly those three
plus `refresh_tokens`. No metrics stack, no tracing backend, no dashboards in v1. The founder's
question is "what happened to this patient's data yesterday", and that is a SQL query, not a
Grafana panel.

---

## 8. Phased delivery

| Phase | Deliverable | Depends on | Effort |
|---|---|---|---|
| 0 | This document plus `api-contract.md` | nothing | this session |
| 1 | **DONE 2026-08-16.** Scaffold: Docker, compose, config with startup validation, `/health`, request-ID, HTTPS-enforcement and audit middleware, RFC 9457 error envelope, structlog with PHI redaction, auth endpoints including forced PIN change, `facilities` / `user_accounts` / `devices` / `refresh_tokens` / `audit_events` / `abha_transactions`, the audit hash chain, account provisioning script, backend CI | Phase 0 review | 1 session (Opus) |
| 2 | **DONE 2026-08-17.** SQLAlchemy models for all 20 mirrored tables plus `sync_batches` / `sync_log` / `kernel_call_log`, migration 0002, pgcrypto column encryption with blind indexes, sync columns on every syncable table, patient CRUD with the ABHA collision guard and the day-scoped roster, encounter create and bundle fetch, case-record status state machine | Phase 1 | 1 session |
| 3 | **DONE 2026-08-17.** Kernel proxy: `/assess` and `/evaluate`, PHI-boundary guard (schema `extra="forbid"` plus an explicit denylist checked before every forward), HMAC case pseudonym (D-7, closed), a per-endpoint in-memory circuit breaker, `kernel_call_log` extended with `case_record_id`/`outcome`/`error_code`, upstream error translation, and persistence into `evaluate_reports` | Phase 2 | 1 session |
| 3-fix | **DONE 2026-08-17.** Correctness and traceability pass over Phase 3, run before Phase 4 because sync push inherits both defects. `risk_category` provenance audit (D-11); the proxy's `kernel_reports` write deleted, making that table device-owned (D-9, D-10); new server-owned `kernel_assessments` holding raw model output only (migration `0004`); read-time versioned derivation in `app/domain/kernel_derivation.py`; success-path records moved into one out-of-band transaction so a call cannot be logged without its assessment | Phase 3 | part of 1 session |
| 4 | **DONE 2026-08-17 (`POST /sync/push` only).** Batch ordering, per-record apply, ack contract, idempotency, `audit_events` hash chain via the one Phase 1 appender. `/audit/events` and `/audit/verify` were **not** built this session: the session brief scoped Phase 4 to the push endpoint alone and this row's own "and" is now known to overreach that brief; still open for a future session. | Phase 3 | 1 session |
| 5 | **DONE 2026-08-17 (`ABDM_MODE=stub` only; live activation is a separate, credential-gated step).** ABDM V3 M1 adapter, Create ABHA via Aadhaar OTP (the P0 vertical slice): `backend/abdm-adapter/`, sibling package mounted as a router into `backend/core`'s FastAPI process, per section 4.3. State machine, RSA-OAEP-SHA1 crypto, per-endpoint response classification (never HTTP status alone), and the audit trail all built and tested against real PostgreSQL and real `uvicorn`. Live activation checklist: correct `abdm_cert_url` against the real ABDM cert (D1, corrected this session from a stale prose default but never exercised against the real host), and re-verify the masked-mobile regex still matches ABDM's message wording (D4, free-text parsing, fragile by construction) before trusting it against live sandbox responses. | Phase 4 plus sandbox approval for live activation | 1 session for the stub adapter (done); live activation is separate |
| 6 | Android wiring: `RetrofitAuthService`, `BackendAuthSession`, auth interceptor and `Authenticator`, `RetrofitPatientSource`, real `SyncStatus` plus `WorkManager` worker, rebase the two kernel sources onto `BACKEND_BASE_URL`, delete `KERNEL_BASE_URL` and `ABHA_BACKEND_BASE_URL`, add `DOCTOR` to `UserRole`. Room `MIGRATION_12_13` for the sync columns is **DONE 2026-08-17**, ahead of this phase (schema only, nothing reads or writes `sync_state` yet) | Phase 3 for the kernel rebase, Phase 4 for sync | 1 session |
| 7 | Admin visibility: read-only page over sessions, sync batches, audit log, kernel calls | Phase 4 | 1 session |
| 8 | AWS Mumbai staging deployment, RDS, TLS, secret store | Phase 4 | separate session |

Phase 3 is deliberately ahead of Phase 4. The kernel proxy is the smallest slice that delivers
standalone value (server-side inference audit, kernel removed from the client network) and it
exercises the whole middleware stack against a real downstream before the harder sync work starts.

**Android prerequisite for Phase 6, DONE 2026-08-17.** The device previously had no generic
per-record sync state. Only `ailments` and `observations` carried `synced_to_cloud_at`, and only
`case_records` carried a sync-ish status (`PENDING_SYNC`). `MIGRATION_12_13` adds `sync_state`,
`server_version`, `sync_error_code`, `last_sync_attempt_at` and `local_modified_at` to all 20
syncable entities, `13.json` exported and committed, per `docs/sync-design.md` §2 item 1.

This corrects two false statements this note previously made. First, no `MIGRATION_12_13` existed
anywhere in the repo when this was written (`git log --all` on the Room migration file returned
nothing), so "already spoken for by the ABHA fields" was describing a migration that had never
been built, not one in flight. Second, the ABHA columns in this document's own §5.2 table
(`PatientEntity`/`patients` row) don't exist on the device at any version. `PatientEntity` carries
only `abhaNumber`, none of `abha_address`/`abha_status`/`kyc_status`/`verification_source`/
`verified_at`, so there was nothing at v12-to-v13 for this migration to conflict with. It shipped
as v12-to-v13 cleanly. Whichever session adds the ABHA columns next must claim v13-to-v14 or later,
not v12-to-v13, since that version is now taken.

---

## 9. Open decisions

Every one of these needs the founder's answer. None of them blocks Phase 1.

| ID | Decision | Recommendation |
|---|---|---|
| D-1 | Which roles may submit to the kernel? The brief says `COMPOUNDER` and `DOCTOR`; the shipped Android navigation lets any signed-in worker reach `SendingViewModel`, so enforcing that returns `403` to ASHA workers and nurses on day one. | Allow all four. The kernel output is never autonomous: it is gated by the liability acknowledgement and the mandatory doctor review. Role does not change that safety argument. |
| D-2 | Add `DOCTOR` to the Android `UserRole` enum? | Yes. The physician AGREE/MODIFY/REJECT decision carries the whole H-02 risk-control argument and is currently attributed to a field role in the audit trail. This is worth fixing independently of the backend. |
| D-3 | How are PINs distributed to workers on day one? | **Resolved and implemented.** Facility administrator provisions accounts via `python -m app.scripts.seed_accounts` and hands out an initial PIN in person. `user_accounts.must_change_pin` forces a change at first login: until then every endpoint except `/auth/me`, `/auth/change-pin`, and `/auth/logout` returns `SAMD-AUTH-1008`. No self-service reset endpoint in v1. |
| D-4 | Does `KERNEL_BASE_URL` get deleted in Phase 6, or kept as an emergency direct-call fallback? | Delete it. A second path to the kernel is a second path that bypasses the audit log, which defeats the point of G-3. |
| D-5 | Retention policy for `kernel_call_log` and `sync_log`. | 24 months, matching whatever `docs/data-retention.md` settles on for clinical rows. `audit_events` is never deleted. Needs the regulatory answer, not an engineering one. |
| D-6 | Does the ABHA transaction store share the main database? | Yes (§4.3). One engine, one Alembic history. |
| D-7 | HMAC pseudonym instead of the raw case PK on the kernel boundary. | **Resolved and implemented.** `app/adapters/kernel/pseudonym.py`: `HMAC-SHA256(case_record_id, CASE_TOKEN_KEY)` truncated to 16 hex characters, substituted on the way out and restored on the way back. Closes the open item in the H-10 row with no contract change, as planned. |
| D-8 | JWT algorithm: HS256 now, or RS256 from the start? | HS256. One process signs and one process verifies. RS256 earns its keep when a second verifier exists, and switching later is a config change plus a key rollover, not a redesign. |
| D-9 | Phase 3, new: the `/v1/assess` kernel response has no field that maps directly onto `kernel_reports` (`predicted_condition`, `confidence_score`, `reasoning_summary`, ...); on Android that mapping is `GenerateKernelReportUseCase`'s own logic (REQ-HAN-07/08), not part of the wire contract. Should the backend reproduce it? | **RESOLVED 2026-08-17 in the Phase 3 fix pass. No: not at write time, and not into a column that reads as model output.** The Phase 3 answer (reproduce the mapping into `kernel_reports`) was wrong for a reason worth stating, because it is the kind of wrong that passes review. Storing `predicted_condition`, `confidence_score`, `risk_category` and `required_human_verification` in the same row as `model_version` makes four backend-computed values indistinguishable from four values the named model emitted. Under IEC 62304 that is a traceability defect: an auditor reading the row cannot tell which of its fields the model is answerable for, and the ACP obligation under CDSCO/MD/GD/MDSW/01/2026 cannot separate a model change from a rule change at all. Labelling the substitution in a docstring does not fix it, because the docstring is not in the database. What replaced it: (a) `_persist_kernel_report` is deleted, and the proxy writes no `kernel_reports` row on any path; (b) new server-owned `kernel_assessments` (migration `0004`) stores the response body verbatim in `jsonb` plus provenance the server owns, and stores **zero** derived values, enforced by a test over the mapped columns; (c) derivation moved to read time in `app/domain/kernel_derivation.py`, a pure function carrying `derivation_rule_version = "HAN-07/08-v1"`, called by the report layer and the Phase 7 dashboard, persisted nowhere. The `device_id` and `software_version` substitutions are gone with the write that needed them: they were backend values dressed as device provenance, and the device now owns that row outright. |
| D-10 | Phase 3, new: does a repeated `/assess` or `/evaluate` call for the same `case_record_id` overwrite the prior `kernel_reports`/`evaluate_reports` row, matching the device's `@Insert(REPLACE)` upsert posture (`docs/data-retention.md`), or insert a new one? | **RESOLVED 2026-08-17 in the Phase 3 fix pass. The question is void for `kernel_reports`, because the server no longer writes that table.** The Phase 3 answer (insert every call) was not merely a divergence from the device's upsert-per-case posture, it was an unresolvable collision: two writers with two different cardinalities and no defined winner, which would have handed Phase 4 sync push a merge with no correct answer. `kernel_reports` is now **device-owned**: written only by sync push, one row per case, exactly as the device holds it, and it remains the record of what was actually displayed to the clinician. The traceability goal that motivated insert-every-call is served better by `kernel_assessments`, which is server-owned, insert-only, one row per successful call, joined to `kernel_call_log` by `request_id`, and holds raw model output rather than a reshaping of it. Open item, deliberately not changed in the same pass: `evaluate_reports` still takes a proxy write on every `/evaluate` call and is also a device-mirrored table, so it carries the identical collision. Its content is now duplicated in `kernel_assessments.raw_response`. Recommend removing the proxy write in Phase 4 for the same reasons as D-9 and D-10; flagged rather than folded in, since the fix pass was scoped to `kernel_reports`. |
| D-11 | Phase 3 fix pass, new: where does `risk_category` actually come from, and is a fabricated value reaching a clinician? | **Audited 2026-08-17 before any code was written. Finding: Case 1, Android derives it, with three qualifications. No safety escalation.** The audit read `KernelReportEntity`, Room schema `12.json`, `GenerateKernelReportUseCase`, `KernelAssessmentViewModel`/`AssessmentDisplay`, `ReportCanvasRenderer`, REQ-HAN-07/08, and the Phase 3 backend code. (1) On the real-inference path `GenerateKernelReportUseCase.tryRealApi` derives it by an explicit mapping, but from `triage_urgency` **and** `confidence_score` together, not from `triage_urgency` alone: `EMERGENCY` maps to `HIGH` outright, otherwise `>= 0.85` is `LOW`, `>= 0.65` is `MODERATE`, below that is `HIGH`. The backend's Phase 3 mapping (`ROUTINE`→`LOW`, `URGENT`→`MODERATE`, `EMERGENCY`→`HIGH`) was a **different rule**, so device and server disagreed for the same response: `URGENT` at 0.90 confidence was `LOW` on the device and `MODERATE` on the server. The Android mapping is now the only one, copied verbatim into `app/domain/kernel_derivation.py`. (2) On the MOCK_FALLBACK path it is a hardcoded per-scenario constant in the curated table, not model output. That path never reaches the proxy and is not reproduced server side. (3) `RiskCategory`'s own KDoc on the device describes the field as "how serious the predicted condition could be", but outside the EMERGENCY branch the implementation grades **model uncertainty**, so higher confidence maps to lower risk. Implementation and documentation contradict each other on the device. Reproduced unchanged rather than corrected, because a server rule that disagreed with the device would make the stored record differ from the displayed one. **Escalation check, the part that mattered: not surfaced to a clinician anywhere.** `riskCategory` is written by the use case, mapped through `KernelReportRepositoryImpl`, and persisted; it is read by no composable, no `toDisplay()`, no `ReportCanvasRenderer` block, and no gating or branching logic. Grep across `app/src/main` finds only entity, mapper, converter and migration references. Had it been rendered, this would have been a safety finding and the pass would have halted. **Recommendations, for the founder:** either correct the KDoc to say the field grades model uncertainty, or change the rule to grade clinical severity and bump `derivation_rule_version` (a risk-control change, not a refactor); and delete `RiskCategory` from the device entity entirely if nothing is ever going to render it, since an unread persisted field is a future accident. Two further deviations from the fix-pass brief are recorded in `app/domain/kernel_derivation.py`: top-differential selection follows `RetrofitKernelSource`'s `firstOrNull()` rather than max-by-probability, and an empty `differential_diagnosis` derives `None` rather than Android's substituted "Non-specific presentation" at 0.50 confidence. |

---

## 10. Risks to this plan

| Risk | Impact | Mitigation |
|---|---|---|
| Sync push is the hardest endpoint and is scheduled after three easier ones | Schedule slip lands on the highest-value feature | Phases 1 through 3 deliberately build every mechanism sync needs (envelope, audit chain, idempotency, ordering) so Phase 4 is assembly, not invention |
| ABDM sandbox approval is outside our control | Phase 5 blocks | `ABDM_MODE=stub` exercises the full adapter and state machine against recorded Postman responses. Going live is a config change, not a code change (`abha-integration-plan.md`) |
| Column-level encryption complicates every query touching identity | Slower development, subtle bugs | Encrypt the identity subset only, keep `district` and `state` plaintext, blind-index the two columns that need lookup, and write the encrypt and decrypt path once in the repository layer |
| The Android side needs a Room migration, new Retrofit services, an auth interceptor, and a WorkManager worker, all in one Phase 6 session | Phase 6 is the largest single-session scope in the plan | Split it if it runs long: kernel rebase (needs only Phase 3) is independent of the sync worker (needs Phase 4), and they can ship in separate sessions |
| Solo maintenance | Everything | Non-goals in §2.2 are the mitigation, and they only work if they are enforced when the next good idea arrives |
