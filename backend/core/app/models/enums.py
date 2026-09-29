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
    # SLM readback proxy (PR-4). Server-origin, like the kernel pair above and unlike the four
    # slm_readback_* device actions in app/domain/audit_actions_device.py, which the DEVICE emits
    # about its own seam and which arrive through sync push. These two are about the network hop
    # this backend makes, and app/services/sync.py's import-time guard would reject them if the
    # two vocabularies ever overlapped.
    SLM_CALL_FORWARDED = "slm_call_forwarded"
    SLM_CALL_FAILED = "slm_call_failed"
    SYNC_BATCH_RECEIVED = "sync_batch_received"
    SYNC_RECORD_REJECTED = "sync_record_rejected"
    ABHA_SESSION_STARTED = "abha_session_started"
    ABHA_SESSION_FAILED = "abha_session_failed"
    ABHA_IDENTITY_LINKED = "abha_identity_linked"
    # Phase 5 Phase B (D7): the intermediate state-machine successes. Without these the chain
    # only had start/failed/linked, a gap exactly where a wrong-patient linkage would need to be
    # reconstructed from. backend-prd.md section 6.2 is the source list; these mirror it exactly.
    ABHA_IDENTITY_SUBMITTED = "abha_identity_submitted"
    ABHA_OTP_VERIFIED = "abha_otp_verified"
    ABHA_ENROLLED = "abha_enrolled"
    ABHA_MOBILE_VERIFIED = "abha_mobile_verified"
    ABHA_PROFILE_RETRIEVED = "abha_profile_retrieved"
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

    UNAVAILABLE mirrors Android's InferenceSource.kt (H-09 kernel-mock production safety fix,
    2026-08-20): staging/prod's honest failure state when the real call failed and no fallback
    scenario was produced. Added here by migration 0006, which widens
    ck_kernel_reports_inference_source to match; see that migration's docstring for the sync
    rejection this closes.
    """

    REAL_INFERENCE = "REAL_INFERENCE"
    MOCK_FALLBACK = "MOCK_FALLBACK"
    UNAVAILABLE = "UNAVAILABLE"


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


class SyncRetryClass(StrEnum):
    """Whether a device may resend a rejected sync record unchanged. Carried on every `rejected`
    result of `POST /api/v1/sync/push` (api-contract.md section 6.1).

    Named after `KernelCallOutcome` and `SlmCallOutcome` above, and after the device's own
    `KernelFailure`, rather than invented as a fourth vocabulary. It is a separate enum for the
    same reason `SlmCallOutcome` is separate from `KernelCallOutcome`: those two answer "what
    happened to a call", per hop, for an operator. This one answers "what should the outbox do
    with this row", for a device. There is no value-for-value correspondence and forcing one
    would produce values no caller can populate.

    **THE CLASSIFICATION LIVES HERE AND ONLY HERE.** A device must read this field, never infer
    it from the `code` string. Inference across this seam is the mirror drift this project has
    already paid for twice (the Phase 1 failed-login audit row, the Phase 3 kernel_call_log rows),
    and the device/server audit-action split exists for exactly the same reason. The `code`
    remains what it was, an identifier for the failure; this says what to do about it.

    Only three values, because only three outbox behaviours are distinguishable. Adding a fourth
    means the device gains a behaviour, and both mirror tests (`test_sync_retry_class_mirror.py`
    and `SyncRetryClassMirrorTest.kt`) will fail until it does.
    """

    # Resending the identical bytes later can succeed, because the cause is outside this record
    # and can change: a parent row that has not landed yet, or a vocabulary the backend has not
    # learned yet. The row stays in the outbox.
    RETRYABLE = "RETRYABLE"

    # Resending the identical bytes fails identically, forever. The record itself is wrong, and
    # nothing that happens elsewhere on the server changes that. Only a device-side change can.
    TERMINAL = "TERMINAL"

    # A different, already-present record claims something this one also claims. Resending
    # unchanged cannot succeed, and unlike TERMINAL the remedy is a human decision about which of
    # the two records is right, not a code fix. Kept apart from TERMINAL because those two want
    # different screens, not because they want different retries.
    CONFLICT = "CONFLICT"


class SlmCallOutcome(StrEnum):
    """slm_call_log.outcome, when that table exists. The SLM proxy's outcome vocabulary.

    Deliberately shaped after KernelCallOutcome above rather than invented, and the two should be
    read side by side. Both describe one hop from backend/core to a model service on the LAN, both
    are written out of band so the record of a network event that really happened does not depend
    on what the request transaction does afterwards, and an operator debugging "is the model
    service unreachable" versus "is its process erroring" is asking the same question of either.
    Two vocabularies for one question is how the answer stops being comparable.

    Every value here has a KernelCallOutcome counterpart except TRUNCATED, and that one is the
    reason this is a separate enum rather than a reuse. A classifier answers or it does not; a
    generative model can answer halfway. TRUNCATED is a SUCCESS at the HTTP layer, 200 with a
    well-formed body, that must not be treated as one: the generation hit the output ceiling and
    the text is cut off. It is invisible to every downstream check on the device, because a
    truncated restatement of an approved record introduces no drug name and no numeral the record
    did not already contain, so the grounding gate passes it. The response envelope's finish_reason
    is the only place it can be caught, which is why the service contract requires that field to be
    derived from the generation rather than asserted (the sample serving app returns the literal
    "stop" unconditionally, so a truncated completion is indistinguishable from a complete one).

    NOT_LOADED is the other addition: a model service that is up and answering health checks while
    its weights are not resident is a state a classifier loading a 4.7 MB artifact at import time
    does not really have, and a service holding roughly 9.5 GiB of bfloat16 weights very much does.
    """

    SUCCESS = "SUCCESS"
    TIMEOUT = "TIMEOUT"
    UNREACHABLE = "UNREACHABLE"
    ENGINE_ERROR = "ENGINE_ERROR"
    PAYLOAD_REJECTED = "PAYLOAD_REJECTED"
    MALFORMED_RESPONSE = "MALFORMED_RESPONSE"
    CIRCUIT_OPEN = "CIRCUIT_OPEN"
    PHI_REJECTED = "PHI_REJECTED"
    NOT_LOADED = "NOT_LOADED"
    TRUNCATED = "TRUNCATED"
    # Added in PR-4, found by PR-3 while writing the contract's section 4.2 mapping table: a
    # service that is loaded, healthy and SATURATED is neither NOT_LOADED nor CIRCUIT_OPEN, and
    # the three want different operator responses. NOT_LOADED is a deployment fault, CIRCUIT_OPEN
    # is this proxy declining to call, and this is ordinary backpressure from the single worker's
    # bounded queue (contract section 5.2), carrying a Retry-After that already tells the caller
    # what to do. Mapping it onto either of the others would make the log state something untrue.
    # It is also the one outcome here that does NOT count toward the circuit breaker; see
    # app/services/slm.py, which argues that next to the code.
    QUEUE_FULL = "QUEUE_FULL"
