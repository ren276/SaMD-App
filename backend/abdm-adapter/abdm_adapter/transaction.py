"""The AbhaTransaction state machine. Server-enforced; an out-of-order call is 409
SAMD-ABHA-2002, never a silent no-op (api-contract.md section 8, backend-prd.md section 4.5's
sibling for ABHA).

`IDENTITY_SUBMITTED` and `OTP_VERIFIED` are transient: a single internal endpoint call passes
through one of them and lands on the next externally-visible resting state
(`OTP_REQUESTED`/`ENROLLED` respectively) within the same request, matching the response shapes
api-contract.md section 8 already pins (`POST .../identity` returns `"state": "OTP_REQUESTED"`
directly, never `"IDENTITY_SUBMITTED"`). `PROFILE_RETRIEVED` similarly collapses into `COMPLETED`
within the one `GET .../profile` call. They stay in `AbhaTransactionState` (and in this table)
because a transaction can still fail while sitting in one of them, and `FAILED`/`EXPIRED` need a
real prior state to have been left from for the audit trail to read honestly.

`ENROLLED` branches: it can go straight to `PROFILE_RETRIEVED`/`COMPLETED` (no mobile check
needed) or into `MOBILE_VERIFICATION_REQUIRED` first, per `mobile_verification_needed` below,
which is this adapter's own decision procedure, not something ABDM's response states outright.
"""

from __future__ import annotations

from datetime import datetime

from app.db.base import utcnow
from app.errors import ErrorCode, SamdError
from app.models.enums import AbhaTransactionState as State

ALLOWED_TRANSITIONS: dict[State, frozenset[State]] = {
    State.STARTED: frozenset({State.IDENTITY_SUBMITTED, State.FAILED, State.EXPIRED}),
    State.IDENTITY_SUBMITTED: frozenset({State.OTP_REQUESTED, State.FAILED, State.EXPIRED}),
    State.OTP_REQUESTED: frozenset({State.OTP_VERIFIED, State.FAILED, State.EXPIRED}),
    State.OTP_VERIFIED: frozenset({State.ENROLLED, State.FAILED}),
    State.ENROLLED: frozenset(
        {State.MOBILE_VERIFICATION_REQUIRED, State.PROFILE_RETRIEVED, State.FAILED}
    ),
    State.MOBILE_VERIFICATION_REQUIRED: frozenset(
        {State.MOBILE_VERIFIED, State.FAILED, State.EXPIRED}
    ),
    State.MOBILE_VERIFIED: frozenset({State.PROFILE_RETRIEVED, State.FAILED}),
    State.PROFILE_RETRIEVED: frozenset({State.COMPLETED, State.FAILED}),
    State.COMPLETED: frozenset(),
    State.FAILED: frozenset(),
    State.EXPIRED: frozenset(),
}


def check_not_expired(*, state: State, expires_at: datetime) -> None:
    """Raise SESSION_EXPIRED before any transition is attempted, regardless of what the caller
    asked to do. Expiry is a property of time, not of an explicit client-invoked transition."""
    if state in (State.COMPLETED, State.FAILED, State.EXPIRED):
        return
    if utcnow() >= expires_at:
        raise SamdError(
            ErrorCode.ABHA_SESSION_EXPIRED, detail="This registration session has expired."
        )


def validate_transition(*, current: State, target: State) -> None:
    """Raise SAMD-ABHA-2002 on any transition not in ALLOWED_TRANSITIONS. Re-asserting the
    current state is not treated as a no-op success here (unlike CaseStatus's state machine in
    app/services/encounter.py): every ABHA endpoint call is expected to move the transaction
    forward exactly once, so a repeat call at the same state is genuinely out of order for this
    flow, not a benign retry, and REQ-AUD-02-style idempotency for ABHA sessions is a Phase 6/
    later concern, not built here.
    """
    allowed = ALLOWED_TRANSITIONS.get(current, frozenset())
    if target not in allowed:
        raise SamdError(
            ErrorCode.ABHA_INVALID_STATE,
            detail=f"Cannot move from {current.value} to {target.value}.",
        )


def mobile_verification_needed(*, submitted_mobile: str, enrolled_masked_mobile: str) -> bool:
    """Whether the communication mobile the worker entered differs from the Aadhaar-linked mobile
    ABDM already has on file, per the xlsx test matrix rows CRT_ABHA_108/109: same number skips
    straight to enrollment completion, different number requires an explicit mobile-OTP step.

    ABDM never discloses the full Aadhaar-linked mobile (Phase A finding, D4): only the last four
    digits are visible, in `enrolled_masked_mobile` (e.g. "******0903"). Comparing the visible
    suffix is the only comparison ABDM's own response shape makes possible; it cannot detect two
    different numbers that happen to share their last four digits, and that is a real, accepted
    limitation of the source data, not a bug in this comparison.
    """
    suffix = enrolled_masked_mobile[-4:]
    return not submitted_mobile.endswith(suffix)
