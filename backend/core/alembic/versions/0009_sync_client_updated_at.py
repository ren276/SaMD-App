"""Store the sync revision (client_updated_at) on every synced table.

Last-write-wins compares the incoming client_updated_at with the STORED client_updated_at plus
server_version (docs/sync-design.md, section 3). No synced table stored that revision. The code
compared the incoming value against a data column instead: updated_at where the model had one,
created_at where it did not, and for kernel_reports, evaluate_reports, medication_lines and
referrals neither exists, so re-syncing an existing row of those tables raised AttributeError,
escaped the per-record savepoint and answered 500 for the whole batch.

One nullable column on all 19 synced tables (the models that use SyncMixin).

  - NULL on every existing row. NO backfill: updated_at, created_at and inference_ended_at mean
    something different from a sync revision, and a value guessed from them would be a fabricated
    revision. A stored NULL counts as older than any incoming write, so the first write after this
    revision applies and stores a real value. The window this leaves (an old delayed write could
    overwrite a newer one on a legacy row) is closed by the pre-pilot data reset, not by a guess.
  - No index: every lookup is by primary key.

Server-owned: stored from the sync envelope, never accepted from a payload.

DOWNGRADE drops the column from all 19 tables and loses the stored revisions.

Revision ID: 0009
Revises: 0008
Create Date: 2026-10-01
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Frozen: the 19 tables whose models use SyncMixin at this revision.
_TABLES = (
    "patients",
    "encounters",
    "consultations",
    "attachments",
    "observations",
    "ailments",
    "medical_history_items",
    "allergies",
    "family_history_entries",
    "social_histories",
    "medication_entries",
    "case_records",
    "kernel_reports",
    "evaluate_reports",
    "diagnosis_feedback",
    "prescriptions",
    "medication_lines",
    "referrals",
    "abha_profiles",
)


def upgrade() -> None:
    for table in _TABLES:
        op.add_column(
            table, sa.Column("client_updated_at", sa.DateTime(timezone=True), nullable=True)
        )


def downgrade() -> None:
    for table in reversed(_TABLES):
        op.drop_column(table, "client_updated_at")
