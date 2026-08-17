"""Test fixtures.

Tests run against a real PostgreSQL 16. Not SQLite: the audit chain uses pg_advisory_xact_lock,
the schema uses regex CHECK constraints and an Identity column, and the append-only guarantee is
a plpgsql trigger. A test suite that runs on a database the service will never see verifies the
wrong thing.

Schema is created with create_all rather than Alembic so a broken migration fails its own test
loudly instead of failing every test confusingly. CI runs `alembic upgrade head` separately and
fails the build if autogenerate still finds a diff.
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator, Iterator

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

import app.models
from app.config import Settings, get_settings
from app.db import session as session_module
from app.db.base import Base
from app.deps import settings_dep
from app.models.enums import UserRole
from app.models.facility import Facility
from app.models.user import UserAccount
from app.services.auth import hash_pin

TEST_DATABASE_URL = os.environ.get(
    "TEST_DATABASE_URL",
    "postgresql+asyncpg://samd:samd_dev_only@localhost:5432/samd_test",
)

TEST_FACILITY_ID = "PHC-TEST-0001"
TEST_WORKER_NAME = "Test Worker"
# sha256("test worker|ASHA_WORKER") truncated to 16 hex characters, the same derivation
# MockAuthSession.stableUserId performs on the device. Written out literally rather than computed
# here, so a change to the derivation breaks a test instead of silently agreeing with itself.
TEST_WORKER_ID = "949ad656774570f6"
TEST_PIN = "482915"


@pytest.fixture(scope="session")
def test_settings() -> Settings:
    return Settings(  # type: ignore[call-arg]
        environment="dev",
        require_https=False,
        database_url=TEST_DATABASE_URL,
        jwt_secret_key="test-only-jwt-secret-key-at-least-32-chars",
        phi_encryption_key="test-only-phi-encryption-key-at-least-32",
        blind_index_key="test-only-blind-index-key-at-least-32-chars",
        case_token_key="test-only-case-token-key-at-least-32-chars",
        log_level="WARNING",
    )


@pytest.fixture(autouse=True)
async def _database(test_settings: Settings) -> AsyncIterator[None]:
    """Fresh schema per test. Slower than truncation, and worth it for a chain-hash suite."""
    engine = create_async_engine(test_settings.database_url, poolclass=None)
    async with engine.begin() as conn:
        await conn.execute(text("CREATE EXTENSION IF NOT EXISTS pgcrypto"))
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
        # The append-only trigger lives in the migration, not the metadata. Recreated here so
        # test_audit_chain can assert it, rather than testing a table that only looks the part.
        await conn.execute(
            text(
                """
                CREATE OR REPLACE FUNCTION audit_events_reject_mutation() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'audit_events is append-only (SAMD-AUDIT-7002)'
                        USING ERRCODE = 'raise_exception';
                END;
                $$ LANGUAGE plpgsql;
                """
            )
        )
        for trigger in ("trg_audit_events_no_update", "trg_audit_events_no_delete"):
            await conn.execute(text(f"DROP TRIGGER IF EXISTS {trigger} ON audit_events"))
        await conn.execute(
            text(
                "CREATE TRIGGER trg_audit_events_no_update BEFORE UPDATE ON audit_events "
                "FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation()"
            )
        )
        await conn.execute(
            text(
                "CREATE TRIGGER trg_audit_events_no_delete BEFORE DELETE ON audit_events "
                "FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation()"
            )
        )

    # Point the application's session factory at the test engine.
    session_module._engine = engine
    session_module._sessionmaker = async_sessionmaker(bind=engine, expire_on_commit=False)

    try:
        yield
    finally:
        await engine.dispose()
        session_module._engine = None
        session_module._sessionmaker = None


@pytest.fixture
async def session() -> AsyncIterator[AsyncSession]:
    factory = session_module.get_sessionmaker()
    async with factory() as db:
        yield db


@pytest.fixture
async def seeded_account(session: AsyncSession) -> UserAccount:
    session.add(
        Facility(id=TEST_FACILITY_ID, name="PHC Test", district="Jaipur", state="Rajasthan")
    )
    account = UserAccount(
        worker_id=TEST_WORKER_ID,
        display_name=TEST_WORKER_NAME,
        role=UserRole.ASHA_WORKER.value,
        facility_id=TEST_FACILITY_ID,
        pin_hash=hash_pin(TEST_PIN),
        # Seeded already-changed so the auth tests exercise the normal path. The forced-change
        # path has its own test.
        must_change_pin=False,
        is_active=True,
    )
    session.add(account)
    await session.commit()
    return account


@pytest.fixture
def app(test_settings: Settings) -> Iterator[FastAPI]:
    """The FastAPI app, exposed as its own fixture so a test can add further
    dependency_overrides (kernel client, circuit breakers) before requests are made.

    ASGITransport (used by the client fixture below) never runs FastAPI's lifespan, so
    app.state.kernel_client is never populated the way it is in production. Kernel tests
    override the dependency instead of relying on app.state; see tests/test_kernel.py.
    """
    from app.main import create_app

    get_settings.cache_clear()
    application = create_app()
    application.dependency_overrides[settings_dep] = lambda: test_settings

    yield application

    application.dependency_overrides.clear()
    get_settings.cache_clear()


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as http:
        yield http


# ---------------------------------------------------------------------------
# Phase 2 fixtures: authenticated callers and a second facility to isolate against
# ---------------------------------------------------------------------------

TEST_DEVICE_ID = "device-abc12345"
DOCTOR_WORKER_ID = "00000000000d0c00"
OTHER_FACILITY_ID = "PHC-TEST-0002"
OTHER_WORKER_ID = "0000000000000f02"


async def _login_headers(http: AsyncClient, worker_id: str) -> dict[str, str]:
    response = await http.post(
        "/api/v1/auth/login",
        json={"worker_id": worker_id, "pin": TEST_PIN, "device_id": TEST_DEVICE_ID},
    )
    token = response.json()["data"]["access_token"]
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
async def auth_headers(client: AsyncClient, seeded_account: UserAccount) -> dict[str, str]:
    """An ASHA worker at TEST_FACILITY_ID, past the forced PIN change."""
    return await _login_headers(client, TEST_WORKER_ID)


@pytest.fixture
async def doctor_headers(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> dict[str, str]:
    """A DOCTOR at the same facility. GET /encounters/{id} is DOCTOR only."""
    session.add(
        UserAccount(
            worker_id=DOCTOR_WORKER_ID,
            display_name="Dr Test",
            role=UserRole.DOCTOR.value,
            facility_id=TEST_FACILITY_ID,
            pin_hash=hash_pin(TEST_PIN),
            must_change_pin=False,
            is_active=True,
        )
    )
    await session.commit()
    return await _login_headers(client, DOCTOR_WORKER_ID)


@pytest.fixture
async def other_facility_headers(
    client: AsyncClient, seeded_account: UserAccount, session: AsyncSession
) -> dict[str, str]:
    """A worker at a different PHC. Every read and write must be invisible across this line."""
    session.add(Facility(id=OTHER_FACILITY_ID, name="PHC Other"))
    session.add(
        UserAccount(
            worker_id=OTHER_WORKER_ID,
            display_name="Other Worker",
            role=UserRole.NURSE.value,
            facility_id=OTHER_FACILITY_ID,
            pin_hash=hash_pin(TEST_PIN),
            must_change_pin=False,
            is_active=True,
        )
    )
    await session.commit()
    return await _login_headers(client, OTHER_WORKER_ID)
