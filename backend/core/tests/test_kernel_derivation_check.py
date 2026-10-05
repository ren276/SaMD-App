"""The sync-time derivation cross-check (R4): a synced kernel report is re-derived from the stored
model output and the result goes to kernel_derivation_checks, never into kernel_reports.

Every test asserts persisted rows, not the sync ack. The assessment rows use the REAL recaptured
classifier fixtures, so the derived values come from a real response.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.kernel_derivation import DERIVATION_RULE_VERSION
from app.models.kernel import KernelReport
from app.models.sync import KernelAssessment, KernelDerivationCheck
from app.services import kernel_derivation_check
from tests.conftest import OTHER_FACILITY_ID, TEST_FACILITY_ID
from tests.test_sync import (
    CASE_ID,
    case_record_record,
    encounter_record,
    kernel_report_record,
    patient_record,
    push,
)

_FIXTURES = Path(__file__).parent / "fixtures" / "classifier"
REQUEST_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b"
OTHER_REQUEST_ID = "9c1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f"
REPORT_ID = "kr-check-1"
# What derive_assess yields for assess_spo2_85.json (the red-flag, real response).
MATCHING_VALUES = {
    "urgency_level": "EMERGENCY",
    "risk_category": "HIGH",
    "required_human_verification": True,
}
# Clinical tokens that must never appear in a check row: field names only, no values.
CLINICAL_TOKENS = ("EMERGENCY", "URGENT", "ROUTINE", "HIGH", "MODERATE", "LOW", "true", "True")


def _fixture_response(name: str = "assess_spo2_85.json") -> dict[str, Any]:
    return json.loads((_FIXTURES / name).read_text())["response"]


@pytest.fixture
async def seeded(client: AsyncClient, auth_headers: dict[str, str]) -> AsyncClient:
    response = await push(
        client, auth_headers, [patient_record(), encounter_record(), case_record_record()]
    )
    assert response.json()["data"]["rejected"] == 0
    return client


async def _add_assessment(
    session: AsyncSession,
    *,
    request_id: str = REQUEST_ID,
    facility_id: str = TEST_FACILITY_ID,
    endpoint: str = "ASSESS",
    raw_response: dict[str, Any] | None = None,
) -> str:
    assessment_id = str(uuid.uuid4())
    session.add(
        KernelAssessment(
            id=assessment_id,
            request_id=request_id,
            case_record_id=CASE_ID,
            facility_id=facility_id,
            worker_id="W-1",
            endpoint=endpoint,
            raw_response=raw_response or _fixture_response(),
            response_sha256="0" * 64,
        )
    )
    await session.commit()
    return assessment_id


async def _sync_report(
    client: AsyncClient,
    headers: dict[str, str],
    *,
    updated_at: str = "2026-08-16T09:46:00.000Z",
    inference_source: str = "REAL_INFERENCE",
    overrides: dict[str, Any] | None = None,
    omit: tuple[str, ...] = (),
    base_version: int | None = None,
    **push_kwargs: Any,
) -> Any:
    record = kernel_report_record(
        REPORT_ID,
        inference_source=inference_source,
        client_updated_at=updated_at,
        data_overrides=overrides,
        omit=omit,
    )
    record["base_version"] = base_version
    response = await push(client, headers, [record], **push_kwargs)
    assert response.status_code == 200
    return response


async def _checks(session: AsyncSession) -> list[KernelDerivationCheck]:
    return list(
        (
            await session.execute(select(KernelDerivationCheck).order_by(KernelDerivationCheck.id))
        ).scalars()
    )


async def _only_check(session: AsyncSession) -> KernelDerivationCheck:
    rows = await _checks(session)
    assert len(rows) == 1
    return rows[0]


# --- the verdicts ------------------------------------------------------------------------------


async def test_a_report_that_agrees_with_the_stored_model_output_is_a_match(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    assessment_id = await _add_assessment(session)
    await _sync_report(
        seeded,
        auth_headers,
        overrides={
            **MATCHING_VALUES,
            "request_id": REQUEST_ID,
            "derivation_rule_version": DERIVATION_RULE_VERSION,
        },
    )

    check = await _only_check(session)
    assert check.rederive_status == "MATCH"
    assert check.mismatch_fields == []
    assert check.kernel_assessment_id == assessment_id
    assert check.kernel_report_id == REPORT_ID
    assert check.request_id == REQUEST_ID
    assert check.facility_id == TEST_FACILITY_ID
    assert check.rule_version_used == DERIVATION_RULE_VERSION
    assert check.device_rule_version == DERIVATION_RULE_VERSION


async def test_a_disagreeing_report_is_a_mismatch_naming_fields_only_and_is_not_replaced(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _add_assessment(session)
    # The device stored ROUTINE / MODERATE / not verified for a red-flag response.
    await _sync_report(seeded, auth_headers, overrides={"request_id": REQUEST_ID})

    check = await _only_check(session)
    assert check.rederive_status == "MISMATCH"
    assert check.mismatch_fields == [
        "urgency_level",
        "risk_category",
        "required_human_verification",
    ]
    # Names only: no row text carries a clinical value.
    row_text = " ".join(
        str(v) for v in (check.request_id, check.rederive_status, check.rule_version_used)
    ) + " ".join(check.mismatch_fields)
    assert not any(token in row_text for token in CLINICAL_TOKENS)

    # R4: the device-owned report is exactly what the device sent, not the re-derived values.
    report = (await session.execute(select(KernelReport))).scalar_one()
    assert (report.urgency_level, report.risk_category, report.required_human_verification) == (
        "ROUTINE",
        "MODERATE",
        False,
    )


async def test_a_partial_disagreement_names_only_the_differing_field(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _add_assessment(session)
    await _sync_report(
        seeded,
        auth_headers,
        overrides={**MATCHING_VALUES, "risk_category": "LOW", "request_id": REQUEST_ID},
    )

    check = await _only_check(session)
    assert check.rederive_status == "MISMATCH"
    assert check.mismatch_fields == ["risk_category"]


# --- nothing to check: always recorded, never skipped ------------------------------------------


async def test_a_real_report_with_no_request_id_records_no_link(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _add_assessment(session)  # exists, but the device did not say which one is its own
    await _sync_report(seeded, auth_headers)

    check = await _only_check(session)
    assert check.rederive_status == "NOT_CHECKED_NO_LINK"
    assert check.request_id is None
    assert check.kernel_assessment_id is None
    assert check.mismatch_fields == []
    assert check.rule_version_used == DERIVATION_RULE_VERSION


async def test_a_request_id_that_joins_no_assessment_records_no_link_with_the_id(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _sync_report(seeded, auth_headers, overrides={"request_id": REQUEST_ID})

    check = await _only_check(session)
    assert check.rederive_status == "NOT_CHECKED_NO_LINK"
    assert check.request_id == REQUEST_ID
    assert check.kernel_assessment_id is None


@pytest.mark.parametrize(
    "assessment_kwargs",
    [
        {"facility_id": OTHER_FACILITY_ID},  # another facility's model output
        {"endpoint": "EVALUATE"},  # not an /assess row
        {"request_id": OTHER_REQUEST_ID},  # a different call
    ],
    ids=["other_facility", "evaluate_endpoint", "different_request_id"],
)
async def test_an_assessment_that_is_not_this_reports_is_not_a_link(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    other_facility_headers: dict[str, str],
    session: AsyncSession,
    assessment_kwargs: dict[str, Any],
) -> None:
    await _add_assessment(session, **assessment_kwargs)
    await _sync_report(seeded, auth_headers, overrides={"request_id": REQUEST_ID})

    check = await _only_check(session)
    assert check.rederive_status == "NOT_CHECKED_NO_LINK"
    assert check.kernel_assessment_id is None


async def test_a_legacy_payload_with_no_new_fields_is_recorded_unknown_to_the_check(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _sync_report(seeded, auth_headers, overrides={"model_version": "remote-kernel"})

    check = await _only_check(session)
    assert check.rederive_status == "NOT_CHECKED_NO_LINK"
    assert check.device_rule_version is None


async def test_the_device_rule_version_is_snapshotted_beside_the_rules_in_force(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _sync_report(seeded, auth_headers, overrides={"derivation_rule_version": "HAN-07/08-v1"})

    check = await _only_check(session)
    assert check.device_rule_version == "HAN-07/08-v1"
    assert check.rule_version_used == DERIVATION_RULE_VERSION


# --- not from a real inference: NOT_APPLICABLE, nothing derived --------------------------------


class _DeriveSpy:
    def __init__(self) -> None:
        self.calls = 0

    def __call__(self, response: dict[str, Any]) -> Any:
        self.calls += 1
        raise AssertionError("derive_assess must not run for a non-real report")


async def test_an_unavailable_report_with_a_request_id_and_no_assessment(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await _sync_report(
        seeded,
        auth_headers,
        inference_source="UNAVAILABLE",
        overrides={"request_id": REQUEST_ID},
        omit=("model_version",),
    )

    check = await _only_check(session)
    assert check.rederive_status == "NOT_APPLICABLE"
    assert check.request_id == REQUEST_ID
    assert check.kernel_assessment_id is None


async def test_an_unavailable_report_whose_request_id_has_an_assessment_records_the_evidence(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The proxy succeeded but the device recorded no result: worth keeping. No derivation runs."""
    spy = _DeriveSpy()
    monkeypatch.setattr(kernel_derivation_check, "derive_assess", spy)
    assessment_id = await _add_assessment(session)
    await _sync_report(
        seeded,
        auth_headers,
        inference_source="UNAVAILABLE",
        overrides={"request_id": REQUEST_ID},
        omit=("model_version",),
    )

    check = await _only_check(session)
    assert check.rederive_status == "NOT_APPLICABLE"
    assert check.kernel_assessment_id == assessment_id
    assert check.mismatch_fields == []
    assert spy.calls == 0


