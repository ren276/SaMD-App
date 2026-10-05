"""Model identity on kernel rows, and the derivation cross-check table.

kernel_reports (device-owned, D-9 and D-10)
  - model_version becomes NULLABLE. NULL means "the kernel did not say which model answered"; the
    device used to write the sentinels "remote-kernel" and "unavailable" there, which attributed a
    result to a model name nobody reported. Existing rows are NOT rewritten: pre-pilot data is
    declared non-clinical and reset before first deployment (D3), so there is no cleansing step.
  - model_calibrated, request_id, derivation_rule_version are new and nullable. request_id is the
    proxy's X-Request-ID, the key that joins a report to kernel_assessments. All three have
    format CHECKs; the columns are empty at this revision, so no CHECK can fail on existing rows.
    model_version has no CHECK on purpose: rows written before this revision are not guaranteed to
    match, and a migration must not fail on them. It is validated at sync ingest only.

kernel_call_log, kernel_assessments
  - model_sha256 (and model_calibrated on kernel_assessments), lifted from the classifier's
    model_metadata. Copied, not computed.

kernel_derivation_checks (new, server-owned, insert-only)
  - One row per accepted write of a kernel_reports record, recording whether re-deriving it from
    the stored model output agrees. kernel_reports itself is never written by the server.
  - UPDATE and DELETE are rejected by triggers, the same mechanism as audit_events (0001).
    The RESTRICT foreign keys plus the no-delete trigger make a checked kernel_reports row
    undeletable: erasure and retention purges need a designed path. The pre-pilot reset must
    TRUNCATE this table (TRUNCATE fires no row triggers) or use CASCADE.

DOWNGRADE refuses while any kernel_reports.model_version is NULL. Re-tightening NOT NULL would need
a value, and writing a placeholder would reintroduce the fabricated attribution this revision
removes. The downgrade also drops kernel_derivation_checks, which loses its history.

CONSTRAINT NAMES are given WITHOUT the ck_<table>_ prefix: the metadata naming convention adds it.
Earlier migrations pass the full name and so end up with the prefix twice, which truncates once the
name passes 63 characters. These names resolve to ck_<table>_<name> and stay under the limit.

Revision ID: 0010
Revises: 0009
Create Date: 2026-10-01
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0010"
down_revision: str | None = "0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Frozen literals, not imported from app.domain.kernel_identity: a migration records what was
# applied at its revision and must not change when the application constant does.
_REQUEST_ID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
_RULE_VERSION_PATTERN = "^HAN-07/08-v[0-9]{1,3}$"
_SHA256_PATTERN = "^[0-9a-f]{64}$"
_STATUSES = ("MATCH", "MISMATCH", "NOT_CHECKED_NO_LINK", "NOT_CHECKED_ERROR", "NOT_APPLICABLE")
_MISMATCH_FIELDS = ("urgency_level", "risk_category", "required_human_verification")


def upgrade() -> None:
    # --- kernel_reports
    op.alter_column(
        "kernel_reports", "model_version", existing_type=sa.String(length=80), nullable=True
    )
    op.add_column("kernel_reports", sa.Column("model_calibrated", sa.Boolean(), nullable=True))
    op.add_column("kernel_reports", sa.Column("request_id", sa.String(length=36), nullable=True))
    op.add_column(
        "kernel_reports",
        sa.Column("derivation_rule_version", sa.String(length=40), nullable=True),
    )
    op.create_check_constraint(
        "request_id_format",
        "kernel_reports",
        f"request_id IS NULL OR request_id ~ '{_REQUEST_ID_PATTERN}'",
    )
    op.create_check_constraint(
        "derivation_rule_version_format",
        "kernel_reports",
        f"derivation_rule_version IS NULL OR derivation_rule_version ~ '{_RULE_VERSION_PATTERN}'",
    )
    op.create_index("ix_kernel_reports_request_id", "kernel_reports", ["request_id"])

    # --- kernel_call_log and kernel_assessments
    op.add_column("kernel_call_log", sa.Column("model_sha256", sa.String(length=64), nullable=True))
    op.create_check_constraint(
        "model_sha256_format",
        "kernel_call_log",
        f"model_sha256 IS NULL OR model_sha256 ~ '{_SHA256_PATTERN}'",
    )
    op.add_column(
        "kernel_assessments", sa.Column("model_sha256", sa.String(length=64), nullable=True)
    )
    op.add_column("kernel_assessments", sa.Column("model_calibrated", sa.Boolean(), nullable=True))
    op.create_check_constraint(
        "model_sha256_format",
        "kernel_assessments",
        f"model_sha256 IS NULL OR model_sha256 ~ '{_SHA256_PATTERN}'",
    )

    # --- kernel_derivation_checks
    # The foreign keys are left unnamed on purpose: the metadata naming convention names them, as it
    # does for the model, and an explicit fk_<table>_<column>_<referred> for the assessment link is
    # 66 characters, over PostgreSQL's 63. The convention truncates deterministically.
    op.create_table(
        "kernel_derivation_checks",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("kernel_report_id", sa.String(length=36), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("request_id", sa.String(length=36), nullable=True),
        sa.Column("kernel_assessment_id", sa.String(length=36), nullable=True),
        sa.Column("rederive_status", sa.String(length=30), nullable=False),
        sa.Column("rule_version_used", sa.String(length=40), nullable=False),
        sa.Column("device_rule_version", sa.String(length=40), nullable=True),
        sa.Column("report_server_version", sa.Integer(), nullable=False),
        sa.Column(
            "mismatch_fields",
            sa.ARRAY(sa.Text()),
            server_default=sa.text("'{}'::text[]"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "rederive_status IN (" + ", ".join(f"'{value}'" for value in _STATUSES) + ")",
            name="rederive_status",
        ),
        sa.CheckConstraint(
            "mismatch_fields <@ ARRAY["
            + ", ".join(f"'{name}'" for name in _MISMATCH_FIELDS)
            + "]::text[]",
            name="mismatch_vocab",
        ),
        sa.CheckConstraint(
            "(rederive_status = 'MISMATCH') = (cardinality(mismatch_fields) > 0)",
            name="mismatch_consistent",
        ),
        sa.CheckConstraint(
            "rederive_status NOT IN ('MATCH', 'MISMATCH') OR kernel_assessment_id IS NOT NULL",
            name="link_consistent",
        ),
        sa.CheckConstraint(
            f"request_id IS NULL OR request_id ~ '{_REQUEST_ID_PATTERN}'",
            name="request_id_format",
        ),
        sa.ForeignKeyConstraint(
            ["kernel_report_id"],
            ["kernel_reports.id"],
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["facility_id"],
            ["facilities.id"],
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["kernel_assessment_id"],
            ["kernel_assessments.id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_kernel_derivation_checks"),
    )
    op.create_index(
        "ix_kernel_derivation_checks_report_created",
        "kernel_derivation_checks",
        ["kernel_report_id", "created_at", "id"],
    )
    op.create_index(
        "ix_kernel_derivation_checks_facility_status",
        "kernel_derivation_checks",
        ["facility_id", "rederive_status"],
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION kernel_derivation_checks_reject_mutation() RETURNS trigger AS $$
        BEGIN
            RAISE EXCEPTION 'kernel_derivation_checks is insert-only'
                USING ERRCODE = 'raise_exception';
        END;
        $$ LANGUAGE plpgsql;
        """
    )
    op.execute(
        "CREATE TRIGGER trg_kernel_derivation_checks_no_update "
        "BEFORE UPDATE ON kernel_derivation_checks "
        "FOR EACH ROW EXECUTE FUNCTION kernel_derivation_checks_reject_mutation()"
    )
    op.execute(
        "CREATE TRIGGER trg_kernel_derivation_checks_no_delete "
        "BEFORE DELETE ON kernel_derivation_checks "
        "FOR EACH ROW EXECUTE FUNCTION kernel_derivation_checks_reject_mutation()"
    )


