"""ABDM-facing calls for the P0 slice. Live-vs-stub switch lives here and only here: every other
module in this package is agnostic to `ABDM_MODE`.

`ABDM_MODE=stub` replays the real Postman example bodies (Phase A contract doc) through the full
adapter and state machine; no network call happens. `ABDM_MODE=live` is implemented for the
session-token fetch, `send_otp`, `enrol_by_aadhaar` and `get_profile`; `verify_mobile_otp` still
raises a loud `NotImplementedError`. Every live path here is exercised only against a mocked
transport in
`tests/test_client_live.py`; no test in this repo makes a real ABDM call.

The stub's branching on plaintext OTP values (`"111111"`/`"222222"` below) exists only so tests can
drive every classified outcome deterministically; it has no bearing on live behaviour and is not a
credential or a secret.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx

from abdm_adapter.errors import (
    AbdmResult,
    classify_generic_enrollment_error,
    classify_get_profile,
    classify_otp_verify,
)
from abdm_adapter.request_context import abdm_headers

# Gateway session token cache. Process-wide, in memory only (never DB, never disk, per the live-
# wiring brief). `_session_lock` serializes refreshes so concurrent callers racing an expired
# token do not all hit POST .../sessions at once (single-flight): the first caller through the
# lock refreshes and populates the cache, every other caller re-checks the cache after acquiring
# the lock and finds it already fresh.
_cached_token: str | None = None
_cached_token_expires_at: datetime | None = None
_session_lock = asyncio.Lock()

# Refresh this many seconds before the token's real expiry, so a call already in flight when the
# token is fetched does not race the token's own deadline.
_TOKEN_REFRESH_SKEW_SECONDS = 60

# Stub-only sentinels. Not real Aadhaar/OTP values; chosen to be obviously fake (all-same-digit,
# never a valid 12-digit Aadhaar or a real OTP) so nobody mistakes them for live test data.
_STUB_OTP_EXPIRED = "111111"
_STUB_OTP_INCORRECT = "222222"

_STUB_MASKED_MOBILE_SUFFIX = "0903"


def _stub_profile(abha_number_dashed: str = "91-7561-4088-0001") -> dict[str, Any]:
    return {
        "ABHANumber": abha_number_dashed,
        "preferredAbhaAddress": "sunita.devi0001@sbx",
        "mobile": f"******{_STUB_MASKED_MOBILE_SUFFIX}",
        "firstName": "Sunita",
        "middleName": "",
        "lastName": "Devi",
        "name": "Sunita Devi",
        "yearOfBirth": "1991",
        "monthOfBirth": "04",
        "dayOfBirth": "12",
        "gender": "F",
        "profilePhoto": "/9j/stub-base64-jpeg-bytes-not-a-real-photo",
        "kycPhoto": "/9j/stub-base64-jpeg-bytes-not-a-real-photo",
        "status": "ACTIVE",
        "stateCode": "08",
        "districtCode": "42",
        "pincode": "303007",
        "address": "Bagru Khurd",
        "stateName": "RAJASTHAN",
        "districtName": "JAIPUR",
        "subdistrictName": "JAIPUR",
        "authMethods": ["MOBILE_OTP", "AADHAAR_OTP", "DEMOGRAPHICS"],
        "tags": {},
        "kycVerified": True,
        "verificationStatus": "VERIFIED",
        "verificationType": "AADHAAR",
        "createdDate": "17-08-2026",
    }


async def fetch_gateway_session_token(
    *,
    mode: str,
    session_url: str = "",
    client_id: str = "",
    client_secret: str = "",
    timeout_seconds: float = 30.0,
) -> str:
    """POST .../gateway/v3/sessions.

    Needed for the cert fetch AND for `send_otp`/`enrol_by_aadhaar` (and, once implemented,
    `verify_mobile_otp`). The Phase A finding that "the four enrollment/* calls carry no
    Authorization header in any recorded example" is FALSIFIED for `send_otp`, live-verified
    2026-08-24:

    On 2026-08-24 against abhasbx.abdm.gov.in sandbox, POST /abha/api/v3/enrollment/request/otp
    returned 401 "Missing Credentials" (WSO2 error 900902) when called without an
    Authorization: Bearer <gateway-session-token> header, and returned 400 "Invalid LoginId" when
    the same call was made with the header, proving auth was the missing piece. This contradicts
    the earlier claim that the four enrollment/* calls carry no Authorization header in any
    recorded example. The recorded examples were either incomplete or ABDM's sandbox gateway
    config has changed. Verified via /tmp/probe_otp.py in-container, using this same function's
    token.

    `X-CM-ID` is still confirmed absent by the same probe: only the Authorization half of the old
    claim was wrong.

    `enrol_by_aadhaar` carries the same Bearer by extrapolation (same gateway product, same
    `/abha/api/v3/enrollment/*` prefix), not by direct live verification — see its own docstring.

    Live mode caches the returned `accessToken` in memory (module-level, this process only) and
    refreshes it just before `expiresIn` runs out. `_session_lock` makes concurrent refreshes
    single-flight: every caller awaits the same lock, and a caller that acquires it after another
    caller already refreshed re-checks the cache first and returns immediately without a second
    network call.
    """
    if mode == "stub":
        return "stub-gateway-session-token"

    global _cached_token, _cached_token_expires_at

    now = datetime.now(UTC)
    if _cached_token is not None and _cached_token_expires_at is not None:
        if now < _cached_token_expires_at:
            return _cached_token

    async with _session_lock:
        now = datetime.now(UTC)
        if _cached_token is not None and _cached_token_expires_at is not None:
            if now < _cached_token_expires_at:
                return _cached_token

        async with httpx.AsyncClient(timeout=timeout_seconds) as http_client:
            response = await http_client.post(
                session_url,
                headers={
                    "Content-Type": "application/json",
                    "X-CM-ID": "sbx",
                    **abdm_headers(),
                },
                json={
                    "clientId": client_id,
                    "clientSecret": client_secret,
                    "grantType": "client_credentials",
                },
            )
            response.raise_for_status()
            body = response.json()

        token = str(body["accessToken"])
        expires_in = int(body.get("expiresIn", 300))
        _cached_token = token
        _cached_token_expires_at = now + timedelta(
            seconds=max(expires_in - _TOKEN_REFRESH_SKEW_SECONDS, 1)
        )
        return token


async def send_otp(
    *,
    mode: str,
    gateway_token: str,
    txn_id: str,
    scope: list[str],
    login_hint: str,
    encrypted_login_id: str,
    otp_system: str,
    base_url: str = "",
    timeout_seconds: float = 30.0,
) -> AbdmResult:
    """POST enrollment/request/otp. Same endpoint for both the initial Aadhaar-OTP request and the
    mobile-update OTP request; only `scope`/`login_hint`/`otp_system` differ between the two call
    sites (service.py), matching the two recorded Postman requests exactly.

    Live mode carries `Authorization: Bearer <gateway_token>`, live-verified 2026-08-24:

    On 2026-08-24 against abhasbx.abdm.gov.in sandbox, POST /abha/api/v3/enrollment/request/otp
    returned 401 "Missing Credentials" (WSO2 error 900902) when called without this header, and
    returned 400 "Invalid LoginId" when the same call was made with it, proving auth was the
    missing piece. This contradicts the earlier claim that the four enrollment/* calls carry no
    Authorization header in any recorded example. The recorded examples were either incomplete or
    ABDM's sandbox gateway config has changed. Verified via /tmp/probe_otp.py in-container, using
    the same fetch_gateway_session_token path this adapter uses.

    `X-CM-ID` is still confirmed absent by the same probe; `gateway_token` is a required parameter
    (not `str | None`) so a future edit that drops it at the call site fails loudly (`TypeError`)
    rather than silently reintroducing the 401.
    """
    if mode != "stub":
        async with httpx.AsyncClient(timeout=timeout_seconds) as http_client:
            response = await http_client.post(
                f"{base_url}/abha/api/v3/enrollment/request/otp",
                headers={
                    "Content-Type": "application/json",
                    **abdm_headers(gateway_token=gateway_token),
                },
                json={
                    "txnId": txn_id,
                    "scope": scope,
                    "loginHint": login_hint,
                    "loginId": encrypted_login_id,
                    "otpSystem": otp_system,
                },
            )
        return classify_generic_enrollment_error(response.status_code, response.json())

    new_txn_id = txn_id or str(uuid.uuid4())
    body = {
        "txnId": new_txn_id,
        "message": f"OTP sent to {'Aadhaar registered' if login_hint == 'aadhaar' else ''} "
        f"mobile number ending with ******{_STUB_MASKED_MOBILE_SUFFIX}",
    }
    return classify_generic_enrollment_error(200, body)


async def enrol_by_aadhaar(
    *,
    mode: str,
    gateway_token: str,
    txn_id: str,
    otp_plain: str,
    encrypted_otp: str,
    communication_mobile: str,
    consent_code: str,
    consent_version: str,
    base_url: str = "",
    timeout_seconds: float = 30.0,
) -> AbdmResult:
    """POST enrollment/enrol/byAadhaar. `otp_plain` is stub-only, used to pick which recorded
    example to replay; never sent anywhere and never logged (see service.py). Live mode sends
    `encrypted_otp`, never `otp_plain`, matching that rule.

    Live mode carries `Authorization: Bearer <gateway_token>`, originally applied by EXTRAPOLATION
    from the `send_otp` finding (2026-08-24: `enrollment/request/otp` 401s "Missing Credentials" /
    WSO2 900902 without this header) — same WSO2 gateway product, same `/abha/api/v3/enrollment/*`
    prefix. CONFIRMED LIVE 2026-08-28 (docs/abdm/M1-tracker.md, D9): a watched run's `get_profile`
    call could not have succeeded without a successful `enrol_by_aadhaar` on the same run, so this
    header is no longer inference alone for this endpoint.
    """
    if mode != "stub":
        async with httpx.AsyncClient(timeout=timeout_seconds) as http_client:
            response = await http_client.post(
                f"{base_url}/abha/api/v3/enrollment/enrol/byAadhaar",
                headers={
                    "Content-Type": "application/json",
                    **abdm_headers(gateway_token=gateway_token),
                },
                json={
                    "authData": {
                        "authMethods": ["otp"],
                        "otp": {
                            "txnId": txn_id,
                            "otpValue": encrypted_otp,
                            "mobile": communication_mobile,
                        },
                    },
                    "consent": {"code": consent_code, "version": consent_version},
                },
            )
        return classify_generic_enrollment_error(response.status_code, response.json())

    if otp_plain == _STUB_OTP_INCORRECT:
        return classify_generic_enrollment_error(
            422,
            {
                "error": {
                    "code": "ABDM-1204",
                    "message": "UIDAI Error code : 400 : Invalid Aadhaar OTP value.",
                }
            },
        )

    body = {
        "message": "Account created successfully",
        "txnId": txn_id,
        "tokens": {
            "token": "stub-x-token-" + str(uuid.uuid4()),
            "expiresIn": 1800,
            "refreshToken": "stub-refresh-token",
            "refreshExpiresIn": 1296000,
        },
        "ABHAProfile": {
            "firstName": "Sunita",
            "middleName": "",
            "lastName": "Devi",
            "dob": "12-04-1991",
            "gender": "F",
            "photo": "/9j/stub-base64-jpeg-bytes-not-a-real-photo",
            "mobile": f"******{_STUB_MASKED_MOBILE_SUFFIX}",
            "phrAddress": ["sunita.devi0001@sbx"],
            "address": "Bagru Khurd",
            "districtCode": "42",
            "stateCode": "08",
            "pinCode": "303007",
            "abhaType": "STANDARD",
            "stateName": "RAJASTHAN",
            "districtName": "JAIPUR",
            "ABHANumber": "91-7561-4088-0001",
            "abhaStatus": "ACTIVE",
        },
        "isNew": True,
    }
    return classify_generic_enrollment_error(200, body)


async def verify_mobile_otp(
    *, mode: str, txn_id: str, otp_plain: str, encrypted_otp: str
) -> AbdmResult:
    """POST enrollment/auth/byAbdm. D2: success/failure is `body["authResult"]`, HTTP status is
    200 either way, matching both the recorded Postman example and the brief's own confirmed body.

    When live mode is implemented: carries `Authorization: Bearer <gateway session token>`, same
    reasoning as `enrol_by_aadhaar` (same gateway product, same `/abha/api/v3/enrollment/*`
    prefix; see `send_otp`'s docstring for the live-verified finding this extrapolates from).
    """
    if mode != "stub":
        raise NotImplementedError("ABDM_MODE=live is not implemented this session.")

    if otp_plain == _STUB_OTP_EXPIRED:
        expired_body: dict[str, Any] = {
            "txnId": txn_id,
            "authResult": "failed",
            "message": "OTP expired, please try again",
            "accounts": [],
        }
        return classify_otp_verify(200, expired_body)

    if otp_plain == _STUB_OTP_INCORRECT:
        incorrect_body: dict[str, Any] = {
            "txnId": txn_id,
            "authResult": "failed",
            "message": "Incorrect OTP, please try again",
            "accounts": [],
        }
        return classify_otp_verify(200, incorrect_body)

    success_body: dict[str, Any] = {
        "txnId": txn_id,
        "authResult": "success",
        "message": "Mobile number is now successfully linked to your Account",
        "token": "stub-mobile-verify-token-" + str(uuid.uuid4()),
        "expiresIn": 300,
        "accounts": [{"ABHANumber": "91-7561-4088-0001"}],
    }
    return classify_otp_verify(200, success_body)


async def get_profile(
    *,
    mode: str,
    gateway_token: str,
    x_token: str,
    base_url: str = "",
    timeout_seconds: float = 30.0,
) -> AbdmResult:
    """GET profile/account, authenticated with two structurally different tokens on the same call:
    the per-transaction `X-token` from enrol_by_aadhaar's `tokens.token` (or verify_mobile_otp's
    `token`), and the gateway session token (see fetch_gateway_session_token).

    Live mode sends both `Authorization: Bearer <gateway_token>` and `X-token: Bearer <x_token>`,
    plus REQUEST-ID/TIMESTAMP. The recorded Postman ground truth for this endpoint
    (`docs/requirements/abha-internal-contract.md` line 84) lists only `X-token`, REQUEST-ID, and
    TIMESTAMP. PR #19 measured that the WSO2 gateway in front of abhasbx.abdm.gov.in rejects
    enrollment/request/otp with 401 "Missing Credentials" (WSO2 error 900902) without an
    Authorization: Bearer gateway session token, and that this is a gateway policy rejection, not
    an application response. `profile/account` sits behind the same gateway. The `Authorization`
    header was originally applied by that same inference, not by a live measurement of this
    specific endpoint. CONFIRMED LIVE 2026-08-28 (docs/abdm/M1-tracker.md, D9): a watched run
    reached Call 4/5 and it succeeded with both headers present, so this is no longer inference
    alone for this endpoint.

    The response body is never logged, here or by any caller. `profilePhoto`/`kycPhoto` are inline
    base64 JPEG bytes and `REDACTED_KEYS` (backend/core's config.py) has no key for either, so the
    recursive log redactor would not mask them. See mapping.py's module docstring, decision D5.
    """
    if mode != "stub":
        async with httpx.AsyncClient(timeout=timeout_seconds) as http_client:
            response = await http_client.get(
                f"{base_url}/abha/api/v3/profile/account",
                headers=abdm_headers(gateway_token=gateway_token, x_token=x_token),
            )
        return classify_get_profile(response.status_code, response.json())

    return classify_get_profile(200, _stub_profile())
