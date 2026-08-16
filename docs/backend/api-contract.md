# SaMD Backend API Contract (v1)

> **Status:** Planning baseline, Phase 0. No implementation exists yet.
> **Controlled document.** This file is the single source of truth from which both the FastAPI
> routes (`backend/core/app/api/v1/`) and the Android Retrofit service interfaces
> (`app/src/main/java/com/example/samdapp/data/remote/api/`) are built. If the two disagree, this
> document wins and the divergent side is a defect.
>
> Companion: `docs/backend/backend-prd.md` (architecture, data model, security, deployment).
> Requirement IDs referenced here live in `docs/requirements/software-requirements.md`.
> Hazard IDs (H-nn) live in `docs/quality/risk-management-file.md`.

---

## 0. Conventions

### 0.1 Base URL and versioning

| Environment | Base URL | Source on device |
|---|---|---|
| dev | `http://<LAN-IP>:8080/` | `BuildConfig.BACKEND_BASE_URL` (dev flavor) |
| staging | `https://staging.samd.example.com/backend/` | `BuildConfig.BACKEND_BASE_URL` (staging flavor) |
| prod | `https://api.samd.example.com/backend/` | `BuildConfig.BACKEND_BASE_URL` (prod flavor) |

All application endpoints are prefixed `/api/v1/`. `GET /health` is deliberately unprefixed so a
load balancer or container healthcheck can reach it without knowing the API version. There is no
version header, no content negotiation, no `Accept-Version`. A breaking change mints `/api/v2/`.

### 0.2 Content type and encoding

Request and response bodies are `application/json; charset=utf-8`. A request with any other
content type on a body-bearing method is rejected with `415` / `SAMD-SYS-9002`.

### 0.3 Timestamps

Every timestamp in every request and response is **UTC, ISO 8601, with an explicit `Z`**, to
millisecond precision: `2026-08-16T10:00:00.000Z`. The backend rejects timestamps carrying a
non-UTC offset (`SAMD-SYS-9001`). Rationale: the device clock in the field is not trustworthy, and
mixing offsets into a hash-chained audit log makes ordering arguments unwinnable later.

Dates without a time component (for example `date_of_birth`) are `YYYY-MM-DD`.

### 0.4 Field naming

Wire format is `snake_case` throughout, matching the existing kernel DTOs
(`KernelAssessmentRequestDto`, `EvaluateRequestDto`). Two documented exceptions:

1. The `/api/v1/evaluate` response payload is **passed through from the kernel verbatim**,
   including its known mixed `snake_case` / `camelCase` fields (`recommendedDrug`, `dosageForms`,
   `requiresHumanReview` alongside `pediatric_referral_flag`). This is intentional. Normalising it
   would force an edit to the shipped `EvaluateReportDto` tree for zero clinical benefit. See
   §6.2.
2. Enum values are `SCREAMING_SNAKE_CASE` and match the Kotlin enum constant names exactly
   (`ASHA_WORKER`, `PENDING_SYNC`, `MOCK_FALLBACK`, `NON_MEASURABLE`), so no mapping table is
   needed on either side.

### 0.5 Success envelope

Every 2xx response body from every endpoint has this shape:

```json
{
  "success": true,
  "data": { },
  "meta": {
    "request_id": "3f2b7c48-9a1e-4c2d-8b55-0f1a2d3e4c5b",
    "timestamp": "2026-08-16T10:00:00.000Z",
    "api_version": "v1"
  }
}
```

- `data` is an object or an array, never a bare scalar, never `null` (an empty result is `{}` or `[]`).
- `request_id` is a UUID4 assigned by the request-ID middleware. If the caller supplies a valid
  UUID4 in the `X-Request-ID` header it is adopted, otherwise one is minted. It is echoed back in
  the `X-Request-ID` response header on every response including errors.
- `api_version` is the literal string `v1`.

`GET /health` is the single exception and returns a bare object (§1).

### 0.6 Error envelope (RFC 9457 aligned)

Every non-2xx response body has this shape. It is
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) plus four extension members
(`code`, `request_id`, `timestamp`, `errors`), which the RFC explicitly permits. Content type is
`application/problem+json`.

```json
{
  "type": "https://samd.example.com/errors/SAMD-AUTH-1001",
  "title": "Invalid credentials",
  "status": 401,
  "detail": "The worker id or PIN supplied is not valid.",
  "instance": "/api/v1/auth/login",
  "code": "SAMD-AUTH-1001",
  "request_id": "3f2b7c48-9a1e-4c2d-8b55-0f1a2d3e4c5b",
  "timestamp": "2026-08-16T10:00:00.000Z",
  "errors": [
    { "field": "pin", "message": "Required." }
  ]
}
```

- `code` is the machine-readable identifier. **Android branches on `code`, never on `title` or
  `detail`**, which are human-facing and may be reworded without a contract change.
- `errors` is present only for field-level validation failures (`SAMD-*-*003` style codes). It is
  absent otherwise, not `null`.
- `detail` never contains PHI. It describes the failure, not the data that caused it. A validation
  message says "Required." and never echoes the submitted value.

### 0.7 Authentication header

All endpoints except `GET /health`, `POST /api/v1/auth/login`, and `POST /api/v1/auth/refresh`
require:

```
Authorization: Bearer <access_token>
```

A missing header is `401` / `SAMD-AUTH-1003`. An expired token is `401` / `SAMD-AUTH-1002` (Android
must treat this specific code as "silently refresh and retry once"). A valid token whose role is
not permitted for the route is `403` / `SAMD-AUTH-1005`.

### 0.8 Idempotency

`POST` endpoints that create durable state (`/patients`, `/encounters`, `/case-records`,
`/sync/push`) are idempotent on a client-supplied key:

- Entity creates use the **client-generated primary key** already present in the payload. The
  device mints `Patient.id` (12-char alphanumeric, `RegisterPatientUseCase.generatePatientId`,
  REQ-REG-03) and all other row IDs offline, so a retried create with the same id and an identical
  payload returns `200` with the existing row rather than `409`. A same-id create with a
  *different* payload is `409` / `SAMD-PAT-3002`.
- `POST /api/v1/sync/push` uses the caller's `batch_id` as the idempotency key, retained 24 hours
  (§7.1).

### 0.9 Role vocabulary

| Role | Exists in Android `UserRole` today | Notes |
|---|---|---|
| `ASHA_WORKER` | Yes | |
| `NURSE` | Yes | |
| `COMPOUNDER` | Yes | |
| `DOCTOR` | **No** | See the open issue in `backend-prd.md` §4.4. The backend defines the role now; Android must add it to `domain/auth/AuthSession.kt` before any account can use it. |

---

## 1. Health

### GET /health

**Purpose:** Liveness and readiness for the container orchestrator and for a human debugging a
deployment. Also the endpoint the Android app can hit to confirm a base URL is reachable before
blaming the network.
**Auth:** None.
**Android consumer:** none in Phase 1. Optional later use by `ConnectivityController` to
distinguish "device has internet" from "our backend is up".

**Request:** no body.

**Success Response (200):** bare object, no envelope, because healthcheck tooling should not have
to parse one.

```json
{
  "status": "ok",
  "version": "0.1.0",
  "git_sha": "4272235",
  "environment": "dev",
  "uptime_seconds": 3814,
  "database": "ok",
  "kernel": "ok",
  "abdm_mode": "stub",
  "timestamp": "2026-08-16T10:00:00.000Z"
}
```

- `database`: `ok` if a `SELECT 1` succeeds within 2 seconds, otherwise `degraded`.
- `kernel`: `ok` / `degraded` / `unknown`. Result of the last kernel call, cached, not a live probe.
  A health endpoint that fans out to a downstream on every hit is a self-inflicted outage.
