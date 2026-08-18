"""D3: two real ABDM DOB shapes in one flow, normalised to one canonical ISO output, and the
year-only case represented honestly rather than fabricated."""

from __future__ import annotations

from abdm_adapter.dob import from_ddmmyyyy, from_split_fields


def test_from_split_fields_full_date() -> None:
    assert from_split_fields(year="1991", month="04", day="12") == "1991-04-12"


def test_from_split_fields_year_only_month_zero() -> None:
    """ABDM's own convention for "no month/day known": "00", not absent. Must not become
    "1991-01-01"."""
    assert from_split_fields(year="1991", month="00", day="00") == "1991"


def test_from_split_fields_year_only_empty_strings() -> None:
    assert from_split_fields(year="1991", month="", day="") == "1991"


def test_from_split_fields_no_year_is_none() -> None:
    assert from_split_fields(year=None, month="04", day="12") is None
    assert from_split_fields(year="00", month="04", day="12") is None


def test_from_ddmmyyyy_full_date() -> None:
    assert from_ddmmyyyy("12-04-1991") == "1991-04-12"


def test_from_ddmmyyyy_year_only() -> None:
    assert from_ddmmyyyy("00-00-1991") == "1991"


def test_from_ddmmyyyy_none_or_empty() -> None:
    assert from_ddmmyyyy(None) is None
    assert from_ddmmyyyy("") is None


def test_from_ddmmyyyy_malformed_is_none_not_a_crash() -> None:
    assert from_ddmmyyyy("not-a-date") is None
