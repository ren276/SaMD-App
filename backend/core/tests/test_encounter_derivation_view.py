"""The DOCTOR view's derivation fields: derivation_rule_status, derivation_check, derivation_ok.

Rows are seeded straight into the database and the assertions read GET /encounters/{id}, so the
fields are proven to come from stored state. derivation_ok fails closed: true only for a CURRENT
rule version with a recorded MATCH.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import CaseStatus
from app.models.kernel import KernelReport
from app.models.sync import KernelDerivationCheck
from tests.test_encounters import ENCOUNTER_ID, seed_case_record, seed_patient_and_encounter
from tests.test_kernel_identity_schema import _assessment, _check, _report

T0 = datetime(2026, 10, 1, 9, 0, tzinfo=UTC)


@pytest.fixture
async def parents(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> AsyncSession:
    await seed_patient_and_encounter(client, auth_headers)
    await seed_case_record(session, CaseStatus.SAVED_LOCALLY)
    return session


async def _kernel_report(client: AsyncClient, doctor_headers: dict[str, str]) -> dict[str, Any]:
    response = await client.get(f"/api/v1/encounters/{ENCOUNTER_ID}", headers=doctor_headers)
    assert response.status_code == 200
    return response.json()["data"]["kernel_report"]


async def _seed(
    session: AsyncSession, rule_version: str | None, *checks: KernelDerivationCheck
) -> None:
    # A MATCH or MISMATCH row must point at the assessment it was derived from (CHECK constraint).
    session.add_all([_report(derivation_rule_version=rule_version), _assessment("ka-1")])
    await session.flush()
    session.add_all(checks)
    await session.commit()


async def test_a_current_version_with_a_match_is_ok(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    await _seed(
        parents,
        "HAN-07/08-v2",
        _check(kernel_assessment_id="ka-1", rederive_status="MATCH", created_at=T0),
    )

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_rule_version"] == "HAN-07/08-v2"
    assert report["derivation_rule_status"] == "CURRENT"
    assert report["derivation_check"] == {
        "status": "MATCH",
        "rule_version_used": "HAN-07/08-v2",
        "mismatch_fields": [],
        "checked_at": "2026-10-01T09:00:00.000Z",
    }
    assert report["derivation_ok"] is True


async def test_a_superseded_version_is_not_ok_even_with_a_match(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    await _seed(
        parents, "HAN-07/08-v1", _check(kernel_assessment_id="ka-1", rederive_status="MATCH")
    )

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_rule_status"] == "SUPERSEDED"
    assert report["derivation_ok"] is False


@pytest.mark.parametrize("version", [None, "HAN-07/08-v9"], ids=["null", "ahead_of_backend"])
async def test_a_null_or_unrecognised_version_is_unknown_and_not_ok(
    parents: AsyncSession,
    client: AsyncClient,
    doctor_headers: dict[str, str],
    version: str | None,
) -> None:
    await _seed(parents, version, _check(kernel_assessment_id="ka-1", rederive_status="MATCH"))

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_rule_version"] == version
    assert report["derivation_rule_status"] == "UNKNOWN"
    assert report["derivation_ok"] is False


async def test_no_check_row_gives_a_null_check_and_not_ok(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    await _seed(parents, "HAN-07/08-v2")

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_rule_status"] == "CURRENT"
    assert report["derivation_check"] is None
    assert report["derivation_ok"] is False


@pytest.mark.parametrize(
    "status", ["NOT_APPLICABLE", "NOT_CHECKED_NO_LINK", "NOT_CHECKED_ERROR", "MISMATCH"]
)
async def test_every_outcome_other_than_a_match_is_not_ok(
    parents: AsyncSession,
    client: AsyncClient,
    doctor_headers: dict[str, str],
    status: str,
) -> None:
    mismatched = ["urgency_level"] if status == "MISMATCH" else []
    await _seed(
        parents,
        "HAN-07/08-v2",
        _check(rederive_status=status, mismatch_fields=mismatched, kernel_assessment_id="ka-1"),
    )

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_rule_status"] == "CURRENT"
    assert report["derivation_check"]["status"] == status
    assert report["derivation_check"]["mismatch_fields"] == mismatched
    assert report["derivation_ok"] is False


async def test_the_latest_check_wins_by_created_at(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    """The row inserted FIRST carries the later created_at, so insertion order cannot be the
    reason it is chosen."""
    await _seed(
        parents,
        "HAN-07/08-v2",
        _check(
            kernel_assessment_id="ka-1", rederive_status="MATCH", created_at=T0 + timedelta(hours=1)
        ),
        _check(rederive_status="NOT_CHECKED_ERROR", created_at=T0),
    )

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_check"]["status"] == "MATCH"
    assert report["derivation_ok"] is True


async def test_a_match_for_an_older_revision_of_the_report_is_not_ok(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    """The report was updated to server_version 2 but its check failed to record, so the only
    check is the MATCH for revision 1. It must not vouch for revision 2."""
    await _seed(
        parents,
        "HAN-07/08-v2",
        _check(
            kernel_assessment_id="ka-1",
            rederive_status="MATCH",
            report_server_version=1,
            created_at=T0,
        ),
    )
    await parents.execute(update(KernelReport).values(server_version=2))
    await parents.commit()

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_check"]["status"] == "MATCH"
    assert report["derivation_ok"] is False


async def test_checks_with_the_same_created_at_break_the_tie_on_id(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    """Two checks from one batch share a transaction timestamp: the higher id is the later one."""
    await _seed(
        parents,
        "HAN-07/08-v2",
        _check(kernel_assessment_id="ka-1", rederive_status="MATCH", created_at=T0),
        _check(rederive_status="NOT_CHECKED_ERROR", created_at=T0),
    )

    report = await _kernel_report(client, doctor_headers)

    assert report["derivation_check"]["status"] == "NOT_CHECKED_ERROR"
    assert report["derivation_ok"] is False


async def test_an_encounter_with_no_kernel_report_is_unchanged(
    parents: AsyncSession, client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    assert await _kernel_report(client, doctor_headers) is None
