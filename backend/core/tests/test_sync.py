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
from app.models.patient import Patient
from app.models.sync import SyncBatch, SyncLogEntry
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


def audit_record(
    record_id: str,
    *,
    action: str = "encounter_created",
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
            audit_record("al-good", action="encounter_created"),
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
