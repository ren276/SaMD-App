"""Authentication endpoints. See api-contract.md section 2."""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.session import get_sessionmaker, write_out_of_band
from app.deps import SessionDep, SettingsDep, WorkerDep
from app.errors import ErrorCode, SamdError
from app.middleware.request_id import current_request_id, current_timestamp
from app.models.enums import AuditAction, AuditOrigin
from app.models.facility import Facility
from app.models.user import UserAccount
from app.schemas.auth import (
    ChangePinRequest,
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
)
from app.schemas.common import envelope
from app.services import audit as audit_service
from app.services import auth as auth_service

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])

# Facility used on audit rows written before a facility is known (an unknown worker_id failing
# login). Keeps those events on a chain instead of dropping them.
UNKNOWN_FACILITY = "UNKNOWN"


def _ok(data: Any) -> dict[str, Any]:
    return envelope(data, request_id=current_request_id(), timestamp=current_timestamp())


async def _facility_name(session: AsyncSession, facility_id: str) -> str:
    name = (
        await session.execute(select(Facility.name).where(Facility.id == facility_id))
    ).scalar_one_or_none()
    return name or facility_id


async def _audit_out_of_band(
    *,
    action: str,
    actor_id: str,
    payload: dict[str, Any],
    device_id: str | None = None,
) -> None:
    """Write an audit row in its own transaction, via app.db.session.write_out_of_band.

    Used only on paths that are about to raise. The request transaction is going to roll back,
    and a failed login that leaves no trace is the opposite of what an audit log is for.

    The facility and role are resolved from the account when it exists, so these events land on
    the worker's own facility chain rather than on a side chain. Only a genuinely unknown
    worker_id falls back to UNKNOWN_FACILITY.
    """

    async def _write(session: AsyncSession) -> None:
        account = (
            await session.execute(select(UserAccount).where(UserAccount.worker_id == actor_id))
        ).scalar_one_or_none()
        await audit_service.append(
            session,
            action=action,
            facility_id=account.facility_id if account else UNKNOWN_FACILITY,
            actor_id=actor_id,
            actor_role=account.role if account else None,
            device_id=device_id,
            request_id=current_request_id(),
            origin=AuditOrigin.SERVER,
            payload=json.dumps(payload, separators=(",", ":"), sort_keys=True),
        )

    await write_out_of_band(_write, context=f"auth.{action}")


