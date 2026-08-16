"""doctors: server-owned reference data.

The device seeds nine mock doctors locally and the server is authoritative once Phase 3 pull
exists. This table is never written by a sync push, which is why it carries no SyncMixin and no
sync_state.

The primary key stays the same string identifier the device uses rather than a server-assigned
surrogate. case_records.assigned_doctor_id and prescriptions.doctor_id carry that value; minting
a different key here would silently break every reference arriving from a device.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.mixins import CLIENT_ID_LENGTH, FACILITY_ID_LENGTH


class Doctor(Base):
    __tablename__ = "doctors"
    __table_args__ = (Index("ix_doctors_specialty", "specialty"),)

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    specialty: Mapped[str] = mapped_column(String(120), nullable=False)
    available: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="true")
    facility_name: Mapped[str | None] = mapped_column(String(200))
    # NMC or State council registration number, rendered on the report's signature line.
    registration_number: Mapped[str | None] = mapped_column(String(60))
    # Nullable: a doctor may serve several PHCs, or none in the mock reference data.
    facility_id: Mapped[str | None] = mapped_column(
        String(FACILITY_ID_LENGTH), ForeignKey("facilities.id", ondelete="SET NULL"), index=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
