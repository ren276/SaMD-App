"""referrals: PHC-side only, no receiving system (REQ-REF-01)."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import ReferralStatus, UrgencyLevel
from app.models.mixins import (
    CLIENT_ID_LENGTH,
    PATIENT_ID_LENGTH,
    SyncMixin,
    enum_check,
    sync_state_check,
)


class Referral(Base, SyncMixin):
    """patient_uid keeps the device's field name rather than being renamed to patient_id, so the
    sync push endpoint receives exactly what the device sends. It is a foreign key to patients.id
    all the same.

    sending_phc_id is the device's own notion of which PHC sent this. It is reconciled against
    the token's facility_id on write; a mismatch is rejected rather than trusted.
    """

    __tablename__ = "referrals"
    __table_args__ = (
        sync_state_check("referrals"),
        enum_check("urgency_level", UrgencyLevel, "ck_referrals_urgency_level"),
        enum_check("status", ReferralStatus, "ck_referrals_status"),
        Index("ix_referrals_patient_uid", "patient_uid"),
        Index("ix_referrals_case_record_id", "case_record_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_uid: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    urgency_level: Mapped[str] = mapped_column(String(20), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    sending_phc_id: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
