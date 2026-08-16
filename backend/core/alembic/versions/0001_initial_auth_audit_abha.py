"""Initial schema: facilities, accounts, devices, refresh tokens, audit chain, ABHA transactions.

The 20 Room-mirrored clinical tables are Phase 2 and are deliberately not here.

abha_transactions IS here, in Phase 1, even though the ABDM adapter is Phase 5. Creating it now
means Phase 5 does not need a migration that has to be sequenced against whatever Phases 2, 3,
and 4 add.

Revision ID: 0001
Revises:
Create Date: 2026-08-16
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Third enforcement layer for the append-only audit log (models/audit.py lists all three).
# A trigger, not only a grant: grants do not restrain the table owner, and in dev the app
# connects as the owner. A trigger restrains everyone, which is what "append-only" has to mean
# for the control to be worth citing in a risk file (hazard H-07, REQ-AUD-02).
AUDIT_IMMUTABILITY_FN = """
CREATE OR REPLACE FUNCTION audit_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (SAMD-AUDIT-7002)'
        USING ERRCODE = 'raise_exception';
END;
$$ LANGUAGE plpgsql;
"""


def upgrade() -> None:
    # pgcrypto backs the encrypted patient identity columns added in Phase 2 and the encrypted
    # ABDM token column below. Created now so Phase 2 is a table migration, not an extension one.
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")

    op.create_table(
        "facilities",
        sa.Column("id", sa.String(length=32), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("district", sa.String(length=100), nullable=True),
        sa.Column("state", sa.String(length=100), nullable=True),
        sa.Column("hfr_id", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.PrimaryKeyConstraint("id", name="pk_facilities"),
    )

    op.create_table(
        "user_accounts",
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("display_name", sa.String(length=200), nullable=False),
        sa.Column("role", sa.String(length=20), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("pin_hash", sa.String(length=60), nullable=False),
        sa.Column("must_change_pin", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("failed_attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("locked_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_login_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint(
            "role IN ('ASHA_WORKER', 'NURSE', 'COMPOUNDER', 'DOCTOR')",
            name="ck_user_accounts_role_valid",
        ),
        sa.CheckConstraint(
            "worker_id ~ '^[0-9a-f]{16}$'", name="ck_user_accounts_worker_id_format"
        ),
        sa.ForeignKeyConstraint(
            ["facility_id"],
            ["facilities.id"],
            name="fk_user_accounts_facility_id_facilities",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("worker_id", name="pk_user_accounts"),
    )
    op.create_index("ix_user_accounts_facility_id", "user_accounts", ["facility_id"])

    op.create_table(
        "devices",
        sa.Column("device_id", sa.String(length=64), nullable=False),
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("app_version", sa.String(length=32), nullable=True),
        sa.Column("environment", sa.String(length=16), nullable=True),
        sa.Column(
            "first_seen_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "last_seen_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.ForeignKeyConstraint(
            ["worker_id"],
            ["user_accounts.worker_id"],
            name="fk_devices_worker_id_user_accounts",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("device_id", "worker_id", name="pk_devices"),
    )
    op.create_index("ix_devices_worker_id", "devices", ["worker_id"])

    op.create_table(
        "refresh_tokens",
        sa.Column("jti", sa.String(length=36), nullable=False),
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("device_id", sa.String(length=64), nullable=False),
        sa.Column(
            "issued_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("replaced_by", sa.String(length=36), nullable=True),
        sa.Column("revoked_reason", sa.String(length=32), nullable=True),
        sa.ForeignKeyConstraint(
            ["worker_id"],
            ["user_accounts.worker_id"],
            name="fk_refresh_tokens_worker_id_user_accounts",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("jti", name="pk_refresh_tokens"),
    )
    op.create_index("ix_refresh_tokens_worker_device", "refresh_tokens", ["worker_id", "device_id"])
    op.create_index("ix_refresh_tokens_expires_at", "refresh_tokens", ["expires_at"])

    op.create_table(
        "audit_events",
        sa.Column("id", sa.String(length=36), nullable=False),
        sa.Column("sequence", sa.BigInteger(), sa.Identity(always=False), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "recorded_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("origin", sa.String(length=8), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=False),
        sa.Column("actor_role", sa.String(length=20), nullable=True),
        sa.Column("device_id", sa.String(length=64), nullable=True),
        sa.Column("request_id", sa.String(length=36), nullable=True),
        sa.Column("action", sa.String(length=64), nullable=False),
        sa.Column("patient_id", sa.String(length=12), nullable=True),
        sa.Column("case_record_id", sa.String(length=64), nullable=True),
        sa.Column("payload", sa.Text(), nullable=False, server_default="{}"),
        sa.Column("payload_sha256", sa.String(length=64), nullable=False),
        sa.Column("previous_hash", sa.String(length=64), nullable=False),
        sa.Column("entry_hash", sa.String(length=64), nullable=False),
        sa.CheckConstraint("origin IN ('DEVICE', 'SERVER')", name="ck_audit_events_origin_valid"),
        sa.CheckConstraint("length(entry_hash) = 64", name="ck_audit_events_entry_hash_length"),
        sa.CheckConstraint(
            "length(previous_hash) = 64", name="ck_audit_events_previous_hash_length"
        ),
        sa.PrimaryKeyConstraint("id", name="pk_audit_events"),
        sa.UniqueConstraint("sequence", name="uq_audit_events_sequence"),
    )
    op.create_index(
        "ix_audit_events_facility_sequence", "audit_events", ["facility_id", "sequence"]
    )
    op.create_index("ix_audit_events_patient_id", "audit_events", ["patient_id"])
    op.create_index("ix_audit_events_actor_id", "audit_events", ["actor_id"])
    op.create_index("ix_audit_events_occurred_at", "audit_events", ["occurred_at"])
    op.create_index("ix_audit_events_request_id", "audit_events", ["request_id"])

    op.execute(AUDIT_IMMUTABILITY_FN)
    op.execute(
        """
        CREATE TRIGGER trg_audit_events_no_update
        BEFORE UPDATE ON audit_events
        FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_audit_events_no_delete
        BEFORE DELETE ON audit_events
        FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();
        """
    )

    op.create_table(
        "abha_transactions",
        sa.Column("local_transaction_id", sa.String(length=36), nullable=False),
        sa.Column("external_txn_id", sa.String(length=64), nullable=True),
        sa.Column("kind", sa.String(length=16), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("facility_id", sa.String(length=32), nullable=False),
        sa.Column("worker_id", sa.String(length=16), nullable=False),
        sa.Column("correlation_id", sa.String(length=36), nullable=False),
        sa.Column("external_token_encrypted", sa.LargeBinary(), nullable=True),
        sa.Column("external_token_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("abha_number", sa.String(length=14), nullable=True),
        sa.Column("abha_address", sa.String(length=255), nullable=True),
        sa.Column("abha_status", sa.String(length=20), nullable=True),
        sa.Column("abha_type", sa.String(length=20), nullable=True),
        sa.Column("linked_patient_id", sa.String(length=12), nullable=True),
        sa.Column("last_error_code", sa.String(length=32), nullable=True),
        sa.Column("last_error_detail", sa.Text(), nullable=True),
        sa.Column("retry_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("otp_request_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "state IN ('STARTED', 'IDENTITY_SUBMITTED', 'OTP_REQUESTED', 'OTP_VERIFIED', "
            "'ENROLLED', 'MOBILE_VERIFICATION_REQUIRED', 'MOBILE_VERIFIED', 'PROFILE_RETRIEVED', "
            "'COMPLETED', 'FAILED', 'EXPIRED')",
            name="ck_abha_transactions_state_valid",
        ),
        sa.CheckConstraint(
            "kind IN ('REGISTRATION', 'VERIFICATION')",
            name="ck_abha_transactions_kind_valid",
        ),
        sa.PrimaryKeyConstraint("local_transaction_id", name="pk_abha_transactions"),
    )
    op.create_index(
        "ix_abha_transactions_external_txn_id", "abha_transactions", ["external_txn_id"]
    )
    op.create_index("ix_abha_transactions_facility_id", "abha_transactions", ["facility_id"])
    op.create_index("ix_abha_transactions_expires_at", "abha_transactions", ["expires_at"])


def downgrade() -> None:
    op.drop_table("abha_transactions")
    op.execute("DROP TRIGGER IF EXISTS trg_audit_events_no_delete ON audit_events")
    op.execute("DROP TRIGGER IF EXISTS trg_audit_events_no_update ON audit_events")
    op.execute("DROP FUNCTION IF EXISTS audit_events_reject_mutation()")
    op.drop_table("audit_events")
    op.drop_table("refresh_tokens")
    op.drop_table("devices")
    op.drop_table("user_accounts")
    op.drop_table("facilities")
