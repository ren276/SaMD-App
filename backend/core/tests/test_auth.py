"""Authentication. api-contract.md section 2.

The point of these tests is not that login works. It is that the three things which make login
worth having cannot regress: the credential is actually checked, the refresh chain detects reuse,
and the forced initial PIN change cannot be skipped.
"""

from __future__ import annotations

import hashlib
from dataclasses import replace

import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.v1.auth import UNKNOWN_FACILITY
from app.deps import CurrentWorker, active_worker
from app.errors import ErrorCode, SamdError
from app.models.audit import AuditEvent
from app.models.enums import AuditAction, UserRole
from app.models.user import UserAccount
from app.scripts.seed_accounts import derive_worker_id
from app.services.auth import hash_pin
from tests.conftest import (
    TEST_FACILITY_ID,
    TEST_PIN,
    TEST_WORKER_ID,
    TEST_WORKER_NAME,
)

DEVICE_ID = "device-abc12345"


def login_payload(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "worker_id": TEST_WORKER_ID,
        "pin": TEST_PIN,
        "device_id": DEVICE_ID,
    }
    body.update(overrides)
    return body


async def _login(client: AsyncClient, **overrides: object) -> dict:  # type: ignore[type-arg]
    response = await client.post("/api/v1/auth/login", json=login_payload(**overrides))
    return {"status": response.status_code, "body": response.json()}


# ---------------------------------------------------------------------------
# worker_id derivation continuity
# ---------------------------------------------------------------------------


def test_worker_id_derivation_matches_mock_auth_session() -> None:
    """Provisioning must reproduce MockAuthSession.stableUserId exactly.

    If this drifts, every worker's audit identity discontinues at the cutover from mock auth to
    real auth, which is precisely what the stable derivation on the device was for.
    """
    expected = hashlib.sha256(b"test worker|ASHA_WORKER").hexdigest()[:16]
    assert derive_worker_id(TEST_WORKER_NAME, UserRole.ASHA_WORKER) == expected
    assert derive_worker_id("  TEST WORKER  ", UserRole.ASHA_WORKER) == expected
    assert derive_worker_id(TEST_WORKER_NAME, UserRole.ASHA_WORKER) == TEST_WORKER_ID


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------


async def test_login_succeeds_with_correct_pin(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    result = await _login(client)
    assert result["status"] == 200

    data = result["body"]["data"]
    assert data["token_type"] == "Bearer"
    assert data["expires_in"] == 3600
    assert data["access_token"]
    assert data["refresh_token"] != data["access_token"]
    assert data["worker"]["role"] == UserRole.ASHA_WORKER.value
    assert data["worker"]["facility_id"] == seeded_account.facility_id
    assert data["must_change_pin"] is False

    meta = result["body"]["meta"]
    assert meta["api_version"] == "v1"
    assert meta["request_id"]


async def test_login_rejects_wrong_pin(client: AsyncClient, seeded_account: UserAccount) -> None:
    result = await _login(client, pin="000000")
    assert result["status"] == 401
    assert result["body"]["code"] == ErrorCode.AUTH_INVALID_CREDENTIALS.value


async def test_login_response_never_contains_the_pin_or_the_hash(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    raw = (await client.post("/api/v1/auth/login", json=login_payload())).text
    assert TEST_PIN not in raw
    assert seeded_account.pin_hash not in raw


async def test_unknown_worker_and_wrong_pin_are_indistinguishable(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    """The endpoint must not be a worker-id oracle."""
    unknown = await _login(client, worker_id="ffffffffffffffff")
    wrong_pin = await _login(client, pin="000000")

    assert unknown["status"] == wrong_pin["status"] == 401
    assert unknown["body"]["code"] == wrong_pin["body"]["code"]
    assert unknown["body"]["detail"] == wrong_pin["body"]["detail"]


async def test_login_rejects_a_client_asserted_role(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    """role is a property of the account, not a claim the client makes."""
    result = await _login(client, role="DOCTOR")
    assert result["status"] == 422


async def test_lockout_after_max_failed_attempts_survives_the_rollback(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    """The counter is committed, not flushed.

    A lockout counter that rolls back with the failing request is not a lockout counter, and this
    is exactly the shape of bug that only shows up under attack.
    """
    for _ in range(5):
        assert (await _login(client, pin="000000"))["status"] == 401

    locked = await _login(client)  # correct PIN, but the account is now locked
    assert locked["status"] == 429
    assert locked["body"]["code"] == ErrorCode.SYS_RATE_LIMITED.value

    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == TEST_WORKER_ID))
    ).scalar_one()
    await session.refresh(account)
    assert account.locked_until is not None


async def test_disabled_account_cannot_log_in(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == TEST_WORKER_ID))
    ).scalar_one()
    account.is_active = False
    await session.commit()

    result = await _login(client)
    assert result["status"] == 403
    assert result["body"]["code"] == ErrorCode.AUTH_ACCOUNT_DISABLED.value