def downgrade() -> None:
    # Refuse first, before anything is dropped: a refused downgrade must change nothing.
    null_versions = (
        op.get_bind()
        .execute(sa.text("SELECT count(*) FROM kernel_reports WHERE model_version IS NULL"))
        .scalar_one()
    )
    if null_versions:
        raise RuntimeError(
            f"0010 downgrade refused: {null_versions} kernel_reports rows have model_version NULL"
        )

    op.execute(
        "DROP TRIGGER IF EXISTS trg_kernel_derivation_checks_no_delete ON kernel_derivation_checks"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_kernel_derivation_checks_no_update ON kernel_derivation_checks"
    )
    op.execute("DROP FUNCTION IF EXISTS kernel_derivation_checks_reject_mutation()")
    op.drop_table("kernel_derivation_checks")

    op.drop_constraint("model_sha256_format", "kernel_assessments", type_="check")
    op.drop_column("kernel_assessments", "model_calibrated")
    op.drop_column("kernel_assessments", "model_sha256")
    op.drop_constraint("model_sha256_format", "kernel_call_log", type_="check")
    op.drop_column("kernel_call_log", "model_sha256")

    op.drop_index("ix_kernel_reports_request_id", table_name="kernel_reports")
    op.drop_constraint("derivation_rule_version_format", "kernel_reports", type_="check")
    op.drop_constraint("request_id_format", "kernel_reports", type_="check")
    op.drop_column("kernel_reports", "derivation_rule_version")
    op.drop_column("kernel_reports", "request_id")
    op.drop_column("kernel_reports", "model_calibrated")
    op.alter_column(
        "kernel_reports", "model_version", existing_type=sa.String(length=80), nullable=False
    )
