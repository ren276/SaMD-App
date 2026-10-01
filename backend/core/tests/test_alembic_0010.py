"""Alembic 0010 actually run: upgrade, behaviour of the result, downgrade and its refusal.

No other test executes a migration (the suite builds its schema with create_all), so the DDL in a
migration file was never exercised by pytest before this one. This builds 0001..0010 in a scratch
schema inside the test database, inside a transaction that is rolled back, so nothing persists and
nothing outside the scratch schema is touched. Every migration module is run through Alembic's
own Operations, not re-implemented here.

Models and migration are also compared with Alembic's autogenerate, restricted to the kernel
tables 0010 changes, so a column added to a model without a migration (or the reverse) fails here.
"""

from __future__ import annotations

from typing import Any

import pytest
from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import AsyncConnection

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from app.db.base import Base
from tests.alembic_scratch import SCHEMA, migrations, run, upgrade_to

_KERNEL_TABLES = (
    "kernel_reports",
    "kernel_call_log",
    "kernel_assessments",
    "kernel_derivation_checks",
)


async def _scalar(conn: AsyncConnection, sql: str, **params: Any) -> Any:
    return (await conn.execute(text(sql), params)).scalar_one()


async def _columns(conn: AsyncConnection, table: str) -> dict[str, str]:
    rows = await conn.execute(
        text(
            "SELECT column_name, is_nullable FROM information_schema.columns "
            "WHERE table_schema = :s AND table_name = :t"
        ),
        {"s": SCHEMA, "t": table},
    )
    return dict(rows.tuples().all())


async def _constraint_names(conn: AsyncConnection, table: str) -> set[str]:
    rows = await conn.execute(
        text(
            "SELECT c.conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid "
            "JOIN pg_namespace n ON n.oid = t.relnamespace "
            "WHERE n.nspname = :s AND t.relname = :t"
        ),
        {"s": SCHEMA, "t": table},
    )
    return {name for (name,) in rows}


async def _drop_kernel_report_fks(conn: AsyncConnection) -> None:
    """Scratch schema only: lets a kernel_reports row exist without building its parent graph."""
    for name in await _constraint_names(conn, "kernel_reports"):
        if name.startswith("fk_"):
            await conn.execute(text(f"ALTER TABLE kernel_reports DROP CONSTRAINT {name}"))


async def _insert_report(conn: AsyncConnection, report_id: str, model_version: str | None) -> None:
    await conn.execute(
        text(
            "INSERT INTO kernel_reports (id, case_record_id, predicted_condition, "
            "confidence_score, differentials, reasoning_summary, evidence_for, evidence_against, "
            "model_version, device_id, software_version, risk_category, urgency_level, "
            "inference_started_at, inference_ended_at, required_human_verification, "
            "inference_source, facility_id) VALUES (:id, 'cr', 'high_risk', 0.8, '{}', 'r', '{}', "
            "'{}', :mv, 'd', '1', 'HIGH', 'URGENT', now(), now(), true, 'REAL_INFERENCE', 'F')"
        ),
        {"id": report_id, "mv": model_version},
    )


# --- upgrade -----------------------------------------------------------------------------------


async def test_upgrade_adds_every_column_check_index_table_and_trigger(
    scratch: AsyncConnection,
) -> None:
    await upgrade_to(scratch, "0009")
    assert (await _columns(scratch, "kernel_reports"))["model_version"] == "NO"
    assert "request_id" not in await _columns(scratch, "kernel_reports")

    mods = [m for m in migrations() if m.revision == "0010"]
    await scratch.run_sync(run, mods, "upgrade")

    reports = await _columns(scratch, "kernel_reports")
    assert reports["model_version"] == "YES"
    for column in ("model_calibrated", "request_id", "derivation_rule_version"):
        assert reports[column] == "YES"
    assert "model_sha256" in await _columns(scratch, "kernel_call_log")
    assessments = await _columns(scratch, "kernel_assessments")
    assert "model_sha256" in assessments and "model_calibrated" in assessments

    checks = await _columns(scratch, "kernel_derivation_checks")
    assert {
        "id",
        "kernel_report_id",
        "facility_id",
        "request_id",
        "kernel_assessment_id",
        "rederive_status",
        "rule_version_used",
        "device_rule_version",
        "mismatch_fields",
        "created_at",
    } == set(checks)
    assert checks["kernel_report_id"] == "NO" and checks["kernel_assessment_id"] == "YES"

    names = await _constraint_names(scratch, "kernel_derivation_checks")
    for suffix in (
        "rederive_status",
        "mismatch_vocab",
        "mismatch_consistent",
        "link_consistent",
        "request_id_format",
    ):
        assert any(n.endswith(suffix) for n in names), suffix
    assert any(
        n.endswith("request_id_format") for n in await _constraint_names(scratch, "kernel_reports")
    )

    triggers = {
        row[0]
        for row in await scratch.execute(
            text(
                "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                "JOIN pg_namespace n ON n.oid = c.relnamespace "
                "WHERE n.nspname = :s AND c.relname = 'kernel_derivation_checks' "
                "AND NOT t.tgisinternal"
            ),
            {"s": SCHEMA},
        )
    }
    assert triggers == {
        "trg_kernel_derivation_checks_no_update",
        "trg_kernel_derivation_checks_no_delete",
    }


