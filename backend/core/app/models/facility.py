"""Facility: the scoping root for every query in the system."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class Facility(Base):
    """A PHC. Seeded, never self-service.

    There is no facility concept anywhere on the Android device today. The nearest thing is
    ReferralEntity.sendingPhcId, which is a free string. facility_id comes from the account, is
    carried in the token, is stamped by the server on every row it writes, and is never accepted
    from a request body.
    """

    __tablename__ = "facilities"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    district: Mapped[str | None] = mapped_column(String(100))
    state: Mapped[str | None] = mapped_column(String(100))
    # Health Facility Registry id, needed as X-HIP-ID for ABDM V3 calls in Phase 5. Nullable
    # because a facility exists here long before it is ABDM registered.
    hfr_id: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
