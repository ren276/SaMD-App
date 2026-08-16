"""SQLAlchemy models.

Every model must be imported here so Base.metadata is complete before Alembic autogenerate or a
test's create_all runs. A model that is defined but not imported is a table that silently does
not exist.

Twenty tables mirror the Room schema one for one, with identical table names and the snake_case
form of each Kotlin property, so a sync push is a dumb row operation rather than a translation
layer. Room's audit_log is the exception: it becomes audit_events, extended with the hash chain
(app/models/audit.py).
"""

from app.models.abha import AbhaProfile, AbhaTransaction
from app.models.attachment import Attachment
from app.models.audit import GENESIS_HASH, AuditEvent
from app.models.clinical import Ailment, CaseRecord, Observation
from app.models.doctor import Doctor
from app.models.encounter import Consultation, Encounter
from app.models.enums import (
    AbhaTransactionKind,
    AbhaTransactionState,
    AllergyCategory,
    AttachmentType,
    AuditAction,
    AuditOrigin,
    BlobStatus,
    CaseStatus,
    InferenceSource,
    KernelDecision,
    MeasurementType,
    MedicalHistoryCategory,
    MedicationKind,
    ObservationSource,
    ObservationType,
    PhysicianDecision,
    ReferralStatus,
    RiskCategory,
    SyncState,
    UrgencyLevel,
    UserRole,
    Visibility,
    VitalsCaptureMethod,
)
from app.models.facility import Facility
from app.models.history import (
    Allergy,
    FamilyHistoryEntry,
    MedicalHistoryItem,
    MedicationEntry,
    SocialHistory,
)
from app.models.kernel import DiagnosisFeedback, EvaluateReport, KernelReport
from app.models.mixins import CLIENT_ID_LENGTH, PATIENT_ID_LENGTH, SyncMixin
from app.models.patient import Patient
from app.models.prescription import MedicationLine, Prescription
from app.models.referral import Referral
from app.models.sync import KernelCallLog, SyncBatch, SyncLogEntry
from app.models.user import WORKER_ID_LENGTH, Device, RefreshToken, UserAccount

__all__ = [
    "CLIENT_ID_LENGTH",
    "GENESIS_HASH",
    "PATIENT_ID_LENGTH",
    "WORKER_ID_LENGTH",
    "AbhaProfile",
    "AbhaTransaction",
    "AbhaTransactionKind",
    "AbhaTransactionState",
    "Ailment",
    "Allergy",
    "AllergyCategory",
    "Attachment",
    "AttachmentType",
    "AuditAction",
    "AuditEvent",
    "AuditOrigin",
    "BlobStatus",
    "CaseRecord",
    "CaseStatus",
    "Consultation",
    "Device",
    "DiagnosisFeedback",
    "Doctor",
    "Encounter",
    "EvaluateReport",
    "Facility",
    "FamilyHistoryEntry",
    "InferenceSource",
    "KernelCallLog",
    "KernelDecision",
    "KernelReport",
    "MeasurementType",
    "MedicalHistoryCategory",
    "MedicalHistoryItem",
    "MedicationEntry",
    "MedicationKind",
    "MedicationLine",
    "Observation",
    "ObservationSource",
    "ObservationType",
    "Patient",
    "PhysicianDecision",
    "Prescription",
    "Referral",
    "ReferralStatus",
    "RefreshToken",
    "RiskCategory",
    "SocialHistory",
    "SyncBatch",
    "SyncLogEntry",
    "SyncMixin",
    "SyncState",
    "UrgencyLevel",
    "UserAccount",
    "UserRole",
    "Visibility",
    "VitalsCaptureMethod",
]