- `abdm_mode`: `stub` or `live`, from config. Present so nobody has to guess which mode a
  deployment is in.

**Error Responses:**
- 503: `SAMD-SYS-9004` (database unreachable). Body uses the standard error envelope. `status` in the
  200-shape body is never `"error"`; failure is expressed as a 503, not a 200 with a sad field.

---

## 2. Authentication

### 2.1 Design note, read before implementing

The Android app today has `MockAuthSession`, which performs **no credential check**. It derives
`userId` as the first 16 hex characters of `SHA-256("<name lowercased and trimmed>|<ROLE>")`.
Risk H-06 records the consequence: anyone typing an existing worker's name and role inherits that
worker's audit identity.

If the backend's login endpoint accepts only `worker_id` + `role` + `device_id`, it inherits that
hole and REQ-SEC-03 stays open forever, with the added harm that the audit trail now looks
authoritative because it is server-side. **The login contract therefore requires a real secret
(`pin`).** Accounts are provisioned out of band by the facility administrator (Phase 2, via
`psql` or a seed script; there is no self-registration endpoint and no self-service password
reset in the v1 surface).

Continuity is preserved deliberately: `user_accounts.worker_id` is provisioned to **the same
16-hex value the device already derives**, so the audit trail's `user_id` does not discontinue at
the cutover from `MockAuthSession` to the real backend. Historical device-side audit rows and new
server-side rows for the same person share one identifier.

### 2.2 POST /api/v1/auth/login

**Purpose:** Authenticate a PHC worker and issue a JWT pair.
**Auth:** None (this is the auth endpoint).
**Android consumer:** `RetrofitAuthService` (new) via `BackendAuthSession` (new implementation of
the existing `domain/auth/AuthSession` interface), replacing `MockAuthSession`. Closes REQ-SEC-03,
mitigates H-06.

**Request:**

```json
{
  "worker_id": "a3f5c9d21b8e4470",
  "pin": "482915",
  "device_id": "9f8b1c2d3e4a5b6c"
}
```

- `worker_id`: 16 lowercase hex characters. The device computes it exactly as
  `MockAuthSession.stableUserId` does today: `sha256(name.trim().lowercase() + "|" + role.name)`,
  hex encoded, first 16 characters.
- `pin`: 6 to 12 characters. Provisioned per worker. Stored server side as a bcrypt hash (cost 12),
  never in plaintext, never logged, never echoed.
- `device_id`: stable per install, generated once on first launch and persisted in the existing
  auth Preferences DataStore. Not `ANDROID_ID`, not the advertising ID, not any hardware
  identifier, so it carries no cross-app tracking capability and can be rotated by a reinstall.

