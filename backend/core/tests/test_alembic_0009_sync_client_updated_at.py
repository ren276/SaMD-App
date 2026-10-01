"""Alembic 0009 (client_updated_at on every synced table), actually executed.

No other test runs a migration (the suite builds its schema with create_all), so this builds
0001..0008 in a scratch schema inside the test database, applies and reverses 0009 through
Alembic's own Operations, and compares the models with the migrated schema using Alembic's
autogenerate. Everything runs inside a transaction that is rolled back, so nothing persists and
nothing outside the scratch schema is touched.
"""

from __future__ import annotations

import importlib.util
from collections.abc import AsyncIterator
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncConnection, create_async_engine

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from alembic.operations import Operations
from app.config import Settings
from app.db.base import Base
from app.services.sync import TABLE_REGISTRY

_VERSIONS = Path(__file__).resolve().parents[1] / "alembic" / "versions"
_SCHEMA = "mig_0009_sync_scratch"
_TABLES = tuple(TABLE_REGISTRY)


def _load(path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(f"_mig_{path.stem}", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _migrations(*revisions: str) -> list[ModuleType]:
    modules = [_load(p) for p in sorted(_VERSIONS.glob("0*.py"))]
    return [m for m in modules if m.revision in revisions] if revisions else modules


def _run(sync_conn: Connection, modules: list[ModuleType], direction: str) -> None:
    ctx = MigrationContext.configure(sync_conn, opts={"target_metadata": Base.metadata})
    with Operations.context(ctx):
        for module in modules:
            getattr(module, direction)()


@pytest.fixture
async def scratch(test_settings: Settings) -> AsyncIterator[AsyncConnection]:
    engine = create_async_engine(test_settings.database_url, poolclass=None)
    conn = await engine.connect()
    trans = await conn.begin()
    try:
        await conn.execute(text(f"CREATE SCHEMA {_SCHEMA}"))
        await conn.execute(text(f"SET LOCAL search_path TO {_SCHEMA}, public"))
        yield conn
    finally:
        await trans.rollback()
        await conn.close()
        await engine.dispose()


async def _nullable_by_table(conn: AsyncConnection) -> dict[str, str]:
    """{table: is_nullable} for every table in the scratch schema that HAS the column."""
    rows = await conn.execute(
        text(
            "SELECT table_name, is_nullable FROM information_schema.columns "
            "WHERE table_schema = :s AND column_name = 'client_updated_at'"
        ),
        {"s": _SCHEMA},
    )
    return dict(rows.tuples().all())


async def _migrate_to_0008(conn: AsyncConnection) -> None:
    await conn.run_sync(_run, [m for m in _migrations() if m.revision <= "0008"], "upgrade")


async def test_the_registry_is_the_nineteen_tables_the_migration_covers() -> None:
    assert len(_TABLES) == 19
    assert sorted(_load(_VERSIONS / "0009_sync_client_updated_at.py")._TABLES) == sorted(_TABLES)


async def test_upgrade_adds_a_nullable_client_updated_at_to_all_19_tables(
    scratch: AsyncConnection,
) -> None:
    await _migrate_to_0008(scratch)
    assert await _nullable_by_table(scratch) == {}  # absent everywhere at 0008

    await scratch.run_sync(_run, _migrations("0009"), "upgrade")

    present = await _nullable_by_table(scratch)
    assert sorted(present) == sorted(_TABLES)
    assert set(present.values()) == {"YES"}  # nullable, with no backfill and no default


async def test_downgrade_removes_it_from_all_19_tables(scratch: AsyncConnection) -> None:
    await _migrate_to_0008(scratch)
    await scratch.run_sync(_run, _migrations("0009"), "upgrade")
    assert len(await _nullable_by_table(scratch)) == 19

    await scratch.run_sync(_run, _migrations("0009"), "downgrade")

    assert await _nullable_by_table(scratch) == {}


async def test_upgrade_again_after_a_downgrade_works(scratch: AsyncConnection) -> None:
    await _migrate_to_0008(scratch)
    for direction in ("upgrade", "downgrade", "upgrade"):
        await scratch.run_sync(_run, _migrations("0009"), direction)
    assert len(await _nullable_by_table(scratch)) == 19


async def test_models_and_the_migrated_schema_agree_for_the_19_tables(
    scratch: AsyncConnection,
) -> None:
    """Alembic autogenerate over the migrated schema: a column on a model with no migration (or the
    reverse) shows up as a difference here."""
    await scratch.run_sync(_run, _migrations(), "upgrade")

    def diff(sync_conn: Connection) -> list[Any]:
        ctx = MigrationContext.configure(
            sync_conn,
            opts={
                "target_metadata": Base.metadata,
                "compare_type": True,
                "compare_server_default": True,
            },
        )
        return compare_metadata(ctx, Base.metadata)

    flat: list[Any] = []
    for entry in await scratch.run_sync(diff):
        flat.extend(entry if isinstance(entry, list) else [entry])
    relevant = [d for d in flat if any(f"'{table}'" in repr(d) for table in _TABLES)]
    assert relevant == []
