"""Append-only, hash-chained audit log.

There is no update method and no delete method in this module, and there must never be one.
That absence is the first of the three enforcement layers described in app/models/audit.py.

Chain rule, stated exactly so an implementation cannot drift (api-contract.md section 7):

    entry_hash = SHA256(
        previous_hash            (64 hex chars, or 64 zeros for the genesis entry)
      + "|" + id                 (server-assigned UUID4)
      + "|" + occurred_at        (ISO 8601 UTC, millisecond precision)
      + "|" + facility_id
      + "|" + actor_id
      + "|" + action
      + "|" + (patient_id or "")
      + "|" + (case_record_id or "")
      + "|" + SHA256(payload)    (64 hex chars, hash of the payload string)
    )

The payload is hashed rather than concatenated so chain verification never has to load the
clinical blobs.
"""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import to_iso, utcnow
from app.models.audit import GENESIS_HASH, AuditEvent
from app.models.enums import AuditOrigin

# A single verify call will not walk more than this many rows. Beyond it the caller pages.
MAX_VERIFY_ENTRIES = 100_000


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def compute_entry_hash(
    *,
    previous_hash: str,
    entry_id: str,
    occurred_at_iso: str,
    facility_id: str,
    actor_id: str,
    action: str,
    patient_id: str | None,
    case_record_id: str | None,
    payload_sha256: str,
) -> str:
    material = "|".join(
        [
            previous_hash,
            entry_id,
            occurred_at_iso,
            facility_id,
            actor_id,
            action,
            patient_id or "",
            case_record_id or "",
            payload_sha256,
        ]
    )
    return sha256_hex(material)


def _facility_lock_key(facility_id: str) -> int:
    """Stable signed 64-bit key for a PostgreSQL transaction-scoped advisory lock."""
    digest = hashlib.sha256(facility_id.encode("utf-8")).digest()[:8]
    return int.from_bytes(digest, "big", signed=True)


@dataclass(frozen=True)
class VerifyResult:
    verified: bool
    from_sequence: int
    to_sequence: int
    entries_checked: int
    first_broken_sequence: int | None
    computed_head_hash: str | None


async def append(
    session: AsyncSession,
    *,
    action: str,
    facility_id: str,
    actor_id: str,
    origin: AuditOrigin = AuditOrigin.SERVER,
    actor_role: str | None = None,
    device_id: str | None = None,
    request_id: str | None = None,
    patient_id: str | None = None,
    case_record_id: str | None = None,
    payload: str = "{}",
    occurred_at: datetime | None = None,
) -> AuditEvent:
    """Append one entry to the facility's chain.

    The advisory lock serialises appends per facility so two concurrent requests cannot compute
    off the same previous_hash and fork the chain. It is transaction scoped, so it releases when
    the request's transaction commits or rolls back, with no unlock path to forget.

    ponytail: one lock per facility. If a single PHC ever writes fast enough for this to be the
    bottleneck, the upgrade is a per-facility chain-head row with a monotonic counter, not a
    finer lock.
    """
    await session.execute(
        text("SELECT pg_advisory_xact_lock(:key)"), {"key": _facility_lock_key(facility_id)}
    )

    head = (
        await session.execute(
            select(AuditEvent.entry_hash)
            .where(AuditEvent.facility_id == facility_id)
            .order_by(AuditEvent.sequence.desc())
            .limit(1)
        )
    ).scalar_one_or_none()

    previous_hash = head or GENESIS_HASH
    entry_id = str(uuid.uuid4())
    when = occurred_at or utcnow()
    occurred_at_iso = to_iso(when)
    payload_hash = sha256_hex(payload)

    event = AuditEvent(
        id=entry_id,
        occurred_at=when,
        recorded_at=utcnow(),
        origin=origin.value,
        facility_id=facility_id,
        actor_id=actor_id,
        actor_role=actor_role,
        device_id=device_id,
        request_id=request_id,
        action=action,
        patient_id=patient_id,
        case_record_id=case_record_id,
        payload=payload,
        payload_sha256=payload_hash,
        previous_hash=previous_hash,
        entry_hash=compute_entry_hash(
            previous_hash=previous_hash,
            entry_id=entry_id,
            occurred_at_iso=occurred_at_iso,
            facility_id=facility_id,
            actor_id=actor_id,
            action=action,
            patient_id=patient_id,
            case_record_id=case_record_id,
            payload_sha256=payload_hash,
        ),
    )
    session.add(event)
    # Flush, not commit. The caller's request-scoped transaction owns the commit, so an audit row
    # never survives a handler that failed after writing it.
    await session.flush()
    return event


async def verify_chain(
    session: AsyncSession,
    *,
    facility_id: str,
    from_sequence: int = 1,
    to_sequence: int | None = None,
) -> VerifyResult:
    """Recompute the chain over a range and report whether it is intact.

    A broken chain is a result, not a transport error. The caller returns 200 with
    verified=false, so a naive monitor cannot treat "the audit log was altered" as a flake.
    """
    stmt = (
        select(AuditEvent)
        .where(AuditEvent.facility_id == facility_id, AuditEvent.sequence >= from_sequence)
        .order_by(AuditEvent.sequence.asc())
    )
    if to_sequence is not None:
        stmt = stmt.where(AuditEvent.sequence <= to_sequence)
    # populate_existing overwrites anything already in the session's identity map with the values
    # actually on disk. A verifier that can be satisfied by its own cached objects verifies
    # nothing, and the caller may well have written some of these rows earlier in the same
    # session.
    stmt = stmt.limit(MAX_VERIFY_ENTRIES).execution_options(populate_existing=True)

    rows = list((await session.execute(stmt)).scalars())
    if not rows:
        return VerifyResult(
            verified=True,
            from_sequence=from_sequence,
            to_sequence=to_sequence or from_sequence,
            entries_checked=0,
            first_broken_sequence=None,
            computed_head_hash=None,
        )

    # Anchor on the stored previous_hash of the first row in the window. Verifying a slice checks
    # internal consistency of that slice; verifying from sequence 1 checks the whole chain back
    # to genesis.
    expected_previous = rows[0].previous_hash
    first_broken: int | None = None

    for row in rows:
        recomputed = compute_entry_hash(
            previous_hash=expected_previous,
            entry_id=row.id,
            occurred_at_iso=to_iso(row.occurred_at),
            facility_id=row.facility_id,
            actor_id=row.actor_id,
            action=row.action,
            patient_id=row.patient_id,
            case_record_id=row.case_record_id,
            payload_sha256=sha256_hex(row.payload),
        )
        if (
            row.previous_hash != expected_previous
            or row.entry_hash != recomputed
            or row.payload_sha256 != sha256_hex(row.payload)
        ):
            first_broken = row.sequence
            break
        expected_previous = row.entry_hash

    return VerifyResult(
        verified=first_broken is None,
        from_sequence=rows[0].sequence,
        to_sequence=rows[-1].sequence,
        entries_checked=len(rows),
        first_broken_sequence=first_broken,
        computed_head_hash=expected_previous if first_broken is None else None,
    )
