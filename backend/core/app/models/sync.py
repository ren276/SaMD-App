"""Backend-only operational tables: sync batches, per-record sync results, kernel call log.

None of these exists on the device. They are what makes "what happened to this patient's data
yesterday" a SQL query rather than a support ticket, and they are the read surface the Phase 7
admin page will sit on.

Populated by Phase 3 (kernel_call_log) and Phase 4 (sync_batches, sync_log). Created now so
those phases are route work, not migration work.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger,
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
from app.models.mixins import CLIENT_ID_LENGTH, FACILITY_ID_LENGTH


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
    """Every forwarded kernel call, by hash.

    What is stored: which input produced which output, when, by whom, from which model version.
    What is not stored: the request body, the response body, any vital value, any complaint text.

    Hashes prove the mapping for IEC 62304 traceability without creating a second copy of the
    clinical record in a log table. The clinical content already lives durably in kernel_reports
    and evaluate_reports.
    """

    __tablename__ = "kernel_call_log"
    __table_args__ = (
        Index("ix_kernel_call_log_facility_created", "facility_id", "created_at"),
        Index("ix_kernel_call_log_case_token", "case_token"),
        Index("ix_kernel_call_log_request_id", "request_id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=False), primary_key=True)
    request_id: Mapped[str] = mapped_column(String(36), nullable=False)
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )
    case_token: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), nullable=False)
    endpoint: Mapped[str] = mapped_column(String(60), nullable=False)
    input_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    output_sha256: Mapped[str | None] = mapped_column(String(64))
    model_version: Mapped[str | None] = mapped_column(String(80))
    http_status: Mapped[int | None] = mapped_column(Integer)
    duration_ms: Mapped[int | None] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
