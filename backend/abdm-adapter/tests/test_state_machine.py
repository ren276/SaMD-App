"""The AbhaTransaction state machine. Every legal transition and every illegal one, checked
explicitly rather than assuming the table is symmetric or exhaustive by inspection alone."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from app.db.base import utcnow
from app.errors import ErrorCode, SamdError
from app.models.enums import AbhaTransactionState as State

from abdm_adapter.transaction import (
    ALLOWED_TRANSITIONS,
    check_not_expired,
    mobile_verification_needed,
    validate_transition,
)

LEGAL_PATH = [
    (State.STARTED, State.IDENTITY_SUBMITTED),
    (State.IDENTITY_SUBMITTED, State.OTP_REQUESTED),
    (State.OTP_REQUESTED, State.OTP_VERIFIED),
    (State.OTP_VERIFIED, State.ENROLLED),
    (State.ENROLLED, State.MOBILE_VERIFICATION_REQUIRED),
    (State.MOBILE_VERIFICATION_REQUIRED, State.MOBILE_VERIFIED),
    (State.MOBILE_VERIFIED, State.PROFILE_RETRIEVED),
    (State.PROFILE_RETRIEVED, State.COMPLETED),
]

ENROLLED_SKIP_MOBILE = (State.ENROLLED, State.PROFILE_RETRIEVED)

ALL_STATES = list(State)


@pytest.mark.parametrize("current,target", LEGAL_PATH)
def test_every_step_of_the_happy_path_is_legal(current: State, target: State) -> None:
    validate_transition(current=current, target=target)  # must not raise


def test_enrolled_can_skip_straight_to_profile_retrieved_when_no_mobile_check_needed() -> None:
    validate_transition(current=ENROLLED_SKIP_MOBILE[0], target=ENROLLED_SKIP_MOBILE[1])


@pytest.mark.parametrize(
    "current,target",
    [
        (State.STARTED, State.OTP_REQUESTED),  # skipping identity submission
        (State.STARTED, State.ENROLLED),  # skipping everything
        (State.OTP_REQUESTED, State.ENROLLED),  # skipping OTP_VERIFIED
        (State.ENROLLED, State.STARTED),  # backward
        (State.COMPLETED, State.STARTED),  # terminal, cannot move at all
        (State.COMPLETED, State.PROFILE_RETRIEVED),
        (State.FAILED, State.STARTED),  # terminal
        (State.MOBILE_VERIFICATION_REQUIRED, State.PROFILE_RETRIEVED),  # skipping MOBILE_VERIFIED
        (State.STARTED, State.STARTED),  # re-asserting current state is NOT a no-op for ABHA
    ],
)
def test_illegal_transitions_raise_samd_abha_2002(current: State, target: State) -> None:
    with pytest.raises(SamdError) as exc_info:
        validate_transition(current=current, target=target)
    assert exc_info.value.code == ErrorCode.ABHA_INVALID_STATE
    assert exc_info.value.status == 409


def test_every_state_has_a_transitions_entry() -> None:
    """No state silently falls through to an empty-set default by omission."""
    assert set(ALLOWED_TRANSITIONS.keys()) == set(ALL_STATES)


def test_terminal_states_have_no_outgoing_transitions() -> None:
    for state in (State.COMPLETED, State.FAILED, State.EXPIRED):
        assert ALLOWED_TRANSITIONS[state] == frozenset()


def test_check_not_expired_passes_when_expiry_is_in_the_future() -> None:
    check_not_expired(state=State.STARTED, expires_at=utcnow() + timedelta(minutes=5))


def test_check_not_expired_raises_when_expiry_has_passed() -> None:
    with pytest.raises(SamdError) as exc_info:
        check_not_expired(state=State.OTP_REQUESTED, expires_at=utcnow() - timedelta(seconds=1))
    assert exc_info.value.code == ErrorCode.ABHA_SESSION_EXPIRED


def test_check_not_expired_is_a_no_op_for_terminal_states() -> None:
    ancient = datetime(2000, 1, 1, tzinfo=UTC)
    for state in (State.COMPLETED, State.FAILED, State.EXPIRED):
        check_not_expired(state=state, expires_at=ancient)  # must not raise


def test_mobile_verification_needed_when_numbers_differ() -> None:
    assert mobile_verification_needed(
        submitted_mobile="9876543210", enrolled_masked_mobile="******0903"
    )


def test_mobile_verification_not_needed_when_suffix_matches() -> None:
    assert not mobile_verification_needed(
        submitted_mobile="8446650903", enrolled_masked_mobile="******0903"
    )