@router.post("/login", summary="Authenticate a PHC worker and issue a JWT pair")
async def login(
    body: LoginRequest,
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    # Nothing here logs or echoes the PIN. LoginRequest.pin is redacted by the structlog
    # processor even if a future call site passes the model into a log event.
    request.state.audit_skip = True

    try:
        account = await auth_service.authenticate(
            session, settings, worker_id=body.worker_id, pin=body.pin
        )
    except SamdError as exc:
        await _audit_out_of_band(
            action=AuditAction.WORKER_LOGIN_FAILED.value,
            actor_id=body.worker_id,
            device_id=body.device_id,
            payload={"code": exc.code.value},
        )
        raise

    await auth_service.touch_device(
        session,
        worker_id=account.worker_id,
        device_id=body.device_id,
        app_version=body.app_version,
        environment=body.environment,
    )
    access, refresh, expires_in = await auth_service.issue_token_pair(
        session, settings, account=account, device_id=body.device_id
    )

    await audit_service.append(
        session,
        action=AuditAction.WORKER_LOGIN_SUCCEEDED.value,
        facility_id=account.facility_id,
        actor_id=account.worker_id,
        actor_role=account.role,
        device_id=body.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        payload=json.dumps(
            {"must_change_pin": account.must_change_pin}, separators=(",", ":"), sort_keys=True
        ),
    )

    return _ok(
        {
            "access_token": access,
            "refresh_token": refresh,
            "token_type": "Bearer",
            "expires_in": expires_in,
            "must_change_pin": account.must_change_pin,
            "worker": {
                "worker_id": account.worker_id,
                "display_name": account.display_name,
                "role": account.role,
                "facility_id": account.facility_id,
                "facility_name": await _facility_name(session, account.facility_id),
            },
        }
    )


@router.post("/refresh", summary="Rotate the refresh token and issue a new access token")
async def refresh(
    body: RefreshRequest,
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_skip = True

    try:
        outcome = await auth_service.rotate_refresh(
            session, settings, refresh_token=body.refresh_token, device_id=body.device_id
        )
    except SamdError as exc:
        if exc.code is ErrorCode.AUTH_REFRESH_REVOKED and exc.log_context:
            # Reuse detection already revoked the chain in the request session, which is about to
            # roll back. Redo the revocation and record it durably.
            factory = get_sessionmaker()
            async with factory() as recovery:
                await auth_service.revoke_chain(
                    recovery,
                    worker_id=str(exc.log_context["worker_id"]),
                    device_id=body.device_id,
                    reason="REUSE_DETECTED",
                )
                await recovery.commit()
            await _audit_out_of_band(
                action=AuditAction.REFRESH_REUSE_DETECTED.value,
                actor_id=str(exc.log_context["worker_id"]),
                device_id=body.device_id,
                payload={"jti": exc.log_context.get("jti")},
            )
        raise

    await audit_service.append(
        session,
        action=AuditAction.TOKEN_REFRESHED.value,
        facility_id=outcome.account.facility_id,
        actor_id=outcome.account.worker_id,
        actor_role=outcome.account.role,
        device_id=body.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        payload="{}",
    )

    return _ok(
        {
            "access_token": outcome.access_token,
            "refresh_token": outcome.refresh_token,
            "token_type": "Bearer",
            "expires_in": outcome.expires_in,
        }
    )


@router.post("/logout", summary="Revoke the refresh chain for this device")
async def logout(
    body: LogoutRequest,
    request: Request,
    worker: WorkerDep,
    session: SessionDep,
    settings: SettingsDep,
) -> dict[str, Any]:
    request.state.audit_skip = True

    claims = auth_service.decode_token(settings, body.refresh_token, expected_type="refresh")
    if claims.worker_id != worker.worker_id:
        raise SamdError(
            ErrorCode.AUTH_TOKEN_INVALID,
            detail="Refresh token does not belong to the authenticated worker.",
        )

    await auth_service.revoke_chain(
        session, worker_id=worker.worker_id, device_id=claims.device_id, reason="LOGOUT"
    )
    await audit_service.append(
        session,
        action=AuditAction.WORKER_LOGOUT.value,
        facility_id=worker.facility_id,
        actor_id=worker.worker_id,
        actor_role=worker.role,
        device_id=claims.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        payload="{}",
    )

    # Idempotent: revoking an already-revoked chain is still 200. The outstanding access token is
    # NOT invalidated and remains valid until its one hour expiry. Stateless JWT plus no Redis
    # means no blacklist; the short lifetime is the mitigation, and it is stated openly in
    # backend-prd.md section 5.6 rather than implied to be more than it is.
    return _ok({"revoked": True})


@router.get("/me", summary="Resolve the current token to a worker record")
async def me(worker: WorkerDep, session: SessionDep) -> dict[str, Any]:
    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == worker.worker_id))
    ).scalar_one_or_none()
    if account is None or not account.is_active:
        raise SamdError(ErrorCode.AUTH_ACCOUNT_DISABLED)

    return _ok(
        {
            "worker_id": account.worker_id,
            "display_name": account.display_name,
            "role": account.role,
            "facility_id": account.facility_id,
            "facility_name": await _facility_name(session, account.facility_id),
            "must_change_pin": account.must_change_pin,
            "permissions": auth_service.permissions_for(account.role),
        }
    )


@router.post(
    "/change-pin",
    status_code=status.HTTP_200_OK,
    summary="Change the worker PIN. Forced after the initial administrator-issued PIN.",
)
async def change_pin(
    body: ChangePinRequest,
    request: Request,
    response: Response,
    worker: WorkerDep,
    session: SessionDep,
) -> dict[str, Any]:
    request.state.audit_skip = True

    account = (
        await session.execute(select(UserAccount).where(UserAccount.worker_id == worker.worker_id))
    ).scalar_one_or_none()
    if account is None or not account.is_active:
        raise SamdError(ErrorCode.AUTH_ACCOUNT_DISABLED)

    await auth_service.change_pin(
        session, account=account, current_pin=body.current_pin, new_pin=body.new_pin
    )
    await audit_service.append(
        session,
        action=AuditAction.WORKER_PIN_CHANGED.value,
        facility_id=account.facility_id,
        actor_id=account.worker_id,
        actor_role=account.role,
        device_id=worker.device_id,
        request_id=current_request_id(),
        origin=AuditOrigin.SERVER,
        payload="{}",
    )

    response.headers["Cache-Control"] = "no-store"
    # Every refresh chain for this worker was just revoked, on every device. The client must log
    # in again with the new PIN; returning a fresh pair here would defeat that.
    return _ok({"pin_changed": True, "reauthentication_required": True})
