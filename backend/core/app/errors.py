"""Error taxonomy and RFC 9457 Problem Details rendering.

Every non-2xx response in this service has one shape, defined here. See
docs/backend/api-contract.md sections 0.6 and 9.1, which this module implements literally.

Three rules that make the taxonomy worth having:

1. Every error response carries a request_id, and that same request_id appears on the audit row
   and on every log line for the request.
2. `detail` never contains PHI. It describes the failure, not the data that caused it.
3. Upstream errors are translated, not leaked. A raw upstream body never reaches the client.

Codes are permanent. A code is never reused for a different meaning and never renumbered,
because Android branches on the string and old app builds stay in the field for years.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any

PROBLEM_CONTENT_TYPE = "application/problem+json"
ERROR_TYPE_BASE = "https://samd.example.com/errors/"


class ErrorCode(StrEnum):
    """The complete registry. Mirrors api-contract.md section 9.1 exactly."""

    # AUTH-1xxx
    AUTH_INVALID_CREDENTIALS = "SAMD-AUTH-1001"
    AUTH_TOKEN_EXPIRED = "SAMD-AUTH-1002"
    AUTH_TOKEN_INVALID = "SAMD-AUTH-1003"
    AUTH_REFRESH_REVOKED = "SAMD-AUTH-1004"
    AUTH_ROLE_FORBIDDEN = "SAMD-AUTH-1005"
    AUTH_ACCOUNT_DISABLED = "SAMD-AUTH-1006"
    AUTH_DEVICE_MISMATCH = "SAMD-AUTH-1007"
    AUTH_PIN_CHANGE_REQUIRED = "SAMD-AUTH-1008"

    # ABHA-2xxx
    ABHA_SESSION_NOT_FOUND = "SAMD-ABHA-2001"
    ABHA_INVALID_STATE = "SAMD-ABHA-2002"
    ABHA_SESSION_EXPIRED = "SAMD-ABHA-2003"
    ABHA_OTP_INCORRECT = "SAMD-ABHA-2004"
    ABHA_OTP_EXPIRED = "SAMD-ABHA-2005"
    ABHA_UPSTREAM_ERROR = "SAMD-ABHA-2006"
    ABHA_UPSTREAM_TIMEOUT = "SAMD-ABHA-2007"
    ABHA_NOT_CONFIGURED = "SAMD-ABHA-2008"
    ABHA_ALREADY_LINKED = "SAMD-ABHA-2009"

    # PAT-3xxx
    PAT_NOT_FOUND = "SAMD-PAT-3001"
    PAT_ID_CONFLICT = "SAMD-PAT-3002"
    PAT_VALIDATION_FAILED = "SAMD-PAT-3003"
    PAT_DUPLICATE_ABHA = "SAMD-PAT-3004"
    PAT_IMMUTABLE_FIELD = "SAMD-PAT-3005"

    # ENC-4xxx
    ENC_ENCOUNTER_NOT_FOUND = "SAMD-ENC-4001"
    ENC_CASE_NOT_FOUND = "SAMD-ENC-4002"
    ENC_ILLEGAL_TRANSITION = "SAMD-ENC-4003"
    ENC_PATIENT_MISMATCH = "SAMD-ENC-4004"
    ENC_CONSULTATION_NOT_FOUND = "SAMD-ENC-4005"

    # KERN-5xxx
    KERN_UNREACHABLE = "SAMD-KERN-5001"
    KERN_TIMEOUT = "SAMD-KERN-5002"
    KERN_PAYLOAD_REJECTED = "SAMD-KERN-5003"
    KERN_MALFORMED_RESPONSE = "SAMD-KERN-5004"
    KERN_IDENTITY_LEAK_BLOCKED = "SAMD-KERN-5005"
    # Added in Phase 3. 5001-5005 were already registered in Phase 1 with the meanings above;
    # this module's own rule is that a code is never reused for a different meaning, so these two
    # new failure modes (an internal kernel error distinct from an unreachable kernel, and the
    # circuit breaker) get the next free numbers rather than overload an existing one.
    KERN_INTERNAL_ERROR = "SAMD-KERN-5007"
    KERN_CIRCUIT_OPEN = "SAMD-KERN-5006"

    # SYNC-6xxx
    SYNC_BATCH_TOO_LARGE = "SAMD-SYNC-6001"
    SYNC_UNKNOWN_TABLE = "SAMD-SYNC-6002"
    SYNC_RECORD_INVALID = "SAMD-SYNC-6003"
    SYNC_VERSION_CONFLICT = "SAMD-SYNC-6004"
    SYNC_DEVICE_MISMATCH = "SAMD-SYNC-6005"
    SYNC_FORBIDDEN_FIELD = "SAMD-SYNC-6006"

    # AUDIT-7xxx
    AUDIT_CHAIN_BROKEN = "SAMD-AUDIT-7001"
    AUDIT_MUTATION_REJECTED = "SAMD-AUDIT-7002"
    AUDIT_RANGE_TOO_WIDE = "SAMD-AUDIT-7003"

    # SYS-9xxx
    SYS_MALFORMED_REQUEST = "SAMD-SYS-9001"
    SYS_UNSUPPORTED_MEDIA_TYPE = "SAMD-SYS-9002"
    SYS_RATE_LIMITED = "SAMD-SYS-9003"
    SYS_DATABASE_UNAVAILABLE = "SAMD-SYS-9004"
    SYS_INTERNAL = "SAMD-SYS-9005"
    SYS_NOT_FOUND = "SAMD-SYS-9006"
    SYS_METHOD_NOT_ALLOWED = "SAMD-SYS-9007"


# code -> (http status, human-readable title). Title is human-facing and may be reworded without
# a contract change; Android branches on the code, never on this.
_REGISTRY: dict[ErrorCode, tuple[int, str]] = {
    ErrorCode.AUTH_INVALID_CREDENTIALS: (401, "Invalid credentials"),
    ErrorCode.AUTH_TOKEN_EXPIRED: (401, "Token expired"),
    ErrorCode.AUTH_TOKEN_INVALID: (401, "Token missing, malformed, or invalid"),
    ErrorCode.AUTH_REFRESH_REVOKED: (401, "Refresh token revoked"),
    ErrorCode.AUTH_ROLE_FORBIDDEN: (403, "Role not permitted"),
    ErrorCode.AUTH_ACCOUNT_DISABLED: (403, "Account disabled"),
    ErrorCode.AUTH_DEVICE_MISMATCH: (403, "Device not bound to this token"),
    ErrorCode.AUTH_PIN_CHANGE_REQUIRED: (403, "PIN change required"),
    ErrorCode.ABHA_SESSION_NOT_FOUND: (404, "ABHA session not found"),
    ErrorCode.ABHA_INVALID_STATE: (409, "Invalid ABHA session state transition"),
    ErrorCode.ABHA_SESSION_EXPIRED: (410, "ABHA session expired"),
    ErrorCode.ABHA_OTP_INCORRECT: (401, "OTP incorrect"),
    ErrorCode.ABHA_OTP_EXPIRED: (410, "OTP expired"),
    ErrorCode.ABHA_UPSTREAM_ERROR: (502, "ABDM upstream error"),
    ErrorCode.ABHA_UPSTREAM_TIMEOUT: (504, "ABDM upstream timeout"),
    ErrorCode.ABHA_NOT_CONFIGURED: (503, "ABDM not configured for this deployment"),
    ErrorCode.ABHA_ALREADY_LINKED: (409, "ABHA number already linked to a different patient"),
    ErrorCode.PAT_NOT_FOUND: (404, "Patient not found"),
    ErrorCode.PAT_ID_CONFLICT: (409, "Identifier already exists with a different payload"),
    ErrorCode.PAT_VALIDATION_FAILED: (422, "Validation failed"),
    ErrorCode.PAT_DUPLICATE_ABHA: (409, "Duplicate ABHA number"),
    ErrorCode.PAT_IMMUTABLE_FIELD: (422, "Immutable field cannot be modified"),
    ErrorCode.ENC_ENCOUNTER_NOT_FOUND: (404, "Encounter not found"),
    ErrorCode.ENC_CASE_NOT_FOUND: (404, "Case record not found"),
    ErrorCode.ENC_ILLEGAL_TRANSITION: (409, "Illegal case status transition"),
    ErrorCode.ENC_PATIENT_MISMATCH: (422, "Encounter and patient do not match"),
    ErrorCode.ENC_CONSULTATION_NOT_FOUND: (404, "Consultation not found"),
    ErrorCode.KERN_UNREACHABLE: (502, "Clinical kernel unreachable"),
    ErrorCode.KERN_TIMEOUT: (504, "Clinical kernel timeout"),
    ErrorCode.KERN_PAYLOAD_REJECTED: (422, "Clinical kernel rejected the payload"),
    ErrorCode.KERN_MALFORMED_RESPONSE: (502, "Clinical kernel returned an unparseable response"),
    ErrorCode.KERN_IDENTITY_LEAK_BLOCKED: (422, "Identity field detected on the kernel boundary"),
    ErrorCode.KERN_CIRCUIT_OPEN: (503, "Clinical kernel circuit breaker is open"),
    ErrorCode.KERN_INTERNAL_ERROR: (502, "Clinical kernel returned an internal error"),
    ErrorCode.SYNC_BATCH_TOO_LARGE: (413, "Sync batch too large"),
    ErrorCode.SYNC_UNKNOWN_TABLE: (422, "Unknown table in sync batch"),
    # 6003 is a per-record result inside results[], never an HTTP status. The 422 here is only
    # used if it ever escapes as a whole-request failure, which would itself be a defect.
    ErrorCode.SYNC_RECORD_INVALID: (422, "Sync record validation failed"),
    ErrorCode.SYNC_VERSION_CONFLICT: (409, "Version conflict"),
    ErrorCode.SYNC_DEVICE_MISMATCH: (403, "Device does not match the token"),
    ErrorCode.SYNC_FORBIDDEN_FIELD: (422, "Forbidden field present"),
    ErrorCode.AUDIT_CHAIN_BROKEN: (500, "Audit chain integrity failure"),
    ErrorCode.AUDIT_MUTATION_REJECTED: (403, "Audit records cannot be modified"),
    ErrorCode.AUDIT_RANGE_TOO_WIDE: (422, "Audit query range too wide"),
    ErrorCode.SYS_MALFORMED_REQUEST: (400, "Malformed request"),
    ErrorCode.SYS_UNSUPPORTED_MEDIA_TYPE: (415, "Unsupported media type"),
    ErrorCode.SYS_RATE_LIMITED: (429, "Rate limit exceeded"),
    ErrorCode.SYS_DATABASE_UNAVAILABLE: (503, "Database unavailable"),
    ErrorCode.SYS_INTERNAL: (500, "Internal server error"),
    ErrorCode.SYS_NOT_FOUND: (404, "Not found"),
    ErrorCode.SYS_METHOD_NOT_ALLOWED: (405, "Method not allowed"),
}


def http_status_for(code: ErrorCode) -> int:
    return _REGISTRY[code][0]


def title_for(code: ErrorCode) -> str:
    return _REGISTRY[code][1]


class SamdError(Exception):
    """The only exception this service raises deliberately.

    Anything else reaching the handler is a bug and becomes SAMD-SYS-9005 with no detail, so an
    unexpected stack trace can never leak internals or PHI to a field device.
    """

    def __init__(
        self,
        code: ErrorCode,
        detail: str | None = None,
        *,
        errors: list[dict[str, str]] | None = None,
        headers: dict[str, str] | None = None,
        log_context: dict[str, Any] | None = None,
    ) -> None:
        self.code = code
        self.status = http_status_for(code)
        self.title = title_for(code)
        self.detail = detail or self.title
        self.errors = errors
        self.headers = headers
        # Never rendered into the response. Attached to the log line only, for the operator.
        self.log_context = log_context or {}
        super().__init__(f"{code}: {self.detail}")


def problem_body(
    code: ErrorCode,
    *,
    detail: str,
    instance: str,
    request_id: str,
    timestamp: str,
    errors: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Build the RFC 9457 body. Extension members: code, request_id, timestamp, errors."""
    body: dict[str, Any] = {
        "type": f"{ERROR_TYPE_BASE}{code.value}",
        "title": title_for(code),
        "status": http_status_for(code),
        "detail": detail,
        "instance": instance,
        "code": code.value,
        "request_id": request_id,
        "timestamp": timestamp,
    }
    # Absent, not null, when there are no field errors.
    if errors:
        body["errors"] = errors
    return body
