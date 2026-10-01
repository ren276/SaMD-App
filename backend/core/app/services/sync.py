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
import re
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

from sqlalchemy import Date, DateTime, select
from sqlalchemy import inspect as sa_inspect
from sqlalchemy.exc import DataError, IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utcnow
from app.deps import CurrentWorker
from app.domain.audit_actions_device import DEVICE_AUDIT_ACTIONS
from app.domain.kernel_identity import (
    DERIVATION_RULE_VERSION_RE,
    MODEL_VERSION_RE,
    REQUEST_ID_RE,
)
from app.errors import ErrorCode, SamdError
from app.logging import get_logger
from app.models.abha import AbhaProfile
from app.models.attachment import Attachment
from app.models.clinical import Ailment, CaseRecord, Observation
from app.models.encounter import Consultation, Encounter
from app.models.enums import AuditAction, AuditOrigin, SyncRetryClass
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

logger = get_logger(__name__)

# Columns SyncMixin adds that a client may never set directly; always excluded from every table's
# client-writable set regardless of what else a table's TableSpec declares.
_SYNC_MIXIN_OWNED = frozenset(
    {"facility_id", "server_version", "received_at", "sync_state", "client_updated_at"}
)

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

# sqlstate -> SyncRetryClass. The one reject site in this module that is not uniformly TERMINAL.
#
# 23503 (foreign key) is the row this whole change exists for. A child whose parent has not landed
# yet is REJECTED TERMINALLY TODAY, which permanently destroys a row that would have synced on the
# next drain once the parent arrived. push() sorts a batch by table rank, so a parent in the SAME
# batch always applies first; a 23503 therefore means the parent genuinely is not on the server
# yet, which is a fact about the world that changes, not a fact about this record.
#
# 23505 (unique) is CONFLICT, not "sometimes retryable". MEASURED: exactly two unique constraints
# in the whole schema are reachable from a client-pushed row, `ix_patients_abha_number` and
# `uq_medication_lines_position`; every other unique index is on a server-generated column
# (audit_events.sequence) or is handled inside _apply_audit_log and never reaches this site. The
# patients one is the duplicate-ABHA case and needs a person to decide which patient is right.
# The medication-lines one is theoretically transient (two lines swapping positions inside one
# prescription, in one batch, where within-table order is the array order the device sent), but
# the device does not currently produce that shape, and a sqlstate cannot tell the two apart.
# Classifying the whole sqlstate as CONFLICT is therefore no worse than today for the second case
# and correct for the first. If the second ever matters, the upgrade is per-CONSTRAINT-NAME
# classification here, not a fourth retry class.
#
# 23502 (not null) and 23514 (check) are TERMINAL: the row omits a required column or carries a
# value the schema forbids, and neither changes by waiting.
_SQLSTATE_RETRY_CLASSES = {
    "23502": SyncRetryClass.TERMINAL,
    "23503": SyncRetryClass.RETRYABLE,
    "23505": SyncRetryClass.CONFLICT,
    "23514": SyncRetryClass.TERMINAL,
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
    # data key -> closed format. A device-supplied string with a closed vocabulary is checked here
    # and rejected, never normalised and never stored verbatim: the backend does not rewrite
    # device-owned data. None passes (the column is nullable); anything else must be a string that
    # matches. The rejection message names the field and never echoes the value.
    formats: dict[str, re.Pattern[str]] = field(default_factory=dict)


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
    "kernel_reports": TableSpec(
        13,
        KernelReport,
        formats={
            "request_id": REQUEST_ID_RE,
            "derivation_rule_version": DERIVATION_RULE_VERSION_RE,
            "model_version": MODEL_VERSION_RE,
        },
    ),
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


def _resolve_write(
    stored_ts: datetime | None,
    stored_version: int,
    incoming_ts: datetime,
    base_version: int | None,
    *,
    same_content: bool = True,
) -> str:
    """Decide what an incoming write to an EXISTING row does: "stale", "conflict" or "apply".

    Compares the incoming client_updated_at with the STORED client_updated_at and uses
    base_version and server_version. No other column takes part. Evaluated in this order:

    1. Stored client_updated_at is set and equals the incoming one: "stale" when the content is
       also the same. An exact replay of a write already applied (a retried batch, a lost ack
       resent under a new batch_id). Stale means exactly this, which is why SYNCED on the device
       is truthful. It comes BEFORE the base_version check, so a resend whose base_version has
       since moved on is still stale, not a conflict. The SAME timestamp with DIFFERENT content
       (two devices writing a natural-key row in the same millisecond, or two saves of one row in
       the same millisecond) is a "conflict": calling it stale would tell the device its write was
       applied when it was not, and the data would be lost without a signal.
    2. base_version present and different from server_version: "conflict".
    3. base_version present and equal to server_version: "apply". A matching base_version proves
       the write was made on top of the latest server state, so no wall-clock comparison runs and a
       device clock that runs behind cannot lose a write.
    4. base_version absent: last-write-wins on client_updated_at. A stored NULL (a row from before
       alembic 0009) or a later incoming value applies. An EARLIER incoming value is "conflict",
       not stale: the write is genuinely older than what the server holds, the device must not be
       told it is synced, and the server keeps the newer data.

    A new row (no existing row) never reaches here: it is inserted and stores its own timestamp.
    """
    if stored_ts is not None and incoming_ts == stored_ts:
        return "stale" if same_content else "conflict"
    if base_version is not None:
        return "apply" if base_version == stored_version else "conflict"
    if stored_ts is None or incoming_ts > stored_ts:
        return "apply"
    return "conflict"


def _constraint_sqlstate(exc: IntegrityError | DataError) -> str | None:
    sqlstate = getattr(exc.orig, "sqlstate", None)
    return sqlstate if isinstance(sqlstate, str) else None


def _constraint_message(exc: IntegrityError | DataError) -> str:
    sqlstate = _constraint_sqlstate(exc)
    if sqlstate is None:
        return "the record could not be applied."
    return _SQLSTATE_MESSAGES.get(sqlstate, "the record could not be applied.")


def _constraint_retry_class(exc: IntegrityError | DataError) -> SyncRetryClass:
    """An sqlstate this module does not recognise, or a driver that supplied none, is TERMINAL.

    TERMINAL is the conservative default here specifically because it is what this site did for
    every sqlstate before this change: an unrecognised constraint failure keeps exactly today's
    behaviour rather than silently gaining an unbounded retry for a class nobody has reasoned
    about. Optimism belongs on the classes that were argued, not on the default.
    """
    sqlstate = _constraint_sqlstate(exc)
    if sqlstate is None:
        return SyncRetryClass.TERMINAL
    return _SQLSTATE_RETRY_CLASSES.get(sqlstate, SyncRetryClass.TERMINAL)


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


def _reject(
    table: str,
    record_id: str,
    code: ErrorCode,
    message: str,
    retry_class: SyncRetryClass,
) -> dict[str, Any]:
    """Build one `rejected` result.

    `retry_class` is a REQUIRED positional parameter with no default, deliberately. A default
    would let a new reject site omit it and silently inherit someone else's decision, and this
    function is the only place a `rejected` result is constructed, so the signature itself is the
    guarantee that no path can answer without classifying. `tests/test_sync_retry_class_mirror.py`
    then checks the values; the compiler-equivalent check is here.
    """
    return {
        "table": table,
        "id": record_id,
        "status": "rejected",
        "code": code.value,
        "message": message,
        "retry_class": retry_class.value,
    }


def _server_state(spec: TableSpec, row: Any) -> dict[str, Any]:
    return {key: getattr(row, attr) for key, attr in _attr_map(spec).items()}


def _same_content(existing: Any, incoming: dict[str, Any]) -> bool:
    """True when every column the incoming write carries already holds that value.

    Compared over the incoming keys only: an identical replay carries the same keys, and a payload
    that omits an optional field (a null is omitted on the wire) must not read as a difference.
    Values are the coerced ones (aware datetimes, parsed JSON), and encrypted columns are already
    decrypted on the loaded row, so plain equality is the right comparison."""
    return all(getattr(existing, attr) == value for attr, value in incoming.items())


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
            # A field the device must never send. Same bytes, same answer, forever.
            return _reject(
                table, record_id, code, f"{key}: forbidden field.", SyncRetryClass.TERMINAL
            )

    for key, pattern in spec.formats.items():
        value = data.get(key)
        if value is not None and not (isinstance(value, str) and pattern.fullmatch(value)):
            # TERMINAL: the same bytes can never become valid. The value is not echoed.
            return _reject(
                table,
                record_id,
                ErrorCode.SYNC_RECORD_INVALID,
                f"{key}: malformed.",
                SyncRetryClass.TERMINAL,
            )

    if table == "referrals":
        sending = data.get("sending_phc_id")
        if sending is not None and sending != worker.facility_id:
            return _reject(
                table,
                record_id,
                ErrorCode.SYNC_RECORD_INVALID,
                "sending_phc_id: does not match this facility.",
                # A rule about this record against the authenticated session. Unchanged bytes
                # from this device can never satisfy it.
                SyncRetryClass.TERMINAL,
            )

    attr_map = _attr_map(spec)
    unknown = set(data) - set(attr_map)
    if unknown:
        bad = sorted(unknown)[0]
        # TERMINAL, with a known limitation recorded in scratchpad/s1-ack-contract.md: a device
        # shipped AHEAD of the backend, sending a column the backend's mapper does not have yet,
        # would also land here and be destroyed rather than waiting for the backend migration.
        # Judged far more likely to be a device defect than a rollout skew, unlike the audit
        # action vocabulary below, where the device is expected to lead. Owner may overrule.
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            f"{bad}: unexpected field.",
            SyncRetryClass.TERMINAL,
        )

    model = spec.model
    pk_column = getattr(model, spec.pk_attr)
    existing = (
        await session.execute(select(model).where(pk_column == record_id))
    ).scalar_one_or_none()

    if existing is not None and existing.facility_id != worker.facility_id:
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "id: belongs to another facility.",
            # A primary key another facility already holds. Not CONFLICT: there is no resolution
            # available to this worker, who cannot see or re-key the other facility's row.
            SyncRetryClass.TERMINAL,
        )

    columns = sa_inspect(model).columns
    incoming = {
        attr_map[key]: _coerce_value(columns[attr_map[key]].type, value)
        for key, value in data.items()
    }
    if table == "evaluate_reports" and isinstance(incoming.get("payload_json"), str):
        try:
            incoming["payload_json"] = json.loads(incoming["payload_json"])
        except json.JSONDecodeError:
            return _reject(
                table,
                record_id,
                ErrorCode.SYNC_RECORD_INVALID,
                "payload_json: invalid JSON.",
                SyncRetryClass.TERMINAL,
            )

    if existing is not None:
        decision = _resolve_write(
            existing.client_updated_at,
            existing.server_version,
            client_updated_at,
            base_version,
            # Only read when the timestamps are equal, where it decides stale versus conflict.
            same_content=(
                existing.client_updated_at != client_updated_at or _same_content(existing, incoming)
            ),
        )
        if decision == "conflict":
            return {
                "table": table,
                "id": record_id,
                "status": "conflict",
                "server_state": _server_state(spec, existing),
            }
        if decision == "stale":
            return {
                "table": table,
                "id": record_id,
                "status": "stale",
                "server_version": existing.server_version,
            }

    attrs = dict(incoming)
    # Server-owned: stored from the envelope, never accepted from the payload.
    attrs["client_updated_at"] = client_updated_at
    if "synced_to_cloud_at" in spec.server_owned:
        attrs["synced_to_cloud_at"] = utcnow()

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
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            f"{bad}: unexpected field.",
            SyncRetryClass.TERMINAL,
        )

    action = data.get("action")
    if action not in _DEVICE_AUDIT_ACTION_VALUES:
        # RETRYABLE, and the one correction to the diagnosis's own sixteen. DEVICE_AUDIT_ACTIONS
        # is this backend's copy of a vocabulary the DEVICE owns and is expected to grow; a device
        # rolled out ahead of the backend sends an action this build has not learned yet. Terminal
        # rejection there destroys an append-only audit row permanently and leaves a hole in the
        # chain, and the remedy (upgrade the backend) needs nothing from the device: the identical
        # bytes then apply. This is the same mirror the AuditActionBackendMirrorTest guards, and
        # the same integrity class S-5 found in the ABDM adapter.
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "action: unknown audit action.",
            SyncRetryClass.RETRYABLE,
        )

    try:
        occurred_at = _parse_datetime(data.get("timestamp"))
    except (TypeError, ValueError):
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "timestamp: invalid timestamp.",
            SyncRetryClass.TERMINAL,
        )

    user_id = data.get("user_id")
    if not isinstance(user_id, str) or not user_id:
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "user_id: required.",
            SyncRetryClass.TERMINAL,
        )

    payload = data.get("payload", "{}")
    if not isinstance(payload, str):
        return _reject(
            table,
            record_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "payload: must be a string.",
            SyncRetryClass.TERMINAL,
        )

    for key in ("patient_id", "case_record_id"):
        if key in data and data[key] is not None and not isinstance(data[key], str):
            return _reject(
                table,
                record_id,
                ErrorCode.SYNC_RECORD_INVALID,
                f"{key}: must be a string.",
                SyncRetryClass.TERMINAL,
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
        return _reject(
            table, safe_id, ErrorCode.SYNC_RECORD_INVALID, "id: required.", SyncRetryClass.TERMINAL
        )
    if op not in ("upsert", "insert"):
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "op: unsupported operation.",
            SyncRetryClass.TERMINAL,
        )
    if not isinstance(data, dict):
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "data: required.",
            SyncRetryClass.TERMINAL,
        )
    if base_version is not None and not isinstance(base_version, int):
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "base_version: must be an integer.",
            SyncRetryClass.TERMINAL,
        )
    try:
        client_updated_at = _parse_datetime(raw.get("client_updated_at"))
    except (TypeError, ValueError):
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            "client_updated_at: invalid timestamp.",
            SyncRetryClass.TERMINAL,
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
        # The only site that is not uniformly TERMINAL. See _SQLSTATE_RETRY_CLASSES.
        return _reject(
            table,
            safe_id,
            ErrorCode.SYNC_RECORD_INVALID,
            _constraint_message(exc),
            _constraint_retry_class(exc),
        )
    except SQLAlchemyError:
        # A database-level failure other than a constraint (a dead connection, a deadlock, a
        # server shutting down) means the session itself may be unusable. It fails the WHOLE batch
        # so the device retries the whole batch, rather than marking one record against a broken
        # session.
        raise
    except Exception as exc:
        # An application bug while applying ONE record. Its savepoint has already rolled back, so
        # the rest of the batch is untouched, and the batch must not be 500ed for it: before this,
        # one such record returned 500 for the whole batch, rolled back every other record and the
        # idempotency row, and the device resent the same batch for ever.
        # RETRYABLE, not TERMINAL: a server defect that a deploy can fix must not condemn
        # recoverable clinical data. The device retries the record up to MAX_SYNC_ATTEMPTS, then
        # shows it as failed with "Send again". Only the exception CLASS is logged: a message can
        # echo patient content.
        logger.error(
            "sync_record_unexpected_error",
            table=table,
            record_id=safe_id,
            error_class=type(exc).__name__,
        )
        return _reject(
            table,
            safe_id,
            ErrorCode.SYS_INTERNAL,
            "server error applying this record.",
            SyncRetryClass.RETRYABLE,
        )

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
