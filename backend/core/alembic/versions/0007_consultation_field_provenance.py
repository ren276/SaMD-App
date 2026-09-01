"""Add consultations.impact_on_daily_activities_provenance.

ASR track PR 1 (scratchpad/asr-field-audit-memo.md Part B.2). Adds the first
FieldProvenance column, nullable, no CHECK constraint (the memo does not call for one at this
stage). Existing rows get NULL, not a backfilled 'TYPED': the device-side migration
(MIGRATION_16_17) backfills every existing local row to 'TYPED' before it can ever reach this
column, so no row synced from a pre-PR-1 device build exists to backfill server-side, and a
brand-new server column has no historical rows of its own. Nothing writes a non-TYPED value yet.

Revision ID: 0007
Revises: 0006
Create Date: 2026-08-31
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "consultations",
        sa.Column("impact_on_daily_activities_provenance", sa.String(length=20), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("consultations", "impact_on_daily_activities_provenance")
