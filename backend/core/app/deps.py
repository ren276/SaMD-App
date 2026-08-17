"""Shared FastAPI dependencies: settings, database session, current worker, role guards."""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from typing import Annotated

import httpx
from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.adapters.kernel.circuit_breaker import KernelCircuitBreakers
from app.config import Settings, get_settings
from app.db.session import session_scope
from app.errors import ErrorCode, SamdError
from app.models.enums import UserRole


async def db_session() -> AsyncIterator[AsyncSession]:
    async for session in session_scope():
        yield session


def settings_dep() -> Settings:
    return get_settings()


SettingsDep = Annotated[Settings, Depends(settings_dep)]
SessionDep = Annotated[AsyncSession, Depends(db_session)]


@dataclass(frozen=True)
class CurrentWorker:
    """The authenticated caller, resolved from the access token.

    facility_id comes from the token, never from a request body. It bounds every read and every
    write on every route for every role.
    """

    worker_id: str
    role: str
    facility_id: str
    device_id: str
    must_change_pin: bool


def _bearer_token(request: Request) -> str:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise SamdError(
            ErrorCode.AUTH_TOKEN_INVALID,
            detail="Authorization header must be 'Bearer <token>'.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return token.strip()


async def current_worker(request: Request, settings: SettingsDep) -> CurrentWorker:
    """Resolve and validate the access token.

    Imported lazily to keep app.deps free of a cycle through app.services.auth, which imports
    app.errors and app.models.
    """
    from app.services.auth import decode_token

    claims = decode_token(settings, _bearer_token(request), expected_type="access")
    worker = CurrentWorker(
        worker_id=claims.worker_id,
        role=claims.role,
        facility_id=claims.facility_id,
        device_id=claims.device_id,
        must_change_pin=claims.must_change_pin,
    )
    # The audit middleware reads this off request.state to attribute its fallback row.
    request.state.worker = worker
    return worker


WorkerDep = Annotated[CurrentWorker, Depends(current_worker)]


async def active_worker(worker: WorkerDep) -> CurrentWorker:
    """A worker who has completed the forced initial PIN change (decision D-3).

    Every route uses this except change-pin, me, and logout. Without it, an administrator-issued
    PIN that was never changed would be a working long-lived credential, which is the thing the
    forced change exists to prevent.
    """
    if worker.must_change_pin:
        raise SamdError(
            ErrorCode.AUTH_PIN_CHANGE_REQUIRED,
            detail="Change the initial PIN before using this endpoint.",
        )
    return worker


ActiveWorkerDep = Annotated[CurrentWorker, Depends(active_worker)]


def require_roles(*roles: UserRole) -> Callable[[CurrentWorker], CurrentWorker]:
    """Route guard. Enforcement is here, server side, never in the client's permission list."""
    allowed = {role.value for role in roles}

    def guard(worker: ActiveWorkerDep) -> CurrentWorker:
        if worker.role not in allowed:
            raise SamdError(
                ErrorCode.AUTH_ROLE_FORBIDDEN,
                detail="This role cannot perform this action.",
            )
        return worker

    return guard


def kernel_client_dep(request: Request) -> httpx.AsyncClient:
    """The shared httpx client, built once in the lifespan and held on app.state.

    Read off app.state rather than constructed here: a client created per request leaks
    connections and defeats keep-alive (app/adapters/kernel/client.py).
    """
    return request.app.state.kernel_client  # type: ignore[no-any-return]


def kernel_breakers_dep(request: Request) -> KernelCircuitBreakers:
    return request.app.state.kernel_breakers  # type: ignore[no-any-return]


KernelClientDep = Annotated[httpx.AsyncClient, Depends(kernel_client_dep)]
KernelBreakersDep = Annotated[KernelCircuitBreakers, Depends(kernel_breakers_dep)]
