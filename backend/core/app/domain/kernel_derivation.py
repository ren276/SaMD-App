"""Read-time derivation of clinical values from a raw /v1/assess response.

WHAT THIS MODULE IS FOR
-----------------------
The kernel's /v1/assess response carries a ranked differential_diagnosis, a triage_urgency, a
safety_screen_passed flag and model_metadata. It does NOT carry predicted_condition,
confidence_score, risk_category or required_human_verification. Those four are derived, and the
Android app derives them client side.

Before the Phase 3 fix pass the backend proxy computed the same four values and wrote them into
kernel_reports next to model_id and model_version, which attributed backend arithmetic to a named
model version. That write is gone (see app/models/sync.py::KernelAssessment). Raw model output is
now stored raw, and derivation happens HERE, at read time, called by the report layer and the
Phase 7 dashboard. Nothing this module returns is persisted.

VERSIONING, AND WHY IT IS NOT DECORATION
----------------------------------------
DERIVATION_RULE_VERSION below must be bumped by any change to a threshold or a rule in this
module, including a change that looks cosmetic. The 0.90 human-verification threshold is a risk
control (REQ-HAN-08, hazard H-02 in docs/quality/risk-management-file.md), not a tuning constant.
Under CDSCO/MD/GD/MDSW/01/2026 the Algorithm Change Protocol has to be able to answer "did the
output change because the model changed, or because we changed the rules?" A stored
model_version answers half of that question. This constant is the other half. Two runs that agree
on model_version and disagree on rule version are a rule change, and that has to be visible
without reading a git log.

SOURCE OF THE RULES
-------------------
app/src/main/java/com/example/samdapp/domain/usecase/GenerateKernelReportUseCase.kt, the
tryRealApi branch (REQ-HAN-07, REQ-HAN-08), plus
app/src/main/java/com/example/samdapp/data/remote/RetrofitKernelSource.kt::assess for the
differential selection. Read from the Android source rather than restated from the requirements
prose, because the app is what the clinician actually saw.

THREE PLACES THIS DELIBERATELY DIVERGES FROM ANDROID, each one a decision, not an oversight:

1. Top differential selection. Android takes differentialDiagnosis.firstOrNull(), trusting the
   kernel's own ordering, and does NOT sort by probability. This module does the same. The Phase
   3 fix-pass brief specified "highest-probability entry" instead; that was not adopted, because
   if the kernel ever returns an unsorted list, a max-by-probability rule here would report a
   different top condition than the one the device displayed to the clinician, and reconstructing
   what was shown is the entire purpose of this module. Flagged as D-11 in
   docs/backend/backend-prd.md rather than reconciled silently.

2. Empty differential_diagnosis. Android substitutes the literal string "Non-specific
   presentation" and a confidence of 0.50 (RetrofitKernelSource.assess). This module returns None
   for both. Inventing a 0.50 confidence the model never produced is the same defect this whole
   fix pass exists to remove; the device does it for a UI that must render something, and a
   read-time analytical function has no such obligation.

3. risk_category on the mock path. Android's MOCK_FALLBACK branch reads risk_category from a
   hardcoded per-scenario constant in a curated table, not from anything a model produced. That
   path is irrelevant here (the proxy only ever sees real kernel responses) and is not
   reproduced. See D-11.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Final

# Bump on ANY threshold or rule change below. See the module docstring.
DERIVATION_RULE_VERSION: Final[str] = "HAN-07/08-v1"

# GenerateKernelReportUseCase.HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD. A risk control, not a knob.
HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD: Final[float] = 0.90

# GenerateKernelReportUseCase.tryRealApi, the `val urgency = when (...)` block. Verbatim,
# EMERGENT alias included.
_EMERGENCY_URGENCY_TOKENS: Final[frozenset[str]] = frozenset({"EMERGENCY", "EMERGENT"})

# GenerateKernelReportUseCase.tryRealApi, the `val risk = when { ... }` block. Verbatim.
_RISK_HIGH_CONFIDENCE_FLOOR: Final[float] = 0.85
_RISK_MODERATE_CONFIDENCE_FLOOR: Final[float] = 0.65


@dataclass(frozen=True)
class AssessDerivation:
    """Derived clinical values for one /v1/assess response.

    Every field except derivation_rule_version is None when the response carries nothing to
    derive it from. None means "the model did not say", and it is never a defaulted zero.
    """

    predicted_condition: str | None
    confidence_score: float | None
    risk_category: str | None
    requires_human_verification: bool | None
    derivation_rule_version: str


def derive_assess(response: Mapping[str, Any]) -> AssessDerivation:
    """Derive the four clinical values Android shows, from a raw /v1/assess response body.

    Pure. Takes the parsed response, touches no database, persists nothing.
    """
    top = _top_differential(response.get("differential_diagnosis"))
    if top is None:
        return AssessDerivation(
            predicted_condition=None,
            confidence_score=None,
            risk_category=None,
            requires_human_verification=None,
            derivation_rule_version=DERIVATION_RULE_VERSION,
        )

    predicted_condition = _as_text(top.get("condition_tier"))
    confidence = _as_confidence(top.get("probability"))
    if confidence is None:
        return AssessDerivation(
            predicted_condition=predicted_condition,
            confidence_score=None,
            risk_category=None,
            requires_human_verification=None,
            derivation_rule_version=DERIVATION_RULE_VERSION,
        )

    return AssessDerivation(
        predicted_condition=predicted_condition,
        confidence_score=confidence,
        risk_category=_risk_category(
            triage_urgency=response.get("triage_urgency"), confidence=confidence
        ),
        requires_human_verification=confidence < HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD,
        derivation_rule_version=DERIVATION_RULE_VERSION,
    )


def _top_differential(differentials: Any) -> Mapping[str, Any] | None:
    """The kernel's own first-ranked entry, matching RetrofitKernelSource.assess's firstOrNull().

    Not max-by-probability. See divergence 1 in the module docstring: a tie or an unsorted list
    must resolve the same way here as it did on the device.
    """
    if not isinstance(differentials, Sequence) or isinstance(differentials, str | bytes):
        return None
    if not differentials:
        return None
    first = differentials[0]
    return first if isinstance(first, Mapping) else None


def _risk_category(*, triage_urgency: Any, confidence: float) -> str:
    """GenerateKernelReportUseCase.tryRealApi's risk mapping, copied verbatim.

    Two things about this mapping are worth stating rather than leaving for someone to rediscover.

    It reads inverted at a glance: HIGHER confidence maps to LOWER risk. That is not a typo in the
    Android source. Outside the EMERGENCY branch this rule is not grading how serious the
    condition is, it is grading how unsure the model is, so a low-confidence result lands in HIGH.
    RiskCategory's own KDoc on the device describes the field as "how serious the predicted
    condition could be", which the implementation does not do. The contradiction is real, it is
    recorded as D-11 in docs/backend/backend-prd.md, and it is reproduced here unchanged rather
    than quietly corrected: a read-time module that disagreed with the device would make the
    stored record and the displayed record differ, which is worse than a documented oddity.

    CRITICAL is unreachable, on both sides. Nothing in the /v1/assess response distinguishes an
    emergency with imminent harm from one needing prompt attention.
    """
    urgency = str(triage_urgency).upper() if triage_urgency is not None else ""
    if urgency in _EMERGENCY_URGENCY_TOKENS:
        return "HIGH"
    if confidence >= _RISK_HIGH_CONFIDENCE_FLOOR:
        return "LOW"
    if confidence >= _RISK_MODERATE_CONFIDENCE_FLOOR:
        return "MODERATE"
    return "HIGH"


def _as_text(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _as_confidence(value: Any) -> float | None:
    """probability, clamped to [0, 1] the way RetrofitKernelSource does with coerceIn.

    bool is rejected explicitly: it is an int subclass in Python, and a probability of True is a
    malformed response, not a confidence of 1.0.
    """
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return min(1.0, max(0.0, float(value)))
