"""The hash-chained, append-only server audit log.

One chain per facility, covering both device-origin and server-origin events, so "what happened
to this patient" is one ordered query rather than a merge of two logs.

Append-only is enforced in three independent places, because a convention that lives in one
place is a convention that gets broken:

1. No UPDATE or DELETE method exists on the audit service (app/services/audit.py).
2. No route exposes mutation of an audit record.
3. A database trigger raises on any UPDATE or DELETE against this table (migration 0001). A
   trigger rather than only a GRANT, because a GRANT does not restrain the table owner and in
   dev the application connects as the owner. Deployment additionally runs the application under
   a role holding only SELECT and INSERT here; see backend/README.md.

REQ-AUD-01, REQ-AUD-02, hazard H-07.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    Identity,
    Index,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import AuditOrigin

GENESIS_HASH = "0" * 64
_ORIGINS_SQL = ", ".join(f"'{origin.value}'" for origin in AuditOrigin)


class AuditEvent(Base):
    """One audit row.

    Mirrors Room's AuditLogEntity (id, timestamp, userId, patientId, caseRecordId, action,
    payload) and adds what only a server can provide: ordering that does not depend on a clock,
    both timestamps, origin, correlation, and the chain itself.
    """

    __tablename__ = "audit_events"
    __table_args__ = (
        CheckConstraint(f"origin IN ({_ORIGINS_SQL})", name="origin_valid"),
        CheckConstraint("length(entry_hash) = 64", name="entry_hash_length"),
        CheckConstraint("length(previous_hash) = 64", name="previous_hash_length"),
        Index("ix_audit_events_facility_sequence", "facility_id", "sequence"),
        Index("ix_audit_events_patient_id", "patient_id"),
        Index("ix_audit_events_actor_id", "actor_id"),
        Index("ix_audit_events_occurred_at", "occurred_at"),
        Index("ix_audit_events_request_id", "request_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    # Chain ordering that does not depend on any clock. Database-assigned, monotonic.
    sequence: Mapped[int] = mapped_column(
        BigInteger, Identity(always=False), nullable=False, unique=True
    )

    # When it happened. Device clock for origin DEVICE.
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # When the server wrote it. Without both, an offline capture synced two days later is
    # indistinguishable from a backdated record.
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    origin: Mapped[str] = mapped_column(String(8), nullable=False)
    facility_id: Mapped[str] = mapped_column(String(32), nullable=False)
    actor_id: Mapped[str] = mapped_column(String(64), nullable=False)
    actor_role: Mapped[str | None] = mapped_column(String(20))
    device_id: Mapped[str | None] = mapped_column(String(64))
    request_id: Mapped[str | None] = mapped_column(String(36))

    action: Mapped[str] = mapped_column(String(64), nullable=False)
    patient_id: Mapped[str | None] = mapped_column(String(12))
    case_record_id: Mapped[str | None] = mapped_column(String(64))

    # JSON string, same as Room's AuditLogEntity.payload. Stored as text rather than jsonb
    # because it is copied verbatim from the device and must hash to the same value it did there.
    payload: Mapped[str] = mapped_column(Text, nullable=False, default="{}", server_default="{}")
    # sha256 of the payload string. Hashed into the chain so verification never loads the blobs.
    payload_sha256: Mapped[str] = mapped_column(String(64), nullable=False)

    previous_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    entry_hash: Mapped[str] = mapped_column(String(64), nullable=False)

    # No ORM event hook blocking UPDATE or DELETE. The database trigger is the real control, and
    # a Python guard alongside it would imply the trigger is the optional half.
