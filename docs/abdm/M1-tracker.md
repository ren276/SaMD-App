# M1 (ABHA Creation via Aadhaar OTP) tracker

Source: xlsx test matrix `abha api docs/Copy_of_M1_ABHA_CREATION_AND_VERIFICATION_WITH_APIS_UPDATED_V1_2_7_Aug_1_58de4446bc.xlsx`,
sheet "ABHA CREATION AND VERIFICATION", rows 21-34 (the "ABHA Creation Through Aadhaar OTP"
section, test case IDs `CRT_ABHA_101` through `CRT_ABHA_115`). Cross-checked against
`docs/requirements/abha-internal-contract.md` (Phase A/B ground truth).

**Note on source disagreement:** the xlsx's own Status column (column K) is blank for every row
in this sheet; it is a blank test-execution template, not a tracker with existing status data.
This document is not a copy of xlsx status values (there are none) but a checklist of what the
xlsx and the contract together say M1 needs, checked against the code as of this session
(2026-08-19, branch `ABHA-Milestone-1`). No conflict found between the xlsx's task list and the
contract's scope; both agree the P0 slice is the four Aadhaar-OTP calls, not biometric, DL/PAN, or
demo-auth creation paths (contract's own "what Phase B must not build" section).

## Backend wiring status (this session's scope: stub-to-live for the 4 M1 calls)

| Call | Live network code | Notes |
|---|---|---|
| Gateway session token | Done | `client.fetch_gateway_session_token`. In-memory cache, single-flight refresh via `asyncio.Lock`, refreshes 60s before `expiresIn`. Tested: cache hit, concurrent single-flight (10 concurrent callers -> 1 POST), refresh-after-expiry. |
| Public certificate fetch | Done | `crypto.fetch_public_key_pem`. Response is JSON-wrapped: `{"publicKey": "<base64-DER>", "encryptionAlgorithm": "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"}`. The wrapper (JSON, with an `encryptionAlgorithm` sibling) was confirmed against a real sandbox call on 2026-08-19 (D6); `publicKey`'s own encoding was wrongly assumed to be PEM text at that time and corrected to base64-DER SubjectPublicKeyInfo on 2026-08-24, via a watched live M1 run (D6 correction). `encryptionAlgorithm` is asserted against `EXPECTED_ENCRYPTION_ALGORITHM` before the key is parsed; a mismatch, a missing/malformed `publicKey`, or base64 that isn't a valid DER SubjectPublicKeyInfo all raise `SamdError(ABHA_UPSTREAM_ERROR)` at fetch time (NON_RETRYABLE), rather than silently encrypting with a scheme ABDM may have changed or misrouting an unparseable cert to a retry. |
| Send Aadhaar OTP (`enrollment/request/otp`) | Done | `client.send_otp`. Matches Postman body/headers exactly (no Authorization/X-CM-ID, per the Phase A finding). Tested against a mocked transport. |
| Enrol by Aadhaar (`enrollment/enrol/byAadhaar`) | Done | `client.enrol_by_aadhaar`. `otp_plain` never leaves the process; only `encrypted_otp` goes on the wire, tested explicitly. |
| RSA-OAEP-SHA1 encryption | Done (pre-existing, unchanged) | `crypto.encrypt_oaep_sha1`, already built and tested prior to this session. |
| Credential config via environment | Done (pre-existing) | `Settings.abdm_client_id/secret`, boot-time gate requiring both when `abdm_mode=live` outside dev, already in `config.py`. This session added `abdm_session_url` (the gateway host differs from `abdm_base_url`) and fixed `.env.example`'s stale `ABDM_CERT_URL`. |
| Secret/PHI log redaction | Done, extended | `REDACTED_KEYS` gained `abdm_client_secret`, `txnid`, `txn_id`. Raw PEM added as a value-pattern scrub (`-----BEGIN` prefix) in `logging.py`, since it has no single field name at every call site. Tested in `backend/core/tests/test_logging_redaction.py`. |
| Mobile-update OTP send/verify (`CRT_ABHA_108`/`109`) | Not in this session's scope | Your prompt named 4 calls only (session, cert, send OTP, enrol by Aadhaar). `client.verify_mobile_otp` and `client.get_profile` remain `NotImplementedError` for live mode, unchanged. |

## xlsx test case rows, P0 (Aadhaar-OTP) section

| Test Case ID | Functionality | Backend-relevant | Status |
|---|---|---|---|
| CRT_ABHA_101 | Create ABHA Option | UI-side, not backend | Not applicable to this session |
| CRT_ABHA_102 | Consent collection | Backend sends fixed `consent.code`/`version` | Done (pre-existing, `abdm_consent_block`) |
| CRT_ABHA_103 | Consent collection multilingual | UI-side | Not applicable to this session |
| CRT_ABHA_104 | Aadhaar collection and error message | Backend validates 12-digit pattern, maps ABDM errors | Done (pre-existing, `schemas.IdentitySubmit`, `errors.py`) |
| CRT_ABHA_105 | Aadhaar OTP collection | Backend `submit_identity` -> `send_otp` | Done, now live-capable |
| CRT_ABHA_106 | Resend OTP | Same endpoint, re-invoked by the caller | Done, live-capable (no separate resend endpoint needed; matches contract) |
| CRT_ABHA_107 | OTP-based Aadhaar authentication | Backend `verify_otp` -> `enrol_by_aadhaar` | Done, now live-capable |
| CRT_ABHA_108 | Communication mobile verification I | Backend `verify_mobile_otp` | Not in this session's scope (still stub-only) |
| CRT_ABHA_109 | Communication mobile verification II | Backend `mobile_verification_needed` decision | Done (pre-existing, decision logic only) |
| CRT_ABHA_112 | Suggested ABHA address | Out of P0 scope (contract's own exclusion) | Out of scope |
| CRT_ABHA_113 | Display of ABHA number | Backend `mapping.strip_abha_number_dashes` | Done (pre-existing) |
| CRT_ABHA_114/115 | View/download ABHA card | Out of P0 scope | Out of scope |

## Resolved this session

- **D6, cert response shape.** JSON-wrapped, `publicKey` and `encryptionAlgorithm` fields, not raw
  PEM text — confirmed against a real sandbox call on 2026-08-19. **Correction, 2026-08-24:**
  that 2026-08-19 check confirmed the wrapper only; `publicKey`'s own value was wrongly assumed to
  be PEM text. A watched live M1 run found it is actually **base64-encoded DER
  SubjectPublicKeyInfo**: `{"publicKey": "<base64-DER>", "encryptionAlgorithm":
  "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"}`, length 736 chars observed, decoding to a 4096-bit RSA
  public key (first 8 chars `MIICIjAN`, last 8 `AwEAAQ==`), confirmed via a probe against
  `abhasbx.abdm.gov.in` from a live-mode backend container. `crypto.fetch_public_key_pem` parses
  the JSON, asserts `encryptionAlgorithm` equals `EXPECTED_ENCRYPTION_ALGORITHM`
  (`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`), then base64-decodes `publicKey` (`validate=True`) and
  parses it as DER SubjectPublicKeyInfo, re-serializing to PEM so every caller keeps taking PEM
  regardless of the wire format. A bad algorithm, a missing `publicKey`, malformed base64, or
  base64 that isn't valid DER all raise `SamdError(ABHA_UPSTREAM_ERROR)` — deliberately unified to
  one NON_RETRYABLE class, since a cert this module cannot use is never retryable. (Before this
  correction, a malformed `publicKey` would have been caught by the same `try` block as
  `httpx.HTTPError` and misrouted to a RETRYABLE result, burning a real gateway session token
  retrying a call that could never succeed.) Tested in `test_crypto.py`:
  `test_fetch_public_key_pem_live_mode_parses_json_wrapped_response` (positive, base64-DER fixture),
  `test_fetch_public_key_pem_live_mode_rejects_unexpected_encryption_algorithm`,
  `test_fetch_public_key_pem_live_mode_rejects_malformed_base64`,
  `test_fetch_public_key_pem_live_mode_rejects_valid_base64_non_der`,
  `test_fetch_public_key_pem_live_mode_rejects_missing_publickey_field`, and
  `test_stub_public_key_b64_der_matches_stub_pem` (anti-fabrication guard on the test fixture
  itself). End-to-end wiring covered by
  `test_submit_identity_end_to_end_with_base64_der_cert_produces_ciphertext` in
  `backend/core/tests/test_abha.py`.

## Live-activation risks, verify on first watched enrollment

Not resolved this session; each of these should be checked against the real first live call
before the flow is widened past one watched enrollment.

- Masked-mobile extraction from free-text `message` (`service.py`'s `_extract_masked_mobile`) is
  still a regex against ABDM's own wording, fragile if ABDM ever rewords the message.
- `mobile_number` on the final `AbhaIdentity` is masked-only (ABDM never returns the full number
  via `profile/account`); documented as a contract gap in `abha-internal-contract.md`, not decided
  or changed here. (D4 in the contract doc.)
