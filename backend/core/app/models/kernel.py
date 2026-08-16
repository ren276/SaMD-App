"""kernel_reports, evaluate_reports, diagnosis_feedback: what the AI produced and what the
physician did about it."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import (
    ARRAY,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    String,
    Text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import InferenceSource, PhysicianDecision, RiskCategory, UrgencyLevel
from app.models.mixins import CLIENT_ID_LENGTH, SyncMixin, enum_check, sync_state_check


class KernelReport(Base, SyncMixin):
    """Output of the /v1/assess leg (REQ-HAN-07).

    inference_source is the reason this table is worth having server side: it records whether a
    given result came from real inference or from the on-device mock scenario table
    (REQ-HAN-08), which makes the mock-fallback rate a queryable metric for the first time
    instead of a Logcat line that vanishes in the field.
    """

    __tablename__ = "kernel_reports"
    __table_args__ = (
        sync_state_check("kernel_reports"),
        enum_check("risk_category", RiskCategory, "ck_kernel_reports_risk_category"),
        enum_check("urgency_level", UrgencyLevel, "ck_kernel_reports_urgency_level"),
        enum_check("inference_source", InferenceSource, "ck_kernel_reports_inference_source"),
        Index("ix_kernel_reports_case_record_id", "case_record_id"),
        Index("ix_kernel_reports_facility_source", "facility_id", "inference_source"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )

    predicted_condition: Mapped[str] = mapped_column(Text, nullable=False)
    confidence_score: Mapped[float] = mapped_column(Float, nullable=False)
    # text[] rather than a child table: these are opaque display strings with no identity of
    # their own and no query that joins on them.
    differentials: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, default=list)
    reasoning_summary: Mapped[str] = mapped_column(Text, nullable=False)
    evidence_for: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, default=list)
    evidence_against: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, default=list)

    model_version: Mapped[str] = mapped_column(String(80), nullable=False)
    icd_code: Mapped[str | None] = mapped_column(String(20))
    device_id: Mapped[str] = mapped_column(String(64), nullable=False)
    software_version: Mapped[str] = mapped_column(String(40), nullable=False)
    data_quality_score: Mapped[float | None] = mapped_column(Float)
    uncertainty_score: Mapped[float | None] = mapped_column(Float)
    risk_category: Mapped[str] = mapped_column(String(20), nullable=False)
    urgency_level: Mapped[str] = mapped_column(String(20), nullable=False)
    inference_started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    inference_ended_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    required_human_verification: Mapped[bool] = mapped_column(Boolean, nullable=False)
    inference_source: Mapped[str] = mapped_column(String(20), nullable=False)


class EvaluateReport(Base, SyncMixin):
    """Output of the /api/v1/evaluate leg (REQ-EVL-01).

    payload_json is jsonb here, not text as on the device. The device stores one Gson blob
    because it only ever reads the whole tree back; the server stores jsonb so the founder can
    query inside it with PostgreSQL JSON operators without writing an application.

    The tree is stored exactly as the kernel returned it, mixed snake_case and camelCase
    included. Reshaping it would break the shipped EvaluateReportDto for no clinical gain.
    """

    __tablename__ = "evaluate_reports"
    __table_args__ = (
        sync_state_check("evaluate_reports"),
        Index("ix_evaluate_reports_case_record_id", "case_record_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    payload_json: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    inference_started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    inference_ended_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class DiagnosisFeedback(Base, SyncMixin):
    """The physician's AGREE / MODIFY / REJECT on the AI's top candidate (REQ-RFN-01).

    Stored, never consumed. No training-data reimport endpoint exists and none is planned in v1.

    physician_final_diagnosis is set only on MODIFY, and only to one of the 18 ICD classes the
    symptom model was actually trained on. clinical_note is free text and is explicitly never
    reimportable. Drug, brand, and company are prescription concerns and must never appear here.
    """

    __tablename__ = "diagnosis_feedback"
    __table_args__ = (
        sync_state_check("diagnosis_feedback"),
        enum_check("physician_decision", PhysicianDecision, "ck_diagnosis_feedback_decision"),
        Index("ix_diagnosis_feedback_case_record_id", "case_record_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    case_record_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH),
        ForeignKey("case_records.id", ondelete="RESTRICT"),
        nullable=False,
    )
    icd_candidate: Mapped[str] = mapped_column(String(20), nullable=False)
    physician_decision: Mapped[str] = mapped_column(String(10), nullable=False)
    physician_final_diagnosis: Mapped[str | None] = mapped_column(String(20))
    clinical_note: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
