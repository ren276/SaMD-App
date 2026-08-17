"""Kernel proxy endpoints. See api-contract.md section 5.

D-1: all four roles may submit to the kernel. The kernel output is never autonomous; it is gated
by the liability acknowledgement on KernelAssessmentScreen and the mandatory doctor
AGREE/MODIFY/REJECT step. No role.require_roles() guard here, same posture as patients.py and
encounters.py's create/update routes, only the DOCTOR-only encounter bundle fetch is restricted.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Request

from app.deps import ActiveWorkerDep, KernelBreakersDep, KernelClientDep, SessionDep, SettingsDep
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction
from app.schemas.common import envelope
from app.schemas.kernel import KernelAssessRequest, KernelEvaluateRequest
from app.services import kernel as kernel_service

router = APIRouter(prefix="/api/v1", tags=["kernel"])


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


@router.post(
    "/assess", summary="Forward a pseudonymized payload to the differential-diagnosis kernel"
)
async def assess(
    body: KernelAssessRequest,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
    kernel_client: KernelClientDep,
    breakers: KernelBreakersDep,
) -> dict[str, Any]:
    # services.kernel writes its own specific audit_events row on every path (success in the
    # request session, failure out of band). Suppress the middleware's generic fallback row.
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    result = await kernel_service.assess(
        session,
        worker,
        settings,
        kernel_client,
        breakers,
        current_request_id(),
        body.model_dump(),
    )
    return _ok(result)


@router.post(
    "/evaluate",
    summary="Forward to the kernel's NLEM treatment and diagnostic-evaluation endpoint",
)
async def evaluate(
    body: KernelEvaluateRequest,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
    kernel_client: KernelClientDep,
    breakers: KernelBreakersDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    result = await kernel_service.evaluate(
        session,
        worker,
        settings,
        kernel_client,
        breakers,
        current_request_id(),
        body.model_dump(exclude_none=True),
    )
    return _ok(result)
