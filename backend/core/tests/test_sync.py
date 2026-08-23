"""POST /api/v1/sync/push. api-contract.md section 6.1 and section 7 (the audit chain rule).

Every test here talks to a real PostgreSQL 16 through the same ASGI app the rest of the suite
uses (tests/conftest.py). The idempotency and audit-chain tests in particular only mean anything
against the real database: sync_batches.batch_id is a real primary key, pg_advisory_xact_lock is
a real Postgres function, and the append-only trigger is real DDL. A happy-path smoke test was
also run against a real `uvicorn` process by hand this session (see the Phase 4 report); it is
not part of this file because the app process, not the test transport, was the thing being
checked, and a subprocess-managed server is not something this suite's own architecture uses
anywhere else (tests/test_kernel.py scripts the *kernel* with httpx.MockTransport, never a real
socket, and every other phase's tests run the same way this file does).
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ErrorCode
from app.models.audit import AuditEvent
from app.models.kernel import KernelReport
from app.models.patient import Patient
from app.models.sync import SyncBatch, SyncLogEntry
from app.domain.audit_actions_device import DEVICE_AUDIT_ACTIONS
from app.services import audit as audit_service
from tests.conftest import TEST_DEVICE_ID, TEST_FACILITY_ID, TEST_WORKER_ID
from tests.test_patients import PATIENT_ID

ENCOUNTER_ID = "8c1d4e6f-a2b3-4c5d-9e0f-112233445566"
CASE_ID = "cr-88f1"


def batch_id() -> str:
    return str(uuid.uuid4())


def batch_body(records: list[dict[str, Any]], **overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "batch_id": batch_id(),
        "device_id": TEST_DEVICE_ID,
        "client_time": "2026-08-16T10:05:00.000Z",
        "records": records,
    }
    body.update(overrides)
    return body


def _record(
    table: str,
    record_id: str,
    client_updated_at: str,
    data: dict[str, Any],
    *,
    op: str = "upsert",
    base_version: int | None = None,
) -> dict[str, Any]:
    return {
        "table": table,
        "op": op,
        "id": record_id,
        "client_updated_at": client_updated_at,
        "base_version": base_version,
        "data": data,
    }


def patient_record(
    record_id: str = PATIENT_ID,
    *,
    client_updated_at: str = "2026-08-16T09:41:30.000Z",
    base_version: int | None = None,
    **data_overrides: Any,
) -> dict[str, Any]:
    data = {
        "full_name": "Sunita Devi",
        "biological_sex": "FEMALE",
        "age": 35,
        "district": "Jaipur",
        "created_at": "2026-08-16T09:40:00.000Z",
        "updated_at": client_updated_at,
    }
    data.update(data_overrides)
    return _record("patients", record_id, client_updated_at, data, base_version=base_version)


def encounter_record(
    record_id: str = ENCOUNTER_ID,
    *,
    patient_id: str = PATIENT_ID,
    client_updated_at: str = "2026-08-16T09:44:00.000Z",
    base_version: int | None = None,
) -> dict[str, Any]:
    data = {
        "patient_id": patient_id,
        "started_at": client_updated_at,
        "follow_up_of_encounter_id": None,
        "created_at": client_updated_at,
        "updated_at": client_updated_at,
    }
    return _record("encounters", record_id, client_updated_at, data, base_version=base_version)


def case_record_record(
    record_id: str = CASE_ID,
    *,
    patient_id: str = PATIENT_ID,
    encounter_id: str = ENCOUNTER_ID,
    status: str = "SAVED_LOCALLY",
    client_updated_at: str = "2026-08-16T09:45:00.000Z",
) -> dict[str, Any]:
    data = {
        "patient_id": patient_id,
        "encounter_id": encounter_id,
        "status": status,
        "assigned_doctor_id": None,
        "created_at": client_updated_at,
        "updated_at": client_updated_at,
    }
    return _record("case_records", record_id, client_updated_at, data)


def observation_record(
    record_id: str,
    *,
    patient_id: str = PATIENT_ID,
    encounter_id: str = ENCOUNTER_ID,
    client_updated_at: str = "2026-08-16T09:44:30.000Z",
) -> dict[str, Any]:
    data = {
        "patient_id": patient_id,
        "encounter_id": encounter_id,
        "type": "PULSE",
        "value_numeric": 78,
        "value_text": None,
        "unit": "bpm",
        "device_id": None,
        "source": "MANUAL",
        "capture_method": "OTHER",
        "recorded_at": client_updated_at,
        "created_at": client_updated_at,
    }
    return _record("observations", record_id, client_updated_at, data)


def ailment_record(
    record_id: str,
    *,
    patient_id: str = PATIENT_ID,
    encounter_id: str = ENCOUNTER_ID,
    visibility: str = "PUBLIC",
    client_updated_at: str = "2026-08-16T09:44:45.000Z",
    **data_overrides: Any,
) -> dict[str, Any]:
    data = {
        "patient_id": patient_id,
        "encounter_id": encounter_id,
        "description": "Fever for three days",
        "measurement_type": "NON_MEASURABLE",
        "visibility": visibility,
        "measured_value": None,
        "measured_unit": None,
        "severity": None,
        "onset": None,
        "duration": None,
        "qualifiers": None,
        "captured_at_offline": client_updated_at,
        "deleted_at": None,
        "created_at": client_updated_at,
    }
    data.update(data_overrides)
    return _record("ailments", record_id, client_updated_at, data)


def kernel_report_record(
    record_id: str,
    *,
    case_record_id: str = CASE_ID,
    inference_source: str = "REAL_INFERENCE",
    client_updated_at: str = "2026-08-16T09:46:00.000Z",
) -> dict[str, Any]:
    data = {
        "case_record_id": case_record_id,
        "predicted_condition": "Viral fever",
        "confidence_score": 0.82,
        "differentials": ["Dengue", "Typhoid"],
        "reasoning_summary": "Fever pattern consistent with viral syndrome.",
        "evidence_for": ["fever reported"],
        "evidence_against": [],
        "model_version": "xgboost-v1",
        "icd_code": None,
        "device_id": TEST_DEVICE_ID,
        "software_version": "1.0",
        "data_quality_score": 1.0,
        "uncertainty_score": 0.18,
        "risk_category": "MODERATE",
        "urgency_level": "ROUTINE",
        "inference_started_at": client_updated_at,
        "inference_ended_at": client_updated_at,
        "required_human_verification": False,
        "inference_source": inference_source,
    }
    return _record("kernel_reports", record_id, client_updated_at, data)


def audit_record(
    record_id: str,
    *,
    action: str = "encounter_started",
    client_updated_at: str = "2026-08-16T09:44:02.000Z",
    user_id: str = TEST_WORKER_ID,
    patient_id: str | None = PATIENT_ID,
    case_record_id: str | None = None,
    payload: str = "{}",
) -> dict[str, Any]:
    data = {
        "timestamp": client_updated_at,
        "user_id": user_id,
        "patient_id": patient_id,
        "case_record_id": case_record_id,
        "action": action,
        "payload": payload,
    }
    return _record("audit_log", record_id, client_updated_at, data, op="insert")


async def push(
    client: AsyncClient, headers: dict[str, str], records: list[dict[str, Any]], **overrides: Any
) -> Any:
    return await client.post(
        "/api/v1/sync/push", json=batch_body(records, **overrides), headers=headers
    )


# ---------------------------------------------------------------------------
# Happy path, mixed batch
# ---------------------------------------------------------------------------


async def test_happy_path_mixed_batch_all_applied(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    records = [
        patient_record(),
        encounter_record(),
        case_record_record(),
        observation_record("obs-1"),
        ailment_record("ail-1"),
        audit_record("al-1"),
    ]
    response = await push(client, auth_headers, records)

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["received"] == 6
    assert data["applied"] == 6
    assert data["stale"] == 0
    assert data["conflicted"] == 0
    assert data["rejected"] == 0
    assert {(r["table"], r["status"]) for r in data["results"]} == {
        ("patients", "applied"),
        ("encounters", "applied"),
        ("case_records", "applied"),
        ("observations", "applied"),
        ("ailments", "applied"),
        ("audit_log", "applied"),
    }


# ---------------------------------------------------------------------------
# inference_source = UNAVAILABLE (H-09 tail, closed by migration 0006)
# ---------------------------------------------------------------------------


async def test_unavailable_inference_source_kernel_report_is_accepted_and_persisted(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """Before migration 0006, this inference_source value made the INSERT violate
    ck_kernel_reports_inference_source, which app/services/sync.py surfaces as a per-record
    SAMD-SYNC-6003 rejection, not a 500 the caller could not miss. Asserting only the HTTP
    response would not have caught a regression here: the persisted row is what matters (see
    CLAUDE.md's rule on this exact trap).
    """
    records = [
        patient_record(),
        encounter_record(),
        case_record_record(),
        kernel_report_record("kr-unavailable-1", inference_source="UNAVAILABLE"),
    ]
    response = await push(client, auth_headers, records)

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["rejected"] == 0
    assert data["applied"] == 4
    assert {(r["table"], r["status"]) for r in data["results"]} >= {
        ("kernel_reports", "applied"),
    }

    persisted = (
        await session.execute(select(KernelReport).where(KernelReport.id == "kr-unavailable-1"))
    ).scalar_one()
    assert persisted.inference_source == "UNAVAILABLE"


# ---------------------------------------------------------------------------
# Reordering
# ---------------------------------------------------------------------------


async def test_reordered_batch_applies_because_server_reorders(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    """The child (encounter) is placed before its parent (patient) in the array on purpose."""
    records = [encounter_record(), patient_record()]
    response = await push(client, auth_headers, records)

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["applied"] == 2
    assert data["rejected"] == 0


# ---------------------------------------------------------------------------
# Idempotent replay
# ---------------------------------------------------------------------------


async def test_idempotent_replay_is_verbatim_and_does_not_double_write(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    body = batch_body([patient_record()])

    # auth_headers already logged in, which writes its own worker_login_succeeded audit row;
    # baseline after that so the assertion below is about what sync push itself adds.
    chain_length_baseline = len(list((await session.execute(select(AuditEvent.id))).scalars()))

    first = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)
    second = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)

    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()

    patient = (await session.execute(select(Patient).where(Patient.id == PATIENT_ID))).scalar_one()
    assert patient.server_version == 1

    batches = list(
        (
            await session.execute(select(SyncBatch).where(SyncBatch.batch_id == body["batch_id"]))
        ).scalars()
    )
    assert len(batches) == 1

    chain_length = len(list((await session.execute(select(AuditEvent.id))).scalars()))
    # One sync_batch_received row from the first push, none from the replay.
    assert chain_length - chain_length_baseline == 1


# ---------------------------------------------------------------------------
# Stale
# ---------------------------------------------------------------------------


async def test_stale_is_acked_as_success_and_not_applied(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await push(client, auth_headers, [patient_record(client_updated_at="2026-08-16T09:41:30.000Z")])

    response = await push(
        client,
        auth_headers,
        [patient_record(client_updated_at="2026-08-16T09:00:00.000Z", full_name="Someone Else")],
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["stale"] == 1
    assert data["applied"] == 0
    assert data["results"][0]["status"] == "stale"

    patient = (await session.execute(select(Patient).where(Patient.id == PATIENT_ID))).scalar_one()
    assert patient.server_version == 1


# ---------------------------------------------------------------------------
# Conflict
# ---------------------------------------------------------------------------


async def test_conflict_on_base_version_mismatch_is_not_applied(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await push(client, auth_headers, [patient_record()])

    response = await push(
        client,
        auth_headers,
        [
            patient_record(
                client_updated_at="2026-08-17T09:00:00.000Z",
                base_version=99,
                full_name="Conflicting Name",
            )
        ],
    )

    assert response.status_code == 200
    result = response.json()["data"]["results"][0]
    assert result["status"] == "conflict"
    assert "server_state" in result
    assert response.json()["data"]["conflicted"] == 1

    patient = (await session.execute(select(Patient).where(Patient.id == PATIENT_ID))).scalar_one()
    assert patient.server_version == 1


# ---------------------------------------------------------------------------
# One rejected record does not fail the batch
# ---------------------------------------------------------------------------


async def test_rejected_record_does_not_fail_the_batch(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await push(
        client,
        auth_headers,
        [
            audit_record("al-good", action="encounter_started"),
            audit_record("al-bad", action="not_a_real_action"),
        ],
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["applied"] == 1
    assert data["rejected"] == 1
    bad = next(r for r in data["results"] if r["id"] == "al-bad")
    assert bad["code"] == ErrorCode.SYNC_RECORD_INVALID.value
    assert bad["message"] == "action: unknown audit action."


# ---------------------------------------------------------------------------
# Forbidden field
# ---------------------------------------------------------------------------


async def test_forbidden_audio_field_rejected_private_ailment_accepted(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    await push(client, auth_headers, [patient_record(), encounter_record()])
    response = await push(
        client,
        auth_headers,
        [
            ailment_record(
                "ail-private", visibility="PRIVATE", description="Private clinical text"
            ),
            ailment_record("ail-audio", audio_local_uri="content://media/audio123"),
        ],
    )

    assert response.status_code == 200
    data = response.json()["data"]
    results = {r["id"]: r for r in data["results"]}
    assert results["ail-private"]["status"] == "applied"
    assert results["ail-audio"]["status"] == "rejected"
    assert results["ail-audio"]["code"] == ErrorCode.SYNC_FORBIDDEN_FIELD.value


# ---------------------------------------------------------------------------
# Whole-batch preconditions
# ---------------------------------------------------------------------------


async def test_device_mismatch_fails_whole_batch(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await push(client, auth_headers, [patient_record()], device_id="some-other-device")
    assert response.status_code == 403
    assert response.json()["code"] == ErrorCode.SYNC_DEVICE_MISMATCH.value


async def test_unknown_table_fails_whole_batch(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await push(
        client, auth_headers, [_record("not_a_real_table", "x1", "2026-08-16T09:00:00.000Z", {})]
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.SYNC_UNKNOWN_TABLE.value


async def test_oversize_batch_is_rejected(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    records = [
        patient_record(record_id=f"p{i:011d}"[-12:], client_updated_at="2026-08-16T09:00:00.000Z")
        for i in range(501)
    ]
    response = await push(client, auth_headers, records)
    assert response.status_code == 413
    assert response.json()["code"] == ErrorCode.SYNC_BATCH_TOO_LARGE.value


# ---------------------------------------------------------------------------
# Audit chain: device + server serialise, duplicate detection
# ---------------------------------------------------------------------------


async def test_audit_chain_intact_under_device_and_server_contention(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """One device-origin append (through sync push's audit_log leg) and one server-origin append
    (through POST /api/v1/patients) racing each other must still serialise into one verifiable
    chain, via the shared per-facility advisory lock in app/services/audit.py.
    """
    sync_call = push(client, auth_headers, [audit_record("al-contend")])
    other_patient = {
        "id": "M9n3Ry8qS5uA",
        "full_name": "Other Patient",
        "biological_sex": "MALE",
        "age": 40,
        "district": "Jaipur",
        "created_at": "2026-08-16T09:40:00.000Z",
        "updated_at": "2026-08-16T09:40:00.000Z",
    }
    patient_call = client.post("/api/v1/patients", json=other_patient, headers=auth_headers)

    sync_response, patient_response = await asyncio.gather(sync_call, patient_call)
    assert sync_response.status_code == 200
    assert patient_response.status_code == 201

    result = await audit_service.verify_chain(session, facility_id=TEST_FACILITY_ID)
    assert result.verified is True
    assert result.first_broken_sequence is None

    origins = set((await session.execute(select(AuditEvent.origin))).scalars())
    assert origins == {"DEVICE", "SERVER"}


async def test_concurrent_batches_racing_the_same_audit_id_append_exactly_once(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """Two different batch_ids, same audit_log record_id, pushed concurrently via asyncio.gather
    so the requests genuinely overlap. Before the partial unique index on
    sync_log(table_name, record_id) WHERE table_name = 'audit_log' (app/models/sync.py,
    alembic/versions/0005), the SELECT-then-INSERT dedup in _apply_audit_log could let both
    requests pass the SELECT "not found" before either committed, and both would append. The
    fix moves the sync_log INSERT before the audit_events append and lets its IntegrityError
    close the race: this test fails (two appends, chain grows by two) without that fix.
    """
    record_id = "al-race-1"

    before = len(
        list(
            (
                await session.execute(
                    select(AuditEvent.id).where(
                        AuditEvent.origin == "DEVICE", AuditEvent.action == "encounter_started"
                    )
                )
            ).scalars()
        )
    )

    first = push(client, auth_headers, [audit_record(record_id)])
    second = push(client, auth_headers, [audit_record(record_id)])
    response_a, response_b = await asyncio.gather(first, second)

    assert response_a.status_code == 200
    assert response_b.status_code == 200

    statuses = sorted(r.json()["data"]["results"][0]["status"] for r in (response_a, response_b))
    assert statuses == ["applied", "duplicate"]

    after = len(
        list(
            (
                await session.execute(
                    select(AuditEvent.id).where(
                        AuditEvent.origin == "DEVICE", AuditEvent.action == "encounter_started"
                    )
                )
            ).scalars()
        )
    )
    assert after - before == 1

    result = await audit_service.verify_chain(session, facility_id=TEST_FACILITY_ID)
    assert result.verified is True
    assert result.first_broken_sequence is None


async def test_duplicate_audit_id_is_acked_without_growing_the_chain(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await push(client, auth_headers, [audit_record("al-dup")])

    chain_length_before = len(list((await session.execute(select(AuditEvent.id))).scalars()))

    response = await push(client, auth_headers, [audit_record("al-dup")])

    assert response.status_code == 200
    assert response.json()["data"]["results"][0]["status"] == "duplicate"

    chain_length_after = len(list((await session.execute(select(AuditEvent.id))).scalars()))
    # The duplicate audit_log record itself must not re-append. The batch still gets its own
    # sync_batch_received summary row, so the chain grows by exactly that one row, not two.
    assert chain_length_after - chain_length_before == 1


# ---------------------------------------------------------------------------
# Counts equal results tallies
# ---------------------------------------------------------------------------


async def test_counts_equal_results_tallies(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    await push(
        client,
        auth_headers,
        [patient_record(), encounter_record(), audit_record("al-seed")],
    )

    response = await push(
        client,
        auth_headers,
        [
            patient_record(client_updated_at="2026-08-16T09:00:00.000Z"),  # stale
            patient_record(record_id="Zz9YbT3wQ1", full_name="New Patient"),  # applied
            encounter_record(
                base_version=99, client_updated_at="2026-08-17T00:00:00.000Z"
            ),  # conflict
            audit_record("al-bad", action="nonsense"),  # rejected
            audit_record("al-seed"),  # duplicate
        ],
    )

    assert response.status_code == 200
    data = response.json()["data"]
    results = data["results"]

    by_status: dict[str, int] = {}
    for r in results:
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1

    assert data["applied"] == by_status.get("applied", 0)
    assert data["stale"] == by_status.get("stale", 0)
    assert data["conflicted"] == by_status.get("conflict", 0)
    assert data["rejected"] == by_status.get("rejected", 0)
    assert data["received"] == len(results)
    assert data["received"] == sum(by_status.values())
    assert by_status == {"stale": 1, "applied": 1, "conflict": 1, "rejected": 1, "duplicate": 1}


# ---------------------------------------------------------------------------
# sync_log gets a row per record processed
# ---------------------------------------------------------------------------


async def test_sync_log_has_one_row_per_record(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    body = batch_body([patient_record(), encounter_record()])
    await client.post("/api/v1/sync/push", json=body, headers=auth_headers)

    rows = list(
        (
            await session.execute(
                select(SyncLogEntry).where(SyncLogEntry.batch_id == body["batch_id"])
            )
        ).scalars()
    )
    assert len(rows) == 2
    assert {r.status for r in rows} == {"applied"}


# ---------------------------------------------------------------------------
# Empty-differential fabrication fix: kernel_empty_differential audit action
# ---------------------------------------------------------------------------


def test_kernel_empty_differential_is_in_the_accepted_device_action_set() -> None:
    """Sourced from the checked-in mirror (app/domain/audit_actions_device.py), not retyped, same
    as every other assertion against DEVICE_AUDIT_ACTIONS in this module. There is no DB-level
    CHECK constraint on audit_events.action (confirmed: models/audit.py's __table_args__ only
    constrains origin/entry_hash/previous_hash) -- app-level enforcement in services/sync.py is
    the only gate, so this and the real-insert test below are what stand in for a constraint test.
    """
    assert "kernel_empty_differential" in DEVICE_AUDIT_ACTIONS


async def test_kernel_empty_differential_audit_row_is_accepted_and_persisted(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """A device-origin audit_log row carrying action=kernel_empty_differential must sync-push
    successfully and land in audit_events with that action verbatim -- proves the accepted-set
    widening actually takes effect end to end, not just that the Python set contains the string.
    Asserts the persisted row via a fresh query, not the HTTP response, per this repo's rule that
    a write-survived-a-failure-path test must check the DB, not the return value.
    """
    response = await push(
        client,
        auth_headers,
        [
            audit_record(
                "al-empty-diff",
                action="kernel_empty_differential",
                case_record_id="case-empty-diff-1",
                payload=(
                    '{"triageUrgency":"ROUTINE","modelVersion":"xgboost-v1",'
                    '"safetyScreenPassed":"true","differentialCount":"0"}'
                ),
            )
        ],
    )
    assert response.status_code == 200

    row = (
        await session.execute(
            select(AuditEvent).where(AuditEvent.action == "kernel_empty_differential")
        )
    ).scalar_one()
    assert row.case_record_id == "case-empty-diff-1"
    assert row.origin == "DEVICE"
    # No fabricated clinical value reaches the server-side audit row either.
    assert "Non-specific" not in row.payload
    assert "confidence" not in row.payload
