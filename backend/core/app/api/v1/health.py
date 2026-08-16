"""GET /health. See api-contract.md section 1."""

from __future__ import annotations

import time
from typing import Literal

from fastapi import APIRouter, Response
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.db.base import to_iso, utcnow
from app.db.session import get_sessionmaker
from app.deps import SettingsDep
from app.errors import PROBLEM_CONTENT_TYPE, ErrorCode, http_status_for, problem_body
from app.middleware.request_id import current_request_id, current_timestamp
from app.schemas.health import HealthResponse

router = APIRouter(tags=["health"])

_STARTED_MONOTONIC = time.monotonic()

# Result of the last kernel call, updated by the Phase 3 proxy. A health endpoint that fans out
# to a downstream on every hit is a self-inflicted outage, so this is cached, never probed here.
_kernel_status: Literal["ok", "degraded", "unknown"] = "unknown"

DB_PROBE_TIMEOUT_SECONDS = 2


def record_kernel_status(status: Literal["ok", "degraded"]) -> None:
    """Called by the kernel proxy (Phase 3) after every forwarded call."""
    global _kernel_status
    _kernel_status = status


async def _database_ok() -> bool:
    factory = get_sessionmaker()
    try:
        async with factory() as session:
            # SET does not accept bind parameters in PostgreSQL, hence the interpolated int. The
            # value is an int constant in this module, never request data.
            await session.execute(
                text(f"SET statement_timeout = {DB_PROBE_TIMEOUT_SECONDS * 1000}")
            )
            await session.execute(text("SELECT 1"))
        return True
    except SQLAlchemyError:
        return False


@router.get(
    "/health",
    response_model=HealthResponse,
    summary="Liveness and readiness",
    # Deliberately unprefixed and unenveloped: a load balancer should not need to know the API
    # version, and healthcheck tooling should not need to parse an envelope.
    responses={503: {"description": "Database unreachable"}},
)
async def health(settings: SettingsDep, response: Response) -> HealthResponse | Response:
    healthy = await _database_ok()
    if not healthy:
        return Response(
            status_code=http_status_for(ErrorCode.SYS_DATABASE_UNAVAILABLE),
            content=_problem_json(),
            media_type=PROBLEM_CONTENT_TYPE,
        )

    response.headers["Cache-Control"] = "no-store"
    return HealthResponse(
        version=settings.app_version,
        git_sha=settings.git_sha,
        environment=settings.environment,
        uptime_seconds=int(time.monotonic() - _STARTED_MONOTONIC),
        database="ok",
        kernel=_kernel_status,
        abdm_mode=settings.abdm_mode,
        timestamp=to_iso(utcnow()),
    )


def _problem_json() -> bytes:
    import json

    return json.dumps(
        problem_body(
            ErrorCode.SYS_DATABASE_UNAVAILABLE,
            detail="The database is not reachable.",
            instance="/health",
            request_id=current_request_id(),
            timestamp=current_timestamp(),
        )
    ).encode("utf-8")