# ---------------------------------------------------------------------------
# Refresh
# ---------------------------------------------------------------------------


async def test_refresh_rotates_the_token(client: AsyncClient, seeded_account: UserAccount) -> None:
    original = (await _login(client))["body"]["data"]

    response = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": original["refresh_token"], "device_id": DEVICE_ID},
    )
    assert response.status_code == 200

    rotated = response.json()["data"]
    assert rotated["refresh_token"] != original["refresh_token"]
    assert rotated["access_token"] != original["access_token"]


async def test_refresh_reuse_revokes_the_whole_chain(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    """This is what makes a stolen refresh token survivable without Redis."""
    first = (await _login(client))["body"]["data"]["refresh_token"]

    second = (
        await client.post(
            "/api/v1/auth/refresh", json={"refresh_token": first, "device_id": DEVICE_ID}
        )
    ).json()["data"]["refresh_token"]

    # Replaying the already-rotated token is reuse.
    replay = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": first, "device_id": DEVICE_ID}
    )
    assert replay.status_code == 401
    assert replay.json()["code"] == ErrorCode.AUTH_REFRESH_REVOKED.value

    # And the legitimately-rotated token is dead too, because the chain was revoked.
    after = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": second, "device_id": DEVICE_ID}
    )
    assert after.status_code == 401
    assert after.json()["code"] == ErrorCode.AUTH_REFRESH_REVOKED.value


async def test_refresh_rejects_a_mismatched_device(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    token = (await _login(client))["body"]["data"]["refresh_token"]

    response = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": token, "device_id": "other-device-1"}
    )
    assert response.status_code == 403
    assert response.json()["code"] == ErrorCode.AUTH_DEVICE_MISMATCH.value


async def test_access_token_is_not_accepted_as_a_refresh_token(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    """The typ claim check. Without it the one hour access lifetime is decorative."""
    access = (await _login(client))["body"]["data"]["access_token"]

    response = await client.post(
        "/api/v1/auth/refresh", json={"refresh_token": access, "device_id": DEVICE_ID}
    )
    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AUTH_TOKEN_INVALID.value


async def test_refresh_token_is_not_accepted_as_an_access_token(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    refresh = (await _login(client))["body"]["data"]["refresh_token"]

    response = await client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {refresh}"})
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# me and logout
# ---------------------------------------------------------------------------


async def test_me_returns_role_derived_permissions(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    access = (await _login(client))["body"]["data"]["access_token"]

    response = await client.get("/api/v1/auth/me", headers={"Authorization": f"Bearer {access}"})
    assert response.status_code == 200

    data = response.json()["data"]
    assert data["worker_id"] == TEST_WORKER_ID
    assert data["facility_name"] == "PHC Test"
    # Decision D-1: every field role may submit to the kernel.
    assert "kernel:submit" in data["permissions"]
    # An ASHA worker cannot read the audit log.
    assert "audit:read" not in data["permissions"]


async def test_logout_revokes_the_refresh_chain_and_is_idempotent(
    client: AsyncClient, seeded_account: UserAccount
) -> None:
    tokens = (await _login(client))["body"]["data"]
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    first = await client.post(
        "/api/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]}, headers=headers
    )
    assert first.status_code == 200
    assert first.json()["data"]["revoked"] is True

    # Idempotent: revoking an already-revoked chain is still 200.
    second = await client.post(
        "/api/v1/auth/logout", json={"refresh_token": tokens["refresh_token"]}, headers=headers
    )
    assert second.status_code == 200

    # The refresh token is dead.
    refreshed = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": tokens["refresh_token"], "device_id": DEVICE_ID},
    )
    assert refreshed.status_code == 401


# ---------------------------------------------------------------------------
# Forced PIN change (decision D-3)
# ---------------------------------------------------------------------------


@pytest.fixture
async def account_pending_pin_change(session: AsyncSession, seeded_account: UserAccount) -> None:
    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == TEST_WORKER_ID))
    ).scalar_one()
    account.must_change_pin = True
    account.pin_hash = hash_pin(TEST_PIN)
    await session.commit()


