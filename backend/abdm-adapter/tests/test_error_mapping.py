"""Per-endpoint success classification (D2) and ABDM error mapping. Every body here is either the
literal recorded Postman example (Phase A contract doc) or the exact brief-confirmed example for
D2, not an invented shape.
"""

from __future__ import annotations

from app.errors import ErrorCode

from abdm_adapter.errors import (
    RetryClass,
    classify_generic_enrollment_error,
    classify_get_profile,
    classify_otp_verify,
)


def test_send_otp_positive_flow() -> None:
    result = classify_generic_enrollment_error(
        200,
        {
            "txnId": "37d8d312-35a0-41e7-a6e4-1074eb18a5fa",
            "message": "OTP sent to Aadhaar registered mobile number ending with ******0903",
        },
    )
    assert result.ok is True


def test_send_otp_flat_field_error_invalid_scope() -> None:
    result = classify_generic_enrollment_error(
        400, {"scope": "Invalid Scope", "timestamp": "2024-05-10 11:13:04"}
    )
    assert result.ok is False
    assert result.error_code == ErrorCode.ABHA_UPSTREAM_ERROR
    assert result.external_code == "field:scope"
    assert result.retry_class == RetryClass.NON_RETRYABLE


def test_send_otp_invalid_credentials_structured_error() -> None:
    result = classify_generic_enrollment_error(
        401,
        {
            "code": "900901",
            "message": "Invalid Credentials",
            "description": "Invalid JWT token. Make sure you have provided the correct security "
            "credentials",
        },
    )
    assert result.ok is False
    assert result.external_code == "900901"


def test_enrol_by_aadhaar_positive_flow() -> None:
    result = classify_generic_enrollment_error(
        200,
        {
            "message": "Account created successfully",
            "txnId": "b89ec10d-71fa-4280-83b3-1fedad66b5f5",
            "tokens": {"token": "eyJ...", "expiresIn": 1800},
            "ABHAProfile": {"ABHANumber": "91-7561-4088-XXXX"},
            "isNew": True,
        },
    )
    assert result.ok is True


def test_enrol_by_aadhaar_invalid_otp_is_422_structured() -> None:
    result = classify_generic_enrollment_error(
        422,
        {
            "error": {
                "code": "ABDM-1204",
                "message": "UIDAI Error code : 400 : Invalid Aadhaar OTP value.",
            }
        },
    )
    assert result.ok is False
    assert result.error_code == ErrorCode.ABHA_OTP_INCORRECT
    assert result.external_code == "ABDM-1204"


def test_d2_a_200_with_authresult_failed_is_not_success() -> None:
    """The exact case the brief named as the single most important correctness rule: HTTP 200,
    but authResult == "failed" must classify as a failure, never as success."""
    result = classify_otp_verify(
        200,
        {
            "txnId": "9c5d453e-756f-45e1-9766-43b9cc1190de",
            "authResult": "failed",
            "message": "OTP expired, please try again",
            "accounts": [],
        },
    )
    assert result.ok is False
    assert result.error_code == ErrorCode.ABHA_OTP_EXPIRED
    assert result.retry_class == RetryClass.USER_ACTION


def test_d2_authresult_failed_incorrect_otp_maps_to_otp_incorrect() -> None:
    result = classify_otp_verify(
        200,
        {
            "txnId": "x",
            "authResult": "failed",
            "message": "Incorrect OTP, please try again",
            "accounts": [],
        },
    )
    assert result.ok is False
    assert result.error_code == ErrorCode.ABHA_OTP_INCORRECT
    assert result.retry_class == RetryClass.NON_RETRYABLE


def test_d2_authresult_success_is_success() -> None:
    result = classify_otp_verify(
        200,
        {
            "txnId": "23acf181-339d-4771-b532-5c5df4a28d19",
            "authResult": "success",
            "message": "Mobile number is now successfully linked to your Account",
            "token": "eyJ...",
            "expiresIn": 300,
            "accounts": [
                {
                    "ABHANumber": "91-2568-7073-XXXX",
                    "dob": "07-03-1997",
                    "gender": "F",
                    "kycVerified": True,
                }
            ],
        },
    )
    assert result.ok is True


def test_otp_verify_non_200_falls_back_to_generic_classification() -> None:
    result = classify_otp_verify(
        500, {"code": "900900", "message": "Unclassified Authentication Failure"}
    )
    assert result.ok is False
    assert result.retry_class == RetryClass.RETRYABLE


def test_get_profile_positive_flow() -> None:
    result = classify_get_profile(
        200, {"ABHANumber": "91-7561-4088-XXXX", "preferredAbhaAddress": "x@sbx"}
    )
    assert result.ok is True


def test_get_profile_x_token_expired() -> None:
    result = classify_get_profile(
        401, {"message": "X-token expired", "timestamp": "2024-05-10 14:51:16"}
    )
    assert result.ok is False
    assert result.external_code == "field:message"
