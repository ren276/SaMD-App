"""Internal SaMD workflow request/response models. Pinned shapes, api-contract.md section 8.

Reuses `app.schemas.common.StrictModel` (extra="forbid") from backend/core, the same boundary
discipline every other request model in this service already follows: an unexpected field is a
422, not a silent pass-through.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from app.schemas.common import StrictModel
from pydantic import BaseModel, Field

AADHAAR_PATTERN = r"^[0-9]{12}$"
OTP_PATTERN = r"^[0-9]{6}$"
MOBILE_PATTERN = r"^[0-9]{10}$"


class IdentitySubmit(StrictModel):
    aadhaar_number: str = Field(pattern=AADHAAR_PATTERN)


class OtpVerify(StrictModel):
    otp: str = Field(pattern=OTP_PATTERN)
    # The communication mobile for this ABHA account. Plaintext on the wire to ABDM in
    # enrol/byAadhaar's own body (Phase A: this field is not RSA-encrypted in the recorded
    # example, unlike loginId/otpValue); still never logged or audited, matching every other
    # mobile number in this system (REDACTED_KEYS covers "mobile"/"mobile_number").
    mobile_number: str = Field(pattern=MOBILE_PATTERN)


class MobileOtpVerify(StrictModel):
    otp: str = Field(pattern=OTP_PATTERN)


class RegistrationSessionResult(BaseModel):
    session_id: str
    state: str
    expires_at: datetime


class IdentitySubmitResult(BaseModel):
    session_id: str
    state: str
    masked_mobile: str | None = None


class OtpVerifyResult(BaseModel):
    session_id: str
    state: str


class SessionPollResult(BaseModel):
    session_id: str
    state: str
    last_error: str | None = None


class AbhaIdentity(BaseModel):
    """Field-for-field the object pinned in api-contract.md section 8. See
    docs/requirements/abha-internal-contract.md's field diff for exactly how each of these is
    derived from the real V3 `/profile/account` response, and which real fields have no slot
    here on purpose (subdistrictName, status, authMethods, tags, localizedDetails, kycPhoto)."""

    abha_number: str
    abha_address: str | None
    name: str
    date_of_birth: str | None
    gender: str
    address: str | None
    district: str | None
    state: str | None
    pincode: str | None
    mobile_number: str | None
    email_address: str | None
    photo_url: str | None
    kyc_verified: bool
    verification_source: str
    verified_at: datetime


def envelope_data(model: BaseModel) -> dict[str, Any]:
    return model.model_dump()
