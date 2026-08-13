# Plan — ABDM/ABHA V3 (M1) Integration: Backend Adapter + Android Wiring

## Context

The PHC SaMD app must become ABDM **M1-compliant** (ABHA creation + verification) so a rural
health worker can create or verify a patient's ABHA identity during registration. Today the app
ships a **mock-only, on-device, synchronous** ABHA flow (`AbhaProfile`, `CreateAbhaProfileUseCase`,
`VerifyAbhaLoginUseCase`, `presentation/abha/`). Real ABDM V3 requires a server that holds the
`client_secret`, does RSA encryption, caches the gateway session token, and stamps request
metadata — none of which may live in the Android app (security + a stable internal contract that
survives ABDM changes). That backend **does not exist yet**: the existing FastAPI "kernel"
(`/v1/assess`, `/api/v1/evaluate`) is a separate external ML process, not under app control.

This plan builds the ABDM adapter backend and rewires the Android ABHA flow to talk to it through a
**stable, workflow-oriented internal SaMD API** — not raw ABDM endpoints. Architecture follows the
supplied handoff: Android owns the patient-facing workflow; the backend owns the ABDM protocol.

**Locked decisions:** same repo (`backend/` dir on branch `ABHA`) · FastAPI/Python · first vertical
slice = Create ABHA via Aadhaar OTP · **contract-only** (full adapter built; the live ABDM HTTP call
sits behind a mode flag and is activated by config when sandbox creds arrive). Android stays
**MVVM + Clean** — no MVI migration (the existing State/Effect/Actions pattern is already MVI-lite;
a rewrite is out of scope).

### Blunt technical notes
- **Go is unnecessary.** The adapter is I/O-bound at PHC scale (tens of registrations/day). Go's
  throughput edge buys nothing here and forks the toolchain. Production-readiness = HTTPS + secret
  management + token lifecycle + idempotency + audit + tests — none are language features. Revisit
  only if M2/M3 multi-facility callback fan-out ever needs real throughput. `ponytail:` FastAPI now,
  reconsider language only on measured M2/M3 load.
- **M1 is synchronous.** ABHA create/verify V3 are direct request/response against
  `abhasbx.abdm.gov.in` (txnId-based). The **async ack-then-callback** pattern applies only to the
  HIE-CM gateway (M2/M3 patient discovery/consent). So M1 needs a backend but **not** a public
  callback URL yet — this corrects the "structural async mismatch" worry in
  `docs/requirements/abha-real-api-mapping.md`. Callback infra is deferred to M2.

---

## Step 0 — Git (do first, on approval)

1. Pre-push hygiene: confirm `local.properties` stays gitignored (holds live `GEMINI_API_KEY`,
   `KERNEL_BASE_URL`). Add `.serena/` to `.gitignore` (tool cache, should not be tracked). The
   `abha api docs/` PDFs (~40 MB, largest 5 MB) are fine for plain git — no LFS needed.
2. Commit the current working tree (docs + `.agents/`, `docs/agents/`, `abha api docs/`, requirement
   docs) to `master`, push `master` to `origin`.
3. Create and checkout branch `ABHA`; push `-u origin ABHA`. All feature work lands here.

---

## Phase A — Contract-first (read + specify, no impl)

Read the authoritative sources and produce the internal contract **before** writing adapter code:
- `abha api docs/Copy_of_M1_ABHA_CREATION_AND_VERIFICATION_WITH_APIS_UPDATED...xlsx` — the M1 API matrix.
- `abha api docs/postman collection/Milestone_1_Postman_Collection...json` — real V3 request/response
  bodies (exact paths, field names/casing, headers, encryption points). This is the ground truth for
  the ABDM-facing calls; the plan deliberately does **not** hardcode V3 paths — extract them here.
- `abha api docs/ABDM_ABHA_V3_AP_Is_V1_31_07_2025...pdf` — spec cross-check.
- `abha api docs/get started/` — `workingwithabdmapis.md` (headers, session token, X-HIP-ID/X-CM-ID),
  `encodingndrsaencryption.md` (V3 cert `https://healthidsbx.abdm.gov.in/api/v1/auth/cert`, cipher
  `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`), `phrframework.md`.
- Existing: `docs/requirements/software-requirements.md` (REQ-ABH/REQ-REG/REQ-SEC),
  `docs/requirements/abha-field-mapping.md`, `abha-real-api-mapping.md`, `agent_docs/spec.md`,
  `docs/quality/risk-management-file.md`, `agent_docs/hardening.md`.

