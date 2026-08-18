# ABHA internal contract (Phase A, ABDM M1, Create ABHA via Aadhaar OTP)

Status: contract extracted from ground truth, no adapter code written yet. Everything in this
document is either quoted directly from `abha api docs/postman collection/Milestone_1_Postman_
Collection_18_08_2025_postman_collection_d202ddf09a.json` (the ground truth per this session's own
brief) or cross-checked against `abha api docs/Copy_of_M1_ABHA_CREATION_AND_VERIFICATION_WITH_
APIS_UPDATED_V1_2_7_Aug_1_58de4446bc.xlsx` (the M1 test matrix), which agree with each other on
every V3 path used here. The `ABDM_ABHA_V3_AP_Is...pdf` spec was not separately re-extracted line
by line: the Postman collection and the xlsx matrix already agree independently on the full P0
path list and the cipher, which is the cross-check the brief asked for, and re-deriving the same
facts a third time from the PDF would not have surfaced anything new. If the operator wants the PDF
walked anyway, say so and it is a short follow-up, not a redo.

## R0. Where this code lives (read before Phase B)

Two planning documents disagree, and there is a third detail neither the plan doc nor this
session's own brief states precisely. All three are recorded here rather than picked silently.

- `ABHA planning/abha-integration-plan.md`: a **separate FastAPI service**, `backend/abdm-adapter/`,
  its own port 8081, its own sqlite transaction store.
- `docs/backend/api-contract.md` §8 and `docs/backend/backend-prd.md` §4.3 (the later decision,
  confirmed authoritative): `backend/abdm-adapter/` is a **separate Python package**, with its own
  tests and its own ABDM-facing code (`client.py`, `session.py`, `crypto.py`,
  `request_context.py`, `errors.py`, matching the plan doc's own internal layout for that
  package), but it is **imported by `backend/core/` and mounted as a router** in the same FastAPI
  process. One process, one container. The transaction store is the same PostgreSQL database and
  the same Alembic history as everything else (`backend/core/alembic`), not sqlite and not a
  second engine.
- **This session's own brief simplifies that last point** to "Build the ABDM adapter as a MODULE
  inside `backend/core/`, not a separate service." That is directionally right (same process, same
  container, same database) but is not quite what api-contract §8 and backend-prd §4.3 actually
  specify: they call for a **sibling package directory**, `backend/abdm-adapter/`, imported into
  `backend/core`'s app rather than written as files under `backend/core/app/` itself. Neither
  directory exists yet in the repo (checked: `backend/` currently contains only `core/`,
  `docker-compose.yml`, `README.md`, `graphify-out/`), so this is not a conflict between two
  pieces of *code*, only between the brief's paraphrase and the authoritative docs' literal words.

**Resolution, per this session's own rule ("if the brief contradicts the repo, the repo wins") and
per the brief's own instruction that api-contract's decision is authoritative**: Phase B should
create `backend/abdm-adapter/` as a real sibling package (its own `pyproject.toml` or at minimum
its own importable package root, its own `tests/`), imported by `backend/core/app/main.py` and
mounted as an `APIRouter` under `/api/v1/abha/`, sharing `backend/core`'s database session,
Alembic history, auth dependencies, and audit appender. Not a folder under `backend/core/app/`.
Flagging this now, before Phase B, exactly as the brief's own R0 instruction asked for.

## Cipher and cert endpoint (confirmed, with one live discrepancy found)

Cipher for V3 APIs: **`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`**. Confirmed in `abha api docs/get
started/encodingndrsaencryption.md` and consistent with the plan doc and the brief. No discrepancy
on the cipher itself.

**The cert endpoint URL is wrong in two places already in this repo, both sourced from the same
stale prose, not from the Postman ground truth:**

| Source | Cert URL |
|---|---|
| `abha api docs/get started/encodingndrsaencryption.md` (prose) | `https://healthidsbx.abdm.gov.in/api/v1/auth/cert` |
| `backend/core/app/config.py` line 85, `abdm_cert_url` default (Phase 1 scaffold) | `https://healthidsbx.abdm.gov.in/api/v1/auth/cert` (copied from the prose above) |
| **Postman collection, "ABDM Session and cert API" → "Cert API" request (ground truth)** | **`https://abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate`** |

