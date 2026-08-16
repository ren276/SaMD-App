"""Patient request and response models. See api-contract.md section 3.

Server-side validation repeats what RegisterViewModel already enforces on the device
(REQ-REG-01, REQ-REG-02, REQ-REG-03). That is not redundancy: a client is not a trust boundary.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Annotated, Any

from pydantic import BaseModel, Field, model_validator

from app.schemas.common import StrictModel

PATIENT_ID_PATTERN = r"^[A-Za-z0-9]{10,12}$"
DIGITS_10 = r"^[0-9]{10}$"
DIGITS_12 = r"^[0-9]{12}$"
DIGITS_14 = r"^[0-9]{14}$"
DIGITS_6 = r"^[0-9]{6}$"

MobileNumber = Annotated[str, Field(pattern=DIGITS_10)]
Pincode = Annotated[str, Field(pattern=DIGITS_6)]
MAX_AGE_YEARS = 130


class PatientBase(StrictModel):
    """Fields shared by create and update. Every one mirrors a PatientEntity property."""

    full_name: str | None = Field(default=None, min_length=1, max_length=200)
    date_of_birth: date | None = None
    age: int | None = Field(default=None, ge=0, le=MAX_AGE_YEARS)
    biological_sex: str | None = Field(default=None, max_length=20)
    guardian_or_spouse_name: str | None = Field(default=None, max_length=200)
    guardian_relation: str | None = Field(default=None, max_length=50)
    mobile_number: MobileNumber | None = None
    aadhaar_number: Annotated[str, Field(pattern=DIGITS_12)] | None = None
    # Bare 14 digits. The dash-formatted XX-XXXX-XXXX-XXXX form is display only and is never
    # stored or transmitted.
    abha_number: Annotated[str, Field(pattern=DIGITS_14)] | None = None
    abha_address: str | None = Field(default=None, max_length=255)
    abha_status: str | None = Field(default=None, max_length=20)
    kyc_status: str | None = Field(default=None, max_length=20)
    verification_source: str | None = Field(default=None, max_length=40)
    verified_at: datetime | None = None
    village: str | None = Field(default=None, max_length=120)
    block: str | None = Field(default=None, max_length=120)
    district: str | None = Field(default=None, max_length=120)
    state: str | None = Field(default=None, max_length=120)
    pincode: Pincode | None = None
    category: str | None = Field(default=None, max_length=50)
    marital_status: str | None = Field(default=None, max_length=50)
    blood_group: str | None = Field(default=None, max_length=10)
    emergency_contact: MobileNumber | None = None
    primary_care_clinic_name: str | None = Field(default=None, max_length=200)
    referring_physician_name: str | None = Field(default=None, max_length=200)


class PatientCreate(PatientBase):
    """POST /api/v1/patients.

    id is client-generated and required. The device mints it offline at registration
    (REQ-REG-03) and the server never reassigns it, which is what makes a retried create an
    idempotent upsert rather than a duplicate.
    """

    id: str = Field(pattern=PATIENT_ID_PATTERN)
    full_name: str = Field(min_length=1, max_length=200)
    biological_sex: str = Field(min_length=1, max_length=20)
    created_at: datetime
    updated_at: datetime

    @model_validator(mode="after")
    def check_required_combinations(self) -> PatientCreate:
        problems: list[dict[str, str]] = []
        # REQ-REG-01: at least one contact method.
        if not self.mobile_number and not (self.village or self.district):
            problems.append(
                {
                    "field": "mobile_number",
                    "message": "A mobile number or an address is required.",
                }
            )
        # Age-only entry is common in rural registration, so either is acceptable, neither is not.
        if self.date_of_birth is None and self.age is None:
            problems.append({"field": "age", "message": "A date of birth or an age is required."})
        if problems:
            raise ValueError(problems)
        return self


class PatientUpdate(PatientBase):
    """PATCH /api/v1/patients/{id}. Only fields present in the body are written.

    base_version is the server_version the client last saw. A mismatch is SAMD-SYNC-6004 rather
    than a silent overwrite. id, created_at, and facility_id are immutable and are not accepted
    here at all; sending one is a 422 from extra="forbid".
    """

    updated_at: datetime
    base_version: int | None = Field(default=None, ge=1)


class PatientWriteResult(BaseModel):
    """Create and update response.

    Deliberately does not echo the patient body. Sending identity fields back over the wire for
    no reason widens the exposure surface, and the client already has the record
    (api-contract.md section 3.1).
    """

    id: str
    server_version: int
    created_at: datetime
    updated_at: datetime
    facility_id: str


class PatientRosterEntry(BaseModel):
    """A projection, not a patient record.

    Aadhaar, ABHA, mobile, and address are absent by construction: a roster is a list of who is
    here today, and it does not need identifiers (REQ-ROS-02, hazard H-04).
    """

    id: str
    full_name: str
    age: int | None
    biological_sex: str
    last_encounter_at: datetime
    server_version: int


class PatientRoster(BaseModel):
    patients: list[PatientRosterEntry]
    next_cursor: str | None


class PatientDetail(BaseModel):
    """GET /api/v1/patients/{id}. The full record, decrypted."""

    model_config = {"extra": "forbid"}

    id: str
    full_name: str
    date_of_birth: date | None
    age: int | None
    biological_sex: str
    guardian_or_spouse_name: str | None
    guardian_relation: str | None
    mobile_number: str | None
    aadhaar_number: str | None
    abha_number: str | None
    abha_address: str | None
    abha_status: str | None
    kyc_status: str | None
    verification_source: str | None
    verified_at: datetime | None
    village: str | None
    block: str | None
    district: str | None
    state: str | None
    pincode: str | None
    category: str | None
    marital_status: str | None
    blood_group: str | None
    emergency_contact: str | None
    primary_care_clinic_name: str | None
    referring_physician_name: str | None
    created_at: datetime
    updated_at: datetime
    server_version: int
    facility_id: str

    @classmethod
    def from_row(cls, row: Any) -> PatientDetail:
        return cls(
            id=row.id,
            full_name=row.full_name,
            date_of_birth=row.date_of_birth,
            age=row.age,
            biological_sex=row.biological_sex,
            guardian_or_spouse_name=row.guardian_or_spouse_name,
            guardian_relation=row.guardian_relation,
            mobile_number=row.mobile_number,
            aadhaar_number=row.aadhaar_number,
            abha_number=row.abha_number,
            abha_address=row.abha_address,
            abha_status=row.abha_status,
            kyc_status=row.kyc_status,
            verification_source=row.verification_source,
            verified_at=row.verified_at,
            village=row.village,
            block=row.block,
            district=row.district,
            state=row.state,
            pincode=row.pincode,
            category=row.category,
            marital_status=row.marital_status,
            blood_group=row.blood_group,
            emergency_contact=row.emergency_contact,
            primary_care_clinic_name=row.primary_care_clinic_name,
            referring_physician_name=row.referring_physician_name,
            created_at=row.created_at,
            updated_at=row.updated_at,
            server_version=row.server_version,
            facility_id=row.facility_id,
        )
