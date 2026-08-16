"""Accounts, devices, and refresh tokens: the real-authentication tables.

These replace MockAuthSession on the device, closing REQ-SEC-03 and mitigating hazard H-06.

Identity continuity is deliberate. user_accounts.worker_id is provisioned to the exact value the
device already derives in MockAuthSession.stableUserId:

    sha256(name.trim().lowercase() + "|" + role.name) hex encoded, first 16 characters

so historical device-side audit rows and new server-side rows for the same person share one
identifier. That stable derivation already shipped; this is what it was for.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    func,
    true,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base
from app.models.enums import UserRole

WORKER_ID_LENGTH = 16
_ROLES_SQL = ", ".join(f"'{role.value}'" for role in UserRole)


class UserAccount(Base):
    __tablename__ = "user_accounts"
    __table_args__ = (
        CheckConstraint(f"role IN ({_ROLES_SQL})", name="role_valid"),
        CheckConstraint(
            "worker_id ~ '^[0-9a-f]{16}$'",
            name="worker_id_format",
        ),
        Index("ix_user_accounts_facility_id", "facility_id"),
    )

    # 16 lowercase hex characters. Not a UUID: it must equal what the device derives.
    worker_id: Mapped[str] = mapped_column(String(WORKER_ID_LENGTH), primary_key=True)
    display_name: Mapped[str] = mapped_column(String(200), nullable=False)
    role: Mapped[str] = mapped_column(String(20), nullable=False)
    facility_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("facilities.id", ondelete="RESTRICT"), nullable=False
    )

    # bcrypt cost 12. Never plaintext, never logged, never echoed in a response.
    pin_hash: Mapped[str] = mapped_column(String(60), nullable=False)
    # D-3: the administrator hands out an initial PIN in person and the worker must change it on
    # first login. Enforced by a dependency that blocks every route except change-pin, me, and
    # logout while this is true.
    must_change_pin: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default=true()
    )

    is_active: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=True, server_default=true()
    )

    # Login throttling counters. Columns, not Redis: 5 failed attempts per worker per 15 minutes
    # does not justify a second datastore.
    failed_attempts: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default="0"
    )
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class Device(Base):
    """One row per app install. Binds a token to a device.

    device_id is generated once on first launch and persisted in the app's auth Preferences
    DataStore. It is deliberately not ANDROID_ID and not any hardware identifier, so it carries
    no cross-app tracking capability and is rotated by a reinstall.
    """

    __tablename__ = "devices"
    __table_args__ = (Index("ix_devices_worker_id", "worker_id"),)

    device_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    worker_id: Mapped[str] = mapped_column(
        String(WORKER_ID_LENGTH),
        ForeignKey("user_accounts.worker_id", ondelete="CASCADE"),
        primary_key=True,
    )
    app_version: Mapped[str | None] = mapped_column(String(32))
    # dev | staging | prod, from the Android product flavor. Present so a device pointed at the
    # wrong environment is visible in the admin surface rather than mysterious.
    environment: Mapped[str | None] = mapped_column(String(16))
    first_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class RefreshToken(Base):
    """Refresh chain with rotation and reuse detection.

    Rotation is mandatory: the presented token is revoked and replaced_by is set to the new
    token's jti. Presenting an already-revoked token revokes the entire chain for that
    (worker_id, device_id) pair. This is what makes a stolen refresh token survivable without a
    Redis blacklist.
    """

    __tablename__ = "refresh_tokens"
    __table_args__ = (
        Index("ix_refresh_tokens_worker_device", "worker_id", "device_id"),
        Index("ix_refresh_tokens_expires_at", "expires_at"),
    )

    jti: Mapped[str] = mapped_column(String(36), primary_key=True)
    worker_id: Mapped[str] = mapped_column(
        String(WORKER_ID_LENGTH),
        ForeignKey("user_accounts.worker_id", ondelete="CASCADE"),
        nullable=False,
    )
    device_id: Mapped[str] = mapped_column(String(64), nullable=False)
    issued_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Set on rotation. A revoked token with replaced_by set was rotated normally; a revoked token
    # presented again is reuse, and reuse revokes the chain.
    replaced_by: Mapped[str | None] = mapped_column(String(36))
    # Why the row was revoked: ROTATED, LOGOUT, REUSE_DETECTED, PIN_CHANGED.
    revoked_reason: Mapped[str | None] = mapped_column(String(32))
