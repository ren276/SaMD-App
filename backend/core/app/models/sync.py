"""Backend-only operational tables: sync batches, per-record sync results, kernel call log,
kernel assessments.

None of these exists on the device. They are what makes "what happened to this patient's data
yesterday" a SQL query rather than a support ticket, and they are the read surface the Phase 7
admin page will sit on.

Populated by Phase 3 (kernel_call_log, kernel_assessments) and Phase 4 (sync_batches, sync_log).
Created now so those phases are route work, not migration work.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Identity,
    Index,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import KernelCallOutcome, KernelEndpoint
from app.models.mixins import CLIENT_ID_LENGTH, FACILITY_ID_LENGTH, enum_check


class SyncBatch(Base):
    """One POST /api/v1/sync/push.

    response_json is the stored acknowledgement. Replaying a batch_id within 24 hours returns it
    verbatim without re-applying anything, which is what makes the endpoint idempotent for a
    field device retrying over a bad link.
    """

    __tablename__ = "sync_batches"
    __table_args__ = (
        Index("ix_sync_batches_facility_received", "facility_id", "received_at"),
        Index("ix_sync_batches_device_id", "device_id"),
    )

    batch_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False)
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    record_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    applied: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    stale: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    conflicted: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    rejected: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    response_json: Mapped[dict[str, Any] | None] = mapped_column(JSONB)


class SyncLogEntry(Base):
    """One row per record in a batch: what actually happened to it."""

    __tablename__ = "sync_log"
    __table_args__ = (
        Index("ix_sync_log_batch_id", "batch_id"),
        Index("ix_sync_log_table_record", "table_name", "record_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=False), primary_key=True)
    batch_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("sync_batches.batch_id", ondelete="CASCADE"), nullable=False
    )
    table_name: Mapped[str] = mapped_column(String(60), nullable=False)
    record_id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), nullable=False)
    # applied | stale | conflict | duplicate | rejected
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    code: Mapped[str | None] = mapped_column(String(20))
    message: Mapped[str | None] = mapped_column(Text)
    server_version: Mapped[int | None] = mapped_column(Integer)
    applied_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class KernelCallLog(Base):
    """Every forwarded kernel call, by hash. Written before forwarding and completed after, so a
    call that never returns (or is rejected before the network call happens) still leaves a row.

    What is stored: which input produced which output, when, by whom, from which model version,
    and which of the two identifiers (case_record_id, case_token) the call correlates to.
    What is not stored: the request body, the response body, any vital value, any complaint text.

    Hashes prove the mapping for IEC 62304 traceability without creating a second copy of the
    clinical record in a log table. The response body itself lives in kernel_assessments below,
    one row per successful call, joined on request_id.

    Retention: 24 months (decision D-5), matching kernel_call_log's row in backend-prd.md
    section 9. Purge is a future operational job, not built in Phase 3.
    """

    __tablename__ = "kernel_call_log"
    __table_args__ = (
        enum_check("endpoint", KernelEndpoint, "ck_kernel_call_log_endpoint"),
        enum_check("outcome", KernelCallOutcome, "ck_kernel_call_log_outcome"),
        Index("ix_kernel_call_log_facility_started", "facility_id", "started_at"),
        Index("ix_kernel_call_log_case_record_id", "case_record_id"),
        Index("ix_kernel_call_log_case_token", "case_token"),
        Index("ix_kernel_call_log_request_id", "request_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=False), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(36), nullable=False)
    # The real id, server-side only. Never sent to the kernel; case_token is what the kernel sees.
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    case_token: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), nullable=False)
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )
    endpoint: Mapped[str] = mapped_column(String(20), nullable=False)
    # dev/staging/prod point at different kernels; recorded because a slow or failing call is a
    # different story depending on which host answered it.
    kernel_base_url: Mapped[str] = mapped_column(String(255), nullable=False)
    input_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    output_sha256: Mapped[str | None] = mapped_column(String(64))
    model_version: Mapped[str | None] = mapped_column(String(80))
    http_status: Mapped[int | None] = mapped_column(Integer)
    outcome: Mapped[str] = mapped_column(String(20), nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(20))
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    duration_ms: Mapped[int | None] = mapped_column(Integer)


class KernelAssessment(Base):
    """What the kernel actually returned, stored verbatim. Server-owned, never synced.

    This table exists because of a traceability defect found in the Phase 3 fix pass (D-9, D-10).
    The proxy used to reshape each /v1/assess response into a kernel_reports row, writing
    predicted_condition, confidence_score, risk_category and required_human_verification next to
    model_id and model_version. None of those four values is on the wire. Storing a
    backend-computed value in a column that reads as model output attributes the backend's
    arithmetic to a specific model version, which is exactly what IEC 62304 traceability and the
    project's no-black-box principle forbid. It also collided with the device, which upserts one
    kernel_reports row per case while the proxy inserted one per call.

    So: kernel_reports is device-owned and written only by sync push (Phase 4). This table is
    server-owned and holds raw model output only.

    THE RULE FOR THIS TABLE, and it is not a style preference: it stores ZERO derived values. No
    predicted_condition, no confidence_score, no risk_category, no required_human_verification.
    Every column below is either copied verbatim out of the response body, or is provenance the
    server itself owns (who called, from which facility, under which request_id). Anything a
    clinician reads as a conclusion is computed at READ time by
    app/domain/kernel_derivation.py, which carries a rule version so a change to the rules is
    distinguishable from a change to the model.

    The nullable columns are nullable because /evaluate does not carry them, not because they are
    optional on /assess. There are no defaults on purpose: an invented default here would be the
    same class of mistake this table was created to remove.

    One row per SUCCESSFUL call that returned parseable JSON. Failures leave a kernel_call_log row
    and an audit_events row and nothing here, because there is no model output to store.
    """

    __tablename__ = "kernel_assessments"
    __table_args__ = (
        enum_check("endpoint", KernelEndpoint, "ck_kernel_assessments_endpoint"),
        Index("ix_kernel_assessments_request_id", "request_id"),
        Index("ix_kernel_assessments_case_record_id", "case_record_id"),
        Index("ix_kernel_assessments_facility_id", "facility_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    # Joins to kernel_call_log. One request_id, one call, one log row, one assessment row.
    request_id: Mapped[str] = mapped_column(String(36), nullable=False)
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    # Stamped from the caller's token, never read from a request body.
    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    endpoint: Mapped[str] = mapped_column(String(20), nullable=False)
    # The response body exactly as the kernel returned it, mixed casing and all. The only
    # difference from the bytes on the wire is that case_token still holds the pseudonym: the
    # swap back to the real id happens in the HTTP response, not in what is stored.
    raw_response: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    # Lifted out of model_metadata for queryability. Still raw: copied, not computed.
    model_version: Mapped[str | None] = mapped_column(String(80))
    inference_time_ms: Mapped[int | None] = mapped_column(Integer)
    # /assess only. /evaluate carries neither.
    safety_screen_passed: Mapped[bool | None] = mapped_column(Boolean)
    # Verbatim string, deliberately not CHECK-constrained against UrgencyLevel: this is the
    # kernel's vocabulary, not ours, and silently coercing an unrecognised value to ROUTINE would
    # hide a contract drift that an operator needs to see.
    triage_urgency: Mapped[str | None] = mapped_column(String(40))
    # The same value written to kernel_call_log.output_sha256, so the two rows are provably about
    # the same bytes without re-hashing a jsonb column whose key order Postgres may have changed.
    response_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