**Deliverable:** `docs/requirements/abha-internal-contract.md` — the versioned internal SaMD API
(request/response schemas, state machine, error codes) + a field-by-field diff of the real V3 KYC
`/profile` response against `AbhaProfile`. Everything downstream builds from this doc.

---

## Architecture (four layers)

```
Android UI/VM  →  Android ABHA domain/repo  →[HTTPS]→  SaMD backend (ABDM adapter)  →  ABDM V3
   (workflow states)      (internal SaMD API)              (owns protocol)         (abhasbx)
```

Android speaks **application workflow** (`startAbhaRegistration`, `submitIdentity`, `submitOtp`,
`getRegisteredIdentity`) — never raw ABDM verbs. Backend translates workflow → ABDM V3 sequence.

---

## Backend — `backend/abdm-adapter/` (FastAPI, new)

```
backend/abdm-adapter/
  app/
    main.py                 # FastAPI app, request-id middleware, /health
    config.py               # env: ABDM_MODE(live|stub), sandbox/prod base URLs, HIP_ID, X-CM-ID, timeouts, feature flags
    api/v1/
      registration.py       # internal SaMD workflow endpoints (below)
      verification.py       # existing-ABHA verify (P1)
    adapter/                # ABDM-facing, the only code that knows ABDM
      client.py             # httpx client; live vs stub switch (stub replays Postman examples)
      session.py            # gateway session token fetch + in-memory cache w/ expiry
      crypto.py             # fetch V3 cert, RSA-OAEP-SHA1 encrypt Aadhaar/OTP/mobile
      request_context.py    # REQUEST-ID (uuid), TIMESTAMP (iso), Authorization, X-HIP-ID, X-CM-ID
      abha_identity.py      # create(Aadhaar)/verify/profile ABDM V3 calls (paths from Phase A)
      abha_address.py       # suggest/create/verify (stage 2)
      errors.py             # ABDM code (ABDM-1006 etc.) → internal code + preserved externalCode/message
    domain/
      transaction.py        # AbhaTransaction state machine (states below)
      models.py             # internal DTOs: AbhaIdentity, VerifiedAbhaIdentity, RegistrationSession
    store/transactions.py   # transaction persistence (sqlite for dev; pg-ready)
    tests/
  requirements.txt
  .env.example              # documents every var; NO secrets committed
```

**Internal SaMD API (versioned `/api/v1/`, workflow-oriented):**
- `POST /api/v1/abha/registration-sessions` → new txn, returns `{sessionId, state}`.
- `POST /api/v1/abha/registration-sessions/{id}/identity` → submit Aadhaar; backend RSA-encrypts +
  calls ABDM request-OTP; returns state `OTP_REQUESTED`.
- `POST /api/v1/abha/registration-sessions/{id}/otp` → verify + enrol; returns next state.
- `POST /api/v1/abha/registration-sessions/{id}/mobile-otp` → conditional mobile verify.
- `GET  /api/v1/abha/registration-sessions/{id}` → poll state.
- `GET  /api/v1/abha/registration-sessions/{id}/profile` → final `AbhaIdentity`.
- (P1) `/api/v1/abha/verification-sessions/...` for existing-ABHA login.

**Transaction state machine** (`AbhaTransaction`, separate from any patient concept):
`STARTED → IDENTITY_SUBMITTED → OTP_REQUESTED → OTP_VERIFIED → ENROLLED →
[MOBILE_VERIFICATION_REQUIRED → MOBILE_VERIFIED] → PROFILE_RETRIEVED → COMPLETED`, plus `FAILED`,
`EXPIRED`. Persist `{localTransactionId, externalTxnId, state, createdAt, expiresAt, correlationId,
lastError, retryState}`.

**Cross-cutting:** REQUEST-ID/TIMESTAMP generated centrally in `request_context.py` (never per-call).
Two correlation IDs kept distinct: SaMD `correlationId` (audit/logs) vs ABDM `REQUEST-ID`. Error
retry classification: retryable (network/timeout) vs non-retryable (invalid OTP/scope/identity) vs
user-action (OTP expired). No blind retries.

**Contract-only activation:** `ABDM_MODE=stub` replays Postman example responses through the full
adapter + state machine (real crypto/token code still exercised where safe). `ABDM_MODE=live` + creds
= real httpx calls. **Going live = a config change, not a code change.**

---

## Android changes

- **New internal-API source (mirrors the kernel Retrofit pattern):** `AbhaApiService` (Retrofit,
  points at the SaMD backend — NOT ABDM), domain port `AbdmAbhaSource`, impl `RetrofitAbhaSource`.
  New base URL `BuildConfig.ABHA_BACKEND_BASE_URL` (separate from `KERNEL_BASE_URL`), wired in
  `di/NetworkModule.kt` with the existing `@Binds Bindings` shape.
