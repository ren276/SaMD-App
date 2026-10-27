"""The sync push owner check (facility-scope memo 3.5, ruling FS-6; api-contract.md section 6.1).

Every assertion about what happened reads the PERSISTED state back from the database, never only
the HTTP response (CLAUDE.md backend convention): a refused batch must leave no clinical row, no
sync_batches row and no sync_log row, and its refusal must leave a server-origin audit row that
survived the request rollback.
"""

from __future__ import annotations

import json

from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.errors import ErrorCode
from app.models.audit import AuditEvent
from app.models.enums import AuditAction
from app.models.patient import Patient
from app.models.sync import SyncBatch, SyncLogEntry
from tests.conftest import OTHER_FACILITY_ID, TEST_FACILITY_ID
from tests.test_patients import PATIENT_ID
from tests.test_sync import audit_record, batch_body, patient_record


async def _count(session: AsyncSession, model: type, *where: object) -> int:
    return (await session.execute(select(func.count()).select_from(model).where(*where))).scalar_one()


async def _mismatch_rows(session: AsyncSession) -> list[AuditEvent]:
    return list(
        (
            await session.execute(
                select(AuditEvent).where(AuditEvent.action == AuditAction.SYNC_BATCH_OWNER_MISMATCH.value)
            )
        ).scalars()
    )


async def test_a_batch_owned_by_another_facility_is_refused_whole_and_nothing_is_persisted(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    body = batch_body([patient_record(), audit_record("al-own-1")], owner_facility_id=OTHER_FACILITY_ID)
    device_audit_before = await _count(session, AuditEvent, AuditEvent.origin == "DEVICE")

    response = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)

    assert response.status_code == 403
    assert response.json()["code"] == ErrorCode.SYNC_OWNER_FACILITY_MISMATCH.value
    session.expire_all()
    assert await _count(session, Patient, Patient.id == PATIENT_ID) == 0, "no clinical row is written"
    assert await _count(session, SyncBatch, SyncBatch.batch_id == body["batch_id"]) == 0
    assert await _count(session, SyncLogEntry, SyncLogEntry.batch_id == body["batch_id"]) == 0
    assert await _count(session, AuditEvent, AuditEvent.origin == "DEVICE") == device_audit_before, (
        "the batch's device audit record is not appended"
    )
    assert await _count(session, AuditEvent, AuditEvent.action == AuditAction.SYNC_BATCH_RECEIVED.value) == 0

    rows = await _mismatch_rows(session)
    assert len(rows) == 1, "the refusal is audited, and the row survived the request rollback"
    row = rows[0]
    assert row.facility_id == TEST_FACILITY_ID, "filed on the chain of the session it arrived under"
    assert row.origin == "SERVER"
    assert json.loads(row.payload) == {
        "batch_id": body["batch_id"],
        "owner_facility_id": OTHER_FACILITY_ID,
        "received": 2,
    }


async def test_a_batch_owned_by_the_session_facility_still_applies(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    body = batch_body([patient_record()])

    response = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)

    assert response.status_code == 200
    session.expire_all()
    assert await _count(session, Patient, Patient.id == PATIENT_ID) == 1
    assert await _count(session, SyncBatch, SyncBatch.batch_id == body["batch_id"]) == 1
    assert await _mismatch_rows(session) == []


async def test_the_owner_check_runs_before_the_replay_lookup(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    """A batch_id already applied, resent with another facility's owner: refused, not replayed.
    This is H-57's shape on the server: an in-flight batch resent under another session."""
    body = batch_body([patient_record()])
    first = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)
    assert first.status_code == 200

    resent = await client.post(
        "/api/v1/sync/push", json={**body, "owner_facility_id": OTHER_FACILITY_ID}, headers=auth_headers
    )

    assert resent.status_code == 403
    assert resent.json()["code"] == ErrorCode.SYNC_OWNER_FACILITY_MISMATCH.value
    session.expire_all()
    assert len(await _mismatch_rows(session)) == 1


async def test_a_missing_or_empty_owner_is_a_malformed_envelope(
    client: AsyncClient, auth_headers: dict[str, str], session: AsyncSession
) -> None:
    missing = batch_body([patient_record()])
    del missing["owner_facility_id"]
    for body in (missing, batch_body([patient_record()], owner_facility_id="")):
        response = await client.post("/api/v1/sync/push", json=body, headers=auth_headers)

        assert response.status_code == 400
        assert response.json()["code"] == ErrorCode.SYS_MALFORMED_REQUEST.value
    session.expire_all()
    assert await _count(session, Patient, Patient.id == PATIENT_ID) == 0
    assert await _count(session, SyncBatch) == 0
