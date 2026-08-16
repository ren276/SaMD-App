"""Encounter persistence and the case-record status state machine."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import CurrentWorker
from app.errors import ErrorCode, SamdError
from app.models.attachment import Attachment
from app.models.clinical import Ailment, CaseRecord, Observation
from app.models.encounter import Consultation, Encounter
from app.models.enums import CaseStatus
from app.models.kernel import DiagnosisFeedback, EvaluateReport, KernelReport
from app.models.patient import Patient
from app.models.prescription import MedicationLine, Prescription
from app.schemas.encounter import CaseStatusUpdate, EncounterCreate

# api-contract.md section 4.3. The server, not the client, decides what may follow what.
#
# PENDING_SYNC is reachable from SAVED_LOCALLY because the device writes it while offline and the
# row arrives later carrying that state. PRESCRIPTION_RECEIVED and ABANDONED are terminal:
# a correction after either is a new encounter, never an edit to a closed one
# (docs/data-retention.md).
ALLOWED_TRANSITIONS: dict[CaseStatus, frozenset[CaseStatus]] = {
    CaseStatus.DRAFT: frozenset({CaseStatus.SAVED_LOCALLY, CaseStatus.ABANDONED}),
    CaseStatus.SAVED_LOCALLY: frozenset(
        {CaseStatus.PENDING_SYNC, CaseStatus.SENT_TO_DOCTOR, CaseStatus.ABANDONED}
    ),
    CaseStatus.PENDING_SYNC: frozenset({CaseStatus.SENT_TO_DOCTOR, CaseStatus.ABANDONED}),
    CaseStatus.SENT_TO_DOCTOR: frozenset({CaseStatus.PRESCRIPTION_RECEIVED}),
    CaseStatus.PRESCRIPTION_RECEIVED: frozenset(),
    CaseStatus.ABANDONED: frozenset(),
}


@dataclass(frozen=True)
class EncounterBundle:
    """One encounter with every child row, for GET /api/v1/encounters/{id}."""

    encounter: Encounter
    consultation: Consultation | None
    attachments: list[Attachment]
    observations: list[Observation]
    ailments: list[Ailment]
    case_record: CaseRecord | None
    kernel_report: KernelReport | None
    evaluate_report: EvaluateReport | None
    diagnosis_feedback: DiagnosisFeedback | None
    prescription: Prescription | None
    medication_lines: list[MedicationLine]


async def create_encounter(
    session: AsyncSession, worker: CurrentWorker, body: EncounterCreate
) -> tuple[Encounter, bool]:
    existing = (
        await session.execute(select(Encounter).where(Encounter.id == body.id))
    ).scalar_one_or_none()
    if existing is not None:
        if existing.facility_id != worker.facility_id or existing.patient_id != body.patient_id:
            raise SamdError(
                ErrorCode.PAT_ID_CONFLICT,
                detail="This identifier already exists with different data.",
            )
        return existing, False

    patient = (
        await session.execute(select(Patient).where(Patient.id == body.patient_id))
    ).scalar_one_or_none()
    if patient is None:
        raise SamdError(
            ErrorCode.PAT_NOT_FOUND,
            detail="Push the patient before the encounter, or use /sync/push, which orders the "
            "batch for you.",
        )
    if patient.facility_id != worker.facility_id:
        raise SamdError(
            ErrorCode.AUTH_ROLE_FORBIDDEN, detail="This record belongs to another facility."
        )

    if body.follow_up_of_encounter_id is not None:
        parent = (
            await session.execute(
                select(Encounter).where(Encounter.id == body.follow_up_of_encounter_id)
            )
        ).scalar_one_or_none()
        if parent is None:
            raise SamdError(ErrorCode.ENC_ENCOUNTER_NOT_FOUND)
        # A follow-up must belong to the same patient. Without this check a mistyped id silently
        # links one patient's visit into another patient's history, which is hazard H-03 arriving
        # through the back door.
        if parent.patient_id != body.patient_id:
            raise SamdError(
                ErrorCode.ENC_PATIENT_MISMATCH,
                detail="The follow-up encounter belongs to a different patient.",
            )

    encounter = Encounter(**body.model_dump(), facility_id=worker.facility_id, server_version=1)
    session.add(encounter)
    await session.flush()
    return encounter, True


async def get_encounter_bundle(
    session: AsyncSession, worker: CurrentWorker, encounter_id: str
) -> EncounterBundle:
    encounter = (
        await session.execute(select(Encounter).where(Encounter.id == encounter_id))
    ).scalar_one_or_none()
    if encounter is None:
        raise SamdError(ErrorCode.ENC_ENCOUNTER_NOT_FOUND)
    if encounter.facility_id != worker.facility_id:
        raise SamdError(
            ErrorCode.AUTH_ROLE_FORBIDDEN, detail="This record belongs to another facility."
        )

    consultation = (
        (
            await session.execute(
                select(Consultation).where(Consultation.encounter_id == encounter_id)
            )
        )
        .scalars()
        .first()
    )

    attachments = (
        list(
            (
                await session.execute(
                    select(Attachment)
                    .where(Attachment.consultation_id == consultation.id)
                    .order_by(Attachment.created_at)
                )
            ).scalars()
        )
        if consultation is not None
        else []
    )

    observations = list(
        (
            await session.execute(
                select(Observation)
                .where(Observation.encounter_id == encounter_id)
                .order_by(Observation.recorded_at)
            )
        ).scalars()
    )
    # Soft-deleted ailments are excluded from the bundle but remain in the table
    # (docs/data-retention.md). Nothing here hard-deletes.
    ailments = list(
        (
            await session.execute(
                select(Ailment)
                .where(Ailment.encounter_id == encounter_id, Ailment.deleted_at.is_(None))
                .order_by(Ailment.created_at)
            )
        ).scalars()
    )

    case_record = (
        (await session.execute(select(CaseRecord).where(CaseRecord.encounter_id == encounter_id)))
        .scalars()
        .first()
    )

    kernel_report = evaluate_report = diagnosis_feedback = prescription = None
    medication_lines: list[MedicationLine] = []

    if case_record is not None:
        kernel_report = (
            (
                await session.execute(
                    select(KernelReport).where(KernelReport.case_record_id == case_record.id)
                )
            )
            .scalars()
            .first()
        )
        evaluate_report = (
            (
                await session.execute(
                    select(EvaluateReport).where(EvaluateReport.case_record_id == case_record.id)
                )
            )
            .scalars()
            .first()
        )
        diagnosis_feedback = (
            (
                await session.execute(
                    select(DiagnosisFeedback).where(
                        DiagnosisFeedback.case_record_id == case_record.id
                    )
                )
            )
            .scalars()
            .first()
        )
        prescription = (
            (
                await session.execute(
                    select(Prescription).where(Prescription.case_record_id == case_record.id)
                )
            )
            .scalars()
            .first()
        )
        if prescription is not None:
            medication_lines = list(
                (
                    await session.execute(
                        select(MedicationLine)
                        .where(MedicationLine.prescription_id == prescription.id)
                        .order_by(MedicationLine.position)
                    )
                ).scalars()
            )

    return EncounterBundle(
        encounter=encounter,
        consultation=consultation,
        attachments=attachments,
        observations=observations,
        ailments=ailments,
        case_record=case_record,
        kernel_report=kernel_report,
        evaluate_report=evaluate_report,
        diagnosis_feedback=diagnosis_feedback,
        prescription=prescription,
        medication_lines=medication_lines,
    )


async def update_case_status(
    session: AsyncSession, worker: CurrentWorker, case_id: str, body: CaseStatusUpdate
) -> CaseRecord:
    case = (
        await session.execute(select(CaseRecord).where(CaseRecord.id == case_id))
    ).scalar_one_or_none()
    if case is None:
        raise SamdError(ErrorCode.ENC_CASE_NOT_FOUND)
    if case.facility_id != worker.facility_id:
        raise SamdError(
            ErrorCode.AUTH_ROLE_FORBIDDEN, detail="This record belongs to another facility."
        )

    if body.base_version is not None and body.base_version != case.server_version:
        raise SamdError(
            ErrorCode.SYNC_VERSION_CONFLICT,
            detail="This record was changed since it was last read.",
        )

    current = CaseStatus(case.status)
    target = body.status

    # Re-asserting the current status is a no-op, not an illegal transition. A retried request
    # over a bad link must not look like a state-machine violation.
    if target is not current and target not in ALLOWED_TRANSITIONS[current]:
        raise SamdError(
            ErrorCode.ENC_ILLEGAL_TRANSITION,
            detail=f"A case in {current.value} cannot move to {target.value}.",
        )

    case.status = target.value
    if body.assigned_doctor_id is not None:
        case.assigned_doctor_id = body.assigned_doctor_id
    case.updated_at = body.updated_at
    case.server_version += 1
    await session.flush()
    return case
