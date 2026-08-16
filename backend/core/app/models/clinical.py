"""observations (vitals), ailments, and case_records."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import (
    CaseStatus,
    MeasurementType,
    ObservationSource,
    ObservationType,
    Visibility,
    VitalsCaptureMethod,
)
from app.models.mixins import (
    CLIENT_ID_LENGTH,
    PATIENT_ID_LENGTH,
    SyncMixin,
    enum_check,
    sync_state_check,
)


class Observation(Base, SyncMixin):
    """One vital sign reading. A vitals snapshot fans out into several of these rows."""

    __tablename__ = "observations"
    __table_args__ = (
        sync_state_check("observations"),
        enum_check("type", ObservationType, "ck_observations_type"),
        enum_check("source", ObservationSource, "ck_observations_source"),
        enum_check("capture_method", VitalsCaptureMethod, "ck_observations_capture_method"),
        Index("ix_observations_patient_id", "patient_id"),
        Index("ix_observations_encounter_id", "encounter_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    encounter_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT"), nullable=False
    )
    type: Mapped[str] = mapped_column(String(30), nullable=False)
    value_numeric: Mapped[float | None] = mapped_column(Float)
    value_text: Mapped[str | None] = mapped_column(Text)
    unit: Mapped[str | None] = mapped_column(String(20))
    device_id: Mapped[str | None] = mapped_column(String(64))
    source: Mapped[str] = mapped_column(String(20), nullable=False)
    # REQ-TRS-05. One value per vitals snapshot, not per vital row.
    capture_method: Mapped[str | None] = mapped_column(String(30))

    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # REQ-TRS-06. Stamped by the server on apply and returned, so the device stops having to
    # guess. Deliberately distinct from recorded_at, never defaulted equal to it.
    synced_to_cloud_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Ailment(Base, SyncMixin):
    """A reported ailment, measurable or not (REQ-AIL-01 through REQ-AIL-04).

    Two rules that are easy to get backwards, and both matter:

    - visibility PRIVATE rows DO live here, clinical text included. PRIVATE hides an entry from
      the worker-facing projection on the device, not from the clinical record. The kernel path
      reads every entry regardless of visibility (REQ-AIL-04).
    - There is NO audio_local_uri column, and there must never be one. Private-ailment audio is
      recorded to app-private storage and has no upload path anywhere in the app. A sync record
      carrying that field is rejected with SAMD-SYNC-6006 (REQ-AIL-03).
    """

    __tablename__ = "ailments"
    __table_args__ = (
        sync_state_check("ailments"),
        enum_check("measurement_type", MeasurementType, "ck_ailments_measurement_type"),
        enum_check("visibility", Visibility, "ck_ailments_visibility"),
        Index("ix_ailments_patient_id", "patient_id"),
        Index("ix_ailments_encounter_id", "encounter_id"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    encounter_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT"), nullable=False
    )
    description: Mapped[str] = mapped_column(Text, nullable=False)
    measurement_type: Mapped[str] = mapped_column(String(20), nullable=False)
    visibility: Mapped[str] = mapped_column(String(10), nullable=False)
    measured_value: Mapped[float | None] = mapped_column(Float)
    measured_unit: Mapped[str | None] = mapped_column(String(20))
    severity: Mapped[int | None] = mapped_column(Integer)
    onset: Mapped[str | None] = mapped_column(String(120))
    duration: Mapped[str | None] = mapped_column(String(120))
    qualifiers: Mapped[str | None] = mapped_column(Text)

    captured_at_offline: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    synced_to_cloud_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Soft delete. Nothing in this system hard-deletes clinical data (docs/data-retention.md).
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class CaseRecord(Base, SyncMixin):
    """The unit of work that moves through the pipeline. Status transitions are enforced server
    side per the state machine in api-contract.md section 4.3.

    assigned_doctor_id carries no foreign key, and that is deliberate. doctors is reference data
    with an independent lifecycle; a device may legitimately hold a doctor row the server has not
    been seeded with yet. Blocking a clinical record because reference data is stale converts a
    seeding gap into clinical data loss, which is the wrong failure. This is the one place in the
    schema where a relationship is left unconstrained, and it is on purpose.
    """

    __tablename__ = "case_records"
    __table_args__ = (
        sync_state_check("case_records"),
        enum_check("status", CaseStatus, "ck_case_records_status"),
        Index("ix_case_records_patient_id", "patient_id"),
        Index("ix_case_records_encounter_id", "encounter_id"),
        Index("ix_case_records_facility_status", "facility_id", "status"),
    )

    id: Mapped[str] = mapped_column(String(CLIENT_ID_LENGTH), primary_key=True)
    patient_id: Mapped[str] = mapped_column(
        String(PATIENT_ID_LENGTH), ForeignKey("patients.id", ondelete="RESTRICT"), nullable=False
    )
    encounter_id: Mapped[str] = mapped_column(
        String(CLIENT_ID_LENGTH), ForeignKey("encounters.id", ondelete="RESTRICT"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(30), nullable=False)
    assigned_doctor_id: Mapped[str | None] = mapped_column(String(CLIENT_ID_LENGTH), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
