"""SQLAlchemy declarative base and shared column conventions."""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import MetaData
from sqlalchemy.orm import DeclarativeBase

# Explicit constraint naming so Alembic autogenerate produces stable, reviewable migration names
# instead of database-assigned ones that differ between environments.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def utcnow() -> datetime:
    """Timezone-aware UTC now.

    Every timestamp in this service is UTC. The device clock in the field is not trustworthy and
    mixing offsets into a hash-chained audit log makes ordering arguments unwinnable later.
    """
    return datetime.now(UTC)


def to_iso(value: datetime) -> str:
    """Render as ISO 8601 UTC with an explicit Z, millisecond precision.

    Matches the wire format fixed in api-contract.md section 0.3, which the Android side parses
    with a fixed formatter.
    """
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
