"""The hash-chained, append-only audit log. api-contract.md section 7, hazard H-07, REQ-AUD-02.

Tamper evidence is only worth citing in a risk file if a test actually tampers.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit import GENESIS_HASH, AuditEvent
from app.models.enums import AuditAction, AuditOrigin
from app.models.user import UserAccount
from app.services import audit as audit_service
from tests.conftest import TEST_FACILITY_ID, TEST_PIN, TEST_WORKER_ID

FACILITY_B = "PHC-TEST-0002"


async def _set_trigger(session: AsyncSession, name: str, *, enabled: bool) -> None:
    """Toggle an append-only trigger, which is what an attacker holding the database would do."""
    verb = "ENABLE" if enabled else "DISABLE"
    await session.execute(text(f"ALTER TABLE audit_events {verb} TRIGGER {name}"))


async def _append(
    session: AsyncSession, action: str, facility_id: str = TEST_FACILITY_ID
) -> AuditEvent:
    event = await audit_service.append(
        session,
        action=action,
        facility_id=facility_id,
        actor_id=TEST_WORKER_ID,
        actor_role="ASHA_WORKER",
        origin=AuditOrigin.SERVER,
        payload='{"k":"v"}',
    )
    await session.commit()
    return event


async def test_first_entry_chains_from_genesis(session: AsyncSession) -> None:
    event = await _append(session, AuditAction.WORKER_LOGIN_SUCCEEDED.value)
    assert event.previous_hash == GENESIS_HASH
    assert len(event.entry_hash) == 64


async def test_entries_chain_in_sequence(session: AsyncSession) -> None:
    first = await _append(session, "a")
    second = await _append(session, "b")
    third = await _append(session, "c")

    assert second.previous_hash == first.entry_hash
    assert third.previous_hash == second.entry_hash
    assert first.sequence < second.sequence < third.sequence


async def test_chains_are_independent_per_facility(session: AsyncSession) -> None:
    a1 = await _append(session, "a", TEST_FACILITY_ID)
    b1 = await _append(session, "b", FACILITY_B)
    a2 = await _append(session, "c", TEST_FACILITY_ID)

    assert b1.previous_hash == GENESIS_HASH
    assert a2.previous_hash == a1.entry_hash


async def test_verify_reports_an_intact_chain(session: AsyncSession) -> None:
    for action in ("a", "b", "c", "d"):
        await _append(session, action)

    result = await audit_service.verify_chain(session, facility_id=TEST_FACILITY_ID)
    assert result.verified is True
    assert result.entries_checked == 4
    assert result.first_broken_sequence is None
    assert result.computed_head_hash is not None


async def test_verify_detects_a_tampered_payload(session: AsyncSession) -> None:
    """The failure mode the control exists for, exercised rather than assumed.

    The append-only trigger blocks a normal UPDATE, so the tamper is performed with the trigger
    disabled, which is what an attacker holding the database would do.
    """
    await _append(session, "a")
    target = await _append(session, "b")
    await _append(session, "c")

    await _set_trigger(session, "trg_audit_events_no_update", enabled=False)
    await session.execute(
        text("UPDATE audit_events SET payload = :p WHERE id = :i"),
        {"p": '{"k":"tampered"}', "i": target.id},
    )
    await _set_trigger(session, "trg_audit_events_no_update", enabled=True)
    await session.commit()

    result = await audit_service.verify_chain(session, facility_id=TEST_FACILITY_ID)
    assert result.verified is False
    assert result.first_broken_sequence == target.sequence


async def test_verify_detects_a_deleted_entry(session: AsyncSession) -> None:
    await _append(session, "a")
    middle = await _append(session, "b")
    await _append(session, "c")

    await _set_trigger(session, "trg_audit_events_no_delete", enabled=False)
    await session.execute(text("DELETE FROM audit_events WHERE id = :i"), {"i": middle.id})
    await _set_trigger(session, "trg_audit_events_no_delete", enabled=True)
    await session.commit()

    result = await audit_service.verify_chain(session, facility_id=TEST_FACILITY_ID)
    assert result.verified is False


async def test_update_is_rejected_by_the_database(session: AsyncSession) -> None:
    """Third enforcement layer. A trigger restrains the owner; a GRANT does not."""
    event = await _append(session, "a")

    with pytest.raises(DBAPIError):
        await session.execute(
            text("UPDATE audit_events SET action = 'edited' WHERE id = :i"), {"i": event.id}
        )
    await session.rollback()


async def test_delete_is_rejected_by_the_database(session: AsyncSession) -> None:
    event = await _append(session, "a")

    with pytest.raises(DBAPIError):
        await session.execute(text("DELETE FROM audit_events WHERE id = :i"), {"i": event.id})
    await session.rollback()


async def test_audit_service_exposes_no_mutation_method() -> None:
    """First enforcement layer, asserted so it cannot be added back by accident."""
    exported = dir(audit_service)
    assert not any(name.startswith(("update", "delete", "purge")) for name in exported)


async def test_login_writes_an_audit_row_with_the_request_id(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    inbound = "22222222-3333-4444-8555-666666666666"
    await client.post(
        "/api/v1/auth/login",
        json={"worker_id": TEST_WORKER_ID, "pin": TEST_PIN, "device_id": "device-abc12345"},
        headers={"X-Request-ID": inbound},
    )

    row = (
        await session.execute(
            select(AuditEvent).where(AuditEvent.action == AuditAction.WORKER_LOGIN_SUCCEEDED.value)
        )
    ).scalar_one()
    assert row.request_id == inbound
    assert row.actor_id == TEST_WORKER_ID
    assert row.origin == AuditOrigin.SERVER.value


async def test_failed_login_is_audited_even_though_the_request_rolls_back(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    """A failed login that leaves no trace is the opposite of what an audit log is for."""
    await client.post(
        "/api/v1/auth/login",
        json={"worker_id": TEST_WORKER_ID, "pin": "000000", "device_id": "device-abc12345"},
    )

    rows = list(
        (
            await session.execute(
                select(AuditEvent).where(AuditEvent.action == AuditAction.WORKER_LOGIN_FAILED.value)
            )
        ).scalars()
    )
    assert len(rows) == 1
    assert "SAMD-AUTH-1001" in rows[0].payload
    # The PIN never reaches the audit payload.
    assert "000000" not in rows[0].payload
