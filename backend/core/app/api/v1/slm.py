"""SLM readback proxy endpoint. See docs/backend/api-contract.md section 11.

Auth is ActiveWorkerDep, the same bearer-authenticated active worker the kernel routes use. That
reuse is half of why topology (A) was chosen: the device already holds exactly one backend
credential and no second one has to be distributed for the generation service.

NO ROLE GUARD, and that is a statement rather than an omission. The kernel routes carry none
either (D-1), and the tier decision for readback lives on the device, in SlmScopeGate, which this
change does not touch: the first build is WORKER tier only and the server cannot tell a
worker-tier prompt from a physician-tier one by looking at it, because both are one assembled
string. What the server does do is record the caller's worker_id, role and facility on every
slm_call_log and audit row, so a call from any role is attributable after the fact. A server-side
cadre gate is a separate decision and is recorded as open, not silently assumed.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request

from app.deps import ActiveWorkerDep, SessionDep, SettingsDep, SlmBreakerDep, SlmClientDep
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction
from app.schemas.common import envelope
from app.schemas.slm import SlmReadbackRequest
from app.services import slm as slm_service

router = APIRouter(prefix="/api/v1", tags=["slm"])


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


@router.post(
    "/slm/readback",
    summary="Forward an approved-record readback prompt to the SLM generation service",
)
async def readback(
    body: SlmReadbackRequest,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
    slm_client: SlmClientDep,
    breaker: SlmBreakerDep,
) -> dict[str, Any]:
    # services.slm writes its own specific audit row on every path, out of band, including the
    # path where it raises something that is not a SamdError. Suppress the middleware's generic
    # fallback row so a request has exactly one, not two. (MEASURED, and it is why the service
    # takes the bug path itself: on an unhandled exception the fallback row is not written at
    # all, because Starlette's ServerErrorMiddleware sits outside AuditMiddleware and the
    # middleware's post-response write never runs.)
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    result = await slm_service.readback(
        session,
        worker,
        settings,
        slm_client,
        breaker,
        current_request_id(),
        body.model_dump(),
    )
    return _ok(result)