The Postman request is a real, saved GET call with real headers (`REQUEST-ID`, `TIMESTAMP`,
`Authorization: Bearer <gateway session token>`), on the `abhasbx.abdm.gov.in` V3 host, not the
`healthidsbx.abdm.gov.in` host the prose and the Phase 1 config default both point at. This is
exactly the class of thing the brief said to extract from Postman rather than trust from memory or
prose, and it caught a real, already-shipped wrong default. **Not fixed in this Phase A pass**
(no adapter code yet, and `config.py` is existing Phase 1 code, not new adapter code); Phase B must
correct `abdm_cert_url`'s default to the Postman value before the crypto round-trip test can mean
anything against the stub's recorded cert.

## Real V3 paths, P0 slice only (Create ABHA via Aadhaar OTP)

All from the Postman collection's "ABDM Session and cert API" and "ABHA enrolment via Aadhaar"
folders. Every path below is the exact `url.raw` from a saved Postman request, not retyped from
prose.

| Step | Method | Path | Headers on the request | Auth |
|---|---|---|---|---|
| Gateway session | POST | `https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions` | `Content-Type`, `REQUEST-ID`, `TIMESTAMP`, `X-CM-ID: sbx` | none (this call produces the token) |
| Cert | GET | `https://abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate` | `REQUEST-ID`, `TIMESTAMP`, `Authorization: Bearer <gateway session token>` | gateway session token |
| Send Aadhaar OTP | POST | `https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/request/otp` | `Content-Type`, `REQUEST-ID`, `TIMESTAMP` | **none** |
| Create ABHA (verify Aadhaar OTP) | POST | `https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/enrol/byAadhaar` | `Content-Type`, `TIMESTAMP`, `REQUEST-ID` | **none** |
| Mobile update: send OTP | POST | `https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/request/otp` (same path, different body) | `Content-Type`, `TIMESTAMP`, `REQUEST-ID` | **none** |
| Mobile update: verify OTP | POST | `https://abhasbx.abdm.gov.in/abha/api/v3/enrollment/auth/byAbdm` | `TIMESTAMP`, `REQUEST-ID` | **none** |
| Get profile | GET | `https://abhasbx.abdm.gov.in/abha/api/v3/profile/account` | `X-token: Bearer <token>`, `REQUEST-ID`, `TIMESTAMP` | **the per-transaction `X-token` from the enrol/byAadhaar response, not the gateway session token** |