async def test_a_row_written_before_0010_survives_and_a_null_version_is_then_allowed(
    scratch: AsyncConnection,
) -> None:
    await upgrade_to(scratch, "0009")
    await _drop_kernel_report_fks(scratch)
    await _insert_report(scratch, "old-1", "remote-kernel")

    await scratch.run_sync(run, [m for m in migrations() if m.revision == "0010"], "upgrade")

    # The legacy sentinel is NOT rewritten: pre-pilot data is reset, not cleansed (D3).
    assert await _scalar(
        scratch, "SELECT model_version FROM kernel_reports WHERE id = 'old-1'"
    ) == ("remote-kernel")
    assert (
        await _scalar(scratch, "SELECT request_id FROM kernel_reports WHERE id = 'old-1'") is None
    )
    await _insert_report(scratch, "new-1", None)
    assert await _scalar(scratch, "SELECT count(*) FROM kernel_reports") == 2


async def test_models_and_migration_agree_for_the_kernel_tables(scratch: AsyncConnection) -> None:
    await upgrade_to(scratch, "0010")

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
    relevant = [d for d in flat if any(table in repr(d) for table in _KERNEL_TABLES)]
    assert relevant == []


# --- downgrade ---------------------------------------------------------------------------------


async def test_downgrade_refuses_while_a_model_version_is_null_and_changes_nothing(
    scratch: AsyncConnection,
) -> None:
    await upgrade_to(scratch, "0010")
    await _drop_kernel_report_fks(scratch)
    await _insert_report(scratch, "null-1", None)

    with pytest.raises(RuntimeError, match="0010 downgrade refused: 1 kernel_reports"):
        await scratch.run_sync(run, [m for m in migrations() if m.revision == "0010"], "downgrade")

    # Refusal happened before any DDL: the table and the columns are all still there.
    assert await _scalar(scratch, "SELECT count(*) FROM kernel_derivation_checks") == 0
    assert "request_id" in await _columns(scratch, "kernel_reports")
    assert await _scalar(scratch, "SELECT count(*) FROM kernel_reports WHERE id = 'null-1'") == 1


async def test_downgrade_succeeds_without_null_versions_and_upgrade_can_follow(
    scratch: AsyncConnection,
) -> None:
    await upgrade_to(scratch, "0010")
    await _drop_kernel_report_fks(scratch)
    await _insert_report(scratch, "ok-1", "toy-v0.6")
    m0010 = [m for m in migrations() if m.revision == "0010"]

    await scratch.run_sync(run, m0010, "downgrade")

    reports = await _columns(scratch, "kernel_reports")
    assert reports["model_version"] == "NO"
    for gone in ("model_calibrated", "request_id", "derivation_rule_version"):
        assert gone not in reports
    assert "model_sha256" not in await _columns(scratch, "kernel_call_log")
    assert "model_calibrated" not in await _columns(scratch, "kernel_assessments")
    assert await _columns(scratch, "kernel_derivation_checks") == {}
    assert await _scalar(scratch, "SELECT count(*) FROM kernel_reports") == 1

    await scratch.run_sync(run, m0010, "upgrade")
    assert "request_id" in await _columns(scratch, "kernel_reports")
