"""POST /api/v1/sync/push apply logic. api-contract.md section 6.1.

Ordering the outer caller (app/api/v1/sync.py) must honour, because it is what makes replay safe:
idempotency lookup first, touching nothing, then and only then the whole-batch preconditions,
then per-record apply. See push() below, which performs all three in that order inside the
caller's request transaction.

TABLE_REGISTRY is schema-driven rather than nineteen hand-written per-table validators. Each
table's client-writable columns are read off the SQLAlchemy mapper at import time, so a required
field simply comes from the column's own NOT NULL, and a bad enum value comes from the column's
own CHECK constraint: both surface as IntegrityError under the record's savepoint and become a
generic SAMD-SYNC-6003, rather than being re-declared here. What genuinely cannot come from the
schema is written out explicitly: the one renamed field (attachments.uri), the one forbidden
field (ailments.audio_local_uri), the two server-stamped timestamps (observations and ailments
synced_to_cloud_at), and the one cross-check the database cannot express
(referrals.sending_phc_id against the caller's own facility_id).

audit_log is not in TABLE_REGISTRY. It does not go through the generic upsert path at all: it
reuses app.services.audit.append, the one chain appender, rather than a second implementation of
the chain rule (see the module's own docstring for why that split must never happen).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

from sqlalchemy import Date, DateTime, select
from sqlalchemy import inspect as sa_inspect
from sqlalchemy.exc import DataError, IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utcnow
from app.deps import CurrentWorker
from app.domain.audit_actions_device import DEVICE_AUDIT_ACTIONS
from app.errors import ErrorCode, SamdError
from app.models.abha import AbhaProfile
from app.models.attachment import Attachment
from app.models.clinical import Ailment, CaseRecord, Observation
from app.models.encounter import Consultation, Encounter
from app.models.enums import AuditAction, AuditOrigin
from app.models.history import (
    Allergy,
    FamilyHistoryEntry,
    MedicalHistoryItem,
    MedicationEntry,
    SocialHistory,
)
from app.models.kernel import DiagnosisFeedback, EvaluateReport, KernelReport
from app.models.patient import Patient
from app.models.prescription import MedicationLine, Prescription
from app.models.referral import Referral
from app.models.sync import SyncBatch, SyncLogEntry
from app.schemas.common import envelope
from app.schemas.sync import MAX_RECORDS, SyncPushEnvelope
from app.services import audit as audit_service
from app.services.patient import apply_blind_indexes

# Columns SyncMixin adds that a client may never set directly; always excluded from every table's
# client-writable set regardless of what else a table's TableSpec declares.
_SYNC_MIXIN_OWNED = frozenset({"facility_id", "server_version", "received_at", "sync_state"})

AUDIT_LOG_TABLE = "audit_log"
AUDIT_LOG_RANK = 20
_AUDIT_LOG_ALLOWED_KEYS = frozenset(
    {"timestamp", "user_id", "patient_id", "case_record_id", "action", "payload"}
)

# backend-prd.md section 6.2: actions the server adds for events with no device equivalent. A
# device-origin audit_log row carrying one of these is rejected, not silently accepted, so a
# device can never inject a server-attributed event through sync (REQ-AUD-02's sibling rule for
# the action field specifically). This set and _DEVICE_AUDIT_ACTION_VALUES below are kept as two
# independently defined sets, not one computed as the complement of the other: the accepted
# device set now has its own source of truth (the Android enum mirror), and deriving one set from
# the other would make it possible for a future addition to either side to silently change the
# other's membership.
_SERVER_ONLY_AUDIT_ACTIONS = frozenset(
    {
        AuditAction.WORKER_LOGIN_SUCCEEDED,
        AuditAction.WORKER_LOGIN_FAILED,
        AuditAction.WORKER_LOGOUT,
        AuditAction.TOKEN_REFRESHED,
        AuditAction.REFRESH_REUSE_DETECTED,
        AuditAction.PATIENT_RECORD_READ,
        AuditAction.AUDIT_LOG_READ,
        AuditAction.KERNEL_CALL_FORWARDED,
        AuditAction.KERNEL_CALL_FAILED,
        AuditAction.SYNC_BATCH_RECEIVED,
        AuditAction.SYNC_RECORD_REJECTED,
        AuditAction.ABHA_SESSION_STARTED,
        AuditAction.ABHA_SESSION_FAILED,
        AuditAction.ABHA_IDENTITY_LINKED,
        AuditAction.ABHA_IDENTITY_SUBMITTED,
        AuditAction.ABHA_OTP_VERIFIED,
        AuditAction.ABHA_ENROLLED,
        AuditAction.ABHA_MOBILE_VERIFIED,
        AuditAction.ABHA_PROFILE_RETRIEVED,
    }
)

# The accepted set for device-origin audit_log rows. Sourced from the checked-in mirror of
# Android's AuditLogger.kt AuditAction enum (app/domain/audit_actions_device.py), not retyped:
# this constant existing as a hand-typed 7-value guess, correct at Phase 1 and never updated as
# the device vocabulary grew, is exactly how 27 of 28 real device actions ended up rejected by
# Phase 4 (found and fixed 2026-08-17). tests/test_sync.py asserts this equals the mirror file
# directly, and where reachable also that the mirror equals the Android source, so this cannot
# rot silently a second time.
_DEVICE_AUDIT_ACTION_VALUES = DEVICE_AUDIT_ACTIONS

_overlap = _DEVICE_AUDIT_ACTION_VALUES & {a.value for a in _SERVER_ONLY_AUDIT_ACTIONS}
if _overlap:
    raise RuntimeError(
        f"device and server-only audit action sets overlap: {sorted(_overlap)}; REQ-AUD-02 "
        "requires these two sets to stay disjoint so a device can never inject a "
        "server-attributed event through sync"
    )

# sqlstate -> a message that names the failure class without echoing the driver's own text, which
# often embeds the offending value (errors.py rule: "detail never contains PHI").
_SQLSTATE_MESSAGES = {
    "23502": "a required field is missing.",
    "23503": "a referenced record does not exist yet.",
    "23505": "this record already exists with different data.",
    "23514": "a field value is not valid.",
}


@dataclass(frozen=True)
class TableSpec:
    rank: int
    model: type[Any]
    pk_attr: str = "id"
    # data key -> model attribute, for the columns whose wire name differs from the column name.
    aliases: dict[str, str] = field(default_factory=dict)
    # Extra attributes to exclude from the client-writable set, beyond _SYNC_MIXIN_OWNED and the
    # primary key. Server-stamped columns like observations/ailments.synced_to_cloud_at live here.
    server_owned: frozenset[str] = frozenset()
    # data key -> error code. Checked before the generic unknown-field rejection, so the specific
    # code in api-contract.md's table (SAMD-SYNC-6006) is not swallowed by the generic 6003.
    forbidden: dict[str, ErrorCode] = field(default_factory=dict)


_PATIENT_BLIND_INDEXES = frozenset({"name_blind_idx", "mobile_blind_idx", "aadhaar_blind_idx"})

TABLE_REGISTRY: dict[str, TableSpec] = {
    # Blind indexes are computed server side from the plaintext by apply_blind_indexes(), never
    # accepted from the wire: a client-supplied value would be meaningless without the server's
    # own HMAC key, and PatientEntity has no such properties to send in the first place.
    "patients": TableSpec(1, Patient, server_owned=_PATIENT_BLIND_INDEXES),
    "encounters": TableSpec(2, Encounter),
    "consultations": TableSpec(3, Consultation),
    "attachments": TableSpec(4, Attachment, aliases={"uri": "local_uri"}),
    "observations": TableSpec(5, Observation, server_owned=frozenset({"synced_to_cloud_at"})),
    "ailments": TableSpec(
        6,
        Ailment,
        server_owned=frozenset({"synced_to_cloud_at"}),
        forbidden={"audio_local_uri": ErrorCode.SYNC_FORBIDDEN_FIELD},
    ),
    "medical_history_items": TableSpec(7, MedicalHistoryItem),
    "allergies": TableSpec(8, Allergy),
    "family_history_entries": TableSpec(9, FamilyHistoryEntry),
    "social_histories": TableSpec(10, SocialHistory, pk_attr="patient_id"),
    "medication_entries": TableSpec(11, MedicationEntry),
    "case_records": TableSpec(12, CaseRecord),
    "kernel_reports": TableSpec(13, KernelReport),
    "evaluate_reports": TableSpec(14, EvaluateReport),
    "diagnosis_feedback": TableSpec(15, DiagnosisFeedback),
    "prescriptions": TableSpec(16, Prescription),
    "medication_lines": TableSpec(17, MedicationLine),
    "referrals": TableSpec(18, Referral),
    "abha_profiles": TableSpec(
        19, AbhaProfile, pk_attr="abha_id", server_owned=frozenset({"mobile_blind_idx"})
    ),
}

ALL_TABLE_RANKS: dict[str, int] = {name: spec.rank for name, spec in TABLE_REGISTRY.items()} | {
    AUDIT_LOG_TABLE: AUDIT_LOG_RANK
}


def _attr_map(spec: TableSpec) -> dict[str, str]:
    """data key -> model attribute, for every column a client may write on this table."""
    mapper = sa_inspect(spec.model)
    excluded = {spec.pk_attr} | _SYNC_MIXIN_OWNED | spec.server_owned
    attrs = {c.key for c in mapper.column_attrs} - excluded
    alias_by_attr = {attr: key for key, attr in spec.aliases.items()}
    return {alias_by_attr.get(attr, attr): attr for attr in attrs}


def _timestamp_attr(spec: TableSpec) -> str:
    """The column compared against client_updated_at for last-write-wins.

    api-contract.md section 6.1 says "stored updated_at" without naming a column per table. Most
    of the twenty tables have one; the rest (history-style child rows: allergies, observations,
    medication entries, and so on) have only created_at, because they are written once per visit
    and never mutated in place. updated_at is used where the column exists, created_at otherwise.
    This is an implementation choice the contract left implicit, not a divergence from it; see the
    Phase 4 report.
    """
    return "updated_at" if hasattr(spec.model, "updated_at") else "created_at"


def _constraint_message(exc: IntegrityError | DataError) -> str:
    sqlstate = getattr(exc.orig, "sqlstate", None)
    if not isinstance(sqlstate, str):
        return "the record could not be applied."
    return _SQLSTATE_MESSAGES.get(sqlstate, "the record could not be applied.")


def _parse_datetime(value: Any) -> datetime:
    if not isinstance(value, str) or not value:
        raise TypeError("not a string")
    normalised = value[:-1] + "+00:00" if value.endswith("Z") else value
    parsed = datetime.fromisoformat(normalised)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def _coerce_value(column_type: Any, value: Any) -> Any:
    """JSON gives back str for every timestamp; asyncpg accepts only date/datetime objects for
    Date/DateTime columns and will not coerce a string itself. Every other column type here
    (String, Text, Integer, Float, Boolean, ARRAY(Text), JSONB) already matches its JSON
    counterpart, so nothing else needs converting.
    """
    if value is None or not isinstance(value, str):
        return value
    if isinstance(column_type, DateTime):
        return _parse_datetime(value)
    if isinstance(column_type, Date):
        return date.fromisoformat(value)
    return value


def _reject(table: str, record_id: str, code: ErrorCode, message: str) -> dict[str, Any]:
    return {
        "table": table,
        "id": record_id,
        "status": "rejected",
        "code": code.value,
        "message": message,
    }


def _server_state(spec: TableSpec, row: Any) -> dict[str, Any]:
    return {key: getattr(row, attr) for key, attr in _attr_map(spec).items()}


async def _apply_generic(
    session: AsyncSession,
    worker: CurrentWorker,
    spec: TableSpec,
    *,
    table: str,
    record_id: str,
    data: dict[str, Any],
    base_version: int | None,
    client_updated_at: datetime,
) -> dict[str, Any]:
    for key, code in spec.forbidden.items():
        if key in data:
            return _reject(table, record_id, code, f"{key}: forbidden field.")

    if table == "referrals":
        sending = data.get("sending_phc_id")
        if sending is not None and sending != worker.facility_id:
            return _reject(
                table,
                record_id,
                ErrorCode.SYNC_RECORD_INVALID,
                "sending_phc_id: does not match this facility.",
            )

    attr_map = _attr_map(spec)
    unknown = set(data) - set(attr_map)
    if unknown:
        bad = sorted(unknown)[0]
        return _reject(table, record_id, ErrorCode.SYNC_RECORD_INVALID, f"{bad}: unexpected field.")

    model = spec.model
    pk_column = getattr(model, spec.pk_attr)
    existing = (
        await session.execute(select(model).where(pk_column == record_id))
    ).scalar_one_or_none()

    if existing is not None and existing.facility_id != worker.facility_id:
        return _reject(
            table, record_id, ErrorCode.SYNC_RECORD_INVALID, "id: belongs to another facility."
        )

    if existing is not None:
        if base_version is not None and base_version != existing.server_version:
            return {
                "table": table,
                "id": record_id,
                "status": "conflict",
                "server_state": _server_state(spec, existing),
            }
        stored_ts = getattr(existing, _timestamp_attr(spec))
        if client_updated_at <= stored_ts:
            return {
                "table": table,
                "id": record_id,
                "status": "stale",
                "server_version": existing.server_version,
            }

    columns = sa_inspect(model).columns
    attrs = {
        attr_map[key]: _coerce_value(columns[attr_map[key]].type, value)
        for key, value in data.items()
    }
    if "synced_to_cloud_at" in spec.server_owned:
        attrs["synced_to_cloud_at"] = utcnow()
    if table == "evaluate_reports" and isinstance(attrs.get("payload_json"), str):
        try:
            attrs["payload_json"] = json.loads(attrs["payload_json"])
        except json.JSONDecodeError:
            return _reject(
                table, record_id, ErrorCode.SYNC_RECORD_INVALID, "payload_json: invalid JSON."
            )

    if existing is not None:
        for attr, value in attrs.items():
            setattr(existing, attr, value)
        existing.server_version += 1
        row = existing
    else:
        row = model(
            **{spec.pk_attr: record_id}, **attrs, facility_id=worker.facility_id, server_version=1
        )
        session.add(row)

    if table == "patients":
        # Blind indexes are not columns SQLAlchemy derives on its own; every write path that
        # touches full_name/mobile_number/aadhaar_number must recompute them or the row silently
        # stops being findable by exact match. Reuses the same helper patients.py's own POST and
        # PATCH handlers call, so there is one place this rule lives, not two.
        apply_blind_indexes(row)

    await session.flush()
    return {
        "table": table,
        "id": record_id,
        "status": "applied",
        "server_version": row.server_version,
    }


async def _apply_audit_log(
    session: AsyncSession,
    worker: CurrentWorker,
    *,
    record_id: str,
    data: dict[str, Any],
    request_id: str,
    batch_id: str,
) -> dict[str, Any]:
    table = AUDIT_LOG_TABLE

    # Fast path only (REQ-AUD-02). The correctness guarantee is the partial unique index
    # uq_sync_log_audit_log_record_id on sync_log(table_name, record_id) WHERE table_name =
    # 'audit_log' (app/models/sync.py, alembic/versions/0005), enforced below at INSERT time.
    # This SELECT only avoids that INSERT's round trip in the common, non-racing case: two
    # concurrent batches carrying the same record_id can both reach here and both see "not
    # found" before either commits.
    already = (
        await session.execute(
            select(SyncLogEntry.id).where(
                SyncLogEntry.table_name == table, SyncLogEntry.record_id == record_id
            )
        )
    ).scalar_one_or_none()
    if already is not None:
        return {"table": table, "id": record_id, "status": "duplicate"}

    unknown = set(data) - _AUDIT_LOG_ALLOWED_KEYS
    if unknown:
        bad = sorted(unknown)[0]
        return _reject(table, record_id, ErrorCode.SYNC_RECORD_INVALID, f"{bad}: unexpected field.")

    action = data.get("action")
    if action not in _DEVICE_AUDIT_ACTION_VALUES:
        return _reject(
            table, record_id, ErrorCode.SYNC_RECORD_INVALID, "action: unknown audit action."
        )

    try:
        occurred_at = _parse_datetime(data.get("timestamp"))
    except (TypeError, ValueError):
        return _reject(
            table, record_id, ErrorCode.SYNC_RECORD_INVALID, "timestamp: invalid timestamp."
        )

    user_id = data.get("user_id")
    if not isinstance(user_id, str) or not user_id:
        return _reject(table, record_id, ErrorCode.SYNC_RECORD_INVALID, "user_id: required.")

    payload = data.get("payload", "{}")
    if not isinstance(payload, str):
        return _reject(
            table, record_id, ErrorCode.SYNC_RECORD_INVALID, "payload: must be a string."
        )

    for key in ("patient_id", "case_record_id"):
        if key in data and data[key] is not None and not isinstance(data[key], str):
            return _reject(
                table, record_id, ErrorCode.SYNC_RECORD_INVALID, f"{key}: must be a string."
            )

    # Reserve the dedup slot before appending to the chain, not after. If this INSERT loses the
    # race against a concurrent batch that reserved the same record_id first (the partial unique
    # index raises IntegrityError), we must not have called audit_service.append yet: otherwise
    # both sides of the race would each append their own row before either found out it lost,
    # and the chain would grow by two for one logical device event instead of one. This insert
    # doubles as this record's own sync_log row; the caller (push()) must not add a second one.
    try:
        async with session.begin_nested():
            session.add(
                SyncLogEntry(
                    batch_id=batch_id, table_name=table, record_id=record_id, status="applied"
                )
            )
            await session.flush()
    except IntegrityError:
        return {"table": table, "id": record_id, "status": "duplicate"}

    await audit_service.append(
        session,
        action=action,
        facility_id=worker.facility_id,
        actor_id=user_id,
        origin=AuditOrigin.DEVICE,
        device_id=worker.device_id,
        request_id=request_id,
        patient_id=data.get("patient_id"),
        case_record_id=data.get("case_record_id"),
        payload=payload,
        occurred_at=occurred_at,
    )
    return {"table": table, "id": record_id, "status": "applied"}


async def _apply_one(
    session: AsyncSession,
    worker: CurrentWorker,
    raw: dict[str, Any],
    *,
    request_id: str,
    batch_id: str,
) -> dict[str, Any]:
    table = raw.get("table")
    record_id = raw.get("id")
    op = raw.get("op")
    data = raw.get("data")
    base_version = raw.get("base_version")

    # push() has already checked every record's table against ALL_TABLE_RANKS (whole-batch 422
    # otherwise), so table is a known str by the time any record reaches here.
    if not isinstance(table, str):
        raise TypeError("_apply_one called with an unvalidated table; push() must check first")
    safe_id = record_id if isinstance(record_id, str) and record_id else ""

    if not isinstance(record_id, str) or not record_id:
        return _reject(table, safe_id, ErrorCode.SYNC_RECORD_INVALID, "id: required.")
    if op not in ("upsert", "insert"):
        return _reject(table, safe_id, ErrorCode.SYNC_RECORD_INVALID, "op: unsupported operation.")
    if not isinstance(data, dict):
        return _reject(table, safe_id, ErrorCode.SYNC_RECORD_INVALID, "data: required.")
    if base_version is not None and not isinstance(base_version, int):
        return _reject(
            table, safe_id, ErrorCode.SYNC_RECORD_INVALID, "base_version: must be an integer."
        )
    try:
        client_updated_at = _parse_datetime(raw.get("client_updated_at"))
    except (TypeError, ValueError):
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "client_updated_at: invalid timestamp.",
        )

    try:
        async with session.begin_nested():
            if table == AUDIT_LOG_TABLE:
                result = await _apply_audit_log(
                    session,
                    worker,
                    record_id=record_id,
                    data=data,
                    request_id=request_id,
                    batch_id=batch_id,
                )
            else:
                spec = TABLE_REGISTRY[table]
                result = await _apply_generic(
                    session,
                    worker,
                    spec,
                    table=table,
                    record_id=record_id,
                    data=data,
                    base_version=base_version,
                    client_updated_at=client_updated_at,
                )
    except (IntegrityError, DataError) as exc:
        return _reject(table, safe_id, ErrorCode.SYNC_RECORD_INVALID, _constraint_message(exc))

    return result


async def push(
    session: AsyncSession,
    worker: CurrentWorker,
    envelope_body: SyncPushEnvelope,
    *,
    request_id: str,
    timestamp: str,
) -> tuple[dict[str, Any], bool]:
    """Apply one sync batch. Returns (the full success envelope, replayed).

    Ordering is the load-bearing part of this function, not an implementation detail:

    1. Idempotency lookup by batch_id, before anything else is touched. A hit returns the stored
       envelope verbatim and stops (api-contract.md section 6.1: "returns the original stored
       response verbatim without re-applying anything").
    2. Only for a genuinely new batch_id: the whole-batch preconditions (device_id match, size,
       unknown table anywhere in the batch).
    3. Per-record apply, sorted by the fixed table rank, each under its own savepoint so one bad
       record cannot poison the rest of the batch or the sync_batches row itself.

    Everything here runs on the caller's request-scoped session (app.db.session.session_scope):
    the sync_batches row, every sync_log row, every applied clinical row, and the audit_events
    rows (both the one device-origin row per audit_log record and the one server-origin
    sync_batch_received summary row) all commit together or all roll back together. Nothing in
    this function uses app.db.session.write_out_of_band. Unlike a kernel call, applying a sync
    batch is not an event that already happened out in the world before this function was
    called; it is pure database work, so there is no failure-path trap to guard against and no
    reason to split it into a second transaction. See the Phase 4 report for the explicit
    statement this brief asked for.
    """
    existing_batch = (
        await session.execute(select(SyncBatch).where(SyncBatch.batch_id == envelope_body.batch_id))
    ).scalar_one_or_none()
    if existing_batch is not None:
        # response_json is only nullable because the row briefly exists without it mid-apply
        # (see the flush right after SyncBatch is constructed below); a row found by a completed
        # transaction's batch_id always has it set, since both are written before that
        # transaction's own commit.
        if existing_batch.response_json is None:
            raise RuntimeError(f"sync_batches {envelope_body.batch_id!r} has no response_json")
        return existing_batch.response_json, True

    if envelope_body.device_id != worker.device_id:
        raise SamdError(
            ErrorCode.SYNC_DEVICE_MISMATCH,
            detail="This device is not bound to the authenticated session.",
        )

    records = envelope_body.records
    if len(records) > MAX_RECORDS:
        raise SamdError(ErrorCode.SYNC_BATCH_TOO_LARGE, detail="Too many records in one batch.")

    for raw in records:
        if raw.get("table") not in ALL_TABLE_RANKS:
            raise SamdError(
                ErrorCode.SYNC_UNKNOWN_TABLE, detail=f"Unknown table: {raw.get('table')!r}."
            )

    ordered = sorted(records, key=lambda r: ALL_TABLE_RANKS[r["table"]])

    batch_row = SyncBatch(
        batch_id=envelope_body.batch_id,
        device_id=envelope_body.device_id,
        worker_id=worker.worker_id,
        facility_id=worker.facility_id,
        record_count=len(ordered),
    )
    session.add(batch_row)
    # sync_log.batch_id is a real foreign key to sync_batches.batch_id (not deferrable), so the
    # parent row must exist before the first per-record sync_log insert below.
    await session.flush()

    results: list[dict[str, Any]] = []
    counts = {"applied": 0, "stale": 0, "conflicted": 0, "rejected": 0}
    status_to_count = {
        "applied": "applied",
        "stale": "stale",
        "conflict": "conflicted",
        "rejected": "rejected",
    }

    for raw in ordered:
        result = await _apply_one(
            session, worker, raw, request_id=request_id, batch_id=batch_row.batch_id
        )
        results.append(result)
        bucket = status_to_count.get(result["status"])
        if bucket is not None:
            counts[bucket] += 1

        # _apply_audit_log already wrote its own sync_log row, inside the same savepoint as the
        # audit_events append it gates (see its docstring comment): that INSERT, not this one, is
        # what the partial unique index on sync_log(table_name, record_id) WHERE table_name =
        # 'audit_log' actually closes the race against. A "duplicate" result never gets a row
        # here either, whichever of the two paths inside _apply_audit_log produced it (the fast
        # SELECT or the race-losing INSERT): a row for this record_id already exists, so a second
        # one here would just fail the same unique index a moment later, for no reason, this
        # record's outcome is already durable.
        if not (
            result["table"] == AUDIT_LOG_TABLE and result["status"] in ("applied", "duplicate")
        ):
            session.add(
                SyncLogEntry(
                    batch_id=batch_row.batch_id,
                    table_name=result["table"],
                    record_id=result["id"],
                    status=result["status"],
                    code=result.get("code"),
                    message=result.get("message"),
                    server_version=result.get("server_version"),
                )
            )

    batch_row.applied = counts["applied"]
    batch_row.stale = counts["stale"]
    batch_row.conflicted = counts["conflicted"]
    batch_row.rejected = counts["rejected"]

    await audit_service.append(
        session,
        action=AuditAction.SYNC_BATCH_RECEIVED.value,
        facility_id=worker.facility_id,
        actor_id=worker.worker_id,
        actor_role=worker.role,
        device_id=worker.device_id,
        request_id=request_id,
        origin=AuditOrigin.SERVER,
        payload=json.dumps(
            {"batch_id": envelope_body.batch_id, "received": len(ordered), **counts},
            separators=(",", ":"),
            sort_keys=True,
        ),
    )

    data = {
        "batch_id": envelope_body.batch_id,
        "received": len(ordered),
        "applied": counts["applied"],
        "stale": counts["stale"],
        "conflicted": counts["conflicted"],
        "rejected": counts["rejected"],
        "server_time": utcnow(),
        "results": results,
    }
    response = envelope(data, request_id=request_id, timestamp=timestamp)
    batch_row.response_json = response
    await session.flush()

    return response, False