- **Use cases:** rewire `CreateAbhaProfileUseCase` (and later `VerifyAbhaLoginUseCase`) to drive the
  session state machine via `AbdmAbhaSource`. Keep the mock body behind a flag using the existing
  `tryRealApi() ?: generateMock()` pattern from `GenerateKernelReportUseCase.kt`, so contract-only
  builds keep working until the backend goes live. Keep `AbhaProfile` + Room DAO as the local cache.
- **Patient model:** add `abhaAddress`, `abhaStatus`, `kycStatus`, `verificationSource`, `verifiedAt`
  to `domain/model/Patient.kt` + `PatientEntity.kt` (registration state does NOT go on Patient — that
  lives in the backend transaction). Additive Room migration **`MIGRATION_12_13`** (DB is at **v12** —
  note: `agent_docs/CLAUDE.md` says v11, it is stale; verify against `app/schemas/.../12.json`), and
  register a `13.json` schema.
- **UI:** `presentation/abha/` screens keep their MVI shape; add an explicit registration-state enum
  in `AbhaSignUpViewModel` mapping backend states → UI (Idle/IdentityInput/OtpPending/Enrolling/
  MobileVerify/ProfileConfirm/Completed/Failed/Expired). No new nav graph.
- **Audit:** add constants to `domain/audit/AuditLogger.kt` — `ABHA_REGISTRATION_STARTED`,
  `ABHA_IDENTITY_VERIFICATION_REQUESTED`, `ABHA_OTP_VERIFIED`, `ABHA_ENROLLMENT_COMPLETED`,
  `ABHA_PROFILE_RETRIEVED`, `ABHA_LINKED_TO_PATIENT`, `ABHA_VERIFICATION_FAILED` (keep existing
  `ABHA_PROFILE_CREATED`, `ABHA_LOGIN_VERIFIED`). Never log Aadhaar/OTP/token/secret.

---

## Safety & compliance boundaries (non-negotiable)

- **Patient linkage is explicit and worker-confirmed.** Verified ABHA ≠ confirmed local patient.
  `VerifiedAbhaIdentity → candidate match → worker confirmation → link`. Never silently overwrite an
  existing ABHA association or auto-pick a fuzzy match. This is the load-bearing risk (wrong-patient).
- **No ABHA in the ML payload** (already enforced — keep it). ABHA never influences prediction/dose.
- **No ABDM secrets in Android; no sensitive values in logs/audit.** Sandbox vs production are
  separate configs; no prod build points at sandbox and vice versa.
- **Regulatory:** add REQ-ABH-* rows to `software-requirements.md` + `traceability-matrix.md`; add the
  wrong-patient-linkage risk to `risk-management-file.md`; log the ABDM decision in
  `design-history-file.md`.

## M1 scope

**P0 (this pass):** ABDM session, crypto, request context, error mapping, **Create ABHA via Aadhaar
OTP → enrollment → profile** (the vertical slice), transaction persistence, Android wiring, audit.
**P1 (next):** existing-ABHA verification, ABHA Address suggest/create. **Deferred:** recovery, QR/
scan-and-share, Face-Auth, ABHA Card, HPR/HFR, M2 discovery/consent, M3 FHIR. Do **not** implement an
ABDM family just because it exists in the docs (Benefit/Child ABHA/Govt-Find are authorization-gated).

---

## Verification (contract-only)

- **Backend:** `pytest` over `adapter/session`, `crypto` (encrypt round-trip vs a known test key),
  `errors` mapping, and the `transaction` state machine. Run `uvicorn` locally with `ABDM_MODE=stub`;
  `curl` the internal endpoints and walk a full session `STARTED → … → COMPLETED`, asserting each
  state transition and the final `AbhaIdentity` shape against the Phase-A contract.
- **Android:** unit-test `RetrofitAbhaSource`/use case against a fake backend (existing testing
  pattern). Build + run the app pointing `ABHA_BACKEND_BASE_URL` at the local stub backend; drive the
  Create-ABHA screen end to end; confirm state transitions, Register autofill, `MIGRATION_12_13` opens
  a v12 DB clean, and the new audit rows are written (no Aadhaar/OTP in them).
- **Live activation checklist (when creds arrive):** set `ABDM_MODE=live` + client_id/secret + HIP_ID
  + X-CM-ID, register the sandbox HIP, run the same session against `abhasbx`, verify sandbox/prod
  isolation. Acceptance is behavioural (full lifecycle + audit + no secret leakage), not "HTTP 200".
