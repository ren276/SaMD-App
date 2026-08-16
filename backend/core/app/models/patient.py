"""patients: the highest-sensitivity table in the system (hazard H-04, DPDP).

Mirrors PatientEntity field for field, plus the ABHA columns that land on the device in Room
migration MIGRATION_12_13, plus the SyncMixin bookkeeping.

Encryption boundary, decided deliberately per column:

    encrypted   full_name, aadhaar_number, mobile_number, emergency_contact,
                guardian_or_spouse_name
    blind index full_name, aadhaar_number, mobile_number
    plaintext   abha_number, village, block, district, state, pincode, biological_sex, age,
                date_of_birth, category, marital_status, blood_group, guardian_relation,
                primary_care_clinic_name, referring_physician_name

guardian_or_spouse_name is encrypted although it was not on the original list: it is a person's
name, and encrypting full_name while leaving another human being's name in plaintext beside it
would be incoherent.

abha_number is deliberately NOT encrypted. It is already a pseudonymous government identifier
rather than a direct identifier, and it needs exact-match lookup plus a UNIQUE constraint, which
ciphertext cannot provide. The UNIQUE constraint is what enforces SAMD-PAT-3004, the
wrong-patient guard (hazard H-03).

district and state stay plaintext so facility-level aggregate queries are possible without
decrypting every row. At PHC granularity they are not identifying on their own.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import (
    CheckConstraint,
    Date,
    DateTime,
    Index,
    Integer,
    String,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.db.types import BLIND_INDEX_LENGTH, EncryptedText
from app.models.mixins import PATIENT_ID_LENGTH, SyncMixin, sync_state_check

ABHA_NUMBER_LENGTH = 14


class Patient(Base, SyncMixin):
    __tablename__ = "patients"
    __table_args__ = (
        sync_state_check("patients"),
        # REQ-REG-03: 10 to 12 alphanumeric characters, minted by the device from SecureRandom
        # over a 62-character alphabet. Enforced here as well as in Pydantic, because a client is
        # not a trust boundary.
        CheckConstraint("id ~ '^[A-Za-z0-9]{10,12}$'", name="ck_patients_id_format"),
        # Bare 14 digits, never the dash-formatted display form.
        CheckConstraint(
            "abha_number IS NULL OR abha_number ~ '^[0-9]{14}$'",
            name="ck_patients_abha_format",
        ),
        # REQ-REG-01: at least one contact method. Expressed against the blind index rather than
        # the ciphertext, because a pgcrypto value is opaque to a CHECK.
        CheckConstraint(
            "mobile_blind_idx IS NOT NULL OR village IS NOT NULL OR district IS NOT NULL",
            name="ck_patients_contact_method",
        ),
        CheckConstraint(
            "age IS NULL OR (age >= 0 AND age <= 130)",
            name="ck_patients_age_range",
        ),
        Index("ix_patients_abha_number", "abha_number", unique=True),
        Index("ix_patients_name_blind_idx", "name_blind_idx"),
        Index("ix_patients_aadhaar_blind_idx", "aadhaar_blind_idx"),
        Index("ix_patients_mobile_blind_idx", "mobile_blind_idx"),
        Index("ix_patients_facility_updated", "facility_id", "updated_at"),
    )

    id: Mapped[str] = mapped_column(String(PATIENT_ID_LENGTH), primary_key=True)

    # Encrypted identity
    full_name: Mapped[str] = mapped_column(EncryptedText, nullable=False)
    guardian_or_spouse_name: Mapped[str | None] = mapped_column(EncryptedText)
    mobile_number: Mapped[str | None] = mapped_column(EncryptedText)
    aadhaar_number: Mapped[str | None] = mapped_column(EncryptedText)
    emergency_contact: Mapped[str | None] = mapped_column(EncryptedText)

    # Blind indexes. HMAC-SHA256 of the normalised plaintext, truncated. Lets an encrypted column
    # be found by exact match without decrypting the table.
    name_blind_idx: Mapped[str | None] = mapped_column(String(BLIND_INDEX_LENGTH))
    aadhaar_blind_idx: Mapped[str | None] = mapped_column(String(BLIND_INDEX_LENGTH))
    mobile_blind_idx: Mapped[str | None] = mapped_column(String(BLIND_INDEX_LENGTH))

    # Plaintext demographics
    date_of_birth: Mapped[date | None] = mapped_column(Date)
    age: Mapped[int | None] = mapped_column(Integer)
    biological_sex: Mapped[str] = mapped_column(String(20), nullable=False)
    guardian_relation: Mapped[str | None] = mapped_column(String(50))
    village: Mapped[str | None] = mapped_column(String(120))
    block: Mapped[str | None] = mapped_column(String(120))
    district: Mapped[str | None] = mapped_column(String(120))
    state: Mapped[str | None] = mapped_column(String(120))
    pincode: Mapped[str | None] = mapped_column(String(6))
    category: Mapped[str | None] = mapped_column(String(50))
    marital_status: Mapped[str | None] = mapped_column(String(50))
    blood_group: Mapped[str | None] = mapped_column(String(10))
    primary_care_clinic_name: Mapped[str | None] = mapped_column(String(200))
    referring_physician_name: Mapped[str | None] = mapped_column(String(200))

    # ABHA. Arrives on the device in MIGRATION_12_13; present here from the start.
    abha_number: Mapped[str | None] = mapped_column(String(ABHA_NUMBER_LENGTH))
    abha_address: Mapped[str | None] = mapped_column(String(255))
    abha_status: Mapped[str | None] = mapped_column(String(20))
    kyc_status: Mapped[str | None] = mapped_column(String(20))
    verification_source: Mapped[str | None] = mapped_column(String(40))
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    # Device clock. Not the server's; see SyncMixin.received_at for that.
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
