"""Patient persistence: encryption boundary, blind indexes, facility scoping, ABHA guard.

Every call site outside this module deals in plaintext. Encryption, decryption, and blind-index
maintenance happen here and nowhere else, so a future endpoint cannot forget one of the three.
"""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import Select, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import to_iso
from app.db.types import blind_index
from app.deps import CurrentWorker
from app.errors import ErrorCode, SamdError
from app.models.clinical import CaseRecord
from app.models.encounter import Encounter
from app.models.patient import Patient
from app.schemas.patient import PatientCreate, PatientUpdate

# Fields compared to decide whether a repeated create is the same record or a genuine conflict.
_IDEMPOTENCY_FIELDS = (
    "full_name",
    "date_of_birth",
    "age",
    "biological_sex",
    "guardian_or_spouse_name",
    "guardian_relation",
    "mobile_number",
    "aadhaar_number",
    "abha_number",
    "village",
    "block",
    "district",
    "state",
    "pincode",
    "category",
    "marital_status",
    "blood_group",
    "emergency_contact",
    "primary_care_clinic_name",
    "referring_physician_name",
)

# Immutable once written. id and created_at are the record's identity; facility_id comes from the
# token and is never client-supplied.
IMMUTABLE_FIELDS = frozenset({"id", "created_at", "facility_id"})

MAX_ROSTER_WINDOW = timedelta(days=31)
DEFAULT_ROSTER_LIMIT = 100
MAX_ROSTER_LIMIT = 200


@dataclass(frozen=True)
class RosterPage:
    rows: list[tuple[Patient, datetime]]
    next_cursor: str | None


def _apply_blind_indexes(patient: Patient) -> None:
    """Recompute every blind index from the current plaintext.

    Called on every write. An index left stale after an update is worse than no index: the
    patient silently stops being findable by the value they were just given.
    """
    patient.name_blind_idx = blind_index(patient.full_name)
    patient.mobile_blind_idx = blind_index(patient.mobile_number, digits_only=True)
    patient.aadhaar_blind_idx = blind_index(patient.aadhaar_number, digits_only=True)


async def _assert_abha_unclaimed(
    session: AsyncSession, *, abha_number: str | None, patient_id: str
) -> None:
    """Guard SAMD-PAT-3004.

    Never auto-merge, never reassign. Two patient records claiming one ABHA number is the
    wrong-patient hazard (H-03), and it is resolved by a human, not by whichever write landed
    second. The UNIQUE index is the backstop; this check exists to return the right error code
    instead of an opaque integrity violation.
    """
    if abha_number is None:
        return
    claimed_by = (
        await session.execute(select(Patient.id).where(Patient.abha_number == abha_number))
    ).scalar_one_or_none()
    if claimed_by is not None and claimed_by != patient_id:
        raise SamdError(
            ErrorCode.PAT_DUPLICATE_ABHA,
            detail="This ABHA number is already linked to a different patient.",
            log_context={"abha_claimed_by": claimed_by},
        )


async def get_patient(session: AsyncSession, worker: CurrentWorker, patient_id: str) -> Patient:
    patient = (
        await session.execute(select(Patient).where(Patient.id == patient_id))
    ).scalar_one_or_none()
    if patient is None:
        raise SamdError(ErrorCode.PAT_NOT_FOUND)
    if patient.facility_id != worker.facility_id:
        raise SamdError(
            ErrorCode.AUTH_ROLE_FORBIDDEN,
            detail="This record belongs to another facility.",
        )
    return patient


async def create_patient(
    session: AsyncSession, worker: CurrentWorker, body: PatientCreate
) -> tuple[Patient, bool]:
    """Create, or return the existing row when the same payload is replayed.

    Returns (patient, created). created is False for an idempotent repeat, which the route turns
    into a 200 instead of a 201.
    """
    existing = (
        await session.execute(select(Patient).where(Patient.id == body.id))
    ).scalar_one_or_none()

    if existing is not None:
        if existing.facility_id != worker.facility_id:
            raise SamdError(
                ErrorCode.PAT_ID_CONFLICT,
                detail="This identifier already exists.",
            )
        incoming = body.model_dump()
        divergent = [
            field
            for field in _IDEMPOTENCY_FIELDS
            if incoming.get(field) != getattr(existing, field)
        ]
        if divergent:
            raise SamdError(
                ErrorCode.PAT_ID_CONFLICT,
                detail="This identifier already exists with different data.",
                log_context={"divergent_fields": ",".join(divergent)},
            )
        return existing, False

    await _assert_abha_unclaimed(session, abha_number=body.abha_number, patient_id=body.id)

    values = body.model_dump()
    patient = Patient(**values, facility_id=worker.facility_id, server_version=1)
    _apply_blind_indexes(patient)
    session.add(patient)
    await session.flush()
    return patient, True


