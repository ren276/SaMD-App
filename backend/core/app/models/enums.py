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
    PATIENT_REGISTERED = "patient_registered"
    PATIENT_CREATE_REPLAYED = "patient_create_replayed"
    PATIENT_UPDATED = "patient_updated"
    ENCOUNTER_CREATED = "encounter_created"
    ENCOUNTER_READ = "encounter_read"
    CASE_STATUS_CHANGED = "case_status_changed"
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


# ---------------------------------------------------------------------------
# Clinical vocabularies mirrored from the Android domain models.
#
# Every value below matches a Kotlin enum constant name exactly, so no mapping
# table is needed on either side of the wire. Stored as VARCHAR with a CHECK
# constraint rather than a native PostgreSQL ENUM: adding a value to a native
# enum is a migration with a lock, and these vocabularies will grow.
# ---------------------------------------------------------------------------


class CaseStatus(StrEnum):
    """domain/model/CaseRecord.kt.

    PENDING_SYNC is accepted from the client: the device legitimately writes it while offline
    (CaseRecordRepository.assignDoctor(isOnline)) and the row reaches the server carrying it.
    """

    DRAFT = "DRAFT"
    SAVED_LOCALLY = "SAVED_LOCALLY"
    PENDING_SYNC = "PENDING_SYNC"
    SENT_TO_DOCTOR = "SENT_TO_DOCTOR"
    PRESCRIPTION_RECEIVED = "PRESCRIPTION_RECEIVED"
    ABANDONED = "ABANDONED"


class MeasurementType(StrEnum):
    MEASURABLE = "MEASURABLE"
    NON_MEASURABLE = "NON_MEASURABLE"


class Visibility(StrEnum):
    """PRIVATE hides an ailment from the worker-facing projection only (REQ-AIL-02).

    PRIVATE rows and their clinical text DO cross this boundary. Only the audio file is
    device-local (REQ-AIL-03). Getting this backwards in either direction is a defect.
    """

    PUBLIC = "PUBLIC"
    PRIVATE = "PRIVATE"


class ObservationType(StrEnum):
    PULSE = "PULSE"
    BP_SYSTOLIC = "BP_SYSTOLIC"
    BP_DIASTOLIC = "BP_DIASTOLIC"
    SPO2 = "SPO2"
    TEMPERATURE = "TEMPERATURE"
    RESPIRATORY_RATE = "RESPIRATORY_RATE"
    WEIGHT = "WEIGHT"
    HEIGHT = "HEIGHT"
    BMI = "BMI"
    BLOOD_GLUCOSE = "BLOOD_GLUCOSE"
    PAIN_SCORE = "PAIN_SCORE"
    URINALYSIS = "URINALYSIS"


class ObservationSource(StrEnum):
    MANUAL = "MANUAL"
    DEVICE = "DEVICE"


class VitalsCaptureMethod(StrEnum):
    MANUAL_CUFF = "MANUAL_CUFF"
    DIGITAL_MONITOR = "DIGITAL_MONITOR"
    PULSE_OXIMETER = "PULSE_OXIMETER"
    THERMOMETER = "THERMOMETER"
    OTHER = "OTHER"


class AttachmentType(StrEnum):
    IMAGE = "IMAGE"
    VIDEO = "VIDEO"
    AUDIO = "AUDIO"
    AFFECTED_AREA_PHOTO = "AFFECTED_AREA_PHOTO"


class MedicalHistoryCategory(StrEnum):
    CHRONIC_CONDITION = "CHRONIC_CONDITION"
    SURGERY = "SURGERY"
    HOSPITALIZATION = "HOSPITALIZATION"


class AllergyCategory(StrEnum):
    DRUG = "DRUG"
    FOOD = "FOOD"
    ENVIRONMENTAL = "ENVIRONMENTAL"


class MedicationKind(StrEnum):
    MEDICATION = "MEDICATION"
    SUPPLEMENT = "SUPPLEMENT"


class RiskCategory(StrEnum):
    LOW = "LOW"
    MODERATE = "MODERATE"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class UrgencyLevel(StrEnum):
    ROUTINE = "ROUTINE"
    URGENT = "URGENT"
    EMERGENCY = "EMERGENCY"


class InferenceSource(StrEnum):
    """REQ-HAN-08. Which path produced a /v1/assess result.

    Stored server side so the mock-fallback rate becomes a queryable metric for the first time.
    """

    REAL_INFERENCE = "REAL_INFERENCE"
    MOCK_FALLBACK = "MOCK_FALLBACK"


class PhysicianDecision(StrEnum):
    AGREE = "AGREE"
    MODIFY = "MODIFY"
    REJECT = "REJECT"


class KernelDecision(StrEnum):
    AGREE = "AGREE"
    MODIFY = "MODIFY"
    REJECT = "REJECT"


class ReferralStatus(StrEnum):
    QUEUED = "QUEUED"
    SENT = "SENT"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    CANCELLED = "CANCELLED"


class SyncState(StrEnum):
    """Server-side state of a synced row.

    ponytail: RECEIVED is the only value written today. CONFLICT is reserved for Phase 4, where a
    push whose base_version does not match parks the row for worker resolution. If a third state
    is ever needed, this is a CHECK constraint edit, not a type migration.
    """

    RECEIVED = "RECEIVED"
    CONFLICT = "CONFLICT"


class BlobStatus(StrEnum):
    """Attachment binary transfer state. Always NOT_UPLOADED in v1; object storage is out of
    scope (api-contract.md section 10)."""

    NOT_UPLOADED = "NOT_UPLOADED"
    UPLOADED = "UPLOADED"


class KernelEndpoint(StrEnum):
    """Which of the two kernel routes a kernel_call_log row is about."""

    ASSESS = "ASSESS"
    EVALUATE = "EVALUATE"


class KernelCallOutcome(StrEnum):
    """kernel_call_log.outcome. Finer grained than the minimum the Phase 3 brief asked for
    (it named SUCCESS, TIMEOUT, KERNEL_ERROR, CIRCUIT_OPEN, PHI_REJECTED as examples), because an
    operator debugging "is the kernel unreachable" versus "is the kernel's process erroring" is
    exactly the traceability G-3 exists to provide, and folding both into one KERNEL_ERROR bucket
    would throw that distinction away at the one place it is cheap to keep."""

    SUCCESS = "SUCCESS"
    TIMEOUT = "TIMEOUT"
    UNREACHABLE = "UNREACHABLE"
    KERNEL_ERROR = "KERNEL_ERROR"
    PAYLOAD_REJECTED = "PAYLOAD_REJECTED"
    MALFORMED_RESPONSE = "MALFORMED_RESPONSE"
    CIRCUIT_OPEN = "CIRCUIT_OPEN"
    PHI_REJECTED = "PHI_REJECTED"
