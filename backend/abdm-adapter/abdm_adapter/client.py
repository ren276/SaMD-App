"""ABDM-facing calls for the P0 slice. Live-vs-stub switch lives here and only here: every other
module in this package is agnostic to `ABDM_MODE`.

`ABDM_MODE=stub` replays the real Postman example bodies (Phase A contract doc) through the full
adapter and state machine; no network call happens. `ABDM_MODE=live` is not implemented beyond a
loud `NotImplementedError`, per the brief: this session builds nothing that could accidentally
make a real call.

The stub's branching on plaintext OTP values (`"111111"`/`"222222"` below) exists only so tests can
drive every classified outcome deterministically; it has no bearing on live behaviour and is not a
credential or a secret.
"""

from __future__ import annotations

import uuid
from typing import Any

from abdm_adapter.errors import (
    AbdmResult,
    classify_generic_enrollment_error,
    classify_get_profile,
    classify_otp_verify,
)

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


async def fetch_gateway_session_token(*, mode: str) -> str:
    """POST .../gateway/v3/sessions. Only needed for the cert fetch in this P0 slice (Phase A
    finding: the four enrollment/* calls carry no Authorization header in any recorded example)."""
    if mode == "stub":
        return "stub-gateway-session-token"
    raise NotImplementedError(
        "ABDM_MODE=live session token fetch is not implemented this session; see PROGRESS.md."
    )


async def send_otp(
    *,
    mode: str,
    txn_id: str,
    scope: list[str],
    login_hint: str,
    encrypted_login_id: str,
    otp_system: str,
) -> AbdmResult:
    """POST enrollment/request/otp. Same endpoint for both the initial Aadhaar-OTP request and the
    mobile-update OTP request; only `scope`/`login_hint`/`otp_system` differ between the two call
    sites (service.py), matching the two recorded Postman requests exactly."""
    if mode != "stub":
        raise NotImplementedError("ABDM_MODE=live is not implemented this session.")
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
    txn_id: str,
    otp_plain: str,
    encrypted_otp: str,
    communication_mobile: str,
    consent_code: str,
    consent_version: str,
) -> AbdmResult:
    """POST enrollment/enrol/byAadhaar. `otp_plain` is stub-only, used to pick which recorded
    example to replay; never sent anywhere and never logged (see service.py)."""
    if mode != "stub":
        raise NotImplementedError("ABDM_MODE=live is not implemented this session.")

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


async def get_profile(*, mode: str, x_token: str) -> AbdmResult:
    """GET profile/account, authenticated with the per-transaction X-token from enrol_by_aadhaar's
    `tokens.token` (or verify_mobile_otp's `token`), never the gateway session token."""
    if mode != "stub":
        raise NotImplementedError("ABDM_MODE=live is not implemented this session.")
    return classify_get_profile(200, _stub_profile())