async def update_patient(
    session: AsyncSession, worker: CurrentWorker, patient_id: str, body: PatientUpdate
) -> Patient:
    patient = await get_patient(session, worker, patient_id)

    if body.base_version is not None and body.base_version != patient.server_version:
        raise SamdError(
            ErrorCode.SYNC_VERSION_CONFLICT,
            detail="This record was changed since it was last read.",
            log_context={
                "server_version": patient.server_version,
                "base_version": body.base_version,
            },
        )

    # exclude_unset is what makes this a PATCH rather than a PUT: a field the client did not send
    # keeps its stored value instead of being nulled.
    changes = body.model_dump(exclude_unset=True, exclude={"base_version"})

    if "abha_number" in changes:
        await _assert_abha_unclaimed(
            session, abha_number=changes["abha_number"], patient_id=patient.id
        )

    for field, value in changes.items():
        if field in IMMUTABLE_FIELDS:
            raise SamdError(
                ErrorCode.PAT_IMMUTABLE_FIELD,
                detail=f"{field} cannot be modified.",
            )
        setattr(patient, field, value)

    _apply_blind_indexes(patient)
    patient.server_version += 1
    await session.flush()
    return patient


def _roster_query(
    worker: CurrentWorker, start: datetime, end: datetime
) -> Select[tuple[Patient, datetime]]:
    last_encounter = func.max(Encounter.started_at).label("last_encounter_at")
    return (
        select(Patient, last_encounter)
        .join(Encounter, Encounter.patient_id == Patient.id)
        .where(
            Patient.facility_id == worker.facility_id,
            Encounter.started_at >= start,
            Encounter.started_at < end,
        )
        .group_by(Patient.id)
        .order_by(last_encounter.desc(), Patient.id.asc())
    )


def encode_cursor(last_encounter_at: datetime, patient_id: str) -> str:
    raw = f"{to_iso(last_encounter_at)}|{patient_id}".encode()
    return base64.urlsafe_b64encode(raw).decode()


def decode_cursor(cursor: str) -> tuple[datetime, str]:
    try:
        raw = base64.urlsafe_b64decode(cursor.encode()).decode()
        timestamp, _, patient_id = raw.partition("|")
        return datetime.fromisoformat(timestamp.replace("Z", "+00:00")), patient_id
    except (ValueError, UnicodeDecodeError, binascii.Error) as exc:
        raise SamdError(
            ErrorCode.PAT_VALIDATION_FAILED,
            detail="The cursor is not valid.",
            errors=[{"field": "cursor", "message": "Malformed."}],
        ) from exc


async def roster(
    session: AsyncSession,
    worker: CurrentWorker,
    *,
    encounter_from: datetime,
    encounter_to: datetime,
    limit: int,
    cursor: str | None,
) -> RosterPage:
    """Day-scoped or week-scoped roster.

    Both bounds are required and the window is capped, so REQ-ROS-02 and hazard H-04 survive the
    network boundary rather than only existing on the device. There is no code path here that can
    return every patient, and there must never be one.
    """
    if encounter_to <= encounter_from:
        raise SamdError(
            ErrorCode.PAT_VALIDATION_FAILED,
            detail="encounter_to must be after encounter_from.",
            errors=[{"field": "encounter_to", "message": "Must be after encounter_from."}],
        )
    if encounter_to - encounter_from > MAX_ROSTER_WINDOW:
        raise SamdError(
            ErrorCode.PAT_VALIDATION_FAILED,
            detail="The requested window exceeds 31 days.",
            errors=[{"field": "encounter_from", "message": "Window exceeds 31 days."}],
        )

    query = _roster_query(worker, encounter_from, encounter_to)

    if cursor is not None:
        cursor_time, cursor_id = decode_cursor(cursor)
        # Keyset pagination on (last_encounter_at desc, id asc). Applied as HAVING because
        # last_encounter_at is an aggregate.
        query = query.having(
            func.max(Encounter.started_at) < cursor_time,
        ).where(Patient.id != cursor_id)

    rows = list((await session.execute(query.limit(limit + 1))).all())
    has_more = len(rows) > limit
    page = rows[:limit]

    next_cursor = encode_cursor(page[-1][1], page[-1][0].id) if has_more and page else None
    return RosterPage(rows=[(row[0], row[1]) for row in page], next_cursor=next_cursor)


async def case_records_for_patient(session: AsyncSession, patient_id: str) -> list[CaseRecord]:
    return list(
        (
            await session.execute(
                select(CaseRecord)
                .where(CaseRecord.patient_id == patient_id)
                .order_by(CaseRecord.created_at.desc())
            )
        ).scalars()
    )
