"""Add slm_call_log, the SLM readback proxy's record of one hop.

One table, not the kernel's two. kernel_assessments exists to hold raw model output verbatim;
the SLM's output is generated clinical prose, and the design's rule is that no row about this hop
holds the prompt, the question or the generated text. With no body to store there is no second
table, and no way to log a call without its response row.

outcome is NULLABLE on purpose. It describes a CALL, and three rejections happen before a call
exists: the hop is not configured, the case does not resolve inside the caller's facility, or the
PHI guard trips. Those rows carry outcome NULL and an error_code. NULL passes the CHECK by SQL's
three-valued logic, so every non-NULL value is still held to the vocabulary. case_record_id and
case_token are nullable for the resolve-failure path, where there is no case to point a foreign
key at.

The CHECK lists SlmCallOutcome as of this revision, QUEUE_FULL included (added in the same change
as this table, from the service contract's section 4.2 mapping).

NO DATA MIGRATION: the table is new and nothing writes it before this revision.

Revision ID: 0008
Revises: 0007
Create Date: 2026-09-20
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_OUTCOMES = (
    "SUCCESS",
    "TIMEOUT",
    "UNREACHABLE",
    "ENGINE_ERROR",
    "PAYLOAD_REJECTED",
    "MALFORMED_RESPONSE",
    "CIRCUIT_OPEN",
    "PHI_REJECTED",
    "NOT_LOADED",
    "TRUNCATED",
    "QUEUE_FULL",
)


def upgrade() -> None:
    op.create_table(
        "slm_call_log",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("request_id", sa.String(length=36), nullable=False),
        sa.Column("case_record_id", sa.String(length=36), nullable=True),
        sa.Column("case_token", sa.String(length=36), nullable=True),
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("slm_base_url", sa.String(length=255), nullable=False),
        sa.Column("prompt_template_version", sa.String(length=40), nullable=False),
        sa.Column("model_id_requested", sa.String(length=80), nullable=False),
        sa.Column("prompt_chars", sa.Integer(), nullable=False),
        sa.Column("input_sha256", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=20), nullable=True),
        sa.Column("error_code", sa.String(length=20), nullable=True),
        sa.Column("http_status", sa.Integer(), nullable=True),
        sa.Column("generation_id", sa.String(length=64), nullable=True),
        sa.Column("model_id_served", sa.String(length=80), nullable=True),
        sa.Column("model_sha256", sa.String(length=64), nullable=True),
        sa.Column("finish_reason", sa.String(length=20), nullable=True),
        sa.Column("prompt_tokens", sa.Integer(), nullable=True),
        sa.Column("completion_tokens", sa.Integer(), nullable=True),
        sa.Column("total_tokens", sa.Integer(), nullable=True),
        sa.Column("output_sha256", sa.String(length=64), nullable=True),
        sa.Column(
            "started_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("duration_ms", sa.Integer(), nullable=True),
        sa.CheckConstraint(
            "outcome IN (" + ", ".join(f"'{value}'" for value in _OUTCOMES) + ")",
            name="ck_slm_call_log_outcome",
        ),
        sa.ForeignKeyConstraint(
            ["case_record_id"],
            ["case_records.id"],
            name="fk_slm_call_log_case_record_id_case_records",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["facility_id"],
            ["facilities.id"],
            name="fk_slm_call_log_facility_id_facilities",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_slm_call_log"),
    )
    op.create_index("ix_slm_call_log_request_id", "slm_call_log", ["request_id"])
    op.create_index("ix_slm_call_log_case_record_id", "slm_call_log", ["case_record_id"])
    op.create_index(
        "ix_slm_call_log_facility_started", "slm_call_log", ["facility_id", "started_at"]
    )


def downgrade() -> None:
    op.drop_index("ix_slm_call_log_facility_started", table_name="slm_call_log")
    op.drop_index("ix_slm_call_log_case_record_id", table_name="slm_call_log")
    op.drop_index("ix_slm_call_log_request_id", table_name="slm_call_log")
    op.drop_table("slm_call_log")
