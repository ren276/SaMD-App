"""Encounter and case-record endpoints. See api-contract.md section 4."""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Depends, Request, Response, status

from app.deps import ActiveWorkerDep, CurrentWorker, SessionDep, require_roles
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction, AuditOrigin, UserRole
from app.schemas.common import envelope
from app.schemas.encounter import (
    CaseStatusResult,
    CaseStatusUpdate,
    EncounterCreate,
    EncounterWriteResult,
)
from app.services import audit as audit_service
from app.services import encounter as encounter_service
from app.services.encounter import EncounterBundle

router = APIRouter(prefix="/api/v1", tags=["encounters"])


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


async def _audit(
    session: SessionDep,
    worker: CurrentWorker,
    *,
    action: str,
    patient_id: str | None = None,
    case_record_id: str | None = None,
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
        case_record_id=case_record_id,
        payload=json.dumps(payload or {}, separators=(",", ":"), sort_keys=True),
    )


def _row_to_dict(row: Any, fields: tuple[str, ...]) -> dict[str, Any]:
    return {field: getattr(row, field) for field in fields}


_ENCOUNTER_FIELDS = (
    "id",
    "patient_id",
    "started_at",
    "follow_up_of_encounter_id",
    "created_at",
    "updated_at",
    "server_version",
)
_CONSULTATION_FIELDS = (
    "id",
    "patient_id",
    "encounter_id",
    "chief_complaint",
    "onset",
    "duration_bucket",
    "severity_score",
    "aggravating_factors",
    "relieving_factors",
    "impact_on_daily_activities",
    "relevant_history",
    "transcription",
    "created_at",
    "updated_at",
)
_ATTACHMENT_FIELDS = ("id", "consultation_id", "type", "local_uri", "blob_status", "created_at")
_OBSERVATION_FIELDS = (
    "id",
    "patient_id",
    "encounter_id",
    "type",
    "value_numeric",
    "value_text",
    "unit",
    "device_id",
    "source",
    "capture_method",
    "recorded_at",
    "synced_to_cloud_at",
    "created_at",
)
_AILMENT_FIELDS = (
    "id",
    "patient_id",
    "encounter_id",
    "description",
    "measurement_type",
    "visibility",
    "measured_value",
    "measured_unit",
    "severity",
    "onset",
    "duration",
    "qualifiers",
    "captured_at_offline",
    "synced_to_cloud_at",
    "deleted_at",
    "created_at",
)
_CASE_FIELDS = (
    "id",
    "patient_id",
    "encounter_id",
    "status",
    "assigned_doctor_id",
    "created_at",
    "updated_at",
    "server_version",
)
_KERNEL_FIELDS = (
    "id",
    "case_record_id",
    "predicted_condition",
    "confidence_score",
    "differentials",
    "reasoning_summary",
    "evidence_for",
    "evidence_against",
    "model_version",
    "icd_code",
    "device_id",
    "software_version",
    "data_quality_score",
    "uncertainty_score",
    "risk_category",
    "urgency_level",
    "inference_started_at",
    "inference_ended_at",
    "required_human_verification",
    "inference_source",
)
_EVALUATE_FIELDS = (
    "id",
    "case_record_id",
    "payload_json",
    "inference_started_at",
    "inference_ended_at",
)
_FEEDBACK_FIELDS = (
    "id",
    "case_record_id",
    "icd_candidate",
    "physician_decision",
    "physician_final_diagnosis",
    "clinical_note",
    "created_at",
)
_PRESCRIPTION_FIELDS = (
    "id",
    "patient_id",
    "encounter_id",
    "case_record_id",
    "doctor_id",
    "diagnosis",
    "kernel_decision",
    "created_at",
)
_MEDICATION_LINE_FIELDS = (
    "id",
    "prescription_id",
    "position",
    "generic_name",
    "brand_name",
    "strength",
    "dosage",
    "frequency",
    "route",
    "duration",
    "quantity",
    "food_relation",
    "instructions",
)


