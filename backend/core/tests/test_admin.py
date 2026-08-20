"""GET /admin/dashboard: DOCTOR-only, read-only, no PHI/secret leakage."""

from __future__ import annotations

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.user import UserAccount
from app.services import audit as audit_service
from app.services.auth import hash_pin
from tests.conftest import (
    DOCTOR_WORKER_ID,
    TEST_FACILITY_ID,
    TEST_PIN,
    TEST_WORKER_ID,
    _login_headers,
)

SENTINEL_PAYLOAD = "SENTINEL_PAYLOAD_DO_NOT_RENDER_a1b2c3"


async def test_asha_worker_is_forbidden(
    client: AsyncClient, auth_headers: dict[str, str]
) -> None:
    response = await client.get("/admin/dashboard", headers=auth_headers)
    assert response.status_code == 403


async def test_doctor_can_view_dashboard(
    client: AsyncClient, doctor_headers: dict[str, str]
) -> None:
    response = await client.get("/admin/dashboard", headers=doctor_headers)
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]


@pytest.fixture
async def doctor_pending_pin_change(
    session: AsyncSession, doctor_headers: dict[str, str]
) -> None:
    account = (
        await session.execute(
            select(UserAccount).where(UserAccount.worker_id == DOCTOR_WORKER_ID)
        )
    ).scalar_one()
    account.must_change_pin = True
    account.pin_hash = hash_pin(TEST_PIN)
    await session.commit()


async def test_doctor_with_pending_pin_change_is_blocked(
    client: AsyncClient, doctor_pending_pin_change: None
) -> None:
    headers = await _login_headers(client, DOCTOR_WORKER_ID)
    response = await client.get("/admin/dashboard", headers=headers)
    assert response.status_code == 403


async def test_dashboard_never_renders_pin_hash_or_audit_payload(
    client: AsyncClient,
    doctor_headers: dict[str, str],
    session: AsyncSession,
) -> None:
    account = (
        await session.execute(
            select(UserAccount).where(UserAccount.worker_id == TEST_WORKER_ID)
        )
    ).scalar_one()
    real_pin_hash = account.pin_hash

    await audit_service.append(
        session,
        action="TEST_SENTINEL_ACTION",
        facility_id=TEST_FACILITY_ID,
        actor_id=TEST_WORKER_ID,
        payload=f'{{"note": "{SENTINEL_PAYLOAD}"}}',
    )
    await session.commit()

    response = await client.get("/admin/dashboard", headers=doctor_headers)
    assert response.status_code == 200
    body = response.text

    assert real_pin_hash not in body
    assert "pin_hash" not in body
    assert "payload" not in body
    assert SENTINEL_PAYLOAD not in body
