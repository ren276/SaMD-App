"""Close the audit_log dedup race: a partial unique index on sync_log, not a table-wide one.

The Phase 4 review's finding was that audit_log dedup (REQ-AUD-02) is check-then-act, SELECT then
INSERT against the non-unique ix_sync_log_table_record, so two concurrent batches carrying the
same client-generated audit_log record_id could both SELECT "not found" and both append to the
append-only chain.

The brief for this migration asked for `UniqueConstraint("table_name", "record_id")` on the whole
table. That would be wrong and was not done: sync_log is a history log for every OTHER table too,
and a record legitimately gets more than one sync_log row over its lifetime there (applied once,
then stale or conflict on every retry after; see test_stale_is_acked_as_success_and_not_applied
and test_counts_equal_results_tallies, both of which push the same record_id twice on purpose and
assert a second, different-status row each time). A table-wide unique constraint on
(table_name, record_id) would turn the second push of the exact same patients/encounters/etc. row
into an IntegrityError instead of the stale/conflict/applied result the contract requires, breaking
every one of those already-passing tests.

audit_log is the one table where "more than one row per record_id" must never happen: it is
append-only, a repeat of the same id is defined by REQ-AUD-02 to be a no-op, and the row it
produces (or the audit_events entry it gates) cannot be corrected after the fact. So the
constraint is scoped to exactly that table with a partial unique index, which PostgreSQL supports
for a UNIQUE INDEX but not for a UNIQUE CONSTRAINT (there is no `WHERE` clause on the constraint
form, only on the index form). Every other table keeps accumulating history rows exactly as
before; only `table_name = 'audit_log'` is now enforced as one row per record_id, ever.

Checked before writing this migration: `SELECT table_name, record_id, count(*) FROM sync_log WHERE
table_name = 'audit_log' GROUP BY 1, 2 HAVING count(*) > 1` against the dev database returns zero
rows, so the index can be created directly. If a populated database ever does have duplicates when
this runs, the migration fails loudly on `CREATE UNIQUE INDEX` rather than silently corrupting the
audit trail; dev data is disposable and the fix is to recreate the volume, not to write a data
migration that decides which duplicate audit_log row is authoritative.

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-17
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_index(
        "uq_sync_log_audit_log_record_id",
        "sync_log",
        ["table_name", "record_id"],
        unique=True,
        postgresql_where=sa.text("table_name = 'audit_log'"),
    )


def downgrade() -> None:
    op.drop_index("uq_sync_log_audit_log_record_id", table_name="sync_log")
