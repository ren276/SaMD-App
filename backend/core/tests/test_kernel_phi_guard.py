"""The PHI boundary guard. api-contract.md section 5.2, hazard H-10, REQ-HAN-06.

Two mechanisms exist (app/adapters/kernel/phi_guard.py): Pydantic's extra="forbid" and this
denylist. Because none of the denied names are declared fields on KernelAssessRequest or
KernelEvaluateRequest today, extra="forbid" always catches them first at the HTTP boundary,
before the denylist ever runs, so an end-to-end POST with e.g. "full_name" in the body gets
PAT_VALIDATION_FAILED, not KERN_IDENTITY_LEAK_BLOCKED (see
test_kernel.py::test_declared_but_undenylisted_extra_field_is_still_a_422 for that path).

The denylist's job is different: it is the backstop for the day someone adds one of these names
AS A DECLARED FIELD on a kernel schema without checking this list first. That case cannot be
exercised through the schema (the schema would accept the field), so it is exercised directly
against the guard function here.
"""

from __future__ import annotations

import pytest

from app.adapters.kernel.phi_guard import DENYLIST, assert_no_identity_fields
from app.errors import ErrorCode, SamdError


@pytest.mark.parametrize("field", sorted(DENYLIST))
def test_each_denylisted_field_is_rejected(field: str) -> None:
    """Parameterised over DENYLIST itself: adding a term to the list automatically adds a test
    for it, per the Phase 3 brief."""
    payload = {"case_token": "cr-1", "age": 35, field: "should never reach the kernel"}

    with pytest.raises(SamdError) as caught:
        assert_no_identity_fields(payload)

    assert caught.value.code is ErrorCode.KERN_IDENTITY_LEAK_BLOCKED
    assert caught.value.status == 422


def test_a_clean_payload_passes() -> None:
    assert_no_identity_fields(
        {
            "case_token": "cr-1",
            "age": 35,
            "sex": "F",
            "systolic_bp": 128.0,
            "spo2": 97.0,
        }
    )


def test_multiple_denylisted_fields_are_all_named_in_the_detail() -> None:
    """detail may name which fields were present (that is not PHI), never their values."""
    with pytest.raises(SamdError) as caught:
        assert_no_identity_fields(
            {"case_token": "cr-1", "full_name": "Sunita Devi", "mobile_number": "9876543210"}
        )

    assert "full_name" in caught.value.detail
    assert "mobile_number" in caught.value.detail
    assert "Sunita Devi" not in caught.value.detail
    assert "9876543210" not in caught.value.detail


def test_the_denylist_is_frozen() -> None:
    """A mutable module-level set is one accidental .add() away from silently losing a name."""
    assert isinstance(DENYLIST, frozenset)


def test_denylist_covers_the_fields_named_in_api_contract_section_5_2() -> None:
    """api-contract.md section 5.2 names this exact set. Kept in sync deliberately: if either
    list grows, the other should too."""
    contract_fields = {
        "full_name",
        "name",
        "aadhaar_number",
        "aadhaar",
        "abha_number",
        "abha_address",
        "mobile_number",
        "mobile",
        "emergency_contact",
        "guardian_or_spouse_name",
        "address",
        "village",
        "patient_id",
        "patient_uid",
        "date_of_birth",
        "dob",
        "birth_date",
        "pincode",
        "pin_code",
        "block",
        "address_line",
    }
    assert DENYLIST == contract_fields


def test_age_is_not_denylisted() -> None:
    """age is a legitimate clinical signal the vitals models take, and is in the shipped
    /assess contract (api-contract.md section 5.3). It must never end up on this list by
    accident alongside date_of_birth."""
    assert "age" not in DENYLIST
