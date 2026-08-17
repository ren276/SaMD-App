"""The HMAC case pseudonym. Decision D-7, api-contract.md section 5.2."""

from __future__ import annotations

from app.adapters.kernel.pseudonym import CASE_TOKEN_LENGTH, case_token_for

KEY = "test-only-case-token-key-at-least-32-chars"


def test_case_token_is_stable_for_the_same_case_record_id() -> None:
    assert case_token_for("cr-88f1", key=KEY) == case_token_for("cr-88f1", key=KEY)


def test_case_token_differs_across_case_record_ids() -> None:
    assert case_token_for("cr-88f1", key=KEY) != case_token_for("cr-88f2", key=KEY)


def test_case_token_differs_across_keys() -> None:
    """A pseudonym computed under one key must not collide with another deployment's key."""
    assert case_token_for("cr-88f1", key=KEY) != case_token_for("cr-88f1", key="a-different-key")


def test_case_token_is_16_hex_characters() -> None:
    token = case_token_for("cr-88f1", key=KEY)
    assert len(token) == CASE_TOKEN_LENGTH
    assert all(c in "0123456789abcdef" for c in token)


def test_case_token_never_contains_the_case_record_id_as_a_substring() -> None:
    """It is a pseudonym, not an encoding. Nothing about the token should leak the input back."""
    case_record_id = "cr-88f1-a-fairly-long-identifier-value"
    token = case_token_for(case_record_id, key=KEY)
    assert case_record_id not in token