@pytest.mark.parametrize("with_request_id", [False, True])
async def test_a_mock_fallback_report_is_not_applicable_and_runs_no_derivation(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
    with_request_id: bool,
) -> None:
    spy = _DeriveSpy()
    monkeypatch.setattr(kernel_derivation_check, "derive_assess", spy)
    await _sync_report(
        seeded,
        auth_headers,
        inference_source="MOCK_FALLBACK",
        overrides={"request_id": REQUEST_ID} if with_request_id else None,
    )

    check = await _only_check(session)
    assert check.rederive_status == "NOT_APPLICABLE"
    assert check.request_id == (REQUEST_ID if with_request_id else None)
    assert spy.calls == 0


# --- failure containment -----------------------------------------------------------------------


async def test_a_derivation_that_raises_is_recorded_and_the_report_is_kept(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(response: dict[str, Any]) -> Any:
        raise ValueError("contains patient detail that must not be logged")

    monkeypatch.setattr(kernel_derivation_check, "derive_assess", boom)
    await _add_assessment(session)
    await _sync_report(seeded, auth_headers, overrides={"request_id": REQUEST_ID})

    check = await _only_check(session)
    assert check.rederive_status == "NOT_CHECKED_ERROR"
    assert check.mismatch_fields == []
    assert (await session.execute(select(func.count()).select_from(KernelReport))).scalar_one() == 1


class _LogSpy:
    def __init__(self) -> None:
        self.warnings: list[tuple[str, dict[str, Any]]] = []

    def warning(self, event: str, **fields: Any) -> None:
        self.warnings.append((event, fields))


async def test_a_derivation_error_logs_the_class_name_and_never_the_message(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def boom(response: dict[str, Any]) -> Any:
        raise ValueError("patient detail that must not be logged")

    spy = _LogSpy()
    monkeypatch.setattr(kernel_derivation_check, "derive_assess", boom)
    monkeypatch.setattr(kernel_derivation_check, "logger", spy)
    await _add_assessment(session)
    await _sync_report(seeded, auth_headers, overrides={"request_id": REQUEST_ID})

    assert len(spy.warnings) == 1
    _, fields = spy.warnings[0]
    assert fields == {"kernel_report_id": REPORT_ID, "error_class": "ValueError"}


async def test_a_check_that_cannot_be_recorded_does_not_lose_the_report(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def cannot_build(*args: Any, **kwargs: Any) -> Any:
        raise SQLAlchemyError("simulated failure while writing the check row")

    spy = _LogSpy()
    monkeypatch.setattr(kernel_derivation_check, "_build_check", cannot_build)
    monkeypatch.setattr(kernel_derivation_check, "logger", spy)

    response = await _sync_report(seeded, auth_headers)

    # Persisted state, not the ack: the clinical row is there and no check row is.
    assert response.json()["data"]["rejected"] == 0
    assert (await session.execute(select(func.count()).select_from(KernelReport))).scalar_one() == 1
    assert await _checks(session) == []
    assert spy.warnings == [
        (
            "kernel derivation check not recorded",
            {"kernel_report_id": REPORT_ID, "error_class": "SQLAlchemyError"},
        )
    ]


# --- history -----------------------------------------------------------------------------------


async def test_a_resync_appends_a_second_check_and_a_replay_appends_none(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    batch_id = str(uuid.uuid4())
    await _sync_report(
        seeded, auth_headers, updated_at="2026-08-16T09:46:00.000Z", batch_id=batch_id
    )
    assert len(await _checks(session)) == 1

    # The same batch again: answered from the idempotency store, nothing re-applied.
    await _sync_report(
        seeded, auth_headers, updated_at="2026-08-16T09:46:00.000Z", batch_id=batch_id
    )
    assert len(await _checks(session)) == 1

    # A genuine later write of the same report: a new server_version and a second check row.
    await _sync_report(seeded, auth_headers, updated_at="2026-08-16T10:30:00.000Z")
    rows = await _checks(session)
    assert len(rows) == 2
    assert rows[0].id < rows[1].id


async def _report_version(session: AsyncSession) -> int:
    """server_version read from the row itself, not from any sync ack."""
    return (
        await session.execute(
            select(KernelReport.server_version).where(KernelReport.id == REPORT_ID)
        )
    ).scalar_one()


# --- the check follows the comparator: only an APPLIED write earns a row ------------------------


async def test_an_exact_replay_under_a_new_batch_writes_no_check_row(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """Stale: same timestamp, same content. Nothing was applied, so nothing is checked."""
    await _sync_report(seeded, auth_headers, batch_id=str(uuid.uuid4()))
    assert len(await _checks(session)) == 1

    await _sync_report(seeded, auth_headers, batch_id=str(uuid.uuid4()))

    assert len(await _checks(session)) == 1
    assert await _report_version(session) == 1


@pytest.mark.parametrize(
    ("updated_at", "base_version"),
    [
        pytest.param("2026-08-16T10:30:00.000Z", 7, id="base_version_does_not_match"),
        pytest.param("2026-08-16T08:00:00.000Z", None, id="older_write_with_no_base_version"),
    ],
)
async def test_a_conflict_writes_no_check_row_and_keeps_the_stored_report(
    seeded: AsyncClient,
    auth_headers: dict[str, str],
    session: AsyncSession,
    updated_at: str,
    base_version: int | None,
) -> None:
    await _sync_report(seeded, auth_headers)
    assert len(await _checks(session)) == 1

    response = await _sync_report(
        seeded,
        auth_headers,
        updated_at=updated_at,
        base_version=base_version,
        overrides={"urgency_level": "URGENT"},
    )

    assert response.json()["data"]["conflicted"] == 1
    assert len(await _checks(session)) == 1
    assert await _report_version(session) == 1
    stored = (
        await session.execute(
            select(KernelReport.urgency_level).where(KernelReport.id == REPORT_ID)
        )
    ).scalar_one()
    assert stored == "ROUTINE"  # the conflicting write did not replace the stored value


async def test_a_resync_on_a_matching_base_version_appends_a_second_check_row(
    seeded: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """The #69 re-assessment path: the device re-saves the same report id carrying the
    server_version it last saw. Applied, so version 2 and a second, later check row."""
    await _sync_report(seeded, auth_headers)
    assert await _report_version(session) == 1

    response = await _sync_report(
        seeded, auth_headers, updated_at="2026-08-16T10:30:00.000Z", base_version=1
    )

    assert response.json()["data"]["applied"] == 1
    assert await _report_version(session) == 2
    rows = await _checks(session)
    assert len(rows) == 2
    assert rows[0].id < rows[1].id
    assert {row.kernel_report_id for row in rows} == {REPORT_ID}