async def test_initial_pin_must_be_changed_before_other_endpoints_work(
    client: AsyncClient, account_pending_pin_change: None
) -> None:
    """Without this, an administrator-issued PIN is a working long-lived credential."""
    tokens = (await _login(client))["body"]["data"]
    assert tokens["must_change_pin"] is True

    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    # me stays reachable so the client can discover why it is blocked.
    assert (await client.get("/api/v1/auth/me", headers=headers)).status_code == 200

    changed = await client.post(
        "/api/v1/auth/change-pin",
        json={"current_pin": TEST_PIN, "new_pin": "551234"},
        headers=headers,
    )
    assert changed.status_code == 200
    assert changed.json()["data"]["reauthentication_required"] is True

    # The old PIN no longer works, the new one does.
    assert (await _login(client))["status"] == 401
    assert (await _login(client, pin="551234"))["status"] == 200


async def test_change_pin_rejects_reusing_the_current_pin(
    client: AsyncClient, account_pending_pin_change: None
) -> None:
    tokens = (await _login(client))["body"]["data"]
    response = await client.post(
        "/api/v1/auth/change-pin",
        json={"current_pin": TEST_PIN, "new_pin": TEST_PIN},
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )
    assert response.status_code == 422
    assert response.json()["code"] == ErrorCode.PAT_VALIDATION_FAILED.value


async def test_change_pin_revokes_every_existing_session(
    client: AsyncClient, account_pending_pin_change: None
) -> None:
    """A PIN change that leaves old sessions alive is not a credential rotation."""
    tokens = (await _login(client))["body"]["data"]
    headers = {"Authorization": f"Bearer {tokens['access_token']}"}

    await client.post(
        "/api/v1/auth/change-pin",
        json={"current_pin": TEST_PIN, "new_pin": "551234"},
        headers=headers,
    )

    stale = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": tokens["refresh_token"], "device_id": DEVICE_ID},
    )
    assert stale.status_code == 401


async def test_logout_then_refresh_is_not_reported_as_reuse(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    """A revoked-for-a-mundane-reason token is not an attack.

    Without this distinction a routine logout or PIN change raises a security-alert-grade
    refresh_reuse_detected audit event, and false alarms of that grade are worse than none.
    """
    tokens = (await _login(client))["body"]["data"]
    await client.post(
        "/api/v1/auth/logout",
        json={"refresh_token": tokens["refresh_token"]},
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )

    response = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": tokens["refresh_token"], "device_id": DEVICE_ID},
    )
    assert response.status_code == 401
    assert response.json()["code"] == ErrorCode.AUTH_REFRESH_REVOKED.value
    assert "reuse" not in response.json()["detail"].lower()

    reuse_rows = list(
        (
            await session.execute(
                select(AuditEvent).where(
                    AuditEvent.action == AuditAction.REFRESH_REUSE_DETECTED.value
                )
            )
        ).scalars()
    )
    assert reuse_rows == []


async def test_failed_login_audit_lands_on_the_workers_own_facility_chain(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    """Events for a known worker belong on that facility's chain, not a side chain."""
    await _login(client, pin="000000")

    row = (
        await session.execute(
            select(AuditEvent).where(AuditEvent.action == AuditAction.WORKER_LOGIN_FAILED.value)
        )
    ).scalar_one()
    assert row.facility_id == TEST_FACILITY_ID
    assert row.actor_role == UserRole.ASHA_WORKER.value


async def test_unknown_worker_failed_login_is_still_recorded(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> None:
    await _login(client, worker_id="ffffffffffffffff")

    row = (
        await session.execute(
            select(AuditEvent).where(AuditEvent.action == AuditAction.WORKER_LOGIN_FAILED.value)
        )
    ).scalar_one()
    assert row.facility_id == UNKNOWN_FACILITY
    assert row.actor_id == "ffffffffffffffff"


async def test_active_worker_dependency_blocks_until_the_pin_is_changed() -> None:
    """The guard every Phase 2 route will hang off, asserted before it has a caller."""
    pending = CurrentWorker(
        worker_id=TEST_WORKER_ID,
        role=UserRole.ASHA_WORKER.value,
        facility_id=TEST_FACILITY_ID,
        device_id=DEVICE_ID,
        must_change_pin=True,
    )
    with pytest.raises(SamdError) as caught:
        await active_worker(pending)
    assert caught.value.code is ErrorCode.AUTH_PIN_CHANGE_REQUIRED

    settled = replace(pending, must_change_pin=False)
    assert await active_worker(settled) is settled
