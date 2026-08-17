"""app/domain/kernel_derivation.py.

Pure-function tests: no database, no HTTP, no fixtures. If any test in this file ever needs a
session, the module has stopped being a domain rule and the test is telling you so.
"""

from __future__ import annotations

from typing import Any

import pytest

from app.domain.kernel_derivation import (
    DERIVATION_RULE_VERSION,
    HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
    derive_assess,
)


def _response(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "case_token": "pseudonym",
        "safety_screen_passed": True,
        "triage_urgency": "ROUTINE",
        "differential_diagnosis": [
            {
                "condition_tier": "Viral upper respiratory infection",
                "probability": 0.81,
                "evidence_for": ["fever 3 days"],
                "evidence_against": ["no chest findings"],
            }
        ],
        "recommended_investigations": ["CBC"],
        "model_metadata": {"model_version": "xgb-2026-06-11", "inference_time_ms": 143},
    }
    body.update(overrides)
    return body


# ---------------------------------------------------------------------------
# Normal case
# ---------------------------------------------------------------------------


def test_normal_case_takes_the_top_differential() -> None:
    result = derive_assess(_response())

    assert result.predicted_condition == "Viral upper respiratory infection"
    assert result.confidence_score == pytest.approx(0.81)
    # 0.81 is below the 0.85 LOW floor and at or above the 0.65 MODERATE floor.
    assert result.risk_category == "MODERATE"
    assert result.requires_human_verification is True
    assert result.derivation_rule_version == DERIVATION_RULE_VERSION


def test_the_rule_version_is_returned_on_every_path() -> None:
    """The version is what lets an ACP tell a rule change from a model change. A derivation that
    returned it only when it happened to succeed would be useless for that."""
    for body in (_response(), _response(differential_diagnosis=[]), {}):
        assert derive_assess(body).derivation_rule_version == DERIVATION_RULE_VERSION


# ---------------------------------------------------------------------------
# The 0.90 human-verification threshold (REQ-HAN-08). A risk control, tested at its boundary.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("probability", "expected"),
    [
        (0.89, True),
        (0.899999, True),
        (0.90, False),  # strictly less than, matching Kotlin's `confidence < THRESHOLD`
        (0.91, False),
    ],
)
def test_requires_human_verification_boundary(probability: float, expected: bool) -> None:
    body = _response(
        differential_diagnosis=[{"condition_tier": "X", "probability": probability}],
    )
    assert derive_assess(body).requires_human_verification is expected


def test_the_threshold_constant_is_the_one_the_android_use_case_uses() -> None:
    """GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD = 0.90. Asserted as a
    literal so a change to the constant fails a test rather than agreeing with itself."""
    assert HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD == 0.90


# ---------------------------------------------------------------------------
# risk_category: the A0 Case 1 mapping, copied from GenerateKernelReportUseCase.tryRealApi
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("triage_urgency", "probability", "expected"),
    [
        # The EMERGENCY branch wins regardless of confidence, including at a confidence that
        # would otherwise be LOW.
        ("EMERGENCY", 0.99, "HIGH"),
        ("EMERGENT", 0.99, "HIGH"),
        ("emergency", 0.99, "HIGH"),  # uppercase() on the device
        # Outside EMERGENCY the mapping grades model uncertainty, not clinical severity, so a
        # higher confidence lands in a LOWER risk band. Reproduced from Android unchanged; see
        # D-11 and the _risk_category docstring.
        ("ROUTINE", 0.85, "LOW"),
        ("ROUTINE", 0.90, "LOW"),
        ("URGENT", 0.849999, "MODERATE"),
        ("URGENT", 0.65, "MODERATE"),
        ("ROUTINE", 0.649999, "HIGH"),
        ("ROUTINE", 0.10, "HIGH"),
        # An urgency string the kernel invents is not EMERGENCY, so the confidence bands decide.
        # No coercion to ROUTINE, no crash.
        ("SOMETHING_NEW", 0.90, "LOW"),
    ],
)
def test_risk_category_mapping(triage_urgency: str, probability: float, expected: str) -> None:
    body = _response(
        triage_urgency=triage_urgency,
        differential_diagnosis=[{"condition_tier": "X", "probability": probability}],
    )
    assert derive_assess(body).risk_category == expected


def test_critical_is_never_assigned() -> None:
    """CRITICAL exists in the RiskCategory vocabulary on both sides and is unreachable from a
    /v1/assess response, because nothing in that response distinguishes an emergency with
    imminent harm from one needing prompt attention."""
    for urgency in ("ROUTINE", "URGENT", "EMERGENCY", "EMERGENT", ""):
        for probability in (0.0, 0.5, 0.64, 0.65, 0.84, 0.85, 1.0):
            body = _response(
                triage_urgency=urgency,
                differential_diagnosis=[{"condition_tier": "X", "probability": probability}],
            )
            assert derive_assess(body).risk_category != "CRITICAL"


