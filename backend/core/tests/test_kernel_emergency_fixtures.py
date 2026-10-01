"""kernel_derivation against real classifier output.

The fixtures under tests/fixtures/classifier/ are real /v1/assess responses captured from
SaMDClassifier (each file's _provenance names the commit). The hand-written bodies in
test_kernel_derivation.py used urgency tokens the classifier never sends, which is how an
EMERGENCY_REFERRAL result was derived as LOW risk with no verification and nothing noticed.

Every expected value comes from expectations.json in the same directory, which the device test
reads too, so the two sides cannot hold different expectations. The edited cases replace one
field of a real response, so every other field stays what the classifier actually emits.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest

from app.domain.kernel_derivation import DERIVATION_RULE_VERSION, derive_assess

_FIXTURES = Path(__file__).parent / "fixtures" / "classifier"
_CASES: list[dict[str, Any]] = json.loads((_FIXTURES / "expectations.json").read_text())["cases"]


def _body(case: dict[str, Any]) -> dict[str, Any]:
    body = copy.deepcopy(json.loads((_FIXTURES / case["fixture"]).read_text())["response"])
    edit = case["edit"]
    if "triage_urgency" in edit:
        body["triage_urgency"] = edit["triage_urgency"]
    if "condition_tier" in edit:
        body["differential_diagnosis"][0]["condition_tier"] = edit["condition_tier"]
    return body


@pytest.mark.parametrize("case", _CASES, ids=[c["name"] for c in _CASES])
def test_derivation_matches_the_shared_expectation(case: dict[str, Any]) -> None:
    derived = derive_assess(_body(case))
    expected = case["expected"]
    assert (
        derived.urgency_level,
        derived.risk_category,
        derived.requires_human_verification,
        list(derived.unrecognised_fields),
    ) == (
        expected["urgency"],
        expected["risk"],
        expected["verification"],
        expected["unrecognised"],
    )


def test_rule_version_was_bumped_for_the_v2_rules() -> None:
    assert DERIVATION_RULE_VERSION == "HAN-07/08-v2"


def test_backend_rule_version_equals_the_shared_fixture_value() -> None:
    """The device suite asserts its own constant against the same value (no hand-kept parity)."""
    shared = json.loads((_FIXTURES / "expectations.json").read_text())["derivation_rule_version"]
    assert DERIVATION_RULE_VERSION == shared