def _serialise_bundle(bundle: EncounterBundle) -> dict[str, Any]:
    """Absent children are null or [], never an omitted key.

    A client should never have to tell "this encounter has no prescription" apart from "this
    response forgot to mention prescriptions".
    """
    prescription = None
    if bundle.prescription is not None:
        prescription = _row_to_dict(bundle.prescription, _PRESCRIPTION_FIELDS)
        prescription["medication_lines"] = [
            _row_to_dict(line, _MEDICATION_LINE_FIELDS) for line in bundle.medication_lines
        ]

    return {
        "encounter": _row_to_dict(bundle.encounter, _ENCOUNTER_FIELDS),
        "consultation": (
            _row_to_dict(bundle.consultation, _CONSULTATION_FIELDS)
            if bundle.consultation is not None
            else None
        ),
        "attachments": [_row_to_dict(a, _ATTACHMENT_FIELDS) for a in bundle.attachments],
        "observations": [_row_to_dict(o, _OBSERVATION_FIELDS) for o in bundle.observations],
        # Includes visibility PRIVATE entries. PRIVATE hides an ailment from the worker-facing
        # projection on the device, not from the clinical record (REQ-AIL-02, REQ-AIL-04).
        "ailments": [_row_to_dict(a, _AILMENT_FIELDS) for a in bundle.ailments],
        "case_record": (
            _row_to_dict(bundle.case_record, _CASE_FIELDS)
            if bundle.case_record is not None
            else None
        ),
        "kernel_report": (
            _row_to_dict(bundle.kernel_report, _KERNEL_FIELDS)
            if bundle.kernel_report is not None
            else None
        ),
        "evaluate_report": (
            _row_to_dict(bundle.evaluate_report, _EVALUATE_FIELDS)
            if bundle.evaluate_report is not None
            else None
        ),
        "diagnosis_feedback": (
            _row_to_dict(bundle.diagnosis_feedback, _FEEDBACK_FIELDS)
            if bundle.diagnosis_feedback is not None
            else None
        ),
        "prescription": prescription,
    }


@router.post("/encounters", summary="Register an encounter created on the device")
async def create_encounter(
    body: EncounterCreate,
    request: Request,
    response: Response,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    encounter, created = await encounter_service.create_encounter(session, worker, body)
    response.status_code = status.HTTP_201_CREATED if created else status.HTTP_200_OK

    if created:
        await _audit(
            session,
            worker,
            action=AuditAction.ENCOUNTER_CREATED.value,
            patient_id=encounter.patient_id,
            payload={"encounter_id": encounter.id},
        )

    return _ok(
        EncounterWriteResult(id=encounter.id, server_version=encounter.server_version).model_dump()
    )


@router.get(
    "/encounters/{encounter_id}",
    summary="One encounter with its child rows",
    # DOCTOR only, per the authorization matrix in api-contract.md section 9.3. A field role has
    # no reason to pull a whole encounter back down; the device already holds it.
    dependencies=[Depends(require_roles(UserRole.DOCTOR))],
)
async def get_encounter(
    encounter_id: str,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_skip = True

    bundle = await encounter_service.get_encounter_bundle(session, worker, encounter_id)
    await _audit(
        session,
        worker,
        action=AuditAction.ENCOUNTER_READ.value,
        patient_id=bundle.encounter.patient_id,
        case_record_id=bundle.case_record.id if bundle.case_record else None,
        payload={"encounter_id": encounter_id},
    )
    return _ok(_serialise_bundle(bundle))


@router.patch("/case-records/{case_id}/status", summary="Advance a case record's status")
async def update_case_status(
    case_id: str,
    body: CaseStatusUpdate,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    case = await encounter_service.update_case_status(session, worker, case_id, body)
    await _audit(
        session,
        worker,
        action=AuditAction.CASE_STATUS_CHANGED.value,
        patient_id=case.patient_id,
        case_record_id=case.id,
        payload={"status": case.status, "server_version": case.server_version},
    )
    return _ok(
        CaseStatusResult(
            id=case.id, status=case.status, server_version=case.server_version
        ).model_dump()
    )
