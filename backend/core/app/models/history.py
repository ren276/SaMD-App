"""The medical-background tables: history items, allergies, family history, social history,
and current medications (REQ-MBG-01)."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import AllergyCategory, MedicalHistoryCategory, MedicationKind
from app.models.mixins import (
    CLIENT_ID_LENGTH,
    PATIENT_ID_LENGTH,
    SyncMixin,
    enum_check,
    sync_state_check,
)


class MedicalHistoryItem(Base, SyncMixin):
    __tablename__ = "medical_history_items"
    __table_args__ = (
        sync_state_check("medical_history_items"),
        enum_check("category", MedicalHistoryCategory, "ck_medical_history_items_category"),
        Index("ix_medical_history_items_patient_id", "patient_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    category: Mapped[str] = mapped_column(String(30), nullable=False)
    description: Mapped[str] = mapped_column(Text, nullable=False)
    year_or_date: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Allergy(Base, SyncMixin):
    __tablename__ = "allergies"
    __table_args__ = (
        sync_state_check("allergies"),
        enum_check("category", AllergyCategory, "ck_allergies_category"),
        Index("ix_allergies_patient_id", "patient_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    category: Mapped[str] = mapped_column(String(20), nullable=False)
    allergen: Mapped[str] = mapped_column(String(200), nullable=False)
    reaction_type: Mapped[str | None] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class FamilyHistoryEntry(Base, SyncMixin):
    __tablename__ = "family_history_entries"
    __table_args__ = (
        sync_state_check("family_history_entries"),
        Index("ix_family_history_entries_patient_id", "patient_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    condition: Mapped[str] = mapped_column(String(200), nullable=False)
    relation: Mapped[str | None] = mapped_column(String(60))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class SocialHistory(Base, SyncMixin):
    """One row per patient. patient_id IS the primary key, matching the Room entity."""

    __tablename__ = "social_histories"
    __table_args__ = (sync_state_check("social_histories"),)

    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH),
        ForeignKey("patients.id", ondelete="RESTRICT"),
        primary_key=True,
    )
    occupation: Mapped[str | None] = mapped_column(String(120))
    tobacco_use: Mapped[str | None] = mapped_column(String(120))
    alcohol_use: Mapped[str | None] = mapped_column(String(120))
    recreational_drug_use: Mapped[str | None] = mapped_column(String(120))
    environmental_exposure: Mapped[str | None] = mapped_column(Text)
    recent_travel: Mapped[str | None] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MedicationEntry(Base, SyncMixin):
    """A medication or supplement the patient is already on. Distinct from medication_lines,
    which is what a doctor prescribed at this visit."""

    __tablename__ = "medication_entries"
    __table_args__ = (
        sync_state_check("medication_entries"),
        enum_check("kind", MedicationKind, "ck_medication_entries_kind"),
        Index("ix_medication_entries_patient_id", "patient_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    # Nullable: a medication recorded during background capture has no encounter yet.
    encounter_id: Mapped[str | None] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT")
    )
    kind: Mapped[str] = mapped_column(String(20), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    dosage: Mapped[str | None] = mapped_column(String(120))
    frequency: Mapped[str | None] = mapped_column(String(120))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
