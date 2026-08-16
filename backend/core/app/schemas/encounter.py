"""Encounter and case-record request models. See api-contract.md section 4."""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from app.models.enums import CaseStatus
from app.schemas.common import StrictModel
from app.schemas.patient import PATIENT_ID_PATTERN

CLIENT_ID_MAX = 36


class EncounterCreate(StrictModel):
    id: str = Field(min_length=1, max_length=CLIENT_ID_MAX)
    patient_id: str = Field(pattern=PATIENT_ID_PATTERN)
    started_at: datetime
    follow_up_of_encounter_id: str | None = Field(default=None, max_length=CLIENT_ID_MAX)
    created_at: datetime
    updated_at: datetime


class EncounterWriteResult(BaseModel):
    id: str
    server_version: int


class CaseStatusUpdate(StrictModel):
    """PATCH /api/v1/case-records/{id}/status.

    PENDING_SYNC is accepted from the client on purpose: the device writes it while offline
    (CaseRecordRepository.assignDoctor(isOnline)) and the row can legitimately reach the server
    carrying that state.
    """

    status: CaseStatus
    assigned_doctor_id: str | None = Field(default=None, max_length=CLIENT_ID_MAX)
    updated_at: datetime
    base_version: int | None = Field(default=None, ge=1)


class CaseStatusResult(BaseModel):
    id: str
    status: str
    server_version: int
