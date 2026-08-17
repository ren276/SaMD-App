"""Add kernel_assessments, the server-owned record of raw kernel output.

Why this table exists rather than more columns on kernel_reports: the Phase 3 proxy wrote a
kernel_reports row per /v1/assess call, filling predicted_condition, confidence_score,
risk_category and required_human_verification with values the backend computed and then storing
them beside model_id and model_version. None of those four fields is on the /v1/assess wire.
Storing backend arithmetic in columns that read as model output attributes it to a named model
version, which is what IEC 62304 traceability forbids, and it collided with the device, which
upserts one kernel_reports row per case while the proxy inserted one per call. kernel_reports is
now device-owned and written only by sync push (Phase 4). This table holds raw model output only,
and every derived clinical value is computed at read time by app/domain/kernel_derivation.py.

NO DATA MIGRATION, deliberately. Any kernel_reports row a dev database already holds that was
written by the proxy is orphaned by design: it carries a facility_id and a device_id that belong
to the calling worker rather than to the device that produced the assessment, and there is no
honest way to reinterpret it. Dev data is disposable; recreate the volume rather than backfilling.

Nullable columns are nullable because /evaluate carries neither safety_screen_passed nor
triage_urgency nor a model_metadata block. There are no server_defaults on those columns on
purpose: an invented default is the exact mistake this table was created to remove.

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-17
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "kernel_assessments",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("request_id", sa.String(length=36), nullable=False),
        sa.Column("case_record_id", sa.String(length=36), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("endpoint", sa.String(length=20), nullable=False),
        sa.Column("raw_response", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("model_version", sa.String(length=80), nullable=True),
        sa.Column("inference_time_ms", sa.Integer(), nullable=True),
        sa.Column("safety_screen_passed", sa.Boolean(), nullable=True),
        sa.Column("triage_urgency", sa.String(length=40), nullable=True),
        sa.Column("response_sha256", sa.String(length=64), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False
        ),
        sa.CheckConstraint(
            "endpoint IN ('ASSESS', 'EVALUATE')", name="ck_kernel_assessments_endpoint"
        ),
        sa.ForeignKeyConstraint(
            ["case_record_id"],
            ["case_records.id"],
            name="fk_kernel_assessments_case_record_id_case_records",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["facility_id"],
            ["facilities.id"],
            name="fk_kernel_assessments_facility_id_facilities",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_kernel_assessments"),
    )
    op.create_index("ix_kernel_assessments_request_id", "kernel_assessments", ["request_id"])
    op.create_index(
        "ix_kernel_assessments_case_record_id", "kernel_assessments", ["case_record_id"]
    )
    op.create_index("ix_kernel_assessments_facility_id", "kernel_assessments", ["facility_id"])


def downgrade() -> None:
    op.drop_index("ix_kernel_assessments_facility_id", table_name="kernel_assessments")
    op.drop_index("ix_kernel_assessments_case_record_id", table_name="kernel_assessments")
    op.drop_index("ix_kernel_assessments_request_id", table_name="kernel_assessments")
    op.drop_table("kernel_assessments")
