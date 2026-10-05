"""The derivation cross-check: does re-deriving a synced kernel report from the model output the
proxy stored agree with what the device stored?

kernel_reports is device-owned (D-9, D-10). This module never writes to it: a disagreement is
recorded as a flag in kernel_derivation_checks, beside the unchanged report, so what the worker
saw is never silently replaced. One row is written per accepted write of a report, including the
cases where nothing could be checked, so a missing check is never silent.

Outcomes
  NOT_APPLICABLE        the report is not from a real inference (MOCK_FALLBACK, UNAVAILABLE).
                        There is no model output to re-derive, so no derivation and no comparison
                        is run. If a same-facility ASSESS row with the report's request_id exists,
                        it is recorded as kernel_assessment_id: the proxy succeeded and the device
                        recorded no result, which is evidence worth keeping.
  NOT_CHECKED_NO_LINK   a real report with no request_id, or with one that joins no assessment
                        (same request_id, same facility, same case, ASSESS endpoint).
  NOT_CHECKED_ERROR     deriving or comparing raised.
  MATCH / MISMATCH      derive_assess(raw_response) compared with the report's urgency_level,
                        risk_category and required_human_verification. A mismatch records the
                        field NAMES only, never the values (patient-linked clinical data).

The check never blocks the clinical row: it runs in its own savepoint, and if even recording it
fails, the report stays accepted and a structured warning is logged.
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import CurrentWorker
from app.domain.kernel_derivation import DERIVATION_RULE_VERSION, derive_assess
from app.domain.kernel_identity import MISMATCH_FIELD_NAMES
from app.logging import get_logger
from app.models.enums import InferenceSource, KernelEndpoint, RederiveStatus
from app.models.kernel import KernelReport
from app.models.sync import KernelAssessment, KernelDerivationCheck

logger = get_logger(__name__)


async def _linked_assessment(
    session: AsyncSession, worker: CurrentWorker, report: KernelReport
) -> KernelAssessment | None:
    """The assessment this report's request_id points at, or None. A row from another facility or
    another case is not a link: a device cannot claim someone else's model output."""
    if report.request_id is None:
        return None
    return (
        await session.execute(
            select(KernelAssessment)
            .where(
                KernelAssessment.request_id == report.request_id,
                KernelAssessment.facility_id == worker.facility_id,
                KernelAssessment.case_record_id == report.case_record_id,
                KernelAssessment.endpoint == KernelEndpoint.ASSESS.value,
            )
            .order_by(KernelAssessment.created_at.desc(), KernelAssessment.id.desc())
            .limit(1)
        )
    ).scalar_one_or_none()


def _compare(report: KernelReport, raw_response: dict[str, Any]) -> list[str]:
    """Names of the fields on which the report disagrees with a fresh derivation, in vocabulary
    order. A derived None against a stored value is a disagreement."""
    derived = derive_assess(raw_response)
    differing = {
        "urgency_level": report.urgency_level != derived.urgency_level,
        "risk_category": report.risk_category != derived.risk_category,
        "required_human_verification": (
            report.required_human_verification != derived.requires_human_verification
        ),
    }
    return [name for name in MISMATCH_FIELD_NAMES if differing[name]]


async def _outcome(
    session: AsyncSession, worker: CurrentWorker, report: KernelReport
) -> tuple[RederiveStatus, KernelAssessment | None, list[str]]:
    assessment = await _linked_assessment(session, worker, report)

    if report.inference_source != InferenceSource.REAL_INFERENCE.value:
        return RederiveStatus.NOT_APPLICABLE, assessment, []
    if assessment is None:
        return RederiveStatus.NOT_CHECKED_NO_LINK, None, []
    try:
        mismatched = _compare(report, assessment.raw_response)
    except Exception as exc:
        # Class name only: an exception message can echo response content.
        logger.warning(
            "kernel derivation check errored",
            kernel_report_id=report.id,
            error_class=type(exc).__name__,
        )
        return RederiveStatus.NOT_CHECKED_ERROR, assessment, []
    status = RederiveStatus.MISMATCH if mismatched else RederiveStatus.MATCH
    return status, assessment, mismatched


def _build_check(
    report: KernelReport,
    status: RederiveStatus,
    assessment: KernelAssessment | None,
    mismatched: list[str],
) -> KernelDerivationCheck:
    return KernelDerivationCheck(
        kernel_report_id=report.id,
        facility_id=report.facility_id,
        request_id=report.request_id,
        kernel_assessment_id=assessment.id if assessment is not None else None,
        rederive_status=status.value,
        rule_version_used=DERIVATION_RULE_VERSION,
        device_rule_version=report.derivation_rule_version,
        mismatch_fields=mismatched,
    )


async def record_check(session: AsyncSession, worker: CurrentWorker, report: KernelReport) -> None:
    """Write one check row for an accepted kernel_reports write. Never raises: the clinical row is
    already applied and must stay accepted whatever happens here."""
    try:
        # Its own savepoint, inside the record's: a failure here rolls back only this insert.
        async with session.begin_nested():
            status, assessment, mismatched = await _outcome(session, worker, report)
            session.add(_build_check(report, status, assessment, mismatched))
            await session.flush()
    except Exception as exc:
        logger.warning(
            "kernel derivation check not recorded",
            kernel_report_id=report.id,
            error_class=type(exc).__name__,
        )
