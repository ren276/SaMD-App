"""Alembic 0010's schema, as the models declare it: nullable model_version, the three new
kernel_reports columns with their format CHECKs, and kernel_derivation_checks with its CHECKs and
insert-only triggers. Every test asserts persisted rows through the ORM, not a response.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError, IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import CaseStatus
from app.models.kernel import KernelReport
from app.models.sync import KernelAssessment, KernelDerivationCheck
from tests.conftest import TEST_FACILITY_ID
from tests.test_encounters import CASE_ID, seed_case_record, seed_patient_and_encounter

GOOD_REQUEST_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
NOW = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)


def _report(report_id: str = "kr-1", **overrides: Any) -> KernelReport:
    values: dict[str, Any] = {
        "id": report_id,
        "case_record_id": CASE_ID,
        "predicted_condition": "high_risk",
        "confidence_score": 0.8,
        "differentials": [],
        "reasoning_summary": "r",
        "evidence_for": [],
        "evidence_against": [],
        "model_version": "toy-v0.6",
        "device_id": "dev-1",
        "software_version": "1.0",
        "risk_category": "HIGH",
        "urgency_level": "URGENT",
        "inference_started_at": NOW,
        "inference_ended_at": NOW,
        "required_human_verification": True,
        "inference_source": "REAL_INFERENCE",
        "facility_id": TEST_FACILITY_ID,
    }
    values.update(overrides)
    return KernelReport(**values)


def _assessment(assessment_id: str = "ka-1") -> KernelAssessment:
    return KernelAssessment(
        id=assessment_id,
        request_id=GOOD_REQUEST_ID,
        case_record_id=CASE_ID,
        facility_id=TEST_FACILITY_ID,
        worker_id="W-1",
        endpoint="ASSESS",
        raw_response={},
        response_sha256="0" * 64,
    )


def _check(**overrides: Any) -> KernelDerivationCheck:
    values: dict[str, Any] = {
        "kernel_report_id": "kr-1",
        "facility_id": TEST_FACILITY_ID,
        "rederive_status": "NOT_CHECKED_NO_LINK",
        "rule_version_used": "HAN-07/08-v2",
    }
    values.update(overrides)
    return KernelDerivationCheck(**values)


@pytest.fixture
async def parents(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> AsyncSession:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)
    return session


async def _count(session: AsyncSession, model: Any) -> int:
    return (await session.execute(select(func.count()).select_from(model))).scalar_one()


# --- kernel_reports ----------------------------------------------------------------------------


async def test_a_null_model_version_is_stored_as_null(parents: AsyncSession) -> None:
    parents.add(_report(model_version=None))
    await parents.commit()

    stored = (await parents.execute(select(KernelReport.model_version))).scalar_one()
    assert stored is None


async def test_the_new_identity_columns_round_trip(parents: AsyncSession) -> None:
    parents.add(
        _report(
            model_calibrated=False,
            request_id=GOOD_REQUEST_ID,
            derivation_rule_version="HAN-07/08-v2",
        )
    )
    await parents.commit()

    row = (await parents.execute(select(KernelReport))).scalar_one()
    assert row.model_calibrated is False
    assert row.request_id == GOOD_REQUEST_ID
    assert row.derivation_rule_version == "HAN-07/08-v2"


@pytest.mark.parametrize(
    "bad",
    [
        "not-a-uuid",
        GOOD_REQUEST_ID.upper(),
        "3f2b8c1e-9a4d-1e6f-8b7a-1c2d3e4f5a6b",  # version nibble is 1, not 4
        GOOD_REQUEST_ID[:-1] + "g",  # 36 characters, last one not hex
        "",
    ],
)
async def test_a_malformed_request_id_is_refused_by_the_database(
    parents: AsyncSession, bad: str
) -> None:
    parents.add(_report(request_id=bad))
    with pytest.raises(IntegrityError):
        await parents.commit()
    await parents.rollback()
    assert await _count(parents, KernelReport) == 0


@pytest.mark.parametrize("bad", ["v2", "HAN-07/08-v", "HAN-07/08-v1000", "han-07/08-v2", "x"])
async def test_a_malformed_derivation_rule_version_is_refused_by_the_database(
    parents: AsyncSession, bad: str
) -> None:
    parents.add(_report(derivation_rule_version=bad))
    with pytest.raises(IntegrityError):
        await parents.commit()
    await parents.rollback()
    assert await _count(parents, KernelReport) == 0


# --- kernel_derivation_checks ------------------------------------------------------------------


async def test_every_status_is_storable_when_its_link_rule_is_met(parents: AsyncSession) -> None:
    parents.add_all([_report(), _assessment()])
    await parents.commit()
    parents.add_all(
        [
            _check(rederive_status="NOT_CHECKED_NO_LINK"),
            _check(rederive_status="NOT_CHECKED_ERROR"),
            _check(rederive_status="NOT_APPLICABLE"),
            _check(rederive_status="NOT_APPLICABLE", kernel_assessment_id="ka-1"),
            _check(rederive_status="MATCH", kernel_assessment_id="ka-1"),
            _check(
                rederive_status="MISMATCH",
                kernel_assessment_id="ka-1",
                mismatch_fields=["risk_category", "urgency_level"],
            ),
        ]
    )
    await parents.commit()

    assert await _count(parents, KernelDerivationCheck) == 6


@pytest.mark.parametrize(
    "overrides",
    [
        {"rederive_status": "BOGUS"},
        # a verdict needs the assessment it was derived from
        {"rederive_status": "MATCH"},
        {"rederive_status": "MISMATCH", "mismatch_fields": ["urgency_level"]},
        # a MISMATCH must name a field, and nothing else may
        {"rederive_status": "MISMATCH", "kernel_assessment_id": "ka-1"},
        {
            "rederive_status": "MATCH",
            "kernel_assessment_id": "ka-1",
            "mismatch_fields": ["urgency_level"],
        },
        {"rederive_status": "NOT_APPLICABLE", "mismatch_fields": ["urgency_level"]},
        # names outside the closed vocabulary, which is what keeps values out of this table
        {
            "rederive_status": "MISMATCH",
            "kernel_assessment_id": "ka-1",
            "mismatch_fields": ["urgency_level=EMERGENCY"],
        },
        {"rederive_status": "NOT_CHECKED_NO_LINK", "request_id": "not-a-uuid"},
    ],
)
async def test_the_check_constraints_refuse_an_inconsistent_row(
    parents: AsyncSession, overrides: dict[str, Any]
) -> None:
    parents.add_all([_report(), _assessment()])
    await parents.commit()

    parents.add(_check(**overrides))
    with pytest.raises(IntegrityError):
        await parents.commit()
    await parents.rollback()

    assert await _count(parents, KernelDerivationCheck) == 0


async def test_a_check_row_cannot_be_updated_or_deleted(parents: AsyncSession) -> None:
    parents.add(_report())
    await parents.commit()
    parents.add(_check())
    await parents.commit()

    with pytest.raises(DBAPIError, match="insert-only"):
        await parents.execute(
            text("UPDATE kernel_derivation_checks SET rule_version_used = 'HAN-07/08-v9'")
        )
    await parents.rollback()
    with pytest.raises(DBAPIError, match="insert-only"):
        await parents.execute(text("DELETE FROM kernel_derivation_checks"))
    await parents.rollback()

    row = (await parents.execute(select(KernelDerivationCheck))).scalar_one()
    assert row.rule_version_used == "HAN-07/08-v2"


async def test_a_checked_report_cannot_be_deleted(parents: AsyncSession) -> None:
    """The RESTRICT foreign key plus the no-delete trigger: recorded as a future obligation."""
    parents.add(_report())
    await parents.commit()
    parents.add(_check())
    await parents.commit()

    with pytest.raises(IntegrityError):
        await parents.execute(text("DELETE FROM kernel_reports WHERE id = 'kr-1'"))
    await parents.rollback()

    assert await _count(parents, KernelReport) == 1
