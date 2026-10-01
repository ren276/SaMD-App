"""Shared scratch-schema harness for the tests that actually execute Alembic migrations.

The suite builds its schema with create_all, so no other test runs a migration. These helpers
build the migration chain in a scratch schema inside the test database, through Alembic's own
Operations. The `scratch` fixture (conftest.py) wraps everything in a transaction that is rolled
back, so nothing persists and nothing outside the scratch schema is touched.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType

from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncConnection

from alembic.migration import MigrationContext
from alembic.operations import Operations
from app.db.base import Base

SCHEMA = "mig_scratch"
_VERSIONS = Path(__file__).resolve().parents[1] / "alembic" / "versions"


def load(path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(f"_mig_{path.stem}", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_version(filename: str) -> ModuleType:
    return load(_VERSIONS / filename)


def migrations(*revisions: str) -> list[ModuleType]:
    """Every migration in order, or only the named revisions."""
    modules = [load(p) for p in sorted(_VERSIONS.glob("0*.py"))]
    return [m for m in modules if m.revision in revisions] if revisions else modules


def run(sync_conn: Connection, modules: list[ModuleType], direction: str) -> None:
    ctx = MigrationContext.configure(sync_conn, opts={"target_metadata": Base.metadata})
    with Operations.context(ctx):
        for module in modules:
            getattr(module, direction)()


async def upgrade_to(conn: AsyncConnection, last: str) -> None:
    await conn.run_sync(run, [m for m in migrations() if m.revision <= last], "upgrade")
