"""prescriptions and their ordered medication_lines."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import KernelDecision
from app.models.mixins import (
    CLIENT_ID_LENGTH,
    PATIENT_ID_LENGTH,
    SyncMixin,
    enum_check,
    sync_state_check,
)


class Prescription(Base, SyncMixin):
    """doctor_id carries no foreign key, for the same reason case_records.assigned_doctor_id does
    not: doctors is reference data with an independent lifecycle, and a stale seed must not cost
    a clinical record."""

    __tablename__ = "prescriptions"
    __table_args__ = (
        sync_state_check("prescriptions"),
        enum_check("kernel_decision", KernelDecision, "ck_prescriptions_kernel_decision"),
        Index("ix_prescriptions_case_record_id", "case_record_id"),
        Index("ix_prescriptions_patient_id", "patient_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    encounter_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT"), nullable=False
    )
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    doctor_id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), nullable=False, index=True)
    diagnosis: Mapped[str] = mapped_column(Text, nullable=False)
    kernel_decision: Mapped[str | None] = mapped_column(String(10))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MedicationLine(Base, SyncMixin):
    """One prescribed drug. position preserves the doctor's ordering.

    REQ-RX-02, a hard rule worth restating where the data lands: no ambiguous Latin abbreviations
    (OD, BD, TDS, QID, SOS, HS) in frequency or any dosing text. Frequencies are written out in
    full. The device enforces this at the report boundary; nothing on this side re-validates it
    yet, and that gap is real rather than covered.
    """

    __tablename__ = "medication_lines"
    __table_args__ = (
        sync_state_check("medication_lines"),
        UniqueConstraint("prescription_id", "position", name="uq_medication_lines_position"),
        Index("ix_medication_lines_prescription_id", "prescription_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    prescription_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("prescriptions.id", ondelete="CASCADE"),
        nullable=False,
    )
    position: Mapped[int] = mapped_column(Integer, nullable=False)
    generic_name: Mapped[str] = mapped_column(String(200), nullable=False)
    brand_name: Mapped[str | None] = mapped_column(String(200))
    strength: Mapped[str] = mapped_column(String(80), nullable=False)
    dosage: Mapped[str] = mapped_column(String(120), nullable=False)
    frequency: Mapped[str] = mapped_column(String(120), nullable=False)
    route: Mapped[str] = mapped_column(String(60), nullable=False)
    duration: Mapped[str] = mapped_column(String(80), nullable=False)
    quantity: Mapped[str] = mapped_column(String(60), nullable=False)
    food_relation: Mapped[str | None] = mapped_column(String(80))
    instructions: Mapped[str | None] = mapped_column(Text)
