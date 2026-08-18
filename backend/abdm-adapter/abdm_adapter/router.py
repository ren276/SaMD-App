"""Internal SaMD workflow endpoints, api-contract.md section 8. Mounted into backend/core's
FastAPI app under `/api/v1/abha/` (see app/main.py). Routes stay thin, matching every other
router in this service (app/api/v1/kernel.py, app/api/v1/sync.py): all state-changing logic and
every audit write lives in service.py.
"""

from __future__ import annotations

from typing import Any

from app.deps import ActiveWorkerDep, SessionDep, SettingsDep
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction
from app.schemas.common import envelope
from fastapi import APIRouter, Request

from abdm_adapter import schemas, service

router = APIRouter(prefix="/api/v1/abha", tags=["abha"])


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


@router.post("/registration-sessions", summary="Start an ABHA registration session")
async def start_registration_session(
    request: Request, worker: ActiveWorkerDep, session: SessionDep
) -> dict[str, Any]:
    # service.start_session already writes its own abha_session_started row.
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    txn = await service.start_session(session, worker)
    return _ok(
        schemas.RegistrationSessionResult(
            session_id=txn.local_transaction_id, state=txn.state, expires_at=txn.expires_at
        ).model_dump()
    )


@router.post(
    "/registration-sessions/{session_id}/identity",
    summary="Submit Aadhaar; backend encrypts and requests an OTP",
)
async def submit_identity(
    session_id: str,
    body: schemas.IdentitySubmit,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    txn = await service.load_transaction(session, worker, session_id)
    masked_mobile = await service.submit_identity(
        session, worker, txn, aadhaar_number=body.aadhaar_number, settings=settings
    )
    return _ok(
        schemas.IdentitySubmitResult(
            session_id=txn.local_transaction_id, state=txn.state, masked_mobile=masked_mobile
        ).model_dump()
    )


@router.post("/registration-sessions/{session_id}/otp", summary="Verify the Aadhaar OTP and enrol")
async def verify_otp(
    session_id: str,
    body: schemas.OtpVerify,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    txn = await service.load_transaction(session, worker, session_id)
    await service.verify_otp(
        session, worker, txn, otp=body.otp, mobile_number=body.mobile_number, settings=settings
    )
    return _ok(
        schemas.OtpVerifyResult(session_id=txn.local_transaction_id, state=txn.state).model_dump()
    )


@router.post(
    "/registration-sessions/{session_id}/mobile-otp",
    summary="Conditional communication-mobile verification",
)
async def verify_mobile_otp(
    session_id: str,
    body: schemas.MobileOtpVerify,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    txn = await service.load_transaction(session, worker, session_id)
    await service.verify_mobile_otp(session, worker, txn, otp=body.otp, settings=settings)
    return _ok(
        schemas.OtpVerifyResult(session_id=txn.local_transaction_id, state=txn.state).model_dump()
    )


@router.get("/registration-sessions/{session_id}", summary="Poll session state")
async def poll_registration_session(
    session_id: str, request: Request, worker: ActiveWorkerDep, session: SessionDep
) -> dict[str, Any]:
    # A status poll is not a clinically meaningful event; no audit row, matching the roster-read
    # posture in app/api/v1/patients.py's list_patients for the same reason.
    request.state.audit_skip = True

    txn = await service.load_transaction(session, worker, session_id)
    return _ok(
        schemas.SessionPollResult(
            session_id=txn.local_transaction_id, state=txn.state, last_error=txn.last_error_detail
        ).model_dump()
    )


@router.get("/registration-sessions/{session_id}/profile", summary="Final verified AbhaIdentity")
async def get_profile(
    session_id: str,
    request: Request,
    worker: ActiveWorkerDep,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_action = AuditAction.REQUEST_COMPLETED.value

    txn = await service.load_transaction(session, worker, session_id)
    identity = await service.fetch_profile(session, worker, txn, settings)
    return _ok(schemas.AbhaIdentity(**identity).model_dump())