# ---------------------------------------------------------------------------
# Top-differential selection: the kernel's order, not ours
# ---------------------------------------------------------------------------


def test_a_tie_on_the_top_probability_resolves_to_the_first_entry() -> None:
    body = _response(
        differential_diagnosis=[
            {"condition_tier": "First", "probability": 0.7},
            {"condition_tier": "Second", "probability": 0.7},
        ]
    )
    result = derive_assess(body)
    assert result.predicted_condition == "First"
    assert result.confidence_score == pytest.approx(0.7)


def test_an_unsorted_list_still_resolves_to_the_kernels_first_entry() -> None:
    """Deliberate divergence from the fix-pass brief, which asked for the highest-probability
    entry. RetrofitKernelSource.assess takes firstOrNull() and does not sort, so a
    max-by-probability rule here would name a different top condition than the one the device
    showed the clinician. Reconstructing what was displayed is the point of this module. D-11.
    """
    body = _response(
        differential_diagnosis=[
            {"condition_tier": "Ranked first by the kernel", "probability": 0.40},
            {"condition_tier": "Higher probability, ranked second", "probability": 0.95},
        ]
    )
    result = derive_assess(body)
    assert result.predicted_condition == "Ranked first by the kernel"
    assert result.confidence_score == pytest.approx(0.40)
    assert result.requires_human_verification is True


# ---------------------------------------------------------------------------
# Nothing to derive from
# ---------------------------------------------------------------------------


def test_empty_differential_diagnosis_derives_nothing() -> None:
    """Android substitutes "Non-specific presentation" and a confidence of 0.50 here. This module
    returns None: inventing a confidence the model never produced is the defect the Phase 3 fix
    pass exists to remove."""
    result = derive_assess(_response(differential_diagnosis=[]))

    assert result.predicted_condition is None
    assert result.confidence_score is None
    assert result.risk_category is None
    assert result.requires_human_verification is None


@pytest.mark.parametrize("differentials", [None, "not a list", 42, [None], ["not a mapping"], [[]]])
def test_a_malformed_differential_list_derives_nothing_instead_of_raising(
    differentials: Any,
) -> None:
    result = derive_assess(_response(differential_diagnosis=differentials))
    assert result.predicted_condition is None
    assert result.confidence_score is None


def test_a_missing_probability_yields_a_condition_but_no_confidence() -> None:
    """Partial honesty beats a substituted zero: the condition was really returned, the
    probability really was not, and a 0.0 confidence would read as a model that was certain it
    was wrong."""
    body = _response(differential_diagnosis=[{"condition_tier": "Dengue fever"}])
    result = derive_assess(body)

    assert result.predicted_condition == "Dengue fever"
    assert result.confidence_score is None
    assert result.risk_category is None
    assert result.requires_human_verification is None


def test_a_boolean_probability_is_malformed_not_a_confidence_of_one() -> None:
    """bool is an int subclass in Python. Without an explicit check, probability=true would
    silently become a confidence of 1.0, which is the most dangerous possible reading."""
    body = _response(differential_diagnosis=[{"condition_tier": "X", "probability": True}])
    assert derive_assess(body).confidence_score is None


@pytest.mark.parametrize(
    ("probability", "expected"), [(1.4, 1.0), (-0.2, 0.0), (1.0, 1.0), (0.0, 0.0)]
)
def test_probability_is_clamped_the_way_the_device_clamps_it(
    probability: float, expected: float
) -> None:
    """RetrofitKernelSource.assess applies coerceIn(0.0, 1.0)."""
    body = _response(differential_diagnosis=[{"condition_tier": "X", "probability": probability}])
    assert derive_assess(body).confidence_score == pytest.approx(expected)


# ---------------------------------------------------------------------------
# model_metadata is not an input to derivation at all
# ---------------------------------------------------------------------------


def test_missing_model_metadata_does_not_affect_derivation() -> None:
    """model_metadata is stored raw on kernel_assessments and is not a derivation input. A
    response without it must derive exactly what a response with it derives."""
    with_metadata = derive_assess(_response())
    body = _response()
    del body["model_metadata"]
    without_metadata = derive_assess(body)

    assert with_metadata == without_metadata


def test_an_entirely_empty_body_derives_nothing() -> None:
    result = derive_assess({})
    assert result.predicted_condition is None
    assert result.confidence_score is None
    assert result.risk_category is None
    assert result.requires_human_verification is None
