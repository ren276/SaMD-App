"""Shared column groups for the device-mirrored clinical tables."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Integer, String, func
from sqlalchemy.orm import Mapped, declarative_mixin, mapped_column

from app.models.enums import SyncState

FACILITY_ID_LENGTH = 32
# The device mints Patient.id as 12 alphanumeric characters (RegisterPatientUseCase, REQ-REG-03)
# and UUID4 strings for everything else. 36 covers both without a per-table guess.
CLIENT_ID_LENGTH = 36
PATIENT_ID_LENGTH = 12

_SYNC_STATES_SQL = ", ".join(f"'{state.value}'" for state in SyncState)


@declarative_mixin
class SyncMixin:
    """Server-side bookkeeping added to every table that arrives from a device.

    None of these four columns exists in the Room schema. They are what the durable copy needs
    and the device does not:

    facility_id     stamped from the caller's token, never accepted from a request body. It is
                    the scoping root for every read and write, on every route, for every role.
    server_version  optimistic-concurrency token, incremented on every applied write. A PATCH
                    carrying a stale base_version is SAMD-SYNC-6004, not a silent overwrite.
    received_at     when the server first saw the row. Distinct from the row's own created_at,
                    which is device capture time. Keeping both is what makes an offline capture
                    synced two days later legible instead of looking backdated.
    sync_state      see app/models/enums.py SyncState.
    """

    facility_id: Mapped[str] = mapped_column(
        String(FACILITY_ID_LENGTH),
        ForeignKey("facilities.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    server_version: Mapped[int] = mapped_column(
        Integer, nullable=False, default=1, server_default="1"
    )
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    sync_state: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default=SyncState.RECEIVED.value,
        server_default=SyncState.RECEIVED.value,
        index=True,
    )


def sync_state_check(table_name: str) -> CheckConstraint:
    return CheckConstraint(f"sync_state IN ({_SYNC_STATES_SQL})", name=f"{table_name}_sync_state")


def enum_check(column: str, values: type, name: str) -> CheckConstraint:
    """CHECK constraint from a StrEnum, so the vocabulary lives in one place.

    VARCHAR plus CHECK rather than a native PostgreSQL ENUM: adding a value to a native enum is a
    migration with a lock, and these vocabularies will grow.
    """
    allowed = ", ".join(f"'{member.value}'" for member in values)  # type: ignore[attr-defined]
    return CheckConstraint(f"{column} IN ({allowed})", name=name)