Note that `role` is **not** in the request. The role is a property of the account on the server,
not a claim the client asserts. A client-asserted role is a privilege-escalation field.

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 3600,
    "worker": {
      "worker_id": "a3f5c9d21b8e4470",
      "display_name": "A. Devi",
      "role": "ASHA_WORKER",
      "facility_id": "PHC-RJ-0142",
      "facility_name": "PHC Bagru"
    }
  },
  "meta": {
    "request_id": "3f2b7c48-9a1e-4c2d-8b55-0f1a2d3e4c5b",
    "timestamp": "2026-08-16T10:00:00.000Z",
    "api_version": "v1"
  }
}
```

Access token lifetime 3600 s (1 h). Refresh token lifetime 604800 s (7 d). Claims are listed in
`backend-prd.md` §5.2.

**Error Responses:**
- 400: `SAMD-SYS-9001` (malformed JSON)
- 401: `SAMD-AUTH-1001` (invalid worker_id or PIN; the message is deliberately identical for both so
  the endpoint is not a worker-id oracle)
- 403: `SAMD-AUTH-1006` (account disabled)
- 422: `SAMD-PAT-3003` (field validation, with `errors[]`)
- 429: `SAMD-SYS-9003` (rate limit: 5 failed attempts per `worker_id` per 15 minutes, then locked
  out for 15 minutes; counter is a column on `user_accounts`, not Redis)
- 500: `SAMD-SYS-9005`
- 503: `SAMD-SYS-9004`

### 2.3 POST /api/v1/auth/refresh

**Purpose:** Exchange a refresh token for a new access token. Rotates the refresh token.
**Auth:** None in the header. The refresh token is the credential, in the body.
**Android consumer:** an OkHttp `Authenticator` in `di/NetworkModule.kt` (new), transparently to
every repository. Field workers must never see a login screen mid-encounter because an hour elapsed.

**Request:**

```json
{
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "device_id": "9f8b1c2d3e4a5b6c"
}
```

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 3600
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

Rotation is mandatory: the presented refresh token is marked revoked and `replaced_by` is set to
the new token's `jti`. **Reuse detection:** presenting an already-revoked refresh token revokes the
entire chain for that `(worker_id, device_id)` pair and returns `SAMD-AUTH-1004`. This is what
makes a stolen refresh token survivable without Redis.

**Error Responses:**
- 401: `SAMD-AUTH-1002` (refresh token expired)
- 401: `SAMD-AUTH-1003` (malformed or bad signature)
- 401: `SAMD-AUTH-1004` (revoked, or reuse detected; Android must force a full re-login on this code)
- 403: `SAMD-AUTH-1007` (device_id does not match the device the token was issued to)
- 500: `SAMD-SYS-9005`

### 2.4 POST /api/v1/auth/logout

**Purpose:** Revoke the refresh chain for this device so sign-out means something server side.
**Auth:** Bearer access token.
**Android consumer:** `BackendAuthSession.signOut()`, called from the Profile screen sign-out action.

**Request:**

```json
{ "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." }
```

**Success Response (200):**

```json
{
  "success": true,
  "data": { "revoked": true },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

Logout is idempotent: revoking an already-revoked chain is still `200`. The outstanding access
token is **not** invalidated and remains valid until its 1 h expiry. Stateless JWT plus no Redis
means no blacklist; a 1 h access-token lifetime is the mitigation and this residual is stated
openly in `backend-prd.md` §5.6.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 500: `SAMD-SYS-9005`

### 2.5 GET /api/v1/auth/me

**Purpose:** Resolve the current token to a worker record. Used on app resume to confirm the
session is still good and to refresh the display name, role, and facility without a full re-login.
**Auth:** Bearer access token, any role.
**Android consumer:** `BackendAuthSession.currentUser()`, feeding the existing `AuthViewModel`.

**Request:** no body.

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "worker_id": "a3f5c9d21b8e4470",
    "display_name": "A. Devi",
    "role": "ASHA_WORKER",
    "facility_id": "PHC-RJ-0142",
    "facility_name": "PHC Bagru",
    "permissions": ["patient:write", "encounter:write", "kernel:submit", "sync:push"]
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

`permissions` is derived from the role by the server (§9.3). It is informational, letting the UI
hide actions the caller cannot perform. **It is not the enforcement point.** Enforcement is
server-side per route, always.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 500: `SAMD-SYS-9005`

---

## 3. Patients

Patient identity fields are the highest-sensitivity data in the system (H-04, DPDP). Every
endpoint in this section writes an audit event, including the reads.

### 3.1 POST /api/v1/patients

**Purpose:** Create (or idempotently re-create) a patient record from the device.
**Auth:** Bearer. Roles: `ASHA_WORKER`, `NURSE`, `COMPOUNDER`, `DOCTOR`.
**Android consumer:** `PatientRepositoryImpl` via a new `RetrofitPatientSource`, called from the
outbox after `RegisterPatientUseCase` has already committed locally. Registration never blocks on
the network (offline-first, REQ-REG-03).

**Request:** field set mirrors `PatientEntity` one for one, plus the ABHA columns arriving in Room
migration `MIGRATION_12_13`.

```json
{
  "id": "K7m2Qx9pR4tZ",
  "full_name": "Sunita Devi",
  "date_of_birth": "1991-04-12",
  "age": 35,
  "biological_sex": "FEMALE",
  "guardian_or_spouse_name": null,
  "guardian_relation": null,
  "mobile_number": "9876543210",
  "aadhaar_number": null,
  "abha_number": "12345678901234",
  "abha_address": "sunita.devi@sbx",
  "abha_status": "VERIFIED",
  "kyc_status": "VERIFIED",
  "verification_source": "ABDM_AADHAAR_OTP",
  "verified_at": "2026-08-16T09:41:22.000Z",
  "village": "Bagru Khurd",
  "block": "Sanganer",
  "district": "Jaipur",
  "state": "Rajasthan",
  "pincode": "303007",
  "category": "GENERAL",
  "marital_status": "MARRIED",
  "blood_group": "B+",
  "emergency_contact": "9876500011",
  "primary_care_clinic_name": null,
  "referring_physician_name": null,
  "created_at": "2026-08-16T09:40:00.000Z",
  "updated_at": "2026-08-16T09:41:30.000Z"
}
```

Server-enforced validation, mirroring REQ-REG-01 and REQ-REG-02 rather than trusting the client:

| Field | Rule |
|---|---|
| `id` | 10 to 12 characters, `[A-Za-z0-9]` only |
| `full_name` | required, 1 to 200 characters after trim |
| `mobile_number` / `emergency_contact` | exactly 10 digits when present |
| `pincode` | exactly 6 digits when present |
| `aadhaar_number` | exactly 12 digits when present |
| `abha_number` | exactly 14 digits when present, never dash-formatted |
| contact rule | at least one of `mobile_number` or (`village` or `district`) must be present |
| `date_of_birth` / `age` | at least one must be present; `age` in 0 to 130 |

The client already enforces these (REQ-REG-02). The server enforces them again because a client is
not a trust boundary.

**Success Response (201, or 200 on an idempotent repeat):**

```json
{
  "success": true,
  "data": {
    "id": "K7m2Qx9pR4tZ",
    "server_version": 1,
    "created_at": "2026-08-16T09:40:00.000Z",
    "updated_at": "2026-08-16T09:41:30.000Z",
    "facility_id": "PHC-RJ-0142"
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

The response deliberately does **not** echo the patient body. Sending PHI back over the wire for no
reason widens the exposure surface, and the client already has the record.

**Error Responses:**
- 400: `SAMD-SYS-9001`
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 409: `SAMD-PAT-3002` (same `id`, different payload)
- 409: `SAMD-PAT-3004` (this `abha_number` is already linked to a different patient id; never
  auto-merge, this is the wrong-patient hazard H-03 and the ABHA linkage rule in
  `ABHA planning/abha-integration-plan.md`)
- 422: `SAMD-PAT-3003` (validation, with `errors[]`)
- 500: `SAMD-SYS-9005`

### 3.2 GET /api/v1/patients/{id}

**Purpose:** Fetch one patient by id.
**Auth:** Bearer. All roles, scoped to the caller's `facility_id`.
**Android consumer:** `PatientRepositoryImpl` cache-miss path (Phase 3 and later; in Phase 1 the
device is the source of truth and never reads back).

**Success Response (200):** `data` is the full patient object from §3.1 plus `server_version` and
`facility_id`.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005` (patient belongs to another facility)
- 404: `SAMD-PAT-3001`
- 500: `SAMD-SYS-9005`

### 3.3 PATCH /api/v1/patients/{id}

**Purpose:** Partial update. Only fields present in the body are written.
**Auth:** Bearer. Roles: `ASHA_WORKER`, `NURSE`, `COMPOUNDER`, `DOCTOR`.
**Android consumer:** outbox, after a local patient edit.

**Request:**

```json
{
  "mobile_number": "9876543211",
  "updated_at": "2026-08-16T11:02:00.000Z",
  "base_version": 1
}
```

`base_version` is the `server_version` the client last saw. A mismatch is `409` /
`SAMD-SYNC-6004` carrying the current server state so the client can decide. `id`, `created_at`,
and `facility_id` are immutable; attempting to change one is `422` / `SAMD-PAT-3005`.

**Success Response (200):** same shape as §3.1's success body, with `server_version` incremented.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 404: `SAMD-PAT-3001`
- 409: `SAMD-SYNC-6004` (version conflict)
- 422: `SAMD-PAT-3003`, `SAMD-PAT-3005`
- 500: `SAMD-SYS-9005`

### 3.4 GET /api/v1/patients

**Purpose:** Day-scoped or week-scoped roster, mirroring `PatientDao.observePatientsWithEncounterBetween`.
**Auth:** Bearer. All roles, scoped to the caller's `facility_id`.
**Android consumer:** Phase 3 only, `HomeViewModel` / `PatientsViewModel` refresh path.

**Query parameters:**

| Parameter | Required | Meaning |
|---|---|---|
| `encounter_from` | **yes** | inclusive lower bound, ISO 8601 UTC |
| `encounter_to` | **yes** | exclusive upper bound, ISO 8601 UTC |
| `limit` | no | default 100, max 200 |
| `cursor` | no | opaque continuation token |

Both bounds are **required** and the window is capped at 31 days. There is no way to request every
patient. This preserves REQ-ROS-02 and H-04 across the network boundary rather than only on the
device, and is the reason the endpoint is not a plain `GET /patients`.

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "patients": [
      { "id": "K7m2Qx9pR4tZ", "full_name": "Sunita Devi", "age": 35, "biological_sex": "FEMALE",
        "last_encounter_at": "2026-08-16T09:44:00.000Z", "server_version": 3 }
    ],
    "next_cursor": null
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

Roster entries are a **projection**, not the full patient record. Aadhaar, ABHA, mobile, and
address are not in a list response.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 422: `SAMD-PAT-3003` (missing or inverted bounds, window over 31 days)
- 500: `SAMD-SYS-9005`

---

## 4. Encounters

### 4.1 POST /api/v1/encounters

**Purpose:** Register an encounter created on the device. Mirrors `EncounterEntity`.
**Auth:** Bearer. Roles: `ASHA_WORKER`, `NURSE`, `COMPOUNDER`, `DOCTOR`.
**Android consumer:** outbox, after `StartCaseUseCase`.

**Request:**

```json
{
  "id": "8c1d4e6f-a2b3-4c5d-9e0f-112233445566",
  "patient_id": "K7m2Qx9pR4tZ",
  "started_at": "2026-08-16T09:44:00.000Z",
  "follow_up_of_encounter_id": null,
  "created_at": "2026-08-16T09:44:00.000Z",
  "updated_at": "2026-08-16T09:44:00.000Z"
}
```

**Success Response (201):**

```json
{
  "success": true,
  "data": { "id": "8c1d4e6f-a2b3-4c5d-9e0f-112233445566", "server_version": 1 },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 404: `SAMD-PAT-3001` (unknown `patient_id`; push the patient first, or use `/sync/push` which
  orders the batch for you)
- 409: `SAMD-PAT-3002`
- 422: `SAMD-ENC-4004` (`follow_up_of_encounter_id` belongs to a different patient)
- 500: `SAMD-SYS-9005`

### 4.2 GET /api/v1/encounters/{id}

**Purpose:** Fetch one encounter with its child rows, for the admin view and for Phase 3 pull.
**Auth:** Bearer. All roles, facility-scoped.
**Android consumer:** none in Phase 1.

**Success Response (200):** `data` contains `encounter`, `consultation`, `observations[]`,
`ailments[]`, `case_record`, `kernel_report`, `evaluate_report`, `diagnosis_feedback`,
`prescription`. Absent children are `null` or `[]`, never omitted keys.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 404: `SAMD-ENC-4001`
- 500: `SAMD-SYS-9005`

### 4.3 PATCH /api/v1/case-records/{id}/status

**Purpose:** Advance a case record's status. Mirrors `CaseRecordEntity.status`.
**Auth:** Bearer. Roles: `ASHA_WORKER`, `NURSE`, `COMPOUNDER`, `DOCTOR`.
**Android consumer:** `CaseRecordRepositoryImpl`, on the doctor-assignment send path
(`DoctorAssignmentConfirmViewModel.onConfirm()`) and on outbox drain of `PENDING_SYNC` cases.

**Request:**

```json
{
  "status": "SENT_TO_DOCTOR",
  "assigned_doctor_id": "doc-007",
  "updated_at": "2026-08-16T09:58:00.000Z",
  "base_version": 2
}
```

Allowed values match `CaseStatus` exactly: `DRAFT`, `SAVED_LOCALLY`, `PENDING_SYNC`,
`SENT_TO_DOCTOR`, `PRESCRIPTION_RECEIVED`, `ABANDONED`.

Server-enforced transitions:

| From | Allowed next |
|---|---|
| `DRAFT` | `SAVED_LOCALLY`, `ABANDONED` |
| `SAVED_LOCALLY` | `PENDING_SYNC`, `SENT_TO_DOCTOR`, `ABANDONED` |
| `PENDING_SYNC` | `SENT_TO_DOCTOR`, `ABANDONED` |
| `SENT_TO_DOCTOR` | `PRESCRIPTION_RECEIVED` |
| `PRESCRIPTION_RECEIVED` | (terminal) |
| `ABANDONED` | (terminal) |

`PENDING_SYNC` is accepted from the client because the device legitimately writes it while offline
(`CaseRecordRepository.assignDoctor(isOnline)`), and the row can reach the server later carrying
that state. Any transition outside this table is `409` / `SAMD-ENC-4003`.

**Success Response (200):**

```json
{
  "success": true,
  "data": { "id": "cr-88f1", "status": "SENT_TO_DOCTOR", "server_version": 3 },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 404: `SAMD-ENC-4002`
- 409: `SAMD-ENC-4003` (illegal transition), `SAMD-SYNC-6004` (version conflict)
- 422: `SAMD-PAT-3003`
- 500: `SAMD-SYS-9005`

---

## 5. Kernel proxy

### 5.1 Why this exists

Today the Android app calls the XGBoost FastAPI kernel directly at
`BuildConfig.KERNEL_BASE_URL`, over cleartext on the dev LAN. Once these two endpoints exist, the
kernel becomes an internal implementation detail reachable only from the backend, and
`KERNEL_BASE_URL` becomes dead configuration to be removed from all three flavors (Phase 6).

Path mapping, which is not symmetric and is easy to get wrong:

| Backend endpoint | Forwards to kernel path |
|---|---|
| `POST /api/v1/assess` | `POST /v1/assess` (note: no `/api` on the kernel side) |
| `POST /api/v1/evaluate` | `POST /api/v1/evaluate` |

### 5.2 Structural PHI guarantee (H-10, REQ-HAN-06)

The Android side already guarantees structurally that no `Patient` object can reach the kernel:
`KernelPayload` has no `Patient`-typed field and `SendToKernelUseCase` accepts only
`VitalsReading` + `Consultation` + an opaque case token. **The backend must reproduce that
guarantee rather than inherit it by assumption**, because the backend, unlike the device, has the
full patient row in the same process and a careless join could put a name in an outbound payload.

Two mechanisms, both required:

1. The Pydantic request models for these two routes contain **only** the fields listed below.
   Pydantic v2 is configured `extra="forbid"`, so an unexpected field is a `422`, not a silent
   pass-through.
2. The outbound kernel client accepts only those same models. It has no code path that takes a
   patient row. A serialized identity field cannot reach `httpx` without a deliberate model change,
   which is a reviewable diff.

If an identity-shaped field is ever detected on this boundary, the response is `422` /
`SAMD-KERN-5005` and the event is written to the audit log at `WARN`.

`case_token` remains the value the client sends, which is `CaseRecord.id` today. It is opaque to the
kernel. The risk file's open item ("consider a separate opaque token, not the case PK") is closed
later without a contract change: the proxy substitutes `HMAC-SHA256(case_id, CASE_TOKEN_KEY)`
truncated to 16 hex characters on the way out and restores the original on the way back. Deferred to
Phase 3 so Phase 1 ships; the field shape does not change when it lands.

### 5.3 POST /api/v1/assess

**Purpose:** Forward a pseudonymized clinical payload to the XGBoost differential-diagnosis kernel
and return its verdict, auditing the call.
**Auth:** Bearer. Roles: `COMPOUNDER`, `DOCTOR` (and see the open issue in §9.3, which is likely to
add `ASHA_WORKER` and `NURSE`).
**Android consumer:** `RetrofitKernelSource` (existing), rebased from `KERNEL_BASE_URL` to
`BACKEND_BASE_URL`. `KernelAssessmentRequestDto` and `KernelAssessmentResponseDto` are unchanged;
only an `ApiEnvelope<T>` wrapper class is added. REQ-HAN-01, REQ-HAN-07.

**Request:** byte-identical to today's `KernelAssessmentRequestDto`.

```json
{
  "case_token": "cr-88f1",
  "age": 35,
  "sex": "F",
  "systolic_bp": 128.0,
  "diastolic_bp": 84.0,
  "bmi": 24.6,
  "heart_rate": 88.0,
  "random_glucose": 104.0,
  "spo2": 97.0
}
```

**Success Response (200):** `data` is the kernel's response body unmodified, so the existing
`KernelAssessmentResponseDto` parses it without edit.

```json
{
  "success": true,
  "data": {
    "case_token": "cr-88f1",
    "safety_screen_passed": true,
    "triage_urgency": "ROUTINE",
    "differential_diagnosis": [
      {
        "condition_tier": "Viral upper respiratory infection",
        "probability": 0.81,
        "evidence_for": ["fever 3 days", "spo2 97"],
        "evidence_against": ["no chest findings"]
      }
    ],
    "recommended_investigations": ["CBC"],
    "model_metadata": { "model_version": "xgb-2026-06-11", "inference_time_ms": 143 }
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

**Audit record written for every call**, success or failure, to `kernel_call_log` and to the
hash-chained audit log:

| Logged | Not logged |
|---|---|
| `request_id`, `worker_id`, `facility_id`, `case_token` | the request body |
| `input_sha256` (hash of the canonical JSON request body) | any vital value or complaint text |
| `output_sha256` (hash of the canonical JSON response body) | the response body |
| `model_version`, `endpoint`, `http_status`, `duration_ms` | |

Hashes, not payloads. They prove *which* payload produced *which* verdict, for IEC 62304
traceability, without creating a second copy of the clinical record in a log table. The clinical
content is already durably stored in `kernel_reports` and `evaluate_reports`.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 422: `SAMD-KERN-5003` (kernel rejected the payload; upstream detail preserved in `detail`)
- 422: `SAMD-KERN-5005` (identity field detected, H-10 guard)
- 502: `SAMD-KERN-5001` (kernel unreachable), `SAMD-KERN-5004` (kernel returned unparseable JSON)
- 504: `SAMD-KERN-5002` (kernel timeout; backend read timeout 30 s, matching the app's current
  OkHttp read timeout so behaviour under a slow kernel does not change)
- 500: `SAMD-SYS-9005`

**Android fallback behaviour is unchanged.** `GenerateKernelReportUseCase.tryRealApi` catches any
failure and falls back to the curated mock scenario table, stamping
`InferenceSource.MOCK_FALLBACK` (REQ-HAN-08). Every error above is a normal `HttpException` or
`IOException` at the Retrofit layer, so that fallback keeps working with no change.

### 5.4 POST /api/v1/evaluate

**Purpose:** Forward to the kernel's NLEM treatment and diagnostic-evaluation endpoint.
**Auth:** Bearer. Roles: same as §5.3.
**Android consumer:** `RetrofitEvaluateSource` (existing), rebased to `BACKEND_BASE_URL`.
`EvaluateRequestDto` and `EvaluateReportDto` are unchanged. REQ-EVL-01.

**Request:** byte-identical to today's `EvaluateRequestDto`.

```json
{
  "case_token": "cr-88f1",
  "symptom_string": "fever, body ache, dry cough",
  "age": 35,
  "sex": "F",
  "systolic_bp": 128.0,
  "diastolic_bp": 84.0,
  "bmi": 24.6,
  "heart_rate": 88.0,
  "random_glucose": 104.0,
  "spo2": 97.0,
  "respiratory_rate": 18.0,
  "temperature": 38.4
}
```

**Success Response (200):** `data` is the kernel's `EvaluateReportDto` tree, **passed through
verbatim**, mixed casing and all. The backend does not reshape, rename, or re-key any part of it.

```json
{
  "success": true,
  "data": {
    "diagnostic_summary": {
      "primary_icd_candidate": "J06",
      "primary_ailment_name": "Acute upper respiratory infection",
      "differential": [
        { "icd_candidate": "J06", "adjusted_confidence": 0.78,
          "original_symptom_confidence": 0.74, "vitals_tier_alignment": 0.9,
          "why": "fever with dry cough and normal spo2" }
      ]
    },
    "nlem_treatment": {
      "recommendedDrug": "Paracetamol",
      "levelOfHealthcare": ["PHC"],
      "availableAtPHC": true,
      "dosageForms": ["Tablet 500 mg"],
      "pediatricDose": null,
      "citation": { "source": "NLEM 2022", "page": 41, "section": "2.1", "subsection": null, "item_num": "2.1.1" },
      "confidence": "HIGH",
      "referralReason": null,
      "matchedDisease": { "icd_candidate": "J06", "disease_name": "Acute upper respiratory infection" }
    },
    "brand_mapping": {
      "generic_name": "Paracetamol",
      "jan_aushadhi_brand": "Paracetamol IP 500mg",
      "commercial_brands": ["Crocin", "Dolo 650"],
      "brand_mapping_available": true
    },
    "safety_and_triage": {
      "vitals_triage": {
        "bp_grade": "NORMAL", "pulse": "NORMAL", "respiratory_rate": "NORMAL",
        "spo2": "NORMAL", "temperature": "FEVER", "bmi": "NORMAL",
        "glucose": "NORMAL", "overall_urgency": "ROUTINE"
      },
      "requiresHumanReview": false,
      "pediatric_referral_flag": false,
      "failure_reason": null
    }
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:00:00.000Z", "api_version": "v1" }
}
```

**Upstream error translation.** The kernel's failure body is a different shape from its success
body (`{"error", "message", "case_token"}`, captured as `EvaluateErrorDto`). The proxy converts it
into the standard error envelope and preserves the upstream text in `detail`. This removes the
reason `ClinicalApiService.evaluate` returns `Response<EvaluateReportDto>` rather than a bare
suspend function, though changing that signature is optional and not required by this contract.

There is **no mock fallback on this leg**, by design (REQ-EVL-01, H-09). A failure omits the AI
Clinical Evaluation section from the report rather than fabricating treatment data. The proxy must
not invent a degraded response for any reason.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 422: `SAMD-KERN-5003`, `SAMD-KERN-5005`
- 502: `SAMD-KERN-5001`, `SAMD-KERN-5004`
- 504: `SAMD-KERN-5002`
- 500: `SAMD-SYS-9005`

---

## 6. Sync

### 6.1 POST /api/v1/sync/push

**Purpose:** Accept a batch of locally-created or locally-modified rows from one device, validate,
persist, and return a per-record acknowledgement. This is the single write path used by the
background sync worker. REQ-SYN-02, H-05.
**Auth:** Bearer. All roles.
**Android consumer:** a real `SyncStatus` implementation replacing `MockSyncStatus`, driven by a
`WorkManager` `CoroutineWorker` with a connectivity constraint and exponential backoff, per
`docs/sync-design.md` §2. The `SyncStatus` domain interface itself does not change.

**Request:**

```json
{
  "batch_id": "b7c1e2f3-4a5b-6c7d-8e9f-001122334455",
  "device_id": "9f8b1c2d3e4a5b6c",
  "client_time": "2026-08-16T10:05:00.000Z",
  "records": [
    {
      "table": "patients",
      "op": "upsert",
      "id": "K7m2Qx9pR4tZ",
      "client_updated_at": "2026-08-16T09:41:30.000Z",
      "base_version": null,
      "data": { "full_name": "Sunita Devi", "biological_sex": "FEMALE", "age": 35 }
    },
    {
      "table": "encounters",
      "op": "upsert",
      "id": "8c1d4e6f-a2b3-4c5d-9e0f-112233445566",
      "client_updated_at": "2026-08-16T09:44:00.000Z",
      "base_version": null,
      "data": { "patient_id": "K7m2Qx9pR4tZ", "started_at": "2026-08-16T09:44:00.000Z",
                "follow_up_of_encounter_id": null }
    },
    {
      "table": "audit_log",
      "op": "insert",
      "id": "al-5521",
      "client_updated_at": "2026-08-16T09:44:02.000Z",
      "base_version": null,
      "data": { "timestamp": "2026-08-16T09:44:02.000Z", "user_id": "a3f5c9d21b8e4470",
                "patient_id": "K7m2Qx9pR4tZ", "case_record_id": null,
                "action": "encounter_started", "payload": "{\"encounterId\":\"8c1d...\"}" }
    }
  ]
}
```

- `op` is `upsert` or `insert`. There is no `delete`. Nothing in this system hard-deletes clinical
  or audit data (`docs/data-retention.md`); a deletion is expressed as a soft-delete field inside
  `data` (for example `ailments.deleted_at`).
- `base_version` is `null` for a record the server has never seen.
- Batch limits: 500 records or 5 MB, whichever is hit first. Over either is `413` /
  `SAMD-SYNC-6001`.

**Accepted `table` values and their apply order.** The server sorts records by this rank and
ignores the caller's array order, because the backend enforces real foreign keys even though the
Room schema has none:

`1 patients` → `2 encounters` → `3 consultations` → `4 attachments` → `5 observations` →
`6 ailments` → `7 medical_history_items` → `8 allergies` → `9 family_history_entries` →
`10 social_histories` → `11 medication_entries` → `12 case_records` → `13 kernel_reports` →
`14 evaluate_reports` → `15 diagnosis_feedback` → `16 prescriptions` → `17 medication_lines` →
`18 referrals` → `19 abha_profiles` → `20 audit_log`

Any other value is `422` / `SAMD-SYNC-6002`.

**Fields rejected on this boundary, always.** Presence of any of these is `422` /
`SAMD-SYNC-6006` for that record, and the record is not applied:

| Field | Table | Reason |
|---|---|---|
| `audio_local_uri` | `ailments` | Private-ailment audio never leaves the device. There is no upload path anywhere in the app and there must never be one on this boundary (REQ-AIL-03). |

Note on ailment visibility: `visibility = PRIVATE` rows **do** sync, including their clinical text.
Private means hidden from the worker-facing projection (REQ-AIL-02, REQ-AIL-04), not withheld from
the clinical record. Only the audio is device-local. Getting this backwards in either direction is
a defect.

Note on attachments: `attachments.uri` is a device-local `content://` or file URI and is
meaningless server side. It is stored as `local_uri` with `blob_status = "NOT_UPLOADED"`. Binary
upload is out of scope for v1 (§10).

**Conflict resolution (Phase 4).** Last write wins, keyed on `client_updated_at`, because in
Phases 1 through 4 exactly one device writes any given record and there is no pull path, so a true
conflict cannot arise from normal operation. Concretely:

- `client_updated_at` newer than the stored `updated_at`: applied, `server_version` incremented.
- `client_updated_at` older than or equal to the stored `updated_at`: **not** applied, acknowledged
  as `stale`. This is not an error. It is the expected result of a retried batch.
- `base_version` present and not matching the stored `server_version`: acknowledged as `conflict`
  with the server's current values, and not applied.
- `audit_log` is append-only: a repeat insert of an existing id is acknowledged `duplicate` and is
  never overwritten (REQ-AUD-02).

Field-level merge for non-conflicting fields is Phase 3 of the roadmap and lands only when a pull
path creates a second writer. Adding merge logic before a second writer exists is speculative
complexity.

**Success Response (200), including partial failure:** the batch is processed record by record.
One bad record does not fail the batch, because a field worker's day of captured data must not be
held hostage by a single malformed row.

```json
{
  "success": true,
  "data": {
    "batch_id": "b7c1e2f3-4a5b-6c7d-8e9f-001122334455",
    "received": 3,
    "applied": 2,
    "stale": 0,
    "conflicted": 0,
    "rejected": 1,
    "server_time": "2026-08-16T10:05:01.220Z",
    "results": [
      { "table": "patients", "id": "K7m2Qx9pR4tZ", "status": "applied", "server_version": 1 },
      { "table": "encounters", "id": "8c1d4e6f-a2b3-4c5d-9e0f-112233445566", "status": "applied", "server_version": 1 },
      { "table": "audit_log", "id": "al-5521", "status": "rejected",
        "code": "SAMD-SYNC-6003", "message": "action: unknown audit action." }
    ]
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:05:01.220Z", "api_version": "v1" }
}
```

`status` is one of `applied`, `stale`, `conflict`, `duplicate`, `rejected`. A `conflict` result
additionally carries `server_state` with the server's current values for the conflicting fields.

**Android handling rule:** mark a record synced on `applied`, `stale`, or `duplicate`. Keep it
pending and surface it for review on `conflict`. Mark it as permanently failed and stop retrying on
`rejected`, since a malformed row will stay malformed forever and an infinite retry loop drains a
field device's battery.

**Idempotency:** replaying a `batch_id` within 24 hours returns the original stored response
verbatim without re-applying anything.

**Error Responses (whole-batch failures only):**
- 400: `SAMD-SYS-9001`
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-SYNC-6005` (`device_id` does not match the device bound to the token)
- 413: `SAMD-SYNC-6001`
- 422: `SAMD-SYNC-6002` (unknown table name anywhere in the batch)
- 500: `SAMD-SYS-9005`
- 503: `SAMD-SYS-9004`

### 6.2 GET /api/v1/sync/pull (Phase 3, specified here for shape stability)

**Purpose:** Return records changed on the server since a watermark, so a second device or a
reinstall can rehydrate.
**Auth:** Bearer. All roles, facility-scoped.
**Android consumer:** Room 3 `RemoteMediator` on the read side, per `docs/sync-design.md` §2.

**Query parameters:** `since` (required, ISO 8601 UTC), `tables` (optional CSV, default all),
`limit` (default 200, max 500), `cursor` (optional).

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "server_time": "2026-08-16T10:06:00.000Z",
    "next_cursor": "eyJ0IjoicGF0aWVudHMiLCJ2IjoxMjN9",
    "has_more": false,
    "changes": [
      { "table": "case_records", "id": "cr-88f1", "server_version": 4,
        "updated_at": "2026-08-16T10:05:59.000Z", "deleted": false,
        "data": { "status": "PRESCRIPTION_RECEIVED" } }
    ]
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:06:00.000Z", "api_version": "v1" }
}
```

The client's next watermark is `server_time`, never its own clock. `deleted` is always `false` in
v1 and exists so a future soft-delete propagation does not need a schema change.

**Error Responses:** 401 `SAMD-AUTH-1002`/`1003`, 422 `SAMD-PAT-3003` (missing `since`),
500 `SAMD-SYS-9005`.

---

## 7. Audit

The server audit log is a **single hash chain per facility**, covering both events originating on
the device (arriving through `/sync/push`) and events originating server side (logins, kernel
calls, admin reads). One chain, not two, so "what happened to this patient" is one ordered query.

Chain rule, stated exactly so an implementation cannot drift:

```
entry_hash = SHA256(
    previous_hash            (64 hex chars, or 64 zeros for the genesis entry)
  + "|" + id                 (server-assigned UUID4)
  + "|" + occurred_at        (ISO 8601 UTC, millisecond precision)
  + "|" + facility_id
  + "|" + actor_id
  + "|" + action
  + "|" + (patient_id or "")
  + "|" + (case_record_id or "")
  + "|" + SHA256(payload)    (64 hex chars, hash of the payload string, not the payload)
)
```

The payload is hashed rather than concatenated so that chain verification never has to load the
clinical blobs. Rows are `INSERT` only. There is no `UPDATE` and no `DELETE` path in the ORM layer,
in the API surface, or in the database role's grants (REQ-AUD-02, H-07).

### 7.1 GET /api/v1/audit/events

**Purpose:** Read the audit trail for review, export, and the admin visibility surface.
**Auth:** Bearer. Roles: `DOCTOR` only in v1. Field roles cannot read the audit log.
**Android consumer:** none in Phase 1. The existing `AuditLogRepository` read side stays device-local.

**Query parameters:**

| Parameter | Required | Meaning |
|---|---|---|
| `from` | **yes** | inclusive, ISO 8601 UTC |
| `to` | **yes** | exclusive, ISO 8601 UTC |
| `patient_id` | no | filter |
| `actor_id` | no | filter |
| `action` | no | filter, exact match |
| `limit` | no | default 100, max 500 |
| `cursor` | no | opaque |

The window is capped at 31 days (`SAMD-AUDIT-7003` beyond that). Reading the audit log is itself an
audited action, written as `audit_log_read` with the query parameters in its payload.

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "events": [
      {
        "id": "5b2c9a10-11d2-4c3e-9f88-77aa66bb55cc",
        "sequence": 10428,
        "occurred_at": "2026-08-16T09:44:02.000Z",
        "recorded_at": "2026-08-16T10:05:01.100Z",
        "origin": "DEVICE",
        "device_id": "9f8b1c2d3e4a5b6c",
        "facility_id": "PHC-RJ-0142",
        "actor_id": "a3f5c9d21b8e4470",
        "actor_role": "ASHA_WORKER",
        "action": "encounter_started",
        "patient_id": "K7m2Qx9pR4tZ",
        "case_record_id": null,
        "request_id": "3f2b7c48-9a1e-4c2d-8b55-0f1a2d3e4c5b",
        "payload": "{\"encounterId\":\"8c1d4e6f-a2b3-4c5d-9e0f-112233445566\"}",
        "previous_hash": "9d1a...",
        "entry_hash": "c4f7..."
      }
    ],
    "next_cursor": null
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:06:00.000Z", "api_version": "v1" }
}
```

`occurred_at` is when the action happened (device clock for `origin: DEVICE`). `recorded_at` is when
the server wrote it. Keeping both is what makes an offline capture followed by a delayed sync
legible instead of looking like a backdated record.

Accepted `action` values are the union of the string literals in
`domain/audit/AuditLogger.kt` (`AuditAction`) plus the server-only actions listed in
`backend-prd.md` §6.2. An unknown action arriving through `/sync/push` is rejected
(`SAMD-SYNC-6003`) rather than silently accepted, so the vocabulary cannot rot.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 422: `SAMD-AUDIT-7003` (window over 31 days), `SAMD-PAT-3003`
- 500: `SAMD-SYS-9005`

### 7.2 GET /api/v1/audit/verify

**Purpose:** Recompute the hash chain over a range and report whether it is intact. This is the
evidence that makes the audit trail tamper-*evident* rather than merely append-only.
**Auth:** Bearer. Roles: `DOCTOR` only.
**Android consumer:** none. Admin surface and, later, a scheduled CI or cron check.

**Query parameters:** `from_sequence` (default 1), `to_sequence` (default latest). Capped at
100000 entries per call.

**Success Response (200):**

```json
{
  "success": true,
  "data": {
    "verified": true,
    "from_sequence": 1,
    "to_sequence": 10428,
    "entries_checked": 10428,
    "first_broken_sequence": null,
    "computed_head_hash": "c4f7...",
    "duration_ms": 812
  },
  "meta": { "request_id": "...", "timestamp": "2026-08-16T10:06:00.000Z", "api_version": "v1" }
}
```

On a broken chain the response is still `200` with `verified: false` and `first_broken_sequence`
populated. A tamper finding is a **result**, not a transport error. Returning 500 here would let a
naive monitor treat "the audit log was altered" as an ordinary flake.

**Error Responses:**
- 401: `SAMD-AUTH-1002`, `SAMD-AUTH-1003`
- 403: `SAMD-AUTH-1005`
- 422: `SAMD-AUDIT-7003` (range over 100000 entries)
- 500: `SAMD-SYS-9005`

---

## 8. ABHA / ABDM (Phase 5)

The full design lives in `ABHA planning/abha-integration-plan.md` and is **not duplicated here**.
That document owns the ABDM V3 protocol details, the RSA-OAEP-SHA1 encryption points, the gateway
session token lifecycle, the request-context headers, and the P0/P1/deferred scope split. This
section fixes only the internal HTTP surface the Android app talks to, so the Retrofit interface
can be written against a stable shape.

Mounting decision: these routes live in the **same FastAPI process** as everything else, under
`/api/v1/abha/`. `backend/abdm-adapter/` remains a separate Python package with its own tests and
its own ABDM-facing code, imported by `backend/core/` and mounted as a router. This satisfies both
locked decisions (a sibling directory `backend/abdm-adapter/`, and one process, one container).
Consequence: `BuildConfig.ABHA_BACKEND_BASE_URL` collapses into `BACKEND_BASE_URL` and the separate
dev port 8081 is not needed. See `backend-prd.md` §4.3.

**Common:** all endpoints require a Bearer token, all roles. Android consumer for all of them is
`RetrofitAbhaSource` implementing the `AbdmAbhaSource` domain port, driven by
`CreateAbhaProfileUseCase` and `VerifyAbhaLoginUseCase`. REQ-ABH-01, REQ-ABH-02.

| Method and path | Purpose | Success `data` | Notable errors |
|---|---|---|---|
| `POST /api/v1/abha/registration-sessions` | Start a registration transaction | `{ "session_id": "...", "state": "STARTED", "expires_at": "..." }` | 503 `SAMD-ABHA-2008` |
| `POST /api/v1/abha/registration-sessions/{id}/identity` | Submit Aadhaar; backend encrypts and requests OTP | `{ "session_id": "...", "state": "OTP_REQUESTED", "masked_mobile": "XXXXXX3210" }` | 409 `SAMD-ABHA-2002`, 410 `SAMD-ABHA-2003`, 502 `SAMD-ABHA-2006` |
| `POST /api/v1/abha/registration-sessions/{id}/otp` | Verify OTP and enrol | `{ "session_id": "...", "state": "ENROLLED" }` or `"MOBILE_VERIFICATION_REQUIRED"` | 401 `SAMD-ABHA-2004`, 410 `SAMD-ABHA-2005` |
| `POST /api/v1/abha/registration-sessions/{id}/mobile-otp` | Conditional mobile verification | `{ "session_id": "...", "state": "MOBILE_VERIFIED" }` | 401 `SAMD-ABHA-2004` |
| `GET /api/v1/abha/registration-sessions/{id}` | Poll state | `{ "session_id": "...", "state": "...", "last_error": null }` | 404 `SAMD-ABHA-2001` |
| `GET /api/v1/abha/registration-sessions/{id}/profile` | Final verified identity | the `AbhaIdentity` object below | 409 `SAMD-ABHA-2002` |
| `POST /api/v1/abha/verification-sessions` and children (P1) | Existing-ABHA login, same session shape | same shape | same codes |

**`AbhaIdentity` response object**, field for field aligned with `AbhaProfileEntity` and the
autofill map in `docs/requirements/abha-field-mapping.md`, so nothing about the Register-screen
autofill changes when the mock is replaced:

```json
{
  "abha_number": "12345678901234",
  "abha_address": "sunita.devi@sbx",
  "name": "Sunita Devi",
  "date_of_birth": "1991-04-12",
  "gender": "F",
  "address": "Bagru Khurd",
  "district": "Jaipur",
  "state": "Rajasthan",
  "pincode": "303007",
  "mobile_number": "9876543210",
  "email_address": null,
  "photo_url": null,
  "kyc_verified": true,
  "verification_source": "ABDM_AADHAAR_OTP",
  "verified_at": "2026-08-16T09:41:22.000Z"
}
```

**Non-negotiable rules on this surface**, carried over from the integration plan:

- Aadhaar numbers, OTP values, ABDM tokens, and the ABDM `client_secret` are **never** returned in a
  response, never written to a log line, and never written to an audit payload.
- The state machine is enforced server side. An out-of-order call is `409` / `SAMD-ABHA-2002`, never
  a silent no-op.
- A verified ABHA identity is **not** automatically linked to a patient. Linking is an explicit,
  worker-confirmed action (§3.1's `SAMD-PAT-3004` guard). This is the load-bearing wrong-patient
  control (H-03).
- `ABDM_MODE=stub` exercises the full adapter and state machine against recorded Postman example
  responses. Going live is a config change, not a code change.

---

## 9. Reference tables

### 9.1 Complete error code registry

| Code | HTTP | Meaning |
|---|---|---|
| `SAMD-AUTH-1001` | 401 | Invalid credentials |
| `SAMD-AUTH-1002` | 401 | Token expired (access or refresh) |
| `SAMD-AUTH-1003` | 401 | Token missing, malformed, or bad signature |
| `SAMD-AUTH-1004` | 401 | Refresh token revoked or reuse detected; chain revoked |
| `SAMD-AUTH-1005` | 403 | Role not permitted for this route or resource |
| `SAMD-AUTH-1006` | 403 | Account disabled |
| `SAMD-AUTH-1007` | 403 | Device not bound to this token |
| `SAMD-ABHA-2001` | 404 | Registration or verification session not found |
| `SAMD-ABHA-2002` | 409 | Invalid state transition for this session |
| `SAMD-ABHA-2003` | 410 | Session expired |
| `SAMD-ABHA-2004` | 401 | OTP incorrect |
| `SAMD-ABHA-2005` | 410 | OTP expired, restart the step |
| `SAMD-ABHA-2006` | 502 | ABDM upstream returned an error (external code preserved in `detail`) |
| `SAMD-ABHA-2007` | 504 | ABDM upstream timeout |
| `SAMD-ABHA-2008` | 503 | ABDM not configured for this deployment |
| `SAMD-ABHA-2009` | 409 | ABHA number already linked to a different patient |
| `SAMD-PAT-3001` | 404 | Patient not found |
| `SAMD-PAT-3002` | 409 | Id already exists with a different payload |
| `SAMD-PAT-3003` | 422 | Field validation failed (`errors[]` present) |
| `SAMD-PAT-3004` | 409 | Duplicate ABHA number across patients |
| `SAMD-PAT-3005` | 422 | Attempt to modify an immutable field |
| `SAMD-ENC-4001` | 404 | Encounter not found |
| `SAMD-ENC-4002` | 404 | Case record not found |
| `SAMD-ENC-4003` | 409 | Illegal case status transition |
| `SAMD-ENC-4004` | 422 | Encounter and patient do not match |
| `SAMD-ENC-4005` | 404 | Consultation not found |
| `SAMD-KERN-5001` | 502 | Kernel unreachable |
| `SAMD-KERN-5002` | 504 | Kernel timeout |
| `SAMD-KERN-5003` | 422 | Kernel rejected the payload |
| `SAMD-KERN-5004` | 502 | Kernel returned an unparseable response |
| `SAMD-KERN-5005` | 422 | Identity field detected on the kernel boundary (H-10 guard) |
| `SAMD-SYNC-6001` | 413 | Batch too large |
| `SAMD-SYNC-6002` | 422 | Unknown table in batch |
| `SAMD-SYNC-6003` | (per record) | Record validation failed; appears inside `results[]`, not as an HTTP status |
| `SAMD-SYNC-6004` | 409 | Version conflict (`base_version` mismatch) |
| `SAMD-SYNC-6005` | 403 | `device_id` does not match the token's device |
| `SAMD-SYNC-6006` | 422 | Forbidden field present (for example `ailments.audio_local_uri`) |
| `SAMD-AUDIT-7001` | 500 | Hash chain integrity failure detected during a write |
| `SAMD-AUDIT-7002` | 403 | Attempted mutation of an audit record |
| `SAMD-AUDIT-7003` | 422 | Audit query range too wide |
| `SAMD-SYS-9001` | 400 | Malformed JSON or bad timestamp format |
| `SAMD-SYS-9002` | 415 | Unsupported media type |
| `SAMD-SYS-9003` | 429 | Rate limit exceeded |
| `SAMD-SYS-9004` | 503 | Database unavailable |
| `SAMD-SYS-9005` | 500 | Internal server error |
| `SAMD-SYS-9006` | 404 | Unknown route |
| `SAMD-SYS-9007` | 405 | Method not allowed |

Codes are permanent. A code is never reused for a different meaning and never renumbered, because
Android branches on the string and older app builds stay in the field for a long time.

### 9.2 Endpoint to Android consumer map

| Endpoint | Android consumer | Status |
|---|---|---|
| `GET /health` | none (optional `ConnectivityController` use later) | new |
| `POST /api/v1/auth/login` | `RetrofitAuthService` → `BackendAuthSession` | new, replaces `MockAuthSession` |
| `POST /api/v1/auth/refresh` | OkHttp `Authenticator` in `di/NetworkModule.kt` | new |
| `POST /api/v1/auth/logout` | `BackendAuthSession.signOut()` | new |
| `GET /api/v1/auth/me` | `BackendAuthSession.currentUser()` → `AuthViewModel` | new |
| `POST /api/v1/patients` | `RetrofitPatientSource` → `PatientRepositoryImpl` outbox | new |
| `GET /api/v1/patients/{id}` | `PatientRepositoryImpl` | new, Phase 3 |
| `PATCH /api/v1/patients/{id}` | `PatientRepositoryImpl` outbox | new |
| `GET /api/v1/patients` | `HomeViewModel` / `PatientsViewModel` refresh | new, Phase 3 |
| `POST /api/v1/encounters` | outbox after `StartCaseUseCase` | new |
| `GET /api/v1/encounters/{id}` | none | admin only |
| `PATCH /api/v1/case-records/{id}/status` | `CaseRecordRepositoryImpl`, `DoctorAssignmentConfirmViewModel` | new |
| `POST /api/v1/assess` | `RetrofitKernelSource` | **existing**, rebased to `BACKEND_BASE_URL` |
| `POST /api/v1/evaluate` | `RetrofitEvaluateSource` | **existing**, rebased to `BACKEND_BASE_URL` |
| `POST /api/v1/sync/push` | real `SyncStatus` implementation + `WorkManager` worker | new, replaces `MockSyncStatus` |
| `GET /api/v1/sync/pull` | Room 3 `RemoteMediator` | new, Phase 3 |
| `GET /api/v1/audit/events` | none | admin only |
| `GET /api/v1/audit/verify` | none | admin only |
| `/api/v1/abha/*` | `RetrofitAbhaSource` → `AbdmAbhaSource` | new, Phase 5 |

### 9.3 Authorization matrix

| Endpoint | ASHA_WORKER | NURSE | COMPOUNDER | DOCTOR |
|---|---|---|---|---|
| `GET /health` | public | public | public | public |
| `POST /auth/login`, `/auth/refresh` | public | public | public | public |
| `POST /auth/logout`, `GET /auth/me` | yes | yes | yes | yes |
| `POST /patients`, `PATCH /patients/{id}` | yes | yes | yes | yes |
| `GET /patients/{id}`, `GET /patients` | yes | yes | yes | yes |
| `POST /encounters`, `PATCH /case-records/{id}/status` | yes | yes | yes | yes |
| `GET /encounters/{id}` | no | no | no | yes |
| `POST /assess`, `POST /evaluate` | **see note** | **see note** | yes | yes |
| `POST /sync/push` | yes | yes | yes | yes |
| `GET /sync/pull` | yes | yes | yes | yes |
| `GET /audit/events`, `GET /audit/verify` | no | no | no | yes |
| `/abha/*` | yes | yes | yes | yes |

**Note on kernel submission, unresolved and needing the founder's decision.** The brief specifies
that only `COMPOUNDER` and `DOCTOR` may submit to the kernel. The shipped Android navigation does
not support that: `SendingViewModel` fires `GenerateKernelReportUseCase` and
`GenerateEvaluateReportUseCase` on a route reachable by any signed-in worker, and there is no
role gate anywhere on that path. Enforcing the restriction server side would break the ASHA worker
and nurse flows on day one with a `403`.

Two ways forward, and the choice must be made before Phase 3:

- **(A, recommended)** Allow all four roles to submit. The kernel output is never autonomous; it is
  gated by the liability acknowledgement on `KernelAssessmentScreen` and by the mandatory doctor
  AGREE/MODIFY/REJECT step. Role does not change the safety argument here.
- **(B)** Keep the restriction and add a role gate in the Android nav graph plus a role check in
  `SendingViewModel`, so a worker is told before capture rather than after.

Facility scoping applies on top of the matrix everywhere: a token's `facility_id` bounds every read
and write, on every route, for every role.

---

## 10. Out of scope for v1

Named explicitly so nobody builds them by accident:

- Binary attachment upload (photos, audio, video). `attachments` rows sync as metadata with
  `blob_status = "NOT_UPLOADED"`. Object storage is a separate design.
- WebSocket or server-sent events. Sync is batch.
- GraphQL.
- Any endpoint that returns an unbounded patient list (REQ-ROS-02, H-04).
- Any `DELETE` endpoint on clinical or audit data (`docs/data-retention.md`).
- Self-service account creation, password reset, or a user-management API.
- Multi-tenancy beyond `facility_id` scoping. One PHC network.
- Public or partner API access. This surface serves one Android client.
- Model training, retraining, or a training-data reimport endpoint. `DiagnosisFeedback` rows sync
  and are stored; nothing consumes them yet (REQ-RFN-01).
