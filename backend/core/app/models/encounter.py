"""encounters and consultations.

Foreign keys are real here even though the Room schema has none. The device's reason for
omitting them is sound (arbitrary insert order during offline capture, exactly one writer). The
server has neither excuse: it is the durable copy of a clinical record, and referential
integrity is the last line of defence against a wrong-patient linkage (hazard H-03).

The consequence is that sync push must apply records in dependency order, which is why
api-contract.md section 6.1 fixes a table rank rather than trusting the client's array order.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.mixins import CLIENT_ID_LENGTH, PATIENT_ID_LENGTH, SyncMixin, sync_state_check


class Encounter(Base, SyncMixin):
    __tablename__ = "encounters"
    __table_args__ = (
        sync_state_check("encounters"),
        Index("ix_encounters_patient_id", "patient_id"),
        # The roster query (GET /api/v1/patients) is a facility-scoped window over started_at.
        Index("ix_encounters_facility_started", "facility_id", "started_at"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH),
        ForeignKey("patients.id", ondelete="RESTRICT"),
        nullable=False,
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # Self-referential. Set once at creation when the worker marks this visit as a follow-up to a
    # specific prior encounter, picked from that patient's own history. Null for a fresh visit.
    follow_up_of_encounter_id: Mapped[str | None] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("encounters.id", ondelete="RESTRICT"),
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Consultation(Base, SyncMixin):
    """Chief complaint and its context. One per encounter in practice, not enforced as such."""

    __tablename__ = "consultations"
    __table_args__ = (
        sync_state_check("consultations"),
        Index("ix_consultations_patient_id", "patient_id"),
        Index("ix_consultations_encounter_id", "encounter_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    encounter_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT"), nullable=False
    )

    # Clinical free text. Not encrypted: it is the clinical record itself, it is what the kernel
    # and the reviewing physician read, and encrypting it would make the whole table opaque for
    # no gain that volume-level encryption does not already provide.
    chief_complaint: Mapped[str] = mapped_column(Text, nullable=False)
    onset: Mapped[str | None] = mapped_column(String(120))
    duration_bucket: Mapped[str | None] = mapped_column(String(40))
    severity_score: Mapped[int | None] = mapped_column(Integer)
    aggravating_factors: Mapped[str | None] = mapped_column(Text)
    relieving_factors: Mapped[str | None] = mapped_column(Text)
    impact_on_daily_activities: Mapped[str | None] = mapped_column(Text)
    # FieldProvenance enum name (TYPED / VOICE_UNCONFIRMED / VOICE_CONFIRMED / VOICE_EDITED),
    # ASR track PR 1 (scratchpad/asr-field-audit-memo.md Part B.2). No CHECK constraint: the memo
    # does not call for one at this stage. VOICE_UNCONFIRMED must never reach this column (the
    # device repository write refusal that enforces that is a later PR); nothing currently sends
    # anything but TYPED or null.
    impact_on_daily_activities_provenance: Mapped[str | None] = mapped_column(String(20))
    relevant_history: Mapped[str | None] = mapped_column(Text)
    transcription: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
