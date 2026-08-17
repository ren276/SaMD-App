"""The PHI boundary guard for the kernel proxy (H-10, REQ-HAN-06, SAMD-KERN-5005).

The Android app already guarantees structurally that no Patient object can reach the kernel:
KernelPayload has no Patient-typed field, and SendToKernelUseCase accepts only
VitalsReading + Consultation + an opaque case token. The backend must reproduce that guarantee
rather than inherit it by assumption, because the backend, unlike the device, holds the full
patient row in the same process, and a careless join could put a name in an outbound payload.

Two mechanisms, both required (api-contract.md section 5.2):

1. The request Pydantic models (app/schemas/kernel.py) declare only the pseudonymized clinical
   fields, with extra="forbid". An unexpected field is a 422 at parse time, before this module
   ever runs.
2. This module: an explicit denylist checked against the parsed body before forwarding. This is
   deliberately redundant with (1). extra="forbid" only rejects a field that is NOT declared on
   the model; it does nothing if a future edit adds one of these names AS a declared field
   without thinking about what it is. The denylist catches that case, which (1) structurally
   cannot.

DENYLIST is the one place this vocabulary lives. Anyone adding a field to a kernel request model
must check this list first, and the parametrised test in tests/test_kernel_phi_guard.py makes
adding a term to the list automatically add a test for it.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from app.errors import ErrorCode, SamdError

DENYLIST: frozenset[str] = frozenset(
    {
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
        # Added in the Phase 3 fix pass (B2): pincode/block/village plus age plus biological sex
        # is the classic re-identification triple (see docs/quality/risk-management-file.md
        # RR-01), and all three of pincode/block/village passed this guard before this change.
        # date_of_birth and its naming variants are a direct identifier on their own, more
        # precise than the `age` field the vitals models legitimately take (age stays allowed).
        "date_of_birth",
        "dob",
        "birth_date",
        "pincode",
        "pin_code",
        "block",
        "address_line",
    }
)


def assert_no_identity_fields(payload: Mapping[str, Any]) -> None:
    """Raise SAMD-KERN-5005 if any denylisted key is present in a parsed kernel request body.

    Field names only in the error detail, never values: naming which key was present does not
    leak PHI, the way echoing its value would.
    """
    present = sorted(DENYLIST & payload.keys())
    if present:
        raise SamdError(
            ErrorCode.KERN_IDENTITY_LEAK_BLOCKED,
            detail=f"Identity field(s) present on the kernel boundary: {', '.join(present)}.",
            log_context={"identity_fields": present},
        )
