"""ABDM/ABHA transaction state, created in Phase 1 so Phase 5 needs no migration.

The adapter itself is Phase 5 and its design lives in "ABHA planning/abha-integration-plan.md",
which is the ground truth. This module only fixes the storage shape, derived from the actual V3
request and response bodies in
"abha api docs/ABDM_ABHA_V3_AP_Is_V1_31_07_2025_869ab8cda9.md" and the Milestone 1 Postman
collection.

Decision D-6: the ABHA transaction store is this same PostgreSQL database and this same Alembic
history. Running a second engine in one process to save one connection string is a cost, not a
saving.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Index,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.db.types import BLIND_INDEX_LENGTH, EncryptedText
from app.models.enums import AbhaTransactionKind, AbhaTransactionState
from app.models.mixins import SyncMixin, sync_state_check

_STATES_SQL = ", ".join(f"'{state.value}'" for state in AbhaTransactionState)
_KINDS_SQL = ", ".join(f"'{kind.value}'" for kind in AbhaTransactionKind)


class AbhaTransaction(Base):
    """One ABDM enrollment or verification session.

    Deliberately separate from any patient concept. A verified ABHA identity is not
    automatically linked to a patient; linking is an explicit, worker-confirmed action guarded by
    the duplicate-ABHA check (SAMD-PAT-3004). That is the load-bearing wrong-patient control
    (hazard H-03).

    What is never stored here, in any column, ever: the Aadhaar number, any OTP value, and the
    ABDM client secret.
    """

    __tablename__ = "abha_transactions"
    __table_args__ = (
        CheckConstraint(f"state IN ({_STATES_SQL})", name="state_valid"),
        CheckConstraint(f"kind IN ({_KINDS_SQL})", name="kind_valid"),
        Index("ix_abha_transactions_external_txn_id", "external_txn_id"),
        Index("ix_abha_transactions_facility_id", "facility_id"),
        Index("ix_abha_transactions_expires_at", "expires_at"),
    )

    # Our identifier, the one Android sees as session_id. Never ABDM's.
    local_transaction_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    # ABDM's txnId, which chains every step of the V3 flow. Rotates as the flow advances.
    external_txn_id: Mapped[str | None] = mapped_column(String(64))

    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    state: Mapped[str] = mapped_column(String(32), nullable=False)

    facility_id: Mapped[str] = mapped_column(String(32), nullable=False)
    worker_id: Mapped[str] = mapped_column(String(16), nullable=False)
    # SaMD correlation id for audit and logs. Kept distinct from ABDM's REQUEST-ID header, which
    # is generated per outbound call in the adapter's request_context module.
    correlation_id: Mapped[str] = mapped_column(String(36), nullable=False)

    # The enrollment token ABDM returns alongside the profile (expiresIn 1800 in the V3 spec).
    # Short lived and pgcrypto-encrypted when written; cleared the moment the session reaches a
    # terminal state. EncryptedText (Phase 5 fix, was a bare LargeBinary column here since Phase
    # 1, "encrypted when written" resting on call-site discipline alone, the exact pattern this
    # codebase avoids everywhere else, see app/logging.py's redaction processor docstring). The
    # underlying column stays bytea either way (EncryptedText.impl is LargeBinary too), so this
    # is a Python-side type change, not a migration.
    external_token_encrypted: Mapped[str | None] = mapped_column(EncryptedText)
    external_token_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    # Result of a completed session. abha_number is stored as 14 bare digits, never the
    # dash-formatted "91-1601-4548-XXXX" that the V3 response returns; normalisation happens in
    # the adapter, matching Patient.abhaNumber on the device.
    abha_number: Mapped[str | None] = mapped_column(String(14))
    abha_address: Mapped[str | None] = mapped_column(String(255))
    # ACTIVE, DEACTIVATED, DELETED. From the V3 profile response's abhaStatus.
    abha_status: Mapped[str | None] = mapped_column(String(20))
    # STANDARD or CHILD. From the V3 profile response's abhaType.
    abha_type: Mapped[str | None] = mapped_column(String(20))
    # Set only once the worker explicitly confirms the link. Never set automatically.
    linked_patient_id: Mapped[str | None] = mapped_column(String(12))

    # Retry classification from the integration plan: retryable (network, timeout) versus
    # non-retryable (invalid OTP, scope, identity) versus user-action (OTP expired). No blind
    # retries. last_error holds our internal code plus the preserved ABDM externalCode, never a
    # secret and never a raw upstream body.
    last_error_code: Mapped[str | None] = mapped_column(String(32))
    last_error_detail: Mapped[str | None] = mapped_column(Text)
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    otp_request_count: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default="0"
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class AbhaProfile(Base, SyncMixin):
    """abha_profiles: the ABHA identity captured on the device (AbhaProfileEntity).

    Distinct from AbhaTransaction above. A transaction is one in-flight ABDM session; a profile is
    the resulting identity, cached locally and synced up like any other row.

    Linked to a patient through Patient.abha_number, both holding the same canonical 14 bare
    digits. There is no duplicate id column and no foreign key: a profile can legitimately exist
    before the worker confirms which patient it belongs to, and that confirmation is an explicit
    act (SAMD-PAT-3004 guards the link). Auto-linking on a fuzzy match is the wrong-patient
    hazard H-03, so the schema does not make it possible by accident.

    name and mobile_number are encrypted at rest for the same reason the patients table encrypts
    them: they are direct identifiers, and this table holds them for the same people.
    """

    __tablename__ = "abha_profiles"
    __table_args__ = (
        sync_state_check("abha_profiles"),
        CheckConstraint("abha_id ~ '^[0-9]{14}$'", name="ck_abha_profiles_id_format"),
        Index("ix_abha_profiles_mobile_blind_idx", "mobile_blind_idx"),
    )

    # 14 bare digits, never the dash-formatted XX-XXXX-XXXX-XXXX display form.
    abha_id: Mapped[str] = mapped_column(String(14), primary_key=True)
    abha_address: Mapped[str | None] = mapped_column(String(255))

    name: Mapped[str] = mapped_column(EncryptedText, nullable=False)
    mobile_number: Mapped[str | None] = mapped_column(EncryptedText)
    email_address: Mapped[str | None] = mapped_column(EncryptedText)
    mobile_blind_idx: Mapped[str | None] = mapped_column(String(BLIND_INDEX_LENGTH))

    date_of_birth: Mapped[date | None] = mapped_column(Date)
    gender: Mapped[str] = mapped_column(String(20), nullable=False)
    address: Mapped[str | None] = mapped_column(Text)
    district: Mapped[str | None] = mapped_column(String(120))
    state: Mapped[str | None] = mapped_column(String(120))
    pincode: Mapped[str | None] = mapped_column(String(6))
    photo_url_mock: Mapped[str | None] = mapped_column(Text)
    kyc_verified: Mapped[bool] = mapped_column(Boolean, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