**Finding, worth stopping on:** the four `enrollment/*` calls (send OTP, enrol by Aadhaar, mobile
OTP send, mobile OTP verify) carry **no `Authorization` header at all** in every saved request in
this collection, and no `X-CM-ID` or `X-HIP-ID` either. Only the Cert call uses the gateway session
`Authorization: Bearer` token, and only the final profile call uses a *different* bearer value
(`X-token`, sourced from the `tokens.token` field the enrol/byAadhaar response returns, not from
the gateway sessions endpoint). This means two structurally different "tokens" exist in this flow
and must not be conflated: the **gateway session token** (client-credentials, used only for the
cert fetch in this slice) and the **transaction `X-token`** (minted by ABDM on successful
enrollment, used only for the profile fetch, distinct from `AbhaTransaction.external_token_encrypted`
naming already in the schema, which this token is exactly what that column is for). The plan doc's
prose implies the gateway session token is used more broadly ("does RSA encryption, caches the
gateway session token, and stamps request metadata"); the actual recorded requests do not bear
that out for the enrollment calls themselves. Confirmed independently against the xlsx matrix's
own path list (row 22), which lists the same four `v3/enrollment/*` paths with no separate auth
column entry either. Flagged, not silently assumed either way; Phase B should treat "does
`enrollment/request/otp` need `Authorization`" as an open question to verify with one real stub
replay against the recorded example rather than guessing, since a wrong guess here either breaks
against live ABDM later or exposes a header this doc got wrong.

## Request bodies (P0 slice, field names and casing exactly as recorded)

**Send OTP** (`enrollment/request/otp`):
```json
{
    "txnId": "",
    "scope": ["abha-enrol"],
    "loginHint": "aadhaar",
    "loginId": "{{encrypted aadhaar}}",
    "otpSystem": "aadhaar"
}
```
`txnId` is empty string on the first call (no transaction exists yet); ABDM returns the real
`txnId` in the response. `loginId` is the RSA-OAEP-SHA1-encrypted Aadhaar number, base64.

**Create ABHA by verifying OTP** (`enrollment/enrol/byAadhaar`):
```json
{
    "authData": {
        "authMethods": ["otp"],
        "otp": {
            "txnId": "{{txnId}}",
            "otpValue": "{{encrypted otp}}",
            "mobile": "{{mobile number for ABHA communication}}"
        }
    },
    "consent": { "code": "abha-enrollment", "version": "1.4" }
}
```
`consent.code`/`consent.version` already match `Settings.abdm_consent_code`/`abdm_consent_version`
in `config.py` exactly (`"abha-enrollment"` / `"1.4"`); no change needed there. `mobile` here is
the plaintext communication mobile number the worker enters on behalf of the patient (not
encrypted in this call, per the recorded example), distinct from the Aadhaar-linked mobile that
received the OTP.

**Mobile update: send OTP** (same path as above, `scope` and `loginHint`/`loginId` differ):
```json
{
    "txnId": "{{txnId}}",
    "scope": ["abha-enrol", "mobile-verify"],
    "loginHint": "mobile",
    "loginId": "{{encrypted mobileNumber}}",
    "otpSystem": "abdm"
}
```

**Mobile update: verify OTP** (`enrollment/auth/byAbdm`):
```json
{
    "scope": ["abha-enrol", "mobile-verify"],
    "authData": {
        "authMethods": ["otp"],
        "otp": {
            "timeStamp": "{{current_timestamp}}",
            "txnId": "{{txnId}}",
            "otpValue": "{{encrypted otp}}"
        }
    }
}
```

## Response shapes and status codes actually recorded (not assumed uniform)

**Important finding:** ABDM does not use one uniform error envelope across these endpoints. Three
different shapes appear in the saved examples for the same four P0 calls:

1. **Structured, `{"error": {"code": "...", "message": "..."}}`**, seen on the 422 (invalid OTP
   value, code `ABDM-1204`), the 401 (invalid credentials, code `900901`), and the 500
   (unclassified auth failure, code `900900`).
2. **Flat, field-keyed, no `code` at all**, seen on several 400s: `{"scope": "Invalid Scope",
   "timestamp": "..."}`, `{"loginId": "Invalid LoginId", "timestamp": "..."}`,
   `{"loginHint": "Invalid Login Hint", "timestamp": "..."}`, `{"txnId": "Invalid Transaction Id",
   "timestamp": "..."}`, `{"authMethods": "Invalid Auth Method", "timestamp": "..."}`. The key
   name is whichever request field was invalid; there is no fixed schema to parse against, only
   "the body is a flat object with a `timestamp` key and one other key naming the bad field."
3. **A `200` that is actually a failure**, distinguished only by a body field, not the HTTP
   status: mobile OTP verify's "OTP expired" case returns `200` with `"authResult": "failed"`,
   `"message": "OTP expired, please try again"`. A caller that only checks HTTP status would treat
   this as success.

Phase B's error-mapping module cannot assume shape 1 for everything; it needs to check for
`error.code` first, fall back to "any top-level string value that isn't `timestamp`" for shape 2,
and for the mobile-verify endpoint specifically, check `authResult` on `200` responses before
treating them as success. This is a real implementation constraint, not a style choice, found only
by reading the actual saved examples rather than assuming a spec-shaped error envelope.

**Send OTP, positive (200):**
```json
{ "txnId": "37d8d312-35a0-41e7-a6e4-1074eb18a5fa",
  "message": "OTP sent to Aadhaar registered mobile number ending with ******0903" }
```
**No structured masked-mobile field.** The internal contract's `identity` endpoint response
(api-contract §8) promises `"masked_mobile": "XXXXXX3210"`. The only source for that value in the
real response is this free-text `message` string ("OTP sent to ... ending with ******0903"),
which would have to be regex-extracted. Flagged as an implementation risk for Phase B: either
parse the message (fragile, breaks silently if ABDM ever reworders it) or change the internal
contract to not promise a masked mobile at all and let the UI show a generic "OTP sent" message
instead. Recorded here, not decided here.

**Create ABHA by verifying OTP, positive (200):**
```json
{
  "message": "Account created successfully",
  "txnId": "b89ec10d-71fa-4280-83b3-1fedad66b5f5",
  "tokens": { "token": "eyJ...", "expiresIn": 1800, "refreshToken": "eyJ...", "refreshExpiresIn": 1296000 },
  "ABHAProfile": {
    "firstName": "Username", "middleName": "Kailas", "lastName": "Shelke",
    "dob": "26-06-1999", "gender": "M", "photo": "<base64 jpeg>",
    "mobile": "******0903", "phrAddress": ["9175614088XXXX@sbx"],
    "address": "...", "districtCode": "478", "stateCode": "27", "pinCode": "424201",
    "abhaType": "STANDARD", "stateName": "MAHARASHTRA", "districtName": "JALGAON",
    "ABHANumber": "91-7561-4088-XXXX", "abhaStatus": "ACTIVE"
  },
  "isNew": true
}
```
`tokens.token` is the `X-token` the profile-fetch call needs. `dob` here is `DD-MM-YYYY`. A default
PHR address (`phrAddress`) is already assigned at account-creation time; **the P0 slice never needs
the separate ABHA-address suggest/create endpoints** (`enrollment/enrol/suggestion`,
`enrollment/enrol/abha-address`), confirmed both by this response already carrying a usable address
and by the xlsx matrix listing those two paths under the P1-flavored "Suggested ABHA Address" row
(1.10), not the P0 mandatory rows.

**Get Profile Details, positive (200):** the response actually used to build the final
`AbhaIdentity`. See the field-by-field diff below; this is the response the `GET
.../registration-sessions/{id}/profile` internal endpoint's `AbhaIdentity` object is built from,
not the `ABHAProfile` embedded in the enrol/byAadhaar response (api-contract's internal endpoint
list has a separate `GET profile` step, matching Postman's separate "Get Profile Details" request
authenticated with the `X-token`, not a replay of the enrollment response).

## Field-by-field diff: real `/profile/account` response vs. the pinned `AbhaIdentity` shape

api-contract.md §8's `AbhaIdentity` is reproduced here for reference:
```json
{
  "abha_number": "12345678901234", "abha_address": "sunita.devi@sbx", "name": "Sunita Devi",
  "date_of_birth": "1991-04-12", "gender": "F", "address": "Bagru Khurd", "district": "Jaipur",
  "state": "Rajasthan", "pincode": "303007", "mobile_number": "9876543210",
  "email_address": null, "photo_url": null, "kyc_verified": true,
  "verification_source": "ABDM_AADHAAR_OTP", "verified_at": "2026-08-16T09:41:22.000Z"
}
```

| `AbhaIdentity` field | Real `/profile/account` source | Confirmed / needs a transform / gap |
|---|---|---|
| `abha_number` | `ABHANumber`: `"91-7561-4088-XXXX"` | **Needs a transform.** Real value is dash-formatted, 14 digits with dashes. `AbhaProfile.abha_id` (the model already built) is documented as "14 bare digits, never the dash-formatted display form." Backend must strip dashes before storing. Not a bug in the model, a real step Phase B must not skip. |
| `abha_address` | `preferredAbhaAddress` | Confirmed, direct copy. |
| `name` | `name`: `"Username Kailas Shelke"` (also available split as `firstName`/`middleName`/`lastName`) | Confirmed. Real response gives both forms; use the combined `name` field, matching `AbhaProfile.name` (single column). |
| `date_of_birth` | **No combined `dob` field on this endpoint.** Only `yearOfBirth`, `monthOfBirth`, `dayOfBirth` as separate strings. | **Gap.** Must be composed (`f"{yearOfBirth}-{monthOfBirth}-{dayOfBirth}"`) here. Note this is *different* from the enrol/byAadhaar response's embedded `ABHAProfile.dob`, which *is* a single `"DD-MM-YYYY"` string. Two different shapes for the same logical field across two different real ABDM responses in the same flow; Phase B's mapper needs two separate parse paths, not one. |
| `gender` | `gender`: `"M"` | Confirmed, single-letter code, direct copy. |
| `address` | `address` | Confirmed, direct copy. |
| `district` | `districtName` (there is also a numeric `districtCode`) | Confirmed; use the name field, drop the code. |
| `state` | `stateName` (there is also a numeric `stateCode`) | Confirmed; use the name field, drop the code. |
| `pincode` | `pincode` | Confirmed, direct copy. |
| `mobile_number` | `mobile`: `"******0903"` | **Gap, not a transform.** The real endpoint never returns the full mobile number, only a masked one (last 4 digits). The pinned shape's own example (`"9876543210"`, a full number) is not achievable from this endpoint. Either `mobile_number` in the real internal response is masked too (contract needs updating to say so) or it is populated from the mobile the worker typed in during the `identity`/mobile-update step (the app's own input, not ABDM's), which the backend already has server-side and could carry forward instead of re-deriving from the profile fetch. Decision needed before Phase B, not made here. |
| `email_address` | **Field absent entirely** from this response. | Confirmed as always-null for this flow, consistent with the pinned example already showing `null`. Not a gap, just confirms there is no real source, ever, via this path. |
| `photo_url` | `profilePhoto` (base64 JPEG) and a second, separate `kycPhoto` (also base64 JPEG) | **Gap.** Real response carries the actual image bytes inline, twice, under two different field names; nothing named `photo_url` and nothing that is a URL. Matches this project's existing "no binary upload in v1" precedent for attachments (stored `NOT_UPLOADED`, no object storage yet); the pinned shape's own example already shows `null`, which reads as "deliberately dropped, not wired to object storage yet." Recorded as a decision to confirm, not silently assumed: `photo_url` stays `null` in v1, the two base64 blobs are received and discarded, never persisted, never logged. |
| `kyc_verified` | `kycVerified`: `true` | Confirmed, direct copy. |
| `verification_source` | **Not derived from the response at all.** Nearest real fields are `verificationType: "AADHAAR"` and `verificationStatus: "VERIFIED"`. | Confirmed as a backend-assigned constant, not a mapped field: since this whole vertical slice is one workflow (Aadhaar OTP), `verification_source` can be hardcoded to `"ABDM_AADHAAR_OTP"` for every profile this endpoint returns, matching the pinned example. No mapping bug; just confirming it is a workflow-level constant, not a per-response field, so Phase B should not go looking for a source field that will not be there. |
| `verified_at` | Not present as such; closest is `createdDate`: `"07-05-2024"` (ABDM's own account-creation date, `DD-MM-YYYY`) | Confirmed as backend-assigned (the SaMD server's own timestamp of when *this* verification transaction completed), not `createdDate`, which is a different concept (when the ABHA account itself was created, possibly long before this particular login/verification). |

**Fields the real response carries that the pinned `AbhaIdentity` shape has no slot for at all**
(dropped, not mapped, confirmed deliberately rather than by omission): `subdistrictName`,
`status` (`"ACTIVE"` / etc., ABHA account status, distinct from `kyc_verified`), `authMethods`
(array of methods available for this account), `tags` (empty object in every sample), `stateCode`
and `districtCode` (numeric, superseded by the name fields already mapped), `localizedDetails`
(a full second copy of name/state/district/gender/labels in Devanagari, sizeable), and
`kycPhoto` (see `photo_url` row above). None of these block P0; recorded so a future session does
not have to re-derive that they were seen and intentionally left out.

## State machine

`STARTED -> IDENTITY_SUBMITTED -> OTP_REQUESTED -> OTP_VERIFIED -> ENROLLED ->
[MOBILE_VERIFICATION_REQUIRED -> MOBILE_VERIFIED] -> PROFILE_RETRIEVED -> COMPLETED`, plus `FAILED`
and `EXPIRED`. Confirmed unchanged from the plan doc and api-contract §8; nothing in the Postman
ground truth contradicts this shape. `AbhaTransactionState` (`app/models/enums.py`) already has
every one of these eleven values; no change needed.

## `abha_transactions` table: columns checked against the brief's required list, no migration needed

Read directly from `app/models/abha.py`'s `AbhaTransaction` model (already exists, Phase 1):
`local_transaction_id` (PK), `external_txn_id`, `kind`, `state`, `facility_id`, `worker_id`,
`correlation_id`, `external_token_encrypted`, `external_token_expires_at`, `abha_number`,
`abha_address`, `abha_status`, `abha_type`, `linked_patient_id`, `last_error_code`,
`last_error_detail`, `retry_count`, `otp_request_count`, `created_at`, `updated_at`, `expires_at`.

Covers every field the brief asked for: local txn id, external txn id, state, timestamps
(`created_at`/`updated_at`/`expires_at`), correlation id, last error (`last_error_code` +
`last_error_detail`), retry state (`retry_count` + `otp_request_count`). `external_token_encrypted`
is exactly where the `X-token` from `enrol/byAadhaar` belongs. **No migration needed for the P0
slice.**

**Open question, not a gap in the table, a gap in this contract:** nothing in the table is named
for the masked-mobile value `identity`'s response promises (see "Send OTP" finding above). If the
decision there is "parse it from the message and return it once, do not persist it," no column is
needed and this is not an issue. If it needs to survive a poll (`GET
.../registration-sessions/{id}`), a column would be needed and that is a real, small migration for
Phase B to add, not this session.

## Server-only audit actions for ABHA events

backend-prd.md §6.2's server-only list already has exactly three ABHA-shaped entries:
`abha_session_started`, `abha_session_failed`, `abha_identity_linked`. These map cleanly to
"registration session started," "a step in the flow failed terminally," and "an already-verified
identity was linked to a patient" respectively (the last one is Android-side per the brief and
this backend session does not fire it). **What is missing from that list, checked against the
state machine above:** there is no dedicated server-only action for the *intermediate* successful
steps (identity submitted, OTP verified, mobile verified, profile retrieved) or for enrollment
itself completing (`ENROLLED`) short of the final `COMPLETED`/linked state. Two honest options for
Phase B: (a) log every transition as a single generic `abha_session_started`/`abha_session_failed`
pair only (start and terminal outcome), which the existing vocabulary already supports with zero
changes, or (b) add new server-only actions for the intermediate states, which needs a
`backend-prd.md` §6.2 edit and, since Phase 4's audit-vocabulary work made the accepted-action sets
explicit and tested, a deliberate one, not an invented string slipped into `app/services/sync.py`'s
server-only set without updating the doc it is sourced from. Flagged per the brief's own
instruction ("if a needed server-only ABHA action is missing... flag it rather than inventing one
silently"); not decided here.

## What Phase B must not build (out of scope, confirmed against both the plan doc and the xlsx)

Existing-ABHA verification (all of "ABHA Verification" folder), ABHA Address suggest/create
(`enrollment/enrol/suggestion`, `enrollment/enrol/abha-address`), Email verification, Find ABHA,
Forgot ABHA, Profile Management (photo change, QR code, ABHA card download), Set/Update Password,
Delete/Deactivate/Reactivate ABHA, Logout/refresh-token, Benefit APIs, Child ABHA, all Biometric
(Face/Iris/Fingerprint) variants, Scan and Share, M2/M3. Every one of these has real, recorded
Postman requests in this same collection; none is P0, and building one because it exists in the
docs is exactly what the brief said not to do.

## Phase B: the six corrections, resolved and built

Settled inputs for Phase B, per the operator's own framing; each is now code, not an open question.

- **D1, cert URL.** `app/config.py`'s `abdm_cert_url` default corrected to the real Postman value
  (`https://abhasbx.abdm.gov.in/abha/api/v3/profile/public/certificate`). `abha api docs/get
  started/encodingndrsaencryption.md` is flagged in code comments as known-wrong on this specific
  URL; nothing else in that file was found wrong. The crypto round-trip test
  (`backend/abdm-adapter/tests/test_crypto.py`) uses a fixed local test key and never reaches this
  URL in stub mode, so it does not validate the URL; that is a live-activation checklist item
  (`backend-prd.md` section 8's phase table), not something a stub-mode test can prove.
- **D2, success-by-body-field, never by HTTP status.** `abdm_adapter/errors.py`'s
  `classify_otp_verify` reads `body["authResult"]`; HTTP status is checked only as a first-pass
  filter for genuinely non-200 transport failures. Proven with the operator's own confirmed
  example body (a 200 with `authResult: "failed"`) in
  `backend/abdm-adapter/tests/test_error_mapping.py::test_d2_a_200_with_authresult_failed_is_not_success`
  and end to end through the real router in
  `backend/core/tests/test_abha.py::test_d2_mobile_otp_expired_200_does_not_advance_state`.
- **D3, two DOB shapes, one canonical output.** `abdm_adapter/dob.py`, `from_ddmmyyyy` (the
  `enrol/byAadhaar` embedded profile) and `from_split_fields` (`profile/account`'s separate
  year/month/day). Year-only decision: the canonical `date_of_birth` is the bare year string
  (`"1991"`), never a fabricated `"1991-01-01"`; `AbhaIdentity.date_of_birth` is typed `str | None`
  specifically so this is representable. Tested in `backend/abdm-adapter/tests/test_dob.py`,
  including the malformed-input case that the writing of that test caught as a real crash bug in
  the first draft of `from_ddmmyyyy` (unguarded `int()` on a non-numeric value).
- **D4, masked mobile only.** `AbhaIdentity.mobile_number` is the masked value straight from
  ABDM's `profile/account.mobile` field, or `None`; never fabricated. The regex extraction of the
  masked suffix out of `identity`'s free-text `message` (`abdm_adapter/service.py`'s
  `_extract_masked_mobile`) is flagged in that function's own docstring as fragile against ABDM
  changing the wording, and is a live-activation checklist item. The Android-side consequence
  (`abha-field-mapping.md`'s `mobileNumber -> Patient.mobileNumber` autofill can no longer satisfy
  REQ-REG-01's contact-method rule on its own) is now noted directly in that file; the Android fix
  itself is Phase 6, not built here.
- **D5, photo dropped, never persisted.** `abdm_adapter/mapping.py`'s `profile_to_abha_identity`
  reads `profilePhoto`/`kycPhoto` off the raw ABDM body only via `body.get(...)` calls that are
  never assigned to anything the function returns; the values go out of scope with the function
  and touch nothing else. `photo_url` is always `None`. Proven, not assumed, by
  `backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs`, which also checks the
  raw SQL row (bypassing the ORM's decrypt hook, which would otherwise mask a plaintext-token bug)
  and `capsys`-captured log output for a base64 JPEG magic-byte prefix, not just for Aadhaar/OTP
  literals. The same test is why `app/models/abha.py`'s `external_token_encrypted` column changed
  from a bare `LargeBinary` (encryption resting on call-site discipline, "encrypted when written"
  in a comment nobody enforced) to `EncryptedText` (the same pgcrypto-decorated type `Patient`'s
  identity columns already use): the underlying column stays `bytea` either way, so this was a
  Python-side type correction, not a migration.
- **D6, packaging.** `backend/abdm-adapter/` is a real sibling package (its own `pyproject.toml`,
  its own `abdm_adapter` top-level import name so it cannot collide with `backend/core`'s `app`
  package in the same virtualenv, its own `tests/`), installed editable into `backend/core`'s
  venv and imported by `app/main.py`, which mounts `abdm_adapter.router.router` the same way it
  mounts every other router. One process (confirmed: `GET /health` and every `/api/v1/abha/*`
  route answered from the same `uvicorn` process in this session's manual walk), one PostgreSQL
  database, one Alembic history (no new migration), one audit chain (`app.services.audit.append`,
  the Phase 4 appender, unchanged).
- **D7, the missing intermediate audit actions.** `abha_identity_submitted`, `abha_otp_verified`,
  `abha_enrolled`, `abha_mobile_verified`, `abha_profile_retrieved` added to `backend-prd.md`
  section 6.2's list first, then to `app.models.enums.AuditAction`, then used. Also added to
  `app.services.sync._SERVER_ONLY_AUDIT_ACTIONS` alongside the three pre-existing ABHA actions,
  so a device can never inject one of these five through `/sync/push` either, consistent with the
  Phase 4 audit-vocabulary work this session builds on.

## Real bugs this session's own tests caught before they shipped

Recorded because they are the concrete proof the D2/D5 testing requirements were not pro forma:

- `_fail`'s first draft flushed `txn.state = FAILED` on the request session and then raised
  `SamdError`. `app.db.session.session_scope` rolls back the whole request transaction on any
  propagating exception, so that write silently vanished every time; a failed session was left
  forever stuck at whatever state it was in before the failure, never actually marked `FAILED`.
  This is the exact trap `write_out_of_band`'s own docstring says has shipped twice already
  (Phase 1's failed-login row, Phase 3's kernel call-log rows); it shipped a third time here,
  caught by `test_invalid_otp_on_enrol_by_aadhaar_fails_the_session` and
  `test_d2_mobile_otp_expired_200_does_not_advance_state` both asserting the persisted state
  directly rather than only the HTTP response. Fixed by moving `_fail` onto `write_out_of_band`
  and removing an early flush in `submit_identity` that would otherwise have deadlocked against it
  (see `abdm_adapter/service.py`'s module docstring for the full mechanics).
- `dob.from_ddmmyyyy("not-a-date")` crashed with an unguarded `ValueError` from `int()` instead of
  returning `None`. Caught by `test_from_ddmmyyyy_malformed_is_none_not_a_crash`, a test written
  specifically because D3 asked for "represent honestly, do not fabricate," which implies
  "do not crash," a case the first draft did not actually handle.
