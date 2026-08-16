"""Enumerations shared across models and schemas.

Wire values are SCREAMING_SNAKE_CASE and match the Kotlin enum constant names exactly, so no
mapping table is needed on either side. Stored as VARCHAR with a CHECK constraint rather than a
native PostgreSQL ENUM type: adding a value to a native enum is a migration with a lock, and
this vocabulary will grow.
"""

from __future__ import annotations

from enum import StrEnum


class UserRole(StrEnum):
    """Mirrors app/src/main/java/com/example/samdapp/domain/auth/AuthSession.kt.

    DOCTOR does not exist in the Android enum yet (decision D-2 adds it in Phase 6). It is
    defined here now so accounts can be provisioned and the authorization matrix is complete.
    """

    ASHA_WORKER = "ASHA_WORKER"
    NURSE = "NURSE"
    COMPOUNDER = "COMPOUNDER"
    DOCTOR = "DOCTOR"


class AuditOrigin(StrEnum):
    DEVICE = "DEVICE"
    SERVER = "SERVER"


class AbhaTransactionState(StrEnum):
    """State machine from "ABHA planning/abha-integration-plan.md".

    Enforced server side in Phase 5. An out-of-order call is SAMD-ABHA-2002, never a silent
    no-op.
    """

    STARTED = "STARTED"
    IDENTITY_SUBMITTED = "IDENTITY_SUBMITTED"
    OTP_REQUESTED = "OTP_REQUESTED"
    OTP_VERIFIED = "OTP_VERIFIED"
    ENROLLED = "ENROLLED"
    MOBILE_VERIFICATION_REQUIRED = "MOBILE_VERIFICATION_REQUIRED"
    MOBILE_VERIFIED = "MOBILE_VERIFIED"
    PROFILE_RETRIEVED = "PROFILE_RETRIEVED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"


class AbhaTransactionKind(StrEnum):
    """Registration (create a new ABHA) versus verification (log in to an existing one)."""

    REGISTRATION = "REGISTRATION"
    VERIFICATION = "VERIFICATION"


class AuditAction(StrEnum):
    """Server-origin audit actions.

    Device-origin actions arrive through sync push and are validated against the Android
    AuditAction vocabulary (domain/audit/AuditLogger.kt) in Phase 4. An unrecognised action is
    rejected rather than silently stored, so the vocabulary cannot rot.
    """

    WORKER_LOGIN_SUCCEEDED = "worker_login_succeeded"
    WORKER_LOGIN_FAILED = "worker_login_failed"
    WORKER_LOGOUT = "worker_logout"
    WORKER_PIN_CHANGED = "worker_pin_changed"
    TOKEN_REFRESHED = "token_refreshed"
    REFRESH_REUSE_DETECTED = "refresh_reuse_detected"
    PATIENT_RECORD_READ = "patient_record_read"
    AUDIT_LOG_READ = "audit_log_read"
    KERNEL_CALL_FORWARDED = "kernel_call_forwarded"
    KERNEL_CALL_FAILED = "kernel_call_failed"
    SYNC_BATCH_RECEIVED = "sync_batch_received"
    SYNC_RECORD_REJECTED = "sync_record_rejected"
    ABHA_SESSION_STARTED = "abha_session_started"
    ABHA_SESSION_FAILED = "abha_session_failed"
    ABHA_IDENTITY_LINKED = "abha_identity_linked"
    # Generic fallback written by the audit middleware for a mutating request whose handler did
    # not declare a specific action. Its presence in the log is a hint that the handler should.
    REQUEST_COMPLETED = "request_completed"
