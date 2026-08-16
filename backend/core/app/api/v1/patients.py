"""Patient endpoints. See api-contract.md section 3.

Every endpoint here writes an audit event, the reads included. Patient identity is the most
sensitive data in the system (hazard H-04, DPDP), and who looked is part of the record.
"""

from __future__ import annotations

import json
from datetime import datetime
from typing import Annotated, Any

from fastapi import APIRouter, Query, Request, Response, status

from app.db.base import to_iso
from app.deps import ActiveWorkerDep, SessionDep
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction, AuditOrigin
from app.schemas.common import envelope
from app.schemas.patient import (
    PatientCreate,
    PatientDetail,
    PatientRoster,
    PatientRosterEntry,
    PatientUpdate,
    PatientWriteResult,
)
from app.services import audit as audit_service
from app.services import patient as patient_service

router = APIRouter(prefix="/api/v1/patients", tags=["patients"])


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


async def _audit(
    session: SessionDep,
    worker: ActiveWorkerDep,
    *,
    action: str,
    patient_id: str | None = None,
    payload: dict[str, Any] | None = None,
) -> None:
    await audit_service.append(
        session,
        action=action,
        facility_id=worker.facility_id,
        actor_id=worker.worker_id,
        actor_role=worker.role,
        device_id=worker.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        patient_id=patient_id,
        payload=json.dumps(payload or {}, separators=(",", ":"), sort_keys=True),
    )


@router.post("", summary="Create a patient, idempotent on the client-generated id")
async def create_patient(
    body: PatientCreate,
    request: Request,
    response: Response,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    patient, created = await patient_service.create_patient(session, worker, body)
    response.status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK

    await _audit(
        session,
        worker,
        action=(
            AuditAction.PATIENT_REGISTERED.value
            if created
            else AuditAction.PATIENT_CREATE_REPLAYED.value
        ),
        patient_id=patient.id,
        payload={"has_abha": patient.abha_number is not None},
    )

    # No patient body in the response. The client already has the record, and sending identity
    # fields back over the wire for no reason widens the exposure surface.
    return _ok(
        PatientWriteResult(
            id=patient.id,
            server_version=patient.server_version,
            created_at=patient.created_at,
            updated_at=patient.updated_at,
            facility_id=patient.facility_id,
        ).model_dump()
    )


@router.get("", summary="Day-scoped or week-scoped roster")
async def list_patients(
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    encounter_from: Annotated[datetime, Query()],
    encounter_to: Annotated[datetime, Query()],
    limit: Annotated[int, Query(ge=1, le=patient_service.MAX_ROSTER_LIMIT)] = (
        patient_service.DEFAULT_ROSTER_LIMIT
    ),
    cursor: Annotated[str | None, Query()] = None,
) -> dict[str, Any]:
    """Both bounds are required and the window is capped at 31 days.

    There is deliberately no way to ask for every patient. That is REQ-ROS-02 and hazard H-04
    surviving the network boundary, not only living on the device, and it is the reason this is
    not a plain GET /patients.
    """
    request.state.audit_skip = True

    page = await patient_service.roster(
        session,
        worker,
        encounter_from=encounter_from,
        encounter_to=encounter_to,
        limit=limit,
        cursor=cursor,
    )
    await _audit(
        session,
        worker,
        action=AuditAction.PATIENT_RECORD_READ.value,
        payload={
            "scope": "roster",
            "encounter_from": to_iso(encounter_from),
            "encounter_to": to_iso(encounter_to),
            "returned": len(page.rows),
        },
    )

    roster = PatientRoster(
        patients=[
            PatientRosterEntry(
                id=row.id,
                full_name=row.full_name,
                age=row.age,
                biological_sex=row.biological_sex,
                last_encounter_at=last_seen,
                server_version=row.server_version,
            )
            for row, last_seen in page.rows
        ],
        next_cursor=page.next_cursor,
    )
    return _ok(roster.model_dump())


@router.get("/{patient_id}", summary="One patient, facility-scoped")
async def get_patient(
    patient_id: str,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_skip = True

    patient = await patient_service.get_patient(session, worker, patient_id)
    await _audit(
        session,
        worker,
        action=AuditAction.PATIENT_RECORD_READ.value,
        patient_id=patient.id,
        payload={"scope": "detail"},
    )
    return _ok(PatientDetail.from_row(patient).model_dump())


@router.patch("/{patient_id}", summary="Partial update with optimistic concurrency")
async def update_patient(
    patient_id: str,
    body: PatientUpdate,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    patient = await patient_service.update_patient(session, worker, patient_id, body)
    changed = sorted(body.model_dump(exclude_unset=True, exclude={"base_version"}))

    await _audit(
        session,
        worker,
        action=AuditAction.PATIENT_UPDATED.value,
        patient_id=patient.id,
        # Field names only. The values are the PHI, and an audit payload is not the place for it.
        payload={"fields": changed, "server_version": patient.server_version},
    )
    return _ok(
        PatientWriteResult(
            id=patient.id,
            server_version=patient.server_version,
            created_at=patient.created_at,
            updated_at=patient.updated_at,
            facility_id=patient.facility_id,
        ).model_dump()
    )
