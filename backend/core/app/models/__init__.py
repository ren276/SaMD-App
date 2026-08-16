"""SQLAlchemy models.

Every model must be imported here so Base.metadata is complete before Alembic autogenerate or a
test's create_all runs. A model that is defined but not imported is a table that silently does
not exist.
"""

from app.models.abha import AbhaTransaction
from app.models.audit import GENESIS_HASH, AuditEvent
from app.models.enums import (
    AbhaTransactionKind,
    AbhaTransactionState,
    AuditAction,
    AuditOrigin,
    UserRole,
)
from app.models.facility import Facility
from app.models.user import WORKER_ID_LENGTH, Device, RefreshToken, UserAccount

__all__ = [
    "GENESIS_HASH",
    "WORKER_ID_LENGTH",
    "AbhaTransaction",
    "AbhaTransactionKind",
    "AbhaTransactionState",
    "AuditAction",
    "AuditEvent",
    "AuditOrigin",
    "Device",
    "Facility",
    "RefreshToken",
    "UserAccount",
    "UserRole",
]
