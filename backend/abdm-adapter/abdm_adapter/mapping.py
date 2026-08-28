"""Real ABDM response body -> the pinned `AbhaIdentity` shape. See
docs/requirements/abha-internal-contract.md's field diff for the full reasoning behind every line
here; this module is that table turned into code, nothing more.

D5, non-negotiable: `profilePhoto`/`kycPhoto` (inline base64 image bytes) arrive on the raw ABDM
body but are never extracted by this function: there is no `body.get("profilePhoto")` or
`body.get("kycPhoto")` call anywhere below, so the bytes never reach a local variable, let alone an
attribute, a logger, or a dict this module returns. There is nothing to redact because the value
never exists inside this function's scope at all.
"""

from __future__ import annotations

from typing import Any

from app.db.base import utcnow

from abdm_adapter.dob import from_split_fields

VERIFICATION_SOURCE = "ABDM_AADHAAR_OTP"


def strip_abha_number_dashes(value: str) -> str:
    """`"91-7561-4088-0001"` -> `"91756140880001"`. `AbhaProfile.abha_id` is documented as
    "14 bare digits, never the dash-formatted display form"; ABDM always returns the dashed form.
    """
    return value.replace("-", "")


def profile_to_abha_identity(body: dict[str, Any]) -> dict[str, Any]:
    """Build the `AbhaIdentity` dict from a real `profile/account` response body.

    Deliberately returns a plain dict, not the pydantic model: the caller constructs
    `AbhaIdentity(**this)`, which is where `photo_url`/etc get their final validation. Keeping the
    boundary here as a dict keeps this function's only job "extract and normalise fields off a raw
    ABDM body," not "know about the pydantic schema too."
    """
    date_of_birth = from_split_fields(
        year=body.get("yearOfBirth"), month=body.get("monthOfBirth"), day=body.get("dayOfBirth")
    )
    return {
        "abha_number": strip_abha_number_dashes(str(body["ABHANumber"])),
        "abha_address": body.get("preferredAbhaAddress"),
        "name": str(body.get("name", "")),
        "date_of_birth": date_of_birth,
        "gender": str(body.get("gender", "")),
        "address": body.get("address"),
        "district": body.get("districtName"),
        "state": body.get("stateName"),
        "pincode": body.get("pincode"),
        # D4 (docs/requirements/abha-internal-contract.md), CONTRADICTED 2026-08-28: this was
        # assumed always-masked from the recorded example, but a prior watched live run observed
        # a full, unmasked mobile on this same field, and a 2026-08-28 run redacted its own
        # observation before it could adjudicate either way. Neither shape is confirmed; do not
        # assume masked. service.py's _extract_masked_mobile is suspect on the same assumption,
        # pending a deliberate, unredacted recheck. See mobile_number's own note in
        # docs/requirements/abha-field-mapping.md for the Android-side REQ-REG-01 consequence.
        "mobile_number": body.get("mobile"),
        "email_address": None,
        # D5: always null. The real response's profilePhoto/kycPhoto bytes are simply never
        # extracted (no body.get call for either key anywhere in this function), so they never
        # exist as a value here at all, let alone touch this return dict.
        "photo_url": None,
        "kyc_verified": bool(body.get("kycVerified", False)),
        "verification_source": VERIFICATION_SOURCE,
        "verified_at": utcnow(),
    }
