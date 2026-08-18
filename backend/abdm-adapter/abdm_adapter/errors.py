"""Per-endpoint success classification and ABDM-to-SAMD error mapping.

D2, the single most important correctness rule in this adapter: an ABDM response is never
classified by HTTP status alone. `enrollment/auth/byAbdm` (mobile OTP verify) returns HTTP 200 for
both a correct and an incorrect OTP, discriminated only by `body["authResult"]`. A status-only
check would silently advance the state machine on a real failure. Every classifier below reads the
body first.

Also confirmed in Phase A: ABDM does not use one error envelope across these endpoints.
`send_otp`/`enrol_by_aadhaar` use a structured `{"error": {"code": ..., "message": ...}}` shape on
some failures (401, 422, 500) and a flat, field-keyed shape with no `code` at all on others (400:
`{"scope": "Invalid Scope", "timestamp": ...}`). `classify_generic_enrollment_error` below handles
both without assuming either is the only one.

No retry logic anywhere in this module or in `client.py`. "No blind retries" (the brief, and the
same rule `app/adapters/kernel/client.py` already follows for the kernel proxy) means this adapter
never re-sends a failed ABDM call itself; retryable vs non-retryable vs user-action is recorded on
the mapped result for the caller (the device, through the app) to act on, not acted on here.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from app.errors import ErrorCode


class RetryClass(StrEnum):
    """What the caller should do about a failure. Advisory only; nothing in this package retries."""

    RETRYABLE = "retryable"  # network/timeout talking to ABDM
    NON_RETRYABLE = (
        "non_retryable"  # invalid OTP/scope/identity; resending the same input fails again
    )
    USER_ACTION = "user_action"  # OTP expired; the worker must request a new one


@dataclass(frozen=True)
class AbdmResult:
    """One classified outcome from one ABDM call. `body` is the parsed response, kept only long
    enough for the caller to pull the specific fields it needs (txn_id, tokens, profile fields);
    callers must not persist or log `body` wholesale, see the photo/token handling in service.py.
    """

    ok: bool
    body: dict[str, Any]
    error_code: ErrorCode | None = None
    # External ABDM code/message, safe to put in `detail` (errors.py's own rule elsewhere in this
    # codebase: upstream errors are translated, not leaked, but the code and message themselves,
    # as opposed to a raw body, are not PHI and are useful for support).
    external_code: str | None = None
    external_message: str | None = None
    retry_class: RetryClass | None = None


def _structured_error(body: dict[str, Any]) -> tuple[str, str] | None:
    error = body.get("error")
    if isinstance(error, dict) and isinstance(error.get("code"), str):
        return error["code"], str(error.get("message", ""))
    if isinstance(body.get("code"), str) and isinstance(body.get("message"), str):
        return body["code"], body["message"]
    return None


def _flat_field_error(body: dict[str, Any]) -> tuple[str, str] | None:
    """The `{"scope": "Invalid Scope", "timestamp": "..."}` shape: exactly one key besides
    `timestamp`, its value a message string. Returns (field_name, message) if the body matches."""
    candidates = {k: v for k, v in body.items() if k != "timestamp"}
    if len(candidates) == 1:
        ((field, message),) = candidates.items()
        if isinstance(message, str):
            return field, message
    return None


def classify_generic_enrollment_error(status_code: int, body: dict[str, Any]) -> AbdmResult:
    """For `enrollment/request/otp` and `enrollment/enrol/byAadhaar`: success is HTTP 200 with no
    error shape present. Both endpoints only fail with a real non-200 status in every recorded
    example (Phase A); neither uses an `authResult` body field.
    """
    if status_code == 200 and _structured_error(body) is None and _flat_field_error(body) is None:
        return AbdmResult(ok=True, body=body)

    structured = _structured_error(body)
    if structured is not None:
        code, message = structured
        return AbdmResult(
            ok=False,
            body=body,
            error_code=_map_structured_code(code, status_code),
            external_code=code,
            external_message=message[:500],
            retry_class=_retry_class_for(status_code, code),
        )

    flat = _flat_field_error(body)
    if flat is not None:
        field, message = flat
        # A flat "Invalid Scope"/"Invalid LoginId"/etc error means this adapter sent ABDM a
        # malformed request. That is our own bug, not a device/worker-facing condition, but it
        # must still resolve to a real error code rather than crash the request.
        return AbdmResult(
            ok=False,
            body=body,
            error_code=ErrorCode.ABHA_UPSTREAM_ERROR,
            external_code=f"field:{field}",
            external_message=message[:500],
            retry_class=RetryClass.NON_RETRYABLE,
        )

    return AbdmResult(
        ok=False,
        body=body,
        error_code=ErrorCode.ABHA_UPSTREAM_ERROR,
        external_code=f"http_{status_code}",
        external_message="Unrecognised ABDM response shape.",
        retry_class=RetryClass.NON_RETRYABLE,
    )


def classify_otp_verify(status_code: int, body: dict[str, Any]) -> AbdmResult:
    """For `enrollment/auth/byAbdm` (mobile OTP verify): D2. HTTP status is always 200 for both a
    correct and an incorrect OTP; the discriminator is `body["authResult"]`. A non-200 status here
    still means a real transport/upstream failure and is classified the same as the generic case.
    """
    if status_code != 200:
        return classify_generic_enrollment_error(status_code, body)

    auth_result = body.get("authResult")
    if auth_result == "success":
        return AbdmResult(ok=True, body=body)

    if auth_result == "failed":
        message = str(body.get("message", ""))
        if "expired" in message.lower():
            return AbdmResult(
                ok=False,
                body=body,
                error_code=ErrorCode.ABHA_OTP_EXPIRED,
                external_code="authResult:failed",
                external_message=message[:500],
                retry_class=RetryClass.USER_ACTION,
            )
        return AbdmResult(
            ok=False,
            body=body,
            error_code=ErrorCode.ABHA_OTP_INCORRECT,
            external_code="authResult:failed",
            external_message=message[:500],
            retry_class=RetryClass.NON_RETRYABLE,
        )

    # Neither "success" nor "failed": an ABDM response shape this adapter does not recognise.
    # Treated as a failure, never silently advanced (the D2 rule again, for the unknown case too).
    return AbdmResult(
        ok=False,
        body=body,
        error_code=ErrorCode.ABHA_UPSTREAM_ERROR,
        external_code="authResult:unrecognised",
        external_message=f"authResult={auth_result!r}",
        retry_class=RetryClass.NON_RETRYABLE,
    )


def classify_get_profile(status_code: int, body: dict[str, Any]) -> AbdmResult:
    """For `profile/account`: success is HTTP 200 with an `ABHANumber` field. Failures in the
    recorded examples are all structured (`{"code": ..., "message": ...}` at the top level, not
    nested under `"error"`, or `{"message": ..., "timestamp": ...}` for the X-token cases) or
    genuinely non-200.
    """
    if status_code == 200 and isinstance(body.get("ABHANumber"), str):
        return AbdmResult(ok=True, body=body)
    return classify_generic_enrollment_error(status_code, body)


def _map_structured_code(external_code: str, status_code: int) -> ErrorCode:
    if external_code == "ABDM-1204":
        return ErrorCode.ABHA_OTP_INCORRECT
    if status_code in (401, 403):
        return ErrorCode.ABHA_UPSTREAM_ERROR
    if status_code >= 500:
        return ErrorCode.ABHA_UPSTREAM_ERROR
    return ErrorCode.ABHA_UPSTREAM_ERROR


def _retry_class_for(status_code: int, external_code: str) -> RetryClass:
    if status_code >= 500:
        return RetryClass.RETRYABLE
    if external_code == "ABDM-1204":
        return RetryClass.NON_RETRYABLE
    return RetryClass.NON_RETRYABLE
